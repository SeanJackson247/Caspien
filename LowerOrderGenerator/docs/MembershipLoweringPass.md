# MembershipLoweringPass

Reference notes for `MembershipLoweringPass.java` (design, formats and rules). Design history is in [history.md](history.md).

## Overview and placement
Covers: class, `run`, `Worker.process`, `rewriteFunctionBody` dispatch.

- Lowers the membership operators `IN` (range/dynarray shape and guaranteed-enum shape), `WITHIN`, `INSTANCEOF`, `IMPLEMENTS`, plus `IS_BASE`, `IN_SCAN` (`u64 in string`) and `LEN_SCAN` (`len(unsafeDynArr, terminator)`) into ordinary primitives: `GT_EQ`/`LT`/`LT_EQ`/`EQ`, `AND`/`OR`/`NOT`, fixed-offset dotted-name reads. The design decision was to compile these operators out into more primitive instructions.
- Runs once, as a final one-shot stage (`BytecodeOptimizer.optimize`), outside both fixed-point loops, the same placement as `DropGlueGenerationPass`. It is the first pass of the fixed lowering pipeline (`MembershipLoweringPass`, `CloneGenerationPass`, `DropGlueGenerationPass`, `ArgToAllocLoweringPass`, `AddressLoweringPass`), so no symbol table exists yet.
- `rewriteFunctionBody` scans one function's lines; at an operator line it asks the matching `tryRewrite*` for an `InMatch`/`InstanceofMatch`. If the result is null the original bytecode is left alone (the "let the pattern fail rather than guess" fallback used by every unhandled shape).
- `IN`, `WITHIN`, `IN_SCAN`: 4 tokens (`MNEMONIC t1 t2 t3`), two preceding operand pushes (left, then right). `LEN_SCAN`: 3 tokens (target type, terminator type; no result type, the result is always `indeterminate_u64`, hardcoded in `tryRewriteLenScan`), two preceding pushes. `INSTANCEOF`/`IMPLEMENTS`: 2 tokens (`MNEMONIC leftType`), preceded by the left operand's lines and a one-line bare `PUSH target`. `IS_BASE`: 2 tokens (`IS_BASE rangeType`), one preceding operand push.
- Push bookkeeping: each operand push is removed from `out` after the match unless the match kept it (`keepLeftPush`/`keepRightPush`, true when that operand was materialized: its original bytecode is real computation). For two-push operators the right entry is removed first so the left index stays valid. For `INSTANCEOF`/`IMPLEMENTS` the target push is always dropped.
- `labelCounter` is never reset per function (labels must be unique program-wide); it is used by the two scan loops (`newLabel`: `@in_scan_loop_N`, `@len_scan_loop_N`, ...). `tempCounter`/`allocInsertIndex` are reset per function.
- Generated lines are parsed with `PARSER.parse(lines, "<generated-membership-lowering>")`.

## Operand materialization
Covers: simple vs non-simple operands in `tryRewriteIn`, `tryRewriteWithin`, `tryRewriteInScan`, `tryRewriteLenScan`, `tryRewriteInstanceofOrImplements`, `tryRewriteIsBase`, `resolveLeftName`.

