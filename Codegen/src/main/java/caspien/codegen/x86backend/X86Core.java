package caspien.codegen.x86backend;

import caspien.codegen.CodegenTarget;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;
import caspien.codegen.CodegenException;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;

/** Shared state of the x86-64 backend; see README.md, "X86Core.java". */
final class X86Core {

    X86Core(CodegenTarget target, boolean bmi2) {
        this.target = target;
        this.bmi2 = bmi2;
    }

    final CodegenTarget target;

    final StringBuilder out = new StringBuilder();

    // Callee-saved preservation (CSR_MARK): see README.md, "Callee-saved register preservation".
    static final String CSR_MARK = "@@CSR@@";

    int csrFuncStart = -1;

    int csrAllocPos = -1;

    long csrAllocBytes = 0;

    String csrAllocLine = null;

    /** Sum of every ALLOC (16-aligned) of the current function: the frame size below rbp, once the callee-saved area is added (finishCalleeSaved). */
    long csrAllocSum = 0;

    /** A catch landing is reached by an unwind that skipped the call's own rsp restore (and any operand words pushed): the mark becomes `rsp = rbp - frame` once the frame size is final. */
    static final String RSP_MARK = "@@RSP@@";

    final LinkedHashMap<String, long[]> globalsSizeInit = new LinkedHashMap<>();

 // name -> [size, init] (init may be absent -> bss)
    final LinkedHashMap<String, Boolean> globalsHasInit = new LinkedHashMap<>();

    final LinkedHashMap<String, String> stringLiterals = new LinkedHashMap<>();

 // id -> text
    final LinkedHashMap<String, String> floatPool = new LinkedHashMap<>();

    final HashSet<String> declaredFunctionNames = new HashSet<>();

    final LinkedHashMap<String, Long> globalAliasOffset = new LinkedHashMap<>();

 // dotted name -> byte offset into its parent
    final LinkedHashMap<String, List<long[]>> aliasInits = new LinkedHashMap<>();

 // parent -> [offset, size, bits] of each initialised element
    final LinkedHashMap<String, String> globalAliasParent = new LinkedHashMap<>();

 // dotted name -> parent global name
    List<BytecodeStatement> allLines;

    int currentLineIndex;

    int asmSkipUntilIndex = -1;

    // See README.md, "Construction-scoped push detection".
    final HashMap<Integer, Long> pushStoreOffsetByLine = new HashMap<>();

    // See README.md, "Call-argument stack tracking and CC_START buffering".
    final Deque<Long> argTrackDeltaStack = new ArrayDeque<>();

    int argTrackSuspendDepth = 0;

    final Deque<StringBuilder> callBufferStack = new ArrayDeque<>();

    final Deque<List<String>> loadedArgRegsStack = new ArrayDeque<>();

    final Deque<List<String>> savedOuterArgRegsStack = new ArrayDeque<>();

    boolean floatResultInR10 = false;

    int pendingVarargsXmmCount = -1;

    final HashSet<Integer> constructionScopedAssign = new HashSet<>();

    final HashSet<Integer> packedNewLines = new HashSet<>();

    final boolean bmi2;

    /** `avx on`: R_FBINX uses VEX 3-operand scalar forms */
    public boolean avx;

    int strideCseHits = 0;

    StringBuilder strideKeySink = null;

    String strideKeyIdx = null;

    long strideKeyScale = 0;

    static final HashSet<String> STRIDE_KEEP_OPS = new HashSet<>(Arrays.asList(
            "movq", "movl", "movw", "movb", "movzbq", "movzwq", "movzbl", "movzwl", "movslq", "movsbq", "movswq", "movsbl", "movswl",
            "movsd", "movss", "movd", "movaps", "movapd", "addsd", "addss", "subsd", "subss", "mulsd", "mulss", "divsd", "divss",
            "addq", "subq", "addl", "subl", "leaq", "andq", "orq", "xorq", "shlq", "shrq", "sarq", "incq", "decq", "negq", "notq",
            "sqrtsd", "sqrtss", "ucomisd", "ucomiss", "comisd", "comiss", "cvtss2sd", "cvtsd2ss", "xorps", "xorpd", "andps", "andpd"));

