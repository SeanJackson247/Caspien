package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeStatement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import caspien.bytecode.Text;

/**
 * "ARG-to-ALLOC lowering" -- the transformation that erases the
 * compiler's own bare `ARG name type` signature-annotation line
 * entirely, replacing it with a real `ALLOC name type` frame slot plus
 * an explicit load sequence moving the parameter's actual incoming
 * value into it. Confirmed directly this belongs here, in the optimizer,
 * not in the compiler: "i just said i wanted the ARG to ALLOC
 * conversions to take place in the optimizer" -- an earlier version of
 * this change lived entirely in `caspien-compiler`'s own
 * `BytecodeEmitter` (`emitParamAllocs`/`emitParamLoads`), which was
 * wrong from the start. `ARG name type` is a pure signature fact (a
 * name and a canonical type, nothing more) the type checker already
 * fully owns; turning it into a real frame address and a load sequence
 * is genuine high-order-to-low-order lowering work, the same category
 * of work `AddressLoweringPass` already does for every other name/type
 * in the program. This pass runs immediately before `AddressLoweringPass`
 * in `BytecodeOptimizer.optimize` -- after it, every parameter is just
 * an ordinary `ALLOC`'d local as far as the rest of the pipeline is
 * concerned, with no special-casing left anywhere else.
 *
 * Per parameter, in declared order: `ADDR name type`, then one
 * `PUSH ARGn type` per word this parameter's canonical type breaks down
 * into (`n` a simple, uniformly incrementing logical word index across
 * this *function's entire parameter list* -- never reset at a register-
 * count boundary, since this pass has no calling-convention data of its
 * own and doesn't need any: deciding which `ARGn` values are register-
 * transferred versus stack-passed, and computing a stack-passed word's
 * own real, positive base-pointer-relative offset, is entirely
 * `AddressLoweringPass`'s job, immediately after this one -- see that
 * pass's own "Parameter word classification" doc comment), then one
 * `ASSIGN type type type`. No new mnemonics: `PUSH ARGn` is the same
 * plain, pre-existing `PUSH` every literal/local push already uses.
 *
 * **Frame position, the one genuinely tricky part:** every parameter's
 * own `ALLOC` must land "the first of the ALLOCs for that function" --
 * ahead of every ordinary local's own `ALLOC` (wherever in the body its
 * `let` actually appears), but still *after* `gt_routine_address`'s own
 * single reserved-slot `ALLOC` when that's present at all (its own
 * fixed, function-count-independent offset from the base pointer this
 * project already depends on in every frame would otherwise shift by
 * however many parameters each individual function happens to declare).
 * The compiler emits `ARG` lines textually *before* `gt_routine_address`
 * ever appears (right after `RETURNS`, the identical position the
 * original, pre-ARG-to-ALLOC `ARG` line always occupied) -- so this pass
 * can't just replace the `ARG` lines in place; it has to remove them
 * from there and re-insert the synthesized parameter `ALLOC`s at the
 * *correct* frame position instead:
 *
 *   1. Copy the function's own header lines (`FUNC_START`, `EXPORT`,
 *      every `FUNC_DECORATE`, `RETURNS`) through unchanged, collecting
 *      each `ARG name type` line's `name`/`type` instead of copying it
 *      (so no `ARG` line survives into the output at all).
 *   2. If the very next line is `ALLOC gt_routine_address code_addr`,
 *      copy it through first, then insert every parameter's own `ALLOC`
 *      right after it. Otherwise (a program using no `owns`/`ref`/
 *      `dyn`/`new`/`throw` anywhere gets no `gt_routine_address` `ALLOC`
 *      in *any* function at all -- see the sibling `caspien-compiler`
 *      project's `emitGtRoutineAlloc`) insert the parameter `ALLOC`s
 *      right there instead, ahead of whatever comes next (an ordinary
 *      local's own `ALLOC`, or straight into the function's body if it
 *      has no locals either).
 *   3. Copy through the rest of the function's own already-contiguous
 *      `ALLOC` run (ordinary locals, "Whole-function ALLOC hoisting"
 *      already guarantees these are all contiguous from here) --
 *      now immediately preceded by the parameter `ALLOC`s from step 2,
 *      so the whole run stays one single contiguous block.
 *   4. Insert every parameter's own load sequence (`ADDR`/`PUSH ARGn`/
 *      `ASSIGN`) right there, in declared order -- "after all the
 *      ALLOCs, but before any routines," the identical placement the
 *      compiler-side version of this used to use, now enforced here
 *      instead.
 *   5. Copy the rest of the function (the gt_routine body, if any, then
 *      the real statement body, then `FUNC_END`) through unchanged.
 *
 * **Word count per parameter** (`argWordTypesOf`): a hand-duplicated
 * copy of `BytecodeEmitter`'s own identical logic (this project has no
 * dependency on the compiler's code -- see CLAUDE.md, "What this
 * project is" -- so this is a second, independently-kept-in-sync copy
 * of the same structural predicate, purely string-based, needing
 * nothing beyond the canonical type string already sitting in the `ARG`
 * line's own text): a by-value range (no storage prefix at all,
 * immediately `imut`/`mut`/`indeterminate_range(...)`) is two words,
 * both untyped `indeterminate_u64` (`.start`/`.end`); anything else
 * (including a storage-bearing, single-word pointer to a range -- never
 * split, unlike a genuine by-value one) is exactly one word, of its own
 * type. (This used to also split a by-value slice argument into three
 * words -- its own origin type, then the same two untyped bounds; the
 * slice type has been removed from the language entirely, so that case
 * is gone along with it.)
 */
public class ArgToAllocLoweringPass {


    public PassResult run(BytecodeProgram lines) { return null; }

    private boolean sameLines(BytecodeProgram lines, int start, int end,
            BytecodeProgram out, int outStart) { return false; }

    private void lowerFunction(BytecodeProgram lines, int start, int end, BytecodeProgram out) { }

    private void emitParamAllocs(BytecodeProgram out, List<String> paramNames, List<String> paramTypes) { }

    private void emitParamLoads(BytecodeProgram out, List<String> paramNames, List<String> paramTypes) { }

    /**
     * True for a real float base type -- "f32" only, the sole
     * floating-point primitive this language has (see the sibling
     * `caspien-compiler` project's own `TypeChecker.PRIMITIVE_SIZE`, and
     * this project's own `AddressLoweringPass.isFloatBaseType`, which
     * checks the identical fact off an already-parsed `CanonicalType`
     * instead of raw text -- this method exists separately because
     * `argWordTypesOf`'s own word types are always either a struct-free
     * scalar canonical string or the fixed literal "indeterminate_u64",
     * never something `CanonicalType.parse` needs for this check). A
     * canonical string always carries its real base type as the very
     * last segment (confirmed directly against `TypeInfo.canonical()`'s
     * own construction order, on the compiler side), so checking the
     * tail is exact.
     */
    private static boolean isFloatCanonical(String canonical) { return false; }

    private List<String> argWordTypesOf(String canonical) { return null; }

    private static void emit(BytecodeProgram out, String text) { }
}