- A "simple" operand is a single `PUSH name type` line (variable reference, dotted field access on one, hoisted string id). The replacement text re-pushes it by name as often as it needs.
- A non-simple operand (CALL result, arithmetic, LOOKUP into a non-name, inline struct/range literal ...) ends its emission with whatever instruction produced it. The rewrite relies only on the stack-order guarantee (left emitted before right): it declares a temp (`declareTemp`) and appends `POP temp type` right after the already-computed value. The operand's own bytecode is not touched or relocated (no backward tracing); `keep*Push` is true so the caller leaves it standing.
- `POP name type` is used instead of `ASSIGN` because `ASSIGN` needs the destination address pushed before the value (would require inserting ahead of an unlocated expression start). `AddressLoweringPass`'s `POP` rewrite resolves a real ALLOC-declared local through the same `resolveAddress` lookup as PUSH/ADDR (it used to resolve only call-argument `ARGn` slots).
- Two-operand operators: the right operand is popped first (it is on top). If right is not simple, left is ALWAYS materialized too, without inspecting `outSoFar`'s second-to-last entry: that entry is only left's last line when right is exactly one line; a CALL is a four-line block (`CC_START`/`CALL`/`CC_END`/`PUSH_RET`), so the entry could be mid-way through right's span.
- `INSTANCEOF`/`IMPLEMENTS`: no such risk, since the target is always a single bare `PUSH name`, so `outSoFar.size()-2` is always left's last line. A non-simple left is materialized the same way (`resolveLeftName`), the temp's type being the `leftType` on the operator line.
- The guaranteed-enum shape (`u64 in EnumName`) is not materialized: its right side is a bare type-name label, not a run-time value. Its left must be a simple push.
- `INSTANCEOF`/`IMPLEMENTS` bail-outs that do not depend on the left name (struct `___type` check, class lookup) happen before any temp is declared, so a rewrite that cannot proceed never leaves a stray ALLOC or orphan POP (`declareTemp` mutates `outSoFar` immediately).

## IN shapes
Covers: `tryRewriteIn`, `buildOrChain`, `buildRangeCheck`, `buildDynArrayCheck`, `buildRangeSubsetCheck`, `buildRangeInDynArrayCheck`.

- Shape 1, `u64 in GuaranteedEnumName`: right is a bare `PUSH EnumName` (2 tokens, no type), left a simple push. Lowered by `buildOrChain` to an OR chain of `left == variantValue` per explicit-valued variant.
- Shape 2/3, plain `u64` left (no storage, base type exactly `u64`) against a `range`/`range(...)` or `dynarray(...)` right:
  - range: `x >= range.start && x < range.end` (half-open: start inclusive, end exclusive; matches the `for` desugaring of `x in $range`). Bounds are read as dotted run-time fields, never from the type text, so a bare `range` (parameter, or `range(dynarray)` with run-time length) works like `range(0,5)`.
  - For a compiler-generated `$for_range_*` holder, only `x < range.end` is emitted: the loop counter starts at `range.start`, is immutable in the body and only incremented after `i < end` held, so it cannot wrap and `i >= start` is always true.
  - dynarray: `x < LEN(dynarray)`; the lower bound is always true for a plain `u64` and is not emitted.
  - The plain-`u64` gate exists because `checkIn` also permits a range-typed left; the u64 logic on a 16-byte range produced wrong bytecode.
