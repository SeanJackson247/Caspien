package caspien.lowerorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Register-variable promotion -- runs after {@link RegisterFormPass}, on its output, and only when compiler.config says
 * "variables-in-registers: on" (which needs "deferred-operands: on").
 *
 * <p>Input hints: "REGHINT offset size weight", one per scalar local that the Optimizer's RegVarHintPass marked as hot
 * ("REGVAR name weight", turned into a frame offset and width by AddressLoweringPass). The weight is the number of
 * source mentions, each counted 8^loopDepth times. The hint says nothing about WHICH register: this pass chooses.
 *
 * <p>What it does, per function: it picks the (at most {@link #VAR_COUNT}) hinted slots with the highest weight that are
 * <b>safe to promote</b> and renames every operand naming that frame slot ("$off") to a variable register "%vN"
 * (r13, r14 in the backend). A slot is safe only if EVERY mention of it in the function is an operand of a register-form
 * instruction that this pass knows how to rewrite, at exactly the slot's width. Any other mention -- a plain PUSH/ADDR/
 * ASSIGN the register-form pass could not fuse, an R_PUSHA or R_LEA base (the slot's address), a size mismatch --
 * leaves that slot in memory. Because the decision is made on the FINAL text there is nothing to roll back: the rename
 * is a pure change of where the value lives, line for line. (The Optimizer already excluded every variable whose
 * address is taken.)
 *
 * <p>The whole function is skipped when it contains an instruction whose backend code uses r13/r14 as its own scratch
 * ({@link #R13_R14_USERS}), or inline assembly.
 *
 * <p>Rewrites (the variable register always holds the value zero-extended to 64 bits, like a temp):
 * <pre>
 *   R_LD n %tD $x        -> R_MOV 8 %tD %vK
 *   R_ST n $x src        -> R_MOV 8 %vK src            (n == 8)
 *                        -> R_SETV n %vK src           (n &lt; 8: truncate, zero-extend)
 *   any other operand    -> the same line with $x replaced by %vK
 * </pre>
 * The backend maps %vN to a callee-saved register; every function that touches one already saves/restores it
 * (X86Backend.finishCalleeSaved), so a value held across a call, a throw or a thread start is safe.
 *
 * <p>Always strips REGHINT lines (and any stray REGVAR), so Codegen never sees them and, with the switch off, the output
 * is byte-identical to a build without this feature.
 *
 * <p><b>Float variables</b> ("float-variables-in-registers: on"): a hint carrying the trailing "f" marker (an f32 local) is
 * kept in an xmm register "%xK" (at most {@link #XVAR_COUNT} per function, chosen by weight) instead of competing for r13/r14.
 * The same all-or-nothing rule applies (every mention of the slot must be renameable; see {@link #mentionOkX}). Rewrites:
 * <pre>
 *   R_LD n %tD $x          -> R_XTOG n %tD %xK            (movd / movq; n = 4 or 8)
 *   R_ST n $x src          -> R_GTOX n %xK src
 *   PUSH n $x              -> PUSH n %xK                  (the backend pushes the zero-extended bits, as before)
 *   R_FBIN/R_FCMP/R_ARG/R_FARG/R_RETF ... $x ...  -> the same line with %xK (the backend reads the register directly)
 *   ADDR 4 $x / CC_START .. CC_END / PUSH_RET_FLOAT 4 / ASSIGN 4 4 4  ->  R_GETRETF 4 %xK after the call   (x = f(...))
 *   R_LD n %t addr ; R_GTOX n %xK %t          -> R_LDX n %xK addr         (temp dead afterwards)
 *   R_FBIN OP n %t a b ; R_GTOX n %xK %t      -> R_FBINX OP n %xK a b     (temp dead afterwards)
 *   R_XTOG n %t %xK ; R_ST n addr %t          -> R_STX n addr %xK
 * </pre>
 * Each function gets "R_XVAR %xK $home" declarations (its frame slot, for the ABIs whose xmm registers do not survive a call:
 * the backend spills/reloads around every call) and an "R_XRELOAD" right after every "@catch_" label (control arrives there
 * from an unwind). Functions with inline assembly are never touched; a function that only uses r13/r14 as backend scratch
 * (NEW, CLONE, ...) can still have float variables, since no backend sequence uses the variable xmm registers.
 */
public class RegVarPromotionPass {

    /** how many variable registers exist (%v0, %v1 -> r13, r14); a function that never needs a fourth temporary also gets %v2 -> r12 */
    public static final int VAR_COUNT = 2;

    /** the most variable registers any function can get (%v2 shares r12 with the fourth temporary %t3) */
    public static final int VAR_COUNT_MAX = 3;

    /** how many float variable registers exist (%x0..%x5 -> xmm8-13 on SysV, xmm6-11 on win64) */
    public static final int XVAR_COUNT = 6;

    /**
     * Variable registers 3..5 are r8, r9 and r10: caller-saved (r8/r9 are argument registers, r10 the INVOKE target and float-result
     * stash), so a variable may sit there only if it is never live at or across a line that could clobber them. Only the lines in
     * {@link #VOL_SAFE} are known not to (checked against the backend: none of them touches r8, r9 or r10). Used only when more
     * variables want registers than the callee-saved ones provide.
     */
    public static final int VOL_FIRST = 3;
    public static final int VOL_COUNT = 7;
    /** how many of the VOL_COUNT registers are the always-available r8-r10 (the first three); the rest (%v6, %v7 = rsi, rdi, SysV argument registers 1 and 0; %v8, %v9 = rdx, rcx, SysV argument registers 2 and 3, SysV only) need the argRegs switch */
    static final int VOL_BASE = 3;

    static final Set<String> VOL_SAFE = new HashSet<>(Arrays.asList(
            "R_MOV", "R_LD", "R_ST", "R_BIN", "R_UN", "R_DIVC", "R_POPV", "R_GETRET", "R_BRC", "R_BRF", "R_LEA", "R_RMW", "R_SETV", "R_LDX", "R_STX", "R_FBIN",
            "R_FBINX", "R_FCMP", "R_XTOG", "R_GTOX", "R_XMOV", "R_PUSH", "R_PUSHA", "R_XVAR", "R_RET", "R_RETF", "JMP", "CMP",
            "ALLOC", "FUNC_START", "FUNC_END"));

    /** Mnemonics whose backend code uses r13/r14 internally (CLONE, DOT of wide fields, LOOKUP_ARRAY, NEW*, RESIZE*) or that run arbitrary code. */
    static final Set<String> R13_R14_USERS = new HashSet<>(Arrays.asList(
            "NEW", "NEW_DYN", "NEW_UDYN", "NEW_FROM_STRING", "NEW_FROM_USTRING", "CLONE", "CLONE_DYN", "RESIZE", "URESIZE",
            "DOT", "LOOKUP_ARRAY", "ASM_START", "ASM_END"));

    private final boolean enabled;
    private final boolean floatEnabled;
    /** `variables-in-arg-registers: on`: rsi and rdi may also hold variables (call-free ranges that never touch an argument bracket) */
    private final boolean argRegs;
    /** functions with scratch-using instructions (NEW, RESIZE, DOT, ...) may keep variables in r12-r14: the backend saves them around those lines */
    private final boolean allocFunctions;

    public RegVarPromotionPass(boolean enabled) {
        this(enabled, false);
    }

    public RegVarPromotionPass(boolean enabled, boolean floatEnabled) {
        this(enabled, floatEnabled, false);
    }

    public RegVarPromotionPass(boolean enabled, boolean floatEnabled, boolean allocFunctions) {
        this.enabled = enabled;
        this.floatEnabled = enabled && floatEnabled;
        this.allocFunctions = enabled && allocFunctions;
        this.argRegs = false;
    }

    public RegVarPromotionPass(boolean enabled, boolean floatEnabled, boolean allocFunctions, boolean argRegs) {
        this.enabled = enabled;
        this.floatEnabled = enabled && floatEnabled;
        this.allocFunctions = enabled && allocFunctions;
        this.argRegs = enabled && argRegs;
    }

    static final class Hint {
        long off;
        int size;
        long weight;
        boolean banned;
        int reg = -1;
        /** the hint carried the float marker */
        boolean isFloat;
        /** promoted to an xmm register (float hint and the float switch is on) rather than r13/r14 */
        boolean xmm;
        /** may live in a caller-saved register (r8/r9/r10): never live across or at a line that could clobber them */
        boolean volOk;
        /** may live in rsi/rdi: volOk, and never mentioned inside a call's argument bracket (CC_START .. CC_END), where those registers are being filled */
        boolean argSafe;
        /** may live in rdx: never live at or across a constant divide (R_DIVC clobbers rdx) */
        boolean rdxOk;
        /** may live in rcx: never live at or across a variable-count shift (the shift saves and uses rcx as the count) */
        boolean rcxOk;
    }

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        int i = 0;
        while (i < lines.size()) {
            List<BytecodeToken> line = lines.get(i);
            if (!line.isEmpty() && line.get(0).text.equals("FUNC_START")) {
                int end = i;
                while (end < lines.size() && !(!lines.get(end).isEmpty() && lines.get(end).get(0).text.equals("FUNC_END"))) {
                    end++;
                }
                int stop = Math.min(end + 1, lines.size());
                processFunction(lines.subList(i, stop), out);
                i = stop;
                continue;
            }
            if (isHint(line)) {
                i++;
                continue;
            }
            out.add(line);
            i++;
        }
        return out;
    }

    /** true when the function text mentions the fourth temporary (%t3 = r12) anywhere */
    private static boolean usesTemp3(List<List<BytecodeToken>> fn) {
        for (List<BytecodeToken> l : fn) {
            for (BytecodeToken t : l) {
                if (t.text.equals("%t3")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isHint(List<BytecodeToken> line) {
        if (line.isEmpty()) {
            return false;
        }
        String m = line.get(0).text;
        return m.equals("REGHINT") || m.equals("REGVAR");
    }

    private void processFunction(List<List<BytecodeToken>> fn, List<List<BytecodeToken>> out) {
        Map<Long, Hint> hints = new HashMap<>();
        boolean blocked = false;      // an instruction that uses r13/r14 as backend scratch: no integer variables here
        boolean hardBlocked = false;  // inline assembly: touch nothing
        for (List<BytecodeToken> l : fn) {
            if (l.isEmpty()) {
                continue;
            }
            String m = l.get(0).text;
            if (m.equals("REGHINT") && (l.size() == 4 || l.size() == 5)) {
                try {
                    Hint h = new Hint();
                    h.off = Long.parseLong(l.get(1).text);
                    h.size = Integer.parseInt(l.get(2).text);
                    h.weight = Long.parseLong(l.get(3).text);
                    h.isFloat = l.size() == 5 && l.get(4).text.equals("f");
                    h.xmm = floatEnabled && h.isFloat && (h.size == 4 || h.size == 8);
                    if (h.size == 1 || h.size == 2 || h.size == 4 || h.size == 8) {
                        hints.putIfAbsent(h.off, h);
                    }
                } catch (NumberFormatException e) {
                    // ignore a malformed hint
                }
            } else if (R13_R14_USERS.contains(m)) {
                blocked = true;
                if (m.equals("ASM_START") || m.equals("ASM_END")) {
                    hardBlocked = true;
                }
            }
        }
        if (!enabled || hardBlocked || hints.isEmpty()) {
            for (List<BytecodeToken> l : fn) {
                if (!isHint(l)) {
                    out.add(l);
                }
            }
            return;
        }
        // r13/r14 are the backend's scratch in a function that has such an instruction, so no callee-saved variable register is used
        // there (intLimit 0 below). The caller-saved r8/r9/r10 stay available: a variable gets one only if no instruction outside
        // VOL_SAFE (which includes every one of those scratch users) has it live, so it is never live across one.

        // A float variable assigned by the stack form: ADDR 4 $x / <value computation> / ASSIGN 4 4 4. The value computation may hold
        // calls (x = x + f(y)); only lines that are known to leave the pushed address alone are allowed inside, and nested
        // ADDR/ASSIGN pairs (the call-site staging stores) are matched by counting. The pair becomes "value; R_POPX %xK";
        // when the computation is exactly one call bracket plus PUSH_RET_FLOAT it becomes "call; R_GETRETF %xK".
        List<int[]> pairs = new ArrayList<>(); // {addrIdx, assignIdx, direct(1)/general(0), retIdx}
        Map<Integer, Long> pairSlot = new HashMap<>();
        java.util.Set<Integer> approvedAddr = new HashSet<>();
        for (int i = 0; i + 1 < fn.size(); i++) {
            List<BytecodeToken> l = fn.get(i);
            if (!(l.size() == 3 && l.get(0).text.equals("ADDR") && (l.get(1).text.equals("1") || l.get(1).text.equals("2") || l.get(1).text.equals("4") || l.get(1).text.equals("8")) && l.get(2).text.startsWith("$"))) {
                continue;
            }
            Hint h = slotHint(hints, l.get(2).text);
            if (h == null || !l.get(1).text.equals(String.valueOf(h.size))) {
                continue;
            }
            int c = 1;
            int j = i + 1;
            boolean ok = true;
            for (; j < fn.size(); j++) {
                List<BytecodeToken> jl = fn.get(j);
                if (jl.isEmpty()) {
                    continue;
                }
                String jm = jl.get(0).text;
                if (jm.equals("ADDR")) {
                    c++;
                } else if (jm.equals("ASSIGN") || jm.equals("ATOMIC_ASSIGN")) {
                    c--;
                    if (c == 0) {
                        break;
                    }
                } else if (jm.equals("POP")) {
                    if (!(jl.size() >= 2 && (jl.get(1).text.startsWith("ARG") || jl.get(1).text.startsWith("FARG")))) {
                        ok = false;
                        break;
                    }
                } else if (!(jm.startsWith("R_") || VALUE_OPS.contains(jm))) {
                    ok = false;
                    break;
                }
            }
            if (!ok || j >= fn.size() || c != 0) {
                continue;
            }
            List<BytecodeToken> asg = fn.get(j);
            String hw = String.valueOf(h.size);
            if (!(asg.size() == 4 && asg.get(0).text.equals("ASSIGN") && asg.get(1).text.equals(hw)
                    && asg.get(2).text.equals(hw) && asg.get(3).text.equals(hw))) {
                continue;
            }
            int direct = 0;
            int retIdx = -1;
            if (j - 1 > i + 1 && fn.get(i + 1).get(0).text.equals("CC_START") && fn.get(j - 2).get(0).text.equals("CC_END")) {
                List<BytecodeToken> ret = fn.get(j - 1);
                if (ret.size() == 2 && ret.get(0).text.equals(h.xmm ? "PUSH_RET_FLOAT" : "PUSH_RET_INT") && ret.get(1).text.equals(hw)) {
                    // the bracket that opens right after the ADDR must be the one that closes right before PUSH_RET_FLOAT
                    int depth = 0;
                    boolean whole = true;
                    for (int q = i + 1; q <= j - 2; q++) {
                        String qm = fn.get(q).isEmpty() ? "" : fn.get(q).get(0).text;
                        if (qm.equals("CC_START")) {
                            depth++;
                        } else if (qm.equals("CC_END")) {
                            depth--;
                            if (depth == 0 && q != j - 2) {
                                whole = false;
                                break;
                            }
                        }
                    }
                    if (whole && depth == 0) {
                        direct = 1;
                        retIdx = j - 1;
                    }
                }
            }
            pairs.add(new int[] {i, j, direct, retIdx});
            pairSlot.put(i, h.off);
            approvedAddr.add(i);
        }

        // Pass 1: ban every hinted slot with a mention we cannot rewrite.
        for (int idx = 0; idx < fn.size(); idx++) {
            List<BytecodeToken> l = fn.get(idx);
            if (l.isEmpty() || isHint(l) || approvedAddr.contains(idx)) {
                continue;
            }
            String m = l.get(0).text;
            for (int k = 1; k < l.size(); k++) {
                Hint h = slotHint(hints, l.get(k).text);
                if (h != null && !(h.xmm ? mentionOkX(m, l, k, h) : mentionOk(m, l, k, h))) {
                    h.banned = true;
                }
            }
        }

        // Choose per register file: highest weight first (lower offset breaks ties, for a stable result).
        Map<Long, Hint> chosenInt = new HashMap<>();
        Map<Long, Hint> chosenX = new HashMap<>();
        List<Hint> intCands = new ArrayList<>();
        List<Hint> xCands = new ArrayList<>();
        for (Hint h : hints.values()) {
            if (!h.banned) {
                (h.xmm ? xCands : intCands).add(h);
            }
        }
        java.util.Comparator<Hint> byWeight = (a, b) -> a.weight != b.weight ? Long.compare(b.weight, a.weight) : Long.compare(a.off, b.off);
        intCands.sort(byWeight);
        xCands.sort(byWeight);
        boolean noCallee = blocked && !allocFunctions;
        int intLimit = noCallee ? 0 : VAR_COUNT;
        if (!noCallee && !usesTemp3(fn)) {
            intLimit = VAR_COUNT_MAX;
        }
        if (intCands.size() > intLimit || xCands.size() > XVAR_COUNT) {
            // More candidates than registers (typical after inlining: dozens of hot variables from different inlined bodies in one
            // function). Variables whose live ranges never overlap can share a register, so colour by interference, heaviest first.
            shareRegisters(fn, hints, pairs, intCands, xCands, intLimit);
            for (Hint h : intCands) if (h.reg >= 0) chosenInt.put(h.off, h);
            for (Hint h : xCands) if (h.reg >= 0) chosenX.put(h.off, h);
        } else {
            for (int k = 0; k < intCands.size() && k < intLimit; k++) {
                intCands.get(k).reg = k;
                chosenInt.put(intCands.get(k).off, intCands.get(k));
            }
            for (int k = 0; k < xCands.size() && k < XVAR_COUNT; k++) {
                xCands.get(k).reg = k;
                chosenX.put(xCands.get(k).off, xCands.get(k));
            }
        }
        if (chosenInt.isEmpty() && chosenX.isEmpty()) {
            for (List<BytecodeToken> l : fn) {
                if (!isHint(l)) {
                    out.add(l);
                }
            }
            return;
        }

        // Pass 2: rewrite.
        java.util.Set<Integer> dropped = new HashSet<>();
        Map<Integer, Hint> retReplace = new HashMap<>();   // PUSH_RET_FLOAT line -> R_GETRETF
        Map<Integer, Hint> assignReplace = new HashMap<>(); // ASSIGN line -> R_POPX
        for (int[] pr : pairs) {
            Hint h = chosenX.get(pairSlot.get(pr[0]));
            if (h == null) {
                h = chosenInt.get(pairSlot.get(pr[0]));
            }
            if (h == null) {
                continue;
            }
            dropped.add(pr[0]);
            if (pr[2] == 1) {
                dropped.add(pr[1]);
                retReplace.put(pr[3], h);
            } else {
                assignReplace.put(pr[1], h);
            }
        }
        List<List<BytecodeToken>> res = new ArrayList<>();
        for (int idx = 0; idx < fn.size(); idx++) {
            List<BytecodeToken> l = fn.get(idx);
            if (isHint(l) || dropped.contains(idx)) {
                continue;
            }
            BytecodeToken ref = l.isEmpty() ? null : l.get(0);
            if (retReplace.containsKey(idx)) {
                Hint rh = retReplace.get(idx);
                res.add(rh.xmm ? mk(ref, "R_GETRETF", String.valueOf(rh.size), "%x" + rh.reg) : mk(ref, "R_GETRET", String.valueOf(rh.size), "%v" + rh.reg));
                continue;
            }
            if (assignReplace.containsKey(idx)) {
                Hint ah = assignReplace.get(idx);
                res.add(ah.xmm ? mk(ref, "R_POPX", String.valueOf(ah.size), "%x" + ah.reg) : mk(ref, "R_POPV", String.valueOf(ah.size), "%v" + ah.reg));
                continue;
            }
            if (!chosenX.isEmpty() && jumpsToCatchLabel(l)) {
                // A jump straight into a catch label (an inlined throw, a failed allocation) is not a call, so the backend has not
                // spilled the float variables: write them to their home slots first, where the catch entry's R_XRELOAD reads them.
                // (Stores only, so it is harmless between a compare and its jump.)
                res.add(mk(ref, "R_XSPILL"));
            }
            res.add(rewrite(l, chosenInt, chosenX));
            if (idx == 0 && !chosenX.isEmpty()) {
                // one declaration per register: its home slot (where the backend spills it around calls) is the slot of the heaviest
                // variable that lives in it; the slot is free for that use because every mention of the variable was renamed
                Map<Integer, Hint> homeOf = new java.util.TreeMap<>();
                for (Hint h : chosenX.values()) {
                    Hint cur = homeOf.get(h.reg);
                    if (cur == null || h.weight > cur.weight || (h.weight == cur.weight && h.off < cur.off)) homeOf.put(h.reg, h);
                }
                for (Hint h : homeOf.values()) {
                    res.add(mk(ref, "R_XVAR", "%x" + h.reg, "$" + h.off, String.valueOf(h.size)));
                }
            }
            if (!chosenX.isEmpty() && l.size() == 1 && l.get(0).text.startsWith("@catch_") && l.get(0).text.endsWith(":")) {
                res.add(mk(ref, "R_XRELOAD"));
            }
        }
        out.addAll(chosenX.isEmpty() ? res : fuse(res));
    }

    /** True for a jump or branch line whose target is a `@catch_` label (a direct jump, not an unwind through a call). */
    private static boolean jumpsToCatchLabel(List<BytecodeToken> l) {
        if (l.size() < 2) {
            return false;
        }
        String m = l.get(0).text;
        if (!(m.equals("JMP") || m.startsWith("R_BR"))) {
            return false;
        }
        return l.get(l.size() - 1).text.startsWith("@catch_");
    }

    /** Mnemonics that may transfer control into this function's landing pads / catch labels (the callee unwinds into them). */
    private static final Set<String> MAY_UNWIND = new HashSet<>(Arrays.asList("CALL", "INVOKE", "EXTERN_CALL", "SLEEP"));

    /** Mnemonics after which control does not continue to the next line. */
    private static final Set<String> NO_FALLTHROUGH = new HashSet<>(Arrays.asList(
            "RET", "RET_FLOAT", "R_RET", "R_RETF", "GT_UNWIND", "EXIT", "EXIT_THREAD", "FUNC_END"));

    private static boolean isLabelDefLine(List<BytecodeToken> l) {
        return l.size() == 1 && l.get(0).text.startsWith("@") && l.get(0).text.endsWith(":");
    }

    /**
     * Register sharing. Two candidate variables may live in the same register only if no program point has both live, and neither is
     * written while the other is still live (a write to a variable that is never read still overwrites the register). Liveness is the
     * usual backward dataflow over the lines of the function (fallthrough, JMP, R_BRC/R_BRF, stack-form CMP+JMP, and an edge from every
     * call to every label whose address the function stages for unwinding). A line counts as a WRITE only when it is a plain store of
     * the whole slot (R_ST, R_MOV with the slot as destination, the assignment that ends a float "ADDR x ... ASSIGN" pair); every other
     * mention is a read, which can only make a range longer, never shorter. Candidates are coloured heaviest first; one that finds no
     * register free of its neighbours stays in memory. Floats of different width never share a register (the backend spills a register
     * with one width). Sets {@code Hint.reg} (-1 = not promoted).
     */
    private void shareRegisters(List<List<BytecodeToken>> fn, Map<Long, Hint> hints, List<int[]> pairs,
                                List<Hint> intCands, List<Hint> xCands, int intLimit) {
        List<Hint> all = new ArrayList<>(intCands.size() + xCands.size());
        all.addAll(intCands);
        all.addAll(xCands);
        int nh = all.size();
        Map<Long, Integer> idOf = new HashMap<>();
        for (int i = 0; i < nh; i++) {
            all.get(i).reg = -1;
            idOf.put(all.get(i).off, i);
        }
        int n = fn.size();
        // labels and branch targets
        Map<String, Integer> labels = new HashMap<>();
        for (int i = 0; i < n; i++) {
            if (isLabelDefLine(fn.get(i))) {
                String t = fn.get(i).get(0).text;
                labels.put(t.substring(0, t.length() - 1), i);
            }
        }
        List<Integer> padTargets = new ArrayList<>();
        java.util.Set<String> seenPad = new HashSet<>();
        for (List<BytecodeToken> l : fn) {
            if (l.isEmpty() || isLabelDefLine(l)) continue;
            String m = l.get(0).text;
            if (m.equals("JMP") || m.equals("R_BRC") || m.equals("R_BRF")) continue;
            for (int k = 1; k < l.size(); k++) {
                String t = l.get(k).text;
                if (t.startsWith("@") && labels.containsKey(t) && seenPad.add(t)) padTargets.add(labels.get(t));
            }
        }
        // successors
        int[][] succ = new int[n][];
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = fn.get(i);
            String m = l.isEmpty() ? "" : l.get(0).text;
            List<Integer> sc = new ArrayList<>(2);
            boolean fall = !NO_FALLTHROUGH.contains(m);
            if (m.equals("JMP") && l.size() == 2) {
                boolean cond = i > 0 && fn.get(i - 1).size() == 1 && fn.get(i - 1).get(0).text.equals("CMP");
                Integer tg = labels.get(l.get(1).text);
                if (tg != null) sc.add(tg);
                fall = cond;
            } else if ((m.equals("R_BRC") || m.equals("R_BRF")) && l.size() >= 3) {
                Integer tg = labels.get(l.get(l.size() - 1).text);
                if (tg != null) sc.add(tg);
            }
            if (fall && i + 1 < n) sc.add(i + 1);
            if (MAY_UNWIND.contains(m)) sc.addAll(padTargets);
            succ[i] = sc.stream().mapToInt(Integer::intValue).toArray();
        }
        // reads and writes per line
        java.util.BitSet[] use = new java.util.BitSet[n];
        java.util.BitSet[] def = new java.util.BitSet[n];
        for (int i = 0; i < n; i++) {
            use[i] = new java.util.BitSet(nh);
            def[i] = new java.util.BitSet(nh);
        }
        java.util.Set<Integer> approved = new HashSet<>();
        for (int[] pr : pairs) {
            approved.add(pr[0]);
            Integer id = idOf.get(pairSlotOf(fn, pr[0], hints));
            if (id == null) continue;
            def[pr[2] == 1 ? pr[3] : pr[1]].set(id);   // the store happens where the value lands: R_GETRETF / R_POPX
        }
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = fn.get(i);
            if (l.isEmpty() || isHint(l) || approved.contains(i)) continue;
            String m = l.get(0).text;
            for (int k = 1; k < l.size(); k++) {
                Hint h = slotHint(hints, l.get(k).text);
                if (h == null) continue;
                Integer id = idOf.get(h.off);
                if (id == null) continue;
                boolean store = (m.equals("R_ST") && k == 2 && l.size() == 4) || (m.equals("R_MOV") && k == 2 && l.size() == 4);
                if (store) def[i].set(id); else use[i].set(id);
            }
        }
        // backward liveness
        java.util.BitSet[] in = new java.util.BitSet[n];
        java.util.BitSet[] out = new java.util.BitSet[n];
        for (int i = 0; i < n; i++) {
            in[i] = new java.util.BitSet(nh);
            out[i] = new java.util.BitSet(nh);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = n - 1; i >= 0; i--) {
                java.util.BitSet o = new java.util.BitSet(nh);
                for (int s : succ[i]) o.or(in[s]);
                java.util.BitSet ni = (java.util.BitSet) o.clone();
                ni.andNot(def[i]);
                ni.or(use[i]);
                if (!o.equals(out[i])) { out[i] = o; changed = true; }
                if (!ni.equals(in[i])) { in[i] = ni; changed = true; }
            }
        }
        // interference
        java.util.BitSet[] adj = new java.util.BitSet[nh];
        for (int i = 0; i < nh; i++) adj[i] = new java.util.BitSet(nh);
        for (int i = 0; i < n; i++) {
            for (int d = def[i].nextSetBit(0); d >= 0; d = def[i].nextSetBit(d + 1)) {
                for (int v = out[i].nextSetBit(0); v >= 0; v = out[i].nextSetBit(v + 1)) {
                    if (v != d) { adj[d].set(v); adj[v].set(d); }
                }
            }
        }
        if (n > 0) {
            for (int a = in[0].nextSetBit(0); a >= 0; a = in[0].nextSetBit(a + 1)) {
                for (int b = in[0].nextSetBit(0); b >= 0; b = in[0].nextSetBit(b + 1)) {
                    if (a != b) adj[a].set(b);
                }
            }
        }
        // which candidates may use the caller-saved registers
        boolean sysv = true;
        for (List<BytecodeToken> l : fn) {
            if (!l.isEmpty() && l.get(0).text.equals("CC_START") && l.size() > 1 && !l.get(1).text.equals("sysv_x64")) sysv = false;
        }
        boolean[] volRegOk = {true, true, true, argRegs, argRegs, argRegs && sysv, argRegs && sysv};   // r8, r9, r10, rsi, rdi, rdx, rcx
        for (List<BytecodeToken> l : fn) {
            if (l.isEmpty()) continue;
            String m = l.get(0).text;
            if (m.equals("PUSH_RET_FLOAT") || m.equals("R_GETRETF")) volRegOk[2] = false;
            for (int k = 1; k < l.size(); k++) {
                String t = l.get(k).text;
                if (t.length() > 3 && t.startsWith("ARG") && Character.isDigit(t.charAt(3))) {
                    try {
                        if (Integer.parseInt(t.substring(3)) >= 2) { volRegOk[0] = false; volRegOk[1] = false; }
                    } catch (NumberFormatException e) {
                        volRegOk[0] = volRegOk[1] = volRegOk[2] = false;
                    }
                }
            }
        }
        boolean[] volOk = new boolean[nh];
        java.util.Arrays.fill(volOk, true);
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = fn.get(i);
            if (l.isEmpty() || isHint(l) || isLabelDefLine(l) || VOL_SAFE.contains(l.get(0).text)) continue;
            for (int v = 0; v < nh; v++) {
                if (in[i].get(v) || out[i].get(v) || def[i].get(v)) volOk[v] = false;
            }
        }
        for (int v = 0; v < nh; v++) all.get(v).volOk = volOk[v];
        // rsi/rdi: the call-argument registers, filled while a call bracket is open; a variable mentioned anywhere in a bracket stays out of them
        boolean[] argSafe = new boolean[nh];
        java.util.Arrays.fill(argSafe, argRegs);
        if (argRegs) {
            int depth = 0;
            for (int i = 0; i < n; i++) {
                List<BytecodeToken> l = fn.get(i);
                String m = l.isEmpty() ? "" : l.get(0).text;
                if (m.equals("CC_START")) depth++;
                if (depth > 0) {
                    for (int v = 0; v < nh; v++) {
                        if (in[i].get(v) || out[i].get(v) || def[i].get(v)) argSafe[v] = false;
                    }
                }
                if (m.equals("CC_END") && depth > 0) depth--;
            }
        }
        for (int v = 0; v < nh; v++) all.get(v).argSafe = argSafe[v];
        // rdx / rcx: kept out of variables live at the lines whose backend code uses them (constant divide: rdx; variable-count shift: rcx)
        boolean[] rdxOk = new boolean[nh];
        boolean[] rcxOk = new boolean[nh];
        java.util.Arrays.fill(rdxOk, true);
        java.util.Arrays.fill(rcxOk, true);
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = fn.get(i);
            if (l.isEmpty()) continue;
            String m = l.get(0).text;
            boolean usesRdx = m.equals("R_DIVC");
            boolean usesRcx = m.equals("R_BIN") && l.size() > 5 && (l.get(1).text.equals("SHL") || l.get(1).text.equals("SHR") || l.get(1).text.equals("SAR") || l.get(1).text.equals("ROTL") || l.get(1).text.equals("ROTR"))
                    && !l.get(5).text.startsWith("#");
            if (!usesRdx && !usesRcx) continue;
            for (int v = 0; v < nh; v++) {
                if (in[i].get(v) || out[i].get(v) || def[i].get(v)) {
                    if (usesRdx) rdxOk[v] = false;
                    if (usesRcx) rcxOk[v] = false;
                }
            }
        }
        for (int v = 0; v < nh; v++) {
            all.get(v).rdxOk = rdxOk[v];
            all.get(v).rcxOk = rcxOk[v];
        }
        // colour, heaviest first, per register file
        colourInt(all, intCands, adj, idOf, intLimit, volRegOk);
        colour(all, xCands, adj, idOf, XVAR_COUNT, true);
    }

    /** Integer colouring: callee-saved registers 0..intLimit-1, and for variables that may (volOk) also r8/r9/r10 as 3..5. */
    private static void colourInt(List<Hint> all, List<Hint> cands, java.util.BitSet[] adj, Map<Long, Integer> idOf, int intLimit,
                                  boolean[] volRegOk) {
        for (Hint h : cands) {
            int id = idOf.get(h.off);
            boolean[] taken = new boolean[VOL_FIRST + VOL_COUNT];
            for (int v = adj[id].nextSetBit(0); v >= 0; v = adj[id].nextSetBit(v + 1)) {
                Hint o = all.get(v);
                if (o.reg >= 0 && o.reg < taken.length && !o.xmm) taken[o.reg] = true;
            }
            List<Integer> order = new ArrayList<>();
            if (h.volOk) {
                for (int r = 0; r < VOL_COUNT; r++) if (volRegOk[r] && (r < VOL_BASE || h.argSafe) && !(r == 5 && !h.rdxOk) && !(r == 6 && !h.rcxOk)) order.add(VOL_FIRST + r);
            }
            for (int r = 0; r < intLimit; r++) order.add(r);
            for (int r : order) {
                if (!taken[r]) { h.reg = r; break; }
            }
        }
    }

    private static void colour(List<Hint> all, List<Hint> cands, java.util.BitSet[] adj, Map<Long, Integer> idOf, int regCount, boolean xmm) {
        int[] width = new int[regCount];
        for (Hint h : cands) {
            int id = idOf.get(h.off);
            boolean[] taken = new boolean[regCount];
            for (int v = adj[id].nextSetBit(0); v >= 0; v = adj[id].nextSetBit(v + 1)) {
                Hint o = all.get(v);
                if (o.reg >= 0 && o.reg < regCount && o.xmm == xmm) taken[o.reg] = true;
            }
            for (int r = 0; r < regCount; r++) {
                if (taken[r]) continue;
                if (xmm && width[r] != 0 && width[r] != h.size) continue;
                h.reg = r;
                if (xmm) width[r] = h.size;
                break;
            }
        }
    }

    private static long pairSlotOf(List<List<BytecodeToken>> fn, int addrIdx, Map<Long, Hint> hints) {
        Hint h = slotHint(hints, fn.get(addrIdx).get(2).text);
        return h == null ? Long.MIN_VALUE : h.off;
    }

    private static List<BytecodeToken> mk(BytecodeToken ref, String... texts) {
        List<BytecodeToken> r = new ArrayList<>(texts.length);
        for (String t : texts) {
            r.add(new BytecodeToken(t, ref.file, ref.line, BytecodeToken.Kind.CODE));
        }
        return r;
    }

    /**
     * Peephole over the rewritten function: a value that is computed into a temp and immediately moved into a float variable
     * is computed straight into the variable's register instead (the temp must be dead afterwards, see {@link #tempDeadAfter}).
     */
    private static List<List<BytecodeToken>> fuse(List<List<BytecodeToken>> res) {
        List<List<BytecodeToken>> o = new ArrayList<>(res.size());
        int i = 0;
        while (i < res.size()) {
            List<BytecodeToken> a = res.get(i);
            List<BytecodeToken> b = i + 1 < res.size() ? res.get(i + 1) : null;
            String am = a.isEmpty() ? "" : a.get(0).text;
            // R_GTOX n %xK %tN
            if (b != null && b.size() == 4 && b.get(0).text.equals("R_GTOX") && b.get(3).text.startsWith("%t")) {
                String tmp = b.get(3).text;
                String xk = b.get(2).text;
                String bw = b.get(1).text;
                if (am.equals("R_LD") && a.size() == 4 && a.get(1).text.equals(bw) && a.get(2).text.equals(tmp)
                        && tempDeadAfter(res, i + 2, tmp)) {
                    o.add(mk(a.get(0), "R_LDX", bw, xk, a.get(3).text));
                    i += 2;
                    continue;
                }
                if (am.equals("R_FBIN") && a.size() == 6 && a.get(2).text.equals(bw) && a.get(3).text.equals(tmp)
                        && tempDeadAfter(res, i + 2, tmp)) {
                    o.add(mk(a.get(0), "R_FBINX", a.get(1).text, bw, xk, a.get(4).text, a.get(5).text));
                    i += 2;
                    continue;
                }
            }
            // R_XTOG n %tN %xK ; R_ST n addr %tN
            if (am.equals("R_XTOG") && a.size() == 4 && b != null && b.size() == 4 && b.get(0).text.equals("R_ST")
                    && b.get(1).text.equals(a.get(1).text) && b.get(3).text.equals(a.get(2).text) && !b.get(2).text.equals(a.get(2).text)
                    && tempDeadAfter(res, i + 2, a.get(2).text)) {
                o.add(mk(a.get(0), "R_STX", a.get(1).text, b.get(2).text, a.get(3).text));
                i += 2;
                continue;
            }
            o.add(a);
            i++;
        }
        return o;
    }

    /** stack-form mnemonics that may sit between "ADDR 4 $x" and its "ASSIGN 4 4 4" without touching the pushed address */
    private static final java.util.Set<String> VALUE_OPS = new HashSet<>(Arrays.asList(
            "PUSH", "PUSH_RET_FLOAT", "PUSH_RET_INT", "PUSH_LABEL", "ADD_FLOAT", "SUB_FLOAT", "MUL_FLOAT", "DIV_FLOAT",
            "ADD_INT", "SUB_INT", "MUL_INT", "CC_START", "CC_END", "CALL", "VARARGS_XMM_COUNT", "PROMOTE_F32_TO_F64", "FCONV", "NEG_FLOAT",
            // pure stack operators of integer value computations (each pops only its own operands, pushes one result)
            "DIV_INT", "MOD_INT", "SDIV_INT", "SMOD_INT", "SHL", "SHR", "SAR", "ROTL", "ROTR", "BITS_AND", "BITS_OR", "BITS_XOR", "BITS_NOT", "NEG", "NOT",
            "INC_INT", "DEC_INT", "EQ_INT", "NEQ_INT", "LT_INT", "LT_EQ_INT", "GT_INT", "GT_EQ_INT", "SLT_INT", "SLT_EQ_INT", "SGT_INT",
            "SGT_EQ_INT", "AND", "OR", "ZEXT", "TRUNC", "DEREF", "LEN", "LOOKUP_DYN", "LOOKUP_ARRAY", "DOT", "PUSH_FIELDNAME", "DOT_LHS",
            "LOOKUP_DYN_LHS", "LOOKUP_ARRAY_LHS"));

    private static final java.util.Set<String> BLOCK_END = new HashSet<>(Arrays.asList(
            "JMP", "R_BRF", "R_RET", "R_RETF", "RET", "RET_FLOAT", "GT_UNWIND", "THROW", "CC_START", "CC_END", "CALL", "INVOKE", "FUNC_END"));

    /**
     * True when temp t is not read again after position from: scan forward; a read means "live", a write first means "dead", and the
     * end of the straight-line run (a label, a jump, a call bracket, a return) means "dead" too -- the register-form pass flushes
     * its operand model at all of those, so no temp is ever live across them.
     */
    private static boolean tempDeadAfter(List<List<BytecodeToken>> res, int from, String t) {
        for (int i = from; i < res.size() && i < from + 60; i++) {
            List<BytecodeToken> l = res.get(i);
            if (l.isEmpty()) {
                continue;
            }
            String m = l.get(0).text;
            if (l.size() == 1 && m.startsWith("@") && m.endsWith(":")) {
                return true;
            }
            if (BLOCK_END.contains(m)) {
                // a return/branch may still read the temp as its operand
                for (int k = 1; k < l.size(); k++) {
                    if (l.get(k).text.equals(t)) {
                        return false;
                    }
                }
                return true;
            }
            int dst = dstIndex(m);
            boolean read = false;
            boolean write = false;
            for (int k = 1; k < l.size(); k++) {
                if (l.get(k).text.equals(t)) {
                    if (k == dst) {
                        write = true;
                    } else {
                        read = true;
                    }
                }
            }
            if (read) {
                return false;
            }
            if (write) {
                return true;
            }
        }
        return true;
    }

    /** operand position of the destination temp of a register-form instruction, or -1 */
    private static int dstIndex(String m) {
        switch (m) {
            case "R_LD": case "R_MOV": case "R_XTOG": return 2;
            case "R_LEA": return 1;
            case "R_BIN": case "R_UN": case "R_FBIN": case "R_FCMP": case "R_DIVC": return 3;
            default: return -1;
        }
    }

    private static Hint slotHint(Map<Long, Hint> hints, String tok) {
        if (tok.length() < 2 || tok.charAt(0) != '$') {
            return null;
        }
        try {
            return hints.get(Long.parseLong(tok.substring(1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intOf(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** May operand k of this line name the hinted slot h (and be renamed to its register)? */
    private static boolean mentionOk(String m, List<BytecodeToken> l, int k, Hint h) {
        switch (m) {
            case "R_MOV": // R_MOV 8 dst src
                return h.size == 8 && l.size() == 4 && (k == 2 || k == 3);
            case "R_LD": // R_LD n %tD addr
                return l.size() == 4 && k == 3 && intOf(l.get(1).text) == h.size;
            case "R_ST": // R_ST n addr src  (the address only)
                return l.size() == 4 && k == 2 && intOf(l.get(1).text) == h.size;
            case "R_LEA": // R_LEA %tD base idx scale [disp] -- the index value, never the base address
                return (l.size() == 5 || l.size() == 6) && k == 3 && h.size == 8;
            case "R_BIN": // R_BIN OP size %tD a b -- only full-width operands (a narrow op loads its operands first)
                return l.size() == 6 && (k == 4 || k == 5) && h.size == 8 && intOf(l.get(2).text) == 8;
            case "R_UN": // R_UN OP size %tD a
                return l.size() == 5 && k == 4 && h.size == 8;
            case "R_DIVC": // R_DIVC OP 8 %tD a #k -- the dividend only
                return l.size() == 6 && k == 4 && h.size == 8;
            case "R_FBIN":
            case "R_FCMP": // R_FBIN OP size %tD a b -- an operand read straight from memory has the op's own width
                return l.size() == 6 && (k == 4 || k == 5) && intOf(l.get(2).text) == h.size;
            case "R_RMW": // R_RMW OP 8 $x [b]
                return h.size == 8 && (k == 3 || (k == 4 && l.size() == 5));
            case "R_ARG":
            case "R_FARG": // R_ARG n size src
                return l.size() == 4 && k == 3 && intOf(l.get(2).text) == h.size;
            case "R_RET":
            case "R_RETF": // R_RET src
                return l.size() == 2 && k == 1 && h.size == 8;
            case "R_PUSH": // R_PUSH 8 src
                return l.size() == 3 && k == 2 && h.size == 8;
            default:
                return false; // anything else (PUSH, ADDR, ASSIGN, R_PUSHA, R_ARGA, ...) needs the slot in memory
        }
    }

    /** May operand k of this line name the float-variable slot h (and be renamed to its xmm register)? */
    private static boolean mentionOkX(String m, List<BytecodeToken> l, int k, Hint h) {
        switch (m) {
            case "R_LD": // R_LD n %tD $x
                return l.size() == 4 && k == 3 && intOf(l.get(1).text) == h.size;
            case "R_ST": // R_ST n $x src (the address only)
                return l.size() == 4 && k == 2 && intOf(l.get(1).text) == h.size;
            case "R_MOV": { // R_MOV 8 %tD $x (load) / R_MOV 8 $x %tS|#imm (store): how RegisterFormPass spells every 8-byte slot access
                if (!(l.size() == 4 && h.size == 8 && intOf(l.get(1).text) == 8)) return false;
                String other = l.get(k == 2 ? 3 : 2).text;
                if (k == 3) return other.startsWith("%t");                       // load into a temp
                return k == 2 && (other.startsWith("%t") || other.startsWith("#")); // store from a temp or an immediate
            }
            case "R_FBIN":
            case "R_FCMP": // R_FBIN OP n %tD a b
                return l.size() == 6 && (k == 4 || k == 5) && intOf(l.get(2).text) == h.size;
            case "R_ARG":
            case "R_FARG": // R_ARG n size src
                return l.size() == 4 && k == 3 && intOf(l.get(2).text) == h.size;
            case "R_RETF": // R_RETF src
                return l.size() == 2 && k == 1;
            case "PUSH": // PUSH n $x
                return l.size() == 3 && k == 2 && intOf(l.get(1).text) == h.size;
            default:
                return false;
        }
    }

    private static List<BytecodeToken> rewrite(List<BytecodeToken> l, Map<Long, Hint> chosenInt, Map<Long, Hint> chosenX) {
        if (l.isEmpty()) {
            return l;
        }
        String m = l.get(0).text;
        boolean touchesInt = false;
        boolean touchesX = false;
        List<String> t = new ArrayList<>(l.size());
        for (int k = 0; k < l.size(); k++) {
            String s = l.get(k).text;
            if (k > 0) {
                Hint hi = slotHint(chosenInt, s);
                Hint hx = hi == null ? slotHint(chosenX, s) : null;
                if (hi != null) {
                    s = "%v" + hi.reg;
                    touchesInt = true;
                } else if (hx != null) {
                    s = "%x" + hx.reg;
                    touchesX = true;
                }
            }
            t.add(s);
        }
        if (!touchesInt && !touchesX) {
            return l;
        }
        if (touchesX) {
            if (m.equals("R_LD")) {
                // R_LD n %tD $x -> R_XTOG n %tD %xK
                t = new ArrayList<>(Arrays.asList("R_XTOG", t.get(1), t.get(2), t.get(3)));
            } else if (m.equals("R_ST")) {
                // R_ST n $x src -> R_GTOX n %xK src
                t = new ArrayList<>(Arrays.asList("R_GTOX", t.get(1), t.get(2), t.get(3)));
            } else if (m.equals("R_MOV")) {
                // R_MOV 8 %tD %xK -> R_XTOG 8 %tD %xK ; R_MOV 8 %xK src -> R_GTOX 8 %xK src
                t = new ArrayList<>(Arrays.asList(t.get(2).startsWith("%x") ? "R_GTOX" : "R_XTOG", t.get(1), t.get(2), t.get(3)));
            }
        } else if (m.equals("R_LD")) {
            // the variable register already holds the zero-extended value
            t.set(0, "R_MOV");
            t.set(1, "8");
        } else if (m.equals("R_ST")) {
            if (intOf(l.get(1).text) == 8) {
                t.set(0, "R_MOV");
            } else {
                t.set(0, "R_SETV");
            }
        }
        BytecodeToken a = l.get(0);
        List<BytecodeToken> r = new ArrayList<>(t.size());
        for (String s : t) {
            r.add(new BytecodeToken(s, a.file, a.line, BytecodeToken.Kind.CODE));
        }
        return r;
    }
}
