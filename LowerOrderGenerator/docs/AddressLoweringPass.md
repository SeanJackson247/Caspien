# AddressLoweringPass

Reference notes for `AddressLoweringPass.java` (design, formats and rules). Design history is in [history.md](history.md).

## Role in the pipeline
Covers: class-level contract, ordering.

The high-order-to-low-order step that erases a named, base-pointer-relative stack slot to its own address and size: `PUSH imut_u64 x.y` becomes `PUSH 8 $32` (8 = size in bytes, `$32` = x.y's location relative to the base pointer). It runs once, as the final stage of the pipeline (`BytecodeOptimizer.optimize`), after every other pass. It needs every ALLOC in the program (including those `CloneGenerationPass`/`DropGlueGenerationPass` just generated) settled before it can assign final offsets.

## Operand conventions
Covers: `$offset` vs literal, signs.

- `offset` is signed. Negative for an ordinary local (stack grows down from the base pointer). Kept signed because a parameter passed on the caller's stack will need a positive offset once the register/stack-argument question is settled; every offset computed today is negative.
- `$` distinguishes stack offsets from literals: an un-prefixed second operand is always a literal/label, never a stack address, and vice versa.

## Mnemonic rewrites
Covers: `tryRewrite` and related per-mnemonic rules (the table in the class Javadoc).

| Input | Output | Notes |
|---|---|---|
| `PUSH name type` | `PUSH size $offset` | named stack slot |
| `PUSH literal type` | `PUSH size literal` | number, `null`, global label such as a hoisted STRING id; literal carried over unchanged |
| `ADDR name type` | `ADDR size $offset` | when `name` resolves |
| `ADDR name type` | `ADDR size name` | when `name` is an `ALLOC_STATIC` or top-level `GLOBAL`: same treatment as an unresolved PUSH. Every real ADDR emission site passes a single bare name, never a dotted chain, so an unresolved ADDR operand is never the "pointer partway through a chain" gap; it just is not a stack local. See `tryRewrite` |
| `NEW TypeName` | `NEW size` | single-operand struct-allocating form (`new Struct{...}`); codegen needs a byte count |
| `NEW_DYN dynarray(T) count` | `NEW_DYN size count` | `dyn([...])`; `size` = `count * elementSize` only, the safe flavour's length header is NOT folded in; `count` kept because the header still has to be written |
| `NEW_UDYN unsafe_dynarray(T) count` | `NEW_UDYN size` | no header, so `count` is not needed downstream |
| `RESIZE dynArrType countType fillType` | `RESIZE size` | element size read off the fill value's type |
| `URESIZE dynArrType countType` | `URESIZE size` | no fill value: element size read off the dynarray operand's declared element type (with a safe-type fallback needed only for `CloneGenerationPass.buildCloneLoop`'s synthesized use against a nominally safe destination) |
| `GT_DESTRUCT name` | `GT_DESTRUCT $offset` | resolved like ADDR/PUSH (bare local or dotted chain for an owns-typed struct member); left untouched when `name` does not resolve. Unlike ADDR it can really be a dotted chain, so an unresolved one may still be the "pointer partway through the chain" gap; destructing an owns-typed global/static was not revisited |
| `RET type` | `RET size` / `RET_FLOAT size` | `RET_FLOAT` for a float return type (`f32` only at the time; see `isFloatBaseType`), `RET` for everything else including void (`RET 0`) |
| `RETURNS type` | dropped | RET/RET_FLOAT carry the byte count; a caller's `PUSH_RET type` still carries the callee's return type in full |
| `LOOKUP targetType indexType returnType` | `LOOKUP_ARRAY size` / `LOOKUP_DYN size` | only the width of the return type is needed; `targetType` and `indexType` dropped |
| `GT/LT/GT_EQ/LT_EQ/EQ/NEQ leftType rightType returnType` | `GT_INT size` / `GT_FLOAT size` / `LT_INT` ... | `leftType` alone decides size and suffix; all six `BytecodeEmitter.COMPARISON_NAMES` covered |
| `AND/OR leftType rightType returnType` | `AND size` / `OR size` | size only, NOT split into `_INT`/`_FLOAT` (the same bitwise machine operation at any width). `leftType` is read (always equal to `rightType`; `emitLogical` only passes same-typed operands) |
| `ADD/SUB/MUL/DIV/MOD leftType rightType returnType` | `ADD_INT size` / `ADD_FLOAT size` ... | `leftType` decides size and suffix |
| `INC/DEC leftType returnType` | `INC_INT/INC_FLOAT/DEC_INT/DEC_FLOAT size` | split for consistency with the comparisons; two-type-operand shape of `emitIncrement`/`emitDecrement`; suffix via `isFloatBaseType`. `DEC` is a mnemonic of the compiler side, produced by the `--` operator mirroring `++` |
| `RECURSIVE_CALL funcName` | `CALL funcName` | bare rename: only ever a compile-time distinction. `AWAIT_CALL`/`PAR_CALL` are deliberately left alone. No corpus fixture emits RECURSIVE_CALL (every legal `@recursive` self-call is rewritten into a loop before BytecodeEmitter), so the rewrite was verified by hand-fed bytecode |
| `EXTERN_CALL name argCount` | `CALL name argCount` | keeps `argCount`: an extern target has no `FUNC_START`/`ARG` lines to recover arity from, and a vararg extern's per-call argument count exists nowhere else |
| `POP ARGn type` | `POP ARGn size` | see below |
| `PUSH_RET type` | `PUSH_RET_INT size` / `PUSH_RET_FLOAT size` | caller-side mirror of RET/RET_FLOAT, same `isFloatBaseType` classification; both suffixes are new (no bare `PUSH_RET` remains). Never emitted for void calls, so no void case |
| `PUSH_FIELDNAME fieldName fieldType` | `PUSH_FIELDNAME memberOffset size` | see below |
| `ALLOC name type` | `ALLOC size` | position in the frame is implicit in order; see "Frame layout" |

