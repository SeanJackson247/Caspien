# CLAUDE.md -- caspien-optimizer (current state only)

## What it is
Stage 2 of the Caspien toolchain: higher-order bytecode (HOB, plain text) in, HOB out; "shallow" passes only. Real lowering is in `LowerOrderGenerator/`. No code shared between stages. Source: `src/main/java/caspien/optimizer/` (one file per pass, `BytecodeOptimizer` = pipeline/order, `Main`, `BytecodeParser/Serializer/Token`).

## Build / run / test
    cd Optimizer && javac -d out $(find src/main/java -name '*.java')
    java -cp out caspien.optimizer.Main -i in.txt out.txt
Whole pipeline: `java Compiler -i x.caspien output/x` (repo root). Config is read from `compiler.config` in the CURRENT DIRECTORY (the orchestrator writes `Optimizer/compiler.config` from root `toolchain.config`); missing key = off, bad value = error. All passes off => output == input.
Tests in repo-root `tests/`: runtime programs (`inline*_test`, `constfold_test`, `unroll_test`, `varelide_test`, `structunpack_test`, `deadflow*_test`, `deadfunc_test`, `unusedecl_test`, `sizeof_stride_test`, `structreorder_test`, `allocreorder_test`, `bits_ops*_test`) and hand-made-bytecode scripts (`inline_hob_check.sh`, `deadfunc_hob_check.sh`, `unusedecl_hob_check.sh`, `sizeof_hob_check.sh`, `structreorder_hob_check.sh`, `allocreorder_hob_check.sh`, `bits_ops_check.sh`). Standard check: output identical with every switch off vs on, at every inlining preset.

## Pass order
Outer loop until no change: StructMemberReordering -> SizeofResolution -> StructUnpacking -> inner loop (ConstantFolding, VariableElision, VariableShifting) -> DeadControlFlow -> DeadFunction -> UnusedDeclaration -> LoopUnrolling -> FunctionInlining. After: VariableAllocationReordering, then RegVarHint (once each).

## Passes (switch = compiler.config key; all default off unless noted)
- StructMemberReordering `struct-member-reordering`: largest alignment first, `___type` first; rewrites every positional layout site. Skips structs with decorators/non-scalar members, a `raw` pointer to them, unparseable constructions, `STRUCT_PIN` (always stripped).
- SizeofResolution (always on): `SIZEOF Name ..` -> `PUSH n ..` from declared layout; unknown struct = IllegalStateException; LOG must never see `SIZEOF`.
- StructUnpacking `struct-unpacking`: scalar-only local structs become one local per member (`v__m`); any other mention leaves it alone.
- ConstantFolding `constant-folding`: only literal `PUSH`es directly before an operator. Integer arithmetic on u64/s64 only; compares, TRUNC/SEXT/ZEXT, bool ops, f32/f64 `+-*/`, compares, NEG (NaN/inf/-0.0 not folded); `foldBits` for BITS_AND/OR/XOR/NOT, SHL/SHR/SAR at all widths using the language shift rule (count >= width gives 0 / sign fill). Never folds div/mod by 0, signed MIN/-1, integer NEG.
- VariableElision `variable-elision`: once-assigned literal local, dominated reads, no address taken -> literal substituted (also in hidden `for` range type text, enabling unrolling). Shared analysis in `VarAnalysis`.
- VariableShifting `variable-shifting`: straight-line reassignments get fresh `x__sN`; not in loops/branches; skips `ASM_START`/`@catch_` functions.
- DeadControlFlow `dead-control-flow-removal`: `PUSH true|false / CMP / JMP` folded, newly unreachable lines removed.
- DeadFunction `dead-function-removal`: reachability from `main`, the four `gt_*` hooks, specially decorated functions; no-op without `main` or with any `INVOKE`.
- UnusedDeclaration `unused-declaration-removal`: drops unreferenced EXTERN/GLOBAL/ALLOC_STATIC/STRING; keeps backend-implicit externs (malloc realloc free strlen exit pthread_exit sched_yield) and `ghost_table`; no-op with `ASM_START` or no `main`.
- LoopUnrolling `loop-unrolling` off|conservative|balanced|aggressive + `loop-unroll-factor|-full-max-trips|-max-body-lines|-max-growth`: only the exact `for` shape with literal bounds in the range type text; never variable bounds or `loop{}`.
- FunctionInlining `function-inlining` off|conservative|balanced|aggressive + `inline-max-callee-lines|depth|growth` (presets 12/1/200, 40/3/2000, unlimited/32/1e8).
- VariableAllocationReordering `variable-allocation-reordering`: sorts the leading `ALLOC` run by alignment then use weight; `gt_routine_address`/`gt_error_message` first, params before locals. Alignment table mirrors `AddressLoweringPass.Sizes.alignOf`: keep in step.
- RegVarHint (always on): `REGVAR name weight [f]` for hot plain scalar locals (weight = sum 8^min(loopDepth,4) >= 8; only ALLOC/PUSH/ADDR mentions, never address-taken; same-type redeclared names OK). Names no register; LOG decides.

## FunctionInlining specifics
- Call site `[ADDR x T] CC_START / args ending POP ARGn / CALL f / CC_END / [PUSH_RET]` (+ staging triple in throw programs). Accepted decorators: `@pub @pure @recursive @inline @throws @lock`; others (`@async`, `@sleep`, `@gt_*`, `@par_call`) refuse. Copies rename locals `name__i<n>`, hoist callee ALLOCs to the caller, result modes STACK/TEMP/DISCARD, `range` param = two words.
- Throw programs: the `gt_routine_address`/`gt_error_message` ALLOCs are scaffolding (not copied; the caller's slots are used). `THROW` dropped; `GT_UNWIND` becomes `JMP` to the label the caller staged.
- Never inlined: `main`, cycles, EXIT/ASM/ALLOC_STATIC/GLOBAL/REGVAR bodies, by-value struct/array/dynarray params, `INVOKE`, externs, unwinding callee with no staged label, unwinding callee into a `@catch_` when a float variable exists (stale reload), callee with own `@catch_` at a site with operands beneath, no-`RET` value callee.
- A site after an earlier arg was popped into a register is NOT inlined (backend clobbers rdi/rsi/rdx/rcx). An unbalanced callee (leaves a word) only with nothing beneath the site (or TEMP form with one address). Owns-move null-outs after the last `POP` are carried; other lines there refuse.
- Debug: `CASPIEN_INLINE_WHY=1` prints callee refusals.

## Gotchas
- A mnemonic missing from a pass's op lists makes the function/struct "unknown" and refused: new operators must be added to ConstantFolding, FunctionInlining, StructUnpacking, StructMemberReordering.
- DeadControlFlow reruns whole-function reachability per folded branch (quadratic in a huge `main` at `aggressive`); keep generated tests split.
- Aggressive inlining has unbounded growth (OOM at 2 GB default heap on many call sites).
- Stack form: a `JMP` right after a bare `CMP` is conditional.
- Passes must be pure functions of the whole program; ordering lives only in `BytecodeOptimizer`.

## Known gaps
- Windows/MASM target unverified; Linux is the executed target.
- No CSE, loop-invariant hoisting or sqrt intrinsic.
