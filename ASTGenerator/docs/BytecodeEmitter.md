# BytecodeEmitter

Reference notes for `BytecodeEmitter.java` (design, formats and rules). Design history is in [history.md](history.md).

Note: there is no recursion in the package. Names like `emitExpr`/`emitBlock`/`emitStatement` below are frames (`...Frame` factory plus `step...` method) run by `EmitDriver`; see the package README.

## Instruction shapes
Covers: class `BytecodeEmitter` header, `emitExpr`/`emitStatement` conventions, if/elseif chains, `++` and compound assignment.

The emitter walks the fully type-checked tree and builds the stack-based higher-order bytecode (HOB) as a `BytecodeProgram` (one `BytecodeStatement` per instruction; `Main` serializes it once emit is done). The instruction shapes below are written as text lines.

- `PUSH value type`
- binary operator: `OPNAME leftType rightType returnType`; unary operator: `OPNAME operandType returnType`
- `ALLOC name type` (hoisted, see "ALLOC hoisting")
- `ASSIGN leftType rightType returnType`
- `RET type` (`RET imut_void` for an empty/void return)
- `CALL funcName`: the one exception, it takes the function name as a literal; the arguments are PUSHed separately before it
- `,` emits nothing: compile left, then right
- `FUNC_START` / `RETURNS` / `ARG...` / `FUNC_END`, `STRUCT_START` / `STRUCT_MEMBER...` / `STRUCT_END`, `ENUM name member...`

Two points the original spec left open:
- **if / elseif / else chains.** Each branch jumps to the NEXT branch's label on a false condition (the shared end label for the last branch). A single `if` with no chain collapses to exactly the spec's shape. Jumping straight to the chain's final end label would skip later elseif/else branches.
- **`x++` and compound assignment** have no opcodes of their own; they are desugared to existing primitives. `x++` compiles as `x = x .INC` (push target, push value, INC, ASSIGN). `x += y` compiles as `x = x + y` (push target, push x, push y, ADD, ASSIGN).

## Per-function emitter state
Covers: `currentFuncMangledName`, `currentFuncInfo`, `pendingCallSiteUnwindLandingPads`, `tryCatchLabels`, `tryBlockLabels`, `continueTargetLabels`, `pendingTryCatchLabel`, `functionCatchParams`.

All of these are saved, replaced by fresh values and restored around every `emitFuncUnderName` call.
- `currentFuncMangledName`: read by `emitCall` to tell a self-call (`RECURSIVE_CALL`) from a `CALL`.
- `currentFuncInfo`: read by `emitDestructList` to look up an `owns` local's type (`FuncInfo.ownsLocalTypes`) when nulling a slot after its natural-end destruct.
- `tryCatchLabels`: "try" token -> catch-block label assigned by `emitHoistedCatchBlocks` BEFORE any statement of the body is emitted, so the label already exists when emission reaches the call a "try" wraps (`emitTryGuardedCall`).
- `tryBlockLabels` (try-block token -> its own "after" label) and `continueTargetLabels` (`continue` token -> the try-block label it targets) are filled by a pre-pass, `collectTryBlockLabels`, because a `continue` may need a label that is only emitted later; a live push-as-you-go stack, as `loopEndLabels` uses for `break`, is not enough.
- `loopEndLabels`: enclosing loops' end labels, innermost last; `break` jumps to the top.
- `loopContinueTargets`: one entry per enclosing loop, parallel to `loopEndLabels`. For a `loop` it is the start label; for a `for` it is a label right before the step, created on the first `continue` that needs it (so a program without one gets the same labels and numbering as before).
- `lockRetryLabels`: start labels of the enclosing `match @lock` spin loops, innermost last; target of a lock-retry `continue`.

## Call-site unwinding
Covers: `pendingCallSiteUnwindLandingPads`, `PendingUnwindLandingPad`, `pendingTryCatchLabel`, `stageCallSiteForUnwind`, `stageCallSiteForUnwindNames`, `emitTryGuardedCall`, `emitCallSiteUnwindLandingPads`.

When the program throws, a function re-stages its reserved `gt_routine_address` slot before EVERY ordinary `CALL`/`RECURSIVE_CALL`:
```
ADDR gt_routine_address code_addr
PUSH_LABEL <label> code_addr
ASSIGN code_addr code_addr code_addr
```
`<label>` is a landing pad specific to that call site. If the callee, or anything it calls at any depth, throws and unwinds back to this frame, `GT_UNWIND` tears down the deeper frame and reads this frame's slot, landing at that pad. The pad destructs exactly what is alive at the call (`op.destructOnExit`, computed by `TypeChecker` the same way a `return` there would be; `op.unlockOnExit` for held locks), then continues the unwind one frame up.
- Staging is a no-op when `checker.usesThrow()` is false, and inside gt-suppressed context (a ghost-table function body or its duplicate: `throw` is forbidden inside the real ones, and a call made from one is never an unwind boundary).
- `stageCallSiteForUnwindNames(destructNames, unlocks, at)` is the shared worker. `emitSafeArgsMainWrapper`'s two hand-built calls use it with empty lists (see "Entry points").
- Landing pads are buffered in `pendingCallSiteUnwindLandingPads` and written by `emitCallSiteUnwindLandingPads` after the body's natural-end destruct list and before `FUNC_END`, behind a leading `JMP` to a skip label (`end_of_gt_callsites__<name>`), because the pads are dead code unless an unwind lands there. Each pad is: `label:`, its `GT_DESTRUCT` list (requires a `@gt_destruct` function), its unlock calls, then `EXIT` (emitted name `main`), `EXIT_THREAD` (`@async` function) or the `GT_UNWIND` line (`gtUnwindLine`). Skipped for gt-suppressed functions.
- **"try ... catch" redirect.** A "try" does not emit a `TRY` line. It redirects the staging write of the exact call it wraps: `emitTryGuardedCall` sets the one-shot `pendingTryCatchLabel` to the hoisted catch label, then `emitExpr`s the call node (`tryTok.sub.get(0)`, always a plain CALL per `TypeChecker.checkTry`). `stageCallSiteForUnwindNames` consumes and clears it, adds no pad entry, and the write points at the catch block directly. `emitCall` stages BEFORE evaluating any argument, so the redirect cannot leak into a nested call in the argument list (in `try f(g()) catch{...}`, `g` still gets its own ordinary pad). The explicit clear after `emitExpr` is only a safety net.
- No resume label is needed after a caught throw: `checkTry` requires every catch body to end in `return` or `throw`.

## gt_routine prologue
Covers: `emitGtRoutineAlloc`, `emitGtRoutineBody`, `gtUnwindLine`, `emitTraceIdStore`, `isTraceRoot`, `traceArrayPointerType`.

