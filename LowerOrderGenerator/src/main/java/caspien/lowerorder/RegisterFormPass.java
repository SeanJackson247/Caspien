package caspien.lowerorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Register-form pass ("deferred operands") -- runs LAST, on the final
 * low-order bytecode, and only when compiler.config says
 * "deferred-operands: on".
 *
 * <p>The low-order bytecode is a pure stack machine: every operand is
 * pushed, every operator pops and pushes. This pass keeps a compile-time
 * model of that operand stack instead of emitting the pushes: a constant,
 * a variable's value (still in its frame slot), an address or a value
 * held in a temp register is just remembered, and is only turned into
 * code when something consumes it. Operators whose operands are all
 * remembered are emitted as three-address "register-form" instructions:
 *
 * <pre>
 *   R_MOV  8 dst src           dst = src            (dst: %tN or $off, src: #imm, $off or %tN)
 *   R_LD   n %tD addr          %tD = n bytes at addr, zero-extended  (addr: $off frame slot, &amp;sym global, %tN pointer)
 *   R_ST   n addr src          n bytes at addr = src (addr as above, src: #imm or %tN)
 *   R_LEA  %tD base idx scale  %tD = base + idx*scale  (base: $off address of a slot, &amp;sym, %tN; idx: #imm, $off value, %tN)
 *   R_BIN  OP size %tD a b     %tD = a OP b         (OP: ADD SUB MUL AND OR EQ NEQ LT LT_EQ GT GT_EQ SLT SLT_EQ SGT SGT_EQ)
 *   R_UN   OP size %tD a       %tD = OP a           (OP: INC DEC NEG NOT)
 *   R_FBIN OP size %tD a b     float %tD = a OP b   (OP: ADD SUB MUL DIV; operands are raw bit patterns)
 *   R_FCMP OP size %tD a b     %tD = a OP b (0/1)   (OP: EQ NEQ LT LT_EQ GT_EQ GT)
 *   R_RMW  OP 8 $x b           $x OP= b             (OP: ADD SUB INC DEC -- one memory-operand instruction)
 *   R_ARG  n size src          integer argument register n = src
 *   R_ARGA n addr              integer argument register n = address ($off or &amp;sym)
 *   R_FARG n size src          float argument register n = src bits
 *   R_RET  src / R_RETF src    return src in rax / xmm0 (then the function epilogue)
 *   R_BRF  src @label          jump if src == 0  (replaces CMP + JMP)
 *   R_PUSH 8 src               push src (#imm, $off or %tN) on the real stack (flush only)
 *   R_PUSHA 8 addr             push an address ($off or &amp;sym) on the real stack (flush only)
 *   R_SETV n %vK src           (written only by RegVarPromotionPass) variable register = n bytes of src, zero-extended
 * </pre>
 *
 * Operands: {@code #imm} an immediate (a float constant is its IEEE bit
 * pattern), {@code $off} a frame slot, {@code &sym} a global, {@code %tN} a
 * temporary register (N below {@link #TEMP_COUNT}); the backend maps temps to
 * real registers. This pass never keeps a variable in a register -- only temporaries. (The separate
 * {@link RegVarPromotionPass}, which runs after this one when "variables-in-registers: on", renames the frame-slot
 * operands of hot scalar locals to variable registers "%vN" in this pass's output.)
 * A temp holding a value narrower than 8 bytes is always zero-extended to 64
 * bits, exactly like a word pushed by the stack form.
 *
 * <p><b>Safety net 1 -- flush.</b> Before ANY instruction this pass does not
 * fuse, every remembered entry is written back out, bottom to top, as the
 * original PUSH/ADDR line (temps as R_PUSH), and the instruction is then
 * emitted unchanged. So temporaries never live across a label, jump, call,
 * CC_*, LOOKUP*, NEW*, atomics, or any mnemonic this pass has never heard of.
 *
 * <p><b>Safety net 2 -- rollback.</b> A "region" runs from one point where
 * the model is empty to the next. A region that uses anything beyond plain
 * 8-byte integers (narrow values, floats, pointers, globals, computed
 * addresses, argument registers) is "risky": a stack word written back from
 * such a value would not necessarily be the word the original code pushed
 * (construction runs, small-array tagging). So if a risky region hits
 * something it cannot fuse while entries are still remembered, the whole
 * region is discarded and its ORIGINAL lines are emitted instead. Every
 * fusion is therefore all-or-nothing per region, and the output of a region
 * that cannot be fused completely is exactly the stack code.
 *
 * <p>Hazards handled at fusion time: a deferred read of a frame slot is
 * loaded into a temp before any store that could overwrite it (an exact
 * overlap test for a store to a known slot; every deferred read for a store
 * through a computed pointer).
 *
 * <p><b>Extension points.</b> Entries carry a size, operand text is produced in one place ({@link #operandOf}), and
 * fusable mnemonics live in the {@link #handlers} table. Promoted-variable registers turned out not to need any change
 * here: every operand this pass emits for a frame slot is already a plain "$off" text, so {@link RegVarPromotionPass}
 * can rename them afterwards, on the final text, where a slot with any mention it cannot rewrite is simply left alone.
 */
public class RegisterFormPass implements OptimizationPass {

    /** How many temporaries exist; the backend maps %t0..%t{N-1} to registers. */
    public static final int TEMP_COUNT = 4;

    enum Kind {
        /** a constant (integer, or a float's bit pattern) */ K,
        /** a value still sitting in a frame slot (size 1/2/4/8) */ M,
        /** an address: of a frame slot ("$off") or of a global ("&sym") */ A,
        /** a value held in a temp register, zero-extended to 64 bits */ T
    }

    static final class Entry {
        Kind kind;
        /** the original line (K, M, A) so a flush can re-emit it verbatim */
        List<BytecodeToken> orig;
        /** K: literal text; M: the "$off" text; A: "$off" or "&sym" */
        String text;
        /** M/A(frame): parsed frame offset */
        long off;
        /** T: which temp */
        int temp = -1;
        /** width in bytes of an M value or a loaded T (arithmetic results are 8) */
        int size = 8;
        /** A only: the address is a global symbol */
        boolean global;
        /** T only: the register holds the value ZERO-EXTENDED to 64 bits (a load of `size` bytes). Arithmetic results are not: a narrow ADD leaves carry bits above its width. */
        boolean zx;
    }

    /** One fusable mnemonic. Returns how many input lines it consumed, or 0 to decline (caller flushes/rolls back and passes through). A handler must not emit anything before it returns 0. */
    interface Handler {
        int tryFuse(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx);
    }

    private final Map<String, Handler> handlers = new HashMap<>();

    /** Names declared by GLOBAL / ALLOC_STATIC lines of the program being processed (refilled by every run). */
    private final java.util.Set<String> globalNames = new java.util.HashSet<>();

    private static final Map<String, String> BIN_OPS = new HashMap<>();
    static {
        BIN_OPS.put("ADD_INT", "ADD");
        BIN_OPS.put("SUB_INT", "SUB");
        BIN_OPS.put("MUL_INT", "MUL");
        BIN_OPS.put("EQ_INT", "EQ");
        BIN_OPS.put("NEQ_INT", "NEQ");
        BIN_OPS.put("LT_INT", "LT");
        BIN_OPS.put("LT_EQ_INT", "LT_EQ");
        BIN_OPS.put("GT_INT", "GT");
        BIN_OPS.put("GT_EQ_INT", "GT_EQ");
        BIN_OPS.put("SLT_INT", "SLT");
        BIN_OPS.put("SLT_EQ_INT", "SLT_EQ");
        BIN_OPS.put("SGT_INT", "SGT");
        BIN_OPS.put("SGT_EQ_INT", "SGT_EQ");
        BIN_OPS.put("AND", "AND");
        BIN_OPS.put("OR", "OR");
        // The bitwise group, every operand width, constant or variable shift count (a variable count goes through %rcx in the backend,
        // saved and restored around the shift). SHR/BITS_AND are also what StrengthReductionPass makes of x / 2^n and x % 2^n.
        BIN_OPS.put("SHL", "SHL");
        BIN_OPS.put("SHR", "SHR");
        BIN_OPS.put("SAR", "SAR");
        BIN_OPS.put("ROTL", "ROTL");   // bits_rotl / bits_rotr: rol / ror, count modulo the width
        BIN_OPS.put("ROTR", "ROTR");
        BIN_OPS.put("BITS_AND", "BAND");
        BIN_OPS.put("BITS_OR", "BOR");
        BIN_OPS.put("BITS_XOR", "BXOR");
    }

    private static final Map<String, String> FLOAT_BIN = new HashMap<>();
    private static final Map<String, String> FLOAT_CMP = new HashMap<>();
    static {
        FLOAT_BIN.put("ADD_FLOAT", "ADD");
        FLOAT_BIN.put("SUB_FLOAT", "SUB");
        FLOAT_BIN.put("MUL_FLOAT", "MUL");
        FLOAT_BIN.put("DIV_FLOAT", "DIV");
        FLOAT_CMP.put("EQ_FLOAT", "EQ");
        FLOAT_CMP.put("NEQ_FLOAT", "NEQ");
        FLOAT_CMP.put("LT_FLOAT", "LT");
        FLOAT_CMP.put("LT_EQ_FLOAT", "LT_EQ");
        FLOAT_CMP.put("GT_EQ_FLOAT", "GT_EQ");
        FLOAT_CMP.put("GT_FLOAT", "GT");
    }

    public RegisterFormPass() {
        handlers.put("PUSH", this::fusePush);
        handlers.put("ADDR", this::fuseAddr);
        for (String m : BIN_OPS.keySet()) {
            handlers.put(m, this::fuseBinary);
        }
        handlers.put("DIV_INT", this::fuseDivConst);
        handlers.put("MOD_INT", this::fuseDivConst);
        handlers.put("INC_INT", this::fuseUnary);
        handlers.put("DEC_INT", this::fuseUnary);
        handlers.put("NEG", this::fuseUnary);
        handlers.put("NOT", this::fuseUnary);
        handlers.put("BITS_NOT", this::fuseUnary);
        handlers.put("ASSIGN", this::fuseAssign);
        handlers.put("CMP", this::fuseCmpJmp);
        for (String m : FLOAT_BIN.keySet()) {
            handlers.put(m, this::fuseFloatBin);
        }
        for (String m : FLOAT_CMP.keySet()) {
            handlers.put(m, this::fuseFloatCmp);
        }
        handlers.put("DEREF", this::fuseDeref);
        handlers.put("LEN", this::fuseLen);
        handlers.put("LOOKUP_DYN", this::fuseLookupDyn);
        handlers.put("LOOKUP_DYN_LHS", this::fuseLookupDyn);
        handlers.put("ZEXT", this::fuseZext);
        handlers.put("TRUNC", this::fuseTrunc);
        handlers.put("LOOKUP_ARRAY_LHS", this::fuseLookupArrayLhs);
        handlers.put("LOOKUP_ARRAY", this::fuseLookupPtrRead);
        handlers.put("PUSH_FIELDNAME", this::fuseFieldName);
        handlers.put("DOT_LHS", this::fuseDotLhs);
        handlers.put("POP", this::fusePop);
        handlers.put("RET", this::fuseRet);
        handlers.put("RET_FLOAT", this::fuseRet);
    }

    @Override
    public String name() {
        return "register-form";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        globalNames.clear();
        for (List<BytecodeToken> l : lines) {
            if (l.size() >= 2 && (l.get(0).text.equals("GLOBAL") || l.get(0).text.equals("ALLOC_STATIC"))) {
                // Dotted names are member aliases into a parent block; the backend's alias handling stays on the stack path.
                if (l.get(1).text.indexOf('.') < 0) {
                    globalNames.add(l.get(1).text);
                }
            }
        }
        State st = new State();
        boolean changed = false;
        int i = 0;
        while (i < lines.size()) {
            List<BytecodeToken> line = lines.get(i);
            if (line.isEmpty()) {
                st.out.add(line);
                i++;
                continue;
            }
            Handler h = handlers.get(line.get(0).text);
            int consumed = h == null ? 0 : h.tryFuse(st, line, lines, i);
            if (consumed > 0) {
                changed = true;
                i += consumed;
            } else {
                if (st.risky && !st.stack.isEmpty() && !(flushIsFaithful(line) && st.plainWords())) {
                    st.rollback(lines, i);
                } else {
                    st.flush();
                }
                st.out.add(line);
                i++;
            }
            if (st.stack.isEmpty()) {
                // A region boundary: nothing is remembered, so everything before this point is final.
                Arrays.fill(st.tempUsed, false);
                st.cpIn = i;
                st.cpOut = st.out.size();
                st.risky = false;
            }
        }
        if (st.risky && !st.stack.isEmpty()) {
            st.rollback(lines, lines.size());
        } else {
            st.flush();
        }
        return new PassResult(st.out, changed);
    }

    /**
     * Mnemonics in front of which a RISKY region may still be flushed instead of rolled back (when
     * {@link State#plainWords} holds): a call bracket, and the plain stack arithmetic ops. For these the stack words
     * a flush writes are exactly the words the original code pushed (every remembered entry is one 8-byte word), and
     * the backend does not look at the lines before them (unlike ASSIGN / NEW / LOOKUP / DOT, which find a struct
     * or array construction run by scanning back). Without this, one call in the middle of a statement
     * (an inlined sqrt under a pending "e = e - ...") threw away the whole region, including the loads already fused.
     */
    private static boolean flushIsFaithful(List<BytecodeToken> line) {
        String m = line.get(0).text;
        return m.equals("CC_START") || m.endsWith("_FLOAT") || m.endsWith("_INT")
                || m.equals("SHL") || m.equals("SHR") || m.equals("SAR") || m.equals("ROTL") || m.equals("ROTR") || m.startsWith("BITS_");
    }

    // ------------------------------------------------------------------
    // State: the abstract operand stack, the temp pool, and the output.
    // ------------------------------------------------------------------

    static final class State {
        final List<List<BytecodeToken>> out = new ArrayList<>();
        final List<Entry> stack = new ArrayList<>();
        final boolean[] tempUsed = new boolean[TEMP_COUNT];
        private BytecodeToken anchor; // any token, for file/line of synthesized lines
        /** region start: the input line index and output size at the last empty-model point */
        int cpIn = 0;
        int cpOut = 0;
        /** the current region uses something a plain stack flush could not reproduce faithfully */
        boolean risky = false;

        int freeTemps() {
            int n = 0;
            for (boolean u : tempUsed) {
                if (!u) n++;
            }
            return n;
        }

        int allocTemp() {
            for (int t = 0; t < TEMP_COUNT; t++) {
                if (!tempUsed[t]) {
                    tempUsed[t] = true;
                    return t;
                }
            }
            return -1;
        }

        void freeTemp(int t) {
            tempUsed[t] = false;
        }

        void emit(String... texts) {
            out.add(mk(texts));
        }

        List<BytecodeToken> mk(String... texts) {
            List<BytecodeToken> l = new ArrayList<>(texts.length);
            BytecodeToken a = anchor;
            for (String t : texts) {
                l.add(new BytecodeToken(t, a == null ? "<register-form>" : a.file, a == null ? 0 : a.line, BytecodeToken.Kind.CODE));
            }
            return l;
        }

        /** every remembered entry is one plain 8-byte word (a constant, a frame value, an address, a full-width temp) */
        boolean plainWords() {
            for (Entry e : stack) {
                if (e.size != 8) {
                    return false;
                }
            }
            return true;
        }

        /** Discards this region's output and emits its original input lines [cpIn, upTo) instead. */
        void rollback(List<List<BytecodeToken>> all, int upTo) {
            out.subList(cpOut, out.size()).clear();
            for (int k = cpIn; k < upTo; k++) {
                out.add(all.get(k));
            }
            stack.clear();
            Arrays.fill(tempUsed, false);
        }

        /** Writes every remembered entry back out, bottom to top, then empties the model. */
        void flush() {
            flush(false);
        }

        /**
         * @param tempLiveAbove true when a temp that is NOT on the model (e.g. a branch condition being
         *                      consumed) is still live in a register while this flush runs
         *
         * An entry's ORIGINAL line is re-emitted by the backend's ordinary PUSH/ADDR code, which uses rax/rbx
         * as scratch -- so it must not run while any temp is still live. Whenever a temp sits above the
         * entry (or is live off-model), the entry is written with a register-form push instead, which only
         * ever uses the spare scratch register. When no temp is live above, the original line is kept, so the
         * struct/array construction shapes the backend recognises reach it unchanged. (Only non-risky regions
         * are ever flushed while entries remain; see the class comment.)
         */
        void flush(boolean tempLiveAbove) {
            int lastT = -1;
            for (int i = 0; i < stack.size(); i++) {
                if (stack.get(i).kind == Kind.T) {
                    lastT = i;
                }
            }
            for (int i = 0; i < stack.size(); i++) {
                Entry e = stack.get(i);
                if (e.kind == Kind.T) {
                    emit("R_PUSH", "8", "%t" + e.temp);
                    freeTemp(e.temp);
                } else if (tempLiveAbove || i < lastT) {
                    if (e.kind == Kind.A) {
                        emit("R_PUSHA", "8", e.text);
                    } else {
                        emit("R_PUSH", "8", operandOf(e));
                    }
                } else {
                    out.add(e.orig);
                }
            }
            stack.clear();
        }
    }

    // ------------------------------------------------------------------
    // Operand text -- the one place that knows how an entry is written.
    // ------------------------------------------------------------------

    private static String operandOf(Entry e) {
        switch (e.kind) {
            case K:
                return "#" + e.text;
            case M:
                return e.text;
            case T:
                return "%t" + e.temp;
            default:
                throw new IllegalStateException("an address entry is not a value operand");
        }
    }

    /**
     * Can this entry be the address operand of a store / dereference / index? An address entry, or an 8-byte
     * pointer value. The width test also keeps a struct-construction run apart from a scalar store: a field
     * that sits below the last value of a run is narrower than the whole (a run's total is the ASSIGN width,
     * at most 8), so it can never be an 8-byte value -- only a genuine address is.
     */
    private static boolean isAddress(Entry e) {
        return e.kind == Kind.A || ((e.kind == Kind.T || e.kind == Kind.M) && e.size == 8);
    }

    /** anything that is a value (not an address) */
    private static boolean isValue(Entry e) {
        return e.kind != Kind.A;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static boolean is(List<BytecodeToken> line, int n) {
        return line.size() == n;
    }

    private static Long parseIntLiteral(String s) {
        if (s.equals("null")) {
            return 0L;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            try {
                return Long.parseUnsignedLong(s);
            } catch (NumberFormatException e2) {
                return null;
            }
        }
    }

    private static Long parseSlot(String s) {
        if (!s.startsWith("$")) {
            return null;
        }
        try {
            return Long.parseLong(s.substring(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseSize(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isWidth(int n) {
        return n == 1 || n == 2 || n == 4 || n == 8;
    }

    private static boolean isFloatLiteral(String s) {
        return s.matches("-?\\d+\\.\\d+([eE][+-]?\\d+)?");
    }

    private static boolean fitsImm32(String text) {
        Long v = parseIntLiteral(text);
        return v != null && v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE;
    }

    /** Loads a memory-resident or constant entry into a fresh temp (the caller has checked a temp is free). */
    private static void loadInto(State st, Entry e) {
        int t = st.allocTemp();
        if (e.kind == Kind.M) {
            if (e.size == 8) {
                st.emit("R_MOV", "8", "%t" + t, e.text);
            } else {
                st.emit("R_LD", String.valueOf(e.size), "%t" + t, e.text);
            }
        } else {
            st.emit("R_MOV", "8", "%t" + t, operandOf(e)); // a constant
        }
        boolean wasMem = e.kind == Kind.M;
        e.kind = Kind.T;
        e.temp = t;
        e.orig = null;
        e.zx = wasMem; // R_LD / an 8-byte move of a frame value: zero-extended; a constant is taken as written
    }

    /** a temp that an R_LD of `size` bytes just filled: zero-extended */
    private static Entry loadedTemp(int t, int size) {
        Entry r = newTemp(t, size);
        r.zx = true;
        return r;
    }

    private static Entry newTemp(int t, int size) {
        Entry r = new Entry();
        r.kind = Kind.T;
        r.temp = t;
        r.size = size;
        return r;
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private int fusePush(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 3)) {
            return 0;
        }
        int n = parseSize(line.get(1).text);
        if (!isWidth(n)) {
            return 0;
        }
        String operand = line.get(2).text;
        Entry e = new Entry();
        e.orig = line;
        e.size = n;
        Long slot = parseSlot(operand);
        if (slot != null) {
            e.kind = Kind.M;
            e.text = operand;
            e.off = slot;
        } else if (isFloatLiteral(operand)) {
            // The backend pushes a float literal as its IEEE bit pattern (single up to 4 bytes, else double).
            long bits = n <= 4
                    ? Integer.toUnsignedLong(Float.floatToRawIntBits(Float.parseFloat(operand)))
                    : Double.doubleToRawLongBits(Double.parseDouble(operand));
            e.kind = Kind.K;
            e.text = Long.toUnsignedString(bits);
            st.risky = true;
        } else {
            Long lit = parseIntLiteral(operand);
            if (lit == null) {
                if (globalNames.contains(operand)) {
                    return fuseGlobalRead(st, line, all, idx, n, operand);
                }
                return 0;
            }
            e.kind = Kind.K;
            e.text = operand.equals("null") ? "0" : operand;
        }
        if (n < 8) {
            st.risky = true;
        }
        st.anchor = line.get(0);
        st.stack.add(e);
        return 1;
    }

    /**
     * {@code PUSH n <global>}: a read of a global scalar. Loaded eagerly into a temp
     * ({@code R_LD n %tD &sym}), so the read order is exactly the original one and no new store hazard exists.
     * Declined (stack form) when an atomic swap follows two lines later (the backend reads that push as an address)
     * or no temp is free.
     */
    private int fuseGlobalRead(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx, int n, String sym) {
        // The backend decides a global push by looking TWO lines ahead: "PUSH address / PUSH newValue / ATOMIC_SWAP" pushes
        // the global's ADDRESS, not its value (X86Backend lineAtOffsetIsAtomicSwap(2)).
        if (idx + 2 < all.size()) {
            List<BytecodeToken> swap = all.get(idx + 2);
            if (!swap.isEmpty() && swap.get(0).text.equals("ATOMIC_SWAP")) {
                return 0;
            }
        }
        if (st.freeTemps() < 1) {
            return 0;
        }
        st.anchor = line.get(0);
        int t = st.allocTemp();
        st.emit("R_LD", String.valueOf(n), "%t" + t, "&" + sym);
        st.stack.add(newTemp(t, n));
        st.risky = true;
        return 1;
    }

    private int fuseAddr(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 3)) {
            return 0;
        }
        String operand = line.get(2).text;
        Entry e = new Entry();
        e.kind = Kind.A;
        e.orig = line;
        Long slot = parseSlot(operand);
        if (slot != null) {
            e.text = operand;
            e.off = slot;
        } else {
            if (operand.isEmpty() || Character.isDigit(operand.charAt(0)) || operand.startsWith("-") || operand.startsWith("@")) {
                return 0;
            }
            e.text = "&" + operand;
            e.global = true;
            st.risky = true;
        }
        st.anchor = line.get(0);
        st.stack.add(e);
        return 1;
    }

    /** Must an M operand of an n-byte integer op be loaded into a register first? (a narrow value or a narrow op needs zero/sign extension in a register) */
    private static boolean needsLoad(Entry e, int n) {
        return e.kind == Kind.M && (e.size != 8 || n < 8);
    }

    private int fuseBinary(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        String mnemonic = line.get(0).text;
        boolean logical = mnemonic.equals("AND") || mnemonic.equals("OR");
        if (!is(line, 2)) {
            return 0;
        }
        int n = parseSize(line.get(1).text);
        if (logical ? n != 1 : !isWidth(n)) {
            return 0;
        }
        int sz = st.stack.size();
        if (sz < 2) {
            return 0;
        }
        Entry b = st.stack.get(sz - 1);
        Entry a = st.stack.get(sz - 2);
        if (!isValue(a) || !isValue(b)) {
            return 0;
        }
        if ((mnemonic.equals("SHL") || mnemonic.equals("SHR") || mnemonic.equals("SAR") || mnemonic.equals("ROTL") || mnemonic.equals("ROTR")) && b.kind == Kind.K && parseIntLiteral(b.text) == null) {
            return 0; // a constant count the backend could not read as an immediate
        }
        if (logical && (a.kind != Kind.T || b.kind != Kind.T)) {
            return 0; // boolean ops only on register-held 0/1 results
        }
        boolean loadA = needsLoad(a, n);
        boolean loadB = needsLoad(b, n);
        boolean aReg = a.kind == Kind.T || loadA;
        boolean bReg = b.kind == Kind.T || loadB;
        int need = (loadA ? 1 : 0) + (loadB ? 1 : 0) + ((!aReg && !bReg) ? 1 : 0);
        if (need > st.freeTemps()) {
            return 0;
        }
        st.anchor = line.get(0);
        if (n < 8 || loadA || loadB) {
            st.risky = true;
        }
        if (loadA) {
            loadInto(st, a);
        }
        if (loadB) {
            loadInto(st, b);
        }
        String aOp = operandOf(a);
        String bOp = operandOf(b);
        st.stack.remove(sz - 1);
        st.stack.remove(sz - 2);
        int dst;
        if (a.kind == Kind.T) {
            dst = a.temp;
            if (b.kind == Kind.T) {
                st.freeTemp(b.temp);
            }
        } else {
            if (b.kind == Kind.T) {
                st.freeTemp(b.temp); // dst may equal b's temp; the backend handles that
            }
            dst = st.allocTemp();
        }
        String bop = BIN_OPS.get(mnemonic);
        boolean bits = bop.equals("SHL") || bop.equals("SHR") || bop.equals("SAR") || bop.equals("ROTL") || bop.equals("ROTR") || bop.equals("BAND") || bop.equals("BOR") || bop.equals("BXOR");
        boolean arith = bits || bop.equals("ADD") || bop.equals("SUB") || bop.equals("MUL");
        st.emit("R_BIN", bop, line.get(1).text, "%t" + dst, aOp, bOp);
        Entry res = newTemp(dst, arith ? n : 1); // the width of the VALUE (a comparison or bool op yields one byte)
        res.zx = bits; // the backend cuts a narrow bitwise/shift result back to its width: the temp holds the zero-extended value
        st.stack.add(res);
        return 1;
    }

    /**
     * DIV_INT n / MOD_INT n (n = 1, 2, 4, 8) by a literal that is not a power of two (those became a shift / a mask earlier): "R_DIVC DIV|MOD n %tD a #k",
     * which the backend turns into a multiplication by the reciprocal. Only the unsigned 8-byte forms and a literal divisor of
     * at least 3; any other division stays in the stack form.
     */
    private int fuseDivConst(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 2)) {
            return 0;
        }
        int n = parseSize(line.get(1).text);
        if (n != 8 && n != 4 && n != 2 && n != 1) {
            return 0;
        }
        int sz = st.stack.size();
        if (sz < 2) {
            return 0;
        }
        Entry b = st.stack.get(sz - 1);
        Entry a = st.stack.get(sz - 2);
        if (b.kind != Kind.K || !isValue(a)) {
            return 0;
        }
        Long k = parseIntLiteral(b.text);
        if (k == null || k < 3 || (k & (k - 1)) == 0) {
            return 0;
        }
        boolean loadA = needsLoad(a, n);
        boolean aReg = a.kind == Kind.T || loadA;
        int need = (loadA ? 1 : 0) + (!aReg ? 1 : 0);
        if (need > st.freeTemps()) {
            return 0;
        }
        st.anchor = line.get(0);
        st.risky = true;
        if (loadA) {
            loadInto(st, a);
        }
        String aOp = operandOf(a);
        st.stack.remove(sz - 1);
        st.stack.remove(sz - 2);
        int dst = a.kind == Kind.T ? a.temp : st.allocTemp();
        st.emit("R_DIVC", line.get(0).text.equals("DIV_INT") ? "DIV" : "MOD", String.valueOf(n), "%t" + dst, aOp, "#" + k);
        st.stack.add(newTemp(dst, n));
        return 1;
    }

    private int fuseUnary(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        String mnemonic = line.get(0).text;
        boolean isNot = mnemonic.equals("NOT");
        boolean isBitsNot = mnemonic.equals("BITS_NOT");
        if (!is(line, 2)) {
            return 0;
        }
        int n = parseSize(line.get(1).text);
        if (isNot ? n != 1 : !isWidth(n)) {
            return 0;
        }
        int sz = st.stack.size();
        if (sz < 1) {
            return 0;
        }
        Entry a = st.stack.get(sz - 1);
        if (!isValue(a)) {
            return 0;
        }
        if (isNot && a.kind != Kind.T) {
            return 0;
        }
        boolean load = a.kind == Kind.M && a.size != 8;
        int need = (load || a.kind != Kind.T) ? 1 : 0;
        if (need > st.freeTemps()) {
            return 0;
        }
        st.anchor = line.get(0);
        if (n < 8 || load) {
            st.risky = true;
        }
        if (load) {
            loadInto(st, a);
        }
        String op = mnemonic.equals("INC_INT") ? "INC" : mnemonic.equals("DEC_INT") ? "DEC" : isBitsNot ? "BNOT" : mnemonic;
        String aOp = operandOf(a);
        st.stack.remove(sz - 1);
        int dst = a.kind == Kind.T ? a.temp : st.allocTemp();
        st.emit("R_UN", op, line.get(1).text, "%t" + dst, aOp);
        Entry res = newTemp(dst, isNot ? 1 : n);
        res.zx = isBitsNot; // the backend cuts the complement back to the operand width
        st.stack.add(res);
        return 1;
    }

    // ---- floats -------------------------------------------------------

    /** A float operand: a T or K is used as is, an M of the op's own width is read straight from memory, any other M is loaded first. */
    private static boolean floatNeedsLoad(Entry e, int n) {
        return e.kind == Kind.M && e.size != n;
    }

    private int fuseFloatBin(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        return fuseFloatOp(st, line, "R_FBIN", FLOAT_BIN.get(line.get(0).text));
    }

    private int fuseFloatCmp(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        return fuseFloatOp(st, line, "R_FCMP", FLOAT_CMP.get(line.get(0).text));
    }

    private int fuseFloatOp(State st, List<BytecodeToken> line, String rmn, String op) {
        if (!is(line, 2)) {
            return 0;
        }
        int n = parseSize(line.get(1).text);
        if (n != 4 && n != 8) {
            return 0;
        }
        int sz = st.stack.size();
        if (sz < 2) {
            return 0;
        }
        Entry b = st.stack.get(sz - 1);
        Entry a = st.stack.get(sz - 2);
        if (!isValue(a) || !isValue(b)) {
            return 0;
        }
        boolean loadA = floatNeedsLoad(a, n);
        boolean loadB = floatNeedsLoad(b, n);
        boolean aReg = a.kind == Kind.T || loadA;
        boolean bReg = b.kind == Kind.T || loadB;
        int need = (loadA ? 1 : 0) + (loadB ? 1 : 0) + ((!aReg && !bReg) ? 1 : 0);
        if (need > st.freeTemps()) {
            return 0;
        }
        st.anchor = line.get(0);
        st.risky = true;
        if (loadA) {
            loadInto(st, a);
        }
        if (loadB) {
            loadInto(st, b);
        }
        String aOp = operandOf(a);
        String bOp = operandOf(b);
        st.stack.remove(sz - 1);
        st.stack.remove(sz - 2);
        int dst;
        if (a.kind == Kind.T) {
            dst = a.temp;
            if (b.kind == Kind.T) {
                st.freeTemp(b.temp);
            }
        } else if (b.kind == Kind.T) {
            dst = b.temp;
        } else {
            dst = st.allocTemp();
        }
        st.emit(rmn, op, line.get(1).text, "%t" + dst, aOp, bOp);
        st.stack.add(newTemp(dst, rmn.equals("R_FCMP") ? 1 : n));
        return 1;
    }

    // ---- pointers, loads, computed addresses ---------------------------

    private int fuseDeref(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 2)) {
            return 0;
        }
        int n = parseSize(line.get(1).text);
        return derefN(st, line, n);
    }

    /** LEN -- the dynarray header's len field: an 8-byte load through the pointer on top of the stack. */
    private int fuseLen(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 1)) {
            return 0;
        }
        return derefN(st, line, 8);
    }

    private int derefN(State st, List<BytecodeToken> line, int n) {
        if (!isWidth(n)) {
            return 0;
        }
        int sz = st.stack.size();
        if (sz < 1) {
            return 0;
        }
        Entry p = st.stack.get(sz - 1);
        if (!isAddress(p)) {
            return 0;
        }
        if (p.kind != Kind.T && st.freeTemps() == 0) {
            return 0;
        }
        st.anchor = line.get(0);
        st.risky = true;
        st.stack.remove(sz - 1);
        if (p.kind == Kind.A) {
            int t = st.allocTemp();
            st.emit("R_LD", String.valueOf(n), "%t" + t, p.text);
            st.stack.add(loadedTemp(t, n));
        } else if (p.kind == Kind.M) {
            int t = st.allocTemp();
            st.emit("R_MOV", "8", "%t" + t, p.text);
            st.emit("R_LD", String.valueOf(n), "%t" + t, "%t" + t);
            st.stack.add(loadedTemp(t, n));
        } else {
            st.emit("R_LD", String.valueOf(n), "%t" + p.temp, "%t" + p.temp);
            st.stack.add(loadedTemp(p.temp, n));
        }
        return 1;
    }

    /** base + idx*scale into one temp. base: A, T or an 8-byte M; idx: K, T or an M (loaded when narrow). */
    private int fuseLookupArrayLhs(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 2)) {
            return 0;
        }
        int scale = parseSize(line.get(1).text);
        if (scale <= 0) {
            return 0;
        }
        int sz = st.stack.size();
        if (sz < 2) {
            return 0;
        }
        Entry ix = st.stack.get(sz - 1);
        Entry base = st.stack.get(sz - 2);
        if (!isValue(ix)) {
            return 0;
        }
        if (!isAddress(base)) {
            return 0;
        }
        return emitLea(st, line, base, ix, scale, sz);
    }

    /**
     * LOOKUP_ARRAY n (no flag token) read through a POINTER base -- an unsafe (headerless) dynarray or a string: the element is the n bytes at
     * pointer + index*n, loaded zero-extended in one go (R_LEA + R_LD), like the safe dynarray read but with no header. The base is the pointer
     * value (a frame slot or a temp); a fixed array pushed by value is never one of these (bigger ones are pushed as blocks, an 8-byte one carries
     * the "t8" token, smaller ones are tagged), so only the plain two-token line is taken. Element types that are not 1/2/4/8 bytes stay stack form.
     */
    private int fuseLookupPtrRead(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 2)) {
            return 0;
        }
        int n = parseSize(line.get(1).text);
        if (idx + 2 < all.size() && n > 0) {
            // "LOOKUP_ARRAY n ; PUSH_FIELDNAME off w ; DOT w" -- one scalar field of a struct element behind a pointer (unsafe dynarray of structs):
            // address = pointer + index*n + off, then load w bytes. (Without this the whole n-byte element was pushed and the field cut out of it.)
            List<BytecodeToken> fl = all.get(idx + 1);
            List<BytecodeToken> dl = all.get(idx + 2);
            if (is(fl, 3) && fl.get(0).text.equals("PUSH_FIELDNAME") && is(dl, 2) && dl.get(0).text.equals("DOT")) {
                Long off = parseIntLiteral(fl.get(1).text);
                int fw = parseSize(fl.get(2).text);
                int dw = parseSize(dl.get(1).text);
                int sz2 = st.stack.size();
                if (off != null && off >= 0 && fw == dw && isWidth(fw) && off + fw <= n && sz2 >= 2) {
                    Entry ix2 = st.stack.get(sz2 - 1);
                    Entry base2 = st.stack.get(sz2 - 2);
                    if (isValue(ix2) && base2.kind != Kind.A && isAddress(base2)) {
                        int r2 = emitLea(st, line, base2, ix2, n, sz2, off);
                        if (r2 != 0) {
                            Entry top2 = st.stack.remove(st.stack.size() - 1);
                            st.emit("R_LD", String.valueOf(fw), "%t" + top2.temp, "%t" + top2.temp);
                            st.stack.add(loadedTemp(top2.temp, fw));
                            return 3;
                        }
                    }
                }
            }
        }
        if (!isWidth(n)) {
            return 0;
        }
        if (idx + 1 < all.size()) {
            List<BytecodeToken> nx = all.get(idx + 1);
            String nm = nx.isEmpty() ? "" : nx.get(0).text;
            // a following DOT takes a struct element apart; a following LOOKUP_ARRAY tagged "t8" indexes an 8-byte fixed array held by value
            // (this element, pushed as one word); any other following lookup just uses this scalar as its index or its pointer
            boolean nextTakesWhole = nm.startsWith("DOT") || (nm.equals("LOOKUP_ARRAY") && nx.size() > 2 && nx.get(2).text.equals("t8"));
            if (nextTakesWhole) {
                return 0;
            }
        }
        int sz = st.stack.size();
        if (sz < 2) {
            return 0;
        }
        Entry ix = st.stack.get(sz - 1);
        Entry base = st.stack.get(sz - 2);
        if (!isValue(ix) || base.kind == Kind.A || !isAddress(base)) {
            return 0;
        }
        int r = emitLea(st, line, base, ix, n, sz, 0);
        if (r == 0) {
            return 0;
        }
        Entry top = st.stack.remove(st.stack.size() - 1);
        st.emit("R_LD", String.valueOf(n), "%t" + top.temp, "%t" + top.temp);
        st.stack.add(loadedTemp(top.temp, n));
        return r;
    }

    private int emitLea(State st, List<BytecodeToken> line, Entry base, Entry ix, int scale, int sz) {
        return emitLea(st, line, base, ix, scale, sz, 0);
    }

    /** as above, plus a constant byte displacement (a sixth R_LEA operand, written only when non-zero). */
    private int emitLea(State st, List<BytecodeToken> line, Entry base, Entry ix, int scale, int sz, long disp) {
        boolean loadBase = base.kind == Kind.M;
        boolean loadIx = ix.kind == Kind.M && ix.size != 8;
        boolean baseReg = base.kind == Kind.T || loadBase;
        boolean ixReg = ix.kind == Kind.T || loadIx;
        int need = (loadBase ? 1 : 0) + (loadIx ? 1 : 0) + ((!baseReg && !ixReg) ? 1 : 0);
        if (need > st.freeTemps()) {
            return 0;
        }
        st.anchor = line.get(0);
        st.risky = true;
        if (loadBase) {
            loadInto(st, base);
        }
        if (loadIx) {
            loadInto(st, ix);
        }
        String bOp = base.kind == Kind.A ? base.text : operandOf(base);
        String iOp = operandOf(ix);
        st.stack.remove(sz - 1);
        st.stack.remove(sz - 2);
        int dst;
        if (base.kind == Kind.T) {
            dst = base.temp;
            if (ix.kind == Kind.T) {
                st.freeTemp(ix.temp);
            }
        } else if (ix.kind == Kind.T) {
            dst = ix.temp;
        } else {
            dst = st.allocTemp();
        }
        if (disp == 0) {
            st.emit("R_LEA", "%t" + dst, bOp, iOp, String.valueOf(scale));
        } else {
            st.emit("R_LEA", "%t" + dst, bOp, iOp, String.valueOf(scale), String.valueOf(disp));
        }
        st.stack.add(newTemp(dst, 8));
        return 1;
    }

    /**
     * LOOKUP_DYN n / LOOKUP_DYN_LHS n -- element address = pointer + 16 + index*n (the dynarray header is len, cap). The LHS form
     * leaves that address; the value form also loads n bytes (n = 1, 2, 4, 8) straight away, zero-extended.
     */
    private int fuseLookupDyn(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 2)) {
            return 0;
        }
        boolean lhs = line.get(0).text.equals("LOOKUP_DYN_LHS");
        int n = parseSize(line.get(1).text);
        if (n <= 0) {
            return 0;
        }
        if (!lhs && idx + 2 < all.size()) {
            // "LOOKUP_DYN n ; PUSH_FIELDNAME off w ; DOT w" -- one scalar field of an element: address = pointer + 16 + index*n + off, then
            // load w bytes. (Without this the whole n-byte element was pushed and the field cut out of the pushed block.)
            List<BytecodeToken> fl = all.get(idx + 1);
            List<BytecodeToken> dl = all.get(idx + 2);
            if (is(fl, 3) && fl.get(0).text.equals("PUSH_FIELDNAME") && is(dl, 2) && dl.get(0).text.equals("DOT")) {
                Long off = parseIntLiteral(fl.get(1).text);
                int fw = parseSize(fl.get(2).text);
                int dw = parseSize(dl.get(1).text);
                int sz2 = st.stack.size();
                if (off != null && off >= 0 && fw == dw && isWidth(fw) && off + fw <= n && sz2 >= 2) {
                    Entry ix2 = st.stack.get(sz2 - 1);
                    Entry base2 = st.stack.get(sz2 - 2);
                    if (isValue(ix2) && isAddress(base2)) {
                        int r2 = emitLea(st, line, base2, ix2, n, sz2, 16 + off);
                        if (r2 != 0) {
                            Entry top2 = st.stack.remove(st.stack.size() - 1);
                            st.emit("R_LD", String.valueOf(fw), "%t" + top2.temp, "%t" + top2.temp);
                            st.stack.add(loadedTemp(top2.temp, fw));
                            return 3;
                        }
                    }
                }
            }
        }
        if (!lhs) {
            if (!isWidth(n)) {
                return 0;
            }
            // the backend tags the pushed element for a following LOOKUP_ARRAY / DOT: leave those shapes alone
            if (idx + 1 < all.size()) {
                List<BytecodeToken> nx = all.get(idx + 1);
                String nm = nx.isEmpty() ? "" : nx.get(0).text;
                if (nm.startsWith("LOOKUP") || nm.startsWith("DOT")) {
                    return 0;
                }
            }
        }
        int sz = st.stack.size();
        if (sz < 2) {
            return 0;
        }
        Entry ix = st.stack.get(sz - 1);
        Entry base = st.stack.get(sz - 2);
        if (!isValue(ix) || !isAddress(base)) {
            return 0;
        }
        // the load below reuses the address temp, so it needs no extra register
        int r = emitLea(st, line, base, ix, n, sz, 16);
        if (r == 0 || lhs) {
            return r;
        }
        Entry top = st.stack.remove(st.stack.size() - 1);
        st.emit("R_LD", String.valueOf(n), "%t" + top.temp, "%t" + top.temp);
        st.stack.add(loadedTemp(top.temp, n));
        return r;
    }

    /**
     * ZEXT src dst -- zero-extension of the low `src` bytes. ZEXT 8 8 is nothing at all. A frame value is simply read at the narrower width. A temp that was LOADED at a width of at most
     * src bytes is already zero-extended (R_LD does it), so only its recorded width changes; a computed temp (a narrow ADD keeps carry
     * bits above its width) is masked (src 1 or 2) or left to the stack form (src 4: the mask does not fit an immediate).
     */
    private int fuseZext(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 3)) {
            return 0;
        }
        int src = parseSize(line.get(1).text);
        int dst = parseSize(line.get(2).text);
        int sz = st.stack.size();
        if (sz < 1 || !isWidth(src) || !isWidth(dst) || dst < src) {
            return 0;
        }
        if (src == 8 && dst == 8) {
            st.anchor = line.get(0);
            return 1;
        }
        Entry e = st.stack.get(sz - 1);
        if (e.kind == Kind.M) {
            // a frame value is zero-extended whenever it is read: keeping only its low `src` bytes is just reading fewer of them
            st.anchor = line.get(0);
            st.risky = true;
            if (e.size > src) {
                e.size = src;
                e.orig = st.mk("PUSH", String.valueOf(src), e.text); // a flush re-emits exactly the narrowed push
            }
            return 1;
        }
        if (e.kind != Kind.T) {
            return 0;
        }
        if (e.zx && e.size <= src) {
            st.anchor = line.get(0);
            st.risky = true;
            e.size = dst;
            return 1;
        }
        if (src == 1 || src == 2) {
            // computed (or wider) value: cut it down with an immediate mask
            st.anchor = line.get(0);
            st.risky = true;
            st.emit("R_BIN", "BAND", "8", "%t" + e.temp, "%t" + e.temp, src == 1 ? "#255" : "#65535");
            e.size = dst;
            e.zx = true;
            return 1;
        }
        return 0;
    }

    /**
     * TRUNC src dst -- keep the low `dst` bytes, zero-filled (a wrap:<T> or a proven narrowing). A frame value of at least dst bytes
     * is just read narrower (little endian: same address); a temp is masked in place (dst 1 or 2: the mask is an immediate).
     */
    private int fuseTrunc(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 3)) {
            return 0;
        }
        int src = parseSize(line.get(1).text);
        int dst = parseSize(line.get(2).text);
        int sz = st.stack.size();
        if (sz < 1 || src != 8 || (dst != 1 && dst != 2 && dst != 4)) {
            return 0;
        }
        Entry e = st.stack.get(sz - 1);
        if (e.kind == Kind.M && e.size >= dst) {
            st.anchor = line.get(0);
            st.risky = true;
            e.size = dst;
            e.orig = st.mk("PUSH", String.valueOf(dst), e.text); // a flush re-emits exactly the narrowed push
            return 1;
        }
        if (e.kind == Kind.T && dst != 4) {
            st.anchor = line.get(0);
            st.risky = true;
            st.emit("R_BIN", "BAND", "8", "%t" + e.temp, "%t" + e.temp, dst == 1 ? "#255" : "#65535");
            e.size = dst;
            e.zx = true;
            return 1;
        }
        return 0;
    }

    /** PUSH_FIELDNAME off size -- the field offset, an immediate that the DOT_LHS right after it adds to a base address. */
    private int fuseFieldName(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 3)) {
            return 0;
        }
        Long off = parseIntLiteral(line.get(1).text);
        if (off == null) {
            return 0;
        }
        Entry e = new Entry();
        e.kind = Kind.K;
        e.orig = line;
        e.text = line.get(1).text;
        st.anchor = line.get(0);
        st.risky = true;
        st.stack.add(e);
        return 1;
    }

    private int fuseDotLhs(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 2)) {
            return 0;
        }
        int sz = st.stack.size();
        if (sz < 2) {
            return 0;
        }
        Entry off = st.stack.get(sz - 1);
        Entry base = st.stack.get(sz - 2);
        if (off.kind != Kind.K) {
            return 0;
        }
        Long k = parseIntLiteral(off.text);
        if (k == null || !isAddress(base)) {
            return 0;
        }
        if (base.kind == Kind.A && !base.global) {
            // A field of a frame slot is just another frame address: no code at all.
            st.anchor = line.get(0);
            st.risky = true;
            st.stack.remove(sz - 1);
            st.stack.remove(sz - 2);
            Entry e = new Entry();
            e.kind = Kind.A;
            e.off = base.off + k;
            e.text = "$" + e.off;
            e.orig = st.mk("ADDR", "8", e.text);
            st.stack.add(e);
            return 1;
        }
        return emitLea(st, line, base, off, 1, sz);
    }

    // ---- stores ---------------------------------------------------------

    private int fuseAssign(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 4) || !line.get(0).text.equals("ASSIGN")) {
            return 0;
        }
        int n = parseSize(line.get(1).text);
        if (!isWidth(n) || !line.get(2).text.equals(line.get(1).text) || !line.get(3).text.equals(line.get(1).text)) {
            return 0;
        }
        int sz = st.stack.size();
        if (sz < 2) {
            return 0;
        }
        Entry val = st.stack.get(sz - 1);
        Entry addr = st.stack.get(sz - 2);
        if (!isAddress(addr) || !isValue(val)) {
            return 0; // not a plain scalar store -- includes every multi-entry construction run
        }
        boolean frameDst = addr.kind == Kind.A && !addr.global;
        boolean globalDst = addr.kind == Kind.A && addr.global;
        // Store hazard: a still-deferred read of memory this store may overwrite must be loaded first.
        List<Entry> hazards = new ArrayList<>();
        for (int k = 0; k < sz - 2; k++) {
            Entry e = st.stack.get(k);
            if (e.kind != Kind.M) {
                continue;
            }
            boolean overlaps;
            if (frameDst) {
                overlaps = e.off < addr.off + n && addr.off < e.off + e.size;
            } else {
                overlaps = !globalDst; // a store through a pointer may land on any frame slot
            }
            if (overlaps) {
                hazards.add(e);
            }
        }
        boolean legacy = n == 8 && frameDst; // the phase-1 shape: R_MOV $slot src
        boolean valLoad = val.kind == Kind.M
                || (val.kind == Kind.K && !legacy && n == 8 && !fitsImm32(val.text));
        boolean addrLoad = addr.kind == Kind.M;
        int needed = hazards.size() + (valLoad ? 1 : 0) + (addrLoad ? 1 : 0);
        if (needed > st.freeTemps()) {
            return 0;
        }
        st.anchor = line.get(0);
        if (!legacy || n != 8) {
            st.risky = true;
        }
        for (Entry h : hazards) {
            loadInto(st, h);
        }
        // Peephole: "x = x + c" / "x = x - c" / "x++" / "x--" on an 8-byte slot -> one memory-operand instruction.
        if (legacy && val.kind == Kind.T && tryRmw(st, addr, val, sz)) {
            return 1;
        }
        String src;
        int scratch = -1;
        if (valLoad) {
            scratch = st.allocTemp();
            if (val.kind == Kind.M) {
                if (val.size == 8) {
                    st.emit("R_MOV", "8", "%t" + scratch, val.text);
                } else {
                    st.emit("R_LD", String.valueOf(val.size), "%t" + scratch, val.text);
                }
            } else {
                st.emit("R_MOV", "8", "%t" + scratch, operandOf(val));
            }
            src = "%t" + scratch;
        } else {
            src = operandOf(val);
        }
        String dst;
        int addrScratch = -1;
        if (addrLoad) {
            addrScratch = st.allocTemp();
            st.emit("R_MOV", "8", "%t" + addrScratch, addr.text);
            dst = "%t" + addrScratch;
        } else if (addr.kind == Kind.T) {
            dst = "%t" + addr.temp;
        } else {
            dst = addr.text; // $off or &sym
        }
        if (legacy) {
            st.emit("R_MOV", "8", dst, src);
        } else {
            st.emit("R_ST", String.valueOf(n), dst, src);
        }
        if (scratch >= 0) {
            st.freeTemp(scratch);
        }
        if (addrScratch >= 0) {
            st.freeTemp(addrScratch);
        }
        if (val.kind == Kind.T) {
            st.freeTemp(val.temp);
        }
        if (addr.kind == Kind.T) {
            st.freeTemp(addr.temp);
        }
        st.stack.remove(sz - 1);
        st.stack.remove(sz - 2);
        return 1;
    }

    /**
     * The value about to be stored to slot $x is the result of the last emitted "R_BIN ADD|SUB 8 %tv $x b" (or
     * "R_UN INC|DEC 8 %tv $x"): replace that instruction and the store with one read-modify-write on the slot.
     * The temp is dead after the store, so nothing else reads it.
     */
    private boolean tryRmw(State st, Entry addr, Entry val, int sz) {
        if (st.out.isEmpty() || st.out.size() <= st.cpOut) {
            return false;
        }
        List<BytecodeToken> last = st.out.get(st.out.size() - 1);
        String vt = "%t" + val.temp;
        if (last.size() == 6 && last.get(0).text.equals("R_BIN") && last.get(2).text.equals("8") && last.get(3).text.equals(vt)) {
            String op = last.get(1).text;
            String a = last.get(4).text;
            String b = last.get(5).text;
            String other = null;
            if ((op.equals("ADD") || op.equals("SUB")) && a.equals(addr.text)) {
                other = b;
            } else if (op.equals("ADD") && b.equals(addr.text)) {
                other = a;
            }
            if (other == null || other.equals(addr.text)) {
                return false;
            }
            if (other.startsWith("#") && !fitsImm32(other.substring(1))) {
                return false; // the backend would need a second scratch register
            }
            st.out.remove(st.out.size() - 1);
            st.emit("R_RMW", op, "8", addr.text, other);
        } else if (last.size() == 5 && last.get(0).text.equals("R_UN") && last.get(2).text.equals("8")
                && last.get(3).text.equals(vt) && last.get(4).text.equals(addr.text)
                && (last.get(1).text.equals("INC") || last.get(1).text.equals("DEC"))) {
            String op = last.get(1).text;
            st.out.remove(st.out.size() - 1);
            st.emit("R_RMW", op, "8", addr.text);
        } else {
            return false;
        }
        st.freeTemp(val.temp);
        st.stack.remove(sz - 1);
        st.stack.remove(sz - 2);
        return true;
    }

    // ---- argument registers and returns ---------------------------------

    /** POP ARGn size / POP FARGn size -- straight into the argument register; anything else (POP $slot ...) is left alone. */
    private int fusePop(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 3)) {
            return 0;
        }
        String dest = line.get(1).text;
        Long slot = parseSlot(dest);
        if (slot != null) {
            return fusePopToSlot(st, line, all, idx, dest, slot);
        }
        boolean isF = dest.startsWith("FARG");
        boolean isI = !isF && dest.startsWith("ARG");
        if (!isF && !isI) {
            return 0;
        }
        int n = parseSize(dest.substring(isF ? 4 : 3));
        if (n < 0) {
            return 0;
        }
        int sz = st.stack.size();
        if (sz < 1) {
            return 0;
        }
        Entry v = st.stack.get(sz - 1);
        if (v.kind == Kind.A) {
            if (isF) {
                return 0;
            }
            st.anchor = line.get(0);
            st.risky = true;
            st.stack.remove(sz - 1);
            st.emit("R_ARGA", String.valueOf(n), v.text);
            return 1;
        }
        // K needs no size (a literal is pushed whole); M carries its own width; a T is already a whole word.
        st.anchor = line.get(0);
        st.risky = true;
        st.stack.remove(sz - 1);
        st.emit(isF ? "R_FARG" : "R_ARG", String.valueOf(n), String.valueOf(v.kind == Kind.M ? v.size : 8), operandOf(v));
        if (v.kind == Kind.T) {
            st.freeTemp(v.temp);
        }
        return 1;
    }

    /**
     * POP $slot n with a remembered value on top: the same store as "ADDR $slot; value; ASSIGN n n n", so it goes through
     * fuseAssign with a transient address entry placed under the value (removed again if fuseAssign declines).
     */
    private int fusePopToSlot(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx, String dest, long slot) {
        int n = parseSize(line.get(2).text);
        int sz = st.stack.size();
        if (!isWidth(n) || sz < 1 || !isValue(st.stack.get(sz - 1))) {
            return 0;
        }
        Entry a = new Entry();
        a.kind = Kind.A;
        a.orig = line;
        a.text = dest;
        a.off = slot;
        st.stack.add(sz - 1, a);
        BytecodeToken h = line.get(0);
        String w = String.valueOf(n);
        List<BytecodeToken> assign = new ArrayList<>();
        assign.add(new BytecodeToken("ASSIGN", h.file, h.line, h.kind));
        for (int k = 0; k < 3; k++) {
            assign.add(new BytecodeToken(w, h.file, h.line, h.kind));
        }
        int r = fuseAssign(st, assign, all, idx);
        if (r == 0) {
            st.stack.remove(sz - 1);
        }
        return r;
    }

    /** RET size / RET_FLOAT size with exactly one remembered value: move it into rax / xmm0 and leave. */
    private int fuseRet(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 2)) {
            return 0;
        }
        boolean isF = line.get(0).text.equals("RET_FLOAT");
        if (!isF) {
            int n = parseSize(line.get(1).text);
            if (n <= 0 || n > 8) {
                return 0; // void (0) returns and wide values keep the stack form
            }
        }
        if (st.stack.size() != 1) {
            return 0;
        }
        Entry v = st.stack.get(0);
        if (!isValue(v)) {
            return 0;
        }
        st.anchor = line.get(0);
        st.risky = true;
        st.stack.remove(0);
        if (v.kind == Kind.M && v.size != 8) {
            st.emit("R_LD", String.valueOf(v.size), "%s", v.text);
            st.emit(isF ? "R_RETF" : "R_RET", "%s");
        } else {
            st.emit(isF ? "R_RETF" : "R_RET", operandOf(v));
        }
        if (v.kind == Kind.T) {
            st.freeTemp(v.temp);
        }
        return 1;
    }

    // ---- conditional branches ---------------------------------------------

    private int fuseCmpJmp(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 1) || idx + 1 >= all.size()) {
            return 0;
        }
        List<BytecodeToken> next = all.get(idx + 1);
        if (next.size() != 2 || !next.get(0).text.equals("JMP")) {
            return 0;
        }
        int n = st.stack.size();
        if (n < 1) {
            return 0;
        }
        Entry c = st.stack.get(n - 1);
        if (c.kind != Kind.T) {
            return 0; // conditions are register-held 0/1 results
        }
        if (st.risky && n > 1) {
            return 0; // entries below the condition could only be written back with a plain stack flush
        }
        st.anchor = line.get(0);
        // Anything still deferred below the condition must be on the real stack before control can leave.
        // The condition's own temp is taken off the model first, so the flush leaves it alone.
        st.stack.remove(n - 1);
        st.flush(true);
        st.emit("R_BRF", "%t" + c.temp, next.get(1).text);
        st.freeTemp(c.temp);
        return 2;
    }
}
