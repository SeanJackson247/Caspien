package caspien.lowerorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * "clone will need clone routines much like the gt routines for each
 * type, to copy all the owned memory," confirmed directly -- a plain
 * "PUSH pointer / CLONE" is a bitwise allocate-and-copy, which is
 * exactly wrong for a pointer whose pointee itself owns further memory:
 * it would leave the "clone" aliasing the very sub-objects it was
 * supposed to duplicate. This is the same problem "GT_DESTRUCT name"
 * used to have before DropGlueGenerationPass existed (a flat,
 * one-level-only runtime primitive that needed a real, struct-layout-
 * aware recursion bolted on outside it), and this pass is the same fix,
 * applied to "CLONE" instead of "GT_DESTRUCT": one shared, generated
 * "clone-glue" routine per owns-bearing struct shape, called wherever a
 * `clone()` builtin's own pointee actually needs deep copying, leaving
 * every other "CLONE" site (a pointer to a plain scalar, or to a
 * struct/array with no owns content anywhere inside it) completely
 * untouched -- a flat allocate-and-copy is already exactly correct
 * there, and (per the earlier reassessment of this bytecode's
 * "obscure/complex" operators) that flat case only ever needs a byte
 * count at its own site, which this pass was never asked to strip.
 *
 * Structurally this pass is DropGlueGenerationPass's own mirror image:
 * read struct layout out of the program's own STRUCT_START/
 * STRUCT_MEMBER/STRUCT_END blocks (StructTable, unchanged, reused
 * as-is), walk each function looking for the mnemonic in question, and
 * generate one shared routine per distinct owns-bearing shape,
 * self-referential/recursive struct types being illegal in this
 * language (confirmed directly, same as DropGlueGenerationPass's own
 * reasoning) so there's no cycle to worry about.
 *
 * "CLONE" used to be the one wrinkle this pass had that "GT_DESTRUCT
 * name" didn't: "GT_DESTRUCT" always names its own target directly
 * ("GT_DESTRUCT name"), but "CLONE" used to be bare -- "PUSH pointer /
 * CLONE" -- with no operand text of its own at all, forcing this pass to
 * recover the source pointer's own canonical type from the immediately
 * preceding line's own *last token* instead. That wrinkle is gone:
 * "clone() should carry its own arguments the same style as every other
 * standard operation, and the optimizer should reduce it to a size
 * during lowering," confirmed directly -- `BytecodeEmitter`'s "clone"
 * case now emits "CLONE argType returnType", the identical trailing-
 * operand-type shape every other value-producing instruction here
 * already has, so this pass reads its source pointer's own canonical
 * type directly off `CLONE`'s own first operand, never off whatever line
 * happens to precede it. A nested `clone(clone(x))` is legal, but poses
 * no problem either: this pass processes each function's lines in
 * program order, so by the time it reaches the outer "CLONE" the inner
 * one has already been rewritten into "CALL .../PUSH_RET type" (see
 * below), and "PUSH_RET" always carries its own trailing type, same as
 * every other operand-producing line.
 *
 * Per-shape handling (mirroring DropGlueGenerationPass's own
 * emitValueDestruct/emitOwnsMemberDestruct dispatch almost exactly,
 * "produce a freshly cloned value" in place of "destruct a value"):
 *   - A pointer to a struct that transitively owns something
 *     (`StructTable.isOwnsBearing`) -- rewritten to
 *     "CALL __clone_StructName" + "PUSH_RET owns_mut_StructName", the
 *     shared routine generated once per such struct.
 *   - A pointer to a dynarray whose own elements themselves need
 *     recursive cloning -- rewritten to a real, generated JMP/CMP loop
 *     (`buildCloneLoop`) rather than a new, not-yet-implemented
 *     mnemonic. This used to be "CLONE_LOOP srcType elemType elemRoutine
 *     resultType" -- a new instruction contract deferred to a
 *     not-yet-built codegen stage, the same "hand the per-element walk
 *     to a new, dedicated instruction rather than unrolling a
 *     runtime-length loop here" move `DropGlueGenerationPass`'s own
 *     "GT_LOOP" made -- but once `DropGlueGenerationPass` rebuilt
 *     "GT_LOOP" as a real, generated loop directly inline rather than
 *     leaving it as an opaque mnemonic ("please implement their
 *     lowering all the same," with a stated preference against a whole
 *     new pass for it), this pass follows the identical move for
 *     "CLONE_LOOP": `buildCloneLoop` builds the real loop right here,
 *     out of the same already-fully-specified primitives (a hidden
 *     `mut_u64` counter, bare `LEN` for the runtime bound, `LOOKUP`/
 *     `LOOKUP_LHS`/`LOOKUP_DYN_LHS`), so nothing named "CLONE_LOOP" is
 *     ever actually emitted any more. Two genuinely different element
 *     shapes both reach this branch, and `elemRoutine` is still tagged
 *     to tell them apart exactly as before (see `buildCloneLoop`'s own
 *     doc comment): **confirmed directly against `TypeInfo.dynArray`'s own
 *     doc comment** -- a dynarray's canonical element-type text
 *     *deliberately never carries storage/mutability* ("two dynarrays
 *     holding the same kind of element are the same type regardless of
 *     whether one happened to be constructed from mut-wrapped literals
 *     and the other from bare ones"), matching the identical rule
 *     `TypeInfo.array` already established for fixed arrays. So the
 *     common, idiomatic shape (confirmed directly against a real
 *     fixture, `clone_generation_manual_check_test.caspien`, in the
 *     sibling `caspien-compiler` project) is actually a dynarray of
 *     **inline** owns-bearing struct
 *     *values* (`dyn([new Box{...}, new Box{...}])` moves each
 *     `new`-constructed value into the array by value, the same way an
 *     ordinary, un-`new`'d `StructName{...}` literal is always a
 *     stack-resident value -- `BytecodeEmitter.emitInstantiate` never
 *     emits a "NEW" of its own; only `emitNew`'s own wrapper around it
 *     does that, for the `new` keyword specifically), not a dynarray of
 *     owns pointers -- which is why this pass generates a *second* kind
 *     of routine (`__clone_value_StructName`, see below) rather than
 *     only the pointer-based one. A dynarray of genuine owns-storage
 *     *pointers* is also supported, for whatever path might still
 *     produce one, using the ordinary pointer-based routine instead.
 *   - Anything else with no owns content anywhere inside -- left as
 *     the original, unmodified "PUSH pointer type / CLONE" -- still
 *     exactly correct, still only ever needing a byte count at its own
 *     site (out of this pass's scope to also strip, see the earlier
 *     "obscure operators" reassessment).
 *   - One shape this pass still doesn't know how to recurse into --
 *     left as the original, unmodified "CLONE" bytecode rather than
 *     guessed at: a bare owns pointer directly at a fixed array (the
 *     identical gap DropGlueGenerationPass already has, for the
 *     identical reason -- no confirmed computed-index addressing this
 *     pass's own primitives can use).
 *
 * `buildCloneLoop`'s own contract (what used to be documented here as
 * the new, not-yet-implemented "CLONE_LOOP srcType elemType elemRoutine
 * resultType" instruction, before it was built as a real loop directly
 * -- kept here verbatim as the still-accurate *semantic* contract, now
 * realized in bytecode rather than deferred): allocate a new dynarray of
 * `srcType`'s own runtime length; for each source element:
 *   - `elemRoutine` == "NONE:PTR" -- `elemType` is confirmed owns-storage
 *     with nothing further owned inside it (a leaf owns pointer) --
 *     clone that one element with the same plain, flat "CLONE" contract
 *     "CLONE" already has (a fresh allocation + bytewise copy).
 *   - `elemRoutine` starts with "PTR:" -- `elemType` is owns-storage and
 *     the rest of the name is a generated pointer-based routine
 *     ("CALL"-shaped, one `raw_imut_StructName` parameter, `RETURNS
 *     owns_mut_StructName") -- call it on that element directly.
 *   - `elemRoutine` starts with "VAL:" -- `elemType` is a plain,
 *     inline (no-storage) struct value, and the rest of the name is a
 *     generated *value*-based routine (one `raw_imut_StructName`
 *     parameter, `RETURNS mut_StructName` -- takes the element's own address,
 *     returns a freshly built value, no allocation of its own) -- call
 *     it on that element's address and store the returned value in
 *     place.
 * Leaves the freshly built dynarray, of type `resultType` (== `srcType`
 * with "owns_" storage), on the stack when done -- see `buildCloneLoop`'s
 * own doc comment for exactly how the runtime-length allocation and the
 * loop itself are actually built.
 *
 * Resolved design question (was previously left open here): unlike
 * DropGlueGenerationPass's own generated routines (which only ever
 * free/traverse, never allocate, and so were confirmed to need no
 * `gt_routine`/`GT_UNWIND` treatment at all), a generated clone routine
 * *does* allocate ("NEW StructName"), which is exactly the property
 * `BytecodeEmitter.emitFuncUnderName`'s own `usesOwnsRefDynNew()` gate
 * uses to decide whether a *real*, type-checked function needs the full
 * `gt_routine` prologue. Confirmed directly that no such treatment is
 * needed here either: "currently things like new can propagate nulls,
 * so trying to do a memory thing and it not working back and getting
 * back null isnt a throw worthy thing. As long as these clone routines
 * dont have throw it should be fine" -- a failed `NEW` returns `null`
 * rather than throwing, `GT_UNWIND` exists purely to unwind past an
 * explicit `throw`, and these generated routines never contain one. This
 * pass therefore generates clone routines the same lean, no-prologue-at-
 * all way DropGlueGenerationPass's own routines already are, with no
 * open question remaining.
 */
public class CloneGenerationPass implements OptimizationPass {

    private static final BytecodeParser PARSER = new BytecodeParser();

    @Override
    public String name() {
        return "clone-generation";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        return new Worker(StructTable.read(lines)).process(lines);
    }

    private static final class Worker {

        private final StructTable structTable;
        private final Set<String> neededPointerRoutines = new LinkedHashSet<>();
        private final Set<String> generatedPointerRoutines = new HashSet<>();
        private final Set<String> neededValueRoutines = new LinkedHashSet<>();
        private final Set<String> generatedValueRoutines = new HashSet<>();
        private int labelCounter = 0;
        private int tempCounter = 0;

        Worker(StructTable structTable) {
            this.structTable = structTable;
        }

        PassResult process(List<List<BytecodeToken>> lines) {
            List<List<BytecodeToken>> rewritten = new ArrayList<>();
            boolean changedAnyCallSite = false;

            int i = 0;
            int n = lines.size();
            while (i < n) {
                List<BytecodeToken> line = lines.get(i);
                if (line.isEmpty() || !line.get(0).text.equals("FUNC_START")) {
                    rewritten.add(line);
                    i++;
                    continue;
                }

                int start = i;
                int end = start;
                while (end < n && !(lines.get(end).size() > 0 && lines.get(end).get(0).text.equals("FUNC_END"))) {
                    end++;
                }
                end = Math.min(end, n - 1);

                for (int k = start; k <= end; k++) {
                    List<BytecodeToken> fl = lines.get(k);
                    if (!fl.isEmpty() && fl.get(0).text.equals("CLONE") && fl.size() == 3) {
                        String sourceType = fl.get(1).text;
                        List<String> replacement = tryRewriteClone(sourceType);
                        if (replacement != null) {
                            rewritten.addAll(PARSER.parse(replacement, "<generated-clone-glue>"));
                            changedAnyCallSite = true;
                            continue;
                        }
                    }
                    rewritten.add(fl);
                }

                i = end + 1;
            }

            boolean generatedAnyRoutine = !neededPointerRoutines.isEmpty() || !neededValueRoutines.isEmpty();
            while (!neededPointerRoutines.isEmpty() || !neededValueRoutines.isEmpty()) {
                while (!neededPointerRoutines.isEmpty()) {
                    String next = neededPointerRoutines.iterator().next();
                    neededPointerRoutines.remove(next);
                    if (generatedPointerRoutines.contains(next)) {
                        continue;
                    }
                    generatedPointerRoutines.add(next);
                    rewritten.addAll(generatePointerCloneRoutine(next));
                }
                while (!neededValueRoutines.isEmpty()) {
                    String next = neededValueRoutines.iterator().next();
                    neededValueRoutines.remove(next);
                    if (generatedValueRoutines.contains(next)) {
                        continue;
                    }
                    generatedValueRoutines.add(next);
                    rewritten.addAll(generateValueCloneRoutine(next));
                }
            }

            return new PassResult(rewritten, changedAnyCallSite || generatedAnyRoutine);
        }

        /** `sourceType` is `CLONE`'s own first operand -- the canonical type of whatever pointer is already sitting on top of the stack, now carried directly on the instruction itself rather than read off the preceding line. Returns the replacement instructions, or null to leave the original "CLONE" bytecode completely untouched. */
        private List<String> tryRewriteClone(String sourceType) {
            CanonicalType t = CanonicalType.parse(sourceType);
            if (!structTable.isOwnsBearing(t.baseType)) {
                return null; // flat -- a plain allocate-and-copy is already exactly correct
            }
            return ownsBearingCloneInstructions(sourceType);
        }

        /**
         * `canonicalType`'s own base type is already confirmed
         * owns-bearing, and its pointer value is already sitting on top
         * of the stack. Returns the instructions that turn it into a
         * freshly, deeply cloned replacement, or null if this is one of
         * the two documented gap shapes (see this pass's own header) --
         * the caller falls back to the plain, flat "CLONE" contract
         * either way, same as any other pointer whose pointee doesn't
         * need this at all.
         */
        private List<String> ownsBearingCloneInstructions(String canonicalType) {
            CanonicalType t = CanonicalType.parse(canonicalType);

            String dynElem = CanonicalType.dynArrayElementTypeOf(t.baseType);
            if (dynElem != null) {
                String elemRoutine = elementRoutineTagFor(dynElem);
                // The result is always "owns" storage, regardless of
                // what storage the source pointer itself had (a
                // dynarray borrowed via "ref", say, at a top-level
                // `clone()` call site) -- same reasoning as the struct
                // branch below.
                return buildCloneLoop(canonicalType, dynElem, elemRoutine, "owns_mut_" + t.baseType);
            }

            if (structTable.hasStruct(t.baseType)) {
                neededPointerRoutines.add(t.baseType);
                // The routine's own, fixed return type ("RETURNS
                // owns_mut_StructName" in generatePointerCloneRoutine
                // below) -- never the source pointer's own storage kind,
                // which (at a top-level `clone()` call site) can be
                // anything checkCloneBuiltin accepts ("raw", "ref", ...),
                // not necessarily "owns" at all.
                return Arrays.asList("CALL " + pointerRoutineNameFor(t.baseType),
                        "PUSH_RET owns_mut_" + t.baseType);
            }

            return null; // a bare owns pointer directly at a fixed array -- the same known gap DropGlueGenerationPass already has
        }

        /**
         * CLONE_LOOP's own "elemRoutine" operand for a dynarray element
         * of type `dynElem` (already known to need *some* form of
         * per-element recursion -- the caller only reaches here once
         * `structTable.isOwnsBearing` has already confirmed the whole
         * dynarray needs work) -- see this pass's own header for the
         * "NONE:PTR"/"PTR:.../VAL:..." tag contract.
         */
        private String elementRoutineTagFor(String dynElem) {
            CanonicalType elemT = CanonicalType.parse(dynElem);
            if (elemT.isOwnsStorage()) {
                if (structTable.hasStruct(elemT.baseType) && structTable.isOwnsBearing(elemT.baseType)) {
                    neededPointerRoutines.add(elemT.baseType);
                    return "PTR:" + pointerRoutineNameFor(elemT.baseType);
                }
                return "NONE:PTR"; // a leaf owns pointer -- still needs a fresh per-element CLONE, never a raw copy
            }
            // Not itself a pointer -- an inline struct value directly in
            // the buffer (dynArray()'s own element-type text never
            // carries storage at all, see this pass's own header), and
            // since the whole dynarray was already confirmed
            // owns-bearing, this inline element must be the reason why.
            neededValueRoutines.add(elemT.baseType);
            return "VAL:" + valueRoutineNameFor(elemT.baseType);
        }

        /**
         * Builds a real, generated JMP/CMP loop that deep-clones
         * `srcType`'s own dynarray elements into a freshly allocated
         * destination of the same runtime length, in place of the
         * original "CLONE_LOOP srcType elemType elemRoutine resultType"
         * contract this pass used to hand off to a not-yet-built codegen
         * stage (see this pass's own header for the full reasoning).
         * Precondition/postcondition mirror the single "value-producing
         * instruction" shape the "CLONE_LOOP ..." line it replaces always
         * had: the source pointer is already sitting on the stack
         * (pushed by whatever line precedes this call site -- the
         * original "PUSH ... srcType" line, left completely untouched),
         * and this method's own returned lines leave exactly the
         * freshly cloned destination dynarray (of type `resultType`) on
         * the stack when they finish, so every existing caller
         * (`tryRewriteClone`'s top-level splice, `emitClonedValue`'s
         * nested-member splice) keeps working unchanged.
         *
         * The one real wrinkle, not present in `DropGlueGenerationPass`'s
         * own "GT_LOOP" loop-building mirror
         * (`emitDynArrayElementDrop`): that pass's own loop only ever
         * *reads* the dynarray it walks (destruction never allocates),
         * whereas this one needs a *destination* dynarray of the exact
         * same runtime length as the source, with nothing existing in
         * this bytecode able to allocate one directly:
         *   - The already-pushed source pointer is captured into a
         *     fresh, named hidden temp via "POP name type" -- the only
         *     way this bytecode can give a bare stack value a name at
         *     all (the identical trick `MembershipLoweringPass`'s own
         *     `declareTemp`/`POP` already established for materializing
         *     a non-simple "in" operand) -- needed here because, unlike
         *     `DropGlueGenerationPass`'s `path` (always a real,
         *     resolvable name/dotted-chain), the value this method
         *     starts from is whatever the *caller's own* "PUSH ..." line
         *     already left on the stack, with no name of its own.
         *   - The destination is bootstrapped via "NEW_DYN dynarray(T) 0"
         *     (a fresh, empty dynarray -- "NEW_DYN dynarray(T) count"
         *     only ever takes a *compile-time-literal* count, the same
         *     shape a real "dyn([...])" array literal already uses,
         *     popping exactly that many already-pushed element values;
         *     "0" is the one literal count that needs no elements pushed
         *     at all, so it's the only way this already-general contract
         *     can produce a dynarray from nothing -- never itself
         *     emitted by the compiler, which has no "dyn([])" literal
         *     syntax, but a reasonable, unforced reading of an
         *     already-fully-specified contract, flagged here rather than
         *     silently assumed) -- then grown to the source's own
         *     runtime length via `resize`'s own *unsafe*, 2-argument,
         *     no-fill-value form -- emitted here as `URESIZE`, the
         *     mnemonic that shape now carries (`AddressLoweringPass`
         *     erases `RESIZE`/`URESIZE` differently: `RESIZE` reads its
         *     element size off a fill value that's guaranteed to exist;
         *     `URESIZE` has none, so it reads the same size off the
         *     dynarray operand's own declared element type instead --
         *     `AddressLoweringPass`'s own `URESIZE` rule tries
         *     `unsafeDynArrayElementTypeOf` first, then falls back to
         *     `dynArrayElementTypeOf`, specifically so this one
         *     synthesized call site -- built against a nominally *safe*
         *     `owns_mut_dynarray(...)` destination, not a genuinely
         *     unsafe one -- still resolves correctly). The 3-argument
         *     safe form (real `RESIZE`) was considered and rejected: it
         *     needs a fill value, and `elemType` here is arbitrary (could
         *     be an owns-bearing struct, could be a leaf pointer), so
         *     there's no way to synthesize a generic one -- the 2-arg
         *     form's own uninitialized memory is never actually
         *     observed, since every slot gets written by this loop
         *     before anything ever reads it. `resize`'s own compile-time
         *     "not inside a dynarray for-loop" restriction
         *     (`checkResizeBuiltin`, `scope.insideDynArrayForLoopBody`)
         *     is a `TypeChecker`-only check on real source text -- it's
         *     never reflected in the bytecode itself, so it doesn't bind
         *     bytecode generated directly here either. `resize` may
         *     return a different address than it was given (modeled
         *     directly on a real `realloc`), so the destination temp is
         *     re-captured (a second "POP") from its own result, never
         *     assumed to be the same address the initial "NEW_DYN ... 0"
         *     produced.
         * From there, the loop itself is exactly `DropGlueGenerationPass`
         * own's "GT_LOOP" mirror -- a hidden `mut_u64` counter, the same
         * `LEN`/`LT`/`CMP`/`JMP` bound check, `INC`/`ASSIGN` increment --
         * except each iteration produces a value (per `elemRoutine`'s own
         * tag, exactly as this pass's header already documents) and
         * stores it into the destination at the same index via
         * `LOOKUP_LHS`/`ASSIGN`, rather than merely destructing what it
         * finds.
         */
        private List<String> buildCloneLoop(String srcType, String dynElem, String elemRoutine, String resultType) {
            List<String> lines = new ArrayList<>();

            String srcTemp = newTemp(lines, "clone_loop_src", srcType);
            lines.add("POP " + srcTemp + " " + srcType);

            String dstType = "owns_mut_dynarray(" + dynElem + ")";
            String dstTemp = newTemp(lines, "clone_loop_dst", dstType);
            lines.add("NEW_DYN mut_dynarray(" + dynElem + ") 0");
            lines.add("POP " + dstTemp + " " + dstType);
            lines.add("PUSH " + dstTemp + " " + dstType);
            lines.add("PUSH " + srcTemp + " " + srcType);
            lines.add("LEN");
            lines.add("URESIZE " + dstType + " indeterminate_u64");
            lines.add("POP " + dstTemp + " " + dstType); // resize may relocate -- never assume the "NEW_DYN ... 0" address survives

            String counter = newTemp(lines, "clone_loop_i", "mut_u64");
            lines.add("ADDR " + counter + " mut_u64");
            lines.add("PUSH 0 indeterminate_u64");
            lines.add("ASSIGN mut_u64 mut_u64 mut_u64");

            String topLabel = newLabel("clone_loop");
            String endLabel = newLabel("clone_loop_end");
            lines.add(topLabel + ":");
            lines.add("PUSH " + counter + " mut_u64");
            lines.add("PUSH " + srcTemp + " " + srcType);
            lines.add("LEN");
            lines.add("LT mut_u64 indeterminate_u64 indeterminate_bool");
            lines.add("CMP");
            lines.add("JMP " + endLabel);

            // Destination slot address first (the same "push the
            // destination, then the value, then ASSIGN" order every
            // other assignment in this bytecode already uses), then
            // this iteration's freshly cloned value, exactly per
            // `elemRoutine`'s own tag contract (this pass's own header).
            lines.add("PUSH " + dstTemp + " " + dstType);
            lines.add("PUSH " + counter + " mut_u64");
            lines.add("LOOKUP_LHS " + dstType + " mut_u64 " + dynElem);

            CanonicalType elemT = CanonicalType.parse(dynElem);
            if (elemRoutine.equals("NONE:PTR")) {
                lines.add("PUSH " + srcTemp + " " + srcType);
                lines.add("PUSH " + counter + " mut_u64");
                lines.add("LOOKUP " + srcType + " mut_u64 " + dynElem);
                lines.add("CLONE " + dynElem + " owns_mut_" + elemT.baseType);
            } else if (elemRoutine.startsWith("PTR:")) {
                lines.add("PUSH " + srcTemp + " " + srcType);
                lines.add("PUSH " + counter + " mut_u64");
                lines.add("LOOKUP " + srcType + " mut_u64 " + dynElem);
                lines.add("CALL " + elemRoutine.substring("PTR:".length()));
                lines.add("PUSH_RET owns_mut_" + elemT.baseType);
            } else { // "VAL:name"
                lines.add("PUSH " + srcTemp + " " + srcType);
                lines.add("PUSH " + counter + " mut_u64");
                lines.add("LOOKUP_LHS " + srcType + " mut_u64 " + dynElem);
                lines.add("CALL " + elemRoutine.substring("VAL:".length()));
                lines.add("PUSH_RET mut_" + elemT.baseType);
            }
            lines.add("ASSIGN " + dynElem + " " + dynElem + " " + dynElem);

            lines.add("ADDR " + counter + " mut_u64");
            lines.add("PUSH " + counter + " mut_u64");
            lines.add("INC mut_u64 indeterminate_u64");
            lines.add("ASSIGN mut_u64 indeterminate_u64 indeterminate_u64");
            lines.add("JMP " + topLabel);
            lines.add(endLabel + ":");

            lines.add("PUSH " + dstTemp + " " + dstType);
            return lines;
        }

        private String newTemp(List<String> lines, String tag, String type) {
            tempCounter++;
            String name = "$" + tag + tempCounter;
            lines.add("ALLOC " + name + " " + type);
            return name;
        }

        private String newLabel(String prefix) {
            labelCounter++;
            return "@" + prefix + "_" + labelCounter;
        }

        private static String pointerRoutineNameFor(String structName) {
            return "__clone_" + CanonicalType.sanitizeForLabel(structName);
        }

        private static String valueRoutineNameFor(String structName) {
            return "__clone_value_" + CanonicalType.sanitizeForLabel(structName);
        }

        /**
         * Builds the full FUNC_START..FUNC_END block for `structName`'s
         * own pointer-based clone-glue routine (takes the pointer,
         * returns a freshly allocated, deeply cloned owns pointer) --
         * see this pass's own header for why this deliberately gets no
         * `gt_routine` prologue, and `emitParamLoad`'s own doc comment
         * (below) for why its one parameter loads via a plain `PUSH $16`
         * -- the sibling `caspien-compiler` project's own "ARG-to-ALLOC
         * lowering" contract, applied to this pass's own generated
         * routines too, now that the old `ARG name type` declaration line
         * is extinct everywhere in this pipeline's bytecode.
         */
        private List<List<BytecodeToken>> generatePointerCloneRoutine(String structName) {
            List<List<BytecodeToken>> out = new ArrayList<>();
            String paramType = "raw_imut_" + structName;
            emit(out, "FUNC_START " + pointerRoutineNameFor(structName));
            emit(out, "FUNC_DECORATE @clone_glue");
            emit(out, "RETURNS owns_mut_" + structName);
            emitParamLoad(out, "src", paramType);
            emitMemberPushSequence(out, structName, "src");
            emit(out, "NEW " + structName);
            emit(out, "RET owns_mut_" + structName);
            emit(out, "FUNC_END");
            return out;
        }

        /**
         * Builds the full FUNC_START..FUNC_END block for `structName`'s
         * own *value*-based clone-glue routine -- takes the *address* of
         * an existing value (never an owns pointer of its own -- this is
         * for an inline struct sitting directly inside, say, a
         * dynarray's own buffer) and returns a freshly built value, with
         * no "NEW"/allocation of its own, the same "the instance IS its
         * members' values, laid out inline" shape an ordinary, un-`new`'d
         * "StructName{...}" literal already has
         * (`BytecodeEmitter.emitInstantiate` never emits "NEW" itself --
         * only `emitNew`'s own wrapper around the `new` keyword does).
         */
        private List<List<BytecodeToken>> generateValueCloneRoutine(String structName) {
            List<List<BytecodeToken>> out = new ArrayList<>();
            String paramType = "raw_imut_" + structName;
            emit(out, "FUNC_START " + valueRoutineNameFor(structName));
            emit(out, "FUNC_DECORATE @clone_glue");
            emit(out, "RETURNS mut_" + structName);
            emitParamLoad(out, "src", paramType);
            emitMemberPushSequence(out, structName, "src");
            emit(out, "RET mut_" + structName);
            emit(out, "FUNC_END");
            return out;
        }

        /**
         * Emits one generated routine's own single-parameter "ARG-to-
         * ALLOC lowering" -- a real `ALLOC name type` (trivially "the
         * first of the ALLOCs for that function," since it's the only
         * one), then loaded via `ADDR`/`PUSH $16`/`ASSIGN`, the same
         * plain, pre-existing `PUSH` (no new mnemonic) the sibling
         * `caspien-compiler` project's own stack-passed parameters use --
         * see that project's CLAUDE.md, "ARG-to-ALLOC lowering." Both of
         * this pass's generated routines are always called through a bare
         * `PUSH value` / `CALL name` (see `tryRewriteClone`), never a real
         * `CC_START`/`CC_END`/register-transfer bracket, so the one
         * argument never touches a register and is always exactly stack
         * word 0 -- unconditionally, whatever real `@call_convention` the
         * rest of the program actually uses (this pass has no
         * `compiler.config` of its own to resolve one from). `16` is the
         * same fixed saved-rbp-plus-return-address base the compiler side
         * uses (`16 + shadowStack + stackWordIndex*8`), with
         * `shadowStack=0` and `stackWordIndex=0` here since this bare
         * `PUSH`/`CALL` shape has no real calling convention and always
         * exactly one stack-passed word -- the identical reasoning
         * `DropGlueGenerationPass.generateRoutine`'s own doc comment
         * gives for its own, structurally identical one-parameter
         * routine.
         */
        private void emitParamLoad(List<List<BytecodeToken>> out, String name, String type) {
            emit(out, "ALLOC " + name + " " + type);
            emit(out, "ADDR " + name + " " + type);
            emit(out, "PUSH $16 " + type);
            emit(out, "ASSIGN " + type + " " + type + " " + type);
        }

        /** Pushes `structName`'s own classId (if any, copied verbatim off `basePath`) followed by every ordinary member's own freshly cloned value, in declared order -- shared by both routine shapes above, matching NEW's/an inline struct literal's own identical construction order (BytecodeEmitter.emitInstantiate). */
        private void emitMemberPushSequence(List<List<BytecodeToken>> out, String structName, String basePath) {
            List<StructTable.Member> members = structTable.membersOf(structName);
            // classId, when present, is always the *first* STRUCT_MEMBER
            // (BytecodeEmitter.emitStruct emits it before every
            // user-declared field -- see that method's own "load-bearing,
            // not cosmetic" comment) -- checked at index 0, not the last
            // index. (Found stale while wiring up struct padding: this
            // used to check `members.get(members.size() - 1)`, a leftover
            // from before the compiler-side "___type declared last" bug
            // was fixed there; it had silently gone uncorrected here ever
            // since, this pass's own "found missing/stale, not new"
            // report gap.)
            boolean classIdBearing = !members.isEmpty() && members.get(0).name.equals("___type");
            List<StructTable.Member> ordinary = classIdBearing ? members.subList(1, members.size()) : members;

            // "___type" is never part of a struct's own declared member
            // list (BytecodeEmitter.emitStruct appends it separately,
            // first, only for a classId-bearing struct) -- copied here
            // directly off the source, matching NEW's own real
            // construction order (classId first, then ordinary members,
            // see BytecodeEmitter.emitInstantiate), rather than needing
            // this pass to know the actual numeric classId at all: a
            // clone always targets the exact same concrete struct type
            // as its source, so simply copying the source's own already-
            // correct value across is always right.
            if (classIdBearing) {
                emit(out, "PUSH " + basePath + ".___type imut_u64");
            }
            for (StructTable.Member m : ordinary) {
                emitClonedValue(out, basePath + "." + m.name, m.canonicalType);
            }
        }

        /**
         * Pushes a freshly cloned value for one member (or one unrolled
         * fixed-array element, or one recursively-visited inline
         * struct's own sub-member) at `path`, of declared type
         * `canonicalType` -- exactly one value pushed per call, for
         * whatever's assembling a "NEW ..." sequence out of these to
         * consume. Mirrors DropGlueGenerationPass.emitValueDestruct's
         * own dispatch shape.
         */
        private void emitClonedValue(List<List<BytecodeToken>> out, String path, String canonicalType) {
            CanonicalType t = CanonicalType.parse(canonicalType);

            if (t.isOwnsStorage()) {
                emit(out, "PUSH " + path + " " + canonicalType);
                List<String> recurse = structTable.isOwnsBearing(t.baseType)
                        ? ownsBearingCloneInstructions(canonicalType) : null;
                if (recurse != null) {
                    for (String l : recurse) {
                        emit(out, l);
                    }
                } else {
                    // A leaf owns pointer (nothing further inside to
                    // clone), or one of the two documented gap shapes --
                    // the plain, flat "CLONE" contract is exactly
                    // correct either way. Carries the same trailing
                    // "argType returnType" operand pair the compiler's
                    // own "clone()" builtin now emits, so this generated
                    // line is indistinguishable from a real one by the
                    // time AddressLoweringPass -- which runs after this
                    // pass -- reduces it to a plain size.
                    emit(out, "CLONE " + canonicalType + " owns_mut_" + t.baseType);
                }
                return;
            }

            if (structTable.hasStruct(t.baseType)) {
                if (structTable.isOwnsBearing(t.baseType)) {
                    // An inline (no-storage) embedded struct that itself
                    // owns something further down -- recurse into its
                    // own members in place, exactly as if they were this
                    // routine's own top-level ones, so the freshly built
                    // value never aliases whatever the embedded struct
                    // itself owns.
                    emitMemberPushSequence(out, t.baseType, path);
                    return;
                }
                // No owns content anywhere inside -- safe to copy as one
                // opaque block of bytes, same as any other flat value.
                emit(out, "PUSH " + path + " " + canonicalType);
                return;
            }

            String fixedElem = t.fixedArrayElementType();
            if (fixedElem != null) {
                CanonicalType elemT = CanonicalType.parse(fixedElem);
                boolean elemNeedsWork = elemT.isOwnsStorage()
                        || (structTable.hasStruct(elemT.baseType) && structTable.isOwnsBearing(elemT.baseType))
                        || CanonicalType.fixedArrayElementTypeOf(elemT.baseType) != null;
                if (elemNeedsWork) {
                    int count = t.fixedArrayLength();
                    for (int i = 0; i < count; i++) {
                        emitClonedValue(out, path + "." + i, fixedElem);
                    }
                    return;
                }
                // Falls through -- no owns content anywhere inside the
                // array, safe to copy the whole thing as one block.
            }

            // A plain scalar, or any other shape with no owns content.
            emit(out, "PUSH " + path + " " + canonicalType);
        }

        private static void emit(List<List<BytecodeToken>> out, String text) {
            out.addAll(PARSER.parse(Collections.singletonList(text), "<generated-clone-glue>"));
        }
    }
}