    /** whether the register spelling `r` (rax, eax, ax, al, r13d, r13b ...) names (part of) the 64-bit register `full` */
    static boolean regAliasOf(String r, String full) {
        if (r.equals(full)) {
            return true;
        }
        if (full.matches("r\\d+")) {
            return r.equals(full + "d") || r.equals(full + "w") || r.equals(full + "b");
        }
        String base = full.substring(1);     // rax -> ax, rsi -> si
        if (full.equals("rax") || full.equals("rbx") || full.equals("rcx") || full.equals("rdx")) {
            String l = base.substring(0, 1);
            return r.equals("e" + base) || r.equals(base) || r.equals(l + "l") || r.equals(l + "h");
        }
        return r.equals("e" + base) || r.equals(base) || r.equals(base + "l");
    }

    static int roundUpToLoadableWidth(int size) {
        if (size <= 2) {
            return size;
        } else if (size <= 4) {
            return 4;
        } else {
            return 8;
        }
    }

    /** 3, 5, 6 and 7 bytes: no single x86 store has these widths. */
    static boolean isOddSize(int size) {
        return size == 3 || (size >= 5 && size <= 7);
    }

    static boolean isFloatLiteral(String s) {
        return s.matches("-?\\d+\\.\\d+([eE][+-]?\\d+)?");
    }

    /** "'a'", "'\n'", "'\0'", ... -- a char-literal PUSH operand, exactly as `BytecodeEmitter.escapeForBytecode` serializes one: an opening single quote, one real character's worth of content, a closing single quote. Deliberately checked structurally (quote ... quote) rather than by first ruling out every other operand shape, since a bare identifier can never itself start *and* end with `'`. */
    static boolean isCharLiteral(String s) {
        return s.length() >= 3 && s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'';
    }

    static long decodeCharLiteral(BytecodeToken operandTok) {
        String operand = operandTok.text;
        String content = operand.substring(1, operand.length() - 1);
        char value;
        if (content.length() == 1) {
            value = content.charAt(0);
        } else if (content.length() == 2 && content.charAt(0) == '\\') {
            char esc = content.charAt(1);
            if (esc == '\\') {
                value = '\\';
            } else if (esc == '"') {
                value = '"';
            } else if (esc == 'n') {
                value = '\n';
            } else if (esc == 't') {
                value = '\t';
            } else if (esc == 'r') {
                value = '\r';
            } else if (esc == '0') {
                value = '\0';
            } else if (esc == 'b') {
                value = '\b';
            } else if (esc == 'f') {
                value = '\f';
            } else if (esc == 'a') {
                value = 7; // bell -- no Java char-literal shorthand
            } else if (esc == 'v') {
                value = 11; // vertical tab -- no Java char-literal shorthand
            } else {
                throw new CodegenException("codegen", operandTok.file, operandTok.line,
                        "unrecognized char-literal escape '" + operand + "'");
            }
        } else {
            throw new CodegenException("codegen", operandTok.file, operandTok.line,
                    "malformed char-literal operand '" + operand + "'");
        }
        return value;
    }

    // ---- Calling convention ------------------------------------------------

    static final String[] LINUX_ARG_REGS = {"rdi", "rsi", "rdx", "rcx", "r8", "r9"};

    static final String[] WIN_ARG_REGS = {"rcx", "rdx", "r8", "r9"};

    static final String[] LINUX_ARG_REGS_FLOAT =
            {"xmm0", "xmm1", "xmm2", "xmm3", "xmm4", "xmm5", "xmm6", "xmm7"};

    static final String[] WIN_ARG_REGS_FLOAT = {"xmm0", "xmm1", "xmm2", "xmm3"};

    // ---- Main per-line dispatch --------------------------------------------

    static final String V1314_SAVE = "@@V1314S@@";

    static final String V1314_REST = "@@V1314R@@";

    /** Bytes pushed on top of RESIZE's wide fill block while its fill loop runs (the saved old block start). */
    int resizeExtraStack = 0;

    boolean lastWasCmp = false;

    /** See the "value-block" handling threaded through PUSH/LOOKUP_ARRAY/DOT: the byte size of a multi-word value most recently pushed *by value* (as opposed to by address), still sitting on top of the real stack, for a following LOOKUP_ARRAY/DOT to extract a sub-range from -- 0 means "no pending value-block; use the ordinary address-based path." */
    long lastValueBlockSize = 0;

    boolean pushedWordWithTag = false;

 // the previous line was an 8-byte PUSH that passed a pending value tag on (see the line emitter prologue)
    String currentFuncName;

    int internalLabelCounter = 0;

