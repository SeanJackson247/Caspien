package caspien.lowerorder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The high-order-to-low-order lowering step that erases a named,
 * base-pointer-relative stack slot down to its own real address and
 * size, confirmed directly: "PUSH imut_u64 x.y" becomes "PUSH 8 $32",
 * where 8 is the size of the value in bytes and $32 is x.y's own
 * computed location relative to the stack base pointer -- "the whole
 * point of all of this... x isn't supposed to be x any more, it's
 * supposed to be an address relative to the base pointer." Run once, as
 * the final stage of the pipeline (BytecodeOptimizer.optimize), after
 * every other pass -- it needs every ALLOC in the program (including
 * ones CloneGenerationPass/DropGlueGenerationPass only just generated)
 * already settled before it can assign real, final offsets.
 *
 * Format, confirmed directly:
 *   - "PUSH name type" (a named stack slot) -> "PUSH size $offset" --
 *     `offset` is signed (negative for an ordinary local -- the stack
 *     growing downward from the base pointer; kept signed, not a bare
 *     magnitude, because a parameter passed on the caller's own stack
 *     will need a *positive* offset once "ARG-to-ALLOC lowering"'s own
 *     still-open register/stack-argument question is settled -- see
 *     that project's CLAUDE.md -- so the sign is real information, not
 *     noise, even though every offset this pass computes today happens
 *     to be negative).
 *   - "PUSH literal type" (an immediate value -- a number, "null", a
 *     global label like a hoisted STRING id, ...) -> "PUSH size
 *     literal" -- reordered to the identical "size first" shape, with
 *     the literal itself carried over completely unchanged. "The $
 *     syntax was to distinguish stack offsets from literals," confirmed
 *     directly -- an un-prefixed second operand is always exactly that,
 *     never a stack address, and vice versa; the two can never be
 *     confused for each other by construction.
 *     "ADDR name type" -> "ADDR size $offset", identically to a named
 *     PUSH, whenever `name` resolves to a real stack offset (ADDR only
 *     ever addresses a real, already-declared name -- never a literal --
 *     so there is no separate "ADDR literal" shape to reorder). When
 *     `name` *doesn't* resolve -- a function-local "ALLOC_STATIC" or a
 *     top-level "GLOBAL", the only other two ways a name gets declared in
 *     this bytecode -- ADDR gets the identical "size name" treatment an
 *     unresolved PUSH already got (see the bullet above): "ADDR name
 *     type" -> "ADDR size name", the name itself carried over unchanged,
 *     still real and addressable, just not as a base-pointer-relative
 *     offset. Originally left as the *entire original line, completely
 *     unrewritten* (full canonical type text and all) instead -- a real,
 *     found-and-fixed inconsistency, confirmed directly against a real
 *     "let static x = mut 0" fixture: this bullet's own doc comment
 *     called this ADDR's "Known gap," but every real "ADDR " emission
 *     site in the compiler (grepped directly, all of them) only ever
 *     hands it a single, bare name, never a dotted chain -- unlike
 *     `GT_DESTRUCT`'s own dotted chain (below), which genuinely can pass
 *     through a pointer partway and hit a real, remaining "Known gap,"
 *     an unresolved ADDR operand was never that; it just isn't a stack
 *     local at all. See `tryRewrite`'s own doc comment at the actual
 *     rewrite site for the full reasoning.
 *   - "NEW TypeName" -> "NEW size" -- the single-operand, struct-
 *     allocating shape only ("new Struct{...}"); the codegen stage
 *     consuming this needs a byte count to allocate/copy, not the type
 *     name, confirmed directly: "NEW just needs to output the size of
 *     bytes to be copied." `dyn([...])`'s own heap-buffer allocation is a
 *     distinct mnemonic entirely now (`NEW_DYN`/`NEW_UDYN`, its own
 *     rewrite further down), not a three-operand use of this same "NEW"
 *     any more.
 *   - "NEW_DYN dynarray(T) count" -> "NEW_DYN size count" / "NEW_UDYN
 *     unsafe_dynarray(T) count" -> "NEW_UDYN size" -- `dyn([...])`'s own
 *     allocation, split by safe/unsafe flavor for the identical reason
 *     `LOOKUP` was split into `LOOKUP_ARRAY`/`LOOKUP_DYN` below: the two
 *     need genuinely different allocation shapes. `size` is always just
 *     the *elements'* own total (`count * elementSize`), never the safe
 *     flavor's extra 8-byte length header folded in -- an explicit
 *     decision ("no dont fold"), not an oversight. `count` survives only
 *     on `NEW_DYN`, since real, still-outstanding work (writing it into
 *     that header once the buffer exists) needs it again; `NEW_UDYN` has
 *     no header, so nothing downstream ever needs it a second time.
 *   - "RESIZE dynArrType countType fillType" -> "RESIZE size" /
 *     "URESIZE dynArrType countType" -> "URESIZE size" -- `resize(...)`'s
 *     own reallocation, split the same way and for the same reason.
 *     `RESIZE` reads its one real piece of information (the element
 *     size) off the fill value's own type, guaranteed to match the
 *     element type exactly; `URESIZE` has no fill value, so it reads the
 *     identical size off the dynarray operand's own declared element
 *     type instead (with a safe-type fallback, needed only for
 *     `CloneGenerationPass.buildCloneLoop`'s own synthesized use of this
 *     shape against a nominally safe destination -- see that rewrite's
 *     own doc comment for why).
 *   - "GT_DESTRUCT name" -> "GT_DESTRUCT $offset" -- resolved exactly
 *     like ADDR/PUSH's own name operand (a bare local, or a dotted chain
 *     for a struct member's own owns-typed field), confirmed directly
 *     this should reference its target "by basepointer offset" too, not
 *     by name -- left completely untouched when `name` doesn't resolve.
 *     Unlike ADDR/PUSH's own bare-name case (since fully handled -- see
 *     that bullet above), `GT_DESTRUCT`'s own name operand genuinely can
 *     be a dotted chain (a struct member's own owns-typed field), so an
 *     unresolved one here can still be the real, remaining "Known gap"
 *     `resolveAddress`'s own doc comment describes (a pointer partway
 *     through the chain) -- not yet revisited, since destructing an
 *     owns-typed global/static (if that's even legal source at all) is
 *     real, separate follow-up work, not something either of today's
 *     fixes attempted or verified.
 *   - "RET type" -> "RET size" or "RET_FLOAT size" -- confirmed
 *     directly: "RET can be split into RET and RET_FLOAT with the value
 *     being the number of bytes." `RET_FLOAT` is used for a real float
 *     return type (`f32` only, today -- see `isFloatBaseType`), `RET`
 *     for everything else, including `void` (`RET 0`). `RET_FLOAT` is a
 *     genuine new mnemonic -- not an exception to any "never invent one"
 *     rule, but the same, already-established "a different runtime
 *     meaning gets its own mnemonic" precedent the compiler side's own
 *     `CAST` split and `IN`/`IN_SCAN`/`LEN`/`LEN_SCAN` already follow
 *     (see that project's CLAUDE.md, "CAST mnemonic split"): a
 *     not-yet-built codegen stage needs to know
 *     whether a returned value comes back in an integer or a
 *     floating-point register, a distinction no existing mnemonic's
 *     operands could carry. The actual, narrower rule this pipeline
 *     holds to is "don't invent a mnemonic for something an existing
 *     one's operands could already say" -- every other rewrite in this
 *     pass satisfies that by just changing operands; this one couldn't.
 *   - "RETURNS type" -> dropped entirely, not merely rewritten --
 *     confirmed directly: "RETURNS can be omitted." Once every "RET"/
 *     "RET_FLOAT" line already carries its own byte count (and, via
 *     which of the two mnemonics it is, whether that value is a float),
 *     the function's own separately-declared return type has nothing
 *     left to say. A caller's own "PUSH_RET type" at each call site
 *     still carries the callee's return type in full, untouched by
 *     this -- this pass's only other place that type still appears.
 *   - "LOOKUP targetType indexType returnType" -> "LOOKUP_ARRAY size" /
 *     "LOOKUP_DYN size" -- confirmed directly: "the only info the LOOKUP
 *     should need is the width of its return type ... the instruction
 *     itself must be split." A fixed array, a `string`, **or an unsafe
 *     dynarray(`unsafe_dynarray(...)`)** all resolve to `LOOKUP_ARRAY` --
 *     a single, direct `base + index * elementWidth` against whatever's
 *     already been pushed for the container, no header of any kind sits
 *     in front of the data for any of these three. A **safe**
 *     `dynarray(...)` resolves to `LOOKUP_DYN` -- `base + 8 +
 *     index * elementWidth`: a safe dynarray's own buffer is preceded by
 *     a single, fixed 8-byte length header (the same 8 bytes `LEN`'s own
 *     bare read already reads directly off that same pushed value, at
 *     offset 0 -- `PUSH arr / LEN`, no operand of its own, needs no
 *     lowering here at all, see `BytecodeEmitter`'s own "len" case), so
 *     the elements themselves start 8 bytes further in than an unsafe
 *     dynarray's (or a fixed array's/string's) own elements do.
 *
 *     **Corrected directly, a real, found-and-fixed design bug:** this
 *     used to describe `LOOKUP_DYN` as needing "one pointer indirection
 *     through its own 8-byte heap-buffer handle" -- i.e. the pushed value
 *     itself being a pointer to a separate handle slot, not the buffer's
 *     own address at all. Confirmed wrong directly, against this
 *     project's own `resize()`: every real call site that resizes a
 *     dynarray (a real compiled fixture, and `CloneGenerationPass.
 *     buildCloneLoop`'s own synthesized resize) explicitly re-`POP`s
 *     `RESIZE`'s returned address back into the *same* variable right
 *     after the call, with an explicit comment that the old address
 *     can't be trusted ("resize may relocate -- never assume the
 *     address survives") -- the identical discipline a raw C `realloc`
 *     forces. If a handle-indirection layer genuinely existed, that
 *     re-`POP` would be entirely unnecessary: the handle slot itself
 *     would just be updated in place, and every existing reference would
 *     see the new address automatically with nothing to reassign. Since
 *     every real resize call site instead reassigns the variable
 *     directly, a dynarray value -- safe or unsafe -- is, and was always
 *     actually being treated as, a single, plain 8-byte pointer, exactly
 *     like every other pointer this bytecode has (confirmed directly:
 *     "either way its an 8 byte pointer itself like all other
 *     pointers"), differing from an unsafe dynarray/fixed array/string
 *     only in the fixed `+8` length-header offset in front of the actual
 *     element data -- never in an extra level of indirection.
 *     `lookupMnemonicFor`'s own doc has the addressing detail; both
 *     `targetType` and `indexType` are dropped entirely from the
 *     rewritten line either way, once the mnemonic itself says which
 *     shape applies. (This used to also have a `LOOKUP_SLICE` mnemonic
 *     for a `slice(...)` target -- the slice type has been removed from
 *     the language entirely, so that shape no longer exists.)
 *   - "GT/LT/GT_EQ/LT_EQ/EQ/NEQ leftType rightType returnType" ->
 *     "GT_INT size" / "GT_FLOAT size" / "LT_INT size" / ... -- confirmed
 *     directly: each "need[s] to be split by _int and _float and take as
 *     their only argument the size in bytes of the type of their first
 *     argument," `EQ`/`NEQ` added right after the first four. `leftType`
 *     alone decides both the size and the suffix; `rightType`/
 *     `returnType` are dropped entirely, the identical mnemonic-says-it-
 *     now treatment `RET`/`RET_FLOAT` and the `LOOKUP` split already get
 *     -- an integer compare and a float compare are genuinely different
 *     machine operations, the same "different runtime meaning gets its
 *     own mnemonic" precedent as `RET_FLOAT`. All six of this project's
 *     own comparison mnemonics (`BytecodeEmitter.COMPARISON_NAMES`) are
 *     covered now -- none left over.
 *   - "AND/OR leftType rightType returnType" -> "AND size" / "OR size" --
 *     confirmed directly, right after the comparison split above: "AND,
 *     OR and INC also just need the number of bytes of their first
 *     argument." Deliberately **not** split into `_INT`/`_FLOAT` like the
 *     six comparisons -- a logical AND/OR is the identical bitwise
 *     machine operation whatever the operand width, so there's no
 *     genuinely different runtime meaning here to earn a second mnemonic
 *     for; this is the plain "the mnemonic itself already says it" size-
 *     only treatment `ALLOC`/`ADDR`/`PUSH`/`ASSIGN` get, not the "new
 *     mnemonic per distinct machine op" treatment `RET_FLOAT`/`LOOKUP_*`/
 *     the `_INT`/`_FLOAT` comparisons get. `leftType` alone is read (it's
 *     always identical to `rightType` here -- `emitLogical` only ever
 *     calls this with same-typed operands); `rightType`/`returnType` are
 *     dropped, same as every other rewrite above.
 *   - "ADD/SUB/MUL/DIV/MOD leftType rightType returnType" -> "ADD_INT
 *     size" / "ADD_FLOAT size" / ... -- confirmed directly, in two
 *     rounds: first just size-only, the plain AND/OR treatment ("ADD,
 *     SUB, MUL, DIV and MOD just need the size of their first argument as
 *     well"), corrected moments later to add the `_INT`/`_FLOAT` split
 *     after all ("can you _int/_float split add/sub/mul/div/mod"), the
 *     identical reasoning `INC`/`DEC` just below get. `leftType` decides
 *     both size and suffix; `rightType`/`returnType` dropped.
 *   - "INC/DEC leftType returnType" -> "INC_INT size" / "INC_FLOAT size" /
 *     "DEC_INT size" / "DEC_FLOAT size" -- confirmed directly this pair
 *     *does* get the `_INT`/`_FLOAT` split the comparisons get, on the
 *     reasoning that "whether they really need it or not is sort of
 *     architecture independent, but its better done than not done" --
 *     consistency with the comparison split, not a strict requirement.
 *     `DEC` itself is a brand-new mnemonic on the compiler side, added in
 *     this same request ("DEC needs exactly the same treatment as INC")
 *     via a new `--` operator mirroring `++`/`emitIncrement` end to end
 *     (lexer, parser, type-checker, and `BytecodeEmitter.emitDecrement`)
 *     -- see that project's CLAUDE.md. Applied to `emitIncrement`'s/
 *     `emitDecrement`'s own two-type-operand shape (one fewer than
 *     AND/OR/the comparisons, since `x++`/`x--` only have one real
 *     operand); `returnType` is dropped, `leftType` decides both size and
 *     suffix via the same `isFloatBaseType`.
 *   - "RECURSIVE_CALL funcName" -> "CALL funcName", and "EXTERN_CALL name
 *     argCount" -> "CALL name argCount" -- confirmed directly: "extern
 *     call and recursive call can both become just call." `RECURSIVE_CALL`
 *     is a bare rename -- it was only ever a compile-time bookkeeping
 *     distinction, never a different runtime call mechanism, unlike
 *     `AWAIT_CALL`/`PAR_CALL` (deliberately left alone). `EXTERN_CALL`
 *     keeps its own `argCount` operand even once collapsed to `CALL` --
 *     an extern target has no `FUNC_START`/`ARG` lines for a codegen
 *     stage to recover arity from the way it can for an ordinary `CALL`,
 *     and a vararg extern's actual per-call argument count is real,
 *     call-site-specific information found nowhere else. Notable: no
 *     fixture in the whole corpus ever actually emits `RECURSIVE_CALL` --
 *     every legally-compiling `@recursive` self-call is unconditionally
 *     rewritten into a loop before `BytecodeEmitter` ever runs (see the
 *     compiler project's own `TypeChecker` doc comment) -- so this
 *     rewrite was verified by hand-feeding a `RECURSIVE_CALL` line
 *     directly into this pass rather than via a real compile.
 *   - "POP ARGn type" -> "POP ARGn size" -- confirmed directly: "POP
 *     should just need the register and the size of what its working
 *     with." Originally verified as the *only* thing `POP` was ever
 *     emitted for by the compiler itself (grepped the whole compiler):
 *     the call-argument-transfer machinery (`emitArgWord`/
 *     `emitSyntheticArgWord`), never for a return value (that's the
 *     separate `PUSH_RET` mnemonic) and never for cleanup after `AND`/
 *     `OR`/a comparison/arithmetic (those consume their own pushed
 *     operands implicitly, with no explicit pop instruction of any
 *     kind). `ARGn` itself is left completely alone -- the identical
 *     "not a name this pass resolves, just a positional label a codegen
 *     stage still needs verbatim" treatment `PUSH ARGn type`'s own
 *     `ARGn` operand already gets just above; only the trailing `type`
 *     operand drops to a plain size.
 *
 *     Since widened, deliberately, to a genuinely general "pop the
 *     current stack top into a named local" instruction -- `POP name
 *     type` now runs its own `name` operand through the exact same
 *     `resolveAddress` this pass already uses for `PUSH`/`ADDR` before
 *     falling back to leaving it verbatim: a real, `ALLOC`-declared
 *     local's name resolves to its own `$offset` (so a later ordinary
 *     `PUSH name type` reading it back resolves to the identical
 *     address), while `ARGn` (never `ALLOC`-declared under that literal
 *     text) is simply never found in `offsets` and keeps today's
 *     verbatim treatment unchanged -- one rewrite correctly serves both
 *     shapes with no special-casing needed. This is the "materialize
 *     whatever's on top of the stack into a named local" primitive
 *     `MembershipLoweringPass`'s own header flagged as a real follow-up
 *     rather than invented speculatively -- needed once `IN`'s left
 *     operand (of a `u64 in range`/`dynarray` shape) isn't a
 *     simple named push (e.g. `(3+4) in r`): `ASSIGN` can't do this
 *     instead, since `ASSIGN` needs its destination address pushed
 *     *before* the value being stored (`ADDR $tmp type` / `PUSH`.../
 *     `ASSIGN`...), which would mean inserting a line ahead of an
 *     arbitrary, already-emitted expression whose start was never
 *     located -- exactly the backward-tracing this rewrite avoids by
 *     construction. `POP name type` needs nothing pushed beforehand, so
 *     it drops in cleanly right after the value it's popping, wherever
 *     that already ends.
 *   - "PUSH_RET type" -> "PUSH_RET_INT size" / "PUSH_RET_FLOAT size" --
 *     confirmed directly, right after `POP`: "everything that isn't a
 *     float is an int, and it also needs the size of the type." The
 *     caller-side mirror of `RET`/`RET_FLOAT` -- same `isFloatBaseType`
 *     classification, same reasoning (a not-yet-built codegen stage
 *     needs to know whether the value it's picking back up off the stack
 *     came back in an integer or floating-point register) -- but unlike
 *     `RET`, both suffixes are new here; there's no bare `PUSH_RET` left
 *     over the way plain `RET` still exists for the non-float case.
 *     Never emitted for a `void`-returning call in the first place
 *     (`BytecodeEmitter`'s own "funcs that return void don't need this"
 *     skip), so there's no third, void-shaped case to handle here.
 *   - "PUSH_FIELDNAME fieldName fieldType" -> "PUSH_FIELDNAME
 *     memberOffset size" -- confirmed directly: "we should be able to
 *     resolve PUSH_FIELDNAME to use the calculated offset of the member
 *     in the struct... as its first argument and the number of bytes it
 *     leaves on the stack as its second argument, by just consulting the
 *     previous instruction." `size` is `sizes.sizeOf(fieldType)`, same as
 *     every other trailing type operand here. `memberOffset` needs to
 *     know *which struct* -- read from whatever instruction ran
 *     immediately before this one (`ADDR`/`PUSH`/`LOOKUP`/`PUSH_RET`/a
 *     prior `DOT`, confirmed directly it's always one of these), whose
 *     own trailing operand always names the type of the value it just
 *     left on the stack -- the same "every instruction states the type
 *     of what it leaves on the stack" rule this whole format already
 *     follows. Read in its *original*, not-yet-lowered form (`run()`'s
 *     own `previousLine`, captured before this same pass gets a chance to
 *     erase it) -- an already-lowered predecessor has had the very
 *     struct-name text this needs erased already. Left untouched if
 *     there's no previous line or its resulting base type isn't a real
 *     struct -- the identical "flag the gap, don't guess" treatment every
 *     other unresolvable case here already gets.
 *   - "ALLOC name type" -> "ALLOC size" -- a reservation needs nothing
 *     but its own size; its own position in the frame is already
 *     implicit in the order these lines already appear in (the same
 *     order this pass itself reads them in to assign every other
 *     line's own offset). Confirmed directly: **not** collapsed into a
 *     single, whole-frame reservation instruction yet ("eventually
 *     optimized to a single instruction manipulating the stack
 *     pointers," the aspiration "Whole-function ALLOC hoisting" already
 *     named on the compiler side) -- that's deliberately deferred until
 *     the sibling project's own still-open "ARG-to-ALLOC lowering"
 *     register-vs-stack-argument question is settled, so a parameter's
 *     own frame slot doesn't need re-deriving twice.
 *
 * "name" can be a plain local/parameter (every parameter already gets a
 * real ALLOC of its own -- see the sibling caspien-compiler project's
 * "ARG-to-ALLOC lowering") or a dotted chain rooted at one ("x.y",
 * "x.y.z") -- resolved by walking the chain's own declared member
 * layout (an ordinary struct's ALLOC-time member order, or a range's
 * own fixed two-word shape) and summing each step's own
 * byte offset.
 *
 * **Formerly a known gap, now closed on the compiler side -- this bail
 * is kept as a defensive fallback, not because it's expected to fire
 * any more:** a dotted chain that passes through a genuine pointer
 * partway through (e.g. "p.inner" where "p" itself has storage -- an
 * "auto"/"ref"/"raw"/... value) used to be left completely untouched
 * (the whole line, exactly as it already was) -- a real gap, since a
 * pointer's own pointee lives at a runtime-computed address (whatever
 * that pointer's own value happens to be), not a fixed, compile-time
 * offset from this function's own base pointer, and resolving that
 * needs a real runtime dereference instruction, not a constant.
 *
 * The sibling `caspien-compiler` project's own `BytecodeEmitter` no
 * longer ever hands this pass such a chain flattened into one bare
 * name string in the first place: `isQualifiedNameableDot` now checks,
 * at every level of a '.'-chain, whether that level's own base has
 * storage (not just whether the chain's root is a bare VARREF), and
 * routes any chain that fails this check through the same uncollapsed
 * "PUSH/PUSH_FIELDNAME/DOT-or-DOT_LHS+DEREF" shape a call result's own
 * dot-access (`x().y`) already used -- decided per level, so a chain
 * with a pointer at any position (not just the root, e.g. "x.y.z" with
 * both x and y themselves pointers) is handled correctly, one hop at a
 * time, never bulk-copying an intervening pointee. See
 * `caspien-compiler`'s own CLAUDE.md for the full design. This bail
 * remains here purely as a safety net for any other, not-yet-audited
 * caller that might still synthesize a flattened name this way -- it is
 * not expected to be reached by anything `BytecodeEmitter` itself emits
 * any more.
 *
 * **`CALL name` and `PUSH_LABEL name code_addr` are both confirmed
 * *finished*, not merely unaddressed** -- checked directly, not assumed:
 * `CALL` already carries nothing but a bare target label, no type
 * operand of any kind to ever shrink to a size (its own `argCount`
 * question was `EXTERN_CALL`'s alone, already resolved above), so there
 * is nothing left here for this pass to do. `PUSH_LABEL` is emitted from
 * exactly one site in the entire compiler (`BytecodeEmitter.
 * emitGtRoutineBody`, the `gt_routine` prologue), always in this same
 * "PUSH_LABEL label code_addr" shape -- `code_addr` is a fixed pseudo-
 * type, not a real, measurable one (there's no "size of a code address"
 * question the way there is for every other trailing type operand this
 * pass reduces), and the label itself is the whole point of the
 * instruction, not a name to resolve to an offset. Confirmed by grepping
 * the whole compiler and this project both: no other site, in either
 * project, ever emits either mnemonic differently. Both are therefore
 * marked done, not "not yet asked for" -- unlike the mnemonics below.
 *
 * ADDR_OF, MEMCOPY, NEW_FROM_STRING, and SEXT/ZEXT are also since confirmed
 * done -- see their own dedicated write-ups in this pass's format contract
 * further down. (`WITHIN`, like `IN` before it, never actually reaches
 * this pass at all any more -- `MembershipLoweringPass` fully lowers both
 * into ordinary comparison/logic primitives one stage earlier, so there's
 * no `WITHIN`/`PTR_WITHIN` shape left here to handle by the time this pass
 * runs. `GT_LOOP`/`CLONE_LOOP` never reach this pass at all either, for
 * the identical reason, one stage later than originally planned:
 * `DropGlueGenerationPass`/`CloneGenerationPass` were originally going
 * to hand off a new, not-yet-implemented mnemonic apiece for a
 * not-yet-built codegen stage to interpret, but both were rebuilt to
 * generate a real, ordinary JMP/CMP loop directly inline instead --
 * see each pass's own header for the reasoning -- so there is no
 * `GT_LOOP`/`CLONE_LOOP` shape left in the bytecode by the time this
 * pass ever runs, and nothing here needs to know about either mnemonic
 * any more.) `LOOKUP_LHS` is also since confirmed done -- see
 * `lookupMnemonicFor`'s own doc comment and this pass's own tryRewrite
 * below, which applies the identical `LOOKUP_ARRAY`/`LOOKUP_DYN` split
 * to it, appending "_LHS" to whichever name comes back. Every other
 * mnemonic this pass doesn't yet touch is left exactly as it already
 * was -- real, separate follow-up work, not assumed here.
 */
public class AddressLoweringPass implements OptimizationPass {

    private static final BytecodeParser PARSER = new BytecodeParser();

    @Override
    public String name() {
        return "address-lowering";
    }

    /**
     * Fixed path, resolved relative to the current working directory --
     * the identical "hard-required, no fallback" contract the sibling
     * `caspien-compiler` project's own copy of this file already
     * follows (see this project's own `CompilerConfig`'s doc comment).
     */
    private static final String CONFIG_PATH = "compiler.config";

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        StructTable structTable = StructTable.read(lines);
        SizeCalculator sizes = new SizeCalculator(structTable);
        EnumTable enumTable = EnumTable.read(lines);
        CompilerConfig config = CompilerConfig.load(CONFIG_PATH);
        List<List<BytecodeToken>> out = new ArrayList<>();
        boolean changed = false;

        int i = 0;
        int n = lines.size();
        while (i < n) {
            List<BytecodeToken> line = lines.get(i);
            if (line.isEmpty() || !line.get(0).text.equals("FUNC_START")) {
                // "STRUCT_DECORATE" is the one top-level-context decorator
                // mnemonic (FUNC_DECORATE/LOOP_DECORATE/FOR_DECORATE are
                // all handled inside a function body, just below) --
                // dropped here entirely, the exact same "purely
                // declarative metadata, already consumed upstream by
                // whichever earlier pass actually needed it, nothing left
                // to erase it *to*" treatment `RETURNS` already gets
                // in-function. Nothing in this project ever reads
                // STRUCT_DECORATE back out of the bytecode stream itself
                // (a real consumer, if one is ever built, would read it
                // off the AST/TypeChecker side instead, the same place
                // `resolveConvention` reads FUNC_DECORATE's own call-
                // convention name from -- not from these lines).
                if (!line.isEmpty() && line.get(0).text.equals("STRUCT_DECORATE")) {
                    changed = true;
                    i++;
                    continue;
                }
                // "STRUCT_START"/"STRUCT_MEMBER"/"STRUCT_END" and "ENUM"
                // (both a plain, user-declared one and every compiler-
                // synthesized one -- "Class", "ClassID", "$enum_for_...")
                // -- dropped here too, now that every real use either of
                // them ever had is already fully spent by this exact
                // point in this exact pass:
                //
                //   - A struct's own layout only ever mattered for
                //     computing a size (`ALLOC`/`GLOBAL`/`PUSH`'s own
                //     type operand, via `SizeCalculator.structSizeOf`) or
                //     a member's own offset (`PUSH_FIELDNAME`) -- both
                //     already read once, up front, off `StructTable.read
                //     (lines)` (built from these exact lines, in their
                //     original, not-yet-touched form, before this loop
                //     ever runs), and both already baked directly into
                //     whichever surviving instruction needed them. Nothing
                //     past that point ever looks a struct up by name
                //     again -- confirmed directly by checking every other
                //     rewrite in this whole pass, none of which reads
                //     `structTable` a second time independently of the
                //     one lookup each already performs through `sizes`/
                //     `memberLocOf`.
                //   - An enum's own variants only ever mattered for
                //     resolving one of three things, and by this point
                //     all three are already resolved: a plain/value-
                //     valued variant's own concrete value (this pass's own
                //     new `resolveLiteralOrEnumValue`, via `EnumTable.
                //     read(lines)`, the identical "read once, up front,
                //     off the original lines" shape `StructTable` already
                //     has); a "Class" enum's own [lo,hi] subtree range and
                //     a "$enum_for_..." enum's own ClassID list, both
                //     already fully consumed by `MembershipLoweringPass`
                //     -- a strictly *earlier* pass -- and rewritten there
                //     into literal comparisons, well before this pass ever
                //     starts; and a struct instance's own hidden
                //     `___type`/ClassID field, which was never enum-
                //     mediated at runtime in the first place -- always a
                //     plain integer literal, pushed directly by
                //     `BytecodeEmitter.emitStruct`'s own construction code
                //     (see that project's own CLAUDE.md), never looked up
                //     by name through any "ENUM" line at all.
                //
                // This pass is also the *last* one in the whole pipeline
                // (see `BytecodeOptimizer`'s own header) -- there is no
                // later stage these declarations could still be waiting
                // to serve either. Confirmed directly: "can we remove
                // struct and enum definitions from the lower order output
                // now?"
                if (!line.isEmpty() && (line.get(0).text.equals("STRUCT_START")
                        || line.get(0).text.equals("STRUCT_MEMBER") || line.get(0).text.equals("STRUCT_END")
                        || line.get(0).text.equals("ENUM"))) {
                    changed = true;
                    i++;
                    continue;
                }
                // A top-level line outside every function -- the one
                // shape here that's a real rewrite target of its own: a
                // top-level "GLOBAL" declaration (`ALLOC_STATIC` is always
                // inside a function, already handled below via
                // `tryRewrite`). Found and fixed directly: without this,
                // a genuine top-level global was passed straight through
                // by this same "not a function, carry it over" branch,
                // completely untouched, even after `ALLOC_STATIC` had
                // already been switched over to the identical treatment.
                List<BytecodeToken> staticRewrite = rewriteStaticOrGlobalDecl(line, sizes, enumTable);
                if (staticRewrite != null) {
                    out.add(staticRewrite);
                    changed = true;
                } else {
                    out.add(line);
                }
                i++;
                continue;
            }

            int start = i;
            int end = start;
            while (end < n && !(lines.get(end).size() > 0 && lines.get(end).get(0).text.equals("FUNC_END"))) {
                end++;
            }
            end = Math.min(end, n - 1);

            // Every ALLOC in this function, in the exact order it's
            // already emitted in (params first, then locals -- see
            // "ARG-to-ALLOC lowering"), assigns the next slot a size and
            // a negative offset from the base pointer -- the stack
            // growing downward, one slot immediately below the last.
            //
            // Real per-variable alignment (companion to the compiler
            // side's own struct-padding round -- see this pass's own
            // CLAUDE.md, "aligning the variables within the stack
            // frame"): `rbp` itself is always 16-aligned (the frame's
            // own combined size is rounded up to 16 below, and every
            // caller's own `call` already lands here 16-aligned per the
            // x86-64 SysV ABI), and 16 is a multiple of every alignment
            // this language's primitives can ever demand (8, at most --
            // no over-aligned/SIMD types exist here), so a variable's
            // own address (`rbp + cursor`, `cursor` always <= 0) is
            // correctly aligned exactly when `cursor` itself, taken as a
            // plain number, is a multiple of that variable's own
            // alignment. Since `cursor` only ever grows more negative,
            // "round up to the next multiple" (the ordinary,
            // ascending-offset move struct-field layout uses) becomes
            // "round DOWN to the next multiple" here -- the numerically
            // smaller (more negative) neighbor, not the larger one --
            // which is exactly what leaves a gap *above* the new slot
            // (between it and whatever was placed immediately before
            // it), the correct side for a downward-growing region. Any
            // such gap is never assigned to any variable's own offset;
            // it's simply absorbed into `totalAllocSize` below, the same
            // way the final round-up-to-16 padding already is.
            Map<String, Long> offsets = new HashMap<>();
            Map<String, String> localTypes = new LinkedHashMap<>();
            long cursor = 0;
            for (int k = start; k <= end; k++) {
                List<BytecodeToken> fl = lines.get(k);
                if (!fl.isEmpty() && fl.get(0).text.equals("ALLOC") && fl.size() >= 3) {
                    String varName = fl.get(1).text;
                    String varType = fl.get(2).text;
                    long align = sizes.alignOf(varType);
                    long remainder = cursor % align; // Java's `%`: dividend's sign, so this is in (-align, 0] for cursor <= 0
                    if (remainder != 0) {
                        cursor -= (align + remainder); // move to the next multiple of `align` at or below the current cursor
                    }
                    cursor -= sizes.sizeOf(varType);
                    offsets.put(varName, cursor);
                    localTypes.put(varName, varType);
                }
            }
            // "all the ALLOC statements in a function should all end up in
            // the same place... they resolve to just the sum," confirmed
            // directly -- every one of this function's own locals/params
            // already gets its own fixed, negative base-pointer offset
            // above, entirely independent of how many separate "ALLOC"
            // *lines* actually survive to the final output; merging them
            // into one combined reservation changes nothing about those
            // offsets, only how the frame's own space is asked for. A
            // real backend reserves an entire frame in one shot ("sub rsp,
            // N") rather than growing it once per local, so N separate
            // "ALLOC size" lines were never anything more than "the sum,
            // spelled out one term at a time" -- `-cursor` here already
            // *is* that sum, computed the identical way each variable's
            // own offset just was, just read once more after the loop
            // finishes. Explicitly excludes `ALLOC_STATIC`/`GLOBAL`
            // (unaffected, per this fix's own scope): a static/global has
            // no stack frame to share space in at all -- it's a
            // separate, independently-addressed memory location on its
            // own, never one term in this function's own single "sub rsp"
            // -- confirmed by this same loop's own `equals("ALLOC")` check
            // never matching either of those two distinct mnemonics.
            long totalAllocSize = -cursor;
            // Rounded UP to the next multiple of 16, never down -- "it
            // grows to accommodate %16ness, not shrinks," confirmed
            // directly. This is the other half of real stack-alignment
            // practice (see this pass's own CLAUDE.md section on it): a
            // real prologue's one "sub rsp, N" needs to leave RSP a
            // multiple of 16 for the rest of the function body, not just
            // at entry, so the frame's own total size has to be a
            // multiple of 16 -- padding it up by at most 15 bytes is
            // always safe (the extra bytes are simply never assigned to
            // any variable's own offset above), where rounding down could
            // silently truncate a real variable's own already-computed
            // slot. `% 16` rather than `& 15` to read directly as "the
            // remainder," matching this project's own "no cleverness,
            // straightforward arithmetic" style; either is exact for a
            // non-negative operand, which `totalAllocSize` always is
            // (a sum of non-negative sizes).
            long allocRemainder = totalAllocSize % 16;
            if (allocRemainder != 0) {
                totalAllocSize += 16 - allocRemainder;
            }

            CompilerConfig.CallingConvention convention = resolveConvention(lines, start, end, config);

            // Resolves every "PUSH ARGn"/"PUSH FARGn" parameter-loading
            // word in this function, up front, the identical "compute the
            // whole function's own layout before rewriting any single
            // line of it" shape the `offsets`/`totalAllocSize` walk just
            // above already uses for ordinary locals. See
            // `resolveArgWordSlots`'s own doc comment for why a single
            // word can no longer be resolved correctly in isolation, one
            // line at a time, now that an int word and a float word can
            // be interleaved in the same parameter list.
            Map<String, ArgWordSlot> argWordSlots = resolveArgWordSlots(lines, start, end, convention);

            // Tracks the immediately preceding line in *original,
            // pre-rewrite* form -- needed only for "PUSH_FIELDNAME
            // fieldName fieldType" (see tryRewrite), which has to read
            // the struct type off whatever instruction just ran before
            // it (confirmed directly: every instruction's own trailing
            // operand names the type of the value it leaves on the
            // stack, so the line right before PUSH_FIELDNAME always
            // already says which struct's own layout to consult -- "by
            // just consulting the previous instruction"). Deliberately
            // the *original* line, not whatever this pass may have
            // already rewritten it down to on this same pass -- an
            // already-lowered predecessor (e.g. a prior LOOKUP already
            // turned into "LOOKUP_ARRAY 16") has had its own struct-name
            // type text erased entirely, exactly the information this
            // still needs.
            List<BytecodeToken> previousLine = null;
            boolean combinedAllocEmitted = false;
            Map<Integer, Integer> literalAssigns = findStructLiteralAssigns(lines, start, end, structTable);
            Map<Integer, Integer> arrayAssigns = findArrayLiteralAssigns(lines, start, end, sizes, structTable);
            for (int k = start; k <= end; k++) {
                List<BytecodeToken> fl = lines.get(k);
                // Every in-function "ALLOC name type" line collapses into
                // one single combined "ALLOC totalAllocSize" -- emitted
                // once, in place of the *first* one reached (matching
                // where a real prologue's own one-shot frame reservation
                // belongs, and where every ALLOC already sits today: all
                // hoisted to the top of the function body, ahead of every
                // other instruction -- see `BytecodeEmitter.
                // collectHoistedAllocs`). Every later ALLOC line in this
                // same function is simply dropped -- its own size already
                // folded into `totalAllocSize` above, and its own
                // variable's own offset already resolved independently of
                // how many ALLOC lines survive to output at all. Matched
                // on `line.size() == 3` specifically, the exact
                // "ALLOC name type" shape -- never `ALLOC_STATIC`, a
                // genuinely distinct mnemonic this check can't accidentally
                // catch. `previousLine` is still updated to each original
                // ALLOC line here (unlike `RETURNS`/the `*_DECORATE`
                // drops) -- an ALLOC line's own trailing type operand is
                // exactly the kind of "what does the line before this one
                // say" text `PUSH_FIELDNAME`'s own lookup already reads
                // off *any* preceding instruction, so there's no reason to
                // special-case it out of that just because most of these
                // lines no longer reach `out`.
                if (!fl.isEmpty() && fl.get(0).text.equals("ALLOC") && fl.size() == 3) {
                    changed = true;
                    if (!combinedAllocEmitted) {
                        combinedAllocEmitted = true;
                        out.add(PARSER.parse(Collections.singletonList("ALLOC " + totalAllocSize),
                                "<address-lowered>").get(0));
                    }
                    previousLine = fl;
                    continue;
                }
                // "REGVAR name weight [f]" (a hint from the Optimizer's RegVarHintPass) becomes "REGHINT offset size weight [f]":
                // the frame offset and width of that variable, resolved here with the same layout every other name uses.
                // RegisterFormPass consumes it; LowerOrderGenerator strips any that remain. An unknown name drops the hint.
                if (!fl.isEmpty() && fl.get(0).text.equals("REGVAR") && (fl.size() == 3 || fl.size() == 4)) {
                    changed = true;
                    Long hintOff = offsets.get(fl.get(1).text);
                    String hintType = localTypes.get(fl.get(1).text);
                    if (hintOff != null && hintType != null) {
                        out.add(PARSER.parse(Collections.singletonList(
                                "REGHINT " + hintOff + " " + sizes.sizeOf(hintType) + " " + fl.get(2).text + (fl.size() == 4 ? " " + fl.get(3).text : "")),
                                "<address-lowered>").get(0));
                    }
                    continue;
                }
                // "RETURNS can be omitted" -- confirmed directly, once
                // "RET"/"RET_FLOAT" (see tryRewrite below) each carry
                // their own byte count directly, the function's own
                // declared return type is no longer needed as a separate
                // line at all: dropped here entirely, not merely
                // rewritten to a bare size the way every other type
                // operand in this pass is. (A caller's own "PUSH_RET
                // type" at each call site still carries the callee's
                // return type in full -- untouched by this, and still
                // this pass's only other reference to it.)
                if (!fl.isEmpty() && fl.get(0).text.equals("RETURNS")) {
                    changed = true;
                    continue;
                }
                // "FUNC_DECORATE"/"LOOP_DECORATE"/"FOR_DECORATE" -- the
                // in-function counterparts of the top-level
                // "STRUCT_DECORATE" drop just above (see that branch's
                // own comment for the full reasoning): purely
                // declarative source-level metadata, already fully
                // consumed by whichever earlier stage actually needed it
                // (`resolveConvention`, run once per function before this
                // very loop, already read every "@call_convention(...)"
                // FUNC_DECORATE it needs directly off `lines`, not off
                // whatever ends up in `out`), so none of the three carry
                // anything a not-yet-built codegen stage still needs by
                // this point. `previousLine` deliberately left unchanged
                // here, exactly like `RETURNS` above -- a decorator line
                // is never a real value-producing instruction a later
                // "PUSH_FIELDNAME"/"ADDR_OF" lookup should ever see as
                // "the thing that just ran."
                if (!fl.isEmpty() && (fl.get(0).text.equals("FUNC_DECORATE")
                        || fl.get(0).text.equals("LOOP_DECORATE") || fl.get(0).text.equals("FOR_DECORATE"))) {
                    changed = true;
                    continue;
                }
                // "ADDR_OF opText leftType returnType" -> dropped
                // entirely (a no-op retag), for exactly two real cases:
                // "ADDR_OF REF" on an existing 'owns' variable (the
                // pushed value already *is* the correct pointer -- `ref`
                // never computes a new address, it borrows the one the
                // 'owns' value already has), and "ADDR_OF RAW" on a
                // string literal (already hoisted to real static memory
                // by `case STRING:` in the sibling caspien-compiler
                // project's own TypeChecker, long before 'raw' is ever
                // applied -- see that project's CLAUDE.md). Both are the
                // identical "already storage-bearing, nothing left to
                // compute" situation `RETURNS` is dropped for just
                // above -- not to a bare size or an address, just erased.
                //
                // The check used to be far broader than that -- "the
                // immediately preceding line's own trailing type carries
                // ANY storage at all" -- which happened to also match a
                // third, genuinely different shape this rule was never
                // meant to cover: "raw val" (or "auto val") where `val`
                // is a plain, ordinary *local variable* whose own
                // declared type simply happens to be pointer-typed (e.g.
                // "let val: raw mut u8 = null" -- a perfectly common
                // pattern, `gtReadSlot`'s own "memcopy(raw val, mut 8,
                // slot)" among them). Confirmed directly, via a real
                // segfault: `val` here is an addressable stack slot like
                // any other, per `checkAddressOf`'s own "raw"/"auto"
                // rule (both require a real addressable lvalue) -- what
                // needs computing is the ADDRESS of that slot, never a
                // reuse of whatever pointer value currently happens to
                // be sitting inside it (which, before the address is
                // ever written, is `null` -- exactly the "PUSH val
                // raw_mut_u8" left behind once the broad check wrongly
                // dropped `ADDR_OF` here, later fed to `MEMCOPY` as a
                // null destination). Narrowed to the two real cases this
                // was actually meant for -- opText itself ("REF" vs
                // "RAW") now distinguishes them, not just the pushed
                // type's storage -- so a plain pointer-typed local still
                // gets a real, computed address below, exactly like a
                // non-pointer-typed one always did.
                //
                // `previousLine` is still updated afterward (unlike
                // `RETURNS`, which never has a real consumer after it)
                // since a real one -- PUSH_FIELDNAME's own struct-type
                // lookup, say -- may need to see what's still genuinely
                // on the stack.
                if (!fl.isEmpty() && fl.get(0).text.equals("ADDR_OF") && fl.size() == 4 && previousLine != null
                        && !previousLine.isEmpty()
                        && isAlreadyCorrectPointerRetag(fl.get(1).text,
                                CanonicalType.parse(previousLine.get(previousLine.size() - 1).text).storage)) {
                    changed = true;
                    previousLine = fl;
                    continue;
                }
                List<BytecodeToken> rewritten = tryRewrite(fl, offsets, localTypes, sizes, structTable, enumTable,
                        convention, previousLine, argWordSlots);
                if (rewritten != null && literalAssigns.containsKey(k) && rewritten.size() == 4) {
                    // A stack struct literal's own top-level store: append the struct's physical layout (see the "NEW" rewrite
                    // for the token format), so Codegen can repack the pushed field words when the field values are not all
                    // plain pushes (see X86Backend.emitAssignRepack). Codegen ignores it on its packed fast path.
                    List<StructTable.LayoutEntry> lit = structTable.layoutOf(CanonicalType.parse(fl.get(1).text).baseType);
                    if (lit != null) {
                        StringBuilder sb = new StringBuilder(fl.get(0).text);
                        for (int q = 1; q < 4; q++) {
                            sb.append(' ').append(rewritten.get(q).text);
                        }
                        sb.append(layoutTokens(lit, lines, literalAssigns.get(k), k, sizes, structTable, enumTable));
                        rewritten = PARSER.parse(Collections.singletonList(sb.toString()), "<address-lowered>").get(0);
                    }
                }
                if (rewritten != null && arrayAssigns.containsKey(k) && rewritten.size() == 4) {
                    String at = fl.get(1).text;
                    CanonicalType act = CanonicalType.parse(at);
                    String elemT = CanonicalType.fixedArrayElementTypeOf(act.baseType);
                    int arrN = CanonicalType.fixedArrayLengthOf(act.baseType);
                    List<Integer> units = splitValueUnits(lines, arrayAssigns.get(k), k, enumTable);
                    if (units != null && arrN > 0 && units.size() == arrN) {
                        boolean computed = false;
                        boolean wholeCopy = false;
                        for (int u : units) {
                            computed |= !lines.get(u).get(0).text.equals("PUSH");
                            wholeCopy |= lines.get(u).get(lines.get(u).size() - 1).text.equals(at);
                        }
                        if (computed && !wholeCopy) {
                            rewritten = PARSER.parse(Collections.singletonList(fl.get(0).text + " " + rewritten.get(1).text + " " + rewritten.get(2).text
                                    + " " + rewritten.get(3).text + " a" + sizes.sizeOf(elemT) + "x" + arrN), "<address-lowered>").get(0);
                        }
                    }
                }
                if (rewritten != null && fl.get(0).text.equals("NEW") && fl.size() == 2 && rewritten.size() > 2) {
                    // Heap struct literal: the same element-wise array handling as for a stack literal (see the ASSIGN case above).
                    int runStart = findNewRunStart(lines, start, k, structTable, enumTable);
                    List<StructTable.LayoutEntry> nl = structTable.layoutOf(CanonicalType.parse(fl.get(1).text).baseType);
                    if (runStart >= 0 && nl != null) {
                        rewritten = PARSER.parse(Collections.singletonList("NEW " + rewritten.get(1).text
                                + layoutTokens(nl, lines, runStart, k, sizes, structTable, enumTable)), "<address-lowered>").get(0);
                    }
                }
                if (rewritten != null) {
                    out.add(rewritten);
                    changed = true;
                } else {
                    out.add(fl);
                }
                previousLine = fl;
            }

            i = end + 1;
        }

        return new PassResult(out, changed);
    }

    /**
     * Indices (within [start, end]) of the ASSIGN lines that store a stack struct literal, each mapped to its literal's first line: the literal's own sequence is
     * "ADDR v T", "PUSH <digits> imut_u64" (the struct's ___type id), the member values, then "ASSIGN T T T". A whole-value copy
     * ("ADDR v T", "PUSH w T", "ASSIGN T T T") has no such type-id push and is not matched.
     */
    private static Map<Integer, Integer> findStructLiteralAssigns(List<List<BytecodeToken>> lines, int start, int end, StructTable structTable) {
        Map<Integer, Integer> result = new HashMap<>();   // ASSIGN line index -> index of the literal's own "ADDR v T" line
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Integer> pendingStart = new java.util.ArrayDeque<>();
        for (int k = start; k <= end; k++) {
            List<BytecodeToken> l = lines.get(k);
            if (l.isEmpty()) {
                continue;
            }
            String m = l.get(0).text;
            if (m.equals("ADDR") && l.size() == 3 && k + 1 <= end) {
                List<BytecodeToken> nx = lines.get(k + 1);
                if (nx.size() == 3 && nx.get(0).text.equals("PUSH") && nx.get(2).text.equals("imut_u64")
                        && nx.get(1).text.matches("[0-9]+")) {
                    CanonicalType ct = CanonicalType.parse(l.get(2).text);
                    if (ct.storage == null && structTable.layoutOf(ct.baseType) != null) {
                        pending.push(l.get(2).text);
                        pendingStart.push(k);
                    }
                }
            } else if (m.equals("PUSH") && l.size() == 3 && l.get(1).text.equals("$ret_dest") && l.get(2).text.startsWith("raw_") && k + 1 <= end) {
                // Return value optimisation: the literal is stored through the hidden destination pointer
                // ("PUSH $ret_dest raw_mut_S / classId / members / ASSIGN mut_S indeterminate_S mut_S"). The pointer value sits where an
                // "ADDR v" would, so the store is the same repack.
                List<BytecodeToken> nx = lines.get(k + 1);
                if (nx.size() == 3 && nx.get(0).text.equals("PUSH") && nx.get(2).text.equals("imut_u64") && nx.get(1).text.matches("[0-9]+")) {
                    CanonicalType ct = CanonicalType.parse(l.get(2).text.substring(4));
                    if (ct.storage == null && structTable.layoutOf(ct.baseType) != null) {
                        pending.push("RVO:" + l.get(2).text.substring(4));
                        pendingStart.push(k);
                    }
                }
            } else if ((m.equals("ASSIGN") || m.equals("ATOMIC_ASSIGN")) && l.size() == 4 && !pending.isEmpty()
                    && pending.peek().startsWith("RVO:")) {
                String t = pending.peek().substring(4);
                if (l.get(1).text.equals(t) && l.get(3).text.equals(t)) {
                    pending.pop();
                    result.put(k, pendingStart.pop());
                }
            } else if ((m.equals("ASSIGN") || m.equals("ATOMIC_ASSIGN")) && l.size() == 4 && !pending.isEmpty()
                    && l.get(1).text.equals(pending.peek()) && l.get(2).text.equals(pending.peek()) && l.get(3).text.equals(pending.peek())) {
                pending.pop();
                result.put(k, pendingStart.pop());
            }
        }
        return result;
    }

    /**
     * The ASSIGN lines that store a stack array literal of scalar elements narrower than 8 bytes (`let a: u16[3] = [c, c + 1, 7]`), mapped to the
     * literal's "ADDR v T" line. Each element is pushed as its own whole word; when one of them is computed Codegen cannot treat the run as a packed
     * image, so the ASSIGN gets an "a<elemBytes>x<n>" token (same repack as an array member of a struct literal).
     */
    private static Map<Integer, Integer> findArrayLiteralAssigns(List<List<BytecodeToken>> lines, int start, int end, SizeCalculator sizes,
            StructTable structTable) {
        Map<Integer, Integer> result = new HashMap<>();
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Integer> pendingStart = new java.util.ArrayDeque<>();
        for (int k = start; k <= end; k++) {
            List<BytecodeToken> l = lines.get(k);
            if (l.isEmpty()) {
                continue;
            }
            String m = l.get(0).text;
            if (m.equals("ADDR") && l.size() == 3) {
                CanonicalType ct = CanonicalType.parse(l.get(2).text);
                String elem = ct.storage == null ? CanonicalType.fixedArrayElementTypeOf(ct.baseType) : null;
                if (elem != null && elem.indexOf('[') < 0 && structTable.layoutOf(elem) == null && sizes.sizeOf(elem) < 8) {
                    pending.push(l.get(2).text);
                    pendingStart.push(k);
                }
            } else if ((m.equals("ASSIGN") || m.equals("ATOMIC_ASSIGN")) && l.size() == 4 && !pending.isEmpty()
                    && l.get(1).text.equals(pending.peek()) && l.get(2).text.equals(pending.peek()) && l.get(3).text.equals(pending.peek())) {
                pending.pop();
                result.put(k, pendingStart.pop());
            }
        }
        return result;
    }

    private static final Set<String> VALUE_BINARY_OPS = new HashSet<>(java.util.Arrays.asList(
            "ADD", "SUB", "MUL", "DIV", "MOD", "SHL", "SHR", "BITS_OR", "BITS_AND", "BITS_XOR", "BITS_LEFT", "BITS_RIGHT",
            "AND", "OR", "EQ", "NEQ", "LT", "LT_EQ", "GT", "GT_EQ", "LOOKUP"));
    private static final Set<String> VALUE_UNARY_OPS = new HashSet<>(java.util.Arrays.asList("NEG", "NOT", "BITS_NOT", "TRUNC", "SEXT", "ZEXT", "FCONV"));

    /**
     * Splits the postfix lines strictly between `from` and `to` (a struct literal's member values, its type-id push first) into their top-level
     * values, by simulating the operand stack: pushes add an entry, binary operators merge two, calls leave one value at PUSH_RET, a nested heap
     * literal collapses at its NEW. Returns, for each top-level value in order, the index of the line that produced it (its last token is the
     * value's type); null if any line is not understood, in which case the caller falls back to counting.
     */
    private static List<Integer> splitValueUnits(List<List<BytecodeToken>> lines, int from, int to, EnumTable enumTable) {
        java.util.ArrayList<int[]> stack = new java.util.ArrayList<>();   // {producer line, 1 if a struct type-id push}
        for (int j = from + 1; j < to; j++) {
            List<BytecodeToken> l = lines.get(j);
            if (l.isEmpty()) {
                continue;
            }
            String m = l.get(0).text;
            if (m.startsWith("@")) {
                continue;   // label
            }
            switch (m) {
                case "PUSH":
                    stack.add(new int[] {j, (l.size() == 3 && l.get(2).text.equals("imut_u64") && l.get(1).text.matches("[0-9]+")) ? 1 : 0});
                    break;
                case "PUSH_LABEL":
                case "ADDR":
                case "PUSH_RET":
                    stack.add(new int[] {j, 0});
                    break;
                case "DUP_TOP":   // the out-of-memory check after a nested `new`: DUP_TOP / PUSH null / EQ / CMP / JMP, then GT_REGISTER
                    if (stack.isEmpty()) {
                        return null;
                    }
                    stack.add(new int[] {j, 0});
                    break;
                case "CMP":
                    if (stack.isEmpty()) {
                        return null;
                    }
                    stack.remove(stack.size() - 1);
                    break;
                case "JMP":
                case "GT_REGISTER":
                case "STACK_LOCK":
                case "CC_START":
                case "CC_END":
                case "CALL":
                case "RECURSIVE_CALL":
                case "EXTERN_CALL":
                case "VARARGS_XMM_COUNT":
                    break;
                case "POP":
                    if (stack.isEmpty()) {
                        return null;
                    }
                    stack.remove(stack.size() - 1);
                    break;
                case "ASSIGN":
                case "ATOMIC_ASSIGN":
                    if (stack.size() < 2) {
                        return null;
                    }
                    stack.remove(stack.size() - 1);
                    stack.remove(stack.size() - 1);
                    break;
                case "NEW": {
                    // The nested literal starts at the nearest type-id push carrying this struct's own class id (an inline struct literal, e.g. an
                    // array element, has its own type-id push inside it and must not be taken for the start).
                    Long wanted = l.size() >= 2 ? classIdOf(CanonicalType.parse(l.get(1).text).baseType, enumTable) : null;
                    int q = stack.size() - 1;
                    while (q >= 0 && (stack.get(q)[1] == 0 || (wanted != null && !typeIdValue(lines.get(stack.get(q)[0])).equals(wanted)))) {
                        q--;
                    }
                    if (q < 0) {
                        return null;
                    }
                    while (stack.size() > q) {
                        stack.remove(stack.size() - 1);
                    }
                    stack.add(new int[] {j, 0});
                    break;
                }
                default:
                    if (VALUE_BINARY_OPS.contains(m) && l.size() == 4 && stack.size() >= 2) {
                        stack.remove(stack.size() - 1);
                        stack.remove(stack.size() - 1);
                        stack.add(new int[] {j, 0});
                    } else if (VALUE_UNARY_OPS.contains(m) && !stack.isEmpty()) {
                        stack.set(stack.size() - 1, new int[] {j, 0});
                    } else {
                        return null;
                    }
            }
        }
        List<Integer> units = new ArrayList<>();
        for (int[] e : stack) {
            units.add(e[0]);
        }
        return units;
    }

    /**
     * The layout descriptor tokens (each preceded by a space): "m<bytes>" member, "p<bytes>" padding, "a<elemBytes>x<n>" for a fixed array of
     * scalars narrower than 8 bytes that this literal writes element by element (n whole 8-byte words on the stack, one per element).
     * An array taken from an existing variable is pushed as one block instead (ceil(size/8) words) and stays "m<bytes>".
     *
     * Which of the two each array member is comes from splitting the literal's values (splitValueUnits): a member's value is a block when
     * the line that produced it has an array type, otherwise it is n element values. If the lines cannot be split, it falls back to counting
     * array-typed value lines; that is only decisive when no array type is shared by two members, otherwise the literal is rejected.
     */
    private static String layoutTokens(List<StructTable.LayoutEntry> layout, List<List<BytecodeToken>> lines, int from, int to,
            SizeCalculator sizes, StructTable structTable, EnumTable enumTable) {
        List<Integer> units = splitValueUnits(lines, from, to, enumTable);
        StringBuilder sb = new StringBuilder();
        if (units != null) {
            List<String> tokens = new ArrayList<>();
            int[] u = {0};
            if (layoutFromUnits(layout, lines, units, u, tokens, sizes, structTable) && u[0] == units.size()) {
                for (String tk : tokens) {
                    sb.append(' ').append(tk);
                }
                return sb.toString();
            }
        }
        Map<String, Integer> arrayValueLines = countArrayValueLines(lines, from, to);
        for (StructTable.LayoutEntry entry : layout) {
            if (entry.member == null) {
                sb.append(" p").append(entry.paddingBytes);
                continue;
            }
            String elementwise = elementwiseArrayToken(entry.member.canonicalType, layout, arrayValueLines, sizes, structTable);
            sb.append(' ').append(elementwise != null ? elementwise : "m" + sizes.sizeOf(entry.member.canonicalType));
        }
        return sb.toString();
    }

    private static final java.util.regex.Pattern ARRAY_TYPE = java.util.regex.Pattern.compile("^(.+)\\[([0-9]+)\\]$");

    /**
     * Appends the descriptor tokens for one struct layout, consuming the literal's top-level value units (u[0] is the next unit). Returns false
     * if the units do not fit the layout (the caller then falls back to counting).
     */
    private static boolean layoutFromUnits(List<StructTable.LayoutEntry> layout, List<List<BytecodeToken>> lines, List<Integer> units, int[] u,
            List<String> tokens, SizeCalculator sizes, StructTable structTable) {
        for (StructTable.LayoutEntry entry : layout) {
            if (entry.member == null) {
                tokens.add("p" + entry.paddingBytes);
                continue;
            }
            CanonicalType ct = CanonicalType.parse(entry.member.canonicalType);
            if (ct.storage != null) {
                if (u[0] >= units.size()) {
                    return false;
                }
                tokens.add("m" + sizes.sizeOf(entry.member.canonicalType));
                u[0]++;
                continue;
            }
            if (!ARRAY_TYPE.matcher(ct.baseType).matches() && structTable.layoutOf(ct.baseType) == null) {
                if (u[0] >= units.size()) {
                    return false;
                }
                tokens.add("m" + sizes.sizeOf(entry.member.canonicalType));
                u[0]++;
                continue;
            }
            if (!valueFromUnits(ct.baseType, lines, units, u, tokens, sizes, structTable)) {
                return false;
            }
        }
        return true;
    }

    /**
     * One plain (no storage) value of type `base` -- a scalar, a fixed array or an inline struct -- as it sits in the literal's units. A value whose producer
     * already has exactly this type is one whole block (a variable): "m<size>". Otherwise an array is its elements one after another (a narrow scalar
     * array is the single token "a<elemBytes>x<n>", n words) and a struct literal is its own layout (type id, members, padding), recursively.
     */
    private static boolean valueFromUnits(String base, List<List<BytecodeToken>> lines, List<Integer> units, int[] u, List<String> tokens,
            SizeCalculator sizes, StructTable structTable) {
        if (u[0] >= units.size()) {
            return false;
        }
        List<BytecodeToken> producer = lines.get(units.get(u[0]));
        String last = producer.get(producer.size() - 1).text;
        boolean isArray = ARRAY_TYPE.matcher(base).matches();
        List<StructTable.LayoutEntry> structLayout = isArray ? null : structTable.layoutOf(base);
        if ((isArray || structLayout != null) && CanonicalType.parse(last).baseType.equals(base)) {
            tokens.add("m" + sizes.sizeOf("mut_" + base));
            u[0]++;
            return true;
        }
        if (structLayout != null) {
            return layoutFromUnits(structLayout, lines, units, u, tokens, sizes, structTable);
        }
        if (isArray) {
            java.util.regex.Matcher mt = ARRAY_TYPE.matcher(base);
            mt.matches();
            String elem = mt.group(1);
            long n = Long.parseLong(mt.group(2));
            boolean nested = ARRAY_TYPE.matcher(elem).matches() || structTable.layoutOf(elem) != null;
            if (!nested) {
                String narrow = narrowElementToken(elem, n, sizes, structTable);
                tokens.add(narrow != null ? narrow : "m" + sizes.sizeOf("mut_" + base));
                u[0] += (int) n;
                return u[0] <= units.size();
            }
            for (long k = 0; k < n; k++) {
                if (!valueFromUnits(elem, lines, units, u, tokens, sizes, structTable)) {
                    return false;
                }
            }
            return true;
        }
        tokens.add("m" + sizes.sizeOf("mut_" + base));
        u[0]++;
        return true;
    }

    /** "a<elemBytes>x<n>" if `elem` is a plain scalar type narrower than 8 bytes, else null. */
    private static String narrowElementToken(String elem, long n, SizeCalculator sizes, StructTable structTable) {
        if (!elem.matches("[A-Za-z0-9_]+") || structTable.layoutOf(elem) != null || n <= 0) {
            return null;
        }
        long e = sizes.sizeOf("mut_" + elem);
        return e > 0 && e < 8 ? "a" + e + "x" + n : null;
    }

    /**
     * Index of the line just before a heap struct literal's own type-id push ("PUSH <digits> imut_u64") for the "NEW" at `newIdx`, walking
     * back and skipping nested literals (each nested "NEW" has its own type-id push); -1 if it is not found within [start, newIdx).
     */
    private static int findNewRunStart(List<List<BytecodeToken>> lines, int start, int newIdx, StructTable structTable, EnumTable enumTable) {
        String self = lines.get(newIdx).size() >= 2 ? CanonicalType.parse(lines.get(newIdx).get(1).text).baseType : null;
        Long wanted = self == null ? null : classIdOf(self, enumTable);
        int depth = 1;
        for (int j = newIdx - 1; j >= start; j--) {
            List<BytecodeToken> l = lines.get(j);
            if (l.isEmpty()) {
                continue;
            }
            if (l.get(0).text.equals("NEW")) {
                // with a known class id only a nested literal of the same struct can own a type-id push that matches; otherwise every NEW counts
                if (wanted == null || (l.size() >= 2 && self.equals(CanonicalType.parse(l.get(1).text).baseType))) {
                    depth++;
                }
            } else if (l.size() == 3 && l.get(0).text.equals("PUSH") && l.get(2).text.equals("imut_u64") && l.get(1).text.matches("[0-9]+")) {
                if (wanted != null && !typeIdValue(l).equals(wanted)) {
                    continue;   // the type-id push of an inline struct literal (an array element, an inline member), not this literal's own
                }
                depth--;
                if (depth == 0) {
                    return j - 1;   // the value-lines range starts after this line; j-1 keeps the exclusive lower bound below the push itself
                }
            }
        }
        return -1;
    }

    /** The struct's class id (the value of its `___type` push), or null if the ClassID enum does not list it. */
    private static Long classIdOf(String structBaseType, EnumTable enumTable) {
        return enumTable == null ? null : enumTable.variantValue("ClassID", structBaseType);
    }

    private static Long typeIdValue(List<BytecodeToken> pushLine) {
        try {
            return pushLine.size() >= 2 ? Long.valueOf(pushLine.get(1).text) : Long.valueOf(-1);
        } catch (NumberFormatException e) {
            return Long.valueOf(-1);
        }
    }

    /** Number of lines in (from, to) whose last token is an array type, keyed by that array's base type text (a whole array value pushed as one block). */
    private static Map<String, Integer> countArrayValueLines(List<List<BytecodeToken>> lines, int from, int to) {
        Map<String, Integer> counts = new HashMap<>();
        for (int j = from + 1; j < to; j++) {
            List<BytecodeToken> l = lines.get(j);
            if (l.size() < 2) {
                continue;
            }
            String last = l.get(l.size() - 1).text;
            if (last.endsWith("]")) {
                counts.merge(CanonicalType.parse(last).baseType, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * For a struct member that is a fixed array of scalars narrower than 8 bytes (say `u32[3]`) whose value in this literal was written
     * element by element (each element pushed as its own whole 8-byte word: n words), returns the descriptor token "a<elemBytes>x<n>";
     * null for anything else. A member whose value was pushed as one block (an existing array variable) takes ceil(size/8) words and
     * keeps the plain "m<size>" token. The two shapes are told apart by whether any array-typed value line of that array type occurs in the
     * literal; if one does, every member of that array type keeps "m". This is only the fallback used when the literal's values cannot be split (see
     * layoutTokens); a mix of the two shapes among same-typed members is rejected there.
     */
    private static String elementwiseArrayToken(String memberType, List<StructTable.LayoutEntry> layout, Map<String, Integer> arrayValueLines,
            SizeCalculator sizes, StructTable structTable) {
        CanonicalType ct = CanonicalType.parse(memberType);
        java.util.regex.Matcher mt = java.util.regex.Pattern.compile("^([A-Za-z0-9_]+)\\[([0-9]+)\\]$").matcher(ct.baseType);
        if (ct.storage != null || !mt.matches() || structTable.layoutOf(mt.group(1)) != null) {
            return null;
        }
        long elem = sizes.sizeOf("mut_" + mt.group(1));
        long n = Long.parseLong(mt.group(2));
        if (elem >= 8 || elem <= 0 || n <= 0) {
            return null;
        }
        int blockValues = arrayValueLines.getOrDefault(ct.baseType, 0);
        int sameType = 0;
        for (StructTable.LayoutEntry e : layout) {
            if (e.member != null && CanonicalType.parse(e.member.canonicalType).baseType.equals(ct.baseType)) {
                sameType++;
            }
        }
        if (blockValues > 0 && sameType > 1) {
            throw new RuntimeException("cannot tell how the array members of type " + memberType + " in a struct literal are written (some as element lists, some taken"
                    + " from a variable) -- write them all the same way");
        }
        return blockValues == 0 ? "a" + elem + "x" + n : null;
    }

    /**
     * This function's own resolved calling convention, read the
     * identical way `TypeChecker.resolveCallConvention` already
     * resolves it on the compiler side: an explicit
     * "FUNC_DECORATE @call_convention(name)" line if the source
     * function carried that decorator (echoed verbatim into the
     * bytecode -- see `BytecodeEmitter.emitDecorators`), otherwise
     * `config`'s own declared default. Confirmed directly this pass
     * should read the *same* `compiler.config` the compiler side reads,
     * rather than have the compiler pre-resolve and bake in a register-
     * count/shadow-stack decision of its own -- see this pass's own
     * "Address lowering" section in CLAUDE.md.
     */
    private CompilerConfig.CallingConvention resolveConvention(List<List<BytecodeToken>> lines, int start, int end,
            CompilerConfig config) {
        String name = null;
        for (int k = start; k <= end && name == null; k++) {
            List<BytecodeToken> fl = lines.get(k);
            if (fl.isEmpty() || !fl.get(0).text.equals("FUNC_DECORATE") || fl.size() < 2) {
                continue;
            }
            String decorator = fl.get(1).text;
            String prefix = "@call_convention(";
            if (decorator.startsWith(prefix) && decorator.endsWith(")")) {
                name = unquoteOrBare(decorator.substring(prefix.length(), decorator.length() - 1));
            }
        }
        if (name == null) {
            name = config.defaultConvention;
        }
        return config.callingConventions.get(name);
    }

    /**
     * True for a real float base type -- `f32` only, today, the sole
     * float type `TypeChecker.PRIMITIVE_SIZE` defines. Used only to
     * decide `RET` vs `RET_FLOAT` (see `tryRewrite`); every other
     * rewrite in this pass treats a value purely by its byte size,
     * never by whether it's a float, so this check is deliberately
     * scoped to just that one call site rather than folded into
     * `SizeCalculator` itself.
     */
    /** s8/s16/s32/s64: the signed integer base types (a signed compare/divide is a different machine operation). */
    private static boolean isSignedIntBaseType(String baseType) {
        return baseType.equals("s8") || baseType.equals("s16") || baseType.equals("s32") || baseType.equals("s64");
    }

    private static boolean isFloatBaseType(String baseType) {
        return "f32".equals(baseType) || "f64".equals(baseType);
    }

    /**
     * Classifies a `LOOKUP`/`LOOKUP_LHS`-style target's own canonical
     * type into which of the two real addressing shapes applies --
     * `"LOOKUP_ARRAY"` for a fixed array, a `string`, *or an unsafe
     * dynarray* (`unsafe_dynarray(...)`) -- all three a single, direct
     * `base + index * width` computation, no header of any kind in front
     * of the actual element data -- or `"LOOKUP_DYN"` for a *safe*
     * `dynarray(...)`, which needs a fixed `+8` added ahead of that same
     * multiply (its own buffer is preceded by a single 8-byte length
     * header, the same 8 bytes bare `LEN` already reads directly, at
     * offset 0, off that identical pushed value) -- or `null` if
     * `targetType`'s own base type is none of those (left for the caller
     * to leave the line untouched rather than guess). Storage (a
     * `ref`/`raw`/... prefix on the target itself, e.g. a pointer to an
     * array) doesn't change which of the two shapes applies, so this
     * classifies `CanonicalType.parse(targetType).baseType`, not the raw
     * canonical string. (This used to also classify a `slice(...)`
     * target as `"LOOKUP_SLICE"` -- the slice type has been removed from
     * the language entirely, so that shape no longer exists.)
     *
     * **Corrected directly, alongside a real, found-and-fixed design
     * bug:** an unsafe dynarray used to fall through this method
     * entirely (its own `"unsafe_dynarray("` prefix matches neither
     * `fixedArrayElementTypeOf` nor `dynArrayElementTypeOf`), so a
     * `LOOKUP`/`LOOKUP_LHS` into one silently returned `null` and was
     * left completely unrewritten -- confirmed directly against a real
     * compiled fixture (`unsafe_dynarray_construct_test.caspien`'s own
     * `arr[0]`/`arr[0] = 99`), whose bare, unresolved `LOOKUP`/
     * `LOOKUP_LHS` lines survived all the way to the end of the
     * optimizer pipeline untouched. Fixed by giving it its own
     * `unsafeDynArrayElementTypeOf` check, routed to `LOOKUP_ARRAY` --
     * not a third mnemonic, since (see `LOOKUP`'s own rewrite site doc
     * comment for the full "resize never actually needs a handle
     * indirection" reasoning) an unsafe dynarray's own addressing is
     * genuinely identical to a fixed array's/string's, just missing the
     * safe variant's `+8` length-header offset.
     */
    private static String lookupMnemonicFor(String targetType) {
        String baseType = CanonicalType.parse(targetType).baseType;
        if (baseType.equals("string") || CanonicalType.fixedArrayElementTypeOf(baseType) != null
                || CanonicalType.unsafeDynArrayElementTypeOf(baseType) != null) {
            return "LOOKUP_ARRAY";
        }
        if (CanonicalType.dynArrayElementTypeOf(baseType) != null) {
            return "LOOKUP_DYN";
        }
        return null;
    }

    private static String unquoteOrBare(String text) {
        if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    /**
     * Parses a "PUSH ARGn"-style operand's own trailing digits back into
     * `n` -- `-1` if `token` isn't exactly "ARG" followed by one or more
     * digits (no regex, hand-scanned, matching this project's own "no
     * regex, ever" rule). Used only to tell a *parameter-loading*
     * "PUSH ARGn type" line (emitted once per parameter word by the
     * compiler's own `emitParamLoads`, uniformly, with no register-vs-
     * stack distinction baked in any more -- see that method's own doc
     * comment) apart from every other operand shape this pass already
     * handles (a name, a literal, "null", a global label, ...); a
     * *call-site* "POP ARGn" is a different mnemonic entirely and is
     * never passed to this helper.
     */
    private static int parseArgIndex(String token) {
        if (!token.startsWith("ARG") || token.length() <= 3) {
            return -1;
        }
        for (int k = 3; k < token.length(); k++) {
            char c = token.charAt(k);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        try {
            return Integer.parseInt(token.substring(3));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The float-bank counterpart of `parseArgIndex` -- "FARG" followed by one or more digits, `-1` otherwise. Never collides with `parseArgIndex` itself: "FARG3" doesn't start with "ARG" (it starts with "F"), so a token is recognized by at most one of the two. */
    private static int parseFargIndex(String token) {
        if (!token.startsWith("FARG") || token.length() <= 4) {
            return -1;
        }
        for (int k = 4; k < token.length(); k++) {
            char c = token.charAt(k);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        try {
            return Integer.parseInt(token.substring(4));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** One resolved "PUSH ARGn"/"PUSH FARGn" parameter word's own real fate -- either a real register-table index (`isRegister` true, `registerIndex` valid, `stackOffset` unused) or a real, positive base-pointer-relative stack offset (`isRegister` false) -- decided once, for the whole function, by `resolveArgWordSlots`. */
    private static final class ArgWordSlot {
        final boolean isRegister;
        final int registerIndex;
        final long stackOffset;

        private ArgWordSlot(boolean isRegister, int registerIndex, long stackOffset) {
            this.isRegister = isRegister;
            this.registerIndex = registerIndex;
            this.stackOffset = stackOffset;
        }

        static ArgWordSlot register(int index) {
            return new ArgWordSlot(true, index, 0);
        }

        static ArgWordSlot stack(long offset) {
            return new ArgWordSlot(false, -1, offset);
        }
    }

    /**
     * Resolves every "PUSH ARGn"/"PUSH FARGn" parameter-loading word in
     * this one function, in original declared order, into its real
     * register-table index or real stack offset -- computed once, up
     * front, keyed by the word's own original token text (e.g. "ARG2",
     * "FARG1"), for `tryRewrite`'s own PUSH handling to just look up.
     *
     * This can no longer be decided correctly one line at a time, now
     * that an int word and a float word can be interleaved in the same
     * parameter list (see `CompilerConfig.CallingConvention.
     * sharedArgumentPosition`'s own doc comment for the full ABI
     * background): which register (if any) a given word gets depends on
     * how many *earlier* words of its own bank (or, for a
     * shared-position convention, of *any* bank) already came before it
     * -- information only visible by walking the whole list together,
     * not from one line's own text.
     *
     * The stack-offset side has an analogous cross-word dependency, of a
     * different shape: real argument-passing ABIs push every word that
     * overflows *either* bank's registers onto the stack in one shared,
     * left-to-right sequence, regardless of which bank it came from -- a
     * float overflow and an int overflow interleave into the same
     * stack, one slot each, in declaration order. `stackWordsSoFar`
     * below is exactly that one shared ordinal, incremented for an
     * overflowing word of *either* bank, so this naturally falls out
     * correct for a mixed-type overflow without needing any special
     * case for it.
     *
     * `ArgToAllocLoweringPass`'s own `wordIndex` (the raw digit already
     * sitting on the token, e.g. "ARG2"'s "2") is a single, uniformly-
     * incrementing counter across the *whole* parameter list regardless
     * of bank (see that pass's own doc comment) -- exactly what a
     * shared-argument-position convention's own register index already
     * needs verbatim, and exactly what this method's own per-bank
     * `intIdx`/`floatIdx` counters recompute independently for every
     * other convention.
     */
    private Map<String, ArgWordSlot> resolveArgWordSlots(List<List<BytecodeToken>> lines, int start, int end,
            CompilerConfig.CallingConvention convention) {
        Map<String, ArgWordSlot> slots = new HashMap<>();
        int intCount = convention.argumentRegisters.size();
        int floatCount = convention.argumentRegistersFloat.size();
        boolean shared = convention.sharedArgumentPosition;
        int sharedIdx = 0;
        int intIdx = 0;
        int floatIdx = 0;
        int stackWordsSoFar = 0;
        for (int k = start; k <= end; k++) {
            List<BytecodeToken> fl = lines.get(k);
            if (fl.size() != 3 || !fl.get(0).text.equals("PUSH")) {
                continue;
            }
            String token = fl.get(1).text;
            boolean isFloatWord = parseFargIndex(token) >= 0;
            boolean isIntWord = !isFloatWord && parseArgIndex(token) >= 0;
            if (!isFloatWord && !isIntWord) {
                continue;
            }
            int idx;
            if (shared) {
                idx = sharedIdx;
                sharedIdx++;
            } else if (isFloatWord) {
                idx = floatIdx;
                floatIdx++;
            } else {
                idx = intIdx;
                intIdx++;
            }
            int cutoff = isFloatWord ? floatCount : intCount;
            if (idx < cutoff) {
                slots.put(token, ArgWordSlot.register(idx));
            } else {
                long offset = 16L + convention.shadowStack + (long) stackWordsSoFar * 8;
                slots.put(token, ArgWordSlot.stack(offset));
                stackWordsSoFar++;
            }
        }
        return slots;
    }

    /**
     * "ALLOC name type" -> "ALLOC size" (unconditional -- every ALLOC in
     * the function was already read by `run` to build `offsets`, so its
     * own size is always known here); "ADDR name type" -> "ADDR size
     * $offset" (only when `name` resolves -- see `resolveAddress`, and
     * this pass's own "Known gap"); "PUSH x type" -> "PUSH size
     * $offset" when `x` resolves to a real stack name, or "PUSH size x"
     * unchanged otherwise (a literal, "null", a global label, ...);
     * "ASSIGN type type type" -> "ASSIGN size size size" (unconditional
     * -- every operand here is already a bare type, never a name, so
     * there's nothing to resolve, just each one's own size in its own
     * place) -- see this pass's own header for the full, confirmed
     * format. Null (leave the original line alone) for every other
     * mnemonic, or an `ADDR` whose name doesn't resolve.
     *
     * One more shape, confirmed directly this belongs here rather than
     * in the compiler: a "PUSH ARGn type" line, emitted uniformly by
     * `emitParamLoads` for every parameter word with no register-vs-
     * stack distinction of its own any more -- this pass is the one
     * place that decides, per `n` against `convention`'s own resolved
     * register count, whether that word is register-transferred (left
     * completely untouched, deferred to codegen exactly as before --
     * codegen still needs to know which real register `ARGn` names) or
     * stack-passed (rewritten to a real, positive "$N" -- the identical
     * "$offset" shape an ordinary local already gets, just positive
     * since an incoming stack argument sits on the other side of the
     * base pointer): `N = 16 + convention.shadowStack + (n -
     * convention.argumentRegisters.size()) * 8` -- 16 for the standard
     * x86-64 saved-base-pointer-plus-return-address pair, plus this
     * convention's own shadow-stack reservation, plus 8 bytes per
     * stack-passed word already ahead of this one.
     */
    /**
     * "ALLOC_STATIC name type" / "GLOBAL name type" (a struct/array/range
     * static's own no-value container line) -> "... name size", and
     * "ALLOC_STATIC name type value" / "GLOBAL name type value" (a scalar
     * static, or one composite's own per-field/per-element leaf line --
     * "p.x mut_u64 12", "p.___type imut_u64 0", "arr.0 mut_u64 1",
     * "r.start imut_u64 0", ...) -> "... name size value" -- the identical
     * "erase the type down to a bare byte count" treatment `ALLOC`/`ADDR`/
     * `PUSH` already get, applied here for the first time. `name` itself
     * is deliberately never touched -- "ADDR's own unresolved-name
     * treatment" (this pass's own header) already established that a
     * static/global's own name is a real, addressable symbol in its own
     * right, not a stack formula, and every per-field/per-element name
     * here (`p.x`, `p.___type`, `arr.0`, ...) is exactly that same kind of
     * symbol too -- "all its members will be valid labels," confirmed
     * directly. `sizes.sizeOf` already handles every shape a static's own
     * type can be here (a plain scalar, a struct via `structSizeOf`, a
     * fixed array via its own element-type-times-length, a range's fixed
     * 16-byte shape) without any change, since it's the identical helper
     * `ALLOC`'s own rewrite already calls.
     *
     * A standalone method, not folded into `tryRewrite` below, because a
     * top-level `GLOBAL` genuinely needs it from a different call site:
     * `run`'s own top-level loop only ever hands a line to `tryRewrite`
     * once it's inside a `FUNC_START`/`FUNC_END` block (an `ALLOC_STATIC`
     * always is), but a top-level `GLOBAL` sits *outside* every function
     * entirely and would otherwise be passed straight through completely
     * unrewritten -- confirmed directly, a real, found-and-fixed miss
     * from the first version of this fix: recompiling a genuine top-level
     * "let static counter = ..." global still showed the untouched
     * "GLOBAL counter mut_u64 0" after `ALLOC_STATIC` alone had already
     * been switched over. `run` now calls this helper directly for any
     * top-level line too, not just `tryRewrite` for in-function ones.
     *
     * The optional trailing value operand (a scalar static/global's own
     * literal initializer, or one composite's own per-field/per-element
     * leaf value) goes through `resolveLiteralOrEnumValue` too, exactly
     * like `PUSH`'s own literal operand does below -- a `let static flag
     * = true` or `let static state = MyEnum.OPEN` needs the identical
     * "true"/"false" -> "1"/"0" and "EnumName.Variant" -> its own
     * concrete value treatment a runtime PUSH of the same literal
     * already gets, and this is the one other place in the whole
     * bytecode format such a literal can appear.
     */
    private static List<BytecodeToken> rewriteStaticOrGlobalDecl(List<BytecodeToken> line, SizeCalculator sizes,
            EnumTable enumTable) {
        if (line.isEmpty()) {
            return null;
        }
        String mnemonic = line.get(0).text;
        if (!(mnemonic.equals("ALLOC_STATIC") || mnemonic.equals("GLOBAL"))
                || (line.size() != 3 && line.size() != 4)) {
            return null;
        }
        String staticName = line.get(1).text;
        long size = sizes.sizeOf(line.get(2).text);
        String rewritten = mnemonic + " " + staticName + " " + size
                + (line.size() == 4 ? " " + resolveLiteralOrEnumValue(line.get(3).text, enumTable) : "");
        return PARSER.parse(Collections.singletonList(rewritten), "<address-lowered>").get(0);
    }

    /**
     * The two, and only two, real "ADDR_OF is a no-op retag, drop it"
     * shapes -- see the call site's own doc comment for the real bug a
     * broader "any storage at all" version of this check caused. `REF`
     * re-aliasing an existing `owns` value never computes a new address
     * regardless of what that value's own storage is (only `owns` is
     * possible here in practice -- `ref` only ever targets an `owns`
     * value per the language's own rules -- but this checks the actual
     * storage text rather than assuming, the same "confirm, don't
     * assume" standard every other rewrite here holds itself to).
     * `RAW` on an already-hoisted string literal is the other -- its
     * storage is always exactly `static` (`TypeInfo.canonical()`'s own
     * "static_some_imut_string" shape for a string literal), never any
     * other storage keyword, so this doesn't also, say, wrongly match
     * "raw" on some other already-`static` local.
     */
    private static boolean isAlreadyCorrectPointerRetag(String opText, String precedingStorage) {
        if ("REF".equals(opText)) {
            return "owns".equals(precedingStorage);
        }
        if ("RAW".equals(opText)) {
            return "static".equals(precedingStorage);
        }
        return false;
    }

    private List<BytecodeToken> tryRewrite(List<BytecodeToken> line, Map<String, Long> offsets,
            Map<String, String> localTypes, SizeCalculator sizes, StructTable structTable, EnumTable enumTable,
            CompilerConfig.CallingConvention convention, List<BytecodeToken> previousLine,
            Map<String, ArgWordSlot> argWordSlots) {
        if (line.isEmpty()) {
            return null;
        }
        String mnemonic = line.get(0).text;

        // "ATOMIC_ASSIGN leftType rightType returnType" gets the identical
        // "erase every type operand to its own byte size" treatment plain
        // "ASSIGN" already gets -- confirmed directly, the mnemonic itself
        // (not any operand) is what a not-yet-built codegen stage needs to
        // tell an ordinary store from one that must not be reordered
        // across another thread's own access to the same location, and
        // that distinction survives here for free simply by keeping
        // `mnemonic` (not a hardcoded "ASSIGN" literal) in the rebuilt
        // line below.
        if ((mnemonic.equals("ASSIGN") || mnemonic.equals("ATOMIC_ASSIGN")) && line.size() == 4) {
            StringBuilder sb = new StringBuilder(mnemonic);
            for (int k = 1; k < 4; k++) {
                sb.append(' ').append(sizes.sizeOf(line.get(k).text));
            }
            return PARSER.parse(Collections.singletonList(sb.toString()), "<address-lowered>").get(0);
        }

        // "RET type" -> "RET size" (a non-float return) or "RET_FLOAT
        // size" (a float return) -- confirmed directly: "RET can be
        // split into RET and RET_FLOAT with the value being the number
        // of bytes." `RET_FLOAT` is a genuine new mnemonic, following the
        // same, already-established "a different runtime meaning gets
        // its own mnemonic" precedent the compiler side's own
        // `CAST` split already set (see this pass's own
        // header and CLAUDE.md's "Address lowering" section): a
        // not-yet-built codegen stage needs to know whether a returned
        // value comes back in an integer or a floating-point register,
        // and a bare byte count alone can't carry that distinction the
        // way it already can for every other rewrite in this pass.
        // `isFloatBaseType` recognizes only `f32` today -- the only real
        // float type `TypeChecker` defines; a second one (`f64`, if this
        // language ever gains it) would just extend that same check, not
        // need a `RET_FLOAT_64` of its own.
        if (mnemonic.equals("RET") && line.size() == 2) {
            String retType = line.get(1).text;
            long size = sizes.sizeOf(retType);
            String outMnemonic = isFloatBaseType(CanonicalType.parse(retType).baseType) ? "RET_FLOAT" : "RET";
            return PARSER.parse(Collections.singletonList(outMnemonic + " " + size), "<address-lowered>").get(0);
        }

        // "PUSH_RET type" -> "PUSH_RET_INT size" / "PUSH_RET_FLOAT size"
        // -- confirmed directly: "everything that isn't a float is an
        // int, and it also needs the size of the type." Unlike `RET`
        // just above (which keeps its own bare non-float name, only
        // `RET_FLOAT` being new), this one takes both suffixes
        // unconditionally -- the caller-side mirror of `RET`/`RET_FLOAT`
        // (the callee's own returned value comes back on the stack, this
        // is the caller picking it back up right after `CC_END` -- see
        // `BytecodeEmitter.emitCallSequence`), needing the identical
        // integer-vs-floating-point-register distinction a bare byte
        // count can't carry, using the same `isFloatBaseType` -- only
        // `f32` today, so "everything that isn't a float is an int" holds
        // exactly. Never emitted at all for a `void`-returning call
        // (`emitCallSequence`'s own "funcs that return void don't need
        // this" skip, untouched by this rewrite -- there is simply no
        // line for a void call to reach this pass in the first place).
        if (mnemonic.equals("PUSH_RET") && line.size() == 2) {
            String retType = line.get(1).text;
            long size = sizes.sizeOf(retType);
            String suffix = isFloatBaseType(CanonicalType.parse(retType).baseType) ? "_FLOAT" : "_INT";
            return PARSER.parse(Collections.singletonList("PUSH_RET" + suffix + " " + size), "<address-lowered>")
                    .get(0);
        }

        // "NEW TypeName" -> "NEW size" -- the single-operand, struct-
        // allocating shape only (BytecodeEmitter.emitNewExpr's own "NEW "
        // + resolvedType), confirmed directly: the codegen stage
        // consuming this just needs a byte count to allocate/copy, not
        // the type name itself, the identical "erase the name, keep the
        // size" treatment ALLOC/ADDR/PUSH/ASSIGN already get above.
        // `dyn([...])`'s own heap-buffer allocation is a distinct
        // mnemonic entirely now (`NEW_DYN`/`NEW_UDYN`, just below), not a
        // three-operand use of this same "NEW" mnemonic any more.
        if (mnemonic.equals("NEW") && line.size() == 2) {
            long size = sizes.sizeOf(line.get(1).text);
            // The struct's own physical layout, appended as a descriptor after the
            // size: one token per entry in declared order, "m<bytes>" for a real
            // member and "p<bytes>" for an alignment-padding gap. Codegen normally
            // ignores it (its packed fast path never needs it), but when it cannot
            // prove that the construction's field pushes form one clean run -- a
            // field value that is an expression, a call, a moved `owns` variable, a
            // nested `new`/`dyn(...)` -- it uses this to repack the blindly-pushed
            // field words into the struct's real layout instead of copying them
            // reversed.
            StringBuilder lowered = new StringBuilder("NEW ").append(size);
            CanonicalType newType = CanonicalType.parse(line.get(1).text);
            List<StructTable.LayoutEntry> newLayout = newType.storage == null ? structTable.layoutOf(newType.baseType) : null;
            if (newLayout != null) {
                for (StructTable.LayoutEntry entry : newLayout) {
                    lowered.append(' ').append(entry.member != null
                            ? "m" + sizes.sizeOf(entry.member.canonicalType)
                            : "p" + entry.paddingBytes);
                }
            }
            return PARSER.parse(Collections.singletonList(lowered.toString()), "<address-lowered>").get(0);
        }

        // "NEW_UDYN typeText count" -> "NEW_UDYN size" -- `dyn([...])`'s
        // own unsafe-dynarray heap allocation (BytecodeEmitter's "dyn"
        // case, split off from a shared "NEW" mnemonic into its own for
        // the identical reason `LOOKUP` was split into
        // `LOOKUP_ARRAY`/`LOOKUP_DYN` in `lookupMnemonicFor` below: a
        // safe and an unsafe dynarray need genuinely different allocation
        // shapes, so the mnemonic itself should say which, rather than a
        // backend having to parse a type string to find out). An unsafe
        // dynarray has no length header (this project's own corrected
        // addressing model -- see `lookupMnemonicFor`'s own doc comment
        // for the full `resize()`-contradiction reasoning), so the total
        // allocation is exactly `count * elementSize`, nothing more --
        // `size` alone is a complete contract, and `count` itself is
        // dropped since nothing downstream of a plain byte-count
        // allocation for a header-less buffer ever needs it again.
        if (mnemonic.equals("NEW_UDYN") && line.size() == 3) {
            String baseType = CanonicalType.parse(line.get(1).text).baseType;
            String elementBaseType = CanonicalType.unsafeDynArrayElementTypeOf(baseType);
            long elementSize = sizes.sizeOf(elementBaseType);
            long count = Long.parseLong(line.get(2).text);
            long size = elementSize * count;
            return PARSER.parse(Collections.singletonList("NEW_UDYN " + size), "<address-lowered>").get(0);
        }

        // "NEW_DYN typeText count" -> "NEW_DYN size count" -- the safe-
        // dynarray sibling just above. `size` here is deliberately just
        // the *elements'* own total (`count * elementSize`), not
        // `8 + count * elementSize` -- the 8-byte length header's own
        // extra space is left for whatever eventually does the real
        // allocating to add on its own, rather than silently folded into
        // this operand (an open, explicitly-flagged question, not a
        // quietly-made assumption: confirmed directly with the person
        // this project is built with, "no dont fold"). `count` is kept
        // on the line, unlike `NEW_UDYN`, because there IS a real,
        // outstanding piece of runtime work here that needs it again --
        // writing the element count into that same length header once
        // the buffer exists -- and per this project's own standing rule
        // ("im against getting it from the previous line as much as
        // possible", from the `DEREF` fix), that value belongs on this
        // instruction's own line rather than re-derived by dividing
        // `size` back down by `elementSize` wherever it's needed next.
        // Whether writing that header is itself something `NEW_DYN`
        // should be responsible for, or a separate later instruction,
        // is real, separate follow-up work -- nothing downstream of this
        // pass does that write yet either way.
        if (mnemonic.equals("NEW_DYN") && line.size() == 3) {
            String baseType = CanonicalType.parse(line.get(1).text).baseType;
            String elementBaseType = CanonicalType.dynArrayElementTypeOf(baseType);
            long elementSize = sizes.sizeOf(elementBaseType);
            long count = Long.parseLong(line.get(2).text);
            long size = elementSize * count;
            return PARSER.parse(Collections.singletonList("NEW_DYN " + size + " " + count), "<address-lowered>")
                    .get(0);
        }

        // "RESIZE dynArrType countType fillType" -> "RESIZE size" -- the
        // safe flavor of `resize(...)`. `checkResizeBuiltin` confirms two
        // of these three operands are always dead: `countType` is always
        // a plain `u64` ("'resize''s new count must be a plain 'u64'"),
        // and `dynArrType` -- like every other `owns`-storage value -- is
        // always 8 bytes as a stack value regardless of what it points
        // to, so neither ever varies call to call. `fillType` is the one
        // real piece of information here, and it's guaranteed to equal
        // the dynarray's own element type exactly
        // ("'resize''s fill value must match the dynarray's own element
        // type"), so its size *is* the element size a real realloc-style
        // implementation needs to compute the new total byte count from
        // the runtime `count` value already sitting on the stack right
        // above this line. Confirmed directly with the person this
        // project is built with: for a `u64` fill value this becomes
        // "RESIZE 8".
        if (mnemonic.equals("RESIZE") && line.size() == 4) {
            String fillBaseType = CanonicalType.parse(line.get(3).text).baseType;
            long size = sizes.sizeOf(fillBaseType);
            return PARSER.parse(Collections.singletonList("RESIZE " + size), "<address-lowered>").get(0);
        }

        // "URESIZE dynArrType countType" -> "URESIZE size" -- the unsafe
        // sibling just above, and the reason this couldn't just be "drop
        // every operand down to whatever's left": there's no fill value
        // here at all, so `dynArrType` is the *only* remaining source of
        // the element size a real realloc-style implementation still
        // needs (to compute the new total byte count from the runtime
        // `count` on the stack), and it can't be dropped the way it was
        // for `RESIZE` above.
        //
        // `CanonicalType.unsafeDynArrayElementTypeOf` is tried first,
        // then `dynArrayElementTypeOf` as a fallback -- not because a
        // real, source-level `resize(...)` call site is ever ambiguous
        // (`checkResizeBuiltin` only ever allows this bare, no-fill-value
        // shape when the target genuinely is an unsafe dynarray type, so
        // `dynArrType` always says "unsafe_dynarray(...)" there), but
        // because `CloneGenerationPass.buildCloneLoop` deliberately
        // synthesizes this exact 2-operand shape against a nominally
        // *safe* `owns_mut_dynarray(...)` destination too (growing a
        // fresh, empty clone to its source's own length with no fill
        // value at all, since the element type is arbitrary and there's
        // no way to synthesize a generic one there -- safe only because
        // every slot is overwritten by that loop before anything ever
        // reads it, see that pass's own doc comment on `buildCloneLoop`).
        // The fallback lets that one synthesized call site still resolve
        // its element size correctly without either pass needing to
        // pretend that destination is something it isn't.
        if (mnemonic.equals("URESIZE") && line.size() == 3) {
            String baseType = CanonicalType.parse(line.get(1).text).baseType;
            String elementBaseType = CanonicalType.unsafeDynArrayElementTypeOf(baseType);
            if (elementBaseType == null) {
                elementBaseType = CanonicalType.dynArrayElementTypeOf(baseType);
            }
            long size = sizes.sizeOf(elementBaseType);
            return PARSER.parse(Collections.singletonList("URESIZE " + size), "<address-lowered>").get(0);
        }

        // "STACK_LOCK structName" -> "STACK_LOCK size" -- the only OTC
        // ("One True Construction") violation this project allows
        // (BytecodeEmitter.emitInstantiate's own "isLockViolationConstruct"
        // branch): every member except the lock/discriminant field was
        // omitted from the literal, so only the classId (if the struct
        // carries one) and the lock field's own value ever get pushed
        // before this line -- STACK_LOCK itself is what's expected to
        // manipulate the stack pointer directly, to reserve/zero the
        // *rest* of the struct's slots (the genuinely omitted members),
        // "confirmed directly." A not-yet-built codegen stage doesn't
        // need the struct's name for that -- exactly like every other
        // rewrite in this pass, it only needs a byte count: the struct's
        // *total* size, minus whatever's already been pushed ahead of
        // it. That's always exactly two things, never more, never
        // conditionally shaped any other way: the hidden "___type"
        // field, 8 bytes, subtracted only when the struct actually has
        // one (`checkOtcLockViolation` never runs for an `@untyped`
        // struct's own classId-less shape, so this can't be assumed
        // unconditionally); and the lock field itself, always 8 bytes,
        // unconditionally -- `checkOtcLockViolation` requires the lock
        // field's value to be a direct, bare `EnumName.Variant`
        // reference, and every enum in this language is representable
        // in a single `u64` word (no enum carries payload data), so
        // `sizes.sizeOf` already resolves any enum's canonical type to
        // 8 via its own generic "unknown base type" fallback -- there's
        // no need to look up *which* member is the lock field (its name
        // isn't even recoverable here: `StructTable` is built purely
        // from `STRUCT_MEMBER` lines, and the compiler's own
        // `emitDecorators` never serializes a lock decorator's
        // `lockFieldName` into the bytecode's `STRUCT_DECORATE @lock`
        // line at all -- only `d.args` round-trips there, and lock's own
        // per-field/per-variant detail is never stored as a `d.args`
        // string). The formula holds regardless of which field carries
        // the lock or how many other members exist, because it's never
        // computed by walking to that field at all -- only by knowing
        // what's always already on the stack by the time this line runs.
        // `sizes.sizeOf(structName)` is now the struct's own real,
        // compiler-padded total (structSizeOf sums the same
        // `STRUCT_PADDING` gaps BytecodeEmitter.emitStruct bakes in) --
        // exactly right for this formula's own "move the stack pointer
        // as if I'd allocated the rest of the struct" intent: the
        // omitted members' own reserved space is correctly wider
        // whenever alignment padding actually falls among or after them,
        // with no separate change needed here for that.
        if (mnemonic.equals("STACK_LOCK") && line.size() == 2) {
            String operand = line.get(1).text;
            // `BytecodeEmitter.emitInstantiate` now also emits
            // "STACK_LOCK <n>" directly, as a plain byte count, for an
            // ordinary padding gap inside an ordinary (non-OTC)
            // construction site -- already fully lowered, nothing here
            // to resolve. Only the OTC case's operand is still a struct
            // *name* needing the lookup below; distinguish the two by
            // whether the operand already parses as a plain
            // non-negative integer, rather than by anything positional.
            if (operand.chars().allMatch(Character::isDigit)) {
                return line;
            }
            String structName = operand;
            long size = sizes.sizeOf(structName);
            List<StructTable.Member> members = structTable.membersOf(structName);
            // classId, when present, is always the *first* STRUCT_MEMBER,
            // not the last -- checked at index 0. (Found stale while
            // wiring up struct padding: this checked
            // `members.get(members.size() - 1)` until now, the same
            // leftover-from-before-the-"___type must be first"-fix bug
            // as CloneGenerationPass.emitMemberPushSequence just above,
            // never caught before because nothing exercised STACK_LOCK
            // against a classId-bearing struct with more than one member
            // closely enough to notice the wrong boolean.)
            boolean hasClassId = members != null && !members.isEmpty()
                    && members.get(0).name.equals("___type");
            if (hasClassId) {
                size -= 8;
            }
            size -= 8; // the lock field's own value, always one enum word
            return PARSER.parse(Collections.singletonList("STACK_LOCK " + size), "<address-lowered>").get(0);
        }

        // "LOOKUP targetType indexType returnType" -> "LOOKUP_ARRAY size"
        // / "LOOKUP_DYN size" -- confirmed directly: "the only info the
        // LOOKUP should need is the width of its return type... the
        // instruction itself must be split." Two mnemonics: a fixed
        // array, a string, *or an unsafe dynarray* all resolve the same
        // way, a single, direct "base + index * elementWidth" against
        // whatever's already been pushed for the container (LOOKUP_ARRAY
        // covers all three -- an unsafe dynarray has no header of any
        // kind in front of its own elements, per `checkLenBuiltin`'s own
        // "no hidden runtime length at all"); a *safe* dynarray needs a
        // fixed "+8" added ahead of that same multiply (LOOKUP_DYN) --
        // its own buffer is preceded by a single 8-byte length header
        // (the same 8 bytes bare `LEN` already reads directly, at offset
        // 0, off that identical pushed value) -- two genuinely different
        // runtime addressing shapes, the same "different runtime meaning
        // gets its own mnemonic" precedent the compiler side's own
        // `CAST` split and `RET`/`RET_FLOAT` already follow.
        //
        // **Corrected directly, a real, found-and-fixed design bug:**
        // `LOOKUP_DYN` used to be documented (and this method used to
        // treat both dynarray flavors identically) as needing "one
        // pointer indirection through its own 8-byte heap-buffer handle"
        // -- the pushed value being a pointer *to* a separate handle
        // slot, not the buffer's own address. Confirmed wrong directly
        // against this project's own `resize()`: every real call site
        // that resizes a dynarray explicitly re-`POP`s `RESIZE`'s
        // returned address back into the *same* variable right after the
        // call (`CloneGenerationPass.buildCloneLoop`'s own synthesized
        // resize does this too, with an explicit "resize may relocate --
        // never assume the address survives" comment) -- if a genuine
        // handle-indirection layer existed, that re-assignment would be
        // pointless, since the handle slot would just update in place
        // and every existing reference would see the new address with
        // nothing to reassign. A dynarray value -- safe or unsafe -- is
        // therefore a single, plain 8-byte pointer exactly like every
        // other pointer this bytecode has ("either way its an 8 byte
        // pointer itself like all other pointers," confirmed directly),
        // never a pointer-to-a-handle; the two flavors differ only in
        // whether a fixed 8-byte length header sits in front of the
        // actual element data, which is exactly what the corrected
        // `LOOKUP_ARRAY`/`LOOKUP_DYN` split above now encodes. (This
        // used to also have a third, `LOOKUP_SLICE`, shape -- needing its
        // own origin resolved, itself possibly a pointer, and its own
        // "start" bound added to the index before the multiply -- but
        // the slice type has been removed from the language entirely, so
        // that shape no longer exists.) `indexType` (the middle operand)
        // is dropped entirely along with `targetType` -- once the
        // mnemonic itself says which addressing shape applies and the
        // one remaining operand says how many bytes the result is,
        // neither type carries anything a not-yet-built codegen stage
        // still needs. `lookupMnemonicFor` classifies `targetType`'s own
        // base type; returns null (line left untouched) for a shape it
        // doesn't recognize, rather than guessing -- shouldn't happen in
        // practice, since the compiler's own `checkLookup` only ever
        // permits a string, array, or (safe or unsafe) dynarray target
        // to begin with, but this pass doesn't assume that invariant
        // holds silently.
        if (mnemonic.equals("LOOKUP") && line.size() == 4) {
            String targetType = line.get(1).text;
            String returnType = line.get(3).text;
            String newMnemonic = lookupMnemonicFor(targetType);
            if (newMnemonic != null) {
                long size = sizes.sizeOf(returnType);
                // A fixed array of exactly 8 bytes held by value (a struct member, say) is pushed as one plain word, which Codegen cannot tell from a
                // pointer to the array (the smaller arrays are tagged when pushed): the extra token "t8" says it is the array's own bytes.
                CanonicalType tt = CanonicalType.parse(targetType);
                String total = "";
                if (newMnemonic.equals("LOOKUP_ARRAY") && tt.storage == null && CanonicalType.fixedArrayElementTypeOf(tt.baseType) != null
                        && sizes.sizeOf(targetType) == 8) {
                    total = " t8";
                }
                CanonicalType rt = CanonicalType.parse(returnType);
                if (newMnemonic.equals("LOOKUP_ARRAY") && rt.storage == null && CanonicalType.fixedArrayElementTypeOf(rt.baseType) != null
                        && sizes.sizeOf(returnType) < 8) {
                    total += " ra";   // the element is itself a small fixed array held by value: Codegen tags it for the next lookup
                }
                return PARSER.parse(Collections.singletonList(newMnemonic + " " + size + total), "<address-lowered>").get(0);
            }
        }

        // "LOOKUP_LHS targetType indexType returnType" -> "LOOKUP_ARRAY_LHS
        // size" / "LOOKUP_DYN_LHS size" -- the identical two-way split
        // `LOOKUP` itself just got, applied to the write-side sibling
        // `emitAssignTarget`'s own "LOOKUP" case emits for a target
        // reached through "[]" (BytecodeEmitter's "LOOKUP_LHS targetType
        // indexType returnType" shape -- textually identical to what
        // bare, unsplit `LOOKUP` used to look like). `lookupMnemonicFor`
        // is reused completely unmodified -- the addressing computation
        // a fixed array/string/dynarray target needs is the same shape
        // whichever side of an assignment it's on -- with `_LHS`
        // appended to whichever of the two names comes back. Deliberately
        // **not** collapsed into the same `LOOKUP_ARRAY`/`LOOKUP_DYN`
        // names the read side now uses: `LOOKUP` leaves a *value* on the
        // stack, `LOOKUP_LHS`
        // leaves an *address* for the very next `ASSIGN` to write into --
        // the identical value-vs-address distinction `DOT`/`DOT_LHS`
        // already keep as two separate mnemonics through their own
        // lowering (`DOT size` / `DOT_LHS size`), rather than merging,
        // for the same reason: a not-yet-built codegen stage needs to
        // know which one it's looking at from the mnemonic alone, not by
        // inspecting whatever instruction happens to follow it. `targetType`/
        // `indexType` are both dropped, the identical reasoning `LOOKUP`
        // itself already gets: once the mnemonic says which addressing
        // shape applies, neither type carries anything left to say.
        // Verified against `array_element_mutability_write_test.caspien`'s
        // own real "x[0] = 42": `LOOKUP_LHS mut_u64[6] indeterminate_u64
        // mut_u64` erases to `LOOKUP_ARRAY_LHS 8`.
        if (mnemonic.equals("LOOKUP_LHS") && line.size() == 4) {
            String targetType = line.get(1).text;
            String returnType = line.get(3).text;
            String newMnemonic = lookupMnemonicFor(targetType);
            if (newMnemonic != null) {
                long size = sizes.sizeOf(returnType);
                return PARSER.parse(Collections.singletonList(newMnemonic + "_LHS " + size), "<address-lowered>")
                        .get(0);
            }
        }

        // "GT/LT/GT_EQ/LT_EQ/EQ/NEQ leftType rightType returnType" ->
        // "GT_INT size" / "GT_FLOAT size" / ... -- confirmed directly:
        // each of the six "need to be split by _int and _float and take
        // as their only argument the size in bytes of the type of their
        // first argument" (EQ/NEQ added on request, right after the
        // first four). `leftType` (the comparison's own first operand)
        // decides both the size and, via `isFloatBaseType`, which suffix
        // -- `rightType`/`returnType` are dropped entirely, the identical
        // "the mnemonic itself now says what used to need a type operand
        // to say" treatment `RET`/`RET_FLOAT` and the `LOOKUP` split
        // already get: an integer and a float compare are genuinely
        // different machine instructions (an integer ALU compare vs. a
        // floating-point unit one), the same "different runtime meaning
        // gets its own mnemonic" precedent as `RET_FLOAT`. All six of
        // this project's own comparison mnemonics
        // (`BytecodeEmitter.COMPARISON_NAMES`) are covered by this one
        // check now -- none left over.
        if ((mnemonic.equals("GT") || mnemonic.equals("LT") || mnemonic.equals("GT_EQ") || mnemonic.equals("LT_EQ")
                || mnemonic.equals("EQ") || mnemonic.equals("NEQ")) && line.size() == 4) {
            String leftType = line.get(1).text;
            long size = sizes.sizeOf(leftType);
            String suffix = isFloatBaseType(CanonicalType.parse(leftType).baseType) ? "_FLOAT" : "_INT";
            String signedPrefix = (suffix.equals("_INT") && isSignedIntBaseType(CanonicalType.parse(leftType).baseType)
                    && !mnemonic.equals("EQ") && !mnemonic.equals("NEQ")) ? "S" : "";
            return PARSER.parse(Collections.singletonList(signedPrefix + mnemonic + suffix + " " + size),
                    "<address-lowered>").get(0);
        }

        // "AND leftType rightType returnType" / "OR leftType rightType
        // returnType" -> "AND size" / "OR size" -- unlike the comparison
        // mnemonics just above, a logical AND/OR is the same machine
        // operation regardless of operand width (a plain bitwise AND/OR
        // over however many bytes), so this doesn't get an `_INT`/`_FLOAT`
        // split -- there's no genuinely different runtime meaning to give
        // a second mnemonic to here, just an operand to drop down to a
        // size, the same "the mnemonic itself already says what a type
        // operand used to have to say" treatment RET/LOOKUP/the
        // comparisons all get. `leftType`/`rightType` are always identical
        // here (BytecodeEmitter's own `emitLogical` only ever calls this
        // with same-typed operands), so only the first is needed.
        if ((mnemonic.equals("AND") || mnemonic.equals("OR")) && line.size() == 4) {
            long size = sizes.sizeOf(line.get(1).text);
            return PARSER.parse(Collections.singletonList(mnemonic + " " + size), "<address-lowered>").get(0);
        }

        // "SHL/SHR/BITS_OR/BITS_AND/BITS_XOR leftType rightType returnType" -> mnemonic +
        // `size` -- the identical AND/OR treatment just above, confirmed
        // directly: `checkBitsBuiltin` (the `bits_left`/`bits_right`/
        // `bits_or` builtins) requires both operands to be the same
        // `INTEGER_TYPES` member -- never a float -- and returns that
        // same base type, so there's no genuinely different runtime
        // meaning here to earn a second mnemonic the way a compare does,
        // just a plain bitwise/shift machine operation over however many
        // bytes. `leftType` alone is read (guaranteed identical to
        // `rightType` by `checkBitsBuiltin`'s own `sameBaseType` check);
        // `rightType`/`returnType` are dropped.
        // (BITS_AND/BITS_XOR/BITS_NOT sit alongside BITS_OR: the same checkBitsBuiltin rules, the same lowering. A signed `SHR` becomes `SAR`.
        // The stack-form BITS_AND with ONE operand "BITS_AND 8" that StrengthReductionPass makes later is the same instruction at size 8.)
        if ((mnemonic.equals("SHL") || mnemonic.equals("SHR") || mnemonic.equals("BITS_OR")
                || mnemonic.equals("BITS_AND") || mnemonic.equals("BITS_XOR")) && line.size() == 4) {
            long size = sizes.sizeOf(line.get(1).text);
            // bits_right on a signed operand is an arithmetic shift (SAR); unsigned stays logical (SHR).
            String outMnemonic = (mnemonic.equals("SHR")
                    && isSignedIntBaseType(CanonicalType.parse(line.get(1).text).baseType)) ? "SAR" : mnemonic;
            return PARSER.parse(Collections.singletonList(outMnemonic + " " + size), "<address-lowered>").get(0);
        }

        // "BITS_NOT leftType returnType" -> "BITS_NOT size": the bitwise complement of the low `size` bytes (the result is re-truncated
        // to the width by the backend). Distinct from the logical NOT just below.
        if (mnemonic.equals("BITS_NOT") && line.size() == 3) {
            long size = sizes.sizeOf(line.get(1).text);
            return PARSER.parse(Collections.singletonList("BITS_NOT " + size), "<address-lowered>").get(0);
        }

        // "NOT leftType returnType" -> "NOT size" -- confirmed directly,
        // right after the comparisons/AND/OR treatment above: "if you
        // could update the lowering for NOT." `checkLogicalNot`
        // (TypeChecker) accepts only bool or integer operands
        // (`requireBoolOrInteger`) -- never a float -- so this gets the
        // plain AND/OR treatment (a single size, no `_INT`/`_FLOAT`
        // split), not the comparison/ADD-SUB-MUL-DIV-MOD/INC-DEC
        // treatment: there's no floating-point "NOT" to ever need a
        // different mnemonic from, just a bitwise/logical flip over
        // however many bytes. Single-operand shape (`BytecodeEmitter.
        // emitLogicalNot` emits "NOT leftType returnType", `returnType`
        // itself always "indeterminate_bool" and dropped here same as
        // every other rewrite drops its own returnType).
        if (mnemonic.equals("NOT") && line.size() == 3) {
            long size = sizes.sizeOf(line.get(1).text);
            return PARSER.parse(Collections.singletonList("NOT " + size), "<address-lowered>").get(0);
        }

        // "CLONE argType returnType" -> "CLONE size" -- confirmed
        // directly, right after a demo showed `CLONE` was emitted
        // completely bare (no type operand at all, unlike every other
        // mnemonic in this format): "the compiler should output the
        // arguments for CLONE the same style as other standard
        // operations, and then the optimizer should reduce it to CLONE
        // size during the lowering." Unlike every other rewrite here,
        // `size` is deliberately *not* `sizes.sizeOf(argType)` directly
        // -- `argType` is itself a pointer (`checkCloneBuiltin` requires
        // non-null storage), and `SizeCalculator.sizeOf` treats every
        // pointer as a single 8-byte word regardless of what it points
        // to (see its own doc comment) -- exactly wrong here, since
        // `CLONE`'s whole job is to allocate and bytewise-copy the
        // *pointee*, not the pointer itself. So the storage keyword is
        // stripped first (`CanonicalType.parse(argType).baseType`), the
        // same "reduce a pointer type down to what it actually points
        // to" step `PUSH_FIELDNAME`'s own struct-type extraction already
        // uses, and the pointee's own size is computed from that bare
        // base type instead. Only reached for the flat, "no owns content
        // inside" case in the first place -- the sibling
        // `CloneGenerationPass`, which runs earlier in this pipeline,
        // already rewrites every owns-bearing pointee into either
        // "CALL .../PUSH_RET type" or a real, generated JMP/CMP loop
        // (`buildCloneLoop`, its own "CLONE_LOOP ..." mnemonic never
        // actually emitted any more) before this pass ever sees it, and
        // neither of those shapes can ever be mistaken for this flat,
        // single-word-pointee-size shape here.
        if (mnemonic.equals("CLONE") && line.size() == 3) {
            String pointeeBaseType = CanonicalType.parse(line.get(1).text).baseType;
            long size = sizes.sizeOf(pointeeBaseType);
            return PARSER.parse(Collections.singletonList("CLONE " + size), "<address-lowered>").get(0);
        }

        // "DEREF pointeeType" -> "DEREF size" -- the fix for a real gap the
        // user identified directly: "a deref is essentially a copy," and a
        // byte-precise copy needs to know how many bytes to copy, which is
        // the *pointee's* width, never the *pointer's* own fixed 8-byte
        // width (every pointer, regardless of what it points to, is 8
        // bytes -- `PUSH ptr type`'s own erased size right above a `DEREF`
        // line is therefore always 8 and tells this pass nothing about
        // what's actually being dereferenced). `BytecodeEmitter`'s own
        // "deref" case now carries `op.resolvedType` -- the pointee's own
        // canonical type, already resolved by `checkDerefBuiltin` --
        // directly on the instruction's own line (`DEREF <pointeeType>`),
        // and `MembershipLoweringPass.derefInto`'s own independently
        // synthesized `DEREF` lines were updated to match. Deliberately
        // *not* inferred from the preceding `PUSH` line: the user was
        // explicit about this being a general principle, not just a
        // one-off preference -- "im against getting it from the previous
        // line as much as possible" -- so the type rides on `DEREF`'s own
        // line instead, exactly like `CLONE`'s own pointee-type operand
        // just above, which this rewrite deliberately mirrors.
        //
        // `size` is computed via plain `sizes.sizeOf(...)`, not (as this
        // rewrite originally did, and `CLONE`'s own rewrite just above
        // deliberately still does) an explicit "strip storage, then size
        // the bare base type" -- those are only the same thing when the
        // operand text carries no storage of its own, which used to be
        // true of every real caller (the `deref()` builtin's own pointee
        // is always storage-free; so is `derefInto`'s own synthesized
        // temp). `BytecodeEmitter.emitDot`'s own new storage-bearing-field
        // case (see its own doc comment) is the first caller to legitimately
        // hand DEREF a *storage-bearing* operand -- reading a pointer-typed
        // struct field's own stored bytes (its 8-byte pointer value
        // itself), not dereferencing through it to copy a further pointee.
        // `sizes.sizeOf` already does exactly the right thing for both
        // shapes in one step (its own doc comment: "a storage-bearing type
        // ... is always exactly one 8-byte word") -- no explicit stripping
        // needed, and, for every existing storage-free caller, identical
        // output to the old explicit-strip version (nothing to strip).
        if (mnemonic.equals("DEREF") && line.size() == 2) {
            long size = sizes.sizeOf(line.get(1).text);
            return PARSER.parse(Collections.singletonList("DEREF " + size), "<address-lowered>").get(0);
        }

        // "NEG leftType returnType" -> "NEG size" -- confirmed directly:
        // "in the lower order code, it needs the size of the left type
        // and the size of the return type if they are ever different, if
        // they are always the same then it just needs the one arg."
        // Checked, not assumed: `checkUnaryMinus`'s own result type is
        // `UNSIGNED_TO_SIGNED.getOrDefault(operand.baseType,
        // operand.baseType)` -- unary minus on an unsigned operand
        // produces the *signed* type of the identical bit width
        // (`u64`->`s64`, `u32`->`s32`, `u16`->`s16`, `u8`->`s8`; a
        // already-signed or float operand maps to itself), never a
        // genuinely wider or narrower one, so `leftType` and `returnType`
        // always agree on byte size even on the rare occasions their
        // text differs. `leftType` alone is therefore enough; `returnType`
        // is dropped, same as every other rewrite here that drops its own
        // returnType once one operand's size is confirmed to cover it.
        if (mnemonic.equals("NEG") && line.size() == 3) {
            long size = sizes.sizeOf(line.get(1).text);
            // A float operand gets "NEG_FLOAT size" (sign-bit flip): the plain
            // integer "NEG" would two's-complement-negate the IEEE-754 bit
            // pattern, which produces a wrong (but valid-looking) float.
            String negSuffix = isFloatBaseType(CanonicalType.parse(line.get(1).text).baseType) ? "_FLOAT" : "";
            return PARSER.parse(Collections.singletonList("NEG" + negSuffix + " " + size), "<address-lowered>")
                    .get(0);
        }

        // "SEXT leftType returnType" / "ZEXT leftType returnType" ->
        // mnemonic + `sourceSize` + `destSize` -- the one exception, in
        // this same "leftType returnType" family, where the two sizes are
        // confirmed to genuinely differ and *both* still have to survive.
        // `checkAs`'s own same-signedness-family widening cast requires
        // `targetWidth > leftWidth` strictly ("'as' only widens"), with
        // both widths read directly off `INTEGER_WIDTH` -- unlike `NEG`
        // just above (where `UNSIGNED_TO_SIGNED` guarantees the two
        // operand types always agree on byte size even when their text
        // differs), here the whole *point* of the instruction is that the
        // two widths differ, and by how much genuinely varies call to
        // call (`s8`->`s32` is a 1-to-4-byte jump; a different call site
        // could just as easily be `s16`->`s64`, 2-to-8). A not-yet-built
        // codegen stage needs both ends of that jump -- how many bytes to
        // read the source value as, and how many bytes to sign/zero-fill
        // the result up to -- so `returnType` is kept here, not dropped,
        // the one rewrite in this whole "erase leftType/returnType to a
        // size" family that can't collapse to a single number. Verified
        // against `cast_within_split_manual_check_test.caspien`'s own two
        // real cases: `SEXT mut_s8 mut_s32` (a 1-to-4-byte signed widen)
        // and `ZEXT mut_u8 mut_u32` (a 1-to-4-byte unsigned widen).
        if ((mnemonic.equals("SEXT") || mnemonic.equals("ZEXT") || mnemonic.equals("TRUNC") || mnemonic.equals("FCONV")) && line.size() == 3) {
            long sourceSize = sizes.sizeOf(line.get(1).text);
            long destSize = sizes.sizeOf(line.get(2).text);
            return PARSER.parse(Collections.singletonList(mnemonic + " " + sourceSize + " " + destSize),
                    "<address-lowered>").get(0);
        }

        // "ATOMIC_SWAP leftType rightType returnType" -> "ATOMIC_SWAP
        // size" -- confirmed directly, checked the same way `NEG` just
        // was ("can you check the same applies to atomic_swap"):
        // `checkSwapOperator` requires `typesCompatible(leftType,
        // rightType)` and constructs its own return type directly as
        // `new TypeInfo(null, "indeterminate", leftType.baseType)` -- all
        // three operands share one `baseType`, so one size always covers
        // all of them, same conclusion as `NEG`. Unlike `NEG`, though,
        // `leftType` itself is the *wrong* one of the three to read the
        // size from: `TypeInfo.canonical()` bakes `isAtomic` in as a
        // literal `"atomic_"` segment ahead of the base type (e.g.
        // "mut_atomic_char"), and `CanonicalType.parse` (this project, no
        // `"atomic"` case of its own anywhere) has no idea that token is
        // there -- it isn't one of the five real storage keywords, so
        // parsing stops one underscore too early and folds "atomic" into
        // the base type itself ("atomic_char" instead of "char"),
        // silently falling through `SizeCalculator`'s own generic 8-byte
        // fallback for a type it doesn't recognize. Confirmed directly
        // against a real fixture (`atomic_swap_global_scalar_cg_test.
        // caspien`, a `char`): `leftType` reads "mut_atomic_char" (would
        // wrongly size to 8), while `rightType`/`returnType` both read
        // the clean "indeterminate_char" (correctly sizes to 1) --
        // `checkSwapOperator`'s own right/return `TypeInfo`s are always
        // freshly constructed as plain, non-atomic values, so neither
        // ever carries that "atomic_" segment. `returnType` (the last
        // operand) is read for exactly this reason, not `leftType`.
        if (mnemonic.equals("ATOMIC_SWAP") && line.size() == 4) {
            long size = sizes.sizeOf(line.get(3).text);
            return PARSER.parse(Collections.singletonList("ATOMIC_SWAP " + size), "<address-lowered>").get(0);
        }

        // "ADD/SUB/MUL/DIV/MOD leftType rightType returnType" ->
        // "ADD_INT size" / "ADD_FLOAT size" / ... -- confirmed directly,
        // in two rounds: first just "the size of their first argument as
        // well" (the plain AND/OR treatment, no split), then corrected
        // moments later to add the `_INT`/`_FLOAT` split after all
        // ("can you _int/_float split add/sub/mul/div/mod, thank you for
        // catching that") -- the same reasoning as `INC`/`DEC` just below:
        // "whether they really need it or not is sort of architecture
        // independent, but its better done than not done." `leftType`
        // alone decides both the size and, via `isFloatBaseType`, the
        // suffix; `rightType`/`returnType` are dropped, same as every
        // other rewrite above. `leftType`/`rightType` are always identical
        // in practice for these five (both operands of a `+`, `-`, `*`,
        // `/`, `%` share one resolved type by the time this pass ever
        // sees them).
        if ((mnemonic.equals("ADD") || mnemonic.equals("SUB") || mnemonic.equals("MUL")
                || mnemonic.equals("DIV") || mnemonic.equals("MOD")) && line.size() == 4) {
            String leftType = line.get(1).text;
            long size = sizes.sizeOf(leftType);
            String suffix = isFloatBaseType(CanonicalType.parse(leftType).baseType) ? "_FLOAT" : "_INT";
            // Signed division/modulo are different machine operations (idiv, not div): own mnemonics.
            String signedPrefix = (suffix.equals("_INT") && isSignedIntBaseType(CanonicalType.parse(leftType).baseType)
                    && (mnemonic.equals("DIV") || mnemonic.equals("MOD"))) ? "S" : "";
            return PARSER.parse(Collections.singletonList(signedPrefix + mnemonic + suffix + " " + size),
                    "<address-lowered>").get(0);
        }

        // "INC/DEC leftType returnType" -> "INC_INT size" / "INC_FLOAT
        // size" / "DEC_INT size" / "DEC_FLOAT size" -- confirmed directly:
        // unlike AND/OR, this pair *is* given the `_INT`/`_FLOAT` split
        // the comparisons get, on the reasoning that "whether they really
        // need it or not is sort of architecture independent, but its
        // better done than not done" -- consistency/future-proofing with
        // the comparison split above, not a claim that a real machine
        // strictly needs two different increment/decrement instructions
        // the way it needs two different compare ones. `DEC` itself is a
        // brand-new mnemonic on the compiler side too -- see that
        // project's CLAUDE.md, "the '--' operator" -- added in the same
        // request as this rewrite ("DEC needs exactly the same treatment
        // as INC"), via `BytecodeEmitter.emitDecrement`, the identical
        // two-type-operand shape `emitIncrement` already has. Same
        // classification `isFloatBaseType` provides everywhere else;
        // `returnType` is dropped, same as always.
        if ((mnemonic.equals("INC") || mnemonic.equals("DEC")) && line.size() == 3) {
            String leftType = line.get(1).text;
            long size = sizes.sizeOf(leftType);
            String suffix = isFloatBaseType(CanonicalType.parse(leftType).baseType) ? "_FLOAT" : "_INT";
            return PARSER.parse(Collections.singletonList(mnemonic + suffix + " " + size), "<address-lowered>")
                    .get(0);
        }

        // "RECURSIVE_CALL funcName" -> "CALL funcName" -- confirmed
        // directly: "extern call and recursive call can both become just
        // call, in the lower order bytecode." Unlike EXTERN_CALL just
        // below, this one really is a bare rename with nothing else to
        // preserve or reconsider: `RECURSIVE_CALL` vs `CALL` was only ever
        // a compile-time bookkeeping distinction (recursion-legality
        // checking, gt-reachability redirection -- see
        // `BytecodeEmitter.emitCall`'s own comment, "@recursive function
        // calling itself"), never a different runtime call mechanism --
        // both resolve to the exact same "jump to this label, it has its
        // own FUNC_START/ARG signature already in this bytecode" shape a
        // not-yet-built codegen stage already has to handle for `CALL`.
        // `AWAIT_CALL`/`PAR_CALL` are deliberately *not* folded in here
        // even though `emitCall` picks all three from one shared site --
        // those really do need a different runtime call mechanism (async
        // orchestration), the same "different runtime meaning gets its
        // own mnemonic" precedent as `RET_FLOAT`, not merely a
        // compile-time-only label the way `RECURSIVE_CALL` turned out to
        // be.
        if (mnemonic.equals("RECURSIVE_CALL") && line.size() == 2) {
            return PARSER.parse(Collections.singletonList("CALL " + line.get(1).text), "<address-lowered>").get(0);
        }

        // "EXTERN_CALL name argCount" -> "CALL name argCount" -- the same
        // request, but with one real wrinkle `RECURSIVE_CALL` doesn't
        // have, flagged here rather than silently resolved either way:
        // `argCount` is *not* always redundant the way it looks at first.
        // An ordinary `CALL`'s target has its own `FUNC_START`/`ARG` lines
        // already in this same bytecode, so a not-yet-built codegen stage
        // can always recover its arity from there -- but an extern has no
        // body and so no `ARG` lines at all, only a single top-level
        // `EXTERN name returnType paramType...  [VARARGS]` declaration
        // line (`BytecodeEmitter.emitExtern`) with a *fixed* param list.
        // For a non-vararg extern that fixed list already fully
        // determines the call's arity (this rewrite doesn't yet cross-
        // reference it to confirm and drop the now-redundant count the
        // way `RETURNS` was dropped once `RET`/`RET_FLOAT` made it
        // redundant -- real, separate follow-up work). For a *vararg*
        // extern (`printf`, `...`), the fixed declaration's own param list
        // is only ever a prefix -- the actual argument count genuinely
        // varies per call site, and this operand is the only place that
        // call-site-specific number lives at all. So `argCount` is kept
        // here unconditionally, on both the varargs-needs-it grounds and
        // the "don't drop something without confirming it's provably
        // redundant first" grounds every other rewrite in this pass
        // already holds to -- only the mnemonic itself collapses to the
        // ordinary `CALL` requested; the operand shape doesn't shrink to
        // match plain `CALL`'s own two-token form.
        if (mnemonic.equals("EXTERN_CALL") && line.size() == 3) {
            return PARSER.parse(
                    Collections.singletonList("CALL " + line.get(1).text + " " + line.get(2).text),
                    "<address-lowered>").get(0);
        }

        // "POP name type" -> "POP $offset size" for a real, ALLOC-declared
        // local (the same `resolveAddress` lookup `PUSH`/`ADDR` already
        // run, so a later `PUSH name type` reading this local back
        // resolves to the identical address) -- or "POP ARGn size",
        // `ARGn` left completely alone verbatim, for the original
        // calling-convention shape (`emitArgWord`/`emitSyntheticArgWord`
        // transfer into it -- see this pass's own header doc, "Parameter
        // word classification"): `ARGn` was never `ALLOC`-declared under
        // that literal text, so `resolveAddress` simply never finds it in
        // `offsets` and the existing verbatim fallback below covers it
        // unchanged, the identical "not a name this pass resolves, just a
        // positional label a not-yet-built codegen stage still needs
        // verbatim" treatment `PUSH ARGn type`'s own `ARGn` operand
        // already gets just above. Either way, the trailing `type`
        // operand drops to a plain byte size, the identical "the
        // mnemonic/other operands already say enough, this one collapses
        // to its size" treatment every rewrite in this pass gives a
        // trailing type operand.
        if (mnemonic.equals("POP") && line.size() == 3) {
            long size = sizes.sizeOf(line.get(2).text);
            Long popAddress = resolveAddress(line.get(1).text, offsets, localTypes, sizes, structTable);
            String nameOperand = popAddress != null ? "$" + popAddress : line.get(1).text;
            return PARSER.parse(Collections.singletonList("POP " + nameOperand + " " + size),
                    "<address-lowered>").get(0);
        }

        // "PUSH_FIELDNAME fieldName fieldType" -> "PUSH_FIELDNAME
        // memberOffset size" -- confirmed directly: "we should be able to
        // resolve PUSH_FIELDNAME to use the calculated offset of the
        // member in the struct... as its first argument and the number of
        // bytes it leaves on the stack as its second argument, by just
        // consulting the previous instruction." `size` is simply
        // `sizes.sizeOf(fieldType)`, the identical treatment every other
        // trailing type operand in this pass already gets. `memberOffset`
        // needs to know *which struct* -- `fieldName` alone doesn't say
        // that -- and that's exactly what "the previous instruction"
        // supplies: whatever ran immediately before (`ADDR`/`PUSH`/
        // `LOOKUP`/`PUSH_RET`/a prior `DOT`, confirmed directly this is
        // always one of these, never anything else in between) always
        // states, as its own trailing operand, the type of the value it
        // just left on the stack -- which is exactly the struct
        // `PUSH_FIELDNAME` is about to read a field's offset out of. That
        // predecessor is read here in its *original*, not-yet-lowered
        // form (`previousLine`, captured by `run()` before this same pass
        // gets a chance to erase it down to a bare size) -- an
        // already-lowered predecessor has had the very struct-name text
        // this rewrite needs erased already. `memberLocOf` (already used
        // by `resolveAddress`/`GT_DESTRUCT` above) does the real lookup
        // against `StructTable`; left completely untouched -- the
        // identical "flag the gap, don't guess" treatment every other
        // unresolvable case in this pass already gets -- when there's no
        // previous line, the previous line is empty, or `memberLocOf`
        // doesn't recognize the resulting base type as a real struct
        // (should never happen given the compiler's own emission
        // structure, but not assumed here either).
        if (mnemonic.equals("PUSH_FIELDNAME") && line.size() == 3) {
            if (previousLine == null || previousLine.isEmpty()) {
                return null;
            }
            String structType = previousLine.get(previousLine.size() - 1).text;
            String structBaseType = CanonicalType.parse(structType).baseType;
            String fieldName = line.get(1).text;
            String fieldType = line.get(2).text;
            MemberLoc loc = memberLocOf(structBaseType, fieldName, sizes, structTable);
            if (loc == null) {
                return null;
            }
            long size = sizes.sizeOf(fieldType);
            return PARSER.parse(Collections.singletonList("PUSH_FIELDNAME " + loc.offset + " " + size),
                    "<address-lowered>").get(0);
        }

        // "DOT leftType returnType returnType" / "DOT_LHS leftType
        // returnType returnType" -> "DOT size" / "DOT_LHS size" --
        // confirmed directly, the same "leftType/rightType -> size"
        // family as everything above, checked while auditing the
        // untouched-mnemonics list. Both always run immediately after a
        // (now-lowered) "PUSH_FIELDNAME" -- `emitDot`/`emitAssignTarget`'s
        // own "PUSH_FIELDNAME fieldName fieldType" then
        // "DOT/DOT_LHS leftType returnType returnType" pairing -- and
        // `leftType` here is the *whole struct's* own type (`op.left.
        // resolvedType`), never actually needed: DOT's whole job is
        // "read N bytes off whatever the base expression left on the
        // stack," and that N is the *field's* own size, not the struct's.
        // `returnType` (`op.resolvedType`, the field's own type, repeated
        // twice -- BytecodeEmitter never varies the two) is exactly that
        // N, so it alone is read; `leftType` and the duplicate
        // `returnType` are both dropped.
        if ((mnemonic.equals("DOT") || mnemonic.equals("DOT_LHS")) && line.size() == 4) {
            long size = sizes.sizeOf(line.get(2).text);
            // A field that is itself a fixed array of at most 8 bytes held by value (`vals: u16[3]`): the extra token "ra" tells Codegen to
            // assemble it from the (possibly two) words it occupies and to tag the pushed word as a small array for the next lookup.
            String smallArray = "";
            CanonicalType ft = CanonicalType.parse(line.get(2).text);
            if (mnemonic.equals("DOT") && ft.storage == null && CanonicalType.fixedArrayElementTypeOf(ft.baseType) != null && size <= 8) {
                smallArray = " ra";
            }
            return PARSER.parse(Collections.singletonList(mnemonic + " " + size + smallArray), "<address-lowered>").get(0);
        }

        // "ADDR_OF opText leftType returnType" -> "ADDR_OF opText
        // $address" -- the other half of this pass's ADDR_OF handling
        // (see the drop case in `run()`, right above where
        // `previousLine` is threaded through): reached only when the
        // immediately preceding line's own trailing type carries *no*
        // storage yet -- a plain value, not already a pointer -- which
        // is exactly the "auto x" / addressable "raw x" case:
        // `emitAddressOf` already did `emitExpr(op.left)` first, and for
        // any addressable lvalue (a bare VARREF or a dot chain rooted in
        // one -- `isAddressableLvalue`, the same condition both `auto`
        // and now `raw` are required to satisfy) that recurses down to
        // `emitDot`'s "PUSH qualifiedDotName type" shape (confirmed
        // directly -- both a bare "x" and a chain like "p.x" collapse to
        // the identical single PUSH of the fully dot-joined name, per
        // `isQualifiedNameableDot`/`qualifiedDotName`), so `previousLine`
        // here is always a plain, not-yet-lowered "PUSH name type" line
        // whose own name is exactly the address ADDR_OF needs --
        // resolved the identical way ADDR/PUSH/GT_DESTRUCT's own name
        // operand already is, via `resolveAddress`. `opText`
        // (AUTO/RAW -- REF and a storage-bearing RAW never reach this
        // case at all, already dropped above) is kept; `leftType`/
        // `returnType` are both dropped -- neither carries anything a
        // not-yet-built codegen stage still needs once a real address is
        // in hand. Left completely untouched (this pass's own "flag the
        // gap, don't guess" treatment) when `previousLine` isn't this
        // exact shape or the name doesn't resolve -- should not happen
        // given `checkAddressOf`'s own restriction, but not assumed here
        // either.
        if (mnemonic.equals("ADDR_OF") && line.size() == 4 && previousLine != null && previousLine.size() == 3
                && (previousLine.get(0).text.equals("PUSH") || previousLine.get(0).text.equals("ATOMIC_PUSH"))) {
            Long address = resolveAddress(previousLine.get(1).text, offsets, localTypes, sizes, structTable);
            if (address == null) {
                return null;
            }
            return PARSER.parse(Collections.singletonList("ADDR_OF " + line.get(1).text + " $" + address),
                    "<address-lowered>").get(0);
        }

        // "MEMCOPY destType countType sourceType returnType" -> bare
        // "MEMCOPY", no operands at all -- a genuine exception to this
        // pass's usual "erase to a size" treatment, confirmed directly by
        // checking whether any of the four operands could ever actually
        // vary: they can't. `checkMemcopyBuiltin` pins every one of them
        // down to a single, fixed shape at every legal call site, not
        // just this pass's own convenience: `destType` must have `raw`
        // storage specifically, `sourceType` must have *some* storage
        // (any pointer kind), and every pointer in this bytecode -- any
        // non-null storage, regardless of what it actually points to --
        // is already a flat 8-byte word (`SizeCalculator`'s own rule, the
        // same one `CLONE`/`ATOMIC_SWAP` had to be careful about
        // elsewhere in this pass); `returnType` is `destType` again,
        // verbatim; `countType` must be `isPlainU64` exactly -- confirmed
        // directly no narrower integer type is ever accepted here even
        // when a real, differently-sized value is in hand (`u32 as u64`
        // widens as expected, but the reverse, or a bare `u32` passed
        // straight through, is rejected outright by `checkMemcopyBuiltin`
        // itself: "'memcopy''s byte count must be a plain 'u64'"). So all
        // four operands are always exactly 8 bytes, at every call site,
        // unconditionally -- unlike `NEG`/`CLONE`/`DOT`/every other
        // "erase to a size" rewrite above, where the size that survives
        // genuinely varies call to call and is real information a
        // not-yet-built codegen stage needs, here it never varies at all,
        // so keeping it as "MEMCOPY 8 8 8 8" everywhere would just be
        // dead, constant text. Unlike `RETURNS` (dropped as a whole line
        // -- pure documentation, nothing ever consumes it, see just
        // above), `MEMCOPY` still does real work at runtime (the actual
        // byte-for-byte copy) and has to survive as an instruction: the
        // three real operands it needs (destination, count, source) are
        // already sitting on the stack as ordinary pushed values right
        // before it runs (`emitExpr` on each of `memcopy`'s three
        // arguments, in order), so a bare "MEMCOPY" with zero trailing
        // operands is a complete contract for a not-yet-built codegen
        // stage: pop/read three word-sized values in that fixed order,
        // copy `count` bytes from the third to the first, leave the first
        // as the result.
        if (mnemonic.equals("MEMCOPY") && line.size() == 5) {
            return PARSER.parse(Collections.singletonList("MEMCOPY"), "<address-lowered>").get(0);
        }

        // "NEW_FROM_STRING dynType sourceType" -> bare "NEW_FROM_STRING",
        // both operands dropped -- the identical `MEMCOPY` treatment just
        // above, for the identical reason: neither operand can ever
        // actually vary. `checkDynBuiltinCore`'s own string-source branch
        // hardcodes the constructed array's element type to a fresh
        // `new TypeInfo(null, "imut", "char")` unconditionally -- `dynType`
        // is always some `dynarray(char)`, never any other element type,
        // and `SizeCalculator.sizeOfBaseType` already gives a flat 8-byte
        // handle to *any* `dynarray(...)`, whatever its element type, so
        // this would be constant even without that hardcoding. `sourceType`
        // is always `string`-based (`checkDynBuiltinCore` requires
        // `argType.baseType.equals("string")` to take this branch at
        // all) and confirmed directly to always carry non-null storage:
        // `string` is a genuine pointer type in this language, and this
        // project's own TypeChecker rejects a storage-less `string`
        // outright wherever one could be declared ("type 'string' is a
        // pointer type and requires a storage modifier") -- so `sourceType`
        // always lands on `SizeCalculator.sizeOf`'s own `storage != null
        // -> 8` branch (true whether the string is a plain literal's own
        // inherent `static` storage, a `raw`/`ref`/... pointer wrapping
        // one, or anything else -- every case is 8 bytes regardless).
        // Both operands are always exactly 8 bytes at every legal call
        // site, unconditionally -- the same "not real, call-site-varying
        // information" situation `MEMCOPY`'s four operands are in, so
        // "NEW_FROM_STRING 8 8" would be exactly as dead and constant as
        // "MEMCOPY 8 8 8 8" would have been. `NEW_FROM_STRING` still does
        // real work at runtime (the actual character-by-character copy
        // out of the string into a fresh heap-backed array) and has to
        // survive as an instruction, unlike `RETURNS` -- its one real
        // operand (the source string) is already an ordinary pushed value
        // on the stack immediately before it runs, so a bare
        // "NEW_FROM_STRING" is a complete contract: pop/read one
        // word-sized string value, build a fresh `dynarray(char)` handle
        // from it, leave that handle as the result.
        if (mnemonic.equals("NEW_FROM_STRING") && line.size() == 3) {
            return PARSER.parse(Collections.singletonList("NEW_FROM_STRING"), "<address-lowered>").get(0);
        }

        // "GT_DESTRUCT name" -> "GT_DESTRUCT $offset" -- `name` is a
        // bare local or a dotted chain (BytecodeEmitter's own
        // `qualifiedDotName`, for a struct member's own owns-typed
        // field), resolved exactly the way ADDR/PUSH's own name operand
        // already is above. Confirmed directly this should reference the
        // target "by basepointer offset" too, the identical "it isn't
        // supposed to be a name any more" treatment this whole pass
        // already gives every other stack-resident reference. Left
        // completely untouched when unresolved -- the identical "Known
        // gap" a dotted chain through a real pointer already hits for
        // ADDR, not silently guessed at here either.
        if (mnemonic.equals("GT_DESTRUCT") && line.size() == 2) {
            Long address = resolveAddress(line.get(1).text, offsets, localTypes, sizes, structTable);
            if (address == null) {
                return null;
            }
            return PARSER.parse(Collections.singletonList("GT_DESTRUCT $" + address), "<address-lowered>").get(0);
        }

        // "ALLOC_STATIC"/"GLOBAL" (a local static or a top-level global,
        // both container and per-field/per-element leaf lines alike) --
        // see `rewriteStaticOrGlobalDecl`'s own doc comment. Handled via a
        // standalone method, not inline here, since `run`'s own top-level
        // loop needs the identical rewrite for a top-level `GLOBAL` line
        // too, from a completely different call site (see that method's
        // own doc for why).
        List<BytecodeToken> staticRewrite = rewriteStaticOrGlobalDecl(line, sizes, enumTable);
        if (staticRewrite != null) {
            return staticRewrite;
        }

        if (line.size() != 3) {
            return null;
        }
        String firstOperand = line.get(1).text;
        String typeOperand = line.get(2).text;

        // No `mnemonic.equals("ALLOC")` case here any more -- every
        // in-function ALLOC line is now intercepted earlier, directly in
        // `run()`'s own per-line loop (see the "combined ALLOC" comment
        // there), and collapsed into one single combined "ALLOC
        // totalAllocSize" line rather than reaching this generic,
        // one-line-at-a-time rewrite at all. This method is never called
        // with an in-function `ALLOC` line as a result.

        // "ATOMIC_PUSH name type" gets the identical name-resolution-then-
        // erase-to-size treatment plain "PUSH" already gets just below --
        // same reasoning as the ATOMIC_ASSIGN case above (see that rewrite's
        // own comment): keeping `mnemonic` (not a hardcoded "PUSH" literal)
        // in every rebuilt line here is what lets the ATOMIC_ prefix survive
        // untouched all the way to low-order bytecode.
        if (!mnemonic.equals("PUSH") && !mnemonic.equals("ADDR") && !mnemonic.equals("ATOMIC_PUSH")) {
            return null;
        }
        long size = sizes.sizeOf(typeOperand);

        if (mnemonic.equals("PUSH") || mnemonic.equals("ATOMIC_PUSH")) {
            ArgWordSlot slot = argWordSlots.get(firstOperand);
            if (slot != null) {
                if (!slot.isRegister) {
                    String rewritten = mnemonic + " " + size + " $" + slot.stackOffset;
                    return PARSER.parse(Collections.singletonList(rewritten), "<address-lowered>").get(0);
                }
                // Register-transferred -- the token's own digit is
                // rewritten to this word's real per-bank register-table
                // index before falling through to the ordinary "erase
                // type to size, carry the operand text through unchanged"
                // tail below. For a shared-argument-position convention
                // (win64) this is a no-op: the raw digit `resolveArgWordSlots`
                // was handed already *is* that index. For an independent-
                // counting convention (SysV/arm64) the raw digit is only
                // ever this function's own uniformly-incrementing word
                // position (`ArgToAllocLoweringPass`'s own `wordIndex`),
                // never the per-bank index a real register table needs,
                // once an int word and a float word can be interleaved --
                // see `resolveArgWordSlots`'s own doc comment for the
                // full reasoning.
                boolean isFloatWord = parseFargIndex(firstOperand) >= 0;
                firstOperand = (isFloatWord ? "FARG" : "ARG") + slot.registerIndex;
            }
        }

        Long address = resolveAddress(firstOperand, offsets, localTypes, sizes, structTable);
        String secondOperand;
        if (address != null) {
            secondOperand = "$" + address;
        } else {
            // Every real ADDR call site in the compiler (`BytecodeEmitter`'s
            // own handful of "ADDR " emissions) hands it a single, bare
            // name, never a dotted chain -- a struct-field/array-element
            // assignment target goes through "DOT_LHS"/"LOOKUP_LHS"
            // instead (see `emitAssignTarget`), and a struct's own dotted
            // '.'/LOOKUP root, when it needs its address at all, is routed
            // through this exact same bare-"ADDR name type" shape by
            // `emitDotLhsRoot`, never a pre-built dotted string. So unlike
            // `GT_DESTRUCT`'s own dotted chain (which genuinely can pass
            // through a pointer partway and hit this pass's own "Known
            // gap," documented above), an unresolved ADDR operand is never
            // that -- `resolveAddress`'s root-lookup simply failed because
            // `firstOperand` isn't a stack local at all, meaning it's a
            // real, singly-declared, addressable name this bytecode
            // already has exactly two other ways to declare (a function-
            // local `ALLOC_STATIC`, or a top-level `GLOBAL`). Confirmed
            // directly by grepping every "ADDR " emission site: none ever
            // builds a dotted string for it. So this was never actually a
            // "Known gap" the way the doc comment above once described it
            // -- it's the exact same "a literal, 'null', a global label...
            // carried over unchanged, just reordered after its own size"
            // shape `PUSH` already had (see the `else` branch just below,
            // now shared) -- ADDR's own separate bail-out here was a real,
            // found-and-fixed inconsistency: it left the *entire original
            // line* untouched (full canonical type text and all) rather
            // than reducing to a bare size the way every other name-
            // bearing operand in this pass already does, confirmed
            // directly against a real "let static x = mut 0" fixture
            // ("ADDR x mut_u64" survived completely unchanged before this
            // fix, next to an already-consistent "PUSH 8 x" one line
            // later). Now uniform: "ADDR size name", exactly mirroring
            // "PUSH size name" -- a not-yet-built codegen stage can treat
            // both the same way, discriminating stack-slot-vs-real-symbol
            // in exactly one place (this pass's own `offsets` lookup
            // already does that discrimination; codegen's equivalent
            // symbol-table lookup is the only other place it would ever
            // need to happen again).
            // "null", a global label, ... carried over unchanged, just
            // reordered after its own size -- except a boolean or
            // enum-variant literal, which gets resolved down to its own
            // concrete value right here (see `resolveLiteralOrEnumValue`'s
            // own doc comment): the one and only place either kind of
            // literal can still be riding along as `firstOperand` text at
            // this point, since `resolveAddress` above only ever succeeds
            // for a genuine stack local.
            secondOperand = resolveLiteralOrEnumValue(firstOperand, enumTable);
        }
        String rewrittenText = mnemonic + " " + size + " " + secondOperand;
        return PARSER.parse(Collections.singletonList(rewrittenText), "<address-lowered>").get(0);
    }

    /**
     * "true"/"false" become "1"/"0", as in C -- confirmed directly. And
     * an "EnumName.Variant" reference resolves down to that variant's own
     * concrete integer value (`EnumTable.variantValue` -- explicit for a
     * value-valued enum, sequential 0/1/2/... "as in C" for a plain one)
     * -- confirmed directly, "in the lower order code, all the literal
     * enum values resolve to their actual values." Both are checked here,
     * in this exact order, at the one place either kind of literal can
     * still be sitting as plain text once every genuine stack-local name
     * has already been resolved to an address above (`PUSH`/`ADDR`/
     * `ATOMIC_PUSH`'s own shared "carried over unchanged" fallback) or
     * once a static/global's own literal initializer is being rewritten
     * (`rewriteStaticOrGlobalDecl`).
     *
     * The enum check is safe to attempt unconditionally on *any* dotted
     * name reaching this point, not just ones already known to be enum
     * references: `EnumTable.variantValue` returns null for anything
     * whose leading segment isn't a real, tracked enum name (an ordinary
     * struct/instance name that merely happens to precede a '.' too, for
     * instance), and `text` is returned unchanged whenever that happens
     * -- so an unresolved struct-field dotted chain (this pass's own
     * "Known gap," see `resolveAddress`) still falls all the way through
     * to being carried over exactly as before, not silently misread as
     * an enum.
     */
    private static String resolveLiteralOrEnumValue(String text, EnumTable enumTable) {
        if (text.equals("true")) {
            return "1";
        }
        if (text.equals("false")) {
            return "0";
        }
        int dot = text.indexOf('.');
        if (dot > 0 && dot < text.length() - 1) {
            String enumName = text.substring(0, dot);
            String variantName = text.substring(dot + 1);
            Long value = enumTable.variantValue(enumName, variantName);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return text;
    }

    /**
     * Resolves a bare name or dotted chain ("x", "x.y", "x.y.z") to a
     * single, flat, compile-time byte offset from this function's own
     * base pointer -- null the moment any segment can't be resolved
     * this way (an unknown root, an unknown member, or a pointer
     * partway through the chain -- see this pass's own "Known gap").
     */
    private Long resolveAddress(String nameChain, Map<String, Long> offsets, Map<String, String> localTypes,
            SizeCalculator sizes, StructTable structTable) {
        int dot = nameChain.indexOf('.');
        String root = (dot < 0) ? nameChain : nameChain.substring(0, dot);
        Long base = offsets.get(root);
        if (base == null) {
            return null;
        }
        long address = base;
        String currentType = localTypes.get(root);
        String remainder = (dot < 0) ? "" : nameChain.substring(dot + 1);
        while (!remainder.isEmpty()) {
            CanonicalType t = CanonicalType.parse(currentType);
            if (t.storage != null) {
                return null; // pointer indirection -- defensive fallback, no longer expected to fire; see this pass's own header
            }
            int nextDot = remainder.indexOf('.');
            String segment = (nextDot < 0) ? remainder : remainder.substring(0, nextDot);
            MemberLoc loc = memberLocOf(t.baseType, segment, sizes, structTable);
            if (loc == null) {
                return null;
            }
            address += loc.offset;
            currentType = loc.type;
            remainder = (nextDot < 0) ? "" : remainder.substring(nextDot + 1);
        }
        return address;
    }

    private static final class MemberLoc {
        final long offset;
        final String type;

        MemberLoc(long offset, String type) {
            this.offset = offset;
            this.type = type;
        }
    }

    /**
     * One member's own byte offset within `baseType`'s own real,
     * physical layout (a real struct's own compiler-padded layout, or a
     * range's fixed synthetic shape -- see `pseudoLayoutOf`), and its
     * own declared type; null if `memberName` isn't one of `baseType`'s
     * own members.
     *
     * Walks `StructTable.LayoutEntry`, not the plain `Member` list --
     * BytecodeEmitter.emitStruct (compiler project) now bakes real
     * natural-alignment "STRUCT_PADDING n" gaps directly into a struct's
     * own declared order, so an offset walk that only ever summed real
     * member sizes (the old `pseudoMembersOf`/`Member`-based version of
     * this method) would silently land every field after the first gap
     * at the wrong address the instant any struct actually needed
     * padding. A pure padding entry is never matched against
     * `memberName` (it has no name to match) and just adds its own
     * already-known byte count straight to the running offset.
     */
    private MemberLoc memberLocOf(String baseType, String memberName, SizeCalculator sizes, StructTable structTable) {
        if (memberName.equals("___type")) {
            // Every real struct's own hidden classId field is always its
            // very first member (BytecodeEmitter.emitStruct's own
            // "___type must be first" invariant -- see caspien-compiler's
            // own CLAUDE.md) -- always offset 0, always a plain
            // "imut_u64", regardless of which static type was used to
            // reach it. Resolved directly here, structure-agnostically,
            // rather than through the ordinary layout walk below, so
            // MembershipLoweringPass's "leftName.___type" (built for
            // "instanceof"/"implements") resolves correctly even when
            // `baseType` is an *interface* -- which carries no
            // STRUCT_START/STRUCT_MEMBER layout of its own in
            // `structTable` at all, and so would otherwise make the
            // ordinary walk below (via `pseudoLayoutOf`) fail with a null
            // layout and leave this dotted access unresolved. A concrete
            // struct's own real layout would have produced this exact
            // same "(0, imut_u64)" answer anyway, so this changes nothing
            // for that already-working case.
            return new MemberLoc(0, "imut_u64");
        }
        List<StructTable.LayoutEntry> layout = pseudoLayoutOf(baseType, structTable);
        if (layout == null) {
            return null;
        }
        MemberLoc direct = walkLayoutFor(layout, memberName, sizes);
        if (direct != null) {
            return direct;
        }
        // "instanceof"/"implements" narrowing fallback -- see
        // memberLocViaExtendingStruct's own doc comment.
        return memberLocViaExtendingStruct(baseType, layout, memberName, sizes, structTable);
    }

    private static MemberLoc walkLayoutFor(List<StructTable.LayoutEntry> layout, String memberName,
            SizeCalculator sizes) {
        long offset = 0;
        for (StructTable.LayoutEntry entry : layout) {
            if (entry.member == null) {
                offset += entry.paddingBytes;
                continue;
            }
            if (entry.member.name.equals(memberName)) {
                return new MemberLoc(offset, entry.member.canonicalType);
            }
            offset += sizes.sizeOf(entry.member.canonicalType);
        }
        return null;
    }

    /**
     * `TypeChecker`'s own "instanceof"/"implements" narrowing (see
     * `narrowInstanceofSlots`) lets a variable's *static* type be treated
     * as a subclass for the rest of a match arm ("match b instanceof
     * Sub{ let z = mut b.y }", `y` declared only on `Sub`, not `Base`) --
     * fully type-checked and correctly resolved at that stage
     * (`op.left.resolvedType` really does read `"mut_Sub"` inside that
     * arm). But this bytecode format has no way to carry that fact
     * forward: `BytecodeEmitter.emitDot`'s own `qualifiedDotName` builds
     * a dotted chain's text purely from each node's own `.text` ("b.y"),
     * never consulting `op.left.resolvedType` at all -- confirmed
     * directly, the narrowing information exists in memory at emission
     * time and is simply never written down. By the time this pass sees
     * "b.y", `localTypes.get("b")` is unconditionally `b`'s own
     * *declared* type from its `ALLOC` line ("Base") -- the narrowed
     * "Sub" fact is already gone, and `walkLayoutFor` above (walking
     * `Base`'s own real layout) can never find a member "Base" itself
     * doesn't declare.
     *
     * Recovering the missing fact here, rather than threading it through
     * three separate bytecode formats, works because of how `extends` is
     * physically represented: this bytecode format has **no explicit
     * "extends" declaration at all** -- a child struct's own
     * `STRUCT_START`/`STRUCT_MEMBER` block is already fully flattened by
     * the compiler (parent's own fields, in the parent's own order, then
     * the child's own additional fields -- see `caspien-compiler`'s own
     * CLAUDE.md), and single inheritance is a hard compiler-level rule
     * (a non-abstract struct can only ever extend one parent). Put
     * together, this means "does struct S extend Base" is always
     * recoverable *structurally*, with no separate relationship to look
     * up: S extends Base (directly or transitively) exactly when S's own
     * real layout begins with Base's own real layout, entry for entry,
     * as a strict prefix.
     *
     * So: scan every struct name `StructTable` knows about (added
     * specifically for this, via `StructTable.allStructNames`) for one
     * whose own layout is a strict, entry-for-entry prefix-extension of
     * `baseLayout`, and which genuinely declares `memberName` somewhere
     * in its own full layout (walked directly, non-recursively -- no
     * further extends-fallback needed for a *transitive* grandchild,
     * since flattening already means a grandchild's own layout already
     * contains its grandparent's layout as a prefix too).
     *
     * **Deliberately conservative on ambiguity**: if two or more
     * qualifying extending structs disagree about where (or as what
     * type) `memberName` lives -- e.g. two different direct children of
     * `Base` each independently declaring their own, differently-offset
     * "y" -- there is no way to tell, from "b.y" alone, which one was
     * actually proven at the real `instanceof`/`implements` site (that
     * fact was already discarded, per this method's own doc comment
     * above), so this bails to `null` (left unresolved, the same "don't
     * guess" fallback every other gap in this codebase's lowering passes
     * already relies on) rather than silently picking one. A truly
     * unambiguous fix would thread the narrowed type through
     * `BytecodeEmitter`/`MembershipLoweringPass` explicitly instead --
     * flagged as a follow-up, not attempted here, since every currently
     * known real fixture exercising this shape has only a single
     * qualifying extending struct.
     */
    private MemberLoc memberLocViaExtendingStruct(String baseType, List<StructTable.LayoutEntry> baseLayout,
            String memberName, SizeCalculator sizes, StructTable structTable) {
        MemberLoc found = null;
        for (String candidateName : structTable.allStructNames()) {
            if (candidateName.equals(baseType)) {
                continue;
            }
            List<StructTable.LayoutEntry> candidateLayout = structTable.layoutOf(candidateName);
            if (candidateLayout == null || candidateLayout.size() <= baseLayout.size()
                    || !layoutStartsWith(candidateLayout, baseLayout)) {
                continue;
            }
            MemberLoc loc = walkLayoutFor(candidateLayout, memberName, sizes);
            if (loc == null) {
                continue;
            }
            if (found != null && !(found.offset == loc.offset && found.type.equals(loc.type))) {
                return null; // genuinely ambiguous between two extending structs -- leave unresolved rather than guess
            }
            found = loc;
        }
        return found;
    }

    /** True when `longer`'s own layout begins, entry for entry (same member name and type, or an identical padding gap), with all of `prefix`. */
    private static boolean layoutStartsWith(List<StructTable.LayoutEntry> longer, List<StructTable.LayoutEntry> prefix) {
        for (int i = 0; i < prefix.size(); i++) {
            StructTable.LayoutEntry a = longer.get(i);
            StructTable.LayoutEntry b = prefix.get(i);
            if (a.member == null || b.member == null) {
                if (a.member != null || b.member != null || a.paddingBytes != b.paddingBytes) {
                    return false;
                }
            } else if (!a.member.name.equals(b.member.name) || !a.member.canonicalType.equals(b.member.canonicalType)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A range's own fixed member layout is never a real
     * STRUCT_START/STRUCT_MEMBER/STRUCT_END block (see
     * `TypeChecker.checkRange`'s own "always two plain u64 bounds" rule,
     * the same rule `MembershipLoweringPass`'s own `.start`/`.end`
     * dotted-field reads already lean on) -- synthesized here, in the
     * exact same declared order the compiler itself always pushes them
     * in (`BytecodeEmitter`'s own range-literal `ASSIGN` sequence: start,
     * then end), as two real `LayoutEntry.ofMember` slots with no gap
     * between them (both are plain 8-byte u64s, already naturally
     * aligned back to back). Everything else falls through to a real
     * struct's own `StructTable` physical layout. (This used to also
     * synthesize a slice's own three-member layout -- origin, then
     * start, then end -- but the slice type has been removed from the
     * language entirely.)
     */
    private List<StructTable.LayoutEntry> pseudoLayoutOf(String baseType, StructTable structTable) {
        if (baseType.equals("range") || baseType.startsWith("range(")) {
            List<StructTable.LayoutEntry> m = new ArrayList<>();
            m.add(StructTable.LayoutEntry.ofMember(new StructTable.Member("start", "indeterminate_u64")));
            m.add(StructTable.LayoutEntry.ofMember(new StructTable.Member("end", "indeterminate_u64")));
            return m;
        }
        return structTable.layoutOf(baseType);
    }

    /**
     * Every value's own size in bytes, computed purely from its
     * canonical type text and this program's own `StructTable` -- no
     * dependency on the compiler project, matching every other pass
     * here. A storage-bearing type (any pointer, whatever its pointee)
     * is always exactly one 8-byte word, the identical "just a
     * pointer, regardless of what it points to" reasoning
     * `emitArgTransfer`'s own single-word argument case already uses on
     * the compiler side.
     */
    static final class SizeCalculator {
        private final StructTable structTable;
        private final Map<String, Long> structSizeCache = new HashMap<>();
        private final Map<String, Long> structAlignCache = new HashMap<>();

        SizeCalculator(StructTable structTable) {
            this.structTable = structTable;
        }

        long sizeOf(String canonical) {
            // A bare struct name (a dynarray's element text, a fill
            // value's already-stripped base type, ...) is not a
            // "mutability_baseType" string, but a generic instantiation's
            // mangled name (HashMapEntry_u64) contains an underscore, and
            // parse() would read "HashMapEntry" as a mutability and "u64"
            // as the base type -- a silent 8-byte size for a 32-byte
            // struct. A name the struct table knows is used whole.
            if (structTable.hasStruct(canonical)) {
                return sizeOfBaseType(canonical);
            }
            CanonicalType t = CanonicalType.parse(canonical);
            if (t.storage != null) {
                return 8;
            }
            return sizeOfBaseType(t.baseType);
        }

        private long sizeOfBaseType(String baseType) {
            switch (baseType) {
                // "bool" is kept 1 byte here, matching the compiler's own
                // TypeChecker.PRIMITIVE_SIZE -- flagged, not settled: a
                // real ABI more commonly widens a bool to a full word
                // (4 or 8 bytes) once it's actually stored in a register
                // or on the stack rather than packed into a struct, so
                // this 1-byte figure may need revisiting once a real
                // codegen stage exists and this size stops being purely
                // informational.
                case "u8": case "s8": case "bool": case "char":
                    return 1;
                case "u16": case "s16":
                    return 2;
                case "u32": case "s32": case "f32":
                    return 4;
                case "u64": case "s64": case "f64": case "code_addr": case "string":
                    return 8;
                case "void":
                    return 0;
                default:
                    break;
            }
            String dynElem = CanonicalType.dynArrayElementTypeOf(baseType);
            if (dynElem != null) {
                return 8; // a dynarray value is a single handle/pointer to its own heap buffer
            }
            String arrElem = CanonicalType.fixedArrayElementTypeOf(baseType);
            if (arrElem != null) {
                int length = CanonicalType.fixedArrayLengthOf(baseType);
                return length < 0 ? 8 : (long) length * sizeOf(arrElem);
            }
            if (baseType.equals("range") || baseType.startsWith("range(")) {
                return 16; // two plain u64 bounds, always -- TypeChecker.checkRange
            }
            if (structTable.hasStruct(baseType)) {
                return structSizeOf(baseType);
            }
            // Unknown to this table (a bare, incomplete "range"
            // pointer type, or anything else this first pass doesn't
            // yet recognize) -- a conservative, flagged-here, one-word
            // fallback rather than a silent guess passed off as exact.
            return 8;
        }

        /**
         * `structName`'s own real total size, in bytes -- summed off its
         * own physical `LayoutEntry` layout (real members plus any
         * compiler-baked "STRUCT_PADDING" gaps), not the old plain
         * `membersOf`-based member-size sum this used to be. That old
         * sum was exactly the "structs when not pointers are their
         * actual size -- ignores alignment/padding entirely" gap this
         * class was long flagged for: it silently under-counted any
         * struct BytecodeEmitter.emitStruct actually padded, since a
         * padding gap was never one of its `Member`s to iterate at all.
         * A padding entry's own byte count is already known outright
         * (never re-derived via `sizeOf`, which has no type text to work
         * from for one) and is added to the running total directly.
         */
        private long structSizeOf(String structName) {
            Long cached = structSizeCache.get(structName);
            if (cached != null) {
                return cached;
            }
            structSizeCache.put(structName, 0L); // struct types form a DAG, never a cycle -- see StructTable.isOwnsBearing's own identical guard
            long total = 0;
            List<StructTable.LayoutEntry> layout = structTable.layoutOf(structName);
            if (layout != null) {
                for (StructTable.LayoutEntry entry : layout) {
                    total += entry.member != null ? sizeOf(entry.member.canonicalType) : entry.paddingBytes;
                }
            } else {
                // Defensive fallback only -- StructTable.read always
                // populates `layouts` and `structs` together, in
                // lockstep, so a struct with no layout entry at all
                // shouldn't be reachable here; falls back to the old,
                // pre-padding plain member sum rather than silently
                // returning 0 if it somehow is.
                for (StructTable.Member m : structTable.membersOf(structName)) {
                    total += sizeOf(m.canonicalType);
                }
            }
            structSizeCache.put(structName, total);
            return total;
        }

        /**
         * Every value's own natural alignment in bytes -- the stack-frame
         * counterpart of `sizeOf` (see `AddressLoweringPass`'s own
         * per-function offset loop, which now rounds each local's own
         * downward-growing offset to this before reserving its slot,
         * exactly mirroring the compiler side's own
         * `BytecodeEmitter.layoutSizeAndAlignOf`/`layoutSizeAndAlignOfBaseType`
         * -- a fully independent, duplicated copy of that same table,
         * matching this project's own established "no dependency on the
         * compiler project" rule). Alignment always equals size for
         * every leaf shape here (nothing in this language is ever
         * over-aligned relative to its own width) except a struct, whose
         * own alignment is the max of its own members' alignments, not
         * necessarily its own (now compiler-padded) total size.
         */
        long alignOf(String canonical) {
            CanonicalType t = CanonicalType.parse(canonical);
            if (t.storage != null) {
                return 8;
            }
            return alignOfBaseType(t.baseType);
        }

        private long alignOfBaseType(String baseType) {
            switch (baseType) {
                case "u8": case "s8": case "bool": case "char":
                    return 1;
                case "u16": case "s16":
                    return 2;
                case "u32": case "s32": case "f32":
                    return 4;
                case "u64": case "s64": case "f64": case "code_addr": case "string":
                    return 8;
                case "void":
                    return 1;
                default:
                    break;
            }
            String dynElem = CanonicalType.dynArrayElementTypeOf(baseType);
            if (dynElem != null) {
                return 8; // a dynarray value is a single handle/pointer -- same reasoning as sizeOfBaseType's own identical case
            }
            String arrElem = CanonicalType.fixedArrayElementTypeOf(baseType);
            if (arrElem != null) {
                return alignOf(arrElem); // an array's own alignment is its element's own -- never its own total size
            }
            if (baseType.equals("range") || baseType.startsWith("range(")) {
                return 8; // two plain u64 bounds -- 8-aligned, same as either bound alone
            }
            if (structTable.hasStruct(baseType)) {
                return structAlignOf(baseType);
            }
            // Unknown to this table -- the identical conservative,
            // one-word fallback `sizeOfBaseType` already uses for the
            // same case.
            return 8;
        }

        /** `structName`'s own overall alignment: the max of every one of its own real members' own alignments (its hidden classId included, when it has one -- an ordinary `Member` in `membersOf`'s own list, "imut_u64", 8-aligned, like any other), never derived from its own (already rounded-up) total size. Memoized the same DAG-safe way `structSizeOf` already is. */
        private long structAlignOf(String structName) {
            Long cached = structAlignCache.get(structName);
            if (cached != null) {
                return cached;
            }
            structAlignCache.put(structName, 1L); // struct types form a DAG, never a cycle -- same seed-before-descend guard structSizeOf/isOwnsBearing already use
            long maxAlign = 1;
            List<StructTable.Member> members = structTable.membersOf(structName);
            if (members != null) {
                for (StructTable.Member m : members) {
                    long align = alignOf(m.canonicalType);
                    if (align > maxAlign) {
                        maxAlign = align;
                    }
                }
            }
            structAlignCache.put(structName, maxAlign);
            return maxAlign;
        }
    }
}
