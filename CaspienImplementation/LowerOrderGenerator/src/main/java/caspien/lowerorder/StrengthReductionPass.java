package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;


/**
 * Unsigned division and modulo by a constant power of two become a shift and a mask (always on; a pure code-quality
 * rewrite, it cannot change a result).
 *
 *   PUSH 8 K ; DIV_INT 8   ->   PUSH 8 n ; SHR 8        (K = 2^n, n >= 1)
 *   PUSH 8 K ; MOD_INT 8   ->   PUSH 8 K-1 ; BITS_AND 8  (K = 2^n, 2 <= K <= 2^31)
 *
 * Only the 8-byte operator on a literal divisor sitting directly before it, and only the unsigned mnemonics (the signed
 * SDIV_INT / SMOD_INT round toward zero and are left alone). A divisor of 1 and any other value are untouched, and
 * division by zero never reaches here (the language rejects a literal zero divisor).
 *
 * BITS_AND is a new stack-form mnemonic (pop two, and, push); the register-form pass turns both results into
 * R_BIN SHR / R_BIN BAND with an immediate.
 */
public class StrengthReductionPass {

    public PassResult run(BytecodeProgram lines) { return null; }

    /** PUSH 8 <non-negative decimal literal> */
    private static boolean isConstPush(BytecodeStatement l) { return false; }

    private static BytecodeStatement push(BytecodeStatement old, long v) { return null; }

    private static BytecodeStatement mnem(BytecodeStatement old, String m) { return null; }
}
