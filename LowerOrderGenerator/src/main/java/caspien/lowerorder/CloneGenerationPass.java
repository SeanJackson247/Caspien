package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;


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
public class CloneGenerationPass {

    public PassResult run(BytecodeProgram lines) {
        return new CloneGenerationWorker(StructTable.read(lines)).process(lines);
    }

}
