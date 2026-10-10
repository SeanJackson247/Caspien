package caspien.lowerorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

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
public class ArgToAllocLoweringPass implements OptimizationPass {

    private static final BytecodeParser PARSER = new BytecodeParser();

    private static final Pattern BY_VALUE_RANGE_ARG =
            Pattern.compile("^(?:imut|mut|indeterminate)_range(?:\\(.*\\))?$");

    @Override
    public String name() {
        return "arg-to-alloc-lowering";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        List<List<BytecodeToken>> out = new ArrayList<>();
        boolean changed = false;

        int i = 0;
        int n = lines.size();
        while (i < n) {
            List<BytecodeToken> line = lines.get(i);
            if (line.isEmpty() || !line.get(0).text.equals("FUNC_START")) {
                out.add(line);
                i++;
                continue;
            }

            int start = i;
            int end = start;
            while (end < n && !(lines.get(end).size() > 0 && lines.get(end).get(0).text.equals("FUNC_END"))) {
                end++;
            }
            end = Math.min(end, n - 1);

            int before = out.size();
            lowerFunction(lines, start, end, out);
            changed |= (out.size() - before) != (end - start + 1) || !sameLines(lines, start, end, out, before);

            i = end + 1;
        }

        return new PassResult(out, changed);
    }

    private boolean sameLines(List<List<BytecodeToken>> lines, int start, int end,
            List<List<BytecodeToken>> out, int outStart) {
        int len = end - start + 1;
        if (out.size() - outStart != len) {
            return false;
        }
        for (int k = 0; k < len; k++) {
            List<BytecodeToken> a = lines.get(start + k);
            List<BytecodeToken> b = out.get(outStart + k);
            if (a.size() != b.size()) {
                return false;
            }
            for (int t = 0; t < a.size(); t++) {
                if (!a.get(t).text.equals(b.get(t).text)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void lowerFunction(List<List<BytecodeToken>> lines, int start, int end, List<List<BytecodeToken>> out) {
        List<String> paramNames = new ArrayList<>();
        List<String> paramTypes = new ArrayList<>();

        int idx = start;
        while (idx <= end) {
            List<BytecodeToken> l = lines.get(idx);
            String mnemonic = l.get(0).text;
            if (mnemonic.equals("ARG") && l.size() >= 3) {
                paramNames.add(l.get(1).text);
                paramTypes.add(l.get(2).text);
                idx++;
                continue;
            }
            if (mnemonic.equals("FUNC_START") || mnemonic.equals("EXPORT")
                    || mnemonic.equals("FUNC_DECORATE") || mnemonic.equals("RETURNS")) {
                out.add(l);
                idx++;
                continue;
            }
            break;
        }

        boolean insertedParamAllocs = false;
        if (idx <= end) {
            List<BytecodeToken> l = lines.get(idx);
            if (l.size() >= 2 && l.get(0).text.equals("ALLOC") && l.get(1).text.equals("gt_routine_address")) {
                out.add(l);
                idx++;
                // the second reserved slot (present only in programs that use throw) stays right behind the first, so both sit at a
                // fixed offset (-8 and -16) in every frame: GT_UNWIND copies the error message up a frame through that fixed slot.
                if (idx <= end) {
                    List<BytecodeToken> m2 = lines.get(idx);
                    if (m2.size() >= 2 && m2.get(0).text.equals("ALLOC") && m2.get(1).text.equals("gt_error_message")) {
                        out.add(m2);
                        idx++;
                        // stack traces: gt_trace_id (rbp-24) and gt_trace_ptr (rbp-32) follow, still before the parameters
                        while (idx <= end) {
                            List<BytecodeToken> m3 = lines.get(idx);
                            if (m3.size() >= 2 && m3.get(0).text.equals("ALLOC") && m3.get(1).text.startsWith("gt_trace_")) {
                                out.add(m3);
                                idx++;
                            } else {
                                break;
                            }
                        }
                    }
                }
                emitParamAllocs(out, paramNames, paramTypes);
                insertedParamAllocs = true;
            }
        }
        if (!insertedParamAllocs) {
            emitParamAllocs(out, paramNames, paramTypes);
        }

        while (idx <= end) {
            List<BytecodeToken> l = lines.get(idx);
            if (l.isEmpty() || !l.get(0).text.equals("ALLOC")) {
                break;
            }
            out.add(l);
            idx++;
        }

        emitParamLoads(out, paramNames, paramTypes);

        while (idx <= end) {
            out.add(lines.get(idx));
            idx++;
        }
    }

    private void emitParamAllocs(List<List<BytecodeToken>> out, List<String> paramNames, List<String> paramTypes) {
        for (int i = 0; i < paramNames.size(); i++) {
            emit(out, "ALLOC " + paramNames.get(i) + " " + paramTypes.get(i));
        }
    }

    private void emitParamLoads(List<List<BytecodeToken>> out, List<String> paramNames, List<String> paramTypes) {
        // `wordIndex` stays one single, uniformly-incrementing counter
        // across the *whole* parameter list, regardless of which words
        // turn out to be "ARGn" vs "FARGn" -- this pass still has no
        // calling-convention data of its own and still doesn't need any
        // (see this class's own header): a word's real register-vs-stack
        // fate, and which specific register it gets, is entirely
        // AddressLoweringPass's job, immediately after this one, exactly
        // as it already was for a plain "ARGn" before this split. Picking
        // the mnemonic itself (ARG vs FARG) needs nothing beyond the
        // type already sitting right here on the word -- never inferred,
        // never new information, the same "the fact was always explicit
        // right here" property this pass already relies on for the word
        // count itself (`argWordTypesOf`).
        int wordIndex = 0;
        for (int i = 0; i < paramNames.size(); i++) {
            String name = paramNames.get(i);
            String canonical = paramTypes.get(i);
            emit(out, "ADDR " + name + " " + canonical);
            for (String wordType : argWordTypesOf(canonical)) {
                emit(out, "PUSH " + (isFloatCanonical(wordType) ? "FARG" : "ARG") + wordIndex + " " + wordType);
                wordIndex++;
            }
            emit(out, "ASSIGN " + canonical + " " + canonical + " " + canonical);
        }
    }

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
    private static boolean isFloatCanonical(String canonical) {
        return canonical.equals("f32") || canonical.endsWith("_f32") || canonical.equals("f64") || canonical.endsWith("_f64");
    }

    private List<String> argWordTypesOf(String canonical) {
        if (BY_VALUE_RANGE_ARG.matcher(canonical).matches()) {
            return Arrays.asList("indeterminate_u64", "indeterminate_u64");
        }
        return Collections.singletonList(canonical);
    }

    private static void emit(List<List<BytecodeToken>> out, String text) {
        out.addAll(PARSER.parse(Collections.singletonList(text), "<arg-to-alloc-lowered>"));
    }
}