    /** "@name" -> "L_name" (a real label can't contain '@'); a bare name with no '@' passes through unchanged -- the identical rule a label *definition* and every JMP/PUSH_LABEL *reference* to it must both apply, so they always agree. */
    // Register-form operands (#imm, $off, %tN): see README.md, "Register-form instructions".

    static final String[] RF_TEMP_REGS = { "rax", "rbx", "r11", "r12" };

    static final String RF_SCRATCH = "r15";

    static final String[] RF_VAR_REGS = { "r13", "r14", "r12", "r8", "r9", "r10", "rsi", "rdi", "rdx", "rcx" };

    /** set when this function uses a %vN; checked against rfClobberSeen at FUNC_END */
    boolean rfVarUsed = false;

    /** bit i set when this function uses %vi (i < 3: r13, r14, r12), see finishCalleeSaved / V1314_SAVE */
    int rfVarMask = 0;

    /** set when this function has an instruction whose code uses r13/r14 internally (see RegVarPromotionPass.R13_R14_USERS) */
    boolean rfClobberSeen = false;

    static final HashSet<String> RF_R13_R14_USERS = new HashSet<>(Arrays.asList(
            "NEW", "NEW_DYN", "NEW_UDYN", "NEW_FROM_STRING", "NEW_FROM_USTRING", "CLONE", "CLONE_DYN", "RESIZE", "URESIZE", "DOT", "LOOKUP_ARRAY", "ASM_START"));

    /** a register operand: a temp, the scratch register or a promoted variable */
    static boolean rfIsTemp(String tok) {
        return tok.startsWith("%t") || tok.startsWith("%v") || tok.equals("%s");
    }

    static boolean rfIsVar(String tok) {
        return tok.startsWith("%v");
    }

    static boolean rfIsImm(String tok) {
        return tok.startsWith("#");
    }

    static boolean rfIsSlot(String tok) {
        return tok.startsWith("$");
    }