Every function with unwind machinery has fixed frame slots at the same rbp offset in every frame (`GT_UNWIND` depends on that):
- `gt_routine_address` (`code_addr`, rbp-8), always the first thing in the frame, ahead of every other local. `code_addr` is a pointer-sized pseudo-type with no equivalent elsewhere in the language. `PUSH_LABEL` (not `PUSH`) is the only place a raw code address is ever pushed, so it is never confused with reading a slot's value. The slot points at a label the body never reaches except through an unwind.
- `gt_error_message` (`static_imut_string`, rbp-16), only when `checker.usesThrow()`. Written by `emitThrow`; read by `emitHoistedCatchBlocks` (snapshotted into each catch's bound parameter). `GT_UNWIND MSG` (X86Backend) reads the slot from the unwinding child frame before the epilogue retargets `rbp` and writes it into the caller's identically-offset slot. The backend copy only exists if some frame has the slot (`programHasErrorMessage`).
- With tracing on (`TraceConfig.usesTrace`): `gt_trace_id` (rbp-24) and `gt_trace_ptr` (rbp-32) in every frame, plus a `gt_trace_buf` staging array in root frames. See stack_trace.md.
- The prologue is split in two halves that `emitFuncUnderName` calls at different points: `emitGtRoutineAlloc` (just the `ALLOC`s, emitted first among all ordinary allocations) and `emitGtRoutineBody` (label, jump-around, `GT_DESTRUCT` list, ending), which runs only after every ALLOC/ALLOC_STATIC of the function has been emitted. So the bytecode text itself guarantees that every local's slot exists before any other functionality.
- Gate (both halves agree): nothing is emitted for a gt-suppressed function, nor in a program that uses neither `owns/ref/dyn/new` anywhere (`checker.usesOwnsRefDynNew()`, the `GT_INIT` trigger) nor `throw` (`checker.usesThrow()`; a `THROW` names its function's `gt_routine` label as an operand). The error-message slot has the narrower gate `usesThrow()` alone.
- Ending of a landing pad or of the old whole-function label: `EXIT` for the true main (checked on the EMITTED name, since dundering has already decided which of `main`/`@event_loop` carries it), `EXIT_THREAD` for `@async`, otherwise `GT_UNWIND` (`GT_UNWIND MSG` in a throwing program, `GT_UNWIND MSG TRACE` with tracing).
- **Old whole-function label.** In a program with no `throw`, `gt_routine__<name>` is emitted as before: `ADDR gt_routine_address / PUSH_LABEL / ASSIGN / JMP end_of_gt_routine__<name>`, the label, one `GT_DESTRUCT` per `FuncInfo.ownsLocalTypes` entry (every owns local of the function at any nesting depth), the ending, then the end label. In a program that throws it is superseded by per-call-site pads and a `throw`'s own inline destruct list (`TypeChecker.checkThrow`, `emitThrow`), and nothing is emitted except the slot `ALLOC` and the pads.
- `emitTraceIdStore` writes `(id << 1) | isRoot` into `gt_trace_id` at entry; a root's `gt_trace_ptr` starts at its own `gt_trace_buf` (`ADDR_OF AUTO`). A root is a frame whose caller is not a traced Caspien frame: emitted name `main`, a `__trampoline_*`, or an exported function. Traced functions get `FUNC_DECORATE @dont(inline)` because an inlined function leaves no frame and so no id. `TRACE_CAPTURE depth` is emitted at throw sites unless gt-suppressed.

## Ghost-table suppression
Covers: `gtDecoratedFuncMangledNames`, `gtReachableMangledNames`, `inGtSuppressedContext`, `computeGtReachableFunctions`, `collectCallTargetsAndBanInvoke`, `findFuncInfoByMangledName`.

Ghost-table instructions must not be generated inside the ghost-table decorated functions, and calls made from them must go to "dundered" copies with the GT instructions removed, all the way down.
- `gtDecoratedFuncMangledNames`: the mangled names of the decorated functions (`gt_init`, `gt_alive_check`, `gt_destruct`, `gt_register`, `gt_moved`, `gt_ref_id`, `gt_ref_resolve`; any that exist). Each is emitted once, under its real name, with suppression active for its own body. They get no duplicate of themselves because nothing ever calls them with an ordinary `CALL`.
- `gtReachableMangledNames`: every function or method transitively called (`CALL`/`RECURSIVE_CALL` only) from those bodies, found by a BFS over `Token.resolvedCallTarget`. Externs are skipped (no body; `EXTERN_CALL` never consults the set). Each reachable function is emitted TWICE: normally (callers elsewhere get real `GT_DESTRUCT`/`GT_ALIVE_CHECK`), and again as a `"__"`-prefixed duplicate with suppression active, from the same already-checked tree, never re-type-checked, and with no `EXPORT` line.
- Suppressed context (`inGtSuppressedContext`): `GT_DESTRUCT` is silently skipped (implicit compiler-inserted behavior); a bare `match` condition that would need `GT_ALIVE_CHECK` is a hard error (the programmer wrote that); any `CALL`/`RECURSIVE_CALL` to a name in `gtReachableMangledNames` is redirected to its `"__"` duplicate; no `gt_routine` prologue and no call-site staging.
- `call()` (INVOKE) anywhere in the reachable set is a compile error, as inside a `@pure` function: a function pointer's target is unknown at compile time, so nothing can guarantee it reaches only duplicates. The check is eager, over every reachable body.
- Emitting any ghost-table interaction requires a function with the matching decorator to exist in the compilation unit (`requireGhostTableFunctionPresent`, error "emitting a '@NAME' ghost-table interaction here requires a function decorated '@NAME' ..."), checked at the point a real instruction is about to be emitted, so a program that never destructs needs no `@gt_destruct`.

## String literal pool
Covers: `hoistedStrings`, `hoistedStringId`, `emitHoistedStringsPrefix`, `fillFuncNameTable`.

Every distinct string literal's raw (unescaped) content maps to one id `string_id1`, `string_id2`, ... in first-occurrence order, shared program-wide whether it occurs as a PUSHed expression or a GLOBAL/ALLOC_STATIC folded constant. Char literals are excluded and stay inline (`PUSH 'c' type`). The map is filled as a side effect of the single top-to-bottom emission pass (`literalValueOf`'s STRING case is the only place a string literal becomes bytecode text) and emitted as `STRING id "text"` lines at the very front of the output, ahead of the GLOBAL lines.

`stdlib/funcname.caspien` returns the marker `"@@funcname@@"`; at the end of `emit()` the push of that marker is replaced by a chain `if id == k { return "name k" }` ending in `"?"` (only when `CALL funcname` appears; stack_trace.md, 3.7). Names come from `traceDisplayName` (`name (file:line)`, signature suffix `__sig_...` removed).

## Top-level emission order
Covers: `emit()`, `flattenRootLinesForEmit`, `emitAsm`, `findFuncInfo`, `findImplMethodInfo`, `findLibraryFuncInfo`.

1. `computeGtReachableFunctions`, `LockNestingCheck.run` (exempting the gt sets), `checker.checkProofKills()`.
2. All `GLOBAL` lines first, wherever the globals were declared, through the same folding as local statics (`emitStaticAlloc("GLOBAL", ...)`).
3. Root lines in order (root-level `unsafe`/`safe` wrappers are expanded in place and leave no bytecode). A bare `asm_name;` VARREF root line emits the named asm (`ASM_START` / raw content verbatim / `ASM_END`); an unnamed asm declaration emits only at its declaration point.
4. Per root kind: `struct` -> `emitStruct`; `enum` -> `emitEnum`; `func` -> `emitFunc`; `extern` -> `emitExtern`; `impl` -> each method via `emitFunc` (a generic constructor, `isConstructorDecl`, is registered by `TypeChecker.collectImpl` in the shared `functions` overload map and is found like a plain function, not through `findImplMethodInfo`; methods are searched in `methods`, `staticMethods` and `genericMethodInstances`); `library` -> its functions as plain top-level funcs under their own mangled names (an inherited function is emitted only under its declaring library). No bytecode at all: `type` and `const` (erased, uses already resolved), `interface`, `export` (folded into the function's `FUNC_START` block as an `EXPORT` line), `impl_default_lock_match` (a template; a cloned copy is emitted at each use site inside the `match @lock` spin loop).
5. `fillFuncNameTable`, then the string prefix is prepended.

## Struct layout and padding
Covers: `emitStruct`, `computeStructLayout`, `layoutSizeAndAlignOf`, `layoutSizeAndAlignOfBaseType`, `StructLayoutEntry`, `LAYOUT_PRIMITIVE_SIZE`.

Structs are padded C-like in the HOB itself (`STRUCT_START` / `STRUCT_MEMBER` / `STRUCT_PADDING n` / `STRUCT_END`), not recomputed from an unpadded member sum by a later stage.
- Each field is aligned to its natural alignment (equal to its size for every leaf shape). A struct's alignment is the max of its members' (recursively: all-`u8` is 1-aligned, any `u64` makes it 8-aligned). Padding is inserted immediately before a field whose running offset is not a multiple of its alignment, and the total size is rounded up to the struct's alignment (so arrays of the struct stay aligned).
- Walk order: the hidden `___type` field (`imut_u64`, offset 0, 8 bytes, absent for `@untyped` structs where `classId == null`) is laid out FIRST, then `info.members` in declaration order. `___type` is never in `info.members`, so it is exempt from struct-literal exhaustiveness and ordinary `.` access; its value is populated at every construction site (`emitInstantiate`, `emitStaticAlloc`). Putting it last would move it away from offset 0, so the ordering is load-bearing. There is no inheritance, so no parent part.
- `[size, align]` of a member from its canonical text (`storage?_some?_mutability_atomic?_baseType`): any storage-bearing type (`owns`, `ref`, `raw`, `auto`, `static`) is one 8-byte, 8-aligned pointer word; then the base type: `range` / `range(..)` 16/8 (two u64 bounds); fixed array = element alignment, element size times length (a non-numeric length counts as one pointer word); dynarray / unsafe_dynarray one 8-byte handle; a nested struct is laid out first through a pending-struct stack (memoized, DAG-only; a cycle throws); primitives per `LAYOUT_PRIMITIVE_SIZE` (u8/s8/bool/char 1, u16/s16 2, u32/s32/f32 4, u64/s64/f64/code_addr/string 8, void 0); anything else (an enum name, since every enum fits in one u64 word, matching `SizeCalculator`) falls back to 8/8.
- The table is deliberately duplicated from `TypeChecker.PRIMITIVE_SIZE`, which serves the unrelated `sizeof` builtin under different rules (e.g. `sizeof` of an array type returns the element size and ignores alignment). The optimizer's `SizeCalculator` likewise keeps its own copy.
- `@unpadded` is rejected up front by `registerStructLike`, so every struct reaching this code is padded.

## Enums
Covers: `emitEnum`.
One line per enum. Plain: `ENUM name V1 V2`. With explicit u64 values: `ENUM name V1 0 V2 1`. Range-valued: `ENUM name V1 a..b V2 c..d`. `collectEnum` guarantees an enum is exactly one of plain / u64-valued / range-valued, and the bare name-only format is unchanged for every enum that never used explicit values.

## Entry points
Covers: `emitFunc`, `emitSafeArgsMainWrapper`, `syntheticVarref`.

- **`@event_loop`.** In the final bytecode the `@event_loop` function is emitted AS `main`, and the user's `main` is renamed `__caspien_main` (the `"__"` dundering convention). The real entry point is whatever a backend recognises as `main`, regardless of source names. Other functions go through `emitFuncUnderName(info, info.mangledName, isGtDecorated, info.isExported)` plus a `"__"` duplicate when gt-reachable.
- **Safe-args main.** With no `@event_loop` and `mainArgShape == "safe_args"`, the user's `main` is emitted as `__caspien_main` and `emitSafeArgsMainWrapper` synthesizes directly as bytecode (no synthetic Token/FuncInfo through checking, since nothing of it was ever source) the C-ABI `main(argc: s32, argv: raw mut raw mut char)`: `let args = make_safe_args(argc, argv); return __caspien_main(args)`. Details: `RETURNS` is the user main's return type; `ARG argc` / `ARG argv` as bare signature lines; a synthetic `FuncInfo` whose `ownsLocalTypes` holds only `args` so it gets the normal `gt_routine` treatment and `EXIT` ending (it is "the true main"); `GT_INIT` placement as in a true main (after the ALLOCs, before the first expression, with `gt_init` and `gt_moved` required); no `@call_convention`, so `ArgToAllocLoweringPass` uses the `compiler.config` default; calls staged with EMPTY destruct lists (`args` does not exist yet at the first call; at the second it is moved into the call, `argsTok.isOwnershipMoveSource`); landing pads emitted under `main`, never gt-suppressed.
- `syntheticVarref` builds a minimal VARREF token (file `<synthesized>`) so synthesized names can use the ordinary `emitExpr` / `emitAssignTarget` machinery.

## Function emission
Covers: `emitFuncUnderName`, `isStructByValueReturn`, `canonicalParamTypes`, `emitParamDecls`, `emitExtern`, `emitDecorators`, `emitBlock`, `emitDestructList`, `emitUnlockList`, `requireOneOwnsTypePerSlotName`, `dropNeedsGlue`.

`emitFuncUnderName(info, emittedName, gtSuppressed, allowExport)` emits, in this order: `FUNC_START`, optional `EXPORT` (only when `allowExport && isExported`: always the bare unmangled name, never on a `"__"` duplicate), `FUNC_DECORATE` lines (with the source position operand for `inline`/`dont(inline)`), `RETURNS`, hidden `ARG $ret_dest` for RVO, one `ARG name type` per parameter, hoisted statics, the `gt_routine_address` family, ordinary `ALLOC`s, late `ALLOC`s, the prologue body, hoisted catch blocks, the body, the natural-end destruct list, the landing pads, `FUNC_END`.
- **ARG.** `ARG name type` is a pure signature fact: no frame slot, load sequence or register decision. Turning it into `ALLOC` + `ADDR`/`PUSH ARGn`/`ASSIGN` (correctly placed after `gt_routine_address`) is address-lowering work done by the optimizer's `ArgToAllocLoweringPass`.
- **RVO.** A function whose return type is a plain struct (no storage modifier; `isStructByValueReturn`) gets a hidden FIRST parameter `$ret_dest` (`raw_mut_<Struct>`): the caller-owned destination it writes the result into. `$` cannot occur in a user identifier. It comes first so it lands in the sret register (System V RDI / win64 RCX). See also `emitReturn`'s struct-RVO branch and `emitStructRvoAssign`.
- **Late allocs.** Locals discovered while emitting the body (`declareHiddenLocal`) get their `ALLOC` lines spliced in at `lateAllocPos` once the body is done; `refAddrSlots` is saved/restored per function.
- **`requireOneOwnsTypePerSlotName`.** After the ordinary ALLOCs are emitted, a name declared twice with different types is an error ("the name 'X' is declared with two different types in this function ('A' and 'B') and one of them needs more than a plain free when it is dropped ...") when either type needs drop glue (`dropNeedsGlue`: an owned dynarray whose elements own memory, or an owned or inline struct with an owning member; `unsafe_dynarray` never does). Otherwise sibling scopes may share a slot sized for the widest use (tests/same_name_locals_test). Reason: drop glue resolves `GT_DESTRUCT name` through one name -> type map.
- **`emitExtern`.** `EXTERN linkName returnType paramType... [VARARGS]` on one line (a START/END block has no variable-length body to wrap). `linkName` rather than `name` so it matches the `EXTERN_CALL` operand; the declaration itself is a no-op for the ELF/GAS backend.
- **`emitDecorators(mnemonic, decorators[, withPos])`.** One line per decorator in declaration order: mnemonic, a space, `@name(args)` with string arguments keeping their quotes and bare identifiers left bare. `withPos` appends a quoted `"file:line"` operand (the optimizer reports on it); for `FUNC_DECORATE` only `inline` and `dont(inline)` get it.
- **`emitBlock(lines, isEntryPointBody)`.** `isEntryPointBody` is true for exactly one call per program: the body of the function emitted as `main` (including an `@event_loop` function). It emits `GT_INIT` when `owns/ref/dyn/new` is used anywhere (requires `gt_init` and `gt_moved`), after the hoisted ALLOCs and before any expression. Each statement is then emitted; a `let static` has no runtime init (its value is baked into `ALLOC_STATIC`). A `let` emits only its ASSIGN at its original position.
- **`emitDestructList(names, at)`.** One `GT_DESTRUCT name` per name of TypeChecker's precomputed list (reverse declaration order, moved or return-preserved names excluded); no computation of its own. Silently skipped in suppressed context. After each destruct the slot is nulled (`ADDR name; PUSH null; ASSIGN`), or, for an inline owning struct, its owned leaves (`emitInlineOwnsNullOut`; its members were dropped by the destruct). Reason: a loop-body `owns` local shares one hoisted slot across iterations, and a stale pointer to an already-freed block could be destructed again after the allocator reused the address, corrupting unrelated live data. Applied at every call site, not only loops.
- **`emitUnlockList`.** The lock-release counterpart: each compiler-synthesized `unlock()` call (`Token.unlockOnExit`, reverse acquisition order) is emitted as an ordinary expression; no dedicated bytecode shape.

## Static and global allocation
Covers: `emitStaticAlloc`, `foldStaticConstant`, `unwrapStaticExpr`, `staticArrayElements`, `staticArrayElementType`, `isRangeType`, `rangeBoundValues`, `parseEmbeddedRangeBounds`.

`let static x = ...` and root-level globals have a compile-time-constant value (TypeChecker validated: no function calls, no variable references except earlier globals). Instead of ALLOC+PUSH+PUSH+ASSIGN the folded value is part of the allocation. MNEMONIC is `ALLOC_STATIC` (local) or `GLOBAL` (root-level; same static instantiation rules, only the leading word differs).
- Scalar: `MNEMONIC name type value`.
- Struct literal: container line `MNEMONIC name imut_Struct` (no value), then `MNEMONIC name.member type value` per member in layout order (nested structs compose: `name.outer.inner`). The walk follows the real layout: `name.___type imut_u64 <classId>` first (absent for `@untyped`), padding gaps as `name.$pad<N> imut_u8[<bytes>]` lines, because the backend places each dotted line at the running sum of the sizes before it (without the pad lines every member after a gap was read at the wrong offset).
- Fixed-array literal: container line, then `name.0`, `name.1`, ... (element type = array type minus the trailing `[N]`).
- Range (`a..b` or a range-valued enum variant, whose bounds are embedded in its type text `imut_range(0,10)`): container line, `name.start imut_u64 a`, `name.end imut_u64 b`.
- `unwrapStaticExpr` strips `mut`/`imut`, a transparent `as`, and grouping parens down to the node with a runtime shape.
- `foldStaticConstant` folds to one literal's bytecode text: literals through `literalValueOf`; `as`, `mut`, `imut`, `static` (on a string/char literal) and `atomic` wrappers are transparent (they carry no runtime representation; without the `atomic` case the operator text `atomic` itself became the initial value); integer `+ - * / %` and unary `-` evaluated (the restricted shape `dup` counts use, see DupExpander); a float constant under unary `-` is negated textually; a plain enum-variant reference (`Color.GREEN`) folds to its dotted name (`qualifiedDotName`); a global referencing an earlier global folds through to that global's value. `sizeof(Struct)` folds to the laid-out size and emits `STRUCT_PIN <Struct>` so the optimizer's `StructMemberReorderingPass` cannot change that size afterwards (the pass always strips the line); the initializer is a data line, so the optimizer's `SIZEOF` resolution never sees it. A `sizeof` CALL with `builtinConstantValue` folds to that value. Not handled: enum-variant defaults beyond the plain variant reference.
- Errors: "a 'static' range initializer must be a literal 'a..b' range or a reference to a range-valued enum variant", "expected a literal-bound range type here", "malformed range type string", "expected an array type here".

## ALLOC hoisting
Covers: `collectHoistedAllocs`, `collectHoistedAllocsFromStatement`, `collectTryHoistedAllocs`, `letDeclarationOf`.

Every `ALLOC` / `ALLOC_STATIC` of a function, from any nesting depth (`if`/`match`/`loop`/`for`/`lock`/`unsafe`/`safe`/`assume`/`try` bodies), is collected up front into two lists of `HoistedAlloc` descriptors in program order, without emitting anything, then written contiguously: all static allocs, the reserved `gt_routine_address` family, all ordinary allocs. `emitBlock`, `emitPreLoopInit` and `emitForLoop` then emit only the runtime initialization (the ASSIGN) at the original position. Parameters are not part of the hoist; they are `ARG` lines. The bytecode text is thus itself the source of truth for "every local's slot exists before any other functionality runs".
- The collection pre-pass must mirror what the main emit walk declares, including: a `loop`'s `preLoopInit` lets; a `for`'s hidden range variable (`rangeVarNameOf`) and loop variable; catch parameters (`ALLOC <param> static_imut_string`, plus `<param>$trace mut_u64[DEPTH+1]` when `catchUsesStackTrace`) and everything declared in a catch body (`collectTryHoistedAllocs`, which also records the name in `functionCatchParams`); a plain `try { }` block declares nothing of its own beyond its body; `assume` only when `hasBlock`.
- A try-catch can appear as `let x = try ... catch(e){}`, as `x = try ... catch(e){}` (OPERATOR `=`), or as a bare statement; `tryNodeOf` recognises all three. Checking it for a `let` as well as ahead of the keyword switch is what hoists the catch parameter in every shape.

"see docs/BytecodeEmitter.md" pointers in the code.

## Hoisted catch blocks
Covers: `emitHoistedCatchBlocks`, `collectTryCatchNodes`, `collectTryBlockLabels`, `tryNodeOf`, `emitContinue` (catch form).

- Every throwing `try .. catch(e){..}` anywhere in a function body (any nesting depth, including inside another catch body) is
  collected up front by `collectTryCatchNodes` and given a fresh label in `tryCatchLabels`. Its catch body is emitted once,
  at the start of the function body (right after the gt_routine prologue), inside `JMP skip / catch pads... / skip:`
  (same shape as `emitCallSiteUnwindLandingPads`, but at the start). Because `tryCatchLabels` is filled before any statement
  is emitted, `emitTryGuardedCall` always finds its label when it redirects the wrapped call's staging write
  (`stageCallSiteForUnwindNames`, `pendingTryCatchLabel`).
- A catch body starts with a copy of the frame's `gt_error_message` into the catch parameter (`ADDR e static_imut_string; PUSH
  gt_error_message ..; ASSIGN`): an independent local (hoisted in `collectHoistedAllocsFromStatement`), never an alias of the
  shared slot. If the handler mentions `e.stack_trace` it also copies the trace array (`<e>$trace`, `mut_u64[depth+1]`) from
  `gt_trace_ptr`, because the staging array belongs to the root and a later throw overwrites it.
- No trailing `JMP` and no per-try "resume" label: `TypeChecker.checkTry` requires every catch body to end in `return` or
  `throw` (same all-paths-return analysis as the function-body check), and both already emit their own destruct list and a
  terminating instruction (`RET`/`EXIT`/`GT_UNWIND`). Consequence: code after a `try`/`catch` statement is reached only when
  the call returned normally, so `let x = try foo() catch{..}` always has the real return value in `x`.
- `collectTryCatchNodes` walks (work stack) into `if`/`match` chains (via `right` links), `loop`/`for`/`unsafe`/`safe`/`lock`,
  plain `try { }` blocks, `assume` with a block, and into a found try's own catch body.
- `tryNodeOf(stmt)` returns the throwing `try` token of a statement. Shapes: the bare statement `try f() catch{..}`; the
  assignment `=` whose right side is a try, seen through any `mut`/`imut` wrappers (`let p = mut try new Point{..} catch(e){..}`:
  a throwing `new` operand can be indeterminate-mutability, so the wrapper sits outside the whole try); `return [mut|imut] try ..`
  / `return ?f(x)`. A plain `try { }` block (`isTryBlock`) shares the KEYWORD text but is a different construct and is excluded.
  A missed shape means the catch is never hoisted or labelled and the call is emitted as a plain one (silently wrong).
- Plain `try { }` blocks (only legal host of a catch-body `continue`): `collectTryBlockLabels` walks the function once, before
  anything is emitted, assigns each a label `after_try_block` in `tryBlockLabels` and records, for every `continue`, the label of
  its nearest lexically enclosing try block in `continueTargetLabels`. A live push/pop stack cannot work because a catch body is
  emitted (hoisted) before its try block's lexical position is walked. The walk also enters real try/catch catch bodies.
  `emitStatement` emits a plain try block as its statements + destruct list + that label, nothing else.
- `emitContinue` has three forms (checked in this order): `isLoopContinue` (user `for`/`loop`: destruct + unlock lists, then jump
  to the loop's continue target, `@for_cont_N` created on first use for `for`, the start label for `loop`),
  `isLockRetryContinue` (CLOSED case of `match @lock`: jump to the spin loop's start label, after pre-loop init so retry counters
  are kept; no lock is held), and the catch-body form (jump to `continueTargetLabels`). A missing target is an internal error
  (TypeChecker should have rejected it). `break` is destruct list, unlock list, `JMP` to the innermost `loopEndLabels` entry.

## Statement emission
Covers: `emitStatement`, `emitIfChain`.

- `unsafe`/`safe` blocks are compile-time safety markers: only their statements and destruct list are emitted.
- `assume`: no runtime presence. Block form emits its statements; the no-block form has no children (later statements are siblings in
  the enclosing block). A no-block `assume match Some(..)` on a nullable ref calls `emitAssumeResolves` (one resolve, no test).
- `yield` is the bare mnemonic `YIELD`. `ASM`: a named asm is emitted at each `asm_name;` invocation site (VARREF with
  `resolvedAsmContent`), an unnamed one at its declaration point.
- A statement-level expression leaves its result on the stack (no POP).
- `emitIfChain`: shared end label, one `CMP`/`JMP nextLabel` per branch. A condition whose root is `&&then`/`||then` goes through
  `emitShortCircuit` instead of `emitExpr; CMP; JMP`.

## Match emission
Covers: `emitMatchChain`, `emitMatchCondition`.

- A `match` chain lowers like an if chain (shared end label, intermediate label per non-final branch, bare `else` falls into its
  block) with `emitMatchCondition` in place of `emitExpr`.
- Condition shapes: `and` chains (each leaf emitted, combined with `AND`); `match Some(i) in/into x` (`someIndexElementExpr`) = one
  combined bool: push the index (argument of the `Some` call, never the call itself) and `x`, `IN`, then the element alive test
  (`emitSomeOnRef` for a nullable ref, otherwise `GT_ALIVE_CHECK`, needing `gt_alive_check`), then `AND`; error if in a
  gt-suppressed context. `instanceof`/`implements`/`in`/`within`/`into`: plain `emitExpr` (the proof is recorded by the type
  checker; `within` and `into` emit like `in`). `Some(x)` CALL: plain `emitExpr` (already emits `GT_ALIVE_CHECK`; the old generic
  fallback would emit it twice). `fits`: `emitFits`. `x is base`: `PUSH x; IS_BASE <leftType>`, generic over any left operand.
  `!= 0` / `> 0` on a variable with integer 0: the division-safety proof, an ordinary comparison. Anything else is an internal
  error (checkMatchCondition rejects it first).
- `IS_BASE` carries the left operand's type because a composite left operand (an inline range literal `match (2..5) is base`) is two
  scalar pushes and the preceding line then holds only the upper bound's `u64`; `node.left.resolvedType` is the only place the real
  type still exists.

## Loops
Covers: `emitLoop`, `emitPreLoopInit`, `emitForLoop`, `noteLoopLabel`, termination bookkeeping fields.

- `emitLoop`: `start:` body destruct-list `JMP start`, `end:`; pushes `loopEndLabels` and a continue target `{startLabel}`. A
  `isLockSpinLoop` registers its start label in `lockRetryLabels` while its body is emitted. `lockSpinBound` goes to `spinBounds`.
- `emitPreLoopInit`: a lowered `match @lock` whose CLOSED case uses a `default` policy carries `preLoopInit` assigns (`$lm_tries = 0`
  plus the policy's configuration parameters), emitted once before the start label and not through `emitBlock`; null for ordinary loops.
  The ALLOCs are hoisted by `collectHoistedAllocs`.
- `emitForLoop`: `TypeChecker.checkForLoop` desugars `for i in a..b` into four nodes in `forTok.sub`: range let, loop-variable let,
  `in` condition, `++`. Emitted: both lets via `emitAssign`, `start:` condition (`emitExpr` -> ordinary `IN`) `CMP; JMP end`, body,
  destruct list, optional `@for_cont` label (only created if a `continue` used it), the increment, `JMP start`, `end:`. The two hidden
  variables' ALLOCs are hoisted.
- `noteLoopLabel` records source positions (`sourcePos`, `termSites`) and attaches any `/*! termination: .. */` doc comment
  (`loopTermLabels`, keyed `hobFunction|loopLabel`) for `GasReport.checkLabels` and `--fix termination`.

## Return and struct return (RVO)
Covers: `emitReturn`, `emitStructRvoReturn`, `emitStructRvoForwardCall`, `emitStructRvoAssign`, `emitStructRvoCallSequence`, `unwrapMutWrappers`, `ownerTryOf`, `destVarNameOf`.

- `emitReturn`: value expression first, then destruct and unlock lists (the returned value is never in the list), then `RET <type>`.
- Callee half: for a function returning a struct by value, `PUSH $ret_dest <raw_mut_S>`, `emitExpr(expr)` (oblivious to whether expr is a
  fresh literal or a bare variable; `TypeChecker.checkReturn` restricts the shapes), one whole-struct `ASSIGN` (ASSIGN is agnostic to
  how the destination address arrived and to how many words the source took), destruct/unlock lists, then `PUSH $ret_dest; RET raw_mut_S`
  (the ABI-style return of the destination pointer; call sites never read it back; `RET`/`PUSH_RET` were already generic for pointer returns).
- `return foo()` where foo is itself an RVO call (`isStructRvoCall`, seen through `mut`/`imut`): tail forwarding. This function's own
  `$ret_dest` pointer value is passed on as the callee's hidden destination (`PUSH $ret_dest`, no address-of, no copy), so the struct is
  written straight into the original outermost caller's memory however deep the chain.
- Caller half (`emitStructRvoAssign`): `op.right` is an RVO call at a legal position (let RHS or bare-variable assignment RHS; enforced by
  TypeChecker). No `PUSH_RET`/`ASSIGN`. The hidden destination argument is `PUSH destVar <type>; ADDR_OF RAW <type> raw_indeterminate_S`,
  byte for byte the shape of a user-written `raw destVar` argument (an indeterminate-mutability pointer satisfies a `raw_mut_S` parameter).
  `emitStructRvoCallSequence` is `emitCallSequence` hand-inlined so the destination pointer takes register slot 0: stage the call site for
  unwind (re-attaching the owning try's catch label via `ownerTryOf`), `CC_START`, push destination, transfer it, transfer user arguments
  (null-out of moved sources), `CALL`/`RECURSIVE_CALL`, `CC_END`.
- Destination that already owns memory (inline struct with owns members): the callee builds into a hidden local; then `GT_DESTRUCT dest`,
  and the local is assigned over it. A throwing callee leaves the destination untouched, and an argument that moved a member out of it
  (`h = f(h.w)`) has already nulled it.
- `unwrapMutWrappers` strips `mut`/`imut` and a non-block `try` (the `isStructRvoCall` flag travels through `try`; `let e = mut ? f()`).

## Throw
Covers: `emitThrow`.

- `THROW <operand>` is a marker line (operand = variable name or string literal `string_id`); a backend may treat it as a comment. Then, unless the
  function is gt-suppressed (nothing emitted: no ghost-table instructions or `gt_routine` slot exist there; `checkThrow` already bans throw in the
  ghost-table functions), the real work: a fresh throw (not `throw e` of a catch parameter, `functionCatchParams`) starts a new trace
  (`emitTraceCapture`), then `ADDR gt_error_message; PUSH operand <resolvedType>; ASSIGN` writes the operand into the frame's slot
  (`static_some_imut_string` for a literal, `static_imut_string` for a variable; `throw e` re-pushes and re-writes the same value, propagating it).
- Then the per-throw-site destruct list (`GT_DESTRUCT name` each, needs `gt_destruct`), unlock list, and the exit: `EXIT` in `main`, `EXIT_THREAD`
  in an async function, else the `GT_UNWIND` line. The destination is resolved at compile time, so the unwind sequence is inline (no jump to a
  central routine).

## Nullable ref ids in expressions
Covers: `emitExpr`, `emitSomeOnRef`, `emitAssumeResolves`, `requireRefHook`, `refAddrSlots`.
(Documented in the code's own Javadoc; see the project CLAUDE.md "Nullable ref = 64-bit id" for the design.)

## Operator emission
Covers: `emitOperator`, `emitExprCore`, `emitCompoundAssign`, `emitBinaryArith`, `emitPointerArith`, unary/inc/dec.

- Grouping parens emit their child; an array literal pushes each element in written order (owns temporaries via `emitOwnsTemporaryViaLocal`).
- `in` and `into` share the dedicated `IN` opcode (`IN <l> <r> <res>`); `into` differs only by a type-checker proof kind. `u64 in EnumName`
  (`isGuaranteedEnumMembershipCheck`): right side is a bare enum type name pushed by name (`PUSH <name>`), the backend derives the check from the
  `ENUM` line. `u64 in string` (`isStringMembershipCheck`): strings have no stored length, so `IN_SCAN` (a byte scan), like `LEN_SCAN` vs `LEN`.
- `within` is always `WITHIN` (never a pointer-specific mnemonic): `MembershipLoweringPass` decides what to dereference from the operand type text.
- No bytecode of their own: `mut`/`imut` (compile-time retag), `await` (the CALL emits `AWAIT_CALL`), `par` (the CALL emits `PAR_CALL`, its
  `resolvedType` overwritten by `checkPar`), `unsafe` around `dyn(..)` (resolvedType corrected by `checkUnsafeDynWrapper`), `atomic` (resolvedType
  carries `atomic`). `..` pushes its two u64 bounds back to back; `,` pushes both sides.
- `instanceof`/`implements`: `emitExpr(left); PUSH <rightName>; INSTANCEOF|IMPLEMENTS <leftType>`. The left type is a trailing operand for the same reason as
  `IS_BASE` (a bare struct literal leaves only the last member's push).
- `and` is emitted exactly as `&&` (`AND`); its extra meaning is type-checker only.
- `FLOAT_CHECK`: compiler-internal (built by `TypeChecker.lowerFloatMatchToIfChain`); `op.right.text` is the canonical `finite|nan`-style chain string;
  an f64 operand appends ` 8` (the f32 form is unchanged).
- Compound assignment `x op= y` = `x = x op y`; on a raw pointer with `pointerScale > 1`, the right side is multiplied by the pointee size
  (`pointerScaleLine`: `PUSH <bytes>` or the symbolic `SIZEOF <struct>` resolved by `SizeofResolutionPass`). `p +/- n` and `n + p` scale n;
  `p - q` (kind 3) divides the signed difference by the pointee size; `p++`/`p--` step by it (`emitPointerStep`).
- A negated integer literal is one `PUSH -N` so it stays a plain push inside packed array/struct construction runs.
- `x++` / `x--`: push target address, push value, `INC`/`DEC`, `ASSIGN`.

## Assignment targets
Covers: `emitAssignTarget`, `emitDotLhsRoot`, `hasStorage`, synthetic tokens.

- An assignment target swaps mnemonics at its root only: `ADDR` for `PUSH`, `DOT_LHS` for the collapsed `.` chain (with `PUSH_FIELDNAME` carrying the
  field name), `LOOKUP_LHS` for `LOOKUP`. All type arguments are identical to the read form, so the type system is untouched. Whatever is left of a `.`/LOOKUP root
  (call, another chain) is evaluated normally.
- `emitDotLhsRoot` picks the base: a bare variable without a storage keyword gets `ADDR` (its stack address is what DOT_LHS/LOOKUP_LHS add an offset to); a
  `.`/LOOKUP root without storage recurses into `emitAssignTarget(root)` (so `grid[1][1] = x` and `cube[1][0][1] = x` compute nested addresses; the read path would
  push the whole value bytes, which `LOOKUP_LHS` would then use as a pointer); a pointer-typed (`raw/owns/ref/auto/static`) root or any other shape goes through
  `emitExpr` (the pushed value already is the address).
- `hasStorage(type)`: type text starts with `raw_|owns_|ref_|auto_|static_`; null-safe (an enum name used only as a `.`-chain root has no resolvedType).
- `let` targets: the name is the `let` wrapper's first child. `syntheticMember`/`syntheticElement` build `base.member` / `base[literal]` tokens for the glue code.

## Moves and owning assignment
Covers: `emitOwnershipMoveNullOut`, `emitInlineOwnsNullOut`, `emitDestructOldOwnedValue`, `isFlatOwnsDestructTarget`, `isPointerCrossingOwnsTarget`, `emitAssign`.

- Move null-out: after a move, a synthesized `ADDR src; PUSH null T; ASSIGN` (same bytecode as a user-written `x = null`, no rule checks) nulls the source; only where
  `isOwnershipMoveSource` is set (a fresh `new`/call result has no slot). `a = mut b` nulls `b` (wrapper stripped). An inline owning struct (`inlineOwnsStruct`) nulls
  each owns member recursively; a fixed array of them is unrolled element by element (`syntheticElement`).
- For a call ARGUMENT the null-out is emitted between the argument's `POP ARGn` and the `CALL` (not after the call: a throw in the callee would skip it, leaving the
  caller's slot stale for its own unwind). A backend must therefore keep the scratch registers of that `ASSIGN` off any register holding a still-pending ARG value; every
  other `ASSIGN` is the last operation of its statement and may clobber freely.
- Destruct of the old value: an owning assignment (never a fresh `let`) destructs what the target holds first; `gt_destruct` is null-safe. Flat-nameable target (variable or
  pointer-free `.` chain, `isQualifiedNameableDot`): `GT_DESTRUCT <qualifiedDotName>`. Otherwise (pointer in the chain, or LOOKUP): `emitAssignTarget`, `DUP_TOP`,
  `GT_DESTRUCT_ADDR <type>` (inline owning struct: `GT_DROP_ADDR`), and the method returns true so `emitAssign` does not compute the address again (double evaluation of
  side effects in an index). `AddressLoweringPass.resolveAddress` can only fold a flat name when every segment is stack-resident, which is why the pointer-crossing form exists.
- Order in `emitAssign`: flat-named owns target (`lateDestruct`): destruct AFTER the right side is evaluated (`s = pass(s)` moves the old value into the call, nulling the
  variable, so the late destruct is a no-op). Pointer/index-reached owns target: the right side is evaluated FIRST into a hidden local, so a move out of the old value nulls it
  and a throw leaves it untouched; then destruct, address, push local, `ASSIGN`. Finally the move null-out of the right side if it is a move source.

## Atomics
Covers: `isAtomicCanonical`, `emitSwap`.
- `ATOMIC_PUSH`/`ATOMIC_ASSIGN` are emitted instead of `PUSH`/`ASSIGN` when the canonical type text contains `atomic_` (exact: `canonical()` writes it as its own segment and no
  type name contains it). A single aligned <=8-byte access cannot tear, so they exist to tell the backend this access must not be reordered, hoisted out of a loop or CSE'd (like C `volatile`).
- `a swap b` = `ATOMIC_SWAP <l> <r> <res>`, a real read-modify-write; the left operand is pushed as an ADDRESS (`emitAssignTarget`), the result is the previous value.

## Short-circuit conditions
Covers: `emitShortCircuit`, `isThenVariant`.
- `&&then`/`||then` are ordinary binary nodes up to the emitter, but lower only to a cascading CMP/JMP shape (entered from `emitIfChain`), never to one eager value; plain `&&`/`||` always
  evaluate both sides (`AND`/`OR`). `TypeChecker.checkLogical` guarantees each side is another then-variant or an ordinary expression with no then-variant inside, so no search is needed.
- Exactly one of `falseLabel`/`trueLabel` is non-null; the other outcome falls through. Leaf: `emitExpr; CMP`, then `JMP falseLabel` (CMP/JMP = jump if false), or, for trueLabel,
  `JMP skip; JMP trueLabel; skip:` (no jump-if-true opcode). `&&then`, falseLabel form: left then right against falseLabel. `&&then` as left of a `||then` (trueLabel form): both against a local
  `@andthen_fail` label placed after an unconditional `JMP trueLabel`. `||then` with trueLabel only: both sides forward trueLabel. `||then`, ordinary form: left against a `@orthen_pass`
  label (true skips the right side), right against falseLabel, then `passLabel:`. No `AND`/`OR` instruction is emitted anywhere in the cascade.

## Builtin call emission
Covers: `emitCallOrBuiltin`, `singleBuiltinArg`.

Calls to reserved names (`sizeof`, `len`, `range`, `deref`, `clone`, `Some`, `wrap`/`sat`, `insecure_rand`, `bits_*`, `memcopy`, `dyn`, `resize`, `call`) look like functions but get their own instruction. TypeChecker never sets `resolvedCallTarget` for them (they bypass the overload table), so `emitCallOrBuiltin` dispatches on the reserved name before the ordinary `emitCall` path. `sleep` and `par`/`await` are intercepted first (`emitSleepCall`, `emitAsyncCall`); an extern name goes to `emitExternCall`.

- `sizeof`: direct replacement, no instruction. A struct (or array of structs) emits `SIZEOF <struct> <type>` because its real size (hidden `___type`, padding, final layout) is known only after the Optimizer; `SizeofResolutionPass` turns it into the same PUSH. Otherwise `PUSH <const> <type>`.
- `len`: two-argument unsafe form `len(a, '\0')` is never foldable (TypeChecker leaves `builtinConstantValue` unset): both args pushed, `LEN_SCAN <targetType> <termType>` (its own mnemonic: a byte scan, not a hidden-field read). A fixed array or string literal folds to `PUSH <const>`. A safe dynarray pushes the argument then `LEN` (run-time value).
- `range`: always two plain u64 words with start a literal 0. Constant bound (string literal / fixed array): `PUSH 0 imut_u64`, `PUSH <const> imut_u64` (no `RANGE` instruction exists). Safe dynarray: `PUSH 0 imut_u64`, argument, `LEN`.
- `deref`: `DEREF <pointeeType>` (from `op.resolvedType`, set by `checkDerefBuiltin`). The operand carries the copy width: the preceding pointer push is always 8 bytes (every pointer is one word), so the pointee width cannot be recovered from context. AddressLoweringPass erases it to a byte count like `NEG`.
- `clone`: `CLONE <argPtrType> <resultType>` (same trailing-operand shape as `NEG`/`DEREF`, so AddressLoweringPass can size the pointee from the argument type with storage stripped). Requires `gt_register`. Then the `new` tail: `GT_REGISTER` before the null check (a failed registration frees the block and leaves null), then `emitAllocFailureCheck`.
- `Some(x)`: the same `GT_ALIVE_CHECK` sequence a match condition emits, as a general expression. Both restrictions of the old bare form still apply: a compile error inside gt-suppressed context ("function is a ghost-table-decorated function, or is reachable from one"), and `gt_alive_check` must exist. A `someOnRef` token goes through `emitSomeOnRef`.
- `wrap`/`sat`: `emitConvert` (see "wrap and sat").
- `insecure_rand`: `emitCallSequence(null, [], EXTERN_CALL rand 0, "mut_u64", defaultConvention)`: shared call machinery, no `ExternInfo`, default convention from compiler.config.
- `bits_not`: unary `BITS_NOT argType resultType`. `bits_left/right/rotl/rotr/or/and/xor`: both operands pushed, then `SHL|SHR|ROTL|ROTR|BITS_OR|BITS_AND|BITS_XOR leftType rightType resultType` (the binary-operator shape ADD has; the shape was not specified, so it mirrors the nearest existing case).
- `memcopy`: copies the third argument number of bytes from the second pointer to the first. All three arguments pushed, then `MEMCOPY t1 t2 t3 resultType`.
- `call(fp, args..)`: `PUSH fp / PUSH arg1 / PUSH arg2 / INVOKE n`, n = argument count excluding the pointer. The pointer is pushed inside CC_START/CC_END but is never one of the register-transferred ARGn words (`emitCallSequence`'s `pushFuncPtr`). The convention is the pointer type's (`op.resolvedCallConvention`).

## dyn literals
Covers: `case "dyn"` in `emitCallOrBuiltin`.

- `dyn(text)` (static imut string argument): push the string, then `NEW_FROM_STRING <elemDynarrayType> <sourceType>` (character-by-character copy, so not the fixed-push `NEW` shape).
- `dyn([a, b, ..])`: the array literal pushes its elements in order (no instruction for the literal), then `NEW_DYN <type> <count>` (safe) or `NEW_UDYN <type> <count>` (unsafe). Two mnemonics because a safe dynarray needs room for its length header and an unsafe one has none, so a backend should not parse a type string to tell them apart (same split as `LOOKUP_ARRAY`/`LOOKUP_DYN`). AddressLoweringPass erases `NEW_UDYN size` (flat count*elementSize) and `NEW_DYN size count` (elements only; the header bytes are added by whatever allocates; `count` stays because the runtime writes it into the header).
- `dyn:<T>([])` pushes nothing. The literal is emitted with `dynLiteralTempNames` set, so call-result temporaries moved into it go through hidden locals.
- Safe forms throw (need try/catch): `GT_REGISTER` (requires `gt_register`) then `emitAllocFailureCheck(op, literalElements, literalTemps)`. The unsafe forms are not throw-capable and are not registered here.

## resize emission
Covers: `case "resize"`.

- Safe `RESIZE <arrType> <argTypes..>` (array, count, fill) or `URESIZE` (array, count; unsafe, bare realloc, no fill). Detected by the first argument's resolved type prefix `owns_mut_unsafe_dynarray(` (TypeChecker forces `mut`, so the prefix is exact). The result is left on the stack by the instruction; no CC_START/PUSH_RET wrapper (resize never goes through the calling-convention path). The mnemonic split mirrors `NEW_DYN/NEW_UDYN` (fill-value type vs none).
- Move out of the source: `emitOwnershipMoveNullOut` of the first argument (when marked `isOwnershipMoveSource`) must come right after its push and before the RESIZE line, like an ordinary call's owns argument. Safe resize defers it (`deferResizeNullOut`) until the realloc succeeded: on failure the old block is still allocated and registered (realloc leaves it intact) but nothing else would reach it, so the failure branch (`emitAllocFailureCheck(op, resizeMoved, resizeTemps)`) destructs it. A source that is a temporary (a call result, `isOwnsTemporary`) goes through a hidden local so there is a name to destruct.
- Owning elements (`checker.elementTextOwnsMemory`): the array pointer and new count are evaluated once into hidden locals (`$mvN`), then `GT_DESTRUCT_TAIL arr type count` drops the elements at index >= count (expanded by DropGlueGenerationPass), the old length is saved, RESIZE runs on the hidden values, and for a growth `CLONE_FILL filled type oldLen` replaces the bitwise copies in the new slots by deep clones.
- Safe resize throws like `new` (null check via `emitAllocFailureCheck`); `URESIZE` is not throw-capable.

## Allocation failure check
Covers: `emitAllocFailureCheck`, the tail of `emitNew`, `emitDestructMovedSources`, `emitDestructTempNames`.

Sequence after `NEW`/`NEW_DYN`/`NEW_FROM_STRING`/`RESIZE`/`CLONE` (value on top of the stack): `DUP_TOP; PUSH null T; EQ T T imut_bool; CMP; JMP ok_label;` then (only if not gt-suppressed and a catch label is pending) destruct the moved-in sources and temps, `emitTraceCapture`, write the shared "out of memory" string (hoisted, deduplicated by content) to `gt_error_message`, `JMP <catch label>`; then `ok_label:`.
- The branch is a plain local `JMP`, not the `EXIT|GT_UNWIND` ending of `throw`: a `try new .. catch` check never crosses a function boundary (checkNew requires `insideTry`), the catch block's prologue snapshots `gt_error_message` into `e`. `DUP_TOP` inspects the pointer without disturbing the one copy callers expect on the stack.
- `emitNew`/clone/safe dyn register the block (`GT_REGISTER`) BEFORE the check so a failed registration (block freed, null left) takes the same out-of-memory path. `emitAllocFailureCheck` itself does not register.
- Catch label hold-back: every allocating builtin saves `pendingTryCatchLabel` and nulls it while emitting operands (a nested call would consume it, leaving the check with no label, i.e. an unchecked null), then restores it before the check. A `@throws` constructor in `new ctor(..)` is the exception: it keeps the label so it stages it.
- Moved-in `owns` sources are not nulled until the allocation succeeded (`deferredMoveNullOuts`, `deferredTempNames`, `dynLiteralTempNames`); if it fails the sources (still holding their pointers) are `GT_DESTRUCT`ed (`emitDestructMovedSources`: plain owns variables / nameable field paths only; temporaries via their hidden locals). `declareHiddenLocal` creates `$mvN` locals whose ALLOC is appended to the function's alloc block at the end (`lateAllocs`). `isOwnsTemporary`/`emitOwnsTemporaryViaLocal`: an owns call result is assigned to a hidden local and pushed from there.

## new
Covers: `emitNew`, `emitNewFromStructRvoCall`, `emitInstantiate` (see "Struct literals").

`new X{..}`: emit the operand normally (struct literal member pushes, primitive PUSH, ...), then `NEW <T>` where T is `op.resolvedType` with `owns_` (and `some_`) stripped, then `GT_REGISTER` (so `match Some` and scope-end destruct can find it), then the null check above. `new ctor(..)` (struct-RVO call): the struct is constructed into a hidden inline local `$new_ctor_tmpN` (`mut_<S>`) through `emitStructRvoCallSequence` (callee writes through a hidden destination pointer, so there is no PUSH_RET), then pushed whole; `NEW`/`GT_REGISTER` are unaware. The struct name comes from the callee's return type, else from the call node's resolvedType text. Like the async handle, the hidden local is emitted inline instead of being hoisted (see hist).

## Call sequence
Covers: `emitCallSequence`, `emitArgTransfer`, `emitRangeArgWords`, `emitArgWord`, `emitSyntheticArgWord`, `emitArgTransferTail`, `ArgCounters`, `voidSafeReturnType`, `emitCall`, `emitExternCall`.

Shape: `CC_START <conv>`, [function pointer push for `call()`], each argument pushed and, for the first n (n = register count of the convention in compiler.config), moved with `POP ARGn type` / `POP FARGn type`; extra arguments stay as plain pushes; then the one CALL/RECURSIVE_CALL/EXTERN_CALL/INVOKE line, `CC_END <conv>`, and for a non-void result `PUSH_RET <type>` after CC_END. Shared by all three call kinds. Nesting needs no special case: an inner call's complete bracket (with its own convention) is emitted while the outer arguments are pushed, so its result is on the stack when the outer POP ARGn runs. The convention is `Token.resolvedCallConvention` (from the target's `@call_convention`, the function-pointer type for INVOKE, else the config default); never re-derived here.
- Register index is a running counter advanced by the words each argument took (a by-value range takes two), not the argument index. `ArgCounters`: SysV/arm64 count int and float arguments on independent counters; win64 shares one position counter (`f(int a, float b, int c)` puts b in XMM1). A stack-passed word still advances its bank's counter; no stack offsets are computed (an overflowing word is just a plain push with no POP line). `floatRegistersUsed()` = `min(floatIdx, floatCount)` (the raw `floatIdx` keeps growing past the registers).
- By-value range argument (`BY_VALUE_RANGE_ARG`: `imut|mut|indeterminate_range..`, no storage prefix): split into its two u64 bounds, always int bank, never promoted. A `..` literal already pushes two words (the `..` operator has no instruction) so `emitArgWord` is called on each side; a named range (variable, dotted chain) pushes synthesized `PUSH <name>.start` / `<name>.end` (`qualifiedDotName`), the same shape `emitDot` produces. A range behind storage (`ref range(..)`) is a single pointer word. `unwrapParens` strips grouping parens first.
- Ownership: an argument that is an ownership-move source is nulled right after its value is captured and before the call instruction, so a `throw` inside the callee already sees the caller's slot null (`emitOwnershipMoveNullOut`; see its doc for the register clobbering hazard).
- `emitCall`: target through `resolveCallTargetForEmission`; mnemonic `RECURSIVE_CALL` when the (redirected) target is the current function (only possible for `@recursive`, TypeChecker.checkNoRecursion already ran), else `CALL`. `stageCallSiteForUnwind(op)` runs before the argument sequence (the slot is only read by a deeper `GT_UNWIND` after the real CALL). The trampoline's own call to the real `@async` function is an ordinary CALL.
- `voidSafeReturnType`: null for exactly `imut_void`/`mut_void` (exact equality, so a base type merely containing "void" is not mistaken), meaning no PUSH_RET.

## Variadic calls
Covers: `emitExternCall`, `varargStartIndex`, `PROMOTE_F32_TO_F64`, `VARARGS_XMM_COUNT`, win64 duplication.

- An extern ending in `...` pushes a different number of arguments per call site, so `EXTERN_CALL <linkName> <argCount>` always carries the count (like INVOKE). Never RECURSIVE_CALL.
- `varargStartIndex` (index into the argument list of the first true vararg = number of declared parameters; -1 otherwise) is passed only by `emitExternCall`. Arguments from that index on get C's default argument promotion: an `f32` is widened by `PROMOTE_F32_TO_F64` right after the push (same single stack word; still one float-bank word). The fixed prefix (printf's format string) is never promoted. See X86Backend's `PROMOTE_F32_TO_F64` for the ABI reasoning (printf("%f", ..) otherwise printed 0.000000).
- SysV: `VARARGS_XMM_COUNT n` is emitted just before the call instruction, only for a variadic call, with n = float registers really used (`floatRegistersUsed`); X86Backend consumes it for `%al` (`pendingVarargsXmmCount`). win64 has no such rule.
- win64 only: a register-passed variadic float must be in both the XMM register and the same-index integer register. `emitArgTransferTail` emits `DUP_TOP; POP FARGn; POP ARGn` when `isTrueVarargFloat && isFloat && regIndex >= 0 && counters.isShared()`. Both argument-register tables of a shared convention have equal size, so the index is valid in both. A stack-passed variadic float is untouched. The same flag (`promoteToDouble`) drives both rules since both need "true vararg and f32"; the duplication then applies to any float word (f64 included) because `isFloat` is tested separately.
- `isFloatCanonical` tests the last `_`-separated segment (the base type is written last in `TypeInfo.canonical()`); it accepts f32 and f64. `isF32Canonical` is f32 only.

## Sleep call
`SLEEP <resolvedCallTarget> <durationType>`: its own mnemonic (not CALL). The operand is the mangled name of the `@sleep` function, resolved by `TypeChecker.checkSleepCall` (never a hardcoded name in codegen). `requireSleepSignature` guarantees one plain u64 parameter and a u64 result, so exactly one argument is pushed and one u64 value is left.

## par / await desugaring
Covers: `emitAsyncCall`, `emitAsyncFailureJump`, `funcPointerCanonicalOf`, `isVoidCanonical`.

A `par`/`await` site is desugared into ordinary bytecode (no PAR_CALL/AWAIT_CALL mnemonics):
```
ALLOC __async_handle_N <handleType>
ADDR  __async_handle_N <handleType>
[handle literal pushes in the handle struct's padded layout order:
   ___type (if any), "value" = the real argument (or 0), "threadId" = 0,
   "state" (non-void) = AsyncState.PENDING, "result" (non-void) = STACK_LOCK gap,
   "gate" (value-returning par handle) = AsyncGate.OPEN, padding = STACK_LOCK n]
NEW <HandleStruct>
GT_REGISTER
(null check: out of memory -> destruct moved sources/temps, jump to catch)
ASSIGN <handleType> x3
CC_START <glue convention>
PUSH __trampoline_<fn> <funcptr type>;  POP ARG0
PUSH __async_handle_N;                   POP ARG1
[par only: ARG2 = &handle.threadId (PUSH handle; PUSH_FIELDNAME threadId; DOT_LHS) when the handle has a "gate",
           else null raw_mut_u64 (fire-and-forget: detached thread)]
CALL <par_call|await_call mangled name>
CC_END
PUSH_RET imut_bool; CMP; JMP thread_fail; JMP thread_ok
thread_fail: GT_DESTRUCT handle; destruct moved sources/temps; set "cannot start thread"; jump to catch
thread_ok:   null out the moved-in sources (the thread owns them now)
PAR  non-void: PUSH handle (the expression value; the consumer owns it)
PAR  void:     nothing pushed
AWAIT:         non-void: PUSH handle; PUSH_FIELDNAME result; DOT_LHS; DEREF.  then GT_DESTRUCT handle
```
- Requires a function decorated `@par_call` / `@await_call` (compile error "requires a function decorated '@...'" otherwise). `@par_call`/`@await_call` return bool (false = pthread_create failed).
- Moved-in owns arguments are held back (`deferredMoveNullOuts`) and only let go after the thread really started; if the handle can not be made or the thread can not be started they are destructed here (the failed par/await never took them). The pending catch label is held back during argument emission like `new`.
- Await destructs the handle unconditionally: `@await_call` already joined, so no other thread touches it. Void `par`: the hidden handle is never visible to the caller and the caller must not destruct it (the new thread may still read `handle.value`: use-after-free), so the trampoline self-destructs it (`synthesizeAsyncTrampoline`'s `selfDestructHidden`; each void par and void await site gets its own trampoline instance so only the par one self-destructs).
- `funcPointerCanonicalOf`: text `static_imut_func(p1,p2,..)ret` (a function pointer value is always `static`), mirroring `TypeInfo.funcPointer`; enough for the low-order stage to treat the push as a function address, as with `call()`.
- `emitAsyncFailureJump(label, msg)`: trace capture, write the hoisted message to `gt_error_message`, `JMP` to the catch.
- The handle local is allocated inline, not hoisted (see hist). Low-order address lowering assigns frame offsets from the order of ALLOC lines, so position in the function body does not matter for correctness.

## Call target redirection
Covers: `resolveCallTargetForEmission`, `collectCommaArgs`.

While gt-suppression is active (a gt-decorated function's body and every dundered duplicate), a CALL/RECURSIVE_CALL to a name in `gtReachableMangledNames` is redirected to its `__`-prefixed duplicate. Targets outside the set are returned unchanged (cannot happen when suppressed, checked defensively). If an event loop function exists (`checker.getEventLoopFunc()`), the real `main` is emitted as `__caspien_main` (emitFunc), so calls to `main` (still plainly "main" in `resolvedCallTarget`) are redirected at the last moment too. `collectCommaArgs` flattens a `,` operator tree into an argument list.

## Array lookup
Covers: `emitLookup`.

A scalar element read from a fixed array held in a variable (non-storage, type ending `]`, not a dynarray, result a plain scalar type): `ADDR name type; <index>; LOOKUP_LHS arrType idxType elemType; DEREF elemType` instead of pushing the whole array and picking one element from the copy (O(array size) per read; an n-body inner loop did this ~30 times per pair). Aggregate elements (row of a 2D array, struct) keep `LOOKUP`: their size is tracked by the following LOOKUP/DOT from the PUSH, and DEREF does not carry it. General form: `<left>; <index>; LOOKUP leftType idxType resultType`.

## as casts
Covers: `emitAs`.

Left side emitted first. `isFloatConvert` -> `FCONV from to`; `isTruncCast` -> `TRUNC from to`; `isAliasCast` (same storage and base type, only the nominal alias differs; aliases never appear in bytecode) -> nothing; otherwise same-signedness-family integer widening: `SEXT` (signed) or `ZEXT` (unsigned) from to. Pointer/float casts other than FCONV are out of scope (planned as stdlib functions). `op.right` is always a plain type-name VARREF (`mut`/`imut` no longer parse after `as`).

## fits
Covers: `emitFits`.

`x fits T` as a run-time test using only unsigned 64-bit compares. x is widened to 64 bits (`SEXT`/`ZEXT` by its signedness when narrower). Then:
- x unsigned or T unsigned: `x64 <= Tmax` (`PUSH tmax; LT_EQ`); a negative signed x is a huge u64 so it fails.
- x signed and T signed: `(x64 + (-Tmin)) <= (Tmax - Tmin)`.
- T is u64: Tmax is s64's max when x is signed (negatives already excluded); the always-true case (unsigned x into u64, or signed x with `tw == 64` or `xw <= tw`) is emitted as `PUSH 0; GT_EQ`.
Result type `imut_bool` for every compare.

## wrap and sat
Covers: `emitConvert`, `baseOfCanonical`.

`wrap:<T>(x)` / `sat:<T>(x)` are total integer conversions (float targets: `FCONV src dst` when `isFloatConvert`, a literal argument was already retagged).
- wrap, and sat when every value of x fits T (`alwaysFits = tw >= xw && (xs ? ts : (!ts || tw > xw))`): widen with `SEXT`/`ZEXT` by the SOURCE signedness, relabel at equal width (no instruction), or `TRUNC` when narrowing.
- sat otherwise: x is widened to 64 bits and clamped with branch-free arithmetic using only unsigned compares and literals below 2^63 (the backend's compares are unsigned), then `TRUNC` to T if `tw < 64`.
  - unsigned x: `V = X`; clamp above: `V + (V > Tmax) * (Tmax - V)`.
  - signed x to unsigned T: `V = X * (X <=u 2^63-1)` (max(X,0)); the above-clamp is needed only when `tw < 64` and the s-max of x exceeds Tmax.
  - signed x to narrower signed T: `notInside = (X + 2^(tw-1)) > (2^tw - 1)`; `fallback = (0 - 2^(tw-1)) + (X >=0) * (2^tw - 1)` i.e. Tmin wrapped, or Tmax when X >= 0; result `X + notInside * (fallback - X)`.

## Dot access
Covers: `emitDot`, `isQualifiedNameableDot`, `qualifiedDotName`.

- Catch parameter members: `e.msg` -> `PUSH <param> static_imut_string`; `e.stack_trace` -> `PUSH <param>$trace mut_u64[DEPTH+1]` then `ADDR_OF AUTO <arr> auto_indeterminate_u64[DEPTH+1]`.
- `EnumName.Variant` and `instance.member` chains (any depth, e.g. `line.start.x`) are one `PUSH <dot-joined name> <type>` (no DOT instruction, no run-time work; neither side is emitted through emitExpr). `qualifiedDotName` builds the name by walking the chain down (`op.left` of a chain is another `.` node, so `op.left.text` alone would give only the innermost segment).
- `isQualifiedNameableDot`: true only when the chain bottoms out at a VARREF and EVERY intermediate segment is storage-free (a pointer segment needs a real dereference that a flat compile-time name cannot express). This is the same "bail on any storage-bearing segment before the last" rule `AddressLoweringPass.resolveAddress` applies; a LOOKUP/CALL token's `.text` is an internal marker, and used as a name it once produced a malformed `PUSH LOOKUP.x`.
- Otherwise: `emitExpr(op.left)`, `PUSH_FIELDNAME <member> <type>`, then if the base has storage (is a pointer) `DOT_LHS baseT T T; DEREF T` (field address, then load exactly this field's width), else `DOT baseT T T` (offset into the inline value on the stack). Decided per level of the chain, never as one whole-chain check, so each hop reads only its own field.

## Struct literals
Covers: `emitInstantiate`.

`S{ m = v, .. }` is a plain value (no pointer, no wrapping instruction): each member value is PUSHed in the struct's padded layout order (`computeStructLayout`, the same layout `emitStruct` declared), regardless of written order, because nothing else says which pushed slot is which member; the consumer (ASSIGN/PUSH/RET) treats the value as one opaque unit of the type's size.
- This is the single choke point for construction: `new X{..}` reaches it through `emitNew`'s `emitExpr(op.left)`, a bare `X{..}` directly. Unless `@untyped` (`structInfo.classId == null`), the hidden class id is pushed first: `PUSH <classId> imut_u64`; the layout loop skips the `___type` entry.
- Padding entry (`memberName == null`): `STACK_LOCK <bytes>` (move the stack pointer forward without a value).
- OTC lock violation (`isLockViolationConstruct`, see `TypeChecker.checkOtcLockViolation`): every member except the lock/discriminant is omitted; the lock value is the only push, then `STACK_LOCK <structName>` (the assembly stage reserves/zeroes the omitted members).
- Owns members: an owns call result (temporary) goes through a hidden local when inside `new`/`dyn` (`deferredTempNames`). An ownership-move source is nulled right after its value is pushed (a later field may contain a throwing call), but inside `new` the null-out is deferred (`deferredMoveNullOuts`) until the allocation succeeded.

## Address-of
Covers: `emitAddressOf`.

`owns x` never reaches codegen (rejected by `checkAddressOf`). `static x` on a literal is a pure compile-time reinterpretation: only the operand is compiled. `auto x`, `raw x`, `ref x` emit the operand then `ADDR_OF <AUTO|RAW|REF> <operandType> <resultType>`. If the operand was addressable (a VARREF or dot chain rooted in one, `TypeChecker.isAddressableLvalue`) it collapsed to one `PUSH qualifiedName type` and AddressLoweringPass rewrites `ADDR_OF op leftType returnType` into `ADDR_OF op $address` (the name's offset is the address). Otherwise (`ref(ownedVar)`, `raw` on a string literal) the pushed value already is the pointer and the pass drops ADDR_OF as a no-op retag. ADDR_OF is a mnemonic of this project, not one named by the spec.

## Literals and escapes
Covers: `literalValueOf`, `escapeForBytecode`.

- String literals are hoisted: every occurrence of a string value is replaced by its shared id (`hoistedStringId`); the quoted, escaped text appears once, in a `STRING id "text"` declaration at the top of the output (see `emit()`). Char literal -> `'<escaped>'`; null -> `null`; integer/float/bool -> token text.
- `escapeForBytecode` re-escapes the decoded value for the plain-text bytecode: `\\ \" \n \t \r \0 \b \f \a \v` (all the lexer's escapes; bell and vertical tab use their numeric char codes because Java has no shorthand).
