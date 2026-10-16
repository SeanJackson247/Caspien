package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;
import caspien.bytecode.BytecodeToken;
import caspien.bytecode.BytecodeTokenKind;

import java.util.HashSet;
import java.util.Set;

/**
 * libm's square root as one instruction (no switch of its own: it only fires on the float register-form text, so it is
 * off whenever the float registers are off). The call sequence of a user extern that IS libm's sqrt/sqrtf,
 *
 *   CC_START conv ; <argument expression> ; R_FARG 0 n SRC ; CALL sqrt 1 ; CC_END conv ; R_GETRETF n DST
 *
 * with SRC and DST both xmm-register operands (%xK variable or %yK temporary) becomes
 *
 *   R_FSQRT n DST SRC          (sqrtsd / sqrtss)
 *
 * so there is no call, no argument shuffling and, above all, no spill and reload of the float variables that live in xmm
 * registers around the call. Only an extern declared exactly as {@code EXTERN sqrt mut_f64 mut_f64} (sqrtf: mut_f32) is
 * touched; the one visible difference from the library call is that errno is not set for a negative argument (the result is
 * the same NaN). Runs after RegVarPromotionPass and FloatTempPass, which create the operands it matches.
 */
public class FloatIntrinsicPass {

    public BytecodeProgram run(BytecodeProgram lines) { return null; }

    private static boolean xmm(String tok) { return false; }

    /** i = the R_FARG line; the call, CC_END and R_GETRETF follow it. */
    private static boolean matches(BytecodeProgram L, int i, Set<String> externs) { return false; }
}