Mnemonic split rule: a different runtime meaning gets its own mnemonic (`RET_FLOAT`, `LOOKUP_*`, `_INT`/`_FLOAT` comparisons and arithmetic, `CAST`, `IN`/`IN_SCAN`, `LEN`/`LEN_SCAN`). Do not invent a mnemonic for something an existing one's operands could already say; every other rewrite here only changes operands.

### LOOKUP split
A fixed array, a `string`, or an unsafe dynarray (`unsafe_dynarray(...)`) resolves to `LOOKUP_ARRAY`: one direct `base + index * elementWidth` against the pushed container, no header. A safe `dynarray(...)` resolves to `LOOKUP_DYN`: `base + 8 + index * elementWidth`, because the buffer is preceded by a fixed 8-byte length header (the same 8 bytes `LEN` reads off the pushed value at offset 0; `PUSH arr / LEN` has no operand and needs no lowering, see `BytecodeEmitter`'s "len" case). A dynarray value (safe or unsafe) is a plain 8-byte pointer like every other pointer; it differs only in the `+8` header offset, never in an extra indirection (`resize` callers re-`POP` the returned address into the same variable). `lookupMnemonicFor` holds the addressing detail. `LOOKUP_LHS` gets the same split with `_LHS` appended to the chosen name. The `LOOKUP_SLICE` form no longer exists (slice type removed from the language).

### POP
`POP ARGn type` -> `POP ARGn size`. `ARGn` is a positional label a codegen stage needs verbatim and is left alone. `POP name type` is a general "pop the stack top into a named local": `name` goes through `resolveAddress` like PUSH/ADDR, so an `ALLOC`-declared local resolves to its `$offset` (a later `PUSH name type` resolves to the same address), while `ARGn` is never in `offsets` and stays verbatim. This is the "materialize the stack top into a named local" primitive `MembershipLoweringPass` needs when `IN`'s left operand is not a simple named push (e.g. `(3+4) in r`). `ASSIGN` cannot do that: it needs its destination address pushed before the value, which would mean inserting a line ahead of an already-emitted expression whose start was never located. `POP` needs nothing pushed beforehand. The compiler emits POP only for call-argument transfer (`emitArgWord`/`emitSyntheticArgWord`), not for return values (`PUSH_RET`) or operand cleanup (operators consume their operands implicitly).

### PUSH_FIELDNAME
`size` is `sizes.sizeOf(fieldType)`. `memberOffset` needs to know which struct: it is read from the instruction immediately before (`ADDR`/`PUSH`/`LOOKUP`/`PUSH_RET`/a prior `DOT`), whose trailing operand always names the type of the value it left on the stack (every instruction states the type of what it leaves). It is read in ORIGINAL, not-yet-lowered form (`run()`'s `previousLine`) since a lowered predecessor has lost the struct-name text. Left untouched if there is no previous line or its base type is not a real struct (flag the gap, do not guess).

## Frame layout
Covers: the ALLOC pre-walk in `run()`, `totalAllocSize`, alignment.

- Every ALLOC of a function (params first, then locals, the order they are emitted in) gets a negative offset from the base pointer, each slot immediately below the last.
- Alignment: `rbp` is always 16-aligned (the frame total is rounded to 16 and every caller's `call` lands 16-aligned per SysV), and no type here needs more than 8, so a variable is correctly aligned exactly when `cursor` (<= 0) is a multiple of its alignment. As `cursor` grows more negative, "round up" becomes "round DOWN to the next multiple" (the more negative neighbour), which leaves the gap above the new slot. Gaps are never assigned to a variable; they are absorbed into `totalAllocSize`.
- Same name declared again in a sibling scope (e.g. `let key` as u64 in one loop, u8 in the next): one slot per NAME, every use goes to the slot recorded last. A later narrower declaration must not shrink it, or the earlier scope's wider stores would spill into neighbouring slots. A repeat reuses the existing slot when it is at least as big and as aligned, otherwise takes a fresh, bigger one.
- All `ALLOC name type` lines collapse into ONE `ALLOC totalAllocSize` (`totalAllocSize = -cursor`, then rounded UP to a multiple of 16 so rsp stays aligned for the whole body; never down, which could truncate a slot), emitted in place of the first ALLOC reached (matched on exactly 3 tokens, never `ALLOC_STATIC`); later ALLOC lines are dropped. Individual offsets are independent of how many ALLOC lines survive. `ALLOC_STATIC`/`GLOBAL` are separately addressed memory, not frame slots.
- Not done: a whole-frame reservation instruction that also accounts for argument slots is deferred until the ARG-to-ALLOC register-vs-stack-argument question is settled, so a parameter's slot is not re-derived twice.
- `previousLine` is updated to each original ALLOC line (its trailing type is the text PUSH_FIELDNAME may read), but NOT for dropped RETURNS / `*_DECORATE` lines.
- `resolveArgWordSlots` lays out all `PUSH ARGn`/`PUSH FARGn` words of a function up front (an int word and a float word can be interleaved in one parameter list, so a word cannot be resolved in isolation); `resolveConvention` reads `@call_convention(...)` off the FUNC_DECORATE lines first.

## Name resolution
Covers: `resolveAddress`, dotted chains, pointer bail.

A `name` can be a plain local/parameter (every parameter gets its own ALLOC) or a dotted chain rooted at one (`x.y`, `x.y.z`), resolved by walking the declared member layout (ordinary struct member order, or a range's fixed two-word shape) and summing each step's byte offset.

A chain through a genuine pointer (e.g. `p.inner` with `p` an auto/ref/raw value) cannot have a compile-time offset: the pointee lives at a run-time address and needs a real dereference. The pass bails and leaves the line untouched. This is now only a defensive fallback: `BytecodeEmitter.isQualifiedNameableDot` checks at EVERY level of a `.`-chain whether that level's base has storage and routes any failing chain through the uncollapsed `PUSH / PUSH_FIELDNAME / DOT or DOT_LHS + DEREF` shape (as for a call result's `x().y`), one hop at a time. See the compiler's CLAUDE.md.

## ADDR_OF
Covers: the drop rule in `run()`.

`ADDR_OF opText leftType returnType` is dropped (a no-op retag) in exactly two cases: `ADDR_OF REF` on an existing owns variable (the pushed value already is the pointer; `ref` borrows it) and `ADDR_OF RAW` on a string literal (already hoisted to static memory by the TypeChecker's `case STRING:`). The test uses `opText` ("REF" vs "RAW") plus the previous line's storage (`isAlreadyCorrectPointerRetag`). It must not drop `raw val` / `auto val` where `val` is an ordinary pointer-typed local (e.g. `let val: raw mut u8 = null`; `memcopy(raw val, mut 8, slot)`): there the ADDRESS of the slot must be computed, not the pointer value inside it. Dropping it left a `null` destination for `MEMCOPY` (segfault). `previousLine` is still updated after the drop.

## Dropped declarations
Covers: top-level and in-function metadata lines.

- `STRUCT_DECORATE`, and in functions `FUNC_DECORATE`/`LOOP_DECORATE`/`FOR_DECORATE`, are dropped: purely declarative, already consumed upstream (`resolveConvention` reads `@call_convention(...)` off `lines`, not `out`). A real consumer would read them from the AST/TypeChecker side. `previousLine` is left unchanged for these (never a value-producing instruction a later PUSH_FIELDNAME/ADDR_OF lookup should see).
- `STRUCT_START/STRUCT_MEMBER/STRUCT_END` and `ENUM` (user-declared and synthesized, `Class`, `$enum_for_...`) are dropped: struct layouts were read once up front via `StructTable.read(lines)` and baked into the surviving instructions (sizes via `SizeCalculator.structSizeOf`, member offsets via PUSH_FIELDNAME/`memberLocOf`); enum variant values are resolved via `resolveLiteralOrEnumValue` from `EnumTable.read(lines)`; `Class` ids and `$enum_for_...` lists were consumed by the earlier `MembershipLoweringPass`; the hidden `___type` id is always a plain integer literal pushed by `BytecodeEmitter.emitStruct`. This is the last pass, so nothing later needs them.
- A top-level `GLOBAL` is rewritten by `rewriteStaticOrGlobalDecl`; `ALLOC_STATIC` is handled inside functions by `tryRewrite`.
- `REGVAR name weight [f]` (from `RegVarHintPass`) becomes `REGHINT offset size weight [f]` using the same layout; `RegisterFormPass` consumes it, `LowerOrderGenerator` strips leftovers; an unknown name drops the hint.

## Finished and untouched mnemonics
Covers: what needs no rewrite.

- `CALL name`: already carries only a bare target label; nothing to do. `PUSH_LABEL name code_addr`: emitted only from `BytecodeEmitter.emitGtRoutineBody` (the `gt_routine` prologue); `code_addr` is a pseudo-type with no size and the label is the point. Both are finished.
- Finished with their own sections elsewhere in this pass: `ADDR_OF`, `MEMCOPY`, `NEW_FROM_STRING`, `SEXT`/`ZEXT`, `LOOKUP_LHS`.
- `WITHIN` (like `IN`) never reaches this pass: `MembershipLoweringPass` lowers both into ordinary comparison/logic primitives earlier. `GT_LOOP`/`CLONE_LOOP` never reach it either: `DropGlueGenerationPass`/`CloneGenerationPass` generate ordinary JMP/CMP loops inline.
- Every other mnemonic not touched is left exactly as is.

## Struct and array literal stores
Covers: `findStructLiteralAssigns`, `findArrayLiteralAssigns` (in `run()`'s call sites), `splitValueUnits`, `layoutTokens`.

- A stack struct literal's store is `ADDR v T`, `PUSH <digits> imut_u64` (the ___type id), member values, then `ASSIGN T T T`. A whole-value copy (`ADDR v T`, `PUSH w T`, `ASSIGN T T T`) has no type-id push and is not matched. An RVO store goes through `PUSH $ret_dest raw_mut_S`, with the type id a literal or, in a generated clone routine, `<src>.___type`. An `@untyped` struct has no class id, so its literal starts with the first member value (the ASSIGN confirms; a one-push whole copy is excluded).
- A matched ASSIGN (rewritten size 4) gets the struct's physical layout appended (`m<bytes>` member, `p<bytes>` padding, `a<elemBytes>x<n>` element-wise array of scalars narrower than 8 bytes; `NEW` also carries it) so Codegen can repack the pushed field words when the values are not all plain pushes (`X86Backend.emitAssignRepack`); Codegen ignores it on its packed fast path.
- Heap literals (`NEW`) use the same element-wise handling (`findNewRunStart`).
- A stack array literal of scalar elements narrower than 8 bytes (`let a: u16[3] = [c, c + 1, 7]`): each element is pushed as its own word; if one is computed, the ASSIGN gets an `a<elemBytes>x<n>` token. Not added when every element is a plain PUSH or any value is a whole copy of the array type.
- `splitValueUnits` simulates the operand stack over the lines between the literal start and ASSIGN/NEW (pushes add, binary ops merge two, calls leave one at PUSH_RET, a nested heap literal collapses at its NEW) and returns the producing line of each top-level value, or null if a line is not understood (then counting is the fallback). `layoutTokens` decides per array member whether its value is a block (producing line has an array type) or n element values; the counting fallback is decisive only when no array type is shared by two members, otherwise the literal is rejected.

## Per-mnemonic rewrites

Covers: `tryRewrite` (in-function lines). Every rewrite erases type operands down to byte sizes via `SizeCalculator.sizeOf`; where two runtime meanings differ, the mnemonic is split instead. A line that matches no rule (or an `ADDR` whose name does not resolve) returns null and is left alone.

- `ALLOC name type` -> `ALLOC size` (always; `run` already read every ALLOC to build `offsets`). `ADDR name type` -> `ADDR size $offset` when `name` resolves. `PUSH x type` -> `PUSH size $offset` when `x` is a stack name, else `PUSH size x` (literal, `null`, global label...). `ASSIGN t t t` -> `ASSIGN s s s`; `ATOMIC_ASSIGN` gets the same treatment and keeps its own mnemonic so codegen can tell it from an ordinary store.
- `RET type` -> `RET size` or `RET_FLOAT size` (integer vs float return register; `isFloatBaseType` is true for f32/f64 and is used only for this and the float splits below).
- `PUSH_RET type` -> `PUSH_RET_INT size` / `PUSH_RET_FLOAT size`: the caller-side mirror of `RET`, emitted after `CC_END` (see `BytecodeEmitter.emitCallSequence`). Both suffixes are unconditional (everything that is not a float is an int). Never emitted for a void call.
- `NEW TypeName` (single operand) -> `NEW size` plus the layout descriptor (next section). `dyn([...])` allocations are the distinct mnemonics below.
- `NEW_UDYN typeText count` -> `NEW_UDYN count*elemSize`. An unsafe dynarray has no length header, so the size is the whole contract and `count` is dropped.
- `NEW_DYN typeText count` -> `NEW_DYN size count`, `size = count*elemSize` WITHOUT the length header (the allocator adds it). `count` stays on the line because the runtime must write it into the header; it is not re-derived from `size`.
- `RESIZE dynArrType countType fillType` -> `RESIZE size`: `countType` is always `u64` and `dynArrType` always an 8-byte pointer, so only the fill type matters; it equals the element type, so its size is the element size (`RESIZE 8` for a u64 fill). A pointer fill (owns/ref/raw/auto) is one 8-byte element whatever it points to: size the canonical type, not the base.
- `URESIZE dynArrType countType` -> `URESIZE elemSize`. There is no fill value, so `dynArrType` is the only source of the element size. The unsafe element type is tried first, then the safe dynarray one: `CloneGenerationPass.buildCloneLoop` synthesizes this 2-operand shape against a safe `owns_mut_dynarray(...)` destination (growing an empty clone to the source length; safe because the loop overwrites every slot before any read).
- Comparisons `GT/LT/GT_EQ/LT_EQ/EQ/NEQ l r ret` -> `<op>_INT|<op>_FLOAT size`, size and kind taken from the LEFT operand only. Signed integer operands (s8..s64) get an `S` prefix (`SGT_INT`), except `EQ`/`NEQ`. All six `BytecodeEmitter.COMPARISON_NAMES` are covered.
- `AND`/`OR l r ret` -> `AND|OR size`: width-independent bitwise op, no int/float split; operands are always same-typed (`emitLogical`).
- `SHL/SHR/ROTL/ROTR/BITS_OR/BITS_AND/BITS_XOR l r ret` -> mnemonic + size of the left operand (same type rules as `checkBitsBuiltin`: same integer type, never float). A signed-left `SHR` becomes `SAR`. A one-operand `BITS_AND 8` made later by `StrengthReductionPass` is the same instruction at size 8.
- `BITS_NOT l ret` -> `BITS_NOT size`: complement of the low `size` bytes (backend re-truncates). Distinct from the logical `NOT`.
- `NOT l ret` -> `NOT size` (bool or integer only, `checkLogicalNot`; result type is dropped).
- `CLONE argType ret` -> `CLONE size`, see the CLONE section.

## NEW layout descriptor

Covers: `NEW` in `tryRewrite`, `layoutFromUnits`, `narrowElementToken`, `elementwiseArrayToken`, `findNewRunStart`, `countArrayValueLines`.

`NEW size` is followed by one token per `StructTable` layout entry in declared order: `m<bytes>` for a member, `p<bytes>` for an alignment-padding gap. Codegen's packed fast path ignores it; it is used when the construction's field pushes cannot be proven to be one clean run (a field that is an expression, a call, a moved `owns` variable, a nested `new`/`dyn(...)`), to repack the blindly pushed words into the struct's real layout.

- `layoutFromUnits` appends tokens for one struct layout, consuming the literal's top-level value units (`u[0]` is the next unit); false means the units do not fit and the caller falls back to counting.
- `layoutFromUnits` also handles each plain (no storage) value (scalar, fixed array or inline struct) itself, with an explicit to-do stack instead of recursion (12 Oct; the former `valueFromUnits`). A value whose producer already has exactly this type is one block (`m<size>`). Otherwise an array is its elements one after another (a narrow scalar array is the single token `a<elemBytes>x<n>`, n words) and a struct literal is its own layout (type id, members, padding), nested to any depth.
- `narrowElementToken`: `a<elemBytes>x<n>` if the element is a plain scalar narrower than 8 bytes, else null.
- `elementwiseArrayToken` (fallback when values cannot be split): a member that is a fixed array of scalars narrower than 8 bytes written element by element (each element its own 8-byte word, n words) gets `a<elemBytes>x<n>`. A member pushed as one block (an existing array variable) takes ceil(size/8) words and keeps `m<size>`. The shapes are told apart by whether any array-typed value line of that array type occurs in the literal; if one does, every member of that type keeps `m`. A mix among same-typed members throws "cannot tell how the array members of type ... are written ... write them all the same way".
- `findNewRunStart`: index of the line just before a heap struct literal's own type-id push (`PUSH <digits> imut_u64`) for the `NEW` at `newIdx`, walking back and skipping nested literals; -1 if not found in `[start, newIdx)`. With a known class id only a nested literal of the same struct is counted and only a push with the matching id closes the run; type-id pushes of inline struct literals (array elements, inline members) are skipped.
- `classIdOf` = the value of the struct's `___type` push (variant of the `Class` enum), null if not listed. `countArrayValueLines` counts, per array base type, lines in `(from, to)` whose last token is an array type (a whole array pushed as one block).

## Calling convention and parameter words

Covers: `resolveConvention`, `parseArgIndex`, `parseFargIndex`, `ArgWordSlot`, `resolveArgWordSlots`, the `PUSH ARGn` handling.

- `resolveConvention`: the function's convention is its `FUNC_DECORATE @call_convention(name)` line (echoed verbatim by `BytecodeEmitter.emitDecorators`), else `config.defaultConvention`. The pass reads the same `compiler.config` as the compiler side (`TypeChecker.resolveCallConvention`); the compiler does not pre-resolve register counts or shadow-stack decisions.
- `PUSH ARGn type` / `PUSH FARGn type` is emitted once per parameter word by `emitParamLoads`, with no register-vs-stack distinction. This pass decides: a register word is left untouched (codegen needs to know which register `ARGn` names); a stack word becomes a positive `$N`, the same shape as a local but positive because an incoming stack argument is on the other side of the base pointer: `N = 16 + convention.shadowStack + stackOrdinal*8` (16 = saved base pointer + return address). A call-site `POP ARGn` is a different mnemonic and never reaches the parsers.
- `parseArgIndex`/`parseFargIndex`: hand-scanned (no regex), return -1 unless the token is exactly `ARG`/`FARG` plus digits; `FARG3` does not start with `ARG`, so a token matches at most one.
- `resolveArgWordSlots` computes every word's `ArgWordSlot` (register index or stack offset) once per function, keyed by token text (`ARG2`, `FARG1`), in declared order. It cannot be decided line by line: int and float words interleave, and which register a word gets depends on how many earlier words of its bank (or of any bank for a `sharedArgumentPosition` convention) precede it. Overflow words of either bank share ONE stack ordinal (`stackWordsSoFar`), 8 bytes each, left to right, so mixed overflow comes out right. For a shared-position convention the register index is the whole-list counter (the raw digit that `ArgToAllocLoweringPass` already puts on the token); other conventions use per-bank `intIdx`/`floatIdx`.

## Static and global declarations

Covers: `rewriteStaticOrGlobalDecl`.

`ALLOC_STATIC|GLOBAL name type` (container line of a struct/array/range static) -> `... name size`; `... name type value` (scalar static, or one per-field/per-element leaf such as `p.x mut_u64 12`, `p.___type imut_u64 0`, `arr.0 mut_u64 1`, `r.start imut_u64 0`) -> `... name size value`. The name is never touched: a static's name and every per-field/element name is a real addressable symbol, not a stack formula. `sizes.sizeOf` already handles scalars, structs, fixed arrays and ranges (fixed 16 bytes). The value goes through `resolveLiteralOrEnumValue` (`true`/`false` -> `1`/`0`, `EnumName.Variant` -> its value), the only other place a literal appears in the format. It is a standalone method because `run` calls `tryRewrite` only inside `FUNC_START`/`FUNC_END`, and a top-level `GLOBAL` is outside every function; `run` calls this helper directly for top-level lines too.

## LOOKUP addressing shapes

Covers: `lookupMnemonicFor`, `LOOKUP`, `LOOKUP_LHS` in `tryRewrite`.

- `LOOKUP target index ret` -> `LOOKUP_ARRAY size` or `LOOKUP_DYN size`, size = width of the return type; `index` and `target` are dropped. `LOOKUP_ARRAY` covers a fixed array, a `string` and an UNSAFE dynarray: direct `base + index*width`, no header (`checkLenBuiltin`: an unsafe dynarray has no hidden length). `LOOKUP_DYN` is a safe dynarray, whose buffer has a header in front so a fixed offset is added before the multiply (the same header bare `LEN` reads at offset 0 of the pushed value; the code adds the offset in later stages, currently a 16-byte `[len][cap]` header per CLAUDE.md).
- A dynarray value, safe or unsafe, is a single plain 8-byte pointer to the buffer, never a pointer to a handle slot; the two flavours differ only in the header.
- Classification uses `CanonicalType.parse(targetType).baseType` (storage prefixes such as a pointer to an array do not change the shape). An unrecognised base type returns null and the line is left untouched (the compiler's `checkLookup` only permits string, array or dynarray targets, but the pass does not rely on that).
- Extra tokens on `LOOKUP_ARRAY`: `t8` when the target is a by-value fixed array of exactly 8 bytes (pushed as one plain word that Codegen cannot tell from a pointer; smaller arrays are tagged when pushed), and `ra` when the element is itself a by-value fixed array smaller than 8 bytes (Codegen tags it for the next lookup).
- `LOOKUP_LHS` -> `LOOKUP_ARRAY_LHS|LOOKUP_DYN_LHS size`: the same classification with `_LHS` appended. It stays a separate name because `LOOKUP` leaves a VALUE and `LOOKUP_LHS` an ADDRESS for the next `ASSIGN`, the same split as `DOT`/`DOT_LHS`. Example: `LOOKUP_LHS mut_u64[6] indeterminate_u64 mut_u64` -> `LOOKUP_ARRAY_LHS 8` (`x[0] = 42`).

## STACK_LOCK

Covers: `STACK_LOCK` in `tryRewrite`.

`STACK_LOCK structName` (the one allowed One-True-Construction violation, `BytecodeEmitter.emitInstantiate`'s `isLockViolationConstruct`: every member except the lock/discriminant field is omitted) -> `STACK_LOCK size`. Only the class id (if any) and the lock field's value are pushed before the line; the instruction reserves/zeroes the rest. So `size = sizeOf(struct) - 8 (only if the first STRUCT_MEMBER is ___type; not for an @untyped struct) - 8 (the lock word)`. The lock word is always one 8-byte enum word (`checkOtcLockViolation` requires a bare `EnumName.Variant`, enums have no payload). The lock field's name is not recoverable here (`StructTable` is built from `STRUCT_MEMBER` lines and `emitDecorators` does not serialize the lock field name into `STRUCT_DECORATE @lock`), and is not needed: the formula depends only on what is already on the stack. `sizeOf(struct)` is the padded total, so padding among or after the omitted members is reserved correctly. An operand that is already digits is a padding gap emitted by `emitInstantiate` for an ordinary construction and is already lowered: the line is returned unchanged.

## CLONE

Covers: `CLONE` in `tryRewrite`.

`CLONE argType ret` -> `CLONE size`, where size is the POINTEE's size: `argType` is a pointer (`checkCloneBuiltin` requires storage) and `sizeOf` treats every pointer as 8 bytes, so the storage is stripped first (as `PUSH_FIELDNAME`'s struct-type extraction does). A pointee that is a safe dynarray becomes `CLONE_DYN elemSize` (a 16-byte header plus length*elemSize bytes; the size is known only at run time). Only flat (no owns content) pointees reach this rule: `CloneGenerationPass` runs earlier and rewrites owns-bearing pointees into `CALL`/`PUSH_RET` or a generated loop.

## Pointer retags

Covers: `isAlreadyCorrectPointerRetag`.

An `ADDR_OF` is dropped as a no-op retag in exactly two cases: `REF` whose preceding storage is `owns` (re-aliasing an existing owns value computes no address; the text is checked rather than assumed), and `RAW` whose preceding storage is exactly `static` (an already-hoisted string literal, `static_some_imut_string`; other `static` locals are not matched).

Every rewrite below reads an HOB line (`mnemonic operands...`) and returns a replacement line in low-order form, or `null` to leave the line untouched ("flag the gap, don't guess"). Type operands are erased to byte sizes via `SizeCalculator`.

## Size-erasing rewrites
Covers: DEREF, NEG, SEXT/ZEXT/TRUNC/FCONV, ATOMIC_SWAP, ADD/SUB/MUL/DIV/MOD, INC/DEC.

- `DEREF pointeeType` -> `DEREF size`. The pointee type is carried on the DEREF line itself (`BytecodeEmitter` "deref" case emits `op.resolvedType`, the type resolved by `checkDerefBuiltin`; `MembershipLoweringPass.derefInto` synthesizes matching lines). It is deliberately not inferred from the preceding `PUSH`, whose pointer type is always 8 bytes; this mirrors `CLONE`'s pointee operand. `sizes.sizeOf` is used directly (no explicit storage stripping) because it handles both storage-free operands (the `deref()` builtin, `derefInto` temps) and storage-bearing ones (`BytecodeEmitter.emitDot` reading a pointer-typed field's own 8 bytes).
- `NEG l ret` -> `NEG size` (leftType only). `checkUnaryMinus` maps unsigned to the signed type of identical width, so left and return always have the same byte size. A float operand gives `NEG_FLOAT size` (sign-bit flip); integer NEG would negate the IEEE-754 bit pattern.
- `SEXT|ZEXT|TRUNC|FCONV l ret` -> mnemonic + `sourceSize destSize`. Both sizes are kept because they genuinely differ (e.g. `SEXT mut_s8 mut_s32` is a 1-to-4 byte widen; `ZEXT mut_u8 mut_u32`). `checkAs` requires target width strictly greater for widening.
- `ATOMIC_SWAP l r ret` -> `ATOMIC_SWAP size`, size read from the LAST operand. All three share one base type, but `leftType` is "mut_atomic_char"-style: `TypeInfo.canonical()` adds an `atomic_` segment that `CanonicalType.parse` does not know, so the base would parse as `atomic_char` and size as the generic 8. `rightType`/`returnType` are fresh non-atomic types ("indeterminate_char", size 1).
- `ADD|SUB|MUL|DIV|MOD l r ret` -> `<op>_INT size` or `<op>_FLOAT size`, from leftType only (both operands share one resolved type). Signed integer DIV/MOD get an `S` prefix (`SDIV_INT`, `SMOD_INT`: idiv, not div). The `_INT/_FLOAT` split is for consistency/future-proofing, whether or not a machine needs it.
- `INC|DEC l ret` -> `INC_INT|INC_FLOAT|DEC_INT|DEC_FLOAT size` (same split; `DEC` is emitted by `BytecodeEmitter.emitDecrement`, same shape as `emitIncrement`).

## Call rewrites
Covers: RECURSIVE_CALL, EXTERN_CALL.

- `RECURSIVE_CALL f` -> `CALL f`. The distinction was only compile-time bookkeeping (recursion legality, gt-reachability redirection; see `BytecodeEmitter.emitCall`); the runtime mechanism is the same label jump. `AWAIT_CALL` and `PAR_CALL` are NOT folded: they need a different runtime mechanism (async orchestration).
- `EXTERN_CALL name argCount` -> `CALL name argCount`. `argCount` is kept: an extern has no `ARG` lines, only one `EXTERN name returnType paramType... [VARARGS]` declaration (`BytecodeEmitter.emitExtern`). For non-vararg externs the count is derivable from it (cross-referencing and dropping it is not done); for varargs (`printf`) the declaration is only a prefix and the call-site count is the only place the real count lives.

## POP
`POP name type` -> `POP $offset size` for an ALLOC-declared local (same `resolveAddress` lookup as PUSH/ADDR). `POP ARGn size` keeps `ARGn` verbatim (calling-convention shape from `emitArgWord`/`emitSyntheticArgWord`; never ALLOC-declared, so `resolveAddress` misses and the verbatim fallback applies, as for `PUSH ARGn type`). See the pass header, "Parameter word classification".

## PUSH_FIELDNAME and DOT
Covers: PUSH_FIELDNAME, DOT, DOT_LHS.

- `PUSH_FIELDNAME fieldName fieldType` -> `PUSH_FIELDNAME memberOffset size`. The owning struct is found from the PREVIOUS instruction (`ADDR`/`PUSH`/`LOOKUP`/`PUSH_RET`/a prior `DOT`), whose trailing operand is the type of the value left on the stack. That predecessor is read in its original, not-yet-lowered form (`previousLine`, captured by `run()` before it is erased to a size). `memberLocOf` does the lookup. Returns `null` (line untouched) if there is no/empty previous line or `memberLocOf` does not know the struct.
- `DOT|DOT_LHS leftType returnType returnType` -> `DOT size` / `DOT_LHS size`. Always follows a lowered `PUSH_FIELDNAME` (`emitDot` / `emitAssignTarget`). `leftType` is the whole struct's type and is not needed: the read size is the field's (`returnType`, repeated twice by `BytecodeEmitter`). A `DOT` of a by-value fixed-array field of at most 8 bytes gets an extra token `ra`: Codegen assembles it from the one or two words it occupies and tags the pushed word as a small array for the next lookup.

## ADDR_OF
`ADDR_OF opText l ret` -> `ADDR_OF opText $address`. Reached only when the previous line's trailing type has no storage yet (a plain value, i.e. `auto x` / addressable `raw x`; REF and storage-bearing RAW were dropped earlier in `run()`). `emitAddressOf` already emitted the operand; for an addressable lvalue (`isAddressableLvalue`) that is a single `PUSH qualifiedDotName type` (a bare `x` and a chain `p.x` both collapse to one PUSH of the dot-joined name, per `isQualifiedNameableDot`/`qualifiedDotName`). So the previous line is a not-yet-lowered `PUSH name type` (or `ATOMIC_PUSH`, size 3) and its name is resolved via `resolveAddress`. `opText` (AUTO/RAW) is kept; the types are dropped. Untouched if the previous line has another shape or the name does not resolve.

## MEMCOPY and NEW_FROM_STRING
Covers: MEMCOPY, NEW_FROM_STRING, NEW_FROM_USTRING.

- `MEMCOPY dest count src ret` -> bare `MEMCOPY`. Unlike the other size-erasing rewrites, no operand can vary: `checkMemcopyBuiltin` requires `raw` dest, any-storage source (every pointer is 8 bytes), return = dest type, and a plain `u64` count (a narrower count is rejected: "'memcopy''s byte count must be a plain 'u64'"). All four are always 8 bytes, so keeping "MEMCOPY 8 8 8 8" would be dead text. It must remain an instruction (real work at run time). Contract: three word-sized values are already on the stack (dest, count, source, pushed by `emitExpr` in that order); copy `count` bytes from source to dest and leave dest as the result.
- `NEW_FROM_STRING dynType srcType` -> bare `NEW_FROM_STRING`. `checkDynBuiltinCore`'s string branch fixes the element type to `imut char` and any dynarray handle is 8 bytes; the source is `string`-based and always has storage (a storage-less `string` is a type error), so 8 bytes. Contract: pop one string value, build a fresh `dynarray(char)` handle, leave it. If the target type contains `unsafe_dynarray(` the mnemonic becomes `NEW_FROM_USTRING` (an unsafe dynarray is headerless; see Codegen `NEW_UDYN`).

## GT_DESTRUCT
`GT_DESTRUCT name` -> `GT_DESTRUCT $offset`. `name` is a bare local or a dotted chain (`qualifiedDotName`, an owns-typed struct member), resolved like ADDR/PUSH. Untouched if unresolved (a dotted chain through a real pointer).

## PUSH / ADDR tail
Covers: the generic end of the rewrite method, including ATOMIC_PUSH.

- `ALLOC_STATIC`/`GLOBAL` lines go to `rewriteStaticOrGlobalDecl` (a separate method because `run()` needs the same rewrite for top-level `GLOBAL` lines).
- In-function `ALLOC` never reaches this method: `run()` collapses them into one combined `ALLOC totalAllocSize`.
- `ATOMIC_PUSH name type` is handled like `PUSH`; rebuilding lines from the `mnemonic` variable keeps the `ATOMIC_` prefix.
- Result shape: `PUSH|ADDR|ATOMIC_PUSH size operand`.
- A `PUSH`/`ATOMIC_PUSH` whose operand is a parameter word with an `argWordSlots` entry: stack-passed gives `mnemonic size $stackOffset`; register-passed rewrites the digit to the per-bank register index (`ARGn` or `FARGn` with `slot.registerIndex`). For a shared-position convention (win64) that is a no-op; for independent-counting conventions (SysV/arm64) the raw digit is only the uniformly incrementing word position, not the per-bank index, once int and float words interleave. See `resolveArgWordSlots`.
- Operand when `resolveAddress` succeeds: `$address`. Otherwise the name is not a stack local: ADDR is only ever given a bare name (struct field / element assignment targets use `DOT_LHS`/`LOOKUP_LHS`; `emitDotLhsRoot` routes a struct root through a bare `ADDR name type`), so the unresolved case is a function-local `ALLOC_STATIC` or top-level `GLOBAL` symbol. It is carried over, reordered after the size (`ADDR size name`, mirroring `PUSH size name`), with boolean and enum-variant literals resolved by `resolveLiteralOrEnumValue`. Codegen discriminates stack-slot vs symbol; the `offsets` lookup here does so on this side.

## Literal and enum resolution
Covers: `resolveLiteralOrEnumValue`.
`true`/`false` become `1`/`0`. `EnumName.Variant` becomes the variant's integer value (`EnumTable.variantValue`: explicit for value-valued enums, sequential 0,1,2.. otherwise). Checked in that order, at the one place such literals can still be plain text (the PUSH/ADDR/ATOMIC_PUSH carry-over fallback, and static/global literal initialisers in `rewriteStaticOrGlobalDecl`). The enum check is safe to try on any dotted name: `variantValue` returns null unless the leading segment is a tracked enum, so an unresolved struct-field chain passes through unchanged.

## Address resolution
Covers: `resolveAddress`, `memberLocOf`, `walkLayoutFor`, `pseudoLayoutOf`, `MemberLoc`.

- `resolveAddress(chain)` walks `x`, `x.y`, `x.y.z` to a flat byte offset from the function's base pointer. Returns null for an unknown root, an unknown member, or a pointer (storage) partway through the chain (defensive fallback; not expected to fire any more). A numeric segment into a fixed array (`name.hs.1.w`, as written by the drop/clone glue passes) adds element stride * index.
- `memberLocOf(baseType, member)` returns the member's offset and declared type. It walks `StructTable.LayoutEntry` (not the plain `Member` list) because `BytecodeEmitter.emitStruct` bakes natural-alignment `STRUCT_PADDING n` entries into the declared order; a padding entry has no name and just adds its byte count. `___type` is special-cased to `(0, imut_u64)` (every non-`@untyped` struct's hidden class id is its first member), so `MembershipLoweringPass`'s `leftName.___type` (from `instanceof`/`implements`) also resolves when the static type is an interface, which has no STRUCT_START/STRUCT_MEMBER layout.
- `pseudoLayoutOf`: `range` / `range(...)` has a synthetic layout of two `indeterminate_u64` members `start`, `end` with no gap (see `TypeChecker.checkRange`, the range-literal ASSIGN order in `BytecodeEmitter`); everything else uses `structTable.layoutOf`.

## SizeCalculator
Covers: `sizeOf`, `sizeOfBaseType`, `structSizeOf`, `alignOf`, `alignOfBaseType`, `structAlignOf`.

- Sizes come only from canonical type text and the `StructTable` (no dependency on the compiler project). Any storage-bearing type (any pointer) is 8 bytes. A bare struct name known to the table is used whole before parsing (a generic mangled name such as `HashMapEntry_u64` would otherwise parse as mutability `HashMapEntry` + base `u64` and silently size as 8).
- Base sizes: u8/s8/bool/char 1; u16/s16 2; u32/s32/f32 4; u64/s64/f64/code_addr/string 8; void 0; dynarray 8 (one handle); fixed array = length * element size (unknown length 8); range 16; struct = sum over its padded layout (members + padding entries); unknown = 8 (conservative fallback). `bool` is 1 byte, matching `TypeChecker.PRIMITIVE_SIZE`; a real ABI may want a wider bool in registers/stack (revisit once codegen needs it).
- Struct size/alignment are memoised; the cache is seeded before descending (struct types form a DAG; same guard as `StructTable.isOwnsBearing`). If a struct has no layout (should not happen: `StructTable.read` fills `layouts` and `structs` together) the size falls back to the plain member sum.
- Alignment mirrors the compiler side's `BytecodeEmitter.layoutSizeAndAlignOf` / `layoutSizeAndAlignOfBaseType` (an independent duplicate): equals size for leaves, void 1, fixed array = element alignment, range 8, dynarray 8, struct = max member alignment (hidden classId included as an 8-aligned member), unknown 8. Used by the per-function offset loop, which rounds each local's offset to it.