    static long rfImm(String tok) {
        String t = tok.substring(1);
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return Long.parseUnsignedLong(t);
        }
    }

    static long rfSlot(String tok) {
        return Long.parseLong(tok.substring(1));
    }

    static boolean rfFitsImm32(long v) {
        return v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE;
    }

    static long[] divMagicSimple(long k) {
        BigInteger bk0 = new BigInteger(Long.toUnsignedString(k));
        int tz = Long.numberOfTrailingZeros(k);
        int[] pres;
        if (tz > 0) {
            pres = new int[] {tz, 0};
        } else {
            pres = new int[] {0};
        }
        BigInteger limit = BigInteger.ONE.shiftLeft(64);
        for (int pre : pres) {
            BigInteger kk = bk0.shiftRight(pre);
            if (kk.compareTo(BigInteger.ONE) <= 0) {
                continue;
            }
            int n = 64 - pre;
            for (int l = pre; l <= 64; l++) {
                BigInteger p = BigInteger.ONE.shiftLeft(n + l);
                BigInteger m = p.add(kk).subtract(BigInteger.ONE).divide(kk);
                if (m.compareTo(limit) >= 0) {
                    break;
                }
                BigInteger e = m.multiply(kk).subtract(p);
                if (e.compareTo(BigInteger.ONE.shiftLeft(l)) <= 0) {
                    return new long[] {pre, m.longValue(), l - pre};
                }
            }
        }
        return null;
    }

    /** the constants rfMulByConst turns into shifts / lea / shift+add (kept in step with it) */
    static boolean mulByConstShape(long k) {
        if (k < 2 || k > (1L << 31)) {
            return false;
        }
        return Long.bitCount(k) == 1 || k == 3 || k == 5 || k == 9 || Long.bitCount(k - 1) == 1 || Long.bitCount(k + 1) == 1;
    }

    /** The conditional jump taken when `a OP b` is false (unsigned and signed compares; shared by R_BRC and R_BRCM). */
    static String rfFalseJump(String op) {
        if (op.equals("EQ")) {
            return "jne";
        } else if (op.equals("NEQ")) {
            return "je";
        } else if (op.equals("LT")) {
            return "jae";
        } else if (op.equals("LT_EQ")) {
            return "ja";
        } else if (op.equals("GT")) {
            return "jbe";
        } else if (op.equals("GT_EQ")) {
            return "jb";
        } else if (op.equals("SLT")) {
            return "jge";
        } else if (op.equals("SLT_EQ")) {
            return "jg";
        } else if (op.equals("SGT")) {
            return "jle";
        } else if (op.equals("SGT_EQ")) {
            return "jl";
        } else {
            throw new IllegalStateException("unknown R_BRC operator '" + op + "'");
        }
    }

    /** Jump to `label` when NOT (a op b), for an 8-byte compare; a and b are %t/%v registers, $off slots or #imm (never both #imm). */
    /** the jump condition after swapping the two compared operands (a < b is b > a) */
    static String rfMirrorJump(String cc) {
        if (cc.equals("jae")) {
            return "jbe";
        } else if (cc.equals("ja")) {
            return "jb";
        } else if (cc.equals("jbe")) {
            return "jae";
        } else if (cc.equals("jb")) {
            return "ja";
        } else if (cc.equals("jge")) {
            return "jle";
        } else if (cc.equals("jg")) {
            return "jl";
        } else if (cc.equals("jle")) {
            return "jge";
        } else if (cc.equals("jl")) {
            return "jg";
        } else {
            return cc; // je / jne are symmetric
        }
    }

    /** the condition-code suffix that is TRUE exactly when the false-jump `jcc` is not taken ("jne" -> "e") */
    static String rfTrueSuffix(String falseJump) {
        if (falseJump.equals("jne")) {
            return "e";
        } else if (falseJump.equals("je")) {
            return "ne";
        } else if (falseJump.equals("jae")) {
            return "b";
        } else if (falseJump.equals("jb")) {
            return "ae";
        } else if (falseJump.equals("ja")) {
            return "be";
        } else if (falseJump.equals("jbe")) {
            return "a";
        } else if (falseJump.equals("jge")) {
            return "l";
        } else if (falseJump.equals("jl")) {
            return "ge";
        } else if (falseJump.equals("jg")) {
            return "le";
        } else if (falseJump.equals("jle")) {
            return "g";
        } else {
            throw new IllegalStateException("unknown condition '" + falseJump + "'");
        }
    }

    static long rfExtImm(long v, int size, boolean signed) {
        if (size >= 8) {
            return v;
        }
        long t = rfTrunc(v, size);
        if (signed) {
            return t;
        }
        return t & ((1L << (8 * size)) - 1);
    }

    // ---- register-form: memory, address, float, read-modify-write helpers ----------------------------------------

    static boolean rfIsGlobal(String tok) {
        return tok.startsWith("&");
    }

    static long rfTrunc(long v, int n) {
        if (n >= 8) {
            return v;
        }
        int sh = 64 - 8 * n;
        return (v << sh) >> sh;
    }

    /** When set, loadSizedFromAddr / storeSizedToAddr address "(base + index*scale)" instead of "(base)" (R_LDI / R_STI only; sizes 1, 2, 4, 8). */
    String rfIdxReg = null;

    long rfIdxScale = 1;

    /** constant displacement added to the next indexed memory operand (a frame-slot base: `off(%rbp,%idx,scale)`) */
    long rfIdxDisp = 0;

    /** When set, the memory operand of the next sized load/store is this text (a RIP-relative global), whatever the address register. */
    String rfMemOverride = null;

    static boolean rfHwScale(long s) {
        return s == 1 || s == 2 || s == 4 || s == 8;
    }


    static final int XV_COUNT = 6;

    final TreeMap<Integer, Long> xvHome = new TreeMap<>();

    final TreeMap<Integer, Integer> xvWidth = new TreeMap<>();

    static boolean rfIsXvar(String tok) {
        return tok.startsWith("%x") || tok.startsWith("%y") || tok.startsWith("%z");
    }

    /** float temporaries %y0..%y3 (FloatTempPass): SysV xmm4-7, win64 xmm12-15 (win64 keeps them; a temp is never live across a call anyway). */
    static final int YT_COUNT = 4;


    /** frame slot of the saved copy of r13, r14, r12 (null: the function keeps no variable there) */
    final String[] vSlots = new String[3];

    static boolean isInteger(String s) {
        if (s.isEmpty()) {
            return false;
        }
        int start;
        if (s.charAt(0) == '-') {
            start = 1;
        } else {
            start = 0;
        }
        if (start == s.length()) {
            return false;
        }
        for (int i = start; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static List<String> textsOf(BytecodeStatement line) {
        List<String> out = new ArrayList<>();
        for (BytecodeToken t : line) {
            out.add(t.text);
        }
        return out;
    }
}
