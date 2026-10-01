# CLAUDE.md -- caspien-optimizer Project Memory

Read this first in any new conversation thread before making changes. This file describes the **current state** of this project only.

## New: constant folding and pass op-lists for `BITS_AND` / `BITS_XOR` / `BITS_NOT` (and the shifts, signed shifts and `BITS_OR` at every width)

Full account in the root `CLAUDE.md` (top section). `ConstantFoldingPass.foldBits(op, width, x, y)` folds `BITS_AND`, `BITS_OR`, `BITS_XOR`, `SHL`, `SHR`, `SAR` and the unary `BITS_NOT` at width 8/16/32/64, signed or unsigned: operands are reduced to their unsigned width-bit patterns, the operation is done on the pattern and the result is wrapped back (sign-extended for a signed type). Shifts follow the language rule (the count is the unsigned pattern of its own width; count >= width gives 0 for `SHL`/`SHR` and the sign fill for `SAR`), so the folded value is exactly what the machine code computes; the signed-shift and narrow cases were NOT folded before. Literal-only source is 64-bit, so narrow widths are reached through typed limit constants (`u8Max`, `s16Min`, ...) or after inlining. `FunctionInliningPass`, `StructUnpackingPass` and `StructMemberReorderingPass` list the new mnemonics where they list the binary / unary operators (a missing entry would treat a `BITS_XOR` line as an unknown line and refuse the function or struct). Note for large programs: `DeadControlFlowRemovalPass` re-runs a whole-function reachability after every folded `if true` / `if false`, so a single huge function (everything inlined into `main` at `function-inlining: aggressive`) is quadratic in the number of folded branches; this is why `tests/bits_ops_test.caspien` is split into five files (about 800 to 1900 checks each, 20 to 150 seconds with everything on).

## Update: `FunctionInliningPass` accepts `@lock`

`OK_DECORATORS` is now `@pub, @pure, @recursive, @inline, @throws, @lock`. `@lock` (struct-level `@lock(match self.X : ...)` and method-level `@lock(match i into self.backing)`) is a compile-time proof contract; the spin-lock code is emitted at the caller by `match @lock`, so inlining the callee cannot skip it. Effect: the stdlib `DynamicArray.get/set` are inlined at `aggressive`. Anything else with a decorator outside the list (`@async`, `@sleep`, `@gt_*`, `@par_call` ...) is still refused (`CASPIEN_INLINE_WHY=1` prints reasons). `tests/inline_hob_check.sh` cases d4/d5 (57 PASS). Details: root `CLAUDE.md`.

## Update: `RegVarHintPass` accepts a name declared several times with the same type

Loop counters are declared once per `for` and all share a name and a slot (`i` in three loops is one LOB slot). The hint pass used to require each name to be declared exactly once, so such counters never got a hint. Now a name is a candidate unless it is declared with DIFFERENT types (`mixedType`); the rest of the rules (only `ALLOC`/`PUSH`/`ADDR` mentions, no address-taken) are unchanged. Tests: `tests/regvars3_test.caspien`.

## What it is

Stage 2: higher-order bytecode -> higher-order bytecode, "shallow methods" only. Split out of the original combined optimizer
("separate the optimizer into two programs -- the shallow methods architecture at the start of the optimizer will be extracted and be
the optimizer, and the actual work ... will just be called the LowerOrderGenerator"). All real lowering (membership, clone, drop glue,
ARG-to-ALLOC, address lowering, the register-form pass) is in `LowerOrderGenerator/`.

## State of the passes