- Shape 4, range-shaped left (bare `range(...)`, or a pointer to one) against a range or dynarray right:
  - `r1 in r2`: non-strict subset `r1.start >= r2.start && r1.end <= r2.end` (allows `r1 == r2`; `LT_EQ` on the upper bound, unlike the half-open `LT` of a scalar, because a range may exactly reach the other's exclusive end).
  - `r in dynarray`: `r.end <= LEN(dynarray)` (`LT_EQ`: a dynarray of length N has indices 0..N-1 and the range 0..N is exactly the full valid span; `r.start >= 0` is trivially true for a u64 range). Treats the dynarray as an implicit `0..LEN` range.
  - Both sides are dereferenced independently when pointer-wrapped (a range-shaped left can be pointer-wrapped, unlike Shape 2/3's scalar left).
- `u64 in string` is `IN_SCAN` and never reaches `tryRewriteIn`.

## WITHIN
Covers: `tryRewriteWithin`, `buildRangeStrictSubsetCheck`.

- `checkWithin` guarantees both operands are range-shaped (bare `range(...)` or a pointer to one); no dynarray, no scalar `u64` left. So there is a single shape, no dispatch.
- `r1 within r2` = strict subset: `r1.start >= r2.start && r1.end <= r2.end` AND NOT (`r1.start == r2.start && r1.end == r2.end`). Example: `1..2` is within `0..3`, `0..3` is not within `0..3`. Built from `GT_EQ`/`LT_EQ`/`EQ`/`AND`/`NOT`; each dotted bound is re-read fresh each time.
- Operand materialization and per-side dereference are the same as IN's range-vs-range shape. There is no `PTR_WITHIN` mnemonic: a pointer-wrapped operand is detected here by `isGenuinePointer`.
- If either side is not range-shaped, the pass returns null (cannot happen while `checkWithin` holds).

## IS_BASE
Covers: `tryRewriteIsBase`, `buildIsBaseCheck`.

- `match RANGE_PARAM is base{...}`: a reserved match condition (TypeChecker's dedicated `is` case, not a general operator), true when the range's start has reached its end, tested at run time. Lowered to `r.start >= r.end` (`GT_EQ`, not `EQ`: a range that overshot its end still reports base).
- One preceding operand push, no right side. Reuses `InstanceofMatch`, `resolveLeftName`, `isGenuinePointer`, `derefInto`. A pointer-wrapped range (`raw range(...)`) is dereferenced first (`checkIs` only checks `baseType` text).
- `leftType` is read from the `IS_BASE leftType` line (BytecodeEmitter's `is` case), not from the preceding push line: an inline range literal (`match (2..5) is base{...}`) is emitted as two scalar `PUSH n u64` lines with no consolidating instruction, so the preceding line's trailing token is `u64`.
- The range type check accepts both `range` and `range(...)`: a `@recursive` function's final range parameter is declared plain `range` and is pushed as `PUSH r imut_range`. `AddressLoweringPass.pseudoMembersOf` resolves `.start`/`.end` for both spellings.

## IN_SCAN and LEN_SCAN
Covers: `tryRewriteInScan`, `buildStringScanCheck`, `tryRewriteLenScan`, `buildLenScanCheck`, `newLabel`.

- `u64 in string` (`IN_SCAN`) is a real byte-by-byte scan, the first rewrite needing control flow. It is a plain forward scan to the NUL terminator with no `strlen` call and no length known upfront; same semantics and the same single caveat as `strlen` (stops at the first `'\0'`); all comparisons are numeric byte compares.
- `checkIn` guarantees a plain `u64` left (8 bytes, never a pointer) and a right with base type `string`. Operand handling is as in IN Shape 2/3 (right popped first, left materialized whenever right is not simple).
- Per iteration of `buildStringScanCheck`:
  1. Read the byte at `counter` with `LOOKUP` (string base type maps to `LOOKUP_ARRAY` in `AddressLoweringPass.lookupMnemonicFor`). The index is unchecked; `checkLookup`'s proof gate applies only to source-compiled bytecode, and a scan that stops at the first NUL has the same trust model as `strlen`/`strchr`.
  2. Compare the byte against `'\0'` at the 1-byte width both sides share (`imut_char`): `AddressLoweringPass` sizes GT/LT/EQ from the first operand's type, so both pushed values must have equal width. Equal: jump to not-found (push `false`).
  3. Compare the byte widened to `u64` with `ZEXT` (a real 1-to-8 zero-extend) against `leftName`. Match: push `true` and jump to the end.
  4. Otherwise increment `counter` (same `ADDR`/`PUSH`/`INC`/`ASSIGN` shape as `CloneGenerationPass.buildCloneLoop`) and loop.
  The two exits each push a boolean and fall to the same next instruction (stack-machine merge, no phi).
- Dereference of the string: NOT `isGenuinePointer`. A string literal's canonical type is `static_some_imut_string`; `string` is the language's only `pointerLikeBaseType` (`TypeChecker.pointerLikeBaseTypes`), so its value already is the pointer at all five storage keywords, `static` included. Only a further-wrapping storage (`raw`/`ref`, a pointer to a string elsewhere; `checkIn` checks only base type) needs `derefInto`. Condition: `storage != null && !"static".equals(storage)`.
- `len(unsafeDynArr, terminator)` (`LEN_SCAN`): same "no stored length, must scan" situation (an unsafe dynarray is headerless). The terminator is a run-time operand whose base type equals the element base type (`checkLenBuiltin`, 2-arg form), so both comparison sides have equal width: no `ZEXT`. No dereference handling: `TypeInfo`'s unsafe-dynarray constructor forces `owns` storage, so a pointer-wrapped unsafe dynarray target cannot exist. The result is the index at the match (the terminator is not counted): the exit pushes `counter` rather than a boolean. Loop shape otherwise identical to `buildStringScanCheck`; the `LOOKUP`/`LOOKUP_DYN` addressing of unsafe dynarray elements is what makes the ordinary `LOOKUP` correct.
- Temps: counter (`mut_u64`) and an element/char temp. Labels from `newLabel`.

## Pointer-wrapped operands
Covers: `isGenuinePointer`, `derefInto`, and every call site.

- `checkIn`, `checkIs`, and friends only look at `baseType` text, never storage, so `raw range(...)`, `raw dynarray(...)`, `raw string`, `ref`... operands are legal. `buildRangeCheck` etc. read the name directly (`PUSH name.start`, `LEN`) and assume it is the value itself, so a pointer operand is dereferenced first.
- `derefInto(name, type, canonical, ...)`: appends `PUSH name type` / `DEREF derefType` / `POP derefTemp derefType` to `lines` using `declareTemp` and the widened `POP`; returns `{derefName, derefType}`. The temp type is `mutability_baseType` with storage stripped (as in `TypeChecker.checkDerefBuiltin`), so `.start`/`.end`/`.field` resolve in `AddressLoweringPass` like on an ordinary value.
- `DEREF` carries the pointee type operand: a deref is a copy and the byte-precise stack needs the pointee size; the preceding `PUSH` line's width is the pointer's (8). `derefType` is the same value the following `POP` needs.
- `isGenuinePointer(canonical)` = `storage != null && !isOwnsStorage()`, and false for a `ref` to a dynarray: `owns` storage on a dynarray is its own intrinsic handle ("buffer pointer plus bookkeeping", read directly by `LEN`/`LOOKUP_DYN`), not a wrapping indirection; a `ref` to a dynarray is the same block pointer the owner holds, so only `raw` (address of the variable) needs a dereference.
- A range's own storage is always null (`TypeChecker.checkRange`), so the gate makes no difference for range-vs-range, only for range-vs-dynarray.
- The `u64` left of Shape 2/3 is never a pointer (`checkIn` rejects it).

## INSTANCEOF and IMPLEMENTS
Covers: `tryRewriteInstanceofOrImplements`, `resolveLeftName`, `typeFieldPathFor`, `buildValueOrChain`, `buildRangeMembershipCheck`.

- `x instanceof S` (S a struct): one class-id equality test of `x.___type` against S's id from the flat `Class` enum (`enumTable.variantValue("Class", name)`); null (leave alone) if S is not in the enum. `x implements I`: OR chain over the class ids of I's implementers (`implementerClassIdsOf`). `buildValueOrChain` emits `PUSH path imut_u64 / PUSH id indeterminate_u64 / EQ ... / OR`. `buildRangeMembershipCheck` (inclusive `lo <= v <= hi`, as a Class hierarchy range is inclusive on both ends, `TypeChecker.walkClassHierarchy`) is a helper in this file.
- The target must be a bare one-line `PUSH name` (`isBarePush`); `checkInstanceof`/`checkImplementsOperator` require a bare VARREF on the right.
- `leftType` is read from the operator line (`INSTANCEOF leftType`), not from the preceding bytecode: `checkInstanceof` allows an inline struct value (`requiresAliveProof` carve-out), and a non-`new` struct literal (`Point{x=1} instanceof Point`) is emitted by `emitInstantiate` as classId plus members pushed back-to-back, so the preceding line carries the last member's type.
- A non-simple left (e.g. `makeThing() instanceof Foo`, a CALL ending in `PUSH_RET type`) is materialized (see "Operand materialization"). Its result type is the operator line's `leftType`.
- Interface-typed left (`structTable.membersOf` returns null for an interface, which has no STRUCT_MEMBER block) is allowed: the generator just needs a pointer to read `___type` from; every struct has `___type` at offset 0 regardless of the static type used to reach it (`AddressLoweringPass.memberLocOf` resolves `___type` structure-agnostically). `hasTypeField` is a defensive check that a registered struct has `___type`.
- `IMPLEMENTS` with no implementer anywhere in the program (a whole-program fact: every `impl X for Y` is collected before any body is checked) lowers to `PUSH false imut_bool`. The left operand is still consumed (materialized and POPped if non-simple) so its side effects run. It is not an error here; the type checker warns (`checkImplementsOperator`). In that branch the left is not dereferenced. `INSTANCEOF`'s empty-range case is unreachable (every legal target is in the `Class` enum).
- `typeFieldPathFor` builds `leftName.___type`; if `leftType` has ANY storage (plain `canonical.storage != null`, `owns` included), the operand is dereferenced first with `derefInto`. Reason: a struct-returning function can only return a storage-qualified value (owns/ref/raw/auto/static), and `AddressLoweringPass.resolveAddress` bails ("pointer indirection") on any dotted chain whose segment type carries storage, leaving the dotted text unresolved in the final bytecode (`PUSH 8 sAuto.___type` survived the pipeline). Unlike a dynarray, a struct's storage-qualified local is never the inline struct at that address, so `isGenuinePointer`'s `owns` carve-out must not be used here.

## Name tokens
Covers: `isRealNameToken`, `isSimplePush`, `isBarePush`, `hasTypeField`.

- A "simple push" is `PUSH name type` (3 tokens) whose `name` is a real name token; a "bare push" is `PUSH name` (2 tokens). Shape alone cannot distinguish a reference from a literal: an inline range literal `2..5` is emitted as two raw back-to-back scalar pushes, the second of which looks like a one-line `PUSH r type` reference.
- `isRealNameToken(text)`: not empty; not `true`/`false`/`null`; first char a letter, `_` or `$`; remaining chars letters/digits/`_`/`.` (dotted qualified names such as `p.x`, `r.start`). Literal shapes (digits, signed digits, quoted char) fail.
- Syntactic rather than a symbol-table lookup: this pass runs before `AddressLoweringPass` builds the name -> offset table. Every real name `BytecodeEmitter` writes in a PUSH name position (a VARREF, the qualified dotted name from `emitDot`, a hoisted string id, a hidden `$` local such as `$for_range_N`) is an identifier optionally prefixed with `$`; literals never start that way.
- `$` is a real name prefix at this stage: `for x in range{...}` declares and pushes its hidden holder as `PUSH $for_range_1 imut_range(...)`, and `declareTemp` uses `$in_tmp`. `isBarePush` additionally applies the guard for consistency (both call sites also compare against a known enum/class/interface name).

## Temp declaration
Covers: `declareTemp`, `allocInsertIndex`, `rewriteFunctionBody` prologue scan.

- `declareTemp(out, type)` names a temp `$in_tmp<N>` (`tempCounter`, per function), inserts `ALLOC name type` into `out` at `allocInsertIndex` and bumps the index. A `$` name cannot collide with a source identifier (same convention as `EnumTable`'s `$enum_for_`); the temp needs no cleanup, it is an ordinary local.
- `allocInsertIndex` is computed once per function against `body`: the first line index after the header block `FUNC_START, [EXPORT], FUNC_DECORATE*, RETURNS, ARG*, ALLOC*`. `ARG` and `EXPORT` are real header lines at this stage; `ArgToAllocLoweringPass` (later) turns `ARG` into `ALLOC` plus a load sequence and its `lowerFunction` header scan stops at the first non-ARG line, so inserting an ALLOC mid-parameter-list would leave the remaining ARGs unlowered. `body` and `out` stay index-aligned for this scan because nothing before the insertion point is rewritten.

## Generated formulas
Covers: `buildRangeCheck`, `buildDynArrayCheck`, `buildOrChain`, `buildRangeSubsetCheck`, `buildRangeStrictSubsetCheck`, `buildRangeInDynArrayCheck`, `buildValueOrChain`, `buildRangeMembershipCheck`, `buildIsBaseCheck`.

See "IN shapes", "WITHIN", "IS_BASE", "INSTANCEOF and IMPLEMENTS". Conventions: dotted `.start`/`.end` fields are re-read fresh every time (no stack-value reuse across boolean chains); comparison result type is the third operator token (`t3`) on the last emitted instruction; intermediates are `imut_bool`. `buildOrChain`: with a single variant the `EQ` carries the result type; otherwise each `OR` after the first carries it only on the last link.