Thirteen passes do real work: `StructMemberReorderingPass` (`struct-member-reordering: on|off`, off unless set; see the root CLAUDE.md section and the class comment), `SizeofResolutionPass` (always on, no switch; see below), `RegVarHintPass`, `LoopUnrollingPass`, `FunctionInliningPass`, `ConstantFoldingPass`, `VariableElisionPass`, `VariableShiftingPass`, `StructUnpackingPass`, `DeadControlFlowRemovalPass`, `DeadFunctionRemovalPass`, `UnusedDeclarationRemovalPass` and `VariableAllocationReorderingPass` (`variable-allocation-reordering: on|off`, off unless set; runs once after the outer loop, before `RegVarHintPass`; see the root README `### variable-allocation-reordering` and its class comment; the alignment table mirrors `AddressLoweringPass.Sizes.alignOf` and must be kept in step). No pass is a no-op stub any more. Each file's header states the intended behaviour. Outer-loop order now: `StructMemberReorderingPass` (sits FIRST so that it changes the declarations before anything measures them; it reads and always strips `STRUCT_PIN` lines) -> `SizeofResolutionPass` -> `StructUnpackingPass` -> inner loop (constant folding, variable elision, variable shifting)
-> dead control flow -> dead function -> unused declarations -> loop unrolling -> function inlining; repeated until nothing changes. After the loop: `VariableAllocationReorderingPass`, then `RegVarHintPass`.

### `SizeofResolutionPass` (always on, no switch)

The front end leaves `sizeof` of a struct, of an array of structs, and the scale of `raw Struct` pointer arithmetic (`p++`, `p + n`, `p - q`, `p += n`) symbolic:
`SIZEOF TypeName <type> [more operands]`. Primitives, pointers and ranges still fold in the front end. The pass rewrites each `SIZEOF Name rest...` into `PUSH <n> rest...`, where n is the
size of the struct as it is DECLARED in the program now: the sum of its `STRUCT_MEMBER` sizes and `STRUCT_PADDING` bytes (the front end bakes padding into the declaration; `___type` is the first member). Member sizes follow
`LowerOrderGenerator` `SizeCalculator`/`AddressLoweringPass.Sizes` (storage keyword = 8, u8/s8/bool/char 1, u16/s16 2, u32/s32/f32 4, u64/s64/f64/code_addr/string 8, range 16, dynarray 8, fixed array = element * length,
a known struct = its own size with the whole mangled name looked up, anything else 8). Because it runs in the outer loop after struct member reordering and before folding, the PUSH it makes is folded by
`ConstantFoldingPass` in the same run (`3 * sizeof(S)` becomes one literal) and any future reordering or padding change is reflected. A `SIZEOF` naming a struct with no declaration throws `IllegalStateException`
(internal error, never a silent 8). Fast exit when no `SIZEOF` line exists. The `LowerOrderGenerator` must never see a `SIZEOF`. The static-initialiser case (`let static x = mut sizeof(S)`) is not an optimizer matter: statics are
data lines, folded by `BytecodeEmitter.foldStaticConstant` from `computeStructLayout(...).size`, which is the same number.
Tests: `tests/sizeof_stride_test.caspien` (sizeof equals the real array stride, `raw P ++` steps sizeof bytes, static initialiser equals the runtime value), `tests/sizeof_hob_check.sh` (6 hand-made HOB cases).

### `FunctionInliningPass` (configurable, off unless `function-inlining` says otherwise)

Settings: `InlineConfig` reads `function-inlining: off|conservative|balanced|aggressive` plus `inline-max-callee-lines`, `inline-max-depth`, `inline-max-growth` from
`compiler.config` (missing = off; the other stages' parsers skip the keys; `Main` prints them). Presets (callee lines / depth = inlining rounds / growth = added lines per function):
conservative 12 / 1 / 200, balanced 40 / 3 / 2000, aggressive unlimited / 32 / 1e8. Runs last in the outer loop, after loop unrolling, so folding/elision/shifting see the merged code.
A call site is `[ADDR x T] CC_START cc / arg lines, each ending POP ARGn|FARGn T / CALL f / CC_END cc / [PUSH_RET T]` (plus, in throw programs, a staging triple `ADDR gt_routine_address / PUSH_LABEL X / ASSIGN` before the `CC_START`, X = a `@gt_callsite__..` landing pad or a `@catch_N`; dropped on commit, X is kept as the unwind target);
a callee is `FUNC_START, FUNC_DECORATE.., RETURNS, ARG.., ALLOC.., body, RET, FUNC_END`. Words are counted per parameter: a scalar is one, a `range` (`imut_range`/`mut_range` exactly) is two
(`ARG2`/`ARG3` receive start and end, the parameter becomes a local range built by `ADDR r__iK imut_range / seg0 / seg1 / ASSIGN`). The inlined copy: parameters and locals renamed `name__i<inst>`,
labels keep their numeric-suffix shape (`$for_range_N` gets a fresh number), range type text that embeds variable names (`imut_range(mut_lo,mut_hi)`) is renamed too, every callee `ALLOC` goes to the
caller's prologue, an `ALLOC` that repeats a parameter or earlier alloc of the same type is deduplicated (the recursion-to-loop conversion emits these), a different type is a rejection.
Result modes: STACK (single tail `RET`: the value is just left on the stack), TEMP (`__ret_i<inst>` result variable, each `RET` becomes assign + `JMP @inl_end_<n>`), DISCARD (no `PUSH_RET`).
Call sites in the middle of an expression or condition are inlined too (the walk-back that finds the start of a statement understands labels, jumps, `ASSIGN`, `CMP`, `POP`, `INC/DEC`). EXCEPTION (bug found and fixed): a site inside another call's argument list after an earlier argument has been popped into a register (`POP ARGn`/`FARGn`, tracked by `argRegisterLoaded`/`regsLoaded` in `expandRange`) is NOT inlined: inlined code has no register protection, and the backend's block copies (rdi/rsi/rcx), divides (rdx) and shifts (rcx) clobber argument registers; before the fix `printf("..", f(range))` printed nothing. If the outer call is itself inlined its `POP`s vanish and the next optimizer round inlines the inner one.
Throw / catch programs (steps 2 and 3, asked for as "continue thru those 2"): every function has `ALLOC gt_routine_address` and `ALLOC gt_error_message` (fixed slots rbp-8 / rbp-16), stages a per-call-site label into its own slot before each call, and unwinds with `GT_UNWIND MSG` (tears down its frame, copies its message slot up, jumps through the CALLER's slot). Both `ALLOC`s are counted in `Callee.scaffoldSlots` and never copied: the inlined lines name `gt_routine_address` / `gt_error_message` and so use the CALLER's own slots, which is exactly where the callee's frame would have delivered them (`Callee.usesAddr/usesMsg`; the caller must have the slot, else refused). `@throws` is now an accepted decorator. `THROW x` is a pure marker (Codegen ignores it) and is dropped (`Callee.unwinds`). Each `GT_UNWIND [MSG]` in the copy becomes `JMP X`, X = the label the caller staged for this call (`stagedLabel(out)`, cut = 3 removes the triple); the message was already written into the caller's slot, and the caller's landing pad / catch does the rest (a pad's `GT_UNWIND MSG` copies it further up; a catch reads `gt_error_message`). The callee's own staging lines, landing pads (`@gt_callsite__f_N: [GT_DESTRUCT..] GT_UNWIND MSG` -> `.. JMP X`) and hoisted catch blocks (`JMP @end_of_catches__ / @catch_N: .. / @end_of_catches__:`) are copied with renamed labels (numeric suffix kept, so `@catch_` / `@gt_callsite__` prefixes survive). `PUSH_LABEL` is accepted only for `@gt_callsite__` / `@catch_` labels defined in the callee.
Never inlined: decorators other than `@pub/@pure/@recursive/@inline/@throws` (`@inline` is an accepted front-end decorator; it is not a command to this pass, just not a blocker) (`@async`, `@sleep`, ghost-table hooks), `main`, call cycles (a self-recursive function is already a loop when it reaches this pass:
`TypeChecker.lowerRecursiveFunctionToLoop` in the ASTGenerator), bodies with EXIT/EXIT_THREAD/ASM/ALLOC_STATIC/GLOBAL/REGVAR, a callee that unwinds at a call site with no staged label (`why` not set: the site check refuses; this is every old-form pad in a non-throw owns program, which has no staging), an unwinding callee whose staged label is a `@catch_` while the caller or the callee has a float variable (the backend's catch entry `R_XRELOAD` reloads float variables from their home slots, which are only written around real calls, so a jump in from inlined code would restore stale values), a callee that defines a `@catch_` label at a site with operands of an enclosing expression beneath it (`cls == 2`), and any callee that is not stack-balanced (`Callee.balanced`, see below) at a site that has anything beneath it except the result-variable form with one address,
by-value struct/array/dynarray parameters, labels defined outside the callee, `INVOKE` (function pointer), extern calls (no body). A callee that mentions a global name the caller also declares is rejected (capture check); a growth check stops a function that would exceed the budget.
Debug aid: set the environment variable `CASPIEN_INLINE_WHY` and the pass prints `[inline] <function> refused: <reason>` to stderr for every callee it rejects (the reason is `Callee.why`); it does not show call-site refusals (nested argument registers, other lines after the last argument `POP`).
Tests: `tests/inline_test.caspien` (45 checks; alone and with every other switch at every preset, Linux and Wine), `tests/inline_argreg_test.caspien` (7 checks, the bug above; the broken version fails it), `tests/inline_throwprog_test.caspien` (10 checks: leaf functions inlined in a program that throws, plus thrown/caught messages around them), `tests/inline_throw_test.caspien` (17 checks: throwers, catchers, re-throw through two frames, translated message, `continue` in a loop, two throwing calls in one function, an owns local destructed in a landing pad, float variable around a catch; identical off and at every preset), `tests/inline_owns_test.caspien` (10 checks: calls that move an owns variable in, null-out kept, callee destruct once, throwing callee, loop; identical off and at every preset, valgrind clean), `tests/inline_hob_check.sh` (55 checks; hand-made bytecode cases, incl. the owns-move null-out ones o1-o5, the scaffolding ones s1-s6, the unwinding ones t1-t5 (throw becomes `JMP` to the staged pad / catch, THROW dropped, float caller at a catch refused, no staged label refused), never-inline decorators and bodies, extern/INVOKE, cycles, the three limits).
Found while building it: `DeadControlFlowRemovalPass` could drop a label definition still referenced by a kept line (`regform2_test` with everything on); `rewriteOne` now reinstates any dropped label that a kept line names, to a fixpoint.
Bug found by the sweep and fixed (pre-existing, not from steps 2/3): a callee that CALLS something and ignores the result leaves that `PUSH_RET` word on the operand stack (harmless in its own frame, `RET` resets rsp). Inlined at a site with operands beneath (`t += noisy(1)`, a value directly above an address) the word sat between the pending operands and their consumer: the program crashed (`stdlib_test` at aggressive; a 9-line program reproduces it with the shipped build too). `stackBalanced` simulates the operand-stack depth over the callee (known effect for every mnemonic, 0 at every RET/end, unknown mnemonic = not balanced); an unbalanced callee is now inlined only with nothing beneath the site, or in TEMP form with one address beneath (the address is pushed again after the copy; an unbalanced callee at a `let x = f()` site is forced out of STACK form into TEMP form). Likewise a callee with a catch of its own entered by a real unwind arrives with whatever its statement had pushed (`ADDR v` of a `let v = ?f()`), so it is refused at `cls == 2` (its catch falls out through the end label with those words still on the stack). Accepted, not fixed: a catch that ends in `continue` leaks a word per throw (same as before inlining).
Owns moves: a call that moves an owns variable in has `ADDR o / PUSH null / ASSIGN` after the argument `POP` inside its bracket; those triples (and only those) after the last `POP` are copied right after the parameter copies (`moveNullOuts`); any other line there refuses the site.
Known limits: a throwing callee that never returns a value (`selfThrower`: no `RET`) at a value site stays a call; an unwinding callee at a `@catch_` with float variables in caller or callee stays a call; old-form (non-throw owns program) pads are not inlined; `@inline` is accepted but is only a non-blocker, not a forcing hint; the recursion-to-loop conversion now also checks the base case after the loop (ASTGenerator, `tests/recursion_base_test.caspien`).

### `RegVarHintPass` (runs once, last)

Emits `REGVAR name weight [f]` (`f` = the local is an f32, for the float register class) after a function's leading `ALLOC` run for every local worth keeping in a register. Eligible: an `ALLOC`
of a plain scalar (`mut|imut` + `u8..u64`, `s8..s64`, `f32`, `bool`, `char`), a unique name that is not `$`-prefixed and not
`gt_routine_address`, mentioned only as the operand of `ALLOC` / `PUSH` / `ADDR`, and never address-taken (`PUSH`/`ADDR` immediately
followed by `ADDR_OF` disqualifies it). Weight = sum over its `PUSH`/`ADDR` mentions of 8^min(loop depth, 4), where a loop is a
backward `JMP @label`; hinted only when the weight is at least 8. The pass is idempotent (a function that already has a `REGVAR`
is skipped). It names no register: the hint says "this one is hot", and the LowerOrderGenerator (`RegVarPromotionPass`) decides
whether and where. Downstream stages that do not understand a `REGVAR` line ignore it; the LowerOrderGenerator always strips it.

### `DeadFunctionRemovalPass` (configurable, off unless `dead-function-removal: on`)

Setting: `VariableConfig` reads `dead-function-removal: on|off` (same file and rules as the other keys of that class); the other stages' parsers skip it. Step 6 of the outer loop, after dead control flow removal.
`run`: return unchanged if disabled, if there is no `FUNC_START main`, or if any line is an `INVOKE`. Build name -> function index from `FUNC_START`. Roots: `main`, `gt_init`/`gt_register`/`gt_alive_check`/`gt_destruct`,
functions whose leading `FUNC_DECORATE` lines include something other than `@pub`/`@throws`/`@recursive`/`@pure` (`INERT_DECORATORS`), and every function named (CODE token, not a STRING) on a line outside any function.
Then a worklist: for each live function, every CODE token on its body lines that equals a function name marks that function live (this covers `CALL`, `RECURSIVE_CALL` and `PUSH f <func type>` alike). Everything not live is deleted as a whole FUNC_START..FUNC_END block.
Why the roots: the backend emits `gt_init`, `gt_register`, `gt_alive_check`, `gt_destruct` by fixed name (X86Backend `emitCallByName`), and `main` is the entry, none of them via a `CALL` in the bytecode; special decorators are how the compiler finds the
`par`/`await`/`sleep`/lock helpers. Removing the now-unused `extern`s, globals and strings is the separate `UnusedDeclarationRemovalPass` below. `tests/deadfunc_hob_check.sh` runs the prebuilt Optimizer on hand-made bytecode for the cases the language cannot easily express (recursion needs `@recursive` and a range parameter).

### `UnusedDeclarationRemovalPass` (configurable, off unless `unused-declaration-removal: on`)

Setting: `VariableConfig` reads `unused-declaration-removal: on|off` (field `unusedDecls`); the other stages' parsers skip it. Runs in the outer loop right after dead function removal.
`run`: return unchanged if disabled, if any line is `ASM_START`, or if there is no `FUNC_START main`. Collect declarations by key: `EXTERN:name`, `STRING:id`, `GLOBAL:base`, `ALLOC_STATIC:base`
(`base` = name before the first `.`, so `g.member` family lines share the key of `g`). A key is used if any CODE token (not `Kind.STRING`/`COMMENT`) at any position of a line that is not itself a
declaration of that key equals the name (or, for globals/statics, has that base). Unused keys are removed unless: EXTERN in `IMPLICIT_EXTERNS` (`malloc realloc free strlen exit pthread_exit sched_yield`,
emitted by the backend by fixed name), GLOBAL in `IMPLICIT_GLOBALS` (`ghost_table`, read and written directly by Codegen), or an `ALLOC_STATIC` base declared other than exactly once (function-local statics
of the same name in different functions, e.g. `m` in lock/match code, are not told apart). Only the declaration lines are deleted. Tests: `tests/unusedecl_test.caspien`, `tests/unusedecl_hob_check.sh`.

### `DeadControlFlowRemovalPass` (configurable, off unless `dead-control-flow-removal: on`)

Setting: `VariableConfig` reads `dead-control-flow-removal: on|off` (same file and rules as the other variable keys); the other stages' parsers skip it. Runs in the outer loop after the inner
loop. `run` applies one rewrite at a time and starts over (the functions are re-derived each time): find `PUSH true|false T / CMP / JMP L` in a function without `ASM_START`;
snapshot the lines already unreachable (`reachable()`, identity set); `true` deletes the three lines, `false` replaces them with the JMP; run `reachable()` again; delete the lines that are
unreachable now and were not before (never `ALLOC`/`ARG`/`REGVAR`/`RETURNS`/`FUNC_DECORATE`/`STRING`/`ALLOC_STATIC`/`FUNC_START`/`FUNC_END`); `dropJumpToNext` removes an unconditional `JMP L` directly before `L:`.
`reachable()`: roots are the function start plus every label that is not structural (`@if_branch_ @end_of_if_ @loop_ @loop_end_ @for_ @for_end_ @match_branch_ @end_of_match_`) or that a
non-JMP line mentions (`PUSH_LABEL @catch_..`, `@gt_callsite__..`); `RET`, `THROW` and an unconditional `JMP` (one not directly preceded by `CMP`) do not flow on; a JMP flows to its label.
Known leftovers: the `GT_UNWIND` callsite entries of a call that was inside a removed branch stay (harmless); empty `@end_of_if_N:` labels stay.

### `StructUnpackingPass` (configurable, off unless `struct-unpacking: on`)

Setting: `VariableConfig` reads `struct-unpacking: on|off` (same file, same rules as the two variable keys); the other stages' parsers skip it. Third step of the outer loop (after struct member reordering and sizeof resolution).
The struct table comes from `STRUCT_START/STRUCT_MEMBER/STRUCT_END` (members other than `___type`; a struct qualifies only if every member type is a plain `mut_`/`imut_` scalar).
Per function (skipped with `ASM_START`), for each `ALLOC v (mut_|imut_)S` with one ALLOC and S qualifying, `plan()` scans every line that mentions `v` or `v.*` and accepts only:
member read `PUSH v.m MT` (type must equal the table type, next line not `ADDR_OF`), member write `ADDR v T / PUSH_FIELDNAME m MT / DOT_LHS T MT MT`, and construction
`ADDR v T / PUSH <digits> imut_u64 / member expressions with STACK_LOCK padding lines anywhere / ASSIGN T T T`. Member expressions are postfix: a stack of start markers
(PUSH adds one, a binary operator drops the second operand, a unary keeps them) finds where each begins; only PUSH, the binary operators `ADD SUB MUL DIV MOD SHL SHR BITS_OR AND OR EQ NEQ LT LT_EQ GT GT_EQ`,
the unary `NEG NOT TRUNC SEXT ZEXT FCONV` are understood and an expression may not mention `v` (sequential assignment would otherwise change a swap). Any other mention returns null and the variable is untouched.
Output: `ALLOC v__m MT` per member (name collisions with existing names skip the variable), reads renamed, writes `ADDR v__m MT`, construction `ADDR v__m MT / expr / ASSIGN MT MT MT` per member.

### `VariableElisionPass` and `VariableShiftingPass` (configurable, off unless `variable-elision: on` / `variable-shifting: on`)

Settings: `VariableConfig` reads both keys from `compiler.config` in the working directory (missing = off, other value = error with `path:line:`); `Main`
prints them on the `[info] optimizer:` line; the other stages' `CompilerConfig` parsers skip them. Shared analysis in `VarAnalysis` (package-private):
per function, every mention of a candidate name must be its own `ALLOC`, a read `PUSH x T` not followed by `ADDR_OF`, or the constant assignment shape
`ADDR x T / PUSH literal T2 / ASSIGN`; anything else (address taken, compound assignment `ADDR x; PUSH x; ...; ASSIGN`, struct field, non-scalar) marks the variable bad.
`dominated(a, m, jumps)` is false when a forward `JMP` before `a` lands after `a` and at or before `m`; `inLoop(a, jumps)` is true when a backward `JMP` after `a`
lands before it. Candidates: exactly one `ALLOC`, name not starting with `$`, base type u8..u64/s8..s64/f32/f64/bool; a literal whose sign is negative in a narrow
type is not propagated; functions containing `ASM_START` are skipped.
Elision: exactly one assignment, every read after it and dominated -> remove the assignment (3 lines) and the `ALLOC`, replace each read by `PUSH <literal> <read's type>`.
Shifting: two or more assignments, no read before the first; for each later assignment that `canSplit` (not in a loop, all later mentions dominated), allocate `x__sN`
(unique in the function), insert its `ALLOC` right after the original one and rename every mention from there to the next split. Functions with `ASM_START` or a
`@catch_` label are skipped (hoisted catch bodies sit textually before the reassignment). Termination: new variables have one assignment. Range bounds: a `for` bound that is an elided constant variable appears by name in the hidden range's type text (`imut_range(0,imut_n)`); elision rewrites every token spelling that type, within the function, to the literal
(`imut_range(0,4)`; only non-negative integer literals), so `LoopUnrollingPass` can unroll it. A bound whose variable is not elided is left as is.
Order in `BytecodeOptimizer`: inner loop is folding, elision, shifting.

### `ConstantFoldingPass` (configurable, off unless `constant-folding: on`)

Setting: `FoldConfig` reads `constant-folding: on|off` from `compiler.config` in the working directory (missing file/key = off, other value =
error); `Main` prints it after the unroll settings on the `[info] optimizer:` line; the other stages' `CompilerConfig` parsers skip the key.

One forward scan per run. The output list is kept as a stack: when an operator line arrives and the last one or two OUTPUT lines are literal
`PUSH lit T` lines (an integer, a simple decimal float or `true`/`false`, of the type the operator line states), they are replaced by one
`PUSH result <the operator's result type>`. Because the result stays on the output stack, `(10 - 3) * 2` folds completely in one run. It only
ever looks at the directly preceding lines, so a label, jump or any other line stops it; it never reads what a variable holds (that is
constant propagation, another pass's job).
Integers: `ADD SUB MUL DIV MOD SHL SHR BITS_OR` on u64/s64 only (a narrower stack word keeps its high bits until stored, so a folded narrow value
could differ from the backend's), computed in BigInteger and wrapped to 64 bits; compares at every width; `TRUNC` to an unsigned (or non-negative)
narrow type; `SEXT`/`ZEXT` to 64 bits. Bool `AND OR NOT`. Floats f32/f64: `ADD SUB MUL DIV`, the six compares, `NEG`; f32 is computed in Java `float`;
the result is printed as a plain decimal that round-trips (`Float.toString`/`Double.toString` through `BigDecimal.toPlainString`, `.0` appended
if needed); a result that is NaN, infinite or -0.0 is not folded. Never folded: division/modulo by zero, signed MIN / -1, shifts >= 64 or of a
signed type, integer `NEG` (the emitter folds a negated literal itself), floats with an exponent in the literal text.
Language facts checked before writing it: a literal `/ 0` or `% 0` is already a compile error; a signed divisor must be proven `> 0`; literal-only
arithmetic is always u64 (or s64 with a negative literal), never a narrow type; float division by a literal zero IS allowed by the language (inf/NaN
at runtime), hence the not-folded rule above.

Verified: `tests/constfold_test.caspien` (11 checks) at on/off, Linux and Wine; 17 deliberately broken versions of the pass all make it fail; the
other `tests/` programs (folding fires in seven) and Hello World identical; all eight n-body ports give identical energies. Not verified: the
Intel/MASM target, a fuzzer, compares/casts on narrow integer types (allowed by the code, but source literals are always 64-bit, so no test produces one), and whether a folded f32 text always reads back to the same float in every downstream parser (it did in every test).

### `LoopUnrollingPass` (configurable, off unless `compiler.config` says otherwise)

Settings come from `UnrollConfig`, which reads the top-level keys `loop-unrolling` (off|conservative|balanced|aggressive),
`loop-unroll-factor`, `loop-unroll-full-max-trips`, `loop-unroll-max-body-lines`, `loop-unroll-max-growth` from `compiler.config` in the
working directory (the orchestrator now writes that file into `Optimizer/` too; a missing file or key = off; the other stages' config
parsers skip these keys). `Main` prints `[info] optimizer: <settings>` to stderr.

It recognises exactly the HOB `for` shape (hoisted `ALLOC $for_range_N`, `ADDR`/`PUSH lo`/`PUSH hi`/`ASSIGN` of the hidden range,
`@for_A:` test `IN`/`CMP`/`JMP @for_end_B`, body, 4-line increment, `JMP @for_A`, `@for_end_B:`) and only when the range type text
carries digit literals (`imut_range(0,4)`). `imut_range(2,mut_n)`, `loop{}` and anything else fail to match and are left alone -- this is
deliberate: no constant propagation, folding or induction analysis (those are other passes). Full unroll: N <= full-max-trips: the test
and back-jump are removed, N body copies with the increment between them. Partial unroll (factor U, N >= 2U): the hidden range's high
bound (both the `PUSH hi` literal and every occurrence of the range type text, including the hoisted `ALLOC`) becomes `lo + (N/U)*U`, U
body copies each followed by the increment, header renamed `@fu_A` so it is never matched again, the test's exit jumps to a new
`@for_rem_K` label in front of the N mod U remainder copies, and `@for_end_B` stays last so `break` still leaves everything. Labels
defined in a copied body are renamed (prefix and numeric suffix kept, numbering above the program-wide maximum). Innermost candidates
first, outer loops on later fixpoint rounds; a per-function growth budget persists across rounds. Skipped: body contains `ALLOC`,
`ALLOC_STATIC`, `FUNC_START`/`FUNC_END`, `STRUCT_START`, `GLOBAL`, `REGVAR`; body mentions its own range variable or header label; body
defines a label referenced from outside it.

Verified: `tests/unroll_test.caspien` at every preset, 12 numeric-override combinations, Linux and Wine; all other `tests/` programs and
Hello World unchanged at every preset (only `array_match_test` and `regform2_test` actually contain a literal-bound loop); no duplicate
labels. Not verified: the Intel/MASM target, any fuzzing, code-size effects beyond those runs. Measured: unrolling alone did not speed up
the looping n-body programs (N = 1e6, within noise).

Ideas discussed for this stage (not built): common-subexpression elimination and loop-invariant hoisting, an `sqrtf` -> `sqrtss`
intrinsic, hinting `for`-loop hidden variables and parameters. See the root `CLAUDE.md` for why these matter (the n-body benchmark
analysis).

## Rules of this project

- Plain-text bytecode is the interchange format; no shared code between stages (each has its own `BytecodeParser` copy).
- A pass must be a pure function of the whole program; ordering and fixed-point looping live in `BytecodeOptimizer` only.
- The Optimizer README.md / CLAUDE.md were added on the owner's instruction (2026-09-29).
- Regression fixtures (`examples/`) live at the project root of the owner's working copy; they are not in the zip or the masters.
- `output.txt` in this folder is a stray run artifact and is not shipped.

## Build / test

    javac -d out $(find src/main/java -name '*.java')
    java -cp out caspien.optimizer.Main -i in.txt out.txt

With every pass a no-op, output equals input; the whole-pipeline check is Hello World and `tests/`.

## New: f64 register hints

`RegVarHintPass` emits `REGVAR name weight f` for `*_f32` and `*_f64` scalars. See the root CLAUDE.md.
