# master_code.md -- Optimizer, full project mirror

## MASTER CODE SYSTEM -- PERMANENT RULES (READ THIS FIRST, EVERY SESSION)

**This section is a permanent fixture of every master_code file. Never remove, shorten, or reword it when regenerating a file. If a master_code file is ever found without it, restore it before doing anything else.** Claude has no memory between sessions; this section is how the system survives that.

### What the master code system is

The project ("Caspienv3", a compiler for a custom systems programming language) is preserved as five text files, the "master code files", one per component plus the orchestrator:

- `master_code.md` -- the orchestrator (`Compiler.java`, `stdlib/`, `toolchain.config`, `hello.caspien`, root README/CLAUDE)
- `ASTGenerator_master_code.md` -- stage 1
- `Optimizer_master_code.md` -- stage 2
- `LowerOrderGenerator_master_code.md` -- stage 3
- `Codegen_master_code.md` -- stage 4

(In the Claude project the four component files carry a component prefix only so all five can sit side by side. Inside the zip every one of them is named plain `master_code.md`, at the root of its own component folder.)

Each is a full text mirror of its component's source, keyed by path, containing everything needed to rebuild that component from nothing at that point in time.

### The deliverable rule (set by the owner -- do not reinterpret it)

**Whenever the owner makes changes to the project, the deliverable is ONE WORKING ZIP FILE of the whole project, with the synced master code file at the root of each component, for each component.** Not loose master code files. Not a zip of only master code files. A zip the owner can unzip and run. It is always handed over with SendUserFile; saving files into the Claude project does NOT count as delivering.

Required layout (single top-level folder `Caspienv3/`):

    Caspienv3/
        master_code.md            (orchestrator, synced)
        Compiler.java, Compiler*.class
        toolchain.config          (the shipped one: windows_gnu / win64)
        README.md, CLAUDE.md, hello.caspien
        stdlib/
        ASTGenerator/             master_code.md (synced), README.md, CLAUDE.md, compiler.config, src/, out/ (prebuilt)
        Optimizer/                master_code.md (synced), src/, out/ (prebuilt)
        LowerOrderGenerator/      master_code.md (synced), CLAUDE.md, compiler.config, src/, out/ (prebuilt)
        Codegen/                  master_code.md (synced), CLAUDE.md, codegen.config, src/, out/ (prebuilt)

Every file listed in a master code file's File index must exist in the zip at its path, and every component's `out/` must be prebuilt (the orchestrator README says the zip ships all four components prebuilt). Not in the zip: `examples/` fixtures, `output/`, `debug.txt`, `output.txt`, scratch or temporary files.

"Synced" means each master_code file contains the current, exact, complete contents of the component it sits in, so that file alone can rebuild that component. Nothing may live only in a chat, a scratch folder, or Claude's memory.

Before handing over the zip Claude must: (1) regenerate every master code file that changed from the actual working tree, not from memory; (2) confirm each File index matches the `### FILE:` blocks it contains; (3) build the zip's contents from the master code files, prebuild each `out/`, and put each synced `master_code.md` at its component root; (4) unzip the FINAL zip into a fresh folder and compile and run Hello World from that unzipped copy; (5) also save the updated master code files into the Claude project; (6) deliver the zip with SendUserFile and tell the owner plainly what was verified and what was not.

### Format conventions (observed 2026-09-29)

- Title line `# master_code.md -- <Component>, full project mirror`, a short description, then `## File index` (one path per line), then one `### FILE: <path>` heading per file followed by a fenced code block with the file's full contents.
- Compiled `out/` class files are not mirrored (they are rebuilt and shipped prebuilt in the zip). The regression fixtures in each component's `examples/` are not mirrored and not in the zip (the ASTGenerator README says they live in that project's own git history / working copy).
- Edit `toolchain.config`, never the per-component config copies (they are overwritten on every run).

### Rebuild recipe (verified end to end on 2026-09-29)

1. Extract every `### FILE:` block of each master code file into its component folder (skip nothing in the file index).
2. In each component folder: `javac -d out $(find src/main/java -name '*.java')`. In the root: `javac Compiler.java`.
3. `java Compiler -i hello.caspien output/main` then run `output/main`; expected output `Hello World!`, exit code 0.
4. `toolchain.config` ships set to `target windows_gnu` with `default: win64`. On a Linux machine without mingw/Wine, test on a temporary copy with `target linux` and `default: sysv_x64` (the two must match or the compiler refuses to run); never ship that edit.

### Resolved items (owner answered 2026-09-29)

- The `examples/` fixtures are kept at the project root of the owner's working copy. They are not in the zip and not in the masters.
- The Optimizer component carries a README.md and a CLAUDE.md (added 2026-09-29).

Owner's rule, in their words: the rules for project deliverables are a permanent fixture in the master code files, in order to preserve the master code system. "A WORKING ZIP WITH THE MASTER CODE SYNCED AT THE ROOT OF EACH COMPONENT, FOR EACH COMPONENT."


Every text file in this project, in full, keyed by path. This is
stage 2 of the Caspien toolchain (higher-order bytecode ->
higher-order bytecode, shallow optimization passes). Compiled
`out/` class files are not part of this mirror. This component
takes no config file of its own.

## File index

- CLAUDE.md
- README.md
- compiler.config
- src/main/java/caspien/optimizer/BytecodeOptimizer.java
- src/main/java/caspien/optimizer/BytecodeParser.java
- src/main/java/caspien/optimizer/BytecodeSerializer.java
- src/main/java/caspien/optimizer/BytecodeToken.java
- src/main/java/caspien/optimizer/ConstantFoldingPass.java
- src/main/java/caspien/optimizer/DeadControlFlowRemovalPass.java
- src/main/java/caspien/optimizer/DeadFunctionRemovalPass.java
- src/main/java/caspien/optimizer/FoldConfig.java
- src/main/java/caspien/optimizer/FunctionInliningPass.java
- src/main/java/caspien/optimizer/InlineConfig.java
- src/main/java/caspien/optimizer/LoopUnrollingPass.java
- src/main/java/caspien/optimizer/Main.java
- src/main/java/caspien/optimizer/OptimizationPass.java
- src/main/java/caspien/optimizer/PassResult.java
- src/main/java/caspien/optimizer/RegVarHintPass.java
- src/main/java/caspien/optimizer/SizeofResolutionPass.java
- src/main/java/caspien/optimizer/StructMemberReorderingPass.java
- src/main/java/caspien/optimizer/StructUnpackingPass.java
- src/main/java/caspien/optimizer/UnrollConfig.java
- src/main/java/caspien/optimizer/UnusedDeclarationRemovalPass.java
- src/main/java/caspien/optimizer/VarAnalysis.java
- src/main/java/caspien/optimizer/VariableAllocationReorderingPass.java
- src/main/java/caspien/optimizer/VariableConfig.java
- src/main/java/caspien/optimizer/VariableElisionPass.java
- src/main/java/caspien/optimizer/VariableShiftingPass.java

### FILE: CLAUDE.md
```markdown
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
```

### FILE: README.md
```markdown
# caspien-optimizer

Stage 2 of the Caspien toolchain: higher-order bytecode (text) in, higher-order bytecode (text) out. It holds the "shallow" passes
that rewrite the bytecode before it is lowered. **Twelve passes do real work (see below; `SizeofResolutionPass` is always on); `StructMemberReorderingPass` is still a no-op** (they return their input unchanged). `RegVarHintPass` adds `REGVAR name weight` hints for hot scalar locals
(an `f` token at the end marks an f32 variable; consumed by the LowerOrderGenerator when `variables-in-registers` is on; otherwise stripped there). The real lowering work is the LowerOrderGenerator's job (the next stage).

## Usage

    optimizer -i input.txt output.txt

`input.txt` is the ASTGenerator's bytecode. The orchestrator (`Compiler.java` at the project root) runs this stage for you;
`java Compiler -i hello.caspien output/hello` runs all four. This component takes no config file of its own.

## Build

    cd Optimizer && javac -d out $(find src/main/java -name '*.java')

The zip ships `out/` prebuilt.

## Pipeline (see `BytecodeOptimizer`)

    repeat until no change:
        struct unpacking
        repeat until no change: constant folding, variable elision, variable shifting
        dead control flow removal, dead function removal, unused declaration removal, loop unrolling, function inlining
    struct member reordering (once, placeholder)
    variable allocation reordering (once; `variable-allocation-reordering`, off unless set)
    register-variable hints (once): REGVAR name weight, see RegVarHintPass

Real passes: `RegVarHintPass`, `LoopUnrollingPass` (`loop-unrolling*` keys of `compiler.config`), `FunctionInliningPass` (`function-inlining: off|conservative|balanced|aggressive` and `inline-max-*`), `ConstantFoldingPass` (`constant-folding: on|off`), `VariableElisionPass` (`variable-elision: on|off`), `VariableShiftingPass` (`variable-shifting: on|off`), `StructUnpackingPass` (`struct-unpacking: on|off`), `DeadControlFlowRemovalPass` (`dead-control-flow-removal: on|off`), `DeadFunctionRemovalPass` (`dead-function-removal: on|off`) and `UnusedDeclarationRemovalPass` (`unused-declaration-removal: on|off`);
the configurable ones are off unless set (see the root README, `### loop-unrolling`, `### constant-folding` and `### variable-elision and variable-shifting`). Struct member reordering is still a no-op.

Each pass is a pure function `List<lines> -> PassResult` (`OptimizationPass`); passes know nothing about each other.

## Where things are

`src/main/java/caspien/optimizer/`: one file per pass, `BytecodeOptimizer` (pipeline), `Main` (CLI), `BytecodeParser` /
`BytecodeSerializer` / `BytecodeToken` (the text interchange format). `master_code.md` mirrors every file here.

## Related documents

Root `README.md` (orchestrator, `toolchain.config`), root `CLAUDE.md` (project memory), `LowerOrderGenerator/` (the register-form
pass that does the real code-quality work today lives there, not here).
```

### FILE: compiler.config
```
# Caspien compiler configuration.
#
# CallingConventions: every calling convention this compiler knows
# about. Selected per func/extern/function-pointer-type via the
# "@call_convention(name)" decorator (a bare name, not a string); the
# one named by "default" below is used wherever no decorator is
# written. Every field is validated at load time; "name" (the
# CC_START/CC_END/EXTERN_CALL label), argument-registers' own count
# (the "n" of "first n arguments get register-transferred") and
# argument-registers-float's own count (the identical "n" for a float
# argument) are now consulted by bytecode emission -- the rest
# (cleanup, alignment, the return-register fields) are parsed and
# validated now, ready for the not-yet-built assembly stage to
# actually use.
#
# shared-argument-position: real, fixed ABI fact about a convention,
# not a stylistic choice -- omit it (false) for a convention where an
# integer argument and a float argument advance two genuinely
# independent counters (SysV's own rdi/rsi/.../xmm0/xmm1/... -- a float
# argument never "uses up" an integer register slot, or vice versa; the
# same is true of arm64's AAPCS64, x0-x7 and v0-v7 counted
# independently). Set it true only for a convention where the two
# banks instead share one running position counter, so which bank a
# given argument's *n*th position falls into depends on that
# argument's own type but the position itself is shared -- win64's own
# documented behavior: "void f(int a, float b, int c)" passes a in
# RCX (position 0), b in XMM1 (position 1, not XMM0), c in R8
# (position 2) -- confirmed against the real Windows x64 calling
# convention, not assumed from the register list lengths alone (which,
# coincidentally, are also 4-and-4 for win64, but that isn't what makes
# it shared -- arm64's own lists are 8-and-8, equally coincidentally,
# and arm64 is independently-counted, not shared).
#
# This section and the "target" key below, in ===codegen.config===,
# have to actually agree with each other -- Compiler.java's own
# splitToolchainConfig validates this now (win64's shape must pair
# with a windows/windows_gnu target, sysv_x64's shape with a linux
# target) and refuses to run at all otherwise, rather than silently
# producing a build whose stack-argument-overflow case reads from the
# wrong offset.
# deferred-operands: on|off -- the LowerOrderGenerator's register-form pass.
# on  = temporaries of integer (1/2/4/8-byte), f32 and pointer expressions live in
#       registers (R_* lines in the low-order bytecode);
#       variables stay in their frame slots (see variables-in-registers). Anything the pass does not
#       recognise falls back to the plain stack-machine code.
# off = the pass is skipped and the low-order bytecode is unchanged.
# Both settings produce programs with identical output.
deferred-operands: on
# variables-in-registers: on|off -- needs deferred-operands: on. Hot scalar locals (the Optimizer's REGVAR hints) live in
# r13/r14 instead of their frame slots, at most two per function, only where every use of the slot can be renamed.
# off = they stay in memory and the assembly is exactly what it was without the feature.
variables-in-registers: on
# float-variables-in-registers: on|off -- needs variables-in-registers: on. Hot f32 locals live in xmm registers (up to six per function:
# xmm8-13 on SysV, xmm6-11 on win64). SysV has no callee-saved xmm registers, so every call spills them to their frame slots and
# reloads them; win64 keeps them across calls. Measured gain is small (0-15% on the n-body programs). Shipped off: least-exercised
# feature (no float fuzzing, Intel/MASM target unexecuted). off = float hints go to the integer registers as before.
float-variables-in-registers: off
# float-temporaries-in-registers: on|off -- needs deferred-operands: on (independent of float-variables-in-registers). The intermediate
# results of f32 expressions stay in xmm registers (four: SysV xmm4-7, win64 xmm12-15) instead of being copied to a general register and
# back around every operation. Measured: nbody_arr 0.23 -> 0.13 s, nbody_plain 0.53 -> 0.38 s. Shipped off with the other float switch.
float-temporaries-in-registers: off
# loop-unrolling: off|conservative|balanced|aggressive -- the Optimizer's LoopUnrollingPass (default off; a missing key is off).
# It unrolls only a `for` loop whose trip count can be read straight off literal bounds (`for i in 0..8`, or a fixed array).
# A variable bound (`for i in 0..n`, `for j in j0..5`) and every `loop{}` are never touched: this pass does no constant
# propagation, folding or other analysis to discover a bound. The loop variable stays a real variable in every copy.
#   preset        factor  full-unroll if trips <=  body lines <=  added lines per function <=
#   conservative     2            4                    40                400
#   balanced         4           16                   100               2000
#   aggressive       8         4096                  2000             200000
# A loop of at most `full` trips is unrolled completely. A longer one is unrolled `factor` times, with the leftover
# trips (trips mod factor) copied after it, when it has at least 2*factor trips. Any of the four numbers can be overridden
# by the keys below (whole numbers, 0 or more; 0 for factor or full switches that kind off). They have no effect while
# the preset is off. A malformed value stops the compile.
loop-unrolling: off
# loop-unroll-factor: 4
# loop-unroll-full-max-trips: 16
# loop-unroll-max-body-lines: 100
# loop-unroll-max-growth: 2000
# function-inlining: off|conservative|balanced|aggressive -- the Optimizer's FunctionInliningPass (default off; a missing key is off).
# A direct call (`CALL f`) is replaced by the body of f: the arguments are assigned to fresh copies of f's parameters, f's locals and labels are
# renamed per call site, and each `return` becomes a jump to the end of the copy (a callee that is one straight-line body with a single final
# `return` needs no jump at all). Constant folding, variable elision and the register hints then see the merged code.
# Never inlined: an `@async` function; anything called through a function pointer (`call()` / `INVOKE`); C / `extern` functions (there is no body
# to copy); `main` and the ghost-table / async / sleep glue functions; a function that uses `throw`, `try`/`catch` unwinding, the ghost-table
# unwind scaffolding (`gt_routine_address`), inline assembly, or a function-local `static`; a callee taking a struct, array or dynarray by value
# (a scalar or a `range` parameter is fine; a range is passed as two words; struct returns via the hidden destination pointer are fine). A call
# site in the middle of an expression, condition or argument list is inlined too. A recursive function has already been turned into a
# loop by the front end (in the ASTGenerator, before the Optimizer runs), so it is inlined like any other function.
#   preset        callee lines <=   rounds (depth)   added lines per function <=
#   conservative        12               1                  200
#   balanced            40               3                 2000
#   aggressive       unlimited          32              100000000
# Any of the three numbers can be overridden by the keys below (whole numbers, 0 or more); they have no effect while the preset is off.
# A malformed value stops the compile.
function-inlining: off
# inline-max-callee-lines: 40
# inline-max-depth: 3
# inline-max-growth: 2000
# constant-folding: on|off -- the Optimizer's ConstantFoldingPass (default off; a missing key is off). Collapses an operator whose operands are
# literals written in the bytecode into one literal: `2 + 3` -> `5`, `(10 - 3) * 2` -> `14`, `3 < 4` -> `true`, `1.5 + 2.25` -> `3.75`.
# Integers (64-bit arithmetic wraps exactly as at runtime; compares at every width), bools, f32/f64 (+ - * / compares, negation) and the
# TRUNC/SEXT/ZEXT casts. It never looks at what a variable holds (no constant propagation), and a float result that would be infinity,
# NaN or negative zero is left for the runtime. Division by a literal zero is already a compile error in the language.
constant-folding: off
# variable-elision: on|off -- the Optimizer's VariableElisionPass (default off; a missing key is off). Finds a local scalar (integer, f32/f64,
# bool) that is assigned exactly once, to a literal, and is never read before that assignment, never has its address taken (`raw`) and is
# not a parameter, and replaces every read with the literal (`PUSH x` -> `PUSH 3`); the assignment and the declaration are removed. Skips
# functions containing inline assembly. Run together with constant-folding, the substituted literals fold in turn; a `for` bound that was
# such a variable is rewritten to the literal, so loop-unrolling can unroll it.
variable-elision: off
# variable-shifting: on|off -- the Optimizer's VariableShiftingPass (default off; a missing key is off). A variable assigned several
# times, always to a literal, would be elidable if each assignment were its own variable. At each reassignment on straight-line code (not
# inside a loop, not after a branch that could skip it) it declares a new variable (x__s1, x__s2, ...) and points the assignment and every
# later reference at it. Only useful with variable-elision on (which then removes the pieces). Skips functions with inline assembly or catch bodies.
variable-shifting: off
# struct-unpacking: on|off -- the Optimizer's StructUnpackingPass (default off; a missing key is off). A local struct variable made only of
# scalar members (integers, f32/f64, bool) that is only ever constructed and accessed member by member (`p.x`, `p.x = ..`, `p.x += ..`) becomes one
# ordinary variable per member (p__x, p__y, ...), which elision, shifting and the register hints then treat like any other scalar. A struct that is
# copied, passed by address (`auto p`), has a member's address taken, or is rebuilt from its own members is left alone.
struct-unpacking: off
# dead-control-flow-removal: on|off -- the Optimizer's DeadControlFlowRemovalPass (default off; a missing key is off). Finds `PUSH true` / `CMP` / `JMP L`
# (a branch whose condition is a literal -- typically made so by variable elision + constant folding): with `true` the jump is deleted and the code
# it would have skipped to (the else branch) is removed; with `false` the jump becomes unconditional and the branch body it skipped is removed. Only code
# that becomes unreachable because of that rewrite is removed (flow reachability; catch entries and unwinding callsites count as reachable).
dead-control-flow-removal: off
# dead-function-removal: on|off -- the Optimizer's DeadFunctionRemovalPass (default off; a missing key is off). Removes every function that is never
# called and never has its address taken. Does nothing at all if the program contains an INVOKE (a `call(fp, ...)` through a function pointer) or has
# no `main`. Kept as roots: main, gt_init/gt_register/gt_alive_check/gt_destruct (the backend calls those by name), and any function with a special
# decorator (@par_call, @await_call, @sleep, @async, @lock, @guard, ...). Best run together with dead-control-flow-removal.
dead-function-removal: off
# unused-declaration-removal: on|off -- the Optimizer's UnusedDeclarationRemovalPass (default off; a missing key is off). Deletes `extern` declarations,
# top-level statics and string literals that nothing refers to any more (typically left behind by dead-function-removal / dead-control-flow-removal).
# Always kept: the externs the backend calls by fixed name (malloc, realloc, free, strlen, exit, pthread_exit, sched_yield) and `ghost_table`. Does
# nothing if the program has no `main` or contains inline assembly.
unused-declaration-removal: off
# variable-allocation-reordering: on|off -- the Optimizer's VariableAllocationReorderingPass (default off; a missing key is off). Reorders each function's
# hoisted local-variable slots: largest alignment first (8, 4, 2, 1), which removes the padding that declaration order such as u8, u64, u8, u64 leaves
# in the frame, and inside one alignment class the slot used most (mentions weighted 8^loopDepth) first, nearest rbp. gt_routine_address and
# gt_error_message stay the first two slots; parameters stay ahead of the locals; functions with inline assembly are left alone. Output is identical.
variable-allocation-reordering: off
# struct-member-reordering: on|off -- the Optimizer's StructMemberReorderingPass (default off; a missing key is off). Reorders each struct's members (largest alignment
# first, which removes interior padding) and rewrites every construction site the same way: stack/static literals, `new`, array literals and RVO. Structs are left alone if they
# use `extends` (or are extended), carry @lock/decorators, have a struct/array/pointer member, are pointed to by `raw` (punning, memcopy, C interop), are the target of a
# `let static n = sizeof(S)` (the front end already folded that number), or have any construction site the pass cannot parse with certainty. `___type` stays first. sizeof
# follows the new layout. Member initialisers are evaluated in the new order (they are pure expressions, so no visible effect). Linux verified; Intel/MASM unverified.
struct-member-reordering: off
CallingConventions:
    win64:
        cleanup: caller
        argument-registers: ["RCX", "RDX", "R8", "R9"]
        argument-registers-float: ["XMM0", "XMM1", "XMM2", "XMM3"]
        return-register: "RAX"
        return-register-float: "XMM0"
        alignment: 16
        shadow-stack: 32
        shared-argument-position: true
    sysv_x64:
        cleanup: caller
        argument-registers: ["RDI", "RSI", "RDX", "RCX", "R8", "R9"]
        argument-registers-float: ["XMM0", "XMM1", "XMM2", "XMM3", "XMM4", "XMM5", "XMM6", "XMM7"]
        return-register: "RAX"
        return-register-float: "XMM0"
        alignment: 16
        shadow-stack: 0
    arm64:
        cleanup: caller
        argument-registers: ["X0", "X1", "X2", "X3", "X4", "X5", "X6", "X7"]
        argument-registers-float: ["V0", "V1", "V2", "V3", "V4", "V5", "V6", "V7"]
        return-register: "X0"
        return-register-float: "V0"
        alignment: 16
        shadow-stack: 0
    default: win64

```

### FILE: src/main/java/caspien/optimizer/BytecodeOptimizer.java
```java
package caspien.optimizer;

import java.util.List;

/**
 * Top-level orchestrator for the "shallow methods" stage only. This
 * class used to also run the real lowering pipeline (membership
 * lowering, clone generation, drop-glue generation, ARG-to-ALLOC
 * lowering, address lowering) -- that work has moved to the sibling
 * caspien-lowerordergenerator project, run as its own separate program
 * against this program's own plain-text output, confirmed directly:
 * "separate the optimizer into two programs -- the shallow methods
 * architecture at the start of the optimizer will be extracted and be
 * the optimizer, and the actual work thats currently been done in the
 * optimizer will just be called the LowerOrderGenerator."
 *
 * Every pass driven by the fixed-point loop below is still a shallow
 * no-op (see each pass's own file) -- this class only wires up that
 * part of the pipeline's shape, not any real optimization logic yet.
 *
 * Pipeline:
 *
 *   repeat until no change (outer loop):
 *       struct member reordering (placeholder, see StructMemberReorderingPass; first, because it
 *           changes layout)
 *       sizeof resolution (always on: SIZEOF Struct T -> PUSH n T from the STRUCT declarations as
 *           they now stand, so constant folding below sees the literal)
 *       struct unpacking
 *
 *       repeat until no change (inner loop):
 *           constant folding
 *           variable elision
 *           variable shifting
 *
 *       dead control flow removal
 *       dead function removal
 *       unused declaration removal (externs, globals/statics, strings nothing refers to any more)
 *       loop unrolling
 *       function inlining
 *
 *   variable allocation reordering (once, after the loop above --
 *   placeholder, not implemented yet, see
 *   VariableAllocationReorderingPass)
 *
 * A note on where the two reordering passes ended up: in the original,
 * single combined project these two placeholders sat *inside* the
 * lowering sequence, specifically "immediately after ArgToAllocLoweringPass
 * and immediately before AddressLoweringPass" -- both of which have now
 * moved to caspien-lowerordergenerator, a genuinely separate program
 * this project's output is handed to as plain text, the same way this
 * project itself only ever receives caspien-compiler's plain-text
 * output. There is no longer a single in-process pipeline for a pass to
 * sit "in the middle of." Since both reordering passes are still true
 * no-ops (neither has any real logic yet), they've been placed here,
 * at the end of this project's own pipeline, alongside every other
 * still-a-placeholder shallow method -- the closest available
 * equivalent to their originally-intended position, given the process
 * boundary. This is a judgment call, not something asked for directly:
 * once either pass grows real logic that needs post-ARG-to-ALLOC or
 * pre-address-lowering information, it may need to move to
 * caspien-lowerordergenerator instead (or that project may need its own
 * pre-address-lowering hook) -- worth revisiting then, not decided here.
 */
public class BytecodeOptimizer {

    private final SizeofResolutionPass sizeofResolution = new SizeofResolutionPass();
    private final StructUnpackingPass structUnpacking;
    private final ConstantFoldingPass constantFolding;
    private final VariableElisionPass variableElision;
    private final VariableShiftingPass variableShifting;
    private final DeadControlFlowRemovalPass deadControlFlowRemoval;
    private final DeadFunctionRemovalPass deadFunctionRemoval;
    private final UnusedDeclarationRemovalPass unusedDeclarationRemoval;
    private final LoopUnrollingPass loopUnrolling;
    private final FunctionInliningPass functionInlining;
    private final StructMemberReorderingPass structMemberReordering;
    private final VariableAllocationReorderingPass variableAllocationReordering;
    private final RegVarHintPass regVarHint = new RegVarHintPass();

    public BytecodeOptimizer() {
        this(UnrollConfig.disabled(), FoldConfig.disabled());
    }

    public BytecodeOptimizer(UnrollConfig unrollConfig) {
        this(unrollConfig, FoldConfig.disabled());
    }

    public BytecodeOptimizer(UnrollConfig unrollConfig, FoldConfig foldConfig) {
        this(unrollConfig, foldConfig, VariableConfig.disabled());
    }

    public BytecodeOptimizer(UnrollConfig unrollConfig, FoldConfig foldConfig, VariableConfig variableConfig) {
        this(unrollConfig, foldConfig, variableConfig, InlineConfig.disabled());
    }

    public BytecodeOptimizer(UnrollConfig unrollConfig, FoldConfig foldConfig, VariableConfig variableConfig, InlineConfig inlineConfig) {
        this.functionInlining = new FunctionInliningPass(inlineConfig);
        this.loopUnrolling = new LoopUnrollingPass(unrollConfig);
        this.constantFolding = new ConstantFoldingPass(foldConfig.enabled);
        this.unusedDeclarationRemoval = new UnusedDeclarationRemovalPass(variableConfig.unusedDecls);
        this.deadFunctionRemoval = new DeadFunctionRemovalPass(variableConfig.deadFunctions);
        this.deadControlFlowRemoval = new DeadControlFlowRemovalPass(variableConfig.deadFlow);
        this.structUnpacking = new StructUnpackingPass(variableConfig.unpacking);
        this.variableElision = new VariableElisionPass(variableConfig.elision);
        this.variableShifting = new VariableShiftingPass(variableConfig.shifting);
        this.variableAllocationReordering = new VariableAllocationReorderingPass(variableConfig.allocReorder);
        this.structMemberReordering = new StructMemberReorderingPass(variableConfig.structReorder);
    }

    public List<List<BytecodeToken>> optimize(List<List<BytecodeToken>> input) {
        List<List<BytecodeToken>> lines = input;

        boolean outerChanged;
        do {
            outerChanged = false;

            // Layout first (a no-op stub today), then SIZEOF -> PUSH n against the layout as it now stands,
            // so everything after (constant folding above all) sees the literal.
            PassResult r = structMemberReordering.run(lines);
            lines = r.lines;
            outerChanged |= r.changed;

            r = sizeofResolution.run(lines);
            lines = r.lines;
            outerChanged |= r.changed;

            r = structUnpacking.run(lines);
            lines = r.lines;
            outerChanged |= r.changed;

            boolean innerLoopChangedAnything = false;
            boolean innerChanged;
            do {
                innerChanged = false;

                r = constantFolding.run(lines);
                lines = r.lines;
                innerChanged |= r.changed;

                r = variableElision.run(lines);
                lines = r.lines;
                innerChanged |= r.changed;

                r = variableShifting.run(lines);
                lines = r.lines;
                innerChanged |= r.changed;

                innerLoopChangedAnything |= innerChanged;
            } while (innerChanged);
            outerChanged |= innerLoopChangedAnything;

            r = deadControlFlowRemoval.run(lines);
            lines = r.lines;
            outerChanged |= r.changed;

            r = deadFunctionRemoval.run(lines);
            lines = r.lines;
            outerChanged |= r.changed;

            r = unusedDeclarationRemoval.run(lines);
            lines = r.lines;
            outerChanged |= r.changed;

            r = loopUnrolling.run(lines);
            lines = r.lines;
            outerChanged |= r.changed;

            r = functionInlining.run(lines);
            lines = r.lines;
            outerChanged |= r.changed;
        } while (outerChanged);

        lines = variableAllocationReordering.run(lines).lines;

        // REGVAR hints (once, last): which scalar locals are worth a register. Names no register; see RegVarHintPass.
        lines = regVarHint.run(lines).lines;

        return lines;
    }
}
```

### FILE: src/main/java/caspien/optimizer/BytecodeParser.java
```java
package caspien.optimizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads already-emitted, plain-text bytecode (BytecodeEmitter's own
 * output) back into the List<List<BytecodeToken>> shape every
 * OptimizationPass works with -- one inner list per line, split on
 * whitespace, with a quoted string kept as a single token (its own
 * spaces included) rather than being split apart -- and a char literal
 * ("' '" included) likewise kept as one token. No regex, hand-scanned
 * character by character, matching this project's own "no regex,
 * anywhere, ever" convention.
 */
public class BytecodeParser {

    public List<List<BytecodeToken>> parse(List<String> rawLines, String originFile) {
        List<List<BytecodeToken>> result = new ArrayList<>();
        for (int lineNo = 0; lineNo < rawLines.size(); lineNo++) {
            result.add(parseLine(rawLines.get(lineNo), originFile, lineNo + 1));
        }
        return result;
    }

    private List<BytecodeToken> parseLine(String rawLine, String originFile, int lineNumber) {
        List<BytecodeToken> tokens = new ArrayList<>();
        int i = 0;
        int len = rawLine.length();
        while (i < len) {
            char c = rawLine.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '"') {
                int start = i;
                i++;
                while (i < len && rawLine.charAt(i) != '"') {
                    if (rawLine.charAt(i) == '\\' && i + 1 < len) {
                        i++;
                    }
                    i++;
                }
                if (i < len) {
                    i++; // consume closing quote
                }
                String text = rawLine.substring(start, Math.min(i, len));
                tokens.add(new BytecodeToken(text, originFile, lineNumber, BytecodeToken.Kind.STRING));
                continue;
            }
            if (c == '\'') {
                // A char-literal operand ("'a'", "'\n'", and -- the case a plain
                // whitespace split gets wrong -- "' '"): kept as ONE token, the
                // same way a quoted string is. The shape is fixed by
                // BytecodeEmitter.escapeForBytecode: a single quote, exactly one
                // character of content (or a backslash plus one character), then
                // a closing single quote, and the literal must end the token.
                int end = -1;
                if (i + 3 < len && rawLine.charAt(i + 1) == '\\' && rawLine.charAt(i + 3) == '\'') {
                    end = i + 4;
                } else if (i + 2 < len && rawLine.charAt(i + 2) == '\'') {
                    end = i + 3;
                }
                if (end != -1 && (end == len || Character.isWhitespace(rawLine.charAt(end)))) {
                    tokens.add(new BytecodeToken(rawLine.substring(i, end), originFile, lineNumber, BytecodeToken.Kind.CODE));
                    i = end;
                    continue;
                }
            }
            int start = i;
            while (i < len && !Character.isWhitespace(rawLine.charAt(i))) {
                i++;
            }
            tokens.add(new BytecodeToken(rawLine.substring(start, i), originFile, lineNumber, BytecodeToken.Kind.CODE));
        }
        return tokens;
    }
}
```

### FILE: src/main/java/caspien/optimizer/BytecodeSerializer.java
```java
package caspien.optimizer;

import java.util.List;

/** The inverse of BytecodeParser -- joins each line's tokens back with a single space, one line per entry. */
public class BytecodeSerializer {

    public String serialize(List<List<BytecodeToken>> lines) {
        StringBuilder sb = new StringBuilder();
        for (List<BytecodeToken> line : lines) {
            for (int i = 0; i < line.size(); i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(line.get(i).text);
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
```

### FILE: src/main/java/caspien/optimizer/BytecodeToken.java
```java
package caspien.optimizer;

/**
 * One token of already-emitted bytecode text, as read back in by the
 * optimizer -- deliberately a much smaller shape than the main
 * compiler's own Token class, since the optimizer never needs anything
 * beyond "what does this token say, where did it come from, and what
 * kind of thing is it." The whole program is a List<List<BytecodeToken>>
 * (BytecodeOptimizer.java), nested by line -- one inner list per line
 * of bytecode text, in original emission order.
 */
public class BytecodeToken {

    public enum Kind {
        /** An ordinary bytecode mnemonic/operand -- "PUSH", "12", "imut_u64", a label, etc. */
        CODE,
        /** A quoted string literal appearing in the bytecode (e.g. a hoisted STRING line's own text). */
        STRING,
        /** A comment -- bytecode has no comment syntax of its own today; reserved for future use. */
        COMMENT
    }

    public final String text;
    public final String file;
    public final int line;
    public final Kind kind;

    public BytecodeToken(String text, String file, int line, Kind kind) {
        this.text = text;
        this.file = file;
        this.line = line;
        this.kind = kind;
    }

    @Override
    public String toString() {
        return text;
    }
}
```

### FILE: src/main/java/caspien/optimizer/ConstantFoldingPass.java
```java
package caspien.optimizer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Constant folding: collapses an operator whose operands are literals written in the bytecode itself into one
 * pushed literal ("PUSH 12 / PUSH 6 / ADD" becomes "PUSH 18"). Nested cases fold in one run ("PUSH 10 / PUSH 3 / SUB /
 * PUSH 2 / MUL" becomes "PUSH 14") because the result is re-examined as soon as the next operator is reached.
 *
 * What it does NOT do (other passes' jobs, kept apart on purpose): it never looks at what a variable holds
 * (no constant propagation), never removes a variable, and never looks across a label or any other line. A fold
 * happens only when the operand lines sit directly in front of the operator line.
 *
 * Off unless "constant-folding: on" is set in compiler.config (see FoldConfig).
 *
 * Folded operators (types are read from the operator line; the result is pushed with the operator's own result type):
 *   integers   ADD SUB MUL DIV MOD on u64/s64 only (a narrower stack word keeps its high bits until stored,
 *              so a folded narrow value could differ from what the backend computes); the bitwise group BITS_AND BITS_OR
 *              BITS_XOR BITS_NOT SHL SHR (SHR on a signed type is the arithmetic shift) at EVERY width u8..s64, because
 *              their runtime result is fully defined by the low `width` bits of the operands (see foldBits): a shift count
 *              is read as an unsigned number of the operand's own width and a count >= the width gives 0 (SHL, SHR) or the
 *              sign fill (SAR) -- the rule the backend implements at run time too; compares LT LT_EQ GT GT_EQ EQ NEQ on
 *              every integer width, signed or unsigned as the type says; TRUNC to an unsigned (or non-negative) narrow
 *              type; SEXT/ZEXT to a 64-bit type. Arithmetic wraps to 64 bits exactly as the hardware does.
 *   bool       AND OR NOT
 *   f32/f64    ADD SUB MUL DIV, the six compares, NEG. A result that is NaN, infinite or negative zero is NOT folded (the
 *              bytecode has no literal for it); the operator is left for the runtime.
 * Left alone: division or modulo by zero (the language already rejects a literal zero divisor), signed MIN / -1, integer NEG (the emitter already folds a negated literal), anything with a non-literal operand.
 */
public class ConstantFoldingPass implements OptimizationPass {

    private final boolean enabled;

    public ConstantFoldingPass() {
        this(false);
    }

    public ConstantFoldingPass(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "constant-folding";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return new PassResult(lines, false);
        }
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        boolean changed = false;
        for (List<BytecodeToken> line : lines) {
            List<BytecodeToken> folded = tryFold(out, line);
            if (folded != null) {
                changed = true; // tryFold already removed the operand lines from `out`
                out.add(folded);
            } else {
                out.add(line);
            }
        }
        return changed ? new PassResult(out, true) : new PassResult(lines, false);
    }

    // ---- literal and type helpers ---------------------------------------------------------------------------------

    /** "indeterminate_u64" / "mut_u64" / "imut_f32" -> "u64"; anything else (pointers, structs, ...) -> null. */
    static String base(String type) {
        String b;
        if (type.startsWith("indeterminate_")) b = type.substring(14);
        else if (type.startsWith("imut_")) b = type.substring(5);
        else if (type.startsWith("mut_")) b = type.substring(4);
        else return null;
        switch (b) {
            case "u8": case "u16": case "u32": case "u64":
            case "s8": case "s16": case "s32": case "s64":
            case "f32": case "f64": case "bool":
                return b;
            default:
                return null;
        }
    }

    static boolean isInt(String b) {
        return b != null && (b.charAt(0) == 'u' || b.charAt(0) == 's');
    }

    private static boolean isFloat(String b) {
        return b != null && b.charAt(0) == 'f';
    }

    private static boolean signed(String b) {
        return b.charAt(0) == 's';
    }

    static int width(String b) {
        return Integer.parseInt(b.substring(1));
    }

    private static BigInteger wrap(BigInteger v, String b) {
        int w = width(b);
        BigInteger mod = BigInteger.ONE.shiftLeft(w);
        BigInteger r = v.mod(mod);
        if (signed(b) && r.testBit(w - 1)) {
            r = r.subtract(mod);
        }
        return r;
    }

    private static boolean fits(BigInteger v, String b) {
        int w = width(b);
        if (signed(b)) {
            return v.compareTo(BigInteger.ONE.shiftLeft(w - 1).negate()) >= 0 && v.compareTo(BigInteger.ONE.shiftLeft(w - 1)) < 0;
        }
        return v.signum() >= 0 && v.compareTo(BigInteger.ONE.shiftLeft(w)) < 0;
    }

    private static boolean isIntText(String s) {
        int i = s.startsWith("-") ? 1 : 0;
        if (i >= s.length()) return false;
        for (; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return true;
    }

    /** "1.5", "-0.25": digits, one point, digits (no exponent -- anything fancier is left alone). */
    private static boolean isFloatText(String s) {
        int i = s.startsWith("-") ? 1 : 0;
        int dot = s.indexOf('.');
        if (dot <= i || dot == s.length() - 1) return false;
        for (int k = i; k < s.length(); k++) {
            if (k != dot && (s.charAt(k) < '0' || s.charAt(k) > '9')) return false;
        }
        return true;
    }

    /** A literal PUSH: "PUSH <literal> <type>" whose literal really is one of the type's kind; else null. */
    static Lit lit(List<BytecodeToken> line) {
        if (line.size() != 3 || line.get(0).kind != BytecodeToken.Kind.CODE || !line.get(0).text.equals("PUSH")) return null;
        String text = line.get(1).text, type = line.get(2).text, b = base(type);
        if (b == null) return null;
        Lit l = new Lit();
        l.type = type;
        l.base = b;
        if (isInt(b)) {
            if (!isIntText(text)) return null;
            l.i = new BigInteger(text);
            if (!fits(l.i, b)) return null;
        } else if (isFloat(b)) {
            if (!isFloatText(text)) return null;
            if (b.equals("f32")) {
                l.d = Float.parseFloat(text);
                if (Float.isInfinite((float) l.d)) return null;
            } else {
                l.d = Double.parseDouble(text);
                if (Double.isInfinite(l.d)) return null;
            }
        } else { // bool
            if (text.equals("true")) l.b = true;
            else if (text.equals("false")) l.b = false;
            else return null;
        }
        return l;
    }

    static final class Lit {
        String type, base;
        BigInteger i;
        double d;      // an f32 is held exactly as a double
        boolean b;
    }

    private static String floatText(double v, boolean single) {
        String s = single ? Float.toString((float) v) : Double.toString(v);
        String plain = new BigDecimal(s).toPlainString();
        return plain.indexOf('.') < 0 ? plain + ".0" : plain;
    }

    private static boolean badFloatResult(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) || (v == 0.0 && 1.0 / v < 0);
    }

    private static List<BytecodeToken> push(BytecodeToken at, String value, String type) {
        List<BytecodeToken> l = new ArrayList<>(3);
        l.add(new BytecodeToken("PUSH", at.file, at.line, BytecodeToken.Kind.CODE));
        l.add(new BytecodeToken(value, at.file, at.line, BytecodeToken.Kind.CODE));
        l.add(new BytecodeToken(type, at.file, at.line, BytecodeToken.Kind.CODE));
        return l;
    }

    // ---- the fold ---------------------------------------------------------------------------------------------------

    /** If `line` is an operator whose operand PUSH lines are the last ones in `out`, removes them and returns the result PUSH. */
    private static List<BytecodeToken> tryFold(List<List<BytecodeToken>> out, List<BytecodeToken> line) {
        if (line.isEmpty() || line.get(0).kind != BytecodeToken.Kind.CODE) return null;
        for (BytecodeToken t : line) {
            if (t.kind != BytecodeToken.Kind.CODE) return null;
        }
        String op = line.get(0).text;
        int n = out.size();
        switch (op) {
            case "NOT": case "NEG": case "TRUNC": case "SEXT": case "ZEXT": case "BITS_NOT": {
                if (line.size() != 3 || n < 1) return null;
                Lit a = lit(out.get(n - 1));
                if (a == null) return null;
                List<BytecodeToken> r = unary(op, a, line);
                if (r != null) out.remove(n - 1);
                return r;
            }
            case "ADD": case "SUB": case "MUL": case "DIV": case "MOD": case "SHL": case "SHR": case "BITS_OR": case "BITS_AND": case "BITS_XOR":
            case "LT": case "LT_EQ": case "GT": case "GT_EQ": case "EQ": case "NEQ": case "AND": case "OR": {
                if (line.size() != 4 || n < 2) return null;
                Lit a = lit(out.get(n - 2)), b = lit(out.get(n - 1));
                if (a == null || b == null) return null;
                List<BytecodeToken> r = binary(op, a, b, line);
                if (r != null) {
                    out.remove(n - 1);
                    out.remove(n - 2);
                }
                return r;
            }
            default:
                return null;
        }
    }

    private static List<BytecodeToken> unary(String op, Lit a, List<BytecodeToken> line) {
        BytecodeToken at = line.get(0);
        String t1 = line.get(1).text, t2 = line.get(2).text;
        switch (op) {
            case "NOT": {
                if (!"bool".equals(a.base) || !"bool".equals(base(t1)) || !"bool".equals(base(t2))) return null;
                return push(at, a.b ? "false" : "true", t2);
            }
            case "BITS_NOT": {
                String b = base(t1);
                if (!isInt(b) || !b.equals(a.base) || !b.equals(base(t2))) return null;
                BigInteger mask = BigInteger.ONE.shiftLeft(width(b)).subtract(BigInteger.ONE);
                return push(at, wrap(a.i.and(mask).xor(mask), b).toString(), t2);
            }
            case "NEG": {
                // floats only: an integer negation of a literal is already folded by the emitter
                if (!isFloat(a.base) || !a.base.equals(base(t1)) || !a.base.equals(base(t2))) return null;
                double r = -a.d;
                if (badFloatResult(r)) return null;
                return push(at, floatText(r, a.base.equals("f32")), t2);
            }
            case "TRUNC": {
                String src = base(t1), dst = base(t2);
                if (!isInt(src) || !isInt(dst) || !src.equals(a.base) || width(dst) >= width(src)) return null;
                BigInteger r = wrap(a.i, dst);
                if (signed(dst) && r.signum() < 0) return null; // a negative narrow literal push is not relied on
                return push(at, r.toString(), t2);
            }
            case "SEXT": case "ZEXT": {
                String src = base(t1), dst = base(t2);
                if (!isInt(src) || !isInt(dst) || !src.equals(a.base) || width(dst) != 64 || width(src) >= 64) return null;
                if (op.equals("SEXT") != signed(src) || signed(src) != signed(dst)) return null;
                return push(at, a.i.toString(), t2);
            }
            default:
                return null;
        }
    }

    /**
     * The bitwise group on two in-range literals of integer base type `b` (u8..s64). Every operand is first reduced to its
     * unsigned width-bit pattern (a negative signed literal becomes 2^w + v), the operation runs on those patterns, and the
     * result is converted back to the type's own value range -- exactly what the stack and register forms compute on the
     * low `w` bits. Shifts: the count is the unsigned pattern of the right operand; SHL/SHR by a count >= w give 0, SHR on a
     * signed type (the arithmetic shift) gives the sign fill (0 or -1); smaller counts shift normally (SHL discards the bits
     * pushed past the width).
     */
    static BigInteger foldBits(String op, String b, BigInteger x, BigInteger y) {
        int w = width(b);
        BigInteger mask = BigInteger.ONE.shiftLeft(w).subtract(BigInteger.ONE);
        BigInteger ux = x.and(mask), uy = y.and(mask);
        BigInteger r;
        switch (op) {
            case "BITS_AND": r = ux.and(uy); break;
            case "BITS_OR": r = ux.or(uy); break;
            case "BITS_XOR": r = ux.xor(uy); break;
            case "SHL":
                r = uy.compareTo(BigInteger.valueOf(w)) >= 0 ? BigInteger.ZERO : ux.shiftLeft(uy.intValue()).and(mask);
                break;
            default: { // SHR
                boolean wide = uy.compareTo(BigInteger.valueOf(w)) >= 0;
                if (signed(b)) {
                    r = wide ? (x.signum() < 0 ? BigInteger.ONE.negate() : BigInteger.ZERO) : x.shiftRight(uy.intValue());
                } else {
                    r = wide ? BigInteger.ZERO : ux.shiftRight(uy.intValue());
                }
                break;
            }
        }
        return wrap(r, b);
    }

    private static List<BytecodeToken> binary(String op, Lit a, Lit b, List<BytecodeToken> line) {
        BytecodeToken at = line.get(0);
        String lt = line.get(1).text, rt = line.get(2).text, res = line.get(3).text;
        String lb = base(lt), rb = base(rt), resB = base(res);
        if (lb == null || resB == null || !lb.equals(rb) || !lb.equals(a.base) || !lb.equals(b.base)) return null;

        boolean compare = op.equals("LT") || op.equals("LT_EQ") || op.equals("GT") || op.equals("GT_EQ")
                || op.equals("EQ") || op.equals("NEQ");
        if (op.equals("AND") || op.equals("OR")) {
            if (!lb.equals("bool") || !resB.equals("bool")) return null;
            return push(at, (op.equals("AND") ? a.b && b.b : a.b || b.b) ? "true" : "false", res);
        }
        if (compare) {
            if (!resB.equals("bool") || lb.equals("bool")) return null;
            int c;
            if (isInt(lb)) {
                c = a.i.compareTo(b.i);
            } else {
                c = a.d < b.d ? -1 : a.d > b.d ? 1 : 0; // literals are never NaN
            }
            boolean v;
            switch (op) {
                case "LT": v = c < 0; break;
                case "LT_EQ": v = c <= 0; break;
                case "GT": v = c > 0; break;
                case "GT_EQ": v = c >= 0; break;
                case "EQ": v = c == 0; break;
                default: v = c != 0; break;
            }
            return push(at, v ? "true" : "false", res);
        }
        if (!lb.equals(resB)) return null;
        if (isFloat(lb)) {
            boolean single = lb.equals("f32");
            double r;
            switch (op) {
                case "ADD": r = single ? (double) ((float) a.d + (float) b.d) : a.d + b.d; break;
                case "SUB": r = single ? (double) ((float) a.d - (float) b.d) : a.d - b.d; break;
                case "MUL": r = single ? (double) ((float) a.d * (float) b.d) : a.d * b.d; break;
                case "DIV": r = single ? (double) ((float) a.d / (float) b.d) : a.d / b.d; break;
                default: return null;
            }
            if (badFloatResult(r)) return null;
            return push(at, floatText(r, single), res);
        }
        if (isInt(lb) && (op.equals("BITS_AND") || op.equals("BITS_OR") || op.equals("BITS_XOR") || op.equals("SHL") || op.equals("SHR"))) {
            return push(at, foldBits(op, lb, a.i, b.i).toString(), res);
        }
        if (!isInt(lb) || width(lb) != 64) return null;
        BigInteger x = a.i, y = b.i, r;
        switch (op) {
            case "ADD": r = x.add(y); break;
            case "SUB": r = x.subtract(y); break;
            case "MUL": r = x.multiply(y); break;
            case "DIV": case "MOD": {
                if (y.signum() == 0) return null;
                if (signed(lb) && y.equals(BigInteger.ONE.negate()) && x.equals(BigInteger.ONE.shiftLeft(63).negate())) return null;
                r = op.equals("DIV") ? x.divide(y) : x.remainder(y); // truncating, like idiv/div
                break;
            }
            default: return null;
        }
        return push(at, wrap(r, lb).toString(), res);
    }
}
```

### FILE: src/main/java/caspien/optimizer/DeadControlFlowRemovalPass.java
```java
package caspien.optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds a provably-constant branch condition and removes the branch it guards (switch: {@code dead-control-flow-removal: on|off}, default off).
 *
 * The pattern is the three lines {@code PUSH true|false T ; CMP ; JMP L}, where {@code CMP ; JMP L} is "jump to L when the condition is false":
 *   - {@code PUSH true}:  the jump never happens, the three lines are deleted and execution falls through into the branch body; the code at L
 *                         (typically the else branch) is dead when nothing else refers to L;
 *   - {@code PUSH false}: the jump always happens, the three lines become {@code JMP L} and the code between it and L (the branch body) is dead
 *                         unless something jumps into it.
 * What is dead is decided by flow reachability over the function (fallthrough, JMP, CMP+JMP both ways; RET, THROW and an unconditional JMP end a
 * path; the roots are the function start, every label that is not one of the compiler's structured if/loop/for/match labels, and every label
 * referenced by something other than a JMP -- catch entries, unwinding callsites). Only lines that become unreachable BECAUSE of the rewrite are
 * deleted; code that was already unreachable before it is left alone. Declarations (ALLOC, ARG, REGVAR, ...) are never deleted. Afterwards a {@code JMP L} directly followed by the definition of L is dropped.
 * Only functions without ASM_START are touched. No other unreachable-code analysis is done.
 */
public class DeadControlFlowRemovalPass implements OptimizationPass {

    private final boolean enabled;

    public DeadControlFlowRemovalPass() {
        this(false);
    }

    public DeadControlFlowRemovalPass(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "dead-control-flow-removal";
    }

    private static final String[] STRUCTURAL = {"@if_branch_", "@end_of_if_", "@loop_", "@loop_end_", "@for_", "@for_end_", "@match_branch_", "@end_of_match_"};
    private static final Set<String> DECLS = new HashSet<>(List.of("ALLOC", "ARG", "REGVAR", "RETURNS", "FUNC_DECORATE", "STRING", "ALLOC_STATIC"));

    private static boolean isStructural(String label) {
        for (String p : STRUCTURAL) {
            if (label.startsWith(p)) return true;
        }
        return false;
    }

    private static boolean isLabelDef(List<BytecodeToken> l) {
        return l.size() == 1 && l.get(0).kind == BytecodeToken.Kind.CODE && l.get(0).text.startsWith("@") && l.get(0).text.endsWith(":");
    }

    private static String labelName(List<BytecodeToken> l) {
        String t = l.get(0).text;
        return t.substring(0, t.length() - 1);
    }

    private static boolean isBoolLit(List<BytecodeToken> l, String lit) {
        return "PUSH".equals(VarAnalysis.mnemonic(l)) && l.size() == 3 && l.get(1).text.equals(lit);
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> input) {
        if (!enabled) {
            return new PassResult(input, false);
        }
        List<List<BytecodeToken>> lines = new ArrayList<>(input);
        boolean changed = false;
        boolean again = true;
        while (again) {
            again = false;
            for (int[] f : VarAnalysis.functions(lines)) {
                if (VarAnalysis.hasMnemonic(lines, f[0], f[1], "ASM_START")) continue;
                if (rewriteOne(lines, f[0], f[1])) {
                    changed = true;
                    again = true;
                    break;
                }
            }
        }
        return new PassResult(changed ? lines : input, changed);
    }

    /** Applies the first applicable rewrite in [s, e]; returns whether one was made. */
    private static boolean rewriteOne(List<List<BytecodeToken>> L, int s, int e) {
        for (int i = s + 1; i + 2 <= e; i++) {
            List<BytecodeToken> p = L.get(i);
            boolean isTrue = isBoolLit(p, "true"), isFalse = isBoolLit(p, "false");
            if (!isTrue && !isFalse) continue;
            List<BytecodeToken> c = L.get(i + 1), j = L.get(i + 2);
            if (!"CMP".equals(VarAnalysis.mnemonic(c)) || c.size() != 1) continue;
            if (!"JMP".equals(VarAnalysis.mnemonic(j)) || j.size() != 2) continue;
            // Lines that are already unreachable before this rewrite are none of this pass's business.
            Set<List<BytecodeToken>> alreadyDead = newIdentitySet();
            boolean[] before = reachable(L, s, e);
            for (int k = s; k <= e; k++) {
                if (!before[k - s]) alreadyDead.add(L.get(k));
            }
            int newEnd;
            if (isTrue) {
                L.subList(i, i + 3).clear();                       // never jumps: fall through into the branch
                newEnd = e - 3;
            } else {
                L.set(i, j);                                       // always jumps
                L.subList(i + 1, i + 3).clear();
                newEnd = e - 2;
            }
            boolean[] after = reachable(L, s, newEnd);
            boolean[] keep = new boolean[newEnd - s + 1];
            for (int k = s; k <= newEnd; k++) {
                List<BytecodeToken> l = L.get(k);
                boolean dead = !after[k - s] && !alreadyDead.contains(l);
                String m = VarAnalysis.mnemonic(l);
                if (dead && m != null && (DECLS.contains(m) || m.equals("FUNC_START") || m.equals("FUNC_END"))) dead = false;
                keep[k - s] = !dead;
            }
            // A label that a kept line still names must stay: code that was unreachable before this rewrite (for example the jump that
            // follows an inlined early return) is left alone, and it may still refer to a label whose only reachable jumps just vanished.
            boolean again = true;
            while (again) {
                again = false;
                Set<String> named = new HashSet<>();
                for (int k = s; k <= newEnd; k++) {
                    List<BytecodeToken> l = L.get(k);
                    if (!keep[k - s] || isLabelDef(l)) continue;
                    for (BytecodeToken t : l) if (t.text.startsWith("@")) named.add(t.text);
                }
                for (int k = s; k <= newEnd; k++) {
                    if (!keep[k - s] && isLabelDef(L.get(k)) && named.contains(labelName(L.get(k)))) { keep[k - s] = true; again = true; }
                }
            }
            List<List<BytecodeToken>> out = new ArrayList<>();
            for (int k = s; k <= newEnd; k++) if (keep[k - s]) out.add(L.get(k));
            L.subList(s, newEnd + 1).clear();
            L.addAll(s, out);
            dropJumpToNext(L, s, s + out.size() - 1);
            return true;
        }
        return false;
    }

    private static Set<List<BytecodeToken>> newIdentitySet() {
        return java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    }

    /**
     * Flow reachability over the lines [s, e] of one function. Roots: the function start and every label that is not one of the compiler's
     * structured control-flow labels or that is referenced by anything other than a JMP (catch entries, unwinding callsites, ...). A line
     * flows into the next one unless it is RET, THROW or a JMP that is not the second half of "CMP ; JMP" (which also flows on); JMP flows to its label.
     */
    private static boolean[] reachable(List<List<BytecodeToken>> L, int s, int e) {
        int n = e - s + 1;
        boolean[] seen = new boolean[n];
        Map<String, Integer> labels = new HashMap<>();
        Set<String> otherRefs = new HashSet<>();
        for (int k = s; k <= e; k++) {
            List<BytecodeToken> l = L.get(k);
            if (isLabelDef(l)) {
                labels.put(labelName(l), k);
                continue;
            }
            if ("JMP".equals(VarAnalysis.mnemonic(l))) continue;
            for (BytecodeToken t : l) {
                if (t.text.startsWith("@")) otherRefs.add(t.text);
            }
        }
        java.util.ArrayDeque<Integer> work = new java.util.ArrayDeque<>();
        work.add(s);
        for (Map.Entry<String, Integer> en : labels.entrySet()) {
            if (!isStructural(en.getKey()) || otherRefs.contains(en.getKey())) work.add(en.getValue());
        }
        while (!work.isEmpty()) {
            int k = work.poll();
            while (k <= e && !seen[k - s]) {
                seen[k - s] = true;
                List<BytecodeToken> l = L.get(k);
                String m = VarAnalysis.mnemonic(l);
                if ("FUNC_END".equals(m) || "RET".equals(m) || "THROW".equals(m)) break;
                if ("JMP".equals(m) && l.size() == 2) {
                    Integer tgt = labels.get(l.get(1).text);
                    if (tgt != null) work.add(tgt);
                    boolean conditional = k > s && "CMP".equals(VarAnalysis.mnemonic(L.get(k - 1)));
                    if (!conditional) break;
                }
                k++;
            }
        }
        return seen;
    }

    /** Removes an unconditional "JMP L" directly followed by "L:" in [s, e]. */
    private static void dropJumpToNext(List<List<BytecodeToken>> L, int s, int e) {
        int fs = Math.min(s, L.size() - 1);
        int fe = Math.min(e, L.size() - 1);
        for (int k = fe - 1; k > fs; k--) {
            List<BytecodeToken> l = L.get(k), nx = L.get(k + 1);
            if (!"JMP".equals(VarAnalysis.mnemonic(l)) || l.size() != 2 || !isLabelDef(nx)) continue;
            if (!l.get(1).text.equals(labelName(nx))) continue;
            if ("CMP".equals(VarAnalysis.mnemonic(L.get(k - 1)))) continue;
            L.remove(k);
        }
    }
}
```

### FILE: src/main/java/caspien/optimizer/DeadFunctionRemovalPass.java
```java
package caspien.optimizer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes every function that is never called and never has its address taken (switch: {@code dead-function-removal: on|off}, default off).
 *
 * Does nothing at all when the program contains an {@code INVOKE} (a call through a function pointer: the target is not known statically and
 * a pointer value could reach any function), and does nothing when there is no {@code main} (a library: everything is an entry point).
 *
 * Otherwise a function is live when it is reachable from a root through CALL / RECURSIVE_CALL sites or through any other line that names it as an
 * operand (a function whose address is taken, e.g. {@code PUSH f static_imut_func(..)}, counts as used). Roots: {@code main}; the four ghost-table hooks
 * {@code gt_init}, {@code gt_register}, {@code gt_alive_check}, {@code gt_destruct}, which the backend calls by fixed name with no CALL in the bytecode;
 * any function carrying a decorator other than {@code @pub}/{@code @throws}/{@code @recursive}/{@code @pure} (the compiler looks such functions up by
 * decorator, e.g. @gt_*, @par_call, @await_call, @sleep, @async, @lock, @unlock, @guard); and any function named on a line outside every function (global initialisers). Reachability, rather than
 * "is there a call site", means two functions that only call each other, or a function that only calls itself, are removed too. Only complete
 * FUNC_START..FUNC_END blocks are deleted. Most effective right after DeadControlFlowRemovalPass, which is what turns "called from a removed
 * branch" into "never called".
 */
public class DeadFunctionRemovalPass implements OptimizationPass {

    private final boolean enabled;

    public DeadFunctionRemovalPass() {
        this(false);
    }

    public DeadFunctionRemovalPass(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "dead-function-removal";
    }

    private static final Set<String> HOOKS = new HashSet<>(List.of("gt_init", "gt_register", "gt_alive_check", "gt_destruct"));
    private static final Set<String> INERT_DECORATORS = new HashSet<>(List.of("@pub", "@throws", "@recursive", "@pure"));

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return new PassResult(lines, false);
        }
        List<int[]> funcs = VarAnalysis.functions(lines);
        if (funcs.isEmpty()) {
            return new PassResult(lines, false);
        }
        Map<String, Integer> byName = new HashMap<>();   // function name -> index into funcs
        for (int k = 0; k < funcs.size(); k++) {
            List<BytecodeToken> st = lines.get(funcs.get(k)[0]);
            if (st.size() >= 2) byName.put(st.get(1).text, k);
        }
        if (!byName.containsKey("main")) {
            return new PassResult(lines, false);
        }
        for (List<BytecodeToken> l : lines) {
            if ("INVOKE".equals(VarAnalysis.mnemonic(l))) {
                return new PassResult(lines, false);
            }
        }
        boolean[] live = new boolean[funcs.size()];
        ArrayDeque<Integer> work = new ArrayDeque<>();
        // roots
        for (Map.Entry<String, Integer> en : byName.entrySet()) {
            int k = en.getValue();
            if (en.getKey().equals("main") || HOOKS.contains(en.getKey()) || hasActiveDecorator(lines, funcs.get(k))) {
                markLive(live, work, k);
            }
        }
        // references from outside every function
        int fi = 0;
        for (int i = 0; i < lines.size(); i++) {
            while (fi < funcs.size() && funcs.get(fi)[1] < i) fi++;
            boolean inside = fi < funcs.size() && funcs.get(fi)[0] <= i && i <= funcs.get(fi)[1];
            if (inside) continue;
            for (BytecodeToken t : lines.get(i)) {
                if (t.kind != BytecodeToken.Kind.CODE) continue;
                Integer k = byName.get(t.text);
                if (k != null) markLive(live, work, k);
            }
        }
        while (!work.isEmpty()) {
            int k = work.poll();
            int[] f = funcs.get(k);
            for (int i = f[0] + 1; i < f[1]; i++) {
                for (BytecodeToken t : lines.get(i)) {
                    if (t.kind != BytecodeToken.Kind.CODE) continue;
                    Integer c = byName.get(t.text);
                    if (c != null) markLive(live, work, c);
                }
            }
        }
        Set<Integer> remove = new HashSet<>();
        boolean any = false;
        for (int k = 0; k < funcs.size(); k++) {
            if (live[k]) continue;
            any = true;
            for (int i = funcs.get(k)[0]; i <= funcs.get(k)[1]; i++) remove.add(i);
        }
        if (!any) {
            return new PassResult(lines, false);
        }
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            if (!remove.contains(i)) out.add(lines.get(i));
        }
        return new PassResult(out, true);
    }

    private static void markLive(boolean[] live, ArrayDeque<Integer> work, int k) {
        if (!live[k]) {
            live[k] = true;
            work.add(k);
        }
    }

    /** A function is a root when one of the FUNC_DECORATE lines right after its FUNC_START is not one of the inert ones. */
    private static boolean hasActiveDecorator(List<List<BytecodeToken>> L, int[] f) {
        for (int i = f[0] + 1; i < f[1]; i++) {
            List<BytecodeToken> l = L.get(i);
            if (!"FUNC_DECORATE".equals(VarAnalysis.mnemonic(l))) break;
            if (l.size() >= 2 && !INERT_DECORATORS.contains(l.get(1).text)) return true;
        }
        return false;
    }
}
```

### FILE: src/main/java/caspien/optimizer/FoldConfig.java
```java
package caspien.optimizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Constant-folding switch, read from the top-level key of "compiler.config" (the same file the other stages read):
 *
 *   constant-folding: on | off        (default off; a missing file or key means off)
 *
 * A malformed value stops the compile (never silently ignored).
 */
public final class FoldConfig {

    public final boolean enabled;

    public FoldConfig(boolean enabled) {
        this.enabled = enabled;
    }

    public static FoldConfig disabled() {
        return new FoldConfig(false);
    }

    public static FoldConfig loadFromWorkingDirectory() {
        Path p = Paths.get("compiler.config");
        if (!Files.exists(p)) {
            return disabled();
        }
        try {
            return parse(Files.readAllLines(p, StandardCharsets.UTF_8), p.toString());
        } catch (IOException e) {
            throw new RuntimeException("could not read '" + p + "': " + e.getMessage(), e);
        }
    }

    public static FoldConfig parse(List<String> lines, String path) {
        boolean on = false;
        for (int n = 0; n < lines.size(); n++) {
            String raw = lines.get(n);
            int hash = raw.indexOf('#');
            if (hash >= 0) {
                raw = raw.substring(0, hash);
            }
            if (raw.isEmpty() || Character.isWhitespace(raw.charAt(0))) {
                continue; // top-level keys only
            }
            String t = raw.trim();
            int colon = t.indexOf(':');
            if (colon <= 0 || !t.substring(0, colon).trim().equals("constant-folding")) {
                continue;
            }
            String val = t.substring(colon + 1).trim();
            if (val.equals("on")) {
                on = true;
            } else if (val.equals("off")) {
                on = false;
            } else {
                throw new RuntimeException(path + ":" + (n + 1) + ": 'constant-folding' must be on or off, found '" + val + "'");
            }
        }
        return new FoldConfig(on);
    }

    @Override
    public String toString() {
        return enabled ? "constant-folding on" : "constant-folding off";
    }
}
```

### FILE: src/main/java/caspien/optimizer/FunctionInliningPass.java
```java
package caspien.optimizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Function inlining: a direct call "CC_START / args / CALL f / CC_END [PUSH_RET T]" is replaced by the body of f.
 *
 * How a call site is rewritten
 *   - Every argument (the lines before its "POP ARGn T") is assigned to a fresh local that stands for the matching parameter:
 *     "ADDR p__iK T / <argument lines> / ASSIGN T T T". Arguments are evaluated left to right, exactly as before.
 *   - The callee's own locals and labels are renamed per inlined copy (x -> x__iK, @lbl_12 -> @lbl_<fresh>; the numeric-suffix
 *     shape is kept so loop unrolling still recognises a copied `for` loop), and all their ALLOC lines move to the top of the caller.
 *   - A callee that is one straight-line body ending in a single `RET` just leaves its value on the stack (the PUSH_RET of the call
 *     site is dropped). Otherwise each `RET` stores into a result local (found by walking the return expression back with the
 *     operand-stack effect of each line) and jumps to the end of the copy, where the result is pushed. A void `RET` is a jump. A call
 *     whose result is discarded drops the (pure) return expression instead.
 *
 * Throw / catch programs: every function carries `gt_routine_address` (and `gt_error_message`) slots and stages a per-call-site label
 * into its own slot before each call it makes. The two slots are dead scaffolding when the body never mentions them, and are never
 * copied (the caller's own slots stand in: the inlined code writes and reads the caller's, which is exactly what the callee's
 * frame slots would have been copied into on unwind). A callee that stages its own calls, throws (THROW is a no-op marker and is
 * dropped) or unwinds is inlined too: each `GT_UNWIND [MSG]` becomes a `JMP` to the label the CALLER staged for this call (its
 * landing pad or `@catch_N`), the message having been written into the caller's slot already. The callee's own landing pads and
 * catch blocks are copied with renamed labels. The staging triple of the call site is dropped (nothing reads it any more).
 *
 * Never inlined (the callee): a function with any decorator other than @pub / @pure / @recursive / @inline / @throws (so never
 * @async, the ghost-table hooks, par/await/sleep glue), `main`, anything on a call cycle, a function using EXIT / inline assembly /
 * a function-local static, a callee that unwinds at a call site with no staged label (old-form pads in non-throw programs), an
 * unwinding callee whose staged label is a `@catch_` while caller or callee has a float variable (the backend's catch entry reloads
 * float variables from their home slots), a function with a by-value struct, array or dynarray
 * parameter, one that reaches outside itself with a label, and one whose local names collide with tokens the renamer cannot tell apart.
 * A bare `range` parameter is supported: the call site passes it as two words (start, end), which are assigned to the copy's range
 * variable like a `lo..hi` literal; variable names inside range type text (`imut_range(mut_lo,mut_hi)`) are renamed along with the
 * variables. A parameter that the callee declares again as a local of the same type (the loop-converted recursive form) is one variable.
 * A call through a function pointer (INVOKE) is not a `CALL` and an extern is not a function with a body: neither is ever touched.
 * A recursive function has already been turned into a loop by the front end, so it is inlined like any other function.
 *
 * Where a call sits matters: with anything else on the operand stack beneath it (an operand of an enclosing expression) only a
 * straight-line callee of plain scalar operations is inlined; `let x = f(..)` (one address beneath) with a branching callee uses the
 * result local and pushes the address after the copy.
 *
 * Configuration: see InlineConfig (presets off|conservative|balanced|aggressive with size / depth / growth limits).
 */
public class FunctionInliningPass implements OptimizationPass {

    private final InlineConfig cfg;
    private int roundsDone = 0;
    private final Map<String, Long> grown = new HashMap<>();
    private int fresh = -1;
    /** Number of call sites inlined over the whole run (for diagnostics). */
    public int inlinedSites = 0;

    public FunctionInliningPass() {
        this(InlineConfig.disabled());
    }

    public FunctionInliningPass(InlineConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public String name() {
        return "function-inlining";
    }

    // ------------------------------------------------------------------------------------------------------------------
    // small helpers

    private static String mn(List<BytecodeToken> l) {
        return l.isEmpty() || l.get(0).kind != BytecodeToken.Kind.CODE ? null : l.get(0).text;
    }

    private static String tok(List<BytecodeToken> l, int i) {
        return i < l.size() ? l.get(i).text : null;
    }

    private static boolean is(List<BytecodeToken> l, int size, String first) {
        return l.size() == size && first.equals(mn(l));
    }

    private static boolean isLabelDef(List<BytecodeToken> l) {
        return l.size() == 1 && l.get(0).kind == BytecodeToken.Kind.CODE && l.get(0).text.startsWith("@") && l.get(0).text.endsWith(":");
    }

    private static BytecodeToken retext(BytecodeToken t, String text) {
        return new BytecodeToken(text, t.file, t.line, t.kind);
    }

    private static List<BytecodeToken> mk(BytecodeToken ref, String... parts) {
        List<BytecodeToken> l = new ArrayList<>(parts.length);
        for (String p : parts) l.add(new BytecodeToken(p, ref.file, ref.line, BytecodeToken.Kind.CODE));
        return l;
    }

    private static boolean allDigits(String s) {
        if (s == null || s.isEmpty() || s.length() > 12) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return true;
    }

    private static final Set<String> OK_DECORATORS = new HashSet<>(Arrays.asList("@pub", "@pure", "@recursive", "@inline", "@throws", "@lock"));
    private static final Set<String> EXCLUDED = new HashSet<>(Arrays.asList("EXIT", "EXIT_THREAD",
            "ASM_START", "ASM_END", "RECURSIVE_CALL", "ALLOC_STATIC", "FUNC_START", "FUNC_END", "STRUCT_START", "GLOBAL", "REGVAR", "STRING",
            "EXTERN", "ENUM", "STRUCT_MEMBER", "STRUCT_END", "STRUCT_DECORATE", "STRUCT_PADDING"));
    private static final Set<String> BINARY = new HashSet<>(Arrays.asList("ADD", "SUB", "MUL", "DIV", "MOD", "AND", "OR", "EQ", "NEQ", "LT",
            "GT", "LT_EQ", "GT_EQ", "SHL", "SHR", "BITS_OR", "BITS_AND", "BITS_XOR", "IN", "WITHIN", "LOOKUP", "LOOKUP_LHS", "DOT", "DOT_LHS"));
    private static final Set<String> UNARY = new HashSet<>(Arrays.asList("NEG", "NOT", "BITS_NOT", "SEXT", "ZEXT", "TRUNC", "FCONV", "DEREF", "ADDR_OF",
            "NEG_FLOAT", "LEN", "PROMOTE_F32_TO_F64"));
    private static final Set<String> PUSH1 = new HashSet<>(Arrays.asList("PUSH", "ADDR", "PUSH_FIELDNAME", "ATOMIC_PUSH"));
    /** What a plain scalar body may contain when other operands are live beneath the call. */
    private static final Set<String> SIMPLE = new HashSet<>(Arrays.asList("ALLOC", "ADDR", "PUSH", "ASSIGN", "ADD", "SUB", "MUL", "DIV", "MOD",
            "AND", "OR", "EQ", "NEQ", "LT", "GT", "LT_EQ", "GT_EQ", "NEG", "NOT", "SEXT", "ZEXT", "TRUNC", "FCONV", "SHL", "SHR", "BITS_OR", "BITS_AND", "BITS_XOR", "BITS_NOT",
            "RET", "INC", "DEC"));
    private static final Set<String> BOUNDARY = new HashSet<>(Arrays.asList("ASSIGN", "ATOMIC_ASSIGN", "JMP", "CMP", "ALLOC", "ARG", "RETURNS",
            "FUNC_DECORATE", "FUNC_START", "CC_END", "CC_START", "GT_DESTRUCT", "RET", "POP"));
    private static final Set<String> SCALARS = new HashSet<>(Arrays.asList("u8", "u16", "u32", "u64", "s8", "s16", "s32", "s64", "f32", "f64",
            "bool", "char"));

    /** A parameter type the inliner can copy by a plain scalar ASSIGN: a scalar, or any pointer. */
    static boolean scalarType(String t) {
        if (t == null || t.indexOf('(') >= 0 || t.indexOf('[') >= 0) return false;
        for (String p : new String[] {"raw_", "owns_", "ref_", "auto_", "static_"}) {
            if (t.startsWith(p)) return true;
        }
        String b = t;
        for (String p : new String[] {"mut_", "imut_", "indeterminate_"}) {
            if (b.startsWith(p)) {
                b = b.substring(p.length());
                break;
            }
        }
        return SCALARS.contains(b);
    }

    /** A bare range parameter (`imut_range` / `mut_range`, no bounds in the type): passed as two argument words, start then end. */
    static boolean rangeParam(String t) {
        return "imut_range".equals(t) || "mut_range".equals(t);
    }

    private static int wordsOf(String paramType) {
        return rangeParam(paramType) ? 2 : 1;
    }

    private static final String[] MUT_PREFIXES = {"mut_", "imut_", "indeterminate_"};

    /**
     * A range type's text embeds the names of the variables that bound it (`imut_range(mut_lo,mut_hi)`); when the variables are
     * renamed for an inlined copy the names inside the text must follow, or elision/unrolling would read stale names.
     */
    static String renameRangeText(String x, Map<String, String> local) {
        if (x.indexOf("range(") < 0) return x;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (true) {
            int q = x.indexOf("range(", i);
            int close = q < 0 ? -1 : x.indexOf(')', q + 6);
            if (q < 0 || close < 0) {
                sb.append(x.substring(i));
                break;
            }
            sb.append(x, i, q + 6);
            String[] parts = x.substring(q + 6, close).split(",", -1);
            for (int k = 0; k < parts.length; k++) {
                if (k > 0) sb.append(',');
                String part = parts[k];
                String out = part;
                if (local.containsKey(part)) {
                    out = local.get(part);
                } else {
                    for (String pre : MUT_PREFIXES) {
                        if (part.startsWith(pre) && local.containsKey(part.substring(pre.length()))) {
                            out = pre + local.get(part.substring(pre.length()));
                            break;
                        }
                    }
                }
                sb.append(out);
            }
            sb.append(')');
            i = close + 1;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------------------------
    // callee analysis

    private static final class Ret {
        int idx;
        boolean isVoid;
        String type;
        int vStart = -1, vTail = -1;
        boolean pure;
        boolean tail;
    }

    private static final class Callee {
        String name;
        List<String[]> params = new ArrayList<>();
        List<String[]> allocs = new ArrayList<>();
        List<List<BytecodeToken>> code = new ArrayList<>();
        boolean eligible;
        int scaffoldSlots;
        /** the body contains THROW / GT_UNWIND: it can leave through the caller's call-site label. */
        boolean unwinds;
        boolean usesAddr, usesMsg, hasFloat;
        /** the body defines a `@catch_N` label (a `?catch` / `try ... catch` of its own). */
        boolean hasCatch;
        /** every statement leaves the operand stack as it found it (no ignored call result, no unknown mnemonic): safe to run with operands of an enclosing expression beneath. */
        boolean balanced;
        boolean controlFlow;
        boolean simple;
        List<Ret> rets = new ArrayList<>();
        Set<String> locals = new HashSet<>();
        Set<String> globals = new HashSet<>();
        Set<String> labels = new LinkedHashSet<>();
        int lines;
        String why = "";
    }

    private static final Set<String> NAME_POS = new HashSet<>(Arrays.asList("PUSH", "ADDR", "GT_DESTRUCT", "ATOMIC_PUSH"));

    private static boolean isFloatText(String t) {
        return t != null && (t.contains("f32") || t.contains("f64"));
    }

    /**
     * Simulates the operand-stack depth over the body in text order. True when it is 0 at every RET (after the returned value is
     * popped), every statement boundary the walk sees and the end, and every mnemonic has a known effect. A call whose result is ignored
     * leaves its PUSH_RET word on the stack: harmless in a frame of its own (RET resets rsp), but once the body runs inline in the middle of
     * an expression that word sits between the pending operands and the code that consumes them.
     */
    private static boolean stackBalanced(List<List<BytecodeToken>> code) {
        int depth = 0;
        for (List<BytecodeToken> l : code) {
            if (isLabelDef(l)) continue;
            String m = mn(l);
            if (m == null) return false;
            int pops, pushes;
            if (m.equals("JMP") || m.equals("GT_DESTRUCT") || m.equals("CC_START") || m.equals("CC_END") || m.equals("CALL")
                    || m.equals("EXTERN_CALL") || m.equals("VARARGS_XMM_COUNT")) {
                continue;
            } else if (m.equals("GT_UNWIND")) {
                depth = 0;
                continue;
            } else if (m.equals("PUSH_LABEL")) {
                // a landing-pad / catch address is staged as ADDR gt_routine_address / PUSH_LABEL / ASSIGN
                pops = 0;
                pushes = 1;
            } else if (m.equals("RET")) {
                pops = l.size() >= 2 && l.get(1).text.equals("imut_void") ? 0 : 1;
                if (depth - pops != 0) return false;
                depth = 0;
                continue;
            } else if (m.equals("ASSIGN") || m.equals("ATOMIC_ASSIGN")) {
                pops = 2;
                pushes = 0;
            } else if (m.equals("CMP") || m.equals("POP")) {
                pops = 1;
                pushes = 0;
            } else if (PUSH1.contains(m) || m.equals("PUSH_RET")) {
                pops = 0;
                pushes = 1;
            } else if (BINARY.contains(m)) {
                pops = 2;
                pushes = 1;
            } else if (UNARY.contains(m) || m.equals("INC") || m.equals("DEC")) {
                pops = 1;
                pushes = 1;
            } else {
                return false;
            }
            depth = depth - pops + pushes;
            if (depth < 0) return false;
        }
        return depth == 0;
    }

    private static boolean pureLine(List<BytecodeToken> l) {
        String m = mn(l);
        return m != null && (PUSH1.contains(m) || BINARY.contains(m) || UNARY.contains(m));
    }

    /** Walks back from line `tail` over an expression that leaves one value; returns its first line, or -1 if the shape is not understood. */
    private static int walkBack(List<List<BytecodeToken>> code, int tail) {
        int need = 1;
        int k = tail;
        while (k >= 0) {
            List<BytecodeToken> l = code.get(k);
            if (isLabelDef(l)) {
                // a label of a body that was inlined into this expression: no effect on the operand stack
                k--;
                continue;
            }
            String m = mn(l);
            if (m == null) return -1;
            int pops, pushes;
            if (m.equals("JMP") || m.equals("GT_DESTRUCT")) {
                k--;
                continue;
            } else if (m.equals("CC_END") && !(k + 1 < code.size() && is(code.get(k + 1), 2, "PUSH_RET"))) {
                // a call whose result is not used (a statement inlined into this expression): skip the whole bracket
                int depth = 0;
                int j = k;
                for (; j >= 0; j--) {
                    String jm = mn(code.get(j));
                    if ("CC_END".equals(jm)) depth++;
                    else if ("CC_START".equals(jm)) {
                        depth--;
                        if (depth == 0) break;
                    }
                }
                if (j < 0) return -1;
                k = j - 1;
                continue;
            } else if (m.equals("ASSIGN") || m.equals("ATOMIC_ASSIGN")) {
                pops = 2;
                pushes = 0;
            } else if (m.equals("CMP") || m.equals("POP")) {
                pops = 1;
                pushes = 0;
            } else if (PUSH1.contains(m)) {
                pops = 0;
                pushes = 1;
            } else if (BINARY.contains(m)) {
                pops = 2;
                pushes = 1;
            } else if (UNARY.contains(m) || m.equals("INC") || m.equals("DEC")) {
                pops = 1;
                pushes = 1;
            } else if (m.equals("PUSH_RET")) {
                pops = 0;
                pushes = 1;
                if (k == 0 || !is(code.get(k - 1), 2, "CC_END")) return -1;
            } else {
                return -1;
            }
            need = need - pushes + pops;
            if (need < 0) return -1;
            if (m.equals("PUSH_RET")) {
                int depth = 0;
                int j = k - 1;
                for (; j >= 0; j--) {
                    String jm = mn(code.get(j));
                    if ("CC_END".equals(jm)) depth++;
                    else if ("CC_START".equals(jm)) {
                        depth--;
                        if (depth == 0) break;
                    }
                }
                if (j < 0) return -1;
                k = j;
                if (need == 0) return k;
                k--;
                continue;
            }
            if (need == 0) return k;
            k--;
        }
        return -1;
    }

    private Callee analyse(List<List<BytecodeToken>> L, int s, int e, Set<String> cyclic) {
        Callee c = new Callee();
        c.name = L.get(s).get(1).text;
        String retType = null;
        boolean ok = true;
        for (int i = s + 1; i < e; i++) {
            List<BytecodeToken> l = L.get(i);
            String m = mn(l);
            if (m == null) {
                c.why = "non-code line";
                ok = false;
                continue;
            }
            if (m.equals("FUNC_DECORATE")) {
                if (l.size() != 2 || !OK_DECORATORS.contains(l.get(1).text)) {
                    c.why = "decorator " + tok(l, 1);
                    ok = false;
                }
                continue;
            }
            if (m.equals("RETURNS")) {
                retType = tok(l, 1);
                continue;
            }
            if (m.equals("ARG") && l.size() == 3) {
                c.params.add(new String[] {l.get(1).text, l.get(2).text});
                continue;
            }
            if (m.equals("ALLOC") && l.size() == 3) {
                if (l.get(1).text.equals("gt_routine_address") || l.get(1).text.equals("gt_error_message")) {
                    // ghost-table / throw scaffolding slot. It is dead weight unless something in the body uses it; a body that does
                    // (staging writes, THROW, unwinding) is refused by the mention check below. The slots are NOT copied to the caller
                    // (the caller has its own), so the inlined code carries no scaffolding.
                    c.scaffoldSlots++;
                    continue;
                }
                boolean dup = false;
                for (String[] pp : c.params) {
                    if (pp[0].equals(l.get(1).text)) {
                        // a parameter that is declared again as a local of the same type (the loop-converted recursive form does this):
                        // one variable, exactly as the frame layout already treats it
                        if (!pp[1].equals(l.get(2).text)) {
                            c.why = "parameter " + pp[0] + " redeclared with another type";
                            ok = false;
                        }
                        dup = true;
                    }
                }
                for (String[] aa : c.allocs) {
                    if (aa[0].equals(l.get(1).text)) {
                        if (!aa[1].equals(l.get(2).text)) {
                            c.why = "local " + aa[0] + " declared twice with different types";
                            ok = false;
                        }
                        dup = true;
                    }
                }
                if (!dup) c.allocs.add(new String[] {l.get(1).text, l.get(2).text});
                continue;
            }
            if (m.equals("THROW")) {
                // a pure marker (the message write and the unwind follow as ordinary lines); it carries nothing the copy needs
                c.unwinds = true;
                continue;
            }
            if (m.equals("GT_UNWIND")) {
                c.unwinds = true;
                if (!(l.size() == 1 || (l.size() == 2 && l.get(1).text.equals("MSG")))) {
                    c.why = "GT_UNWIND shape";
                    ok = false;
                }
            }
            if (m.equals("PUSH_LABEL")) {
                if (l.size() != 3 || !(l.get(1).text.startsWith("@gt_callsite__") || l.get(1).text.startsWith("@catch_"))) {
                    c.why = "PUSH_LABEL of something other than a landing pad / catch";
                    ok = false;
                }
            }
            if (EXCLUDED.contains(m)) {
                c.why = m;
                ok = false;
            }
            c.code.add(l);
        }
        for (List<BytecodeToken> l : c.code) {
            for (int t = 1; t < l.size(); t++) {
                BytecodeToken tk = l.get(t);
                if (tk.kind == BytecodeToken.Kind.CODE && tk.text.equals("gt_routine_address")) c.usesAddr = true;
                if (tk.kind == BytecodeToken.Kind.CODE && tk.text.equals("gt_error_message")) c.usesMsg = true;
            }
        }
        c.lines = e - s - 1;
        if (retType == null) {
            c.why = "no RETURNS";
            ok = false;
        }
        if (c.name.equals("main")) {
            c.why = "main";
            ok = false;
        }
        if (cyclic.contains(c.name)) {
            c.why = "call cycle";
            ok = false;
        }
        if (c.lines > cfg.maxCalleeLines) {
            c.why = "too long";
            ok = false;
        }
        for (String[] p : c.params) {
            if (!scalarType(p[1]) && !rangeParam(p[1])) {
                c.why = "param type " + p[1];
                ok = false;
            }
            c.locals.add(p[0]);
        }
        for (String[] a : c.allocs) c.locals.add(a[0]);
        for (String[] p : c.params) if (isFloatText(p[1])) c.hasFloat = true;
        for (String[] a : c.allocs) if (isFloatText(a[1])) c.hasFloat = true;
        if (c.locals.size() != c.params.size() + c.allocs.size()) {
            c.why = "duplicate parameter names";
            ok = false;
        }
        // labels, jumps, names
        for (List<BytecodeToken> l : c.code) {
            if (isLabelDef(l)) {
                String t = l.get(0).text;
                c.labels.add(t.substring(0, t.length() - 1));
                if (t.startsWith("@catch_")) c.hasCatch = true;
            }
        }
        boolean flow = false;
        boolean simple = true;
        for (List<BytecodeToken> l : c.code) {
            String m = mn(l);
            if (isLabelDef(l) || "JMP".equals(m) || "CMP".equals(m)) flow = true;
            if (!isLabelDef(l) && !SIMPLE.contains(m)) simple = false;
            if ("ASSIGN".equals(m) && !(l.size() == 4 && scalarType(l.get(1).text) && scalarType(l.get(2).text) && scalarType(l.get(3).text))) simple = false;
            if ("ADDR".equals(m) && l.size() == 3 && !scalarType(l.get(2).text)) simple = false;
            if ("PUSH".equals(m) && l.size() == 3 && !scalarType(l.get(2).text)) simple = false;
            for (int t = 1; t < l.size(); t++) {
                BytecodeToken tk = l.get(t);
                if (tk.kind != BytecodeToken.Kind.CODE) {
                    continue;
                }
                String x = tk.text;
                if (x.startsWith("@")) {
                    String nm = x.endsWith(":") ? x.substring(0, x.length() - 1) : x;
                    if (!c.labels.contains(nm)) {
                        c.why = "label " + nm + " not defined in callee";
                        ok = false;
                    }
                    continue;
                }
                boolean namePos = t == 1 && NAME_POS.contains(m) && (l.size() == 3 || "GT_DESTRUCT".equals(m));
                String root = x.indexOf('.') > 0 ? x.substring(0, x.indexOf('.')) : x;
                if (namePos) {
                    if (!c.locals.contains(root) && !root.equals("gt_routine_address") && !root.equals("gt_error_message") && !x.isEmpty() && !Character.isDigit(x.charAt(0)) && !x.startsWith("'") && !x.startsWith("\"")
                            && !x.equals("true") && !x.equals("false") && !x.equals("null") && !x.startsWith("-")) {
                        c.globals.add(root);
                    }
                } else if (t == 1 && ("PUSH_FIELDNAME".equals(m) || "CALL".equals(m) || "EXTERN_CALL".equals(m))) {
                    // a field name, not a variable
                } else if (t >= 2 && NAME_POS.contains(m)) {
                    // type operand
                } else if (c.locals.contains(x) || c.locals.contains(root)) {
                    c.why = "local name " + x + " used in an unrecognised position (" + m + ")";
                    ok = false;
                }
            }
        }
        c.controlFlow = flow;
        c.balanced = stackBalanced(c.code);
        c.simple = simple;
        // returns
        for (int i = 0; i < c.code.size(); i++) {
            List<BytecodeToken> l = c.code.get(i);
            if (!"RET".equals(mn(l))) continue;
            if (l.size() != 2) {
                c.why = "RET shape";
                ok = false;
                continue;
            }
            Ret r = new Ret();
            r.idx = i;
            r.type = l.get(1).text;
            r.isVoid = r.type.equals("imut_void");
            r.tail = i == c.code.size() - 1;
            if (!r.isVoid) {
                int t = i - 1;
                while (t >= 0 && "GT_DESTRUCT".equals(mn(c.code.get(t)))) t--;
                if (t >= 0) {
                    r.vTail = t;
                    r.vStart = walkBack(c.code, t);
                    if (r.vStart >= 0) {
                        r.pure = true;
                        for (int q = r.vStart; q <= r.vTail; q++) {
                            if (!pureLine(c.code.get(q))) r.pure = false;
                        }
                    }
                }
            }
            c.rets.add(r);
        }
        if (c.rets.size() != 1 || !c.rets.get(0).tail) c.controlFlow = true;
        c.eligible = ok;
        if (!ok && System.getenv("CASPIEN_INLINE_WHY") != null) System.err.println("[inline] " + c.name + " refused: " + c.why);
        return c;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // the pass

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        if (!cfg.enabled || roundsDone >= cfg.maxDepth) {
            return new PassResult(lines, false);
        }
        List<List<BytecodeToken>> work = lines;
        boolean any = false;
        while (roundsDone < cfg.maxDepth) {
            List<List<BytecodeToken>> next = round(work);
            if (next == null) break;
            work = next;
            any = true;
            roundsDone++;
        }
        return any ? new PassResult(work, true) : new PassResult(lines, false);
    }

    private final class Ctx {
        String caller;
        Set<String> callerNames = new HashSet<>();
        Map<String, Callee> callees;
        List<List<BytecodeToken>> newAllocs = new ArrayList<>();
        long added = 0;
        boolean changed = false;
        boolean callerHasFloat = false;
    }

    /** One round: every call site whose callee (as it stands now) is eligible is inlined. Returns null if nothing changed. */
    private List<List<BytecodeToken>> round(List<List<BytecodeToken>> L) {
        int maxNum = 0;
        for (List<BytecodeToken> l : L) {
            for (BytecodeToken t : l) {
                if (t.kind != BytecodeToken.Kind.CODE) continue;
                String x = t.text;
                if (!(x.startsWith("@") || x.startsWith("$for_range_"))) continue;
                if (x.endsWith(":")) x = x.substring(0, x.length() - 1);
                int us = x.lastIndexOf('_');
                if (us >= 0 && allDigits(x.substring(us + 1))) {
                    maxNum = Math.max(maxNum, (int) Math.min(Long.parseLong(x.substring(us + 1)), 1_000_000_000L));
                }
            }
        }
        fresh = Math.max(fresh, maxNum + 1);

        List<int[]> fns = VarAnalysis.functions(L);
        // call graph -> functions on a cycle
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (int[] f : fns) {
            Set<String> out = new HashSet<>();
            for (int i = f[0] + 1; i < f[1]; i++) {
                List<BytecodeToken> l = L.get(i);
                if (is(l, 2, "CALL")) out.add(l.get(1).text);
            }
            graph.put(L.get(f[0]).get(1).text, out);
        }
        Set<String> cyclic = new HashSet<>();
        for (String n : graph.keySet()) {
            Set<String> seen = new HashSet<>();
            List<String> stack = new ArrayList<>(graph.get(n));
            while (!stack.isEmpty()) {
                String x = stack.remove(stack.size() - 1);
                if (!seen.add(x)) continue;
                if (x.equals(n)) {
                    cyclic.add(n);
                    break;
                }
                Set<String> nx = graph.get(x);
                if (nx != null) stack.addAll(nx);
            }
        }
        Map<String, Callee> callees = new HashMap<>();
        for (int[] f : fns) {
            Callee c = analyse(L, f[0], f[1], cyclic);
            callees.put(c.name, c);
        }

        List<List<BytecodeToken>> out = new ArrayList<>(L.size());
        boolean changed = false;
        int pos = 0;
        for (int[] f : fns) {
            while (pos < f[0]) out.add(L.get(pos++));
            Ctx cx = new Ctx();
            cx.caller = L.get(f[0]).get(1).text;
            cx.callees = callees;
            for (int i = f[0] + 1; i < f[1]; i++) {
                List<BytecodeToken> l = L.get(i);
                String m = mn(l);
                if (("ALLOC".equals(m) || "ARG".equals(m)) && l.size() >= 3) {
                    cx.callerNames.add(l.get(1).text);
                    if (isFloatText(l.get(2).text)) cx.callerHasFloat = true;
                }
            }
            List<List<BytecodeToken>> body = expandRange(L, f[0] + 1, f[1], cx, true, false);
            if (cx.changed) {
                changed = true;
                List<List<BytecodeToken>> fn = new ArrayList<>();
                fn.add(L.get(f[0]));
                int p = 0;
                while (p < body.size()) {
                    String m = mn(body.get(p));
                    if ("FUNC_DECORATE".equals(m) || "RETURNS".equals(m) || "ARG".equals(m) || "ALLOC".equals(m) || "ALLOC_STATIC".equals(m)) {
                        fn.add(body.get(p));
                        p++;
                    } else {
                        break;
                    }
                }
                fn.addAll(cx.newAllocs);
                while (p < body.size()) fn.add(body.get(p++));
                fn.add(L.get(f[1]));
                out.addAll(fn);
                grown.merge(cx.caller, cx.added, Long::sum);
            } else {
                for (int i = f[0]; i <= f[1]; i++) out.add(L.get(i));
            }
            pos = f[1] + 1;
        }
        while (pos < L.size()) out.add(L.get(pos++));
        return changed ? out : null;
    }

    /** Index of the CC_END matching the CC_START at k, or -1. */
    private static int matchEnd(List<List<BytecodeToken>> L, int k, int to) {
        int depth = 0;
        for (int i = k; i < to; i++) {
            String m = mn(L.get(i));
            if ("CC_START".equals(m)) depth++;
            else if ("CC_END".equals(m)) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** What is on the operand stack beneath a call site whose CC_START would be appended to `out`: 0 = nothing, 1 = one address, 2 = other. */
    private int classify(List<List<BytecodeToken>> out, int cut, boolean baseClean) {
        int n = out.size() - cut;
        if (!baseClean) return 2;
        if (n == 0) return 0;
        if (boundary(out.get(n - 1))) return 0;
        if (is(out.get(n - 1), 3, "ADDR") && n >= 2 && boundary(out.get(n - 2))) return 1;
        return 2;
    }

    /** True if a "POP ARGn" / "POP FARGn" of the bracket being built (depth 0 of `out`) has already put a value in an argument register. */
    private static boolean argRegisterLoaded(List<List<BytecodeToken>> out) {
        int depth = 0;
        for (List<BytecodeToken> l : out) {
            String m = mn(l);
            if ("CC_START".equals(m)) depth++;
            else if ("CC_END".equals(m)) depth--;
            else if (depth == 0 && "POP".equals(m) && l.size() == 3 && (l.get(1).text.startsWith("ARG") || l.get(1).text.startsWith("FARG"))) return true;
        }
        return false;
    }

    /**
     * regsLoaded: this region sits inside the argument list of an enclosing call that has already loaded an argument register. Inlined code
     * runs with no register protection (a real nested call saves and restores the loaded argument registers around itself; the backend's own
     * block copies, shifts and divides use rdi, rsi, rdx, rcx as scratch), so a call site in that position is never inlined.
     */
    private List<List<BytecodeToken>> expandRange(List<List<BytecodeToken>> L, int from, int to, Ctx cx, boolean baseClean, boolean regsLoaded) {
        List<List<BytecodeToken>> out = new ArrayList<>();
        int k = from;
        while (k < to) {
            List<BytecodeToken> line = L.get(k);
            if (!is(line, 2, "CC_START")) {
                out.add(line);
                k++;
                continue;
            }
            int e = matchEnd(L, k, to);
            if (e < 0) {
                out.add(line);
                k++;
                continue;
            }
            List<BytecodeToken> callLine = L.get(e - 1);
            boolean isCall = e - 1 > k && is(callLine, 2, "CALL");
            int argsEnd = isCall ? e - 1 : e; // exclusive end of the argument region
            int cut = stagedLabel(out) != null ? 3 : 0;
            int cls = classify(out, cut, baseClean);
            boolean loaded = regsLoaded || argRegisterLoaded(out);
            List<List<BytecodeToken>> args = expandRange(L, k + 1, argsEnd, cx, cls == 0, loaded);
            boolean hasRet = e + 1 < to && is(L.get(e + 1), 2, "PUSH_RET");
            boolean done = false;
            if (isCall) {
                Callee c = cx.callees.get(callLine.get(1).text);
                if (c != null && c.eligible && !c.name.equals(cx.caller) && !loaded) {
                    done = tryInline(c, line, args, hasRet ? L.get(e + 1) : null, out, cut, cls, cx);
                }
            }
            if (done) {
                k = e + 1 + (hasRet ? 1 : 0);
            } else {
                out.add(line);
                out.addAll(args);
                if (isCall) out.add(callLine);
                out.add(L.get(e));
                k = e + 1;
            }
        }
        return out;
    }

    /** True if `l` is empty or made only of null-out triples "ADDR name T / PUSH null T / ASSIGN T T T" (a moved owns variable being cleared). */
    private static boolean moveNullOuts(List<List<BytecodeToken>> l) {
        if (l.size() % 3 != 0) return false;
        for (int i = 0; i < l.size(); i += 3) {
            List<BytecodeToken> a = l.get(i), b = l.get(i + 1), c = l.get(i + 2);
            if (!(is(a, 3, "ADDR") && is(b, 3, "PUSH") && b.get(1).text.equals("null") && is(c, 4, "ASSIGN"))) return false;
        }
        return true;
    }

    /** The label staged for this call site by "ADDR gt_routine_address / PUSH_LABEL X / ASSIGN" (a landing pad or a catch), or null. */
    private String stagedLabel(List<List<BytecodeToken>> out) {
        int n = out.size();
        if (n < 3) return null;
        List<BytecodeToken> a = out.get(n - 3), b = out.get(n - 2), c = out.get(n - 1);
        boolean ok = is(a, 3, "ADDR") && a.get(1).text.equals("gt_routine_address") && is(b, 3, "PUSH_LABEL")
                && (b.get(1).text.startsWith("@gt_callsite__") || b.get(1).text.startsWith("@catch_")) && is(c, 4, "ASSIGN");
        return ok ? b.get(1).text : null;
    }

    private static boolean boundary(List<BytecodeToken> l) {
        if (l.isEmpty()) return false;
        if (isLabelDef(l)) return true;
        return BOUNDARY.contains(mn(l));
    }

    private boolean tryInline(Callee c, List<BytecodeToken> ccStart, List<List<BytecodeToken>> args, List<BytecodeToken> pushRet,
            List<List<BytecodeToken>> out, int cut, int cls, Ctx cx) {
        // ---- split the argument region into per-parameter segments
        List<List<List<BytecodeToken>>> segs = new ArrayList<>();
        List<List<BytecodeToken>> pops = new ArrayList<>();
        List<List<BytecodeToken>> cur = new ArrayList<>();
        int depth = 0;
        for (List<BytecodeToken> l : args) {
            String m = mn(l);
            if ("CC_START".equals(m)) depth++;
            if (depth == 0) {
                if ("VARARGS_XMM_COUNT".equals(m) || "DUP_TOP".equals(m) || "CALL".equals(m) || "EXTERN_CALL".equals(m) || "INVOKE".equals(m)) return false;
                if ("POP".equals(m)) {
                    if (l.size() != 3 || !(l.get(1).text.startsWith("ARG") || l.get(1).text.startsWith("FARG"))) return false;
                    segs.add(cur);
                    pops.add(l);
                    cur = new ArrayList<>();
                    continue;
                }
            }
            if ("CC_END".equals(m)) depth--;
            cur.add(l);
        }
        // What follows the last POP: the owns-move null-out of the variable just passed (`ADDR o / PUSH null / ASSIGN`, one triple per moved
        // owns argument). It runs after the argument is read and before the callee starts, so it goes right after the parameter copies.
        List<List<BytecodeToken>> post = cur;
        if (depth != 0 || !moveNullOuts(post)) return false;
        int words = 0;
        for (String[] pp : c.params) words += wordsOf(pp[1]);
        if (segs.size() != words) return false;
        for (int i = 0; i < segs.size(); i++) {
            if (!scalarType(pops.get(i).get(2).text)) return false;
        }
        // ---- site context
        boolean valueMode = pushRet != null;
        boolean clean = cls == 0;
        boolean addr1 = cls == 1;
        int n = out.size() - cut;
        List<BytecodeToken> addrLine = addr1 ? out.get(n - 1) : null;
        if (addr1 && !valueMode) return false;
        // ---- callee shape vs. site
        boolean voidCallee = c.rets.isEmpty() || c.rets.get(0).isVoid;
        for (Ret r : c.rets) {
            if (r.isVoid != voidCallee) return false;
        }
        if (valueMode && voidCallee && !c.rets.isEmpty()) return false;
        if (valueMode && c.rets.isEmpty()) return false; // a value call whose callee never returns a value
        String mode;
        if (!valueMode) {
            mode = "DISCARD";
            if (!voidCallee) {
                for (Ret r : c.rets) {
                    if (r.vStart < 0 || !r.pure) return false;
                }
            }
        } else {
            boolean stackOk = !c.controlFlow && c.rets.size() == 1 && c.rets.get(0).tail && c.rets.get(0).vStart >= 0;
            if (addr1 && (c.controlFlow || !c.balanced)) stackOk = false;
            if (stackOk) {
                mode = "STACK";
            } else {
                mode = "TEMP";
                for (Ret r : c.rets) {
                    if (r.vStart < 0) return false;
                }
            }
        }
        // A body that leaves words behind (an ignored call result) must not run with operands beneath it: they would sit between the
        // pending operands and the code that consumes them (a STACK-mode value must be directly above the address beneath it). Only
        // the result-variable form with one address beneath is safe, because the address is pushed again after the copy.
        if (!c.balanced && cls != 0 && !(cls == 1 && mode.equals("TEMP"))) return false;
        // ---- growth
        long est = (long) c.code.size() + c.allocs.size() + words + c.params.size() * 2L + 4;
        if (grown.getOrDefault(cx.caller, 0L) + cx.added + est > cfg.maxGrowth) return false;
        // A callee with a catch of its own is entered by a real unwind with whatever the statement had pushed at the call still on the
        // operand stack (the callee's frame is not there to reset rsp any more), and its catch then falls out through the end label. With
        // operands of an enclosing expression beneath the site, those extra words would sit between them and the code that consumes them.
        if (c.hasCatch && cls == 2) return false;
        // ---- unwinding: the callee leaves through the label the caller staged for this call
        String unwindTo = cut == 3 ? out.get(out.size() - 2).get(1).text : null;
        if (c.unwinds && unwindTo == null) return false;
        if (c.usesAddr && !cx.callerNames.contains("gt_routine_address")) return false;
        if (c.usesMsg && !cx.callerNames.contains("gt_error_message")) return false;
        // A jump into a catch label lands on the backend's float-variable reload (the values come back from their home slots, which
        // are only written around real calls), so a caller or callee with float variables is left alone there.
        if (c.unwinds && unwindTo.startsWith("@catch_") && (cx.callerHasFloat || c.hasFloat)) return false;
        // ---- global-name capture: the callee's globals must not be shadowed by a caller local
        for (String g : c.globals) {
            if (cx.callerNames.contains(g)) return false;
        }

        // ---- build
        int inst = fresh++;
        Map<String, String> local = new HashMap<>();
        for (String[] p : c.params) local.put(p[0], renameVar(p[0], inst));
        for (String[] a : c.allocs) local.put(a[0], renameVar(a[0], inst));
        Map<String, String> lab = new HashMap<>();
        for (String lb : c.labels) lab.put(lb, renameLabel(lb, inst));
        String endLabel = "@inl_end_" + (fresh++);
        BytecodeToken ref = ccStart.get(0);
        String retVar = "__ret_i" + inst;

        List<List<BytecodeToken>> blk = new ArrayList<>();
        int sg = 0;
        for (String[] pp : c.params) {
            String pn = local.get(pp[0]);
            String pt = pp[1];
            blk.add(mk(ref, "ADDR", pn, pt));
            if (rangeParam(pt)) {
                // the two argument words (start, end) are built into the range value exactly as a `lo..hi` literal is
                blk.addAll(segs.get(sg));
                blk.addAll(segs.get(sg + 1));
                blk.add(mk(ref, "ASSIGN", pt, pt, pt));
                sg += 2;
            } else {
                blk.addAll(segs.get(sg));
                blk.add(mk(ref, "ASSIGN", pt, pops.get(sg).get(2).text, pt));
                sg++;
            }
        }
        blk.addAll(post);
        String retT = null;
        for (Ret r : c.rets) {
            if (!r.isVoid) retT = r.type;
        }
        Set<Integer> skip = new HashSet<>();
        Map<Integer, List<List<BytecodeToken>>> before = new HashMap<>();
        Map<Integer, List<List<BytecodeToken>>> after = new HashMap<>();
        boolean usesEnd = false;
        for (Ret r : c.rets) {
            if (!r.isVoid && mode.equals("DISCARD")) {
                for (int q = r.vStart; q <= r.vTail; q++) skip.add(q);
            }
            if (!r.isVoid && mode.equals("TEMP")) {
                before.computeIfAbsent(r.vStart, z -> new ArrayList<>()).add(mk(ref, "ADDR", retVar, retT));
                after.computeIfAbsent(r.vTail, z -> new ArrayList<>()).add(mk(ref, "ASSIGN", retT, retT, retT));
            }
            if (mode.equals("STACK")) {
                skip.add(r.idx);
            } else if (r.tail) {
                skip.add(r.idx);
            } else {
                usesEnd = true;
            }
        }
        for (int i = 0; i < c.code.size(); i++) {
            List<List<BytecodeToken>> b = before.get(i);
            if (b != null) blk.addAll(b);
            if (!skip.contains(i)) {
                List<BytecodeToken> l = c.code.get(i);
                Ret rr = null;
                for (Ret r : c.rets) {
                    if (r.idx == i) rr = r;
                }
                if (rr != null) {
                    blk.add(mk(ref, "JMP", endLabel));
                } else if ("GT_UNWIND".equals(mn(l))) {
                    blk.add(mk(ref, "JMP", unwindTo));
                } else {
                    blk.add(rename(l, local, lab));
                }
            }
            List<List<BytecodeToken>> a = after.get(i);
            if (a != null) blk.addAll(a);
        }
        if (usesEnd) blk.add(mk(ref, endLabel + ":"));
        if (mode.equals("TEMP")) {
            if (addr1) blk.add(addrLine);
            blk.add(mk(ref, "PUSH", retVar, retT));
        }

        // ---- commit
        for (int i = 0; i < cut; i++) out.remove(out.size() - 1);
        if (mode.equals("TEMP") && addr1) out.remove(out.size() - 1);
        out.addAll(blk);
        int newLocals = c.params.size() + c.allocs.size() + (mode.equals("TEMP") ? 1 : 0);
        for (String[] p : c.params) cx.newAllocs.add(mk(ref, "ALLOC", local.get(p[0]), renameRangeText(p[1], local)));
        for (String[] a : c.allocs) cx.newAllocs.add(mk(ref, "ALLOC", local.get(a[0]), renameRangeText(a[1], local)));
        if (mode.equals("TEMP")) cx.newAllocs.add(mk(ref, "ALLOC", retVar, retT));
        cx.callerNames.addAll(local.values());
        cx.added += blk.size() + newLocals;
        cx.changed = true;
        inlinedSites++;
        return true;
    }

    private String renameVar(String name, int inst) {
        if (name.startsWith("$for_range_") && allDigits(name.substring("$for_range_".length()))) {
            return "$for_range_" + (fresh++);
        }
        return name + "__i" + inst;
    }

    private String renameLabel(String lb, int inst) {
        String x = lb.substring(1); // drop '@'
        int us = x.lastIndexOf('_');
        if (us >= 0 && allDigits(x.substring(us + 1))) {
            return "@" + x.substring(0, us + 1) + (fresh++);
        }
        return "@" + x + "_i" + inst + "_" + (fresh++);
    }

    private static List<BytecodeToken> rename(List<BytecodeToken> l, Map<String, String> local, Map<String, String> lab) {
        boolean touch = false;
        List<BytecodeToken> out = new ArrayList<>(l.size());
        String m = mn(l);
        for (int t = 0; t < l.size(); t++) {
            BytecodeToken tk = l.get(t);
            if (tk.kind != BytecodeToken.Kind.CODE || t == 0 && !isLabelDef(l)) {
                out.add(tk);
                continue;
            }
            String x = tk.text;
            if (x.startsWith("@")) {
                boolean colon = x.endsWith(":");
                String nm = colon ? x.substring(0, x.length() - 1) : x;
                String r = lab.get(nm);
                if (r != null) {
                    out.add(retext(tk, r + (colon ? ":" : "")));
                    touch = true;
                    continue;
                }
                out.add(tk);
                continue;
            }
            if (t == 1 && NAME_POS.contains(m)) {
                int dot = x.indexOf('.');
                String root = dot > 0 ? x.substring(0, dot) : x;
                String r = local.get(root);
                if (r != null) {
                    out.add(retext(tk, dot > 0 ? r + x.substring(dot) : r));
                    touch = true;
                    continue;
                }
            }
            if (x.indexOf("range(") >= 0) {
                String y = renameRangeText(x, local);
                if (!y.equals(x)) {
                    out.add(retext(tk, y));
                    touch = true;
                    continue;
                }
            }
            out.add(tk);
        }
        return touch ? out : l;
    }
}
```

### FILE: src/main/java/caspien/optimizer/InlineConfig.java
```java
package caspien.optimizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Function-inlining settings, read from the top-level keys of "compiler.config" (the same file, with the same mirrored copy, the other stages
 * read). No other key of that file is looked at, and the file is optional here: a missing file or a missing 'function-inlining' key means
 * inlining is off.
 *
 *   function-inlining: off | conservative | balanced | aggressive      (default off)
 *   inline-max-callee-lines: N     never inline a callee whose body is longer than N bytecode lines
 *   inline-max-depth: N            rounds of inlining: 1 inlines the calls written in each function, 2 also the calls that came in with
 *                                  the first round's bodies, and so on
 *   inline-max-growth: N           at most N added bytecode lines per function, over the whole run
 *
 * The preset supplies all three numbers; any of the three keys after it overrides just that number. The keys do nothing while the preset is
 * off. A malformed value stops the compile (never silently ignored).
 */
public final class InlineConfig {

    public boolean enabled = false;
    public String preset = "off";
    public int maxCalleeLines = 0;
    public int maxDepth = 0;
    public long maxGrowth = 0;

    public static InlineConfig disabled() {
        return new InlineConfig();
    }

    /** Loads "compiler.config" from the working directory if it exists; otherwise inlining is off. */
    public static InlineConfig loadFromWorkingDirectory() {
        Path p = Paths.get("compiler.config");
        if (!Files.exists(p)) {
            return disabled();
        }
        try {
            return parse(Files.readAllLines(p, StandardCharsets.UTF_8), p.toString());
        } catch (IOException e) {
            throw new RuntimeException("could not read '" + p + "': " + e.getMessage(), e);
        }
    }

    public static InlineConfig parse(List<String> lines, String path) {
        String preset = "off";
        Integer calleeLines = null, depth = null;
        Long growth = null;
        for (int n = 0; n < lines.size(); n++) {
            String raw = lines.get(n);
            int hash = raw.indexOf('#');
            if (hash >= 0) {
                raw = raw.substring(0, hash);
            }
            if (raw.isEmpty() || Character.isWhitespace(raw.charAt(0))) {
                continue; // top-level keys only (the calling-convention block is indented)
            }
            String t = raw.trim();
            int colon = t.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = t.substring(0, colon).trim();
            String val = t.substring(colon + 1).trim();
            if (val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")) {
                val = val.substring(1, val.length() - 1);
            }
            switch (key) {
                case "function-inlining":
                    if (!(val.equals("off") || val.equals("conservative") || val.equals("balanced") || val.equals("aggressive"))) {
                        throw bad(path, n, "'function-inlining' must be off, conservative, balanced or aggressive, found '" + val + "'");
                    }
                    preset = val;
                    break;
                case "inline-max-callee-lines":
                    calleeLines = (int) Math.min(number(path, n, key, val), Integer.MAX_VALUE);
                    break;
                case "inline-max-depth":
                    depth = (int) Math.min(number(path, n, key, val), Integer.MAX_VALUE);
                    break;
                case "inline-max-growth":
                    growth = number(path, n, key, val);
                    break;
                default:
                    break;
            }
        }
        InlineConfig c = new InlineConfig();
        c.preset = preset;
        switch (preset) {
            case "conservative":
                c.set(12, 1, 200);
                break;
            case "balanced":
                c.set(40, 3, 2000);
                break;
            case "aggressive":
                c.set(Integer.MAX_VALUE, 32, 100_000_000L);
                break;
            default:
                return c; // off: the numbers are ignored
        }
        c.enabled = true;
        if (calleeLines != null) c.maxCalleeLines = calleeLines;
        if (depth != null) c.maxDepth = depth;
        if (growth != null) c.maxGrowth = growth;
        return c;
    }

    private void set(int maxCalleeLines, int maxDepth, long maxGrowth) {
        this.maxCalleeLines = maxCalleeLines;
        this.maxDepth = maxDepth;
        this.maxGrowth = maxGrowth;
    }

    private static long number(String path, int lineIdx, String key, String val) {
        if (val.isEmpty() || val.length() > 12) {
            throw bad(path, lineIdx, "'" + key + "' needs a whole number, found '" + val + "'");
        }
        long v = 0;
        for (int i = 0; i < val.length(); i++) {
            char ch = val.charAt(i);
            if (ch < '0' || ch > '9') {
                throw bad(path, lineIdx, "'" + key + "' needs a whole number (0 or more), found '" + val + "'");
            }
            v = v * 10 + (ch - '0');
        }
        return v;
    }

    private static RuntimeException bad(String path, int lineIdx, String msg) {
        return new RuntimeException(path + ":" + (lineIdx + 1) + ": " + msg);
    }

    @Override
    public String toString() {
        return enabled ? "function-inlining " + preset + " (callee<=" + maxCalleeLines + " lines, depth " + maxDepth + ", growth<="
                + maxGrowth + ")" : "function-inlining off";
    }
}
```

### FILE: src/main/java/caspien/optimizer/LoopUnrollingPass.java
```java
package caspien.optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unrolls counted `for` loops whose trip count can be read straight off the bytecode, and nothing else.
 *
 * A loop qualifies only when the hidden range's type text carries two integer literals, e.g.
 * {@code ALLOC $for_range_3 imut_range(0,4)}. A bound that is a variable shows up as
 * {@code imut_range(2,mut_n)} and is never unrolled: this pass does no constant propagation, no induction
 * analysis and no folding (those are other passes' jobs; because the optimizer repeats until nothing changes,
 * a bound another pass later turns into a literal is picked up on a later round). {@code loop{}} has no
 * counted structure in the bytecode and is never touched.
 *
 * The recognised shape (emitted by the front end for every `for`):
 * <pre>
 *   ADDR $for_range_N T ; PUSH lo ; PUSH hi ; ASSIGN T T T
 *   ADDR i vt ; PUSH $for_range_N.start indeterminate_u64 ; ASSIGN vt vt vt
 *   @for_A: ; PUSH i vt ; PUSH $for_range_N T ; IN vt T indeterminate_bool ; CMP ; JMP @for_end_B
 *   ...body...
 *   ADDR i vt ; PUSH i vt ; INC vt indeterminate_u64 ; ASSIGN vt indeterminate_u64 indeterminate_u64 ; JMP @for_A
 *   @for_end_B:
 * </pre>
 * Anything that does not match exactly is left alone.
 *
 * Full unroll (trip count N within the configured limit): the test and back-jump go, the body is repeated N
 * times with the induction variable's own increment between copies (the variable stays a real variable; it is
 * not replaced by a constant).
 *
 * Partial unroll (factor U, N >= 2U): the hidden range's high bound becomes lo + floor(N/U)*U (in the literal
 * and everywhere the type text appears), the loop body holds U copies each followed by the increment, and the
 * N mod U leftover iterations follow the loop as plain copies. The loop test failing jumps to a new label in
 * front of the leftover copies; a `break` in the body still jumps to the original end label, which now sits
 * after them. The partially unrolled loop's header label is renamed so it is never matched again.
 *
 * Copies get fresh labels (numbering continues above the highest number in the program, keeping each label's
 * name prefix, so a copied inner `for` is still recognisable to a later round). A loop is skipped when its body
 * contains an allocation or declaration line, mentions its own range or header label, or defines a label that
 * is referenced from outside the body (a try block, for instance). Innermost candidates go first; an outer loop
 * is considered on a later round with its now larger body.
 */
public class LoopUnrollingPass implements OptimizationPass {

    private final UnrollConfig cfg;
    private final Map<String, Long> grown = new HashMap<>();
    private int nextLabel = 0;

    public LoopUnrollingPass() {
        this(UnrollConfig.disabled());
    }

    public LoopUnrollingPass(UnrollConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public String name() {
        return "loop-unrolling";
    }

    // ---- small helpers over the token lines --------------------------------------------------------------

    private static String tok(List<BytecodeToken> line, int i) {
        return i < line.size() ? line.get(i).text : null;
    }

    private static boolean is(List<BytecodeToken> line, int size, String first) {
        return line.size() == size && line.get(0).kind == BytecodeToken.Kind.CODE && line.get(0).text.equals(first);
    }

    private static boolean isLabelDef(List<BytecodeToken> line) {
        return line.size() == 1 && line.get(0).kind == BytecodeToken.Kind.CODE
                && line.get(0).text.startsWith("@") && line.get(0).text.endsWith(":");
    }

    private static boolean allDigits(String s) {
        if (s == null || s.isEmpty() || s.length() > 12) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** "@for_12:" -> "for_12"; "@for_12" -> "for_12". */
    private static String labelName(String t) {
        String s = t.substring(1);
        return s.endsWith(":") ? s.substring(0, s.length() - 1) : s;
    }

    private static boolean labelWithPrefix(List<BytecodeToken> line, String prefix) {
        return isLabelDef(line) && allDigits(labelName(line.get(0).text).startsWith(prefix)
                ? labelName(line.get(0).text).substring(prefix.length()) : null);
    }

    private static BytecodeToken retext(BytecodeToken t, String text) {
        return new BytecodeToken(text, t.file, t.line, t.kind);
    }

    // ---- candidate loop ----------------------------------------------------------------------------------

    private static final class Loop {
        int start;            // index of "ADDR $for_range_N T"
        int bodyStart;        // first body line
        int incStart;         // first line of the 4-line increment (= body end, exclusive)
        int jmpIdx;           // "JMP @for_A"
        int endIdx;           // "@for_end_B:"
        String rangeName, rangeType, var, varType, headLabel, endLabel;
        long lo, hi;
        int allocIdx = -1;    // "ALLOC $for_range_N T" near the top of the function
    }

    private static Loop match(List<List<BytecodeToken>> L, int s, int fnEnd) {
        if (s + 13 >= fnEnd) return null;
        List<BytecodeToken> a = L.get(s);
        if (!is(a, 3, "ADDR") || !a.get(1).text.startsWith("$for_range_")) return null;
        String rangeName = a.get(1).text, T = a.get(2).text;
        int open = T.indexOf("range(");
        if (open < 0 || !T.endsWith(")")) return null;
        String inner = T.substring(open + 6, T.length() - 1);
        int comma = inner.indexOf(',');
        if (comma < 0) return null;
        String loS = inner.substring(0, comma), hiS = inner.substring(comma + 1);
        if (!allDigits(loS) || !allDigits(hiS)) return null;
        long lo = Long.parseLong(loS), hi = Long.parseLong(hiS);
        if (hi <= lo) return null;
        List<BytecodeToken> p1 = L.get(s + 1), p2 = L.get(s + 2), as = L.get(s + 3);
        if (!is(p1, 3, "PUSH") || !p1.get(1).text.equals(loS)) return null;
        if (!is(p2, 3, "PUSH") || !p2.get(1).text.equals(hiS)) return null;
        if (!is(as, 4, "ASSIGN") || !as.get(1).text.equals(T) || !as.get(2).text.equals(T) || !as.get(3).text.equals(T)) return null;
        List<BytecodeToken> av = L.get(s + 4);
        if (!is(av, 3, "ADDR") || av.get(1).text.startsWith("$")) return null;
        String var = av.get(1).text, vt = av.get(2).text;
        List<BytecodeToken> st = L.get(s + 5);
        if (!is(st, 3, "PUSH") || !st.get(1).text.equals(rangeName + ".start")) return null;
        List<BytecodeToken> as2 = L.get(s + 6);
        if (!is(as2, 4, "ASSIGN") || !as2.get(1).text.equals(vt) || !as2.get(2).text.equals(vt) || !as2.get(3).text.equals(vt)) return null;
        if (!labelWithPrefix(L.get(s + 7), "for_")) return null;
        String head = L.get(s + 7).get(0).text;
        head = head.substring(0, head.length() - 1);
        List<BytecodeToken> t1 = L.get(s + 8), t2 = L.get(s + 9), t3 = L.get(s + 10), t4 = L.get(s + 11), t5 = L.get(s + 12);
        if (!is(t1, 3, "PUSH") || !t1.get(1).text.equals(var) || !t1.get(2).text.equals(vt)) return null;
        if (!is(t2, 3, "PUSH") || !t2.get(1).text.equals(rangeName) || !t2.get(2).text.equals(T)) return null;
        if (!is(t3, 4, "IN") || !t3.get(1).text.equals(vt) || !t3.get(2).text.equals(T)) return null;
        if (!is(t4, 1, "CMP")) return null;
        if (!is(t5, 2, "JMP") || !t5.get(1).text.startsWith("@for_end_")) return null;
        String endLabel = t5.get(1).text;
        int endIdx = -1;
        for (int k = s + 13; k < fnEnd; k++) {
            List<BytecodeToken> l = L.get(k);
            if (isLabelDef(l) && l.get(0).text.equals(endLabel + ":")) {
                endIdx = k;
                break;
            }
        }
        if (endIdx < 0 || endIdx - 5 < s + 13) return null;
        int jmp = endIdx - 1;
        if (!is(L.get(jmp), 2, "JMP") || !L.get(jmp).get(1).text.equals(head)) return null;
        int inc = jmp - 4;
        List<BytecodeToken> i1 = L.get(inc), i2 = L.get(inc + 1), i3 = L.get(inc + 2), i4 = L.get(inc + 3);
        if (!is(i1, 3, "ADDR") || !i1.get(1).text.equals(var) || !i1.get(2).text.equals(vt)) return null;
        if (!is(i2, 3, "PUSH") || !i2.get(1).text.equals(var) || !i2.get(2).text.equals(vt)) return null;
        if (!is(i3, 3, "INC") || !i3.get(1).text.equals(vt)) return null;
        if (!is(i4, 4, "ASSIGN")) return null;
        Loop lp = new Loop();
        lp.start = s;
        lp.bodyStart = s + 13;
        lp.incStart = inc;
        lp.jmpIdx = jmp;
        lp.endIdx = endIdx;
        lp.rangeName = rangeName;
        lp.rangeType = T;
        lp.var = var;
        lp.varType = vt;
        lp.headLabel = head;
        lp.endLabel = endLabel;
        lp.lo = lo;
        lp.hi = hi;
        return lp;
    }

    /** Is this candidate safe to copy? Also fills in allocIdx. */
    private static boolean copyable(List<List<BytecodeToken>> L, Loop lp, int fnStart, int fnEnd) {
        Set<String> defined = new HashSet<>();
        for (int k = lp.bodyStart; k < lp.incStart; k++) {
            List<BytecodeToken> l = L.get(k);
            if (l.isEmpty()) continue;
            String f = l.get(0).text;
            if (l.get(0).kind == BytecodeToken.Kind.CODE && (f.equals("ALLOC") || f.equals("ALLOC_STATIC") || f.equals("FUNC_START")
                    || f.equals("FUNC_END") || f.equals("STRUCT_START") || f.equals("GLOBAL") || f.equals("REGVAR"))) {
                return false;
            }
            for (BytecodeToken t : l) {
                if (t.kind != BytecodeToken.Kind.CODE) continue;
                if (t.text.equals(lp.headLabel) || t.text.equals(lp.headLabel + ":") || t.text.equals(lp.rangeName) || t.text.startsWith(lp.rangeName + ".")) return false;
            }
            if (isLabelDef(l)) {
                defined.add(labelName(l.get(0).text));
            }
        }
        if (!defined.isEmpty()) {
            for (int k = fnStart; k < fnEnd; k++) {
                if (k >= lp.bodyStart && k < lp.incStart) continue;
                for (BytecodeToken t : L.get(k)) {
                    if (t.kind == BytecodeToken.Kind.CODE && t.text.startsWith("@") && defined.contains(labelName(t.text))) {
                        return false; // a label of the body is used from outside it (try block, etc.)
                    }
                }
            }
        }
        for (int k = fnStart; k < lp.start; k++) {
            List<BytecodeToken> l = L.get(k);
            if (is(l, 3, "ALLOC") && l.get(1).text.equals(lp.rangeName)) {
                lp.allocIdx = k;
                break;
            }
        }
        return true;
    }

    // ---- the pass ----------------------------------------------------------------------------------------

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        if (!cfg.enabled) {
            return new PassResult(lines, false);
        }
        int maxNum = 0;
        for (List<BytecodeToken> l : lines) {
            for (BytecodeToken t : l) {
                if (t.kind == BytecodeToken.Kind.CODE && t.text.startsWith("@")) {
                    String n = labelName(t.text);
                    int us = n.lastIndexOf('_');
                    if (us >= 0 && allDigits(n.substring(us + 1))) {
                        maxNum = Math.max(maxNum, (int) Math.min(Long.parseLong(n.substring(us + 1)), 1_000_000_000L));
                    }
                }
            }
        }
        nextLabel = Math.max(nextLabel, maxNum + 1);

        List<List<BytecodeToken>> work = new ArrayList<>(lines);
        boolean changed = false;
        int i = 0;
        while (i < work.size()) {
            List<BytecodeToken> l = work.get(i);
            if (!is(l, 2, "FUNC_START")) {
                i++;
                continue;
            }
            int fnStart = i;
            int fnEnd = fnStart + 1;
            while (fnEnd < work.size() && !(work.get(fnEnd).size() >= 1 && work.get(fnEnd).get(0).text.equals("FUNC_END"))) {
                fnEnd++;
            }
            String fname = l.get(1).text;
            List<Loop> cands = new ArrayList<>();
            for (int k = fnStart + 1; k < fnEnd; k++) {
                Loop lp = match(work, k, fnEnd);
                if (lp != null && copyable(work, lp, fnStart, fnEnd)) {
                    cands.add(lp);
                }
            }
            // innermost candidates only (no other candidate inside), applied from the back so earlier indices stay valid
            List<Loop> inner = new ArrayList<>();
            for (Loop a : cands) {
                boolean hasInner = false;
                for (Loop b : cands) {
                    if (b != a && b.start > a.start && b.endIdx < a.endIdx) {
                        hasInner = true;
                        break;
                    }
                }
                if (!hasInner) inner.add(a);
            }
            int delta = 0;
            for (int c = inner.size() - 1; c >= 0; c--) {
                Loop lp = inner.get(c);
                int added = apply(work, lp, fname);
                if (added != Integer.MIN_VALUE) {
                    changed = true;
                    delta += added;
                }
            }
            i = fnEnd + delta + 1;
            if (i <= fnStart) i = fnStart + 1;
        }
        return changed ? new PassResult(work, true) : new PassResult(lines, false);
    }

    /** Applies the chosen unroll to work in place; returns the change in line count, or MIN_VALUE if nothing was done. */
    private int apply(List<List<BytecodeToken>> work, Loop lp, String fname) {
        long n = lp.hi - lp.lo;
        int bodyLines = lp.incStart - lp.bodyStart;
        if (bodyLines > cfg.maxBodyLines) {
            return Integer.MIN_VALUE;
        }
        long unit = bodyLines + 4L;
        long budget = cfg.maxGrowth - grown.getOrDefault(fname, 0L);
        boolean full = cfg.fullMaxTrips > 0 && n <= cfg.fullMaxTrips && (n - 1) * unit <= budget;
        int u = cfg.factor;
        boolean partial = false;
        long rem = 0;
        if (!full && u >= 2 && n >= 2L * u) {
            rem = n % u;
            long extra = (u - 1 + rem) * unit + 3;
            partial = extra <= budget;
        }
        if (!full && !partial) {
            return Integer.MIN_VALUE;
        }
        int oldSize = lp.endIdx - lp.start + 1;
        List<List<BytecodeToken>> out = new ArrayList<>();
        BytecodeToken ref = work.get(lp.start).get(0);
        List<List<BytecodeToken>> body = new ArrayList<>(work.subList(lp.bodyStart, lp.incStart));
        List<List<BytecodeToken>> inc = new ArrayList<>(work.subList(lp.incStart, lp.jmpIdx));

        if (full) {
            for (int k = lp.start; k <= lp.start + 6; k++) out.add(work.get(k));
            for (long c = 0; c < n; c++) {
                out.addAll(c == 0 ? body : freshCopy(body));
                if (c < n - 1) out.addAll(inc);
            }
            out.add(work.get(lp.endIdx));
        } else {
            long trips = n / u;
            long newHi = lp.lo + trips * u;
            String newType = lp.rangeType.substring(0, lp.rangeType.indexOf('(') + 1) + lp.lo + "," + newHi + ")";
            for (int k = lp.start; k <= lp.start + 6; k++) {
                List<BytecodeToken> src = work.get(k);
                List<BytecodeToken> row = new ArrayList<>();
                for (BytecodeToken t : src) {
                    if (t.text.equals(lp.rangeType)) row.add(retext(t, newType));
                    else if (k == lp.start + 2 && t.text.equals(Long.toString(lp.hi)) && row.size() == 1) row.add(retext(t, Long.toString(newHi)));
                    else row.add(t);
                }
                out.add(row);
            }
            String fuLabel = "@fu_" + lp.headLabel.substring("@for_".length());
            String remLabel = "@for_rem_" + (nextLabel++);
            out.add(label(ref, fuLabel + ":"));
            for (int k = lp.start + 8; k <= lp.start + 12; k++) {
                List<BytecodeToken> src = work.get(k);
                List<BytecodeToken> row = new ArrayList<>();
                for (BytecodeToken t : src) {
                    if (t.text.equals(lp.rangeType)) row.add(retext(t, newType));
                    else if (k == lp.start + 12 && t.text.equals(lp.endLabel)) row.add(retext(t, remLabel));
                    else row.add(t);
                }
                out.add(row);
            }
            for (int c = 0; c < u; c++) {
                out.addAll(c == 0 ? body : freshCopy(body));
                out.addAll(inc);
            }
            List<BytecodeToken> back = new ArrayList<>();
            back.add(retext(ref, "JMP"));
            back.add(retext(ref, fuLabel));
            out.add(back);
            out.add(label(ref, remLabel + ":"));
            for (long c = 0; c < rem; c++) {
                out.addAll(freshCopy(body));
                if (c < rem - 1) out.addAll(inc);
            }
            out.add(work.get(lp.endIdx));
            if (lp.allocIdx >= 0) {
                List<BytecodeToken> al = new ArrayList<>();
                for (BytecodeToken t : work.get(lp.allocIdx)) {
                    al.add(t.text.equals(lp.rangeType) ? retext(t, newType) : t);
                }
                work.set(lp.allocIdx, al);
            }
        }
        // replace [start, endIdx] by out
        for (int k = lp.endIdx; k >= lp.start; k--) work.remove(k);
        work.addAll(lp.start, out);
        int added = out.size() - oldSize;
        grown.merge(fname, (long) Math.max(added, 0), Long::sum);
        return added;
    }

    private static List<BytecodeToken> label(BytecodeToken ref, String text) {
        List<BytecodeToken> l = new ArrayList<>();
        l.add(retext(ref, text));
        return l;
    }

    /** A copy of the body in which every label it defines (and every use of one) gets a fresh name. */
    private List<List<BytecodeToken>> freshCopy(List<List<BytecodeToken>> body) {
        Map<String, String> map = new HashMap<>();
        for (List<BytecodeToken> l : body) {
            if (isLabelDef(l)) {
                String old = labelName(l.get(0).text);
                int us = old.lastIndexOf('_');
                String fresh = (us >= 0 && allDigits(old.substring(us + 1)))
                        ? old.substring(0, us + 1) + (nextLabel++)
                        : old + "_u" + (nextLabel++);
                map.put(old, fresh);
            }
        }
        List<List<BytecodeToken>> out = new ArrayList<>(body.size());
        for (List<BytecodeToken> l : body) {
            if (map.isEmpty()) {
                out.add(l);
                continue;
            }
            List<BytecodeToken> row = new ArrayList<>(l.size());
            for (BytecodeToken t : l) {
                if (t.kind == BytecodeToken.Kind.CODE && t.text.startsWith("@")) {
                    boolean colon = t.text.endsWith(":");
                    String repl = map.get(labelName(t.text));
                    row.add(repl == null ? t : retext(t, "@" + repl + (colon ? ":" : "")));
                } else {
                    row.add(t);
                }
            }
            out.add(row);
        }
        return out;
    }
}
```

### FILE: src/main/java/caspien/optimizer/Main.java
```java
package caspien.optimizer;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * CLI entry point for the (now standalone) "shallow methods" optimizer
 * stage -- split out of the original combined caspien-optimizer project
 * on request ("separate the optimizer into two programs -- the shallow
 * methods architecture at the start of the optimizer will be extracted
 * and be the optimizer, and the actual work thats currently been done
 * in the optimizer will just be called the LowerOrderGenerator").
 *
 * This project now contains only the fixed-point, still-bytecode-to-
 * bytecode shallow rewrite passes that ran at the very top of the old
 * combined pipeline (struct unpacking, constant folding, variable
 * elision, variable shifting, dead control flow removal, dead function
 * removal, loop unrolling, function inlining), plus the two
 * placeholder reordering passes (struct member reordering, variable
 * allocation reordering) -- see BytecodeOptimizer's own class doc for
 * why those two ended up here rather than in LowerOrderGenerator.
 *
 * None of the real lowering work (membership lowering, clone
 * generation, drop-glue generation, ARG-to-ALLOC lowering, address
 * lowering) lives in this project any more -- that's
 * caspien-lowerordergenerator's job now, run as a separate program
 * against this program's own plain-text output, the same
 * plain-text-interchange convention caspien-compiler -> (this project)
 * already used.
 *
 * Usage: optimizer -i input.txt output.txt
 */
public class Main {

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IOException ioe) {
            System.err.println("[io error] " + ioe.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException {
        if (args.length < 3 || !args[0].equals("-i")) {
            System.err.println("Usage: optimizer -i <input.txt> <output>");
            System.exit(1);
            return;
        }
        String inputPath = args[1];
        String outputPath = args[2];

        List<String> rawLines = Files.readAllLines(Paths.get(inputPath), StandardCharsets.UTF_8);

        BytecodeParser parser = new BytecodeParser();
        List<List<BytecodeToken>> parsed = parser.parse(rawLines, inputPath);

        UnrollConfig unrollConfig = UnrollConfig.loadFromWorkingDirectory();
        FoldConfig foldConfig = FoldConfig.loadFromWorkingDirectory();
        VariableConfig variableConfig = VariableConfig.loadFromWorkingDirectory();
        InlineConfig inlineConfig = InlineConfig.loadFromWorkingDirectory();
        System.err.println("[info] optimizer: " + unrollConfig + "; " + foldConfig + "; " + variableConfig + "; " + inlineConfig);
        BytecodeOptimizer optimizer = new BytecodeOptimizer(unrollConfig, foldConfig, variableConfig, inlineConfig);
        List<List<BytecodeToken>> result = optimizer.optimize(parsed);

        BytecodeSerializer serializer = new BytecodeSerializer();
        String bytecode = serializer.serialize(result);

        System.out.print(bytecode);

        writeFile(outputPath, bytecode);
        writeFile("output.txt", bytecode);

        System.err.println("\n[info] wrote optimized bytecode to " + outputPath + " and output.txt ("
                + bytecode.lines().count() + " instructions/lines)");
    }

    private static void writeFile(String path, String content) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Paths.get(path), StandardCharsets.UTF_8))) {
            pw.print(content);
        }
    }
}
```

### FILE: src/main/java/caspien/optimizer/OptimizationPass.java
```java
package caspien.optimizer;

import java.util.List;

/**
 * One optimization pass over the whole, already-emitted bytecode
 * program. Every pass is a pure function: given the current program, it
 * returns either the same lines back (nothing to do) or a rewritten
 * program, and says which. Passes are deliberately unaware of each
 * other and of BytecodeOptimizer's own pipeline shape -- ordering,
 * fixed-point looping, etc. all live in BytecodeOptimizer, not here.
 */
public interface OptimizationPass {

    /** A short, stable name for this pass, used only for logging/diagnostics. */
    String name();

    PassResult run(List<List<BytecodeToken>> lines);
}
```

### FILE: src/main/java/caspien/optimizer/PassResult.java
```java
package caspien.optimizer;

import java.util.List;

/**
 * The result of running one OptimizationPass once: the (possibly
 * rewritten) program, plus whether this pass actually changed anything.
 * `changed` is what drives every fixed-point loop in BytecodeOptimizer --
 * a pass that made no changes must report false so the loop it's part of
 * can terminate.
 */
public class PassResult {

    public final List<List<BytecodeToken>> lines;
    public final boolean changed;

    public PassResult(List<List<BytecodeToken>> lines, boolean changed) {
        this.lines = lines;
        this.changed = changed;
    }
}
```

### FILE: src/main/java/caspien/optimizer/RegVarHintPass.java
```java
package caspien.optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Marks local variables that are worth keeping in a register, as a HINT for the lower stages:
 *
 *     REGVAR name weight [f]
 *
 * (the optional trailing "f" says the variable is a float, so a lowering that has a separate float register file can use it)
 *
 * placed right after the function's leading ALLOC run. The ALLOC stays (a variable that gets no register simply stays a
 * stack slot, and a lower stage that ignores the hint produces exactly the code it did before). The hint names NO register:
 * which registers exist, which are free and how many is the lowering's business.
 *
 * A local is eligible when
 *   - it is declared by a plain "ALLOC name type" with a plain scalar type (mut/imut u8..u64, s8..s64, f32, bool, char;
 *     no storage keyword, no atomic, no struct/array/range), every declaration of the name in the function having the same
 *     type (the `i` of several `for` loops is one slot), and no '$' hidden name;
 *   - its name appears ONLY as the operand of ALLOC, PUSH and ADDR lines (any other mention, e.g. GT_DESTRUCT, disqualifies);
 *   - no PUSH/ADDR of it is directly followed by ADDR_OF (that is raw/auto/ref taking its address).
 * The weight is the number of PUSH/ADDR mentions, each counted 8^depth where depth is the number of loops (a backward JMP to an
 * already-seen label) containing it, capped at depth 4. Only variables with a weight of at least 8 (used inside a loop) are
 * hinted. A function that already carries REGVAR lines is left alone, so the pass is idempotent inside the fixed-point loop.
 * This pass proves nothing about the lowered code; the LowerOrderGenerator re-checks every mention before it promotes anything.
 */
public class RegVarHintPass implements OptimizationPass {

    private static final Pattern PLAIN_SCALAR =
            Pattern.compile("^(mut|imut)_(u8|u16|u32|u64|s8|s16|s32|s64|f32|f64|bool|char)$");
    private static final long MIN_WEIGHT = 8;

    @Override
    public String name() {
        return "regvar-hint";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        List<List<BytecodeToken>> out = new ArrayList<>();
        boolean changed = false;
        int i = 0;
        while (i < lines.size()) {
            if (!isMnemonic(lines.get(i), "FUNC_START")) {
                out.add(lines.get(i));
                i++;
                continue;
            }
            int end = i;
            while (end < lines.size() && !isMnemonic(lines.get(end), "FUNC_END")) {
                end++;
            }
            if (end >= lines.size()) {
                end = lines.size() - 1;
            }
            List<List<BytecodeToken>> fn = lines.subList(i, end + 1);
            List<List<BytecodeToken>> hinted = hintFunction(fn);
            if (hinted != null) {
                changed = true;
                out.addAll(hinted);
            } else {
                out.addAll(fn);
            }
            i = end + 1;
        }
        return new PassResult(changed ? out : lines, changed);
    }

    private static boolean isMnemonic(List<BytecodeToken> line, String m) {
        return !line.isEmpty() && line.get(0).kind == BytecodeToken.Kind.CODE && line.get(0).text.equals(m);
    }

    private static String mnemonic(List<BytecodeToken> line) {
        return line.isEmpty() ? "" : line.get(0).text;
    }

    /** Returns the function's lines with REGVAR lines inserted, or null when nothing changes. */
    private List<List<BytecodeToken>> hintFunction(List<List<BytecodeToken>> fn) {
        Map<String, Integer> allocCount = new HashMap<>();
        Map<String, String> allocType = new HashMap<>();
        Set<String> mixedType = new HashSet<>(); // a name declared more than once with different types
        int lastLeadingAlloc = -1;
        boolean leading = true;
        for (int k = 0; k < fn.size(); k++) {
            List<BytecodeToken> l = fn.get(k);
            String m = mnemonic(l);
            if (m.equals("REGVAR")) {
                return null; // already hinted
            }
            if (m.equals("ALLOC") && l.size() == 3) {
                allocCount.merge(l.get(1).text, 1, Integer::sum);
                String prevType = allocType.put(l.get(1).text, l.get(2).text);
                if (prevType != null && !prevType.equals(l.get(2).text)) {
                    mixedType.add(l.get(1).text);
                }
                if (leading) {
                    lastLeadingAlloc = k;
                }
            } else if (!(m.equals("FUNC_START") || m.equals("RETURNS") || m.equals("ARG") || m.equals("ALLOC_STATIC"))) {
                leading = false;
            }
        }
        if (lastLeadingAlloc < 0) {
            return null;
        }
        Set<String> candidates = new HashSet<>();
        for (Map.Entry<String, Integer> e : allocCount.entrySet()) {
            String n = e.getKey();
            // A name declared several times with ONE type (the counter `i` of several `for` loops of a function) is one slot
            // for the lower stages (they resolve a name to a single frame offset), so it is one candidate.
            if (!mixedType.contains(n) && !n.startsWith("$") && !n.equals("gt_routine_address") && !n.equals("gt_error_message")
                    && PLAIN_SCALAR.matcher(allocType.get(n)).matches()) {
                candidates.add(n);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        // loop depth per line: a backward JMP to an already-seen label closes a loop over [label, jmp]
        int n = fn.size();
        int[] delta = new int[n + 1];
        Map<String, Integer> labelAt = new HashMap<>();
        for (int k = 0; k < n; k++) {
            List<BytecodeToken> l = fn.get(k);
            if (l.size() == 1 && l.get(0).text.startsWith("@") && l.get(0).text.endsWith(":")) {
                String t = l.get(0).text;
                labelAt.put(t.substring(0, t.length() - 1), k);
            } else if (isMnemonic(l, "JMP") && l.size() >= 2) {
                Integer at = labelAt.get(l.get(1).text);
                if (at != null) {
                    delta[at]++;
                    delta[k + 1]--;
                }
            }
        }
        int[] depth = new int[n];
        int run = 0;
        for (int k = 0; k < n; k++) {
            run += delta[k];
            depth[k] = run;
        }
        Map<String, Long> weight = new HashMap<>();
        for (int k = 0; k < n; k++) {
            List<BytecodeToken> l = fn.get(k);
            String m = mnemonic(l);
            for (int t = 1; t < l.size(); t++) {
                String tok = l.get(t).text;
                if (!candidates.contains(tok) || l.get(t).kind != BytecodeToken.Kind.CODE) {
                    continue;
                }
                boolean simple = (m.equals("PUSH") || m.equals("ADDR")) && t == 1;
                boolean decl = m.equals("ALLOC") && t == 1;
                if (decl) {
                    continue;
                }
                boolean addressTaken = simple && k + 1 < n && mnemonic(fn.get(k + 1)).equals("ADDR_OF");
                if (!simple || addressTaken) {
                    candidates.remove(tok);
                    weight.remove(tok);
                } else {
                    long w = 1L << (3 * Math.min(depth[k], 4));
                    weight.merge(tok, w, Long::sum);
                }
            }
        }
        List<String> chosen = new ArrayList<>();
        for (String c : candidates) {
            Long w = weight.get(c);
            if (w != null && w >= MIN_WEIGHT) {
                chosen.add(c);
            }
        }
        if (chosen.isEmpty()) {
            return null;
        }
        java.util.Collections.sort(chosen);
        BytecodeToken ref = fn.get(lastLeadingAlloc).get(0);
        List<List<BytecodeToken>> result = new ArrayList<>(fn.subList(0, lastLeadingAlloc + 1));
        for (String c : chosen) {
            List<BytecodeToken> h = new ArrayList<>();
            h.add(new BytecodeToken("REGVAR", ref.file, ref.line, BytecodeToken.Kind.CODE));
            h.add(new BytecodeToken(c, ref.file, ref.line, BytecodeToken.Kind.CODE));
            h.add(new BytecodeToken(Long.toString(Math.min(weight.get(c), 1_000_000_000L)), ref.file, ref.line, BytecodeToken.Kind.CODE));
            if ((allocType.get(c).endsWith("_f32") || allocType.get(c).endsWith("_f64"))) {
                // a class marker only: "this one is a float". Which register file (if any) holds it is the lowering's business.
                h.add(new BytecodeToken("f", ref.file, ref.line, BytecodeToken.Kind.CODE));
            }
            result.add(h);
        }
        result.addAll(fn.subList(lastLeadingAlloc + 1, fn.size()));
        return result;
    }
}
```

### FILE: src/main/java/caspien/optimizer/SizeofResolutionPass.java
```java
package caspien.optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the front end's symbolic {@code SIZEOF TypeName <type>} into {@code PUSH n <type>}.
 *
 * The front end folds {@code sizeof} for primitives, pointers and ranges (their size never
 * changes) but leaves a struct, or an array of structs, symbolic, and does the same for the scale
 * of {@code raw Struct} pointer arithmetic. A struct's real size (the hidden {@code ___type}
 * word, member padding, trailing padding) is only settled here, in the STRUCT declarations the
 * program actually carries. Resolving it late lets struct member reordering run first and
 * constant folding (which needs the PUSH) run afterwards, so the size {@code unsafe} code
 * passes to malloc or memcopy, and the stride of a pointer step, always match the layout the
 * Lower Order Generator will use.
 *
 * Size of a struct = the sum of its STRUCT_MEMBER sizes and STRUCT_PADDING bytes, read from the
 * declaration as it stands now. Member sizes follow the same rules as the Lower Order
 * Generator's SizeCalculator: any storage-keyword type is an 8-byte pointer, u8/s8/bool/char 1,
 * u16/s16 2, u32/s32/f32 4, u64/s64/f64/code_addr/string 8, range 16, dynarray 8, a fixed array
 * is element size times its length, a known struct is its own size (a mangled generic name is
 * looked up whole), anything else (an enum) 8.
 *
 * Always on: the SIZEOF mnemonic must never reach the Lower Order Generator. A SIZEOF naming a
 * struct with no declaration is an internal error.
 */
public class SizeofResolutionPass implements OptimizationPass {

    @Override
    public String name() {
        return "sizeof-resolution";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        boolean any = false;
        for (List<BytecodeToken> l : lines) {
            if (isMnemonic(l, "SIZEOF")) {
                any = true;
                break;
            }
        }
        if (!any) {
            return new PassResult(lines, false);
        }
        Sizes sizes = new Sizes(lines);
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        for (List<BytecodeToken> l : lines) {
            if (isMnemonic(l, "SIZEOF") && l.size() >= 3) {
                String name = l.get(1).text;
                long n = sizes.structSize(name, 0);
                if (n < 0) {
                    BytecodeToken at = l.get(0);
                    throw new IllegalStateException("internal error: SIZEOF names struct '" + name
                            + "' which has no STRUCT declaration (" + at.file + ":" + at.line + ")");
                }
                List<BytecodeToken> r = new ArrayList<>(l.size());
                BytecodeToken m = l.get(0);
                r.add(new BytecodeToken("PUSH", m.file, m.line, BytecodeToken.Kind.CODE));
                r.add(new BytecodeToken(Long.toString(n), m.file, m.line, BytecodeToken.Kind.CODE));
                for (int i = 2; i < l.size(); i++) {
                    r.add(l.get(i));
                }
                out.add(r);
            } else {
                out.add(l);
            }
        }
        return new PassResult(out, true);
    }

    private static boolean isMnemonic(List<BytecodeToken> line, String m) {
        return !line.isEmpty() && line.get(0).kind == BytecodeToken.Kind.CODE && line.get(0).text.equals(m);
    }

    /** Struct sizes from the STRUCT_START ... STRUCT_END declarations. */
    static final class Sizes {
        /** struct name -> its members' canonical types, with padding as a negative marker (-n). */
        private final Map<String, List<Object>> decls = new HashMap<>();
        private final Map<String, Long> cache = new HashMap<>();

        Sizes(List<List<BytecodeToken>> lines) {
            String cur = null;
            for (List<BytecodeToken> l : lines) {
                if (isMnemonic(l, "STRUCT_START") && l.size() >= 2) {
                    cur = l.get(1).text;
                    decls.put(cur, new ArrayList<>());
                } else if (isMnemonic(l, "STRUCT_MEMBER") && l.size() >= 3 && cur != null) {
                    decls.get(cur).add(l.get(2).text);
                } else if (isMnemonic(l, "STRUCT_PADDING") && l.size() >= 2 && cur != null) {
                    decls.get(cur).add(Long.valueOf(l.get(1).text));
                } else if (isMnemonic(l, "STRUCT_END")) {
                    cur = null;
                }
            }
        }

        /** Size of the named struct, or -1 if it has no declaration. */
        long structSize(String name, int depth) {
            Long c = cache.get(name);
            if (c != null) {
                return c;
            }
            List<Object> members = decls.get(name);
            if (members == null || depth > 64) {
                return -1;
            }
            long sum = 0;
            for (Object o : members) {
                if (o instanceof Long) {
                    sum += (Long) o;
                } else {
                    sum += sizeOfType((String) o, depth + 1);
                }
            }
            cache.put(name, sum);
            return sum;
        }

        long sizeOfType(String canonical, int depth) {
            String rest = canonical;
            for (String st : new String[]{"owns_", "ref_", "raw_", "auto_", "static_"}) {
                if (rest.startsWith(st)) {
                    return 8;
                }
            }
            if (rest.startsWith("some_")) {
                rest = rest.substring(5);
            }
            for (String mu : new String[]{"mut_", "imut_", "indeterminate_"}) {
                if (rest.startsWith(mu)) {
                    rest = rest.substring(mu.length());
                    break;
                }
            }
            if (rest.startsWith("atomic_")) {
                rest = rest.substring(7);
            }
            return sizeOfBase(rest, depth);
        }

        private long sizeOfBase(String base, int depth) {
            switch (base) {
                case "u8": case "s8": case "bool": case "char": case "void":
                    return 1;
                case "u16": case "s16":
                    return 2;
                case "u32": case "s32": case "f32":
                    return 4;
                case "u64": case "s64": case "f64": case "code_addr": case "string":
                    return 8;
                case "range":
                    return 16;
                default:
                    break;
            }
            if (base.startsWith("range(")) {
                return 16;
            }
            if (base.startsWith("dynarray(") || base.startsWith("unsafe_dynarray(")) {
                return 8;
            }
            if (base.endsWith("]")) {
                int open = base.lastIndexOf('[');
                if (open > 0) {
                    long n;
                    try {
                        n = Long.parseLong(base.substring(open + 1, base.length() - 1));
                    } catch (NumberFormatException e) {
                        return 8;
                    }
                    long el = sizeOfType(base.substring(0, open), depth);
                    return el * n;
                }
            }
            if (decls.containsKey(base)) {
                long s = structSize(base, depth);
                return s < 0 ? 8 : s;
            }
            return 8;
        }
    }
}
```

### FILE: src/main/java/caspien/optimizer/StructMemberReorderingPass.java
```java
package caspien.optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reorders a struct's own members, largest alignment first, so the padding the front end put between
 * them (and after the last one) disappears and the struct gets smaller
 * (switch: {@code struct-member-reordering: on|off}, default off).
 *
 * Runs first in the Optimizer's outer loop, ahead of SizeofResolutionPass and StructUnpackingPass, so the layout is settled before
 * anything reads it. Everything that reads a member does it by NAME (member reads/writes, offsets worked out later by the Lower Order
 * Generator from the declaration), so only two things are positional and must be rewritten together with the declaration: a
 * construction site (the hidden class id, then one value per member in declaration order, with {@code STACK_LOCK n} for each padding gap)
 * and static sizes folded by the front end (see the pin below).
 *
 * A struct is reordered only when ALL of these hold (otherwise it is left exactly as it was):
 *   - it has at least two members, all plain scalars (u8..u64, s8..s64, bool, char, f32, f64; no pointer, no atomic, no nested struct,
 *     no array), and no decorator other than {@code @pub} (so not {@code @lock});
 *   - it takes no part in {@code extends}: its class-id range covers no child and no other struct's range covers it (a child's
 *     layout must start with its parent's);
 *   - no other struct has it as a member type (by value, or as an array of it), and no static/global declaration uses an array of it
 *     or an {@code ALLOC_STATIC} of it;
 *   - no {@code raw} pointer to it appears anywhere, except the hidden destination pointer of a struct-returning call
 *     (an {@code ARG/PUSH $ret_dest}, a {@code RET} of it, and the address handed to a callee that declares one): unsafe code can
 *     reach a struct through a raw pointer by byte offset, and C interop sees the memory layout;
 *   - the front end did not fold its size into a static ({@code STRUCT_PIN Name}, emitted for {@code let static n = sizeof(Name)});
 *   - EVERY construction site of it was understood: a {@code PUSH <classId> imut_u64} followed by exactly the member units and
 *     padding locks its declaration predicts (a unit is pure: PUSH, arithmetic, compare and cast lines only, with the member's type),
 *     ended by an ASSIGN, a NEW or the next class-id push (array literal). A site that does not parse, or parses two ways, blocks the
 *     struct (a literal that happens to equal a class id can do this too; that only costs the optimisation);
 *   - the new layout is strictly smaller.
 *
 * Effect: the STRUCT_MEMBER / STRUCT_PADDING lines of the declaration are replaced by the new order (largest member first, equal sizes
 * keep their relative order, padding only at the end); each construction site is rewritten the same way (units reordered, padding locks
 * re-derived). Member expressions are evaluated in the new order; they are pure, so this only matters for the order of loads.
 * Static initialisers ({@code GLOBAL g.member ...}) name their members and need no change.
 *
 * Always, even with the switch off, the pass strips every {@code STRUCT_PIN} line (it must never reach the Lower Order Generator).
 */
public class StructMemberReorderingPass implements OptimizationPass {

    private final boolean enabled;

    public StructMemberReorderingPass() {
        this(false);
    }

    public StructMemberReorderingPass(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "struct-member-reordering";
    }

    // ---------------------------------------------------------------- model

    private static final class Entry {
        final boolean pad;
        final long padBytes;
        final String name;
        final String type;
        final List<BytecodeToken> line;   // the original declaration line

        Entry(boolean pad, long padBytes, String name, String type, List<BytecodeToken> line) {
            this.pad = pad;
            this.padBytes = padBytes;
            this.name = name;
            this.type = type;
            this.line = line;
        }
    }

    private static final class Decl {
        String name;
        int start;                       // index of STRUCT_START
        int end;                         // index of STRUCT_END
        List<List<BytecodeToken>> head = new ArrayList<>();   // STRUCT_START, STRUCT_DECORATE..., the ___type member
        List<Entry> entries = new ArrayList<>();              // everything after ___type, in order
        boolean hasType;
        boolean typeFirst;
        boolean plain = true;            // scalar members only, no odd decorators
        List<Entry> members = new ArrayList<>();
        long classId = -1;
        boolean blocked;
        List<Entry> newEntries;          // the reordered layout (members and padding)
    }

    private static final class Site {
        int start;
        int end;                         // exclusive
        Decl decl;
        Map<String, List<List<BytecodeToken>>> units = new HashMap<>();   // member name -> its lines
    }

    // ------------------------------------------------------------- helpers

    private final Set<String> pinnedEver = new HashSet<>();

    private static boolean isMn(List<BytecodeToken> l, String m) {
        return !l.isEmpty() && l.get(0).kind == BytecodeToken.Kind.CODE && l.get(0).text.equals(m);
    }

    private static BytecodeToken tok(BytecodeToken near, String text) {
        return new BytecodeToken(text, near.file, near.line, BytecodeToken.Kind.CODE);
    }

    private static String stripMut(String t) {
        for (String m : new String[] {"mut_", "imut_", "indeterminate_"}) {
            if (t.startsWith(m)) {
                return t.substring(m.length());
            }
        }
        return t;
    }

    /** Byte size of a plain scalar base type, or -1 when the type is anything else. */
    private static int scalarSize(String type) {
        String b = stripMut(type);
        switch (b) {
            case "u8": case "s8": case "bool": case "char":
                return 1;
            case "u16": case "s16":
                return 2;
            case "u32": case "s32": case "f32":
                return 4;
            case "u64": case "s64": case "f64":
                return 8;
            default:
                return -1;
        }
    }

    private static final Set<String> BINARY = new HashSet<>(java.util.List.of(
            "ADD", "SUB", "MUL", "DIV", "MOD", "SHL", "SHR", "SAR", "BITS_OR", "BITS_AND", "BITS_XOR", "AND", "OR",
            "EQ", "NEQ", "LT", "LT_EQ", "GT", "GT_EQ"));
    private static final Set<String> UNARY = new HashSet<>(java.util.List.of(
            "NEG", "NOT", "BITS_NOT", "TRUNC", "SEXT", "ZEXT", "FCONV", "NEG_FLOAT"));

    /** Stack effect of one member-expression line, or Integer.MIN_VALUE when the line is not allowed in one. */
    private static int effect(List<BytecodeToken> l) {
        if (l.isEmpty() || l.get(0).kind != BytecodeToken.Kind.CODE) {
            return Integer.MIN_VALUE;
        }
        String m = l.get(0).text;
        if (m.equals("PUSH") && l.size() == 3) {
            return 1;
        }
        if (BINARY.contains(m) && l.size() == 4) {
            return -1;
        }
        if (UNARY.contains(m)) {
            return 0;
        }
        return Integer.MIN_VALUE;
    }

    private static String resultType(List<BytecodeToken> l) {
        return l.get(l.size() - 1).text;
    }

    // ------------------------------------------------------------------ run

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        // STRUCT_PIN lines: read, then always removed.
        Set<String> pinned = pinnedEver; // the pass instance lives across outer-loop rounds; the lines are stripped in round one, the pins must outlive them
        boolean stripped = false;
        List<List<BytecodeToken>> work = lines;
        for (List<BytecodeToken> l : lines) {
            if (isMn(l, "STRUCT_PIN")) {
                stripped = true;
                break;
            }
        }
        if (stripped) {
            work = new ArrayList<>(lines.size());
            for (List<BytecodeToken> l : lines) {
                if (isMn(l, "STRUCT_PIN")) {
                    if (l.size() >= 2) {
                        pinned.add(l.get(1).text);
                    }
                } else {
                    work.add(l);
                }
            }
        }
        if (!enabled) {
            return new PassResult(work, stripped);
        }
        PassResult r = reorder(work, pinned);
        return new PassResult(r.lines, r.changed || stripped);
    }

    private PassResult reorder(List<List<BytecodeToken>> L, Set<String> pinned) {
        Map<String, Decl> decls = readDecls(L);
        if (decls.isEmpty()) {
            return new PassResult(L, false);
        }
        readClassIds(L, decls);
        // structural eligibility
        for (Decl d : decls.values()) {
            if (!d.plain || !d.hasType || !d.typeFirst || d.members.size() < 2 || d.classId < 0 || pinned.contains(d.name)) {
                d.blocked = true;
            }
        }
        blockByEnumRanges(L, decls);
        blockByUses(L, decls);
        // the new layout, and only when it is strictly smaller
        for (Decl d : decls.values()) {
            if (d.blocked) {
                continue;
            }
            long oldSize = 8;
            for (Entry e : d.entries) {
                oldSize += e.pad ? e.padBytes : scalarSize(e.type);
            }
            List<Entry> sorted = new ArrayList<>(d.members);
            sorted.sort((a, b) -> Integer.compare(scalarSize(b.type), scalarSize(a.type)));   // stable
            long sum = 8;
            for (Entry e : sorted) {
                sum += scalarSize(e.type);
            }
            long total = (sum + 7) / 8 * 8;
            if (total >= oldSize) {
                d.blocked = true;
                continue;
            }
            List<Entry> ne = new ArrayList<>(sorted);
            if (total > sum) {
                ne.add(new Entry(true, total - sum, null, null, null));
            }
            d.newEntries = ne;
        }
        Map<Long, Decl> byId = new HashMap<>();
        for (Decl d : decls.values()) {
            if (d.classId >= 0) {
                byId.put(d.classId, d);
            }
        }
        // construction sites: every classId push of a still-eligible struct must parse
        List<Site> sites = new ArrayList<>();
        for (int i = 0; i < L.size(); i++) {
            Decl d = classIdPush(L.get(i), byId);
            if (d == null || d.blocked) {
                continue;
            }
            Site s = parseSite(L, i, d, byId);
            if (s == null) {
                d.blocked = true;
            } else {
                sites.add(s);
            }
        }
        boolean any = false;
        for (Decl d : decls.values()) {
            if (!d.blocked && d.newEntries != null) {
                any = true;
            }
        }
        if (!any) {
            return new PassResult(L, false);
        }
        // rewrite
        Map<Integer, Site> siteAt = new HashMap<>();
        for (Site s : sites) {
            if (!s.decl.blocked) {
                siteAt.put(s.start, s);
            }
        }
        Map<Integer, Decl> declAt = new HashMap<>();
        for (Decl d : decls.values()) {
            if (!d.blocked && d.newEntries != null) {
                declAt.put(d.start, d);
            }
        }
        List<List<BytecodeToken>> out = new ArrayList<>(L.size());
        for (int i = 0; i < L.size(); i++) {
            Decl d = declAt.get(i);
            if (d != null) {
                out.addAll(d.head);
                for (Entry e : d.newEntries) {
                    if (e.pad) {
                        BytecodeToken near = d.head.get(0).get(0);
                        List<BytecodeToken> pl = new ArrayList<>();
                        pl.add(tok(near, "STRUCT_PADDING"));
                        pl.add(tok(near, Long.toString(e.padBytes)));
                        out.add(pl);
                    } else {
                        out.add(e.line);
                    }
                }
                out.add(L.get(d.end));
                i = d.end;
                continue;
            }
            Site s = siteAt.get(i);
            if (s != null) {
                out.add(L.get(i));   // the class id push
                for (Entry e : s.decl.newEntries) {
                    if (e.pad) {
                        List<BytecodeToken> pl = new ArrayList<>();
                        pl.add(tok(L.get(i).get(0), "STACK_LOCK"));
                        pl.add(tok(L.get(i).get(0), Long.toString(e.padBytes)));
                        out.add(pl);
                    } else {
                        out.addAll(s.units.get(e.name));
                    }
                }
                i = s.end - 1;
                continue;
            }
            out.add(L.get(i));
        }
        return new PassResult(out, true);
    }

    // ---------------------------------------------------------- declarations

    private static Map<String, Decl> readDecls(List<List<BytecodeToken>> L) {
        Map<String, Decl> out = new LinkedHashMap<>();
        Decl cur = null;
        for (int i = 0; i < L.size(); i++) {
            List<BytecodeToken> l = L.get(i);
            if (isMn(l, "STRUCT_START") && l.size() == 2) {
                cur = new Decl();
                cur.name = l.get(1).text;
                cur.start = i;
                cur.head.add(l);
            } else if (cur == null) {
                continue;
            } else if (isMn(l, "STRUCT_DECORATE")) {
                if (!(l.size() == 2 && l.get(1).text.equals("@pub"))) {
                    cur.plain = false;
                }
                cur.head.add(l);
            } else if (isMn(l, "STRUCT_MEMBER") && l.size() == 3) {
                String nm = l.get(1).text;
                String ty = l.get(2).text;
                if (nm.equals("___type")) {
                    cur.hasType = true;
                    cur.typeFirst = cur.entries.isEmpty();
                    cur.head.add(l);
                } else {
                    Entry e = new Entry(false, 0, nm, ty, l);
                    if (scalarSize(ty) < 0) {
                        cur.plain = false;
                    }
                    cur.entries.add(e);
                    cur.members.add(e);
                }
            } else if (isMn(l, "STRUCT_PADDING") && l.size() == 2) {
                long n;
                try {
                    n = Long.parseLong(l.get(1).text);
                } catch (NumberFormatException ex) {
                    cur.plain = false;
                    n = 0;
                }
                cur.entries.add(new Entry(true, n, null, null, l));
            } else if (isMn(l, "STRUCT_END")) {
                cur.end = i;
                out.put(cur.name, cur);
                cur = null;
            } else {
                cur.plain = false;   // something we do not understand inside a declaration
            }
        }
        return out;
    }

    /** Class id of each struct, and the id ranges used to find extends relations. */
    private static void readClassIds(List<List<BytecodeToken>> L, Map<String, Decl> decls) {
        for (List<BytecodeToken> l : L) {
            if (isMn(l, "ENUM") && l.size() >= 3 && l.get(1).text.equals("ClassID")) {
                for (int i = 2; i + 1 < l.size(); i += 2) {
                    Decl d = decls.get(l.get(i).text);
                    if (d != null) {
                        try {
                            d.classId = Long.parseLong(l.get(i + 1).text);
                        } catch (NumberFormatException ex) {
                            d.classId = -1;
                        }
                    }
                }
            }
        }
    }

    private static void blockByEnumRanges(List<List<BytecodeToken>> L, Map<String, Decl> decls) {
        Map<String, long[]> range = new HashMap<>();
        for (List<BytecodeToken> l : L) {
            if (isMn(l, "ENUM") && l.size() >= 3 && l.get(1).text.equals("Class")) {
                for (int i = 2; i + 1 < l.size(); i += 2) {
                    String r = l.get(i + 1).text;
                    int dots = r.indexOf("..");
                    if (dots > 0) {
                        try {
                            range.put(l.get(i).text, new long[] {Long.parseLong(r.substring(0, dots)), Long.parseLong(r.substring(dots + 2))});
                        } catch (NumberFormatException ex) {
                            // leave it out; the struct then has no range and is blocked below
                        }
                    }
                }
            }
        }
        for (Decl d : decls.values()) {
            long[] r = range.get(d.name);
            if (r == null || r[0] != r[1]) {
                d.blocked = true;   // unknown range, or it has children
                continue;
            }
            for (Map.Entry<String, long[]> e : range.entrySet()) {
                if (!e.getKey().equals(d.name) && e.getValue()[0] <= r[0] && r[0] <= e.getValue()[1] && e.getValue()[1] > e.getValue()[0]) {
                    d.blocked = true;   // covered by another struct's range: a child
                }
            }
        }
    }

    // ---------------------------------------------------------------- blockers

    /** Does the type text name struct s as its base, through an array suffix, mutability and storage prefixes? */
    private static boolean namesStruct(String text, String s) {
        String t = text;
        for (;;) {
            boolean cut = false;
            for (String p : new String[] {"owns_", "ref_", "raw_", "auto_", "static_", "some_", "mut_", "imut_", "indeterminate_", "atomic_"}) {
                if (t.startsWith(p)) {
                    t = t.substring(p.length());
                    cut = true;
                    break;
                }
            }
            if (!cut) {
                break;
            }
        }
        int br = t.indexOf('[');
        if (br > 0) {
            t = t.substring(0, br);
        }
        return t.equals(s);
    }

    private static boolean isRawTo(String text, String s) {
        if (!text.startsWith("raw_")) {
            return false;
        }
        return namesStruct(text, s);
    }

    private static void blockByUses(List<List<BytecodeToken>> L, Map<String, Decl> decls) {
        // struct used as a member of another struct (by value or array)
        for (Decl d : decls.values()) {
            for (Entry e : d.members) {
                for (Decl o : decls.values()) {
                    if (namesStruct(e.type, o.name) && !e.type.startsWith("owns_") && !e.type.startsWith("ref_")
                            && !e.type.startsWith("auto_") && !e.type.startsWith("static_")) {
                        o.blocked = true;
                    }
                }
            }
            for (Entry e : d.members) {
                for (Decl o : decls.values()) {
                    if (namesStruct(e.type, o.name)) {
                        o.blocked = true;   // any use as a member type, pointer or not (a raw one is also caught below)
                    }
                }
            }
        }
        // functions that declare a hidden destination pointer
        Set<String> rvo = new HashSet<>();
        String fn = null;
        for (List<BytecodeToken> l : L) {
            if (isMn(l, "FUNC_START") && l.size() >= 2) {
                fn = l.get(1).text;
            } else if (isMn(l, "ARG") && l.size() == 3 && l.get(1).text.equals("$ret_dest") && fn != null) {
                rvo.add(fn);
            }
        }
        for (int i = 0; i < L.size(); i++) {
            List<BytecodeToken> l = L.get(i);
            if (l.isEmpty() || l.get(0).kind != BytecodeToken.Kind.CODE) {
                continue;
            }
            String m = l.get(0).text;
            boolean decl = m.startsWith("STRUCT_");
            if (decl) {
                continue;
            }
            for (Decl d : decls.values()) {
                if (d.blocked) {
                    continue;
                }
                if ((m.equals("ALLOC_STATIC")) && lineNames(l, d.name)) {
                    d.blocked = true;
                    continue;
                }
                if ((m.equals("GLOBAL") || m.equals("ALLOC_STATIC")) && lineHasArrayOf(l, d.name)) {
                    d.blocked = true;
                    continue;
                }
                boolean raw = false;
                for (int k = 1; k < l.size(); k++) {
                    if (isRawTo(l.get(k).text, d.name)) {
                        raw = true;
                    }
                }
                if (!raw) {
                    continue;
                }
                boolean ok = false;
                if ((m.equals("ARG") || m.equals("PUSH")) && l.size() >= 2 && l.get(1).text.equals("$ret_dest")) {
                    ok = true;
                } else if (m.equals("RET")) {
                    ok = true;
                } else if ((m.equals("ADDR_OF") || (m.equals("POP") && l.size() >= 2 && l.get(1).text.equals("ARG0")))) {
                    // the address handed to a struct-returning callee: a CALL of one before the bracket closes
                    for (int j = i + 1; j < L.size() && j < i + 12; j++) {
                        List<BytecodeToken> n = L.get(j);
                        if (isMn(n, "CC_END")) {
                            break;
                        }
                        if (isMn(n, "CALL") && n.size() >= 2) {
                            ok = rvo.contains(n.get(1).text);
                            break;
                        }
                    }
                }
                if (!ok) {
                    d.blocked = true;
                }
            }
        }
    }

    private static boolean lineNames(List<BytecodeToken> l, String s) {
        for (int k = 1; k < l.size(); k++) {
            if (namesStruct(l.get(k).text, s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean lineHasArrayOf(List<BytecodeToken> l, String s) {
        for (int k = 1; k < l.size(); k++) {
            String t = l.get(k).text;
            if (t.indexOf('[') > 0 && namesStruct(t, s)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------ construction sites

    private static Decl classIdPush(List<BytecodeToken> l, Map<Long, Decl> byId) {
        if (isMn(l, "PUSH") && l.size() == 3 && l.get(2).text.equals("imut_u64")) {
            try {
                return byId.get(Long.parseLong(l.get(1).text));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static final int MAX_UNIT = 64;

    private Site parseSite(List<List<BytecodeToken>> L, int i, Decl d, Map<Long, Decl> byId) {
        List<Entry> order = d.entries;
        List<Site> found = new ArrayList<>();
        walk(L, i + 1, order, 0, 0, new ArrayList<>(), d, byId, i, found);
        return found.size() == 1 ? found.get(0) : null;
    }

    private void walk(List<List<BytecodeToken>> L, int j, List<Entry> order, int idx, int membersDone,
            List<List<List<BytecodeToken>>> acc, Decl d, Map<Long, Decl> byId, int start, List<Site> found) {
        if (found.size() > 1) {
            return;
        }
        if (idx == order.size()) {
            if (j < L.size()) {
                List<BytecodeToken> t = L.get(j);
                if (isMn(t, "ASSIGN") || isMn(t, "NEW") || classIdPush(t, byId) != null) {
                    Site s = new Site();
                    s.start = start;
                    s.end = j;
                    s.decl = d;
                    int k = 0;
                    for (Entry e : order) {
                        if (!e.pad) {
                            s.units.put(e.name, acc.get(k++));
                        }
                    }
                    found.add(s);
                }
            }
            return;
        }
        Entry e = order.get(idx);
        if (e.pad) {
            if (j < L.size() && isMn(L.get(j), "STACK_LOCK") && L.get(j).size() == 2
                    && L.get(j).get(1).text.equals(Long.toString(e.padBytes))) {
                walk(L, j + 1, order, idx + 1, membersDone, acc, d, byId, start, found);
            }
            return;
        }
        int depth = membersDone;
        List<List<BytecodeToken>> unit = new ArrayList<>();
        for (int p = j; p < L.size() && unit.size() < MAX_UNIT; p++) {
            List<BytecodeToken> l = L.get(p);
            int ef = effect(l);
            if (ef == Integer.MIN_VALUE) {
                return;
            }
            if (ef < 0 && depth - membersDone < 2) {
                return;   // a binary operator would eat the previous member's value
            }
            depth += ef;
            if (depth < membersDone + 1) {
                return;
            }
            unit.add(l);
            if (depth == membersDone + 1 && typeMatches(resultType(l), e.type)) {
                List<List<List<BytecodeToken>>> next = new ArrayList<>(acc);
                next.add(new ArrayList<>(unit));
                walk(L, p + 1, order, idx + 1, membersDone + 1, next, d, byId, start, found);
                if (found.size() > 1) {
                    return;
                }
            }
        }
    }

    private static boolean typeMatches(String pushed, String member) {
        return stripMut(pushed).equals(stripMut(member));
    }
}
```

### FILE: src/main/java/caspien/optimizer/StructUnpackingPass.java
```java
package caspien.optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites a struct instance living in a local variable to act as independent variables, one per member, instead of one opaque
 * struct-shaped slot (switch: {@code struct-unpacking: on|off}, default off).
 *
 * A local {@code ALLOC v T} whose type T is {@code mut_S} or {@code imut_S}, where S is a struct declared in the program with only
 * scalar members (integers, f32/f64, bool), qualifies when EVERY mention of v (or of v.member) is one of:
 *   - its own ALLOC;
 *   - a construction   ADDR v T ; PUSH lit imut_u64 (the hidden ___type slot) ; one expression per member, in declaration order ;
 *                      STACK_LOCK n (padding lines may also sit between members) ; ASSIGN T T T     -- each member expression may only use PUSH, arithmetic/compare/cast operators (a known
 *                      stack effect) and must not mention v;
 *   - a member read    PUSH v.m MT   (not followed by ADDR_OF);
 *   - a member write   ADDR v T ; PUSH_FIELDNAME m MT ; DOT_LHS T MT MT   (the left-hand side of an assignment or compound assignment).
 * Anything else -- the whole struct pushed or copied, passed by address (ADDR_OF), a nested or ___type access, an unknown line inside a
 * construction -- leaves v alone. Functions containing ASM_START are skipped.
 *
 * Effect: the ALLOC becomes one ALLOC per member ({@code v__m}, type from the struct's member list); every read/write is redirected to
 * {@code v__m}; a construction becomes one {@code ADDR v__m MT ; expr ; ASSIGN MT MT MT} per member. The hidden ___type slot and the padding
 * are dropped (a program that reads ___type is not touched). The new variables are ordinary scalars, so elision, shifting and the register
 * hints see them.
 */
public class StructUnpackingPass implements OptimizationPass {

    private final boolean enabled;

    public StructUnpackingPass() {
        this(false);
    }

    public StructUnpackingPass(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "struct-unpacking";
    }

    private static final Set<String> BINARY = new HashSet<>(List.of(
            "ADD", "SUB", "MUL", "DIV", "MOD", "SHL", "SHR", "BITS_OR", "BITS_AND", "BITS_XOR", "AND", "OR",
            "EQ", "NEQ", "LT", "LT_EQ", "GT", "GT_EQ"));
    private static final Set<String> UNARY = new HashSet<>(List.of("NEG", "NOT", "BITS_NOT", "TRUNC", "SEXT", "ZEXT", "FCONV"));

    /** struct name -> ordered (member name, member type), excluding ___type; only structs whose members are all scalars. */
    private static Map<String, List<String[]>> structs(List<List<BytecodeToken>> L) {
        Map<String, List<String[]>> out = new HashMap<>();
        String cur = null;
        List<String[]> mem = null;
        boolean ok = true;
        for (List<BytecodeToken> l : L) {
            String m = VarAnalysis.mnemonic(l);
            if ("STRUCT_START".equals(m) && l.size() == 2) {
                cur = l.get(1).text;
                mem = new ArrayList<>();
                ok = true;
            } else if ("STRUCT_MEMBER".equals(m) && cur != null && l.size() == 3) {
                String nm = l.get(1).text, ty = l.get(2).text;
                if (nm.equals("___type")) continue;
                if (ConstantFoldingPass.base(ty) == null || ty.startsWith("indeterminate_")) ok = false;
                mem.add(new String[] {nm, ty});
            } else if ("STRUCT_END".equals(m) && cur != null) {
                if (ok && !mem.isEmpty()) out.put(cur, mem);
                cur = null;
            }
        }
        return out;
    }

    private static boolean mentions(List<BytecodeToken> l, String v) {
        for (BytecodeToken t : l) {
            if (t.text.equals(v) || t.text.startsWith(v + ".")) return true;
        }
        return false;
    }

    /** Stack effect of one expression line, or Integer.MIN_VALUE when the line is not understood. */
    private static int effect(List<BytecodeToken> l) {
        String m = VarAnalysis.mnemonic(l);
        if (m == null) return Integer.MIN_VALUE;
        if (m.equals("PUSH") && l.size() == 3) return 1;
        if (BINARY.contains(m) && l.size() == 4) return -1;
        if (UNARY.contains(m)) return 0;
        return Integer.MIN_VALUE;
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return new PassResult(lines, false);
        }
        Map<String, List<String[]>> table = structs(lines);
        if (table.isEmpty()) {
            return new PassResult(lines, false);
        }
        Set<Integer> remove = new HashSet<>();
        Map<Integer, List<List<BytecodeToken>>> replaceWith = new HashMap<>();   // line index -> lines emitted instead (first line of a removed span)
        Map<Integer, String> renameRead = new HashMap<>();                       // line index of "PUSH v.m T" -> new name
        boolean changed = false;
        for (int[] f : VarAnalysis.functions(lines)) {
            if (VarAnalysis.hasMnemonic(lines, f[0], f[1], "ASM_START")) continue;
            Map<String, Integer> allocCount = new HashMap<>();
            Set<String> allNames = new HashSet<>();
            for (int i = f[0]; i <= f[1]; i++) {
                List<BytecodeToken> l = lines.get(i);
                for (BytecodeToken t : l) allNames.add(t.text);
                if ("ALLOC".equals(VarAnalysis.mnemonic(l)) && l.size() == 3) allocCount.merge(l.get(1).text, 1, Integer::sum);
            }
            for (int i = f[0]; i <= f[1]; i++) {
                List<BytecodeToken> l = lines.get(i);
                if (!"ALLOC".equals(VarAnalysis.mnemonic(l)) || l.size() != 3) continue;
                String v = l.get(1).text, T = l.get(2).text;
                if (v.startsWith("$") || allocCount.get(v) != 1) continue;
                String sname = T.startsWith("imut_") ? T.substring(5) : T.startsWith("mut_") ? T.substring(4) : null;
                List<String[]> members = sname == null ? null : table.get(sname);
                if (members == null) continue;
                Plan plan = plan(lines, f[0], f[1], i, v, T, members, allNames);
                if (plan == null) continue;
                changed = true;
                remove.addAll(plan.remove);
                replaceWith.putAll(plan.replace);
                renameRead.putAll(plan.rename);
            }
        }
        if (!changed) {
            return new PassResult(lines, false);
        }
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            List<List<BytecodeToken>> rep = replaceWith.get(i);
            if (rep != null) out.addAll(rep);
            if (remove.contains(i)) continue;
            String nn = renameRead.get(i);
            out.add(nn == null ? lines.get(i) : VarAnalysis.withToken(lines.get(i), 1, nn));
        }
        return new PassResult(out, true);
    }

    private static final class Plan {
        final Set<Integer> remove = new HashSet<>();
        final Map<Integer, List<List<BytecodeToken>>> replace = new HashMap<>();
        final Map<Integer, String> rename = new HashMap<>();
    }

    private static List<BytecodeToken> mk(BytecodeToken like, String... toks) {
        List<BytecodeToken> r = new ArrayList<>();
        for (String s : toks) r.add(new BytecodeToken(s, like.file, like.line, BytecodeToken.Kind.CODE));
        return r;
    }

    /** Returns the rewrite for variable v, or null when some mention is not one of the understood shapes. */
    private static Plan plan(List<List<BytecodeToken>> L, int s, int e, int allocIdx, String v, String T,
                             List<String[]> members, Set<String> allNames) {
        Map<String, String> newName = new LinkedHashMap<>();
        Map<String, String> memType = new HashMap<>();
        for (String[] m : members) {
            String nn = v + "__" + m[0];
            if (allNames.contains(nn)) return null;
            newName.put(m[0], nn);
            memType.put(m[0], m[1]);
        }
        Plan pl = new Plan();
        Set<Integer> handled = new HashSet<>();
        handled.add(allocIdx);
        int n = members.size();
        for (int i = s; i <= e; i++) {
            List<BytecodeToken> l = L.get(i);
            if (i == allocIdx || handled.contains(i)) continue;
            if (!mentions(l, v)) continue;
            String m = VarAnalysis.mnemonic(l);
            // member read
            if ("PUSH".equals(m) && l.size() == 3 && l.get(1).text.startsWith(v + ".")) {
                String mem = l.get(1).text.substring(v.length() + 1);
                if (!newName.containsKey(mem)) return null;
                if (i + 1 <= e && "ADDR_OF".equals(VarAnalysis.mnemonic(L.get(i + 1)))) return null;
                if (!l.get(2).text.equals(memType.get(mem))) return null;
                pl.rename.put(i, newName.get(mem));
                continue;
            }
            if (!"ADDR".equals(m) || l.size() != 3 || !l.get(1).text.equals(v) || !l.get(2).text.equals(T)) return null;
            // member write: ADDR v T ; PUSH_FIELDNAME m MT ; DOT_LHS T MT MT
            if (i + 2 <= e) {
                List<BytecodeToken> a = L.get(i + 1), b = L.get(i + 2);
                if ("PUSH_FIELDNAME".equals(VarAnalysis.mnemonic(a)) && a.size() == 3) {
                    String mem = a.get(1).text;
                    if (!newName.containsKey(mem)) return null;
                    if (!"DOT_LHS".equals(VarAnalysis.mnemonic(b)) || b.size() != 4 || !b.get(1).text.equals(T)) return null;
                    String mt = memType.get(mem);
                    if (!a.get(2).text.equals(mt) || !b.get(2).text.equals(mt) || !b.get(3).text.equals(mt)) return null;
                    pl.remove.add(i);
                    pl.remove.add(i + 1);
                    pl.remove.add(i + 2);
                    List<List<BytecodeToken>> r = new ArrayList<>();
                    r.add(mk(l.get(0), "ADDR", newName.get(mem), mt));
                    pl.replace.put(i, r);
                    handled.add(i + 1);
                    handled.add(i + 2);
                    // the operands that follow are checked on their own turn (they may not mention v except as member reads)
                    continue;
                }
            }
            // construction: ADDR v T ; PUSH lit imut_u64 ; n expressions ; STACK_LOCK k ; ASSIGN T T T
            int p = i + 1;
            if (p > e) return null;
            List<BytecodeToken> ty = L.get(p);
            if (!"PUSH".equals(VarAnalysis.mnemonic(ty)) || ty.size() != 3 || !ty.get(2).text.equals("imut_u64") || !ty.get(1).text.matches("[0-9]+")) return null;
            p++;
            // The member expressions are postfix; simulate a stack of "start line" markers to find where each one begins.
            List<Integer> starts = new ArrayList<>();
            while (true) {
                if (p > e) return null;
                List<BytecodeToken> x = L.get(p);
                String xm = VarAnalysis.mnemonic(x);
                if ("ASSIGN".equals(xm)) break;
                if (!"STACK_LOCK".equals(xm)) {
                    int ef = effect(x);
                    if (ef == Integer.MIN_VALUE || mentions(x, v)) return null;
                    if (ef == 1) {
                        starts.add(p);
                    } else if (ef == -1) {
                        if (starts.size() < 2) return null;
                        starts.remove(starts.size() - 1);
                    } else if (starts.isEmpty()) {
                        return null;
                    }
                }
                p++;
            }
            if (starts.size() != n) return null;
            List<int[]> groups = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                groups.add(new int[] {starts.get(k), k + 1 < n ? starts.get(k + 1) : p});
            }
            List<BytecodeToken> as = L.get(p);
            if (!"ASSIGN".equals(VarAnalysis.mnemonic(as)) || as.size() != 4 || !as.get(1).text.equals(T) || !as.get(2).text.equals(T) || !as.get(3).text.equals(T)) return null;
            List<List<BytecodeToken>> r = new ArrayList<>();
            int gi = 0;
            for (String[] mm : members) {
                int[] g = groups.get(gi++);
                r.add(mk(l.get(0), "ADDR", newName.get(mm[0]), mm[1]));
                for (int q = g[0]; q < g[1]; q++) {
                    if (!"STACK_LOCK".equals(VarAnalysis.mnemonic(L.get(q)))) r.add(L.get(q));
                }
                r.add(mk(l.get(0), "ASSIGN", mm[1], mm[1], mm[1]));
            }
            for (int q = i; q <= p; q++) {
                pl.remove.add(q);
                handled.add(q);
            }
            pl.replace.put(i, r);
            // note: expressions inside the groups were checked not to mention v; they may mention other variables freely
        }
        // ALLOC replacement
        List<List<BytecodeToken>> allocs = new ArrayList<>();
        for (String[] m : members) allocs.add(mk(L.get(allocIdx).get(0), "ALLOC", newName.get(m[0]), m[1]));
        pl.remove.add(allocIdx);
        pl.replace.put(allocIdx, allocs);
        return pl;
    }
}
```

### FILE: src/main/java/caspien/optimizer/UnrollConfig.java
```java
package caspien.optimizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Loop-unrolling settings, read from the top-level keys of "compiler.config" (the same file, with the same
 * mirrored copy, the other stages read). No other key of that file is looked at, and the file is optional
 * here: a missing file, or a missing 'loop-unrolling' key, means unrolling is off (this stage never needed a
 * config before).
 *
 *   loop-unrolling: off | conservative | balanced | aggressive      (default off)
 *   loop-unroll-factor: N                 partial unroll: N copies of the body per pass (0 or 1 = never partial)
 *   loop-unroll-full-max-trips: N         fully unroll a loop of at most N known iterations (0 = never full)
 *   loop-unroll-max-body-lines: N         never copy a loop body longer than N bytecode lines
 *   loop-unroll-max-growth: N             at most N added bytecode lines per function, over the whole run
 *
 * The preset supplies all four numbers; any of the four keys after it overrides just that number. The four
 * keys do nothing while the preset is off. A malformed value stops the compile (never silently ignored).
 */
public final class UnrollConfig {

    public boolean enabled = false;
    public String preset = "off";
    public int factor = 0;
    public int fullMaxTrips = 0;
    public int maxBodyLines = 0;
    public long maxGrowth = 0;

    public static UnrollConfig disabled() {
        return new UnrollConfig();
    }

    /** Loads "compiler.config" from the working directory if it exists; otherwise unrolling is off. */
    public static UnrollConfig loadFromWorkingDirectory() {
        Path p = Paths.get("compiler.config");
        if (!Files.exists(p)) {
            return disabled();
        }
        try {
            return parse(Files.readAllLines(p, StandardCharsets.UTF_8), p.toString());
        } catch (IOException e) {
            throw new RuntimeException("could not read '" + p + "': " + e.getMessage(), e);
        }
    }

    public static UnrollConfig parse(List<String> lines, String path) {
        String preset = "off";
        Integer factor = null, fullTrips = null, bodyLines = null;
        Long growth = null;
        for (int n = 0; n < lines.size(); n++) {
            String raw = lines.get(n);
            int hash = raw.indexOf('#');
            if (hash >= 0) {
                raw = raw.substring(0, hash);
            }
            if (raw.isEmpty() || Character.isWhitespace(raw.charAt(0))) {
                continue; // top-level keys only (the calling-convention block is indented)
            }
            String t = raw.trim();
            int colon = t.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = t.substring(0, colon).trim();
            String val = t.substring(colon + 1).trim();
            if (val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")) {
                val = val.substring(1, val.length() - 1);
            }
            switch (key) {
                case "loop-unrolling":
                    if (!(val.equals("off") || val.equals("conservative") || val.equals("balanced") || val.equals("aggressive"))) {
                        throw bad(path, n, "'loop-unrolling' must be off, conservative, balanced or aggressive, found '" + val + "'");
                    }
                    preset = val;
                    break;
                case "loop-unroll-factor":
                    factor = (int) number(path, n, key, val);
                    break;
                case "loop-unroll-full-max-trips":
                    fullTrips = (int) number(path, n, key, val);
                    break;
                case "loop-unroll-max-body-lines":
                    bodyLines = (int) number(path, n, key, val);
                    break;
                case "loop-unroll-max-growth":
                    growth = number(path, n, key, val);
                    break;
                default:
                    break;
            }
        }
        UnrollConfig c = new UnrollConfig();
        c.preset = preset;
        switch (preset) {
            case "conservative":
                c.set(2, 4, 40, 400);
                break;
            case "balanced":
                c.set(4, 16, 100, 2000);
                break;
            case "aggressive":
                c.set(8, 4096, 2000, 200000);
                break;
            default:
                return c; // off: the four numbers are ignored
        }
        c.enabled = true;
        if (factor != null) c.factor = factor;
        if (fullTrips != null) c.fullMaxTrips = fullTrips;
        if (bodyLines != null) c.maxBodyLines = bodyLines;
        if (growth != null) c.maxGrowth = growth;
        return c;
    }

    private void set(int factor, int fullMaxTrips, int maxBodyLines, long maxGrowth) {
        this.factor = factor;
        this.fullMaxTrips = fullMaxTrips;
        this.maxBodyLines = maxBodyLines;
        this.maxGrowth = maxGrowth;
    }

    private static long number(String path, int lineIdx, String key, String val) {
        if (val.isEmpty() || val.length() > 12) {
            throw bad(path, lineIdx, "'" + key + "' needs a whole number, found '" + val + "'");
        }
        long v = 0;
        for (int i = 0; i < val.length(); i++) {
            char ch = val.charAt(i);
            if (ch < '0' || ch > '9') {
                throw bad(path, lineIdx, "'" + key + "' needs a whole number (0 or more), found '" + val + "'");
            }
            v = v * 10 + (ch - '0');
        }
        return v;
    }

    private static RuntimeException bad(String path, int lineIdx, String msg) {
        return new RuntimeException(path + ":" + (lineIdx + 1) + ": " + msg);
    }

    @Override
    public String toString() {
        return enabled ? "loop-unrolling " + preset + " (factor " + factor + ", full<=" + fullMaxTrips + " trips, body<="
                + maxBodyLines + " lines, growth<=" + maxGrowth + ")" : "loop-unrolling off";
    }
}
```

### FILE: src/main/java/caspien/optimizer/UnusedDeclarationRemovalPass.java
```java
package caspien.optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes declarations nothing refers to any more (switch: {@code unused-declaration-removal: on|off}, default off): {@code EXTERN} lines, {@code GLOBAL}
 * and {@code ALLOC_STATIC} declarations (a name and its {@code name.member} lines), and {@code STRING} literals. It is the follow-on to
 * DeadFunctionRemovalPass and DeadControlFlowRemovalPass: a function or branch that disappeared often leaves its externs, statics and strings behind.
 *
 * A declaration is used when any CODE token on any other line is its name (for a global also {@code name.member}); tokens of quoted strings are not
 * references. Kept unconditionally: the externs the backend calls by fixed name with no reference in the bytecode ({@code malloc}, {@code realloc},
 * {@code strlen}, {@code exit}, {@code pthread_exit}, {@code sched_yield}, {@code free}) and the global {@code ghost_table}, whose lock state the backend
 * reads and writes directly. An {@code ALLOC_STATIC} whose name is declared more than once (a function-local static of the same name in several
 * functions) is left alone. The whole pass does nothing when the program has no {@code main} (a library exports its declarations) or contains
 * inline assembly ({@code ASM_START}, whose text may name symbols).
 */
public class UnusedDeclarationRemovalPass implements OptimizationPass {

    private final boolean enabled;

    public UnusedDeclarationRemovalPass() {
        this(false);
    }

    public UnusedDeclarationRemovalPass(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "unused-declaration-removal";
    }

    private static final Set<String> IMPLICIT_EXTERNS = new HashSet<>(List.of(
            "malloc", "realloc", "free", "strlen", "exit", "pthread_exit", "sched_yield"));
    private static final Set<String> IMPLICIT_GLOBALS = new HashSet<>(List.of("ghost_table"));

    private static String base(String name) {
        int d = name.indexOf('.');
        return d < 0 ? name : name.substring(0, d);
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return new PassResult(lines, false);
        }
        boolean hasMain = false;
        for (List<BytecodeToken> l : lines) {
            String m = VarAnalysis.mnemonic(l);
            if ("ASM_START".equals(m)) return new PassResult(lines, false);
            if ("FUNC_START".equals(m) && l.size() == 2 && l.get(1).text.equals("main")) hasMain = true;
        }
        if (!hasMain) {
            return new PassResult(lines, false);
        }
        // declaration name -> line indices that declare it (externs and strings: one line; globals/statics: the base line and every base.member line)
        Map<String, List<Integer>> decls = new HashMap<>();
        Map<String, Integer> staticBaseCount = new HashMap<>();
        Set<String> staticNames = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            List<BytecodeToken> l = lines.get(i);
            String m = VarAnalysis.mnemonic(l);
            if (m == null || l.size() < 2) continue;
            String n = l.get(1).text;
            switch (m) {
                case "EXTERN":
                case "STRING":
                    decls.computeIfAbsent(m + ":" + n, k -> new ArrayList<>()).add(i);
                    break;
                case "GLOBAL":
                    decls.computeIfAbsent("GLOBAL:" + base(n), k -> new ArrayList<>()).add(i);
                    break;
                case "ALLOC_STATIC":
                    decls.computeIfAbsent("ALLOC_STATIC:" + base(n), k -> new ArrayList<>()).add(i);
                    if (n.indexOf('.') < 0) staticBaseCount.merge(n, 1, Integer::sum);
                    staticNames.add(base(n));
                    break;
                default:
                    break;
            }
        }
        if (decls.isEmpty()) {
            return new PassResult(lines, false);
        }
        // every name mentioned by a CODE token on a line that does not itself declare it
        Set<String> used = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            List<BytecodeToken> l = lines.get(i);
            String m = VarAnalysis.mnemonic(l);
            String own = null;
            if (m != null && l.size() >= 2 && (m.equals("EXTERN") || m.equals("STRING"))) own = m + ":" + l.get(1).text;
            else if (m != null && l.size() >= 2 && (m.equals("GLOBAL") || m.equals("ALLOC_STATIC"))) own = m + ":" + base(l.get(1).text);
            for (int k = 0; k < l.size(); k++) {
                BytecodeToken t = l.get(k);
                if (t.kind != BytecodeToken.Kind.CODE) continue;
                if (k == 0 && own != null) continue;   // the mnemonic itself
                String b = base(t.text);
                for (String key : new String[] {"EXTERN:" + t.text, "STRING:" + t.text, "GLOBAL:" + b, "ALLOC_STATIC:" + b}) {
                    if (decls.containsKey(key) && !key.equals(own)) used.add(key);
                }
                // a declaration line's own name token (token 1) is not a use of itself; a later token naming the same thing is not either
            }
        }
        Set<Integer> remove = new HashSet<>();
        for (Map.Entry<String, List<Integer>> en : decls.entrySet()) {
            String key = en.getKey();
            if (used.contains(key)) continue;
            String nm = key.substring(key.indexOf(':') + 1);
            if (key.startsWith("EXTERN:") && IMPLICIT_EXTERNS.contains(nm)) continue;
            if (key.startsWith("GLOBAL:") && IMPLICIT_GLOBALS.contains(nm)) continue;
            if (key.startsWith("ALLOC_STATIC:") && staticBaseCount.getOrDefault(nm, 0) != 1) continue;
            remove.addAll(en.getValue());
        }
        if (remove.isEmpty()) {
            return new PassResult(lines, false);
        }
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            if (!remove.contains(i)) out.add(lines.get(i));
        }
        return new PassResult(out, true);
    }
}
```

### FILE: src/main/java/caspien/optimizer/VarAnalysis.java
```java
package caspien.optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared analysis for VariableElisionPass and VariableShiftingPass: for one function, finds the local scalar variables whose every
 * mention is one of exactly three shapes -- their own "ALLOC name T", a read "PUSH name T" (not immediately followed by ADDR_OF, which
 * would take the variable's address), or a constant assignment "ADDR name T / PUSH literal T2 / ASSIGN ..." -- and records where each
 * read and assignment sits. Any other mention (a compound assignment, ++, an address taken, a swap, a struct field, ...) makes the
 * variable "bad": neither pass touches it.
 *
 * Also provides the two control-flow questions both passes ask, answered from the jumps and labels of the function text:
 *   dominated(a, m): can a read at line m be reached without passing line a? (a forward jump sitting before a that lands between a and m)
 *   inLoop(a):       does a backward jump after line a land before it (a is inside a loop)?
 */
final class VarAnalysis {

    private VarAnalysis() {
    }

    static final class Var {
        String name, type;
        int allocs, allocIdx = -1;
        boolean bad;
        final List<Integer> reads = new ArrayList<>();
        final List<Integer> assigns = new ArrayList<>();   // index of the "ADDR name T" line; the literal PUSH is at +1
    }

    /** [start, end] line indices (FUNC_START .. FUNC_END inclusive) of every function. */
    static List<int[]> functions(List<List<BytecodeToken>> L) {
        List<int[]> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < L.size(); i++) {
            String m = mnemonic(L.get(i));
            if ("FUNC_START".equals(m)) {
                start = i;
            } else if ("FUNC_END".equals(m) && start >= 0) {
                out.add(new int[] {start, i});
                start = -1;
            }
        }
        return out;
    }

    static String mnemonic(List<BytecodeToken> line) {
        if (line.isEmpty() || line.get(0).kind != BytecodeToken.Kind.CODE) return null;
        return line.get(0).text;
    }

    static boolean hasMnemonic(List<List<BytecodeToken>> L, int s, int e, String m) {
        for (int i = s; i <= e; i++) {
            if (m.equals(mnemonic(L.get(i)))) return true;
        }
        return false;
    }

    /** A label definition line is one token "@name:". */
    static Map<String, Integer> labels(List<List<BytecodeToken>> L, int s, int e) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = s; i <= e; i++) {
            List<BytecodeToken> l = L.get(i);
            if (l.size() == 1 && l.get(0).kind == BytecodeToken.Kind.CODE && l.get(0).text.startsWith("@") && l.get(0).text.endsWith(":")) {
                String t = l.get(0).text;
                m.put(t.substring(0, t.length() - 1), i);
            }
        }
        return m;
    }

    /** Every "JMP @label" as {lineIndex, targetLabelLineIndex}; a target outside the function is ignored. */
    static List<int[]> jumps(List<List<BytecodeToken>> L, int s, int e, Map<String, Integer> labels) {
        List<int[]> out = new ArrayList<>();
        for (int i = s; i <= e; i++) {
            List<BytecodeToken> l = L.get(i);
            if (l.size() == 2 && "JMP".equals(mnemonic(l))) {
                Integer t = labels.get(l.get(1).text);
                if (t != null) out.add(new int[] {i, t});
            }
        }
        return out;
    }

    static boolean dominated(int a, int m, List<int[]> jumps) {
        for (int[] j : jumps) {
            if (j[0] < a && j[1] > a && j[1] <= m) return false;
        }
        return true;
    }

    static boolean inLoop(int a, List<int[]> jumps) {
        for (int[] j : jumps) {
            if (j[0] > a && j[1] < a) return true;
        }
        return false;
    }

    /** The scan described in the class comment, for the function spanning lines s..e. */
    static Map<String, Var> scan(List<List<BytecodeToken>> L, int s, int e) {
        Map<String, Var> vars = new LinkedHashMap<>();
        for (int i = s; i <= e; i++) {
            List<BytecodeToken> l = L.get(i);
            if (l.size() == 3 && "ALLOC".equals(mnemonic(l)) && !l.get(1).text.startsWith("$") && ConstantFoldingPass.base(l.get(2).text) != null) {
                Var v = vars.computeIfAbsent(l.get(1).text, k -> new Var());
                v.name = l.get(1).text;
                v.type = l.get(2).text;
                v.allocs++;
                v.allocIdx = i;
            }
        }
        for (Var v : vars.values()) {
            if (v.allocs != 1) v.bad = true;
        }
        for (int i = s; i <= e; i++) {
            List<BytecodeToken> l = L.get(i);
            for (int t = 1; t < l.size(); t++) {
                BytecodeToken tok = l.get(t);
                if (tok.kind != BytecodeToken.Kind.CODE) continue;
                String txt = tok.text;
                Var v = vars.get(txt);
                if (v == null) {
                    int dot = txt.indexOf('.');
                    if (dot > 0) v = vars.get(txt.substring(0, dot));
                }
                if (v == null) continue;
                if (i == v.allocIdx && t == 1) continue; // its own ALLOC
                String m = mnemonic(l);
                if (t == 1 && txt.equals(v.name) && l.size() == 3 && "PUSH".equals(m) && !"ADDR_OF".equals(i + 1 <= e ? mnemonic(L.get(i + 1)) : null)) {
                    v.reads.add(i);
                } else if (t == 1 && txt.equals(v.name) && l.size() == 3 && "ADDR".equals(m) && i + 2 <= e && isConstAssign(L, i, v)) {
                    v.assigns.add(i);
                } else {
                    v.bad = true;
                }
            }
        }
        return vars;
    }

    /** "ADDR x T" at i, "PUSH literal T2" at i+1 (same base type as x), "ASSIGN ..." at i+2. */
    private static boolean isConstAssign(List<List<BytecodeToken>> L, int i, Var v) {
        ConstantFoldingPass.Lit lit = ConstantFoldingPass.lit(L.get(i + 1));
        if (lit == null) return false;
        String vb = ConstantFoldingPass.base(v.type);
        if (vb == null || !vb.equals(lit.base) || !vb.equals(ConstantFoldingPass.base(L.get(i).get(2).text))) return false;
        if (ConstantFoldingPass.isInt(vb) && lit.i.signum() < 0 && ConstantFoldingPass.width(vb) < 64) return false; // a negative narrow literal push is not relied on
        return "ASSIGN".equals(mnemonic(L.get(i + 2)));
    }

    static List<BytecodeToken> withToken(List<BytecodeToken> line, int idx, String text) {
        List<BytecodeToken> out = new ArrayList<>(line);
        BytecodeToken o = line.get(idx);
        out.set(idx, new BytecodeToken(text, o.file, o.line, o.kind));
        return out;
    }
}
```

### FILE: src/main/java/caspien/optimizer/VariableAllocationReorderingPass.java
```java
package caspien.optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Variable-allocation reordering (`variable-allocation-reordering: on|off`, default off).
 *
 * Frame offsets are assigned by the LowerOrderGenerator's AddressLoweringPass purely from the order of a function's ALLOC lines: each slot goes
 * immediately below the previous one, rounded DOWN to its own alignment, and the gap this leaves above it is wasted. So `u8, u64, u8, u64`
 * loses 7 bytes twice. This pass reorders the function's hoisted ALLOC run so the gaps disappear:
 *
 *   1. alignment, largest first (8, 4, 2, 1). Every slot's size is a multiple of its alignment (structs are padded to their alignment, an
 *      array's is its element's), so once the slots are sorted the only padding left is the final round-up of the frame to 16.
 *   2. inside one alignment class, the slot with the larger use weight first (the same weight RegVarHintPass uses: each PUSH/ADDR mention
 *      counts 8^loopDepth, depth capped at 4), so hot variables sit nearest rbp, where an x86 displacement fits one byte.
 *   3. the original order (a stable sort), so equal keys never move.
 *
 * What stays put: `gt_routine_address` (rbp-8) and `gt_error_message` (rbp-16) must be the first two slots of every frame that has them
 * (GT_UNWIND and its MSG copy address them by those offsets) and are pinned there. Parameters are not ALLOCs yet at this point in the
 * pipeline (`ARG` lines): ArgToAllocLoweringPass, which runs afterwards in the LowerOrderGenerator, inserts their slots right behind the
 * pinned ones, so they stay ahead of every reordered local. Only the leading, contiguous ALLOC run is touched (ALLOC lines that appear later,
 * such as the inline temporary of an async `par` call, keep their place). A function with an ASM block, or with an ALLOC of one of the two
 * pinned names anywhere but the very front of the run, is left alone.
 *
 * Correctness needs no analysis: a local is only ever mentioned by name, offsets are computed after this pass, nothing else depends on the
 * slot order (the language does not define the layout of locals), and this pass never adds, removes or renames a line. The alignment table
 * below mirrors AddressLoweringPass.Sizes.alignOf (storage pointers 8, u8/s8/bool/char 1, u16/s16 2, u32/s32/f32 4, u64/s64/f64/code_addr/
 * string/range/dynarray 8, a fixed array its element's, a struct the max over its members, anything unknown 8); a wrong estimate could only
 * cost some padding, never correctness.
 */
public class VariableAllocationReorderingPass implements OptimizationPass {

    private final boolean enabled;

    public VariableAllocationReorderingPass() {
        this(false);
    }

    public VariableAllocationReorderingPass(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "variable-allocation-reordering";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return new PassResult(lines, false);
        }
        Align table = new Align(lines);
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        boolean changed = false;
        int i = 0;
        int n = lines.size();
        while (i < n) {
            if (!isMnemonic(lines.get(i), "FUNC_START")) {
                out.add(lines.get(i));
                i++;
                continue;
            }
            int end = i;
            while (end < n && !isMnemonic(lines.get(end), "FUNC_END")) {
                end++;
            }
            end = Math.min(end, n - 1);
            List<List<BytecodeToken>> fn = lines.subList(i, end + 1);
            List<List<BytecodeToken>> re = reorderFunction(fn, table);
            if (re != null) {
                changed = true;
                out.addAll(re);
            } else {
                out.addAll(fn);
            }
            i = end + 1;
        }
        return new PassResult(changed ? out : lines, changed);
    }

    private static boolean isMnemonic(List<BytecodeToken> line, String m) {
        return !line.isEmpty() && line.get(0).kind == BytecodeToken.Kind.CODE && line.get(0).text.equals(m);
    }

    private static boolean isAlloc(List<BytecodeToken> l) {
        return isMnemonic(l, "ALLOC") && l.size() == 3;
    }

    /** The function's lines with the leading ALLOC run reordered, or null when nothing changes. */
    private List<List<BytecodeToken>> reorderFunction(List<List<BytecodeToken>> fn, Align table) {
        int n = fn.size();
        for (List<BytecodeToken> l : fn) {
            if (isMnemonic(l, "ASM_START")) {
                return null;
            }
        }
        // the leading run: the first ALLOC that follows only header lines, and every ALLOC directly after it
        int first = -1;
        for (int k = 0; k < n; k++) {
            List<BytecodeToken> l = fn.get(k);
            if (isAlloc(l)) {
                first = k;
                break;
            }
            if (!(isMnemonic(l, "FUNC_START") || isMnemonic(l, "FUNC_DECORATE") || isMnemonic(l, "RETURNS") || isMnemonic(l, "ARG")
                    || isMnemonic(l, "ALLOC_STATIC"))) {
                return null;
            }
        }
        if (first < 0) {
            return null;
        }
        int last = first;
        while (last + 1 < n && isAlloc(fn.get(last + 1))) {
            last++;
        }
        // pinned prefix
        int p = first;
        if (p <= last && fn.get(p).get(1).text.equals("gt_routine_address")) {
            p++;
            if (p <= last && fn.get(p).get(1).text.equals("gt_error_message")) {
                p++;
            }
        }
        for (int k = p; k <= last; k++) {
            String nm = fn.get(k).get(1).text;
            if (nm.equals("gt_routine_address") || nm.equals("gt_error_message")) {
                return null;
            }
        }
        for (int k = last + 1; k < n; k++) {
            List<BytecodeToken> l = fn.get(k);
            if (isAlloc(l)) {
                String nm = l.get(1).text;
                if (nm.equals("gt_routine_address") || nm.equals("gt_error_message")) {
                    return null;
                }
            }
        }
        int count = last - p + 1;
        if (count < 2) {
            return null;
        }
        // weights: 8^loopDepth per mention (a backward JMP to an already-seen label closes a loop over [label, jmp])
        int[] delta = new int[n + 1];
        Map<String, Integer> labelAt = new HashMap<>();
        for (int k = 0; k < n; k++) {
            List<BytecodeToken> l = fn.get(k);
            if (l.size() == 1 && l.get(0).text.startsWith("@") && l.get(0).text.endsWith(":")) {
                String t = l.get(0).text;
                labelAt.put(t.substring(0, t.length() - 1), k);
            } else if (isMnemonic(l, "JMP") && l.size() >= 2) {
                Integer at = labelAt.get(l.get(1).text);
                if (at != null) {
                    delta[at]++;
                    delta[k + 1]--;
                }
            }
        }
        Map<String, Long> weights = new HashMap<>();
        int run = 0;
        for (int k = 0; k < n; k++) {
            run += delta[k];
            List<BytecodeToken> l = fn.get(k);
            if (isMnemonic(l, "ALLOC") || isMnemonic(l, "FUNC_START")) {
                continue;
            }
            long w = 1L << (3 * Math.min(run, 4));
            for (int t = 1; t < l.size(); t++) {
                if (l.get(t).kind == BytecodeToken.Kind.CODE) {
                    weights.merge(l.get(t).text, w, Long::sum);
                }
            }
        }
        final class Slot {
            final int idx;
            final List<BytecodeToken> line;
            final long align;
            final long weight;

            Slot(int idx, List<BytecodeToken> line) {
                this.idx = idx;
                this.line = line;
                this.align = table.of(line.get(2).text);
                this.weight = weights.getOrDefault(line.get(1).text, 0L);
            }

        }
        List<Slot> slots = new ArrayList<>(count);
        for (int k = p; k <= last; k++) {
            slots.add(new Slot(k - p, fn.get(k)));
        }
        List<Slot> sorted = new ArrayList<>(slots);
        sorted.sort((a, b) -> {
            if (a.align != b.align) {
                return Long.compare(b.align, a.align);
            }
            if (a.weight != b.weight) {
                return Long.compare(b.weight, a.weight);
            }
            return Integer.compare(a.idx, b.idx);
        });
        boolean same = true;
        for (int k = 0; k < count; k++) {
            if (sorted.get(k) != slots.get(k)) {
                same = false;
                break;
            }
        }
        if (same) {
            return null;
        }
        List<List<BytecodeToken>> result = new ArrayList<>(n);
        result.addAll(fn.subList(0, p));
        for (Slot s : sorted) {
            result.add(s.line);
        }
        result.addAll(fn.subList(last + 1, n));
        return result;
    }

    /** Alignment of a canonical type text; mirrors the LowerOrderGenerator's own table (see the class comment). */
    static final class Align {
        private final Map<String, List<String>> structMembers = new HashMap<>();
        private final Map<String, Long> structCache = new HashMap<>();

        Align(List<List<BytecodeToken>> lines) {
            String cur = null;
            for (List<BytecodeToken> l : lines) {
                if (isMnemonic(l, "STRUCT_START") && l.size() >= 2) {
                    cur = l.get(1).text;
                    structMembers.put(cur, new ArrayList<>());
                } else if (isMnemonic(l, "STRUCT_MEMBER") && l.size() >= 3 && cur != null) {
                    structMembers.get(cur).add(l.get(2).text);
                } else if (isMnemonic(l, "STRUCT_END")) {
                    cur = null;
                }
            }
        }

        long of(String canonical) {
            String rest = canonical;
            int u = rest.indexOf('_');
            if (u > 0) {
                String c = rest.substring(0, u);
                if (c.equals("owns") || c.equals("ref") || c.equals("raw") || c.equals("auto") || c.equals("static")) {
                    return 8;
                }
            }
            u = rest.indexOf('_');
            if (u > 0 && rest.substring(0, u).equals("some")) {
                rest = rest.substring(u + 1);
            }
            u = rest.indexOf('_');
            String base = u > 0 ? rest.substring(u + 1) : rest;
            if (base.startsWith("atomic_")) {
                base = base.substring("atomic_".length());
            }
            return ofBase(base);
        }

        private long ofBase(String base) {
            switch (base) {
                case "u8": case "s8": case "bool": case "char": case "void":
                    return 1;
                case "u16": case "s16":
                    return 2;
                case "u32": case "s32": case "f32":
                    return 4;
                case "u64": case "s64": case "f64": case "code_addr": case "string":
                    return 8;
                default:
                    break;
            }
            if (base.startsWith("dynarray(") && base.endsWith(")")) {
                return 8;
            }
            if (base.endsWith("]")) {
                int open = base.lastIndexOf('[');
                if (open > 0) {
                    return of(base.substring(0, open));
                }
            }
            if (base.equals("range") || base.startsWith("range(")) {
                return 8;
            }
            List<String> members = structMembers.get(base);
            if (members != null) {
                Long cached = structCache.get(base);
                if (cached != null) {
                    return cached;
                }
                structCache.put(base, 1L);
                long max = 1;
                for (String m : members) {
                    max = Math.max(max, of(m));
                }
                structCache.put(base, max);
                return max;
            }
            return 8;
        }
    }
}
```

### FILE: src/main/java/caspien/optimizer/VariableConfig.java
```java
package caspien.optimizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Switches for the two variable passes, read from the top-level keys of "compiler.config":
 *
 *   variable-elision: on | off      (default off)
 *   variable-shifting: on | off     (default off)
 *   struct-unpacking: on | off      (default off)
 *   dead-control-flow-removal: on | off  (default off)
 *   dead-function-removal: on | off  (default off)
 *   unused-declaration-removal: on | off  (default off)
 *   variable-allocation-reordering: on | off  (default off)
 *   struct-member-reordering: on | off  (default off)
 *
 * A missing file or key means off; any other value stops the compile.
 */
public final class VariableConfig {

    public final boolean elision;
    public final boolean shifting;
    public final boolean unpacking;
    public final boolean deadFlow;
    public final boolean deadFunctions;
    public final boolean unusedDecls;
    public final boolean allocReorder;
    public final boolean structReorder;

    public VariableConfig(boolean elision, boolean shifting) {
        this(elision, shifting, false);
    }

    public VariableConfig(boolean elision, boolean shifting, boolean unpacking) {
        this(elision, shifting, unpacking, false);
    }

    public VariableConfig(boolean elision, boolean shifting, boolean unpacking, boolean deadFlow) {
        this(elision, shifting, unpacking, deadFlow, false);
    }

    public VariableConfig(boolean elision, boolean shifting, boolean unpacking, boolean deadFlow, boolean deadFunctions) {
        this(elision, shifting, unpacking, deadFlow, deadFunctions, false);
    }

    public VariableConfig(boolean elision, boolean shifting, boolean unpacking, boolean deadFlow, boolean deadFunctions, boolean unusedDecls) {
        this(elision, shifting, unpacking, deadFlow, deadFunctions, unusedDecls, false);
    }

    public VariableConfig(boolean elision, boolean shifting, boolean unpacking, boolean deadFlow, boolean deadFunctions, boolean unusedDecls, boolean allocReorder) {
        this(elision, shifting, unpacking, deadFlow, deadFunctions, unusedDecls, allocReorder, false);
    }

    public VariableConfig(boolean elision, boolean shifting, boolean unpacking, boolean deadFlow, boolean deadFunctions, boolean unusedDecls, boolean allocReorder, boolean structReorder) {
        this.structReorder = structReorder;
        this.allocReorder = allocReorder;
        this.unusedDecls = unusedDecls;
        this.deadFunctions = deadFunctions;
        this.deadFlow = deadFlow;
        this.elision = elision;
        this.shifting = shifting;
        this.unpacking = unpacking;
    }

    public static VariableConfig disabled() {
        return new VariableConfig(false, false, false, false, false, false, false, false);
    }

    public static VariableConfig loadFromWorkingDirectory() {
        Path p = Paths.get("compiler.config");
        if (!Files.exists(p)) {
            return disabled();
        }
        try {
            return parse(Files.readAllLines(p, StandardCharsets.UTF_8), p.toString());
        } catch (IOException e) {
            throw new RuntimeException("could not read '" + p + "': " + e.getMessage(), e);
        }
    }

    public static VariableConfig parse(List<String> lines, String path) {
        boolean elision = false, shifting = false, unpacking = false, deadFlow = false, deadFunctions = false, unusedDecls = false, allocReorder = false, structReorder = false;
        for (int n = 0; n < lines.size(); n++) {
            String raw = lines.get(n);
            int hash = raw.indexOf('#');
            if (hash >= 0) {
                raw = raw.substring(0, hash);
            }
            if (raw.isEmpty() || Character.isWhitespace(raw.charAt(0))) {
                continue;
            }
            String t = raw.trim();
            int colon = t.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = t.substring(0, colon).trim();
            if (!key.equals("variable-elision") && !key.equals("variable-shifting") && !key.equals("struct-unpacking") && !key.equals("dead-control-flow-removal") && !key.equals("dead-function-removal") && !key.equals("unused-declaration-removal") && !key.equals("variable-allocation-reordering") && !key.equals("struct-member-reordering")) {
                continue;
            }
            String val = t.substring(colon + 1).trim();
            boolean on;
            if (val.equals("on")) {
                on = true;
            } else if (val.equals("off")) {
                on = false;
            } else {
                throw new RuntimeException(path + ":" + (n + 1) + ": '" + key + "' must be on or off, found '" + val + "'");
            }
            if (key.equals("variable-elision")) elision = on;
            else if (key.equals("variable-shifting")) shifting = on;
            else if (key.equals("struct-unpacking")) unpacking = on;
            else if (key.equals("dead-control-flow-removal")) deadFlow = on;
            else if (key.equals("dead-function-removal")) deadFunctions = on;
            else if (key.equals("unused-declaration-removal")) unusedDecls = on;
            else if (key.equals("variable-allocation-reordering")) allocReorder = on;
            else structReorder = on;
        }
        return new VariableConfig(elision, shifting, unpacking, deadFlow, deadFunctions, unusedDecls, allocReorder, structReorder);
    }

    @Override
    public String toString() {
        return "variable-elision " + (elision ? "on" : "off") + "; variable-shifting " + (shifting ? "on" : "off") + "; struct-unpacking " + (unpacking ? "on" : "off") + "; dead-control-flow-removal " + (deadFlow ? "on" : "off") + "; dead-function-removal " + (deadFunctions ? "on" : "off") + "; unused-declaration-removal " + (unusedDecls ? "on" : "off") + "; variable-allocation-reordering " + (allocReorder ? "on" : "off") + "; struct-member-reordering " + (structReorder ? "on" : "off");
    }
}
```

### FILE: src/main/java/caspien/optimizer/VariableElisionPass.java
```java
package caspien.optimizer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Variable elision (constant propagation for a variable that is assigned exactly once): finds
 *
 *     ADDR x T
 *     PUSH <literal> T2
 *     ASSIGN ...
 *
 * where x is a local scalar that is never assigned again and never has its address taken, removes the assignment (and x's ALLOC),
 * and replaces every read "PUSH x T" with "PUSH <literal> T". With ConstantFoldingPass running in the same inner loop, the
 * substituted literals then fold in turn.
 *
 * Safe only if every read is reached through the assignment, so a read must sit after the assignment in the text and no forward jump
 * from before the assignment may land between it and the read (an assignment inside an 'if' branch does not license reads after the
 * branch). A variable with any other kind of mention (compound assignment, ++, address taken, struct field, parameter, ...) is left
 * alone. Only integers (u/s 8..64), f32/f64 and bool; a negative literal in a narrow type is not propagated. A function containing
 * inline assembly is skipped.
 *
 * Off unless "variable-elision: on" is set in compiler.config (see VariableConfig).
 */
public class VariableElisionPass implements OptimizationPass {

    private final boolean enabled;

    public VariableElisionPass() {
        this(false);
    }

    public VariableElisionPass(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "variable-elision";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return new PassResult(lines, false);
        }
        Set<Integer> remove = new HashSet<>();
        Map<Integer, String> replaceRead = new HashMap<>();   // line index of "PUSH x T" -> literal text
        // per function (keyed by FUNC_START index): elided name -> non-negative integer literal, for rewriting range types like imut_range(0,imut_n)
        TreeMap<Integer, Object[]> boundLits = new TreeMap<>();
        for (int[] f : VarAnalysis.functions(lines)) {
            if (VarAnalysis.hasMnemonic(lines, f[0], f[1], "ASM_START")) continue;
            Map<String, VarAnalysis.Var> vars = VarAnalysis.scan(lines, f[0], f[1]);
            Map<String, Integer> labels = VarAnalysis.labels(lines, f[0], f[1]);
            List<int[]> jumps = VarAnalysis.jumps(lines, f[0], f[1], labels);
            Map<String, String> lits = new HashMap<>();
            boundLits.put(f[0], new Object[] {f[1], lits});
            for (VarAnalysis.Var v : vars.values()) {
                if (v.bad || v.assigns.size() != 1) continue;
                int a = v.assigns.get(0);
                boolean ok = true;
                for (int r : v.reads) {
                    if (r < a || !VarAnalysis.dominated(a, r, jumps)) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
                String lit = lines.get(a + 1).get(1).text;
                remove.add(a);
                remove.add(a + 1);
                remove.add(a + 2);
                remove.add(v.allocIdx);
                if (lit.matches("[0-9]+")) lits.put(v.name, lit);
                for (int r : v.reads) {
                    replaceRead.put(r, lit);
                }
            }
        }
        if (remove.isEmpty()) {
            return new PassResult(lines, false);
        }
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            if (remove.contains(i)) continue;
            String lit = replaceRead.get(i);
            List<BytecodeToken> row = lit == null ? lines.get(i) : VarAnalysis.withToken(lines.get(i), 1, lit);
            Map.Entry<Integer, Object[]> fe = boundLits.floorEntry(i);
            if (fe != null && i <= (Integer) fe.getValue()[0]) {
                @SuppressWarnings("unchecked")
                Map<String, String> lits = (Map<String, String>) fe.getValue()[1];
                if (!lits.isEmpty()) row = rewriteRangeTypes(row, lits);
            }
            out.add(row);
        }
        return new PassResult(out, true);
    }

    /**
     * A hidden for-range's type text spells a variable bound by name, e.g. {@code imut_range(0,imut_n)}. Once n is elided the name is
     * dangling, and LoopUnrollingPass (which reads literal trip counts only from this text) could not use the known bound. Rewrites
     * such bounds to the literal: {@code imut_range(0,4)}. Every token spelling the type is rewritten the same way, so they stay equal.
     */
    private static List<BytecodeToken> rewriteRangeTypes(List<BytecodeToken> row, Map<String, String> lits) {
        List<BytecodeToken> res = row;
        for (int k = 0; k < row.size(); k++) {
            String s = row.get(k).text;
            int open = s.indexOf("range(");
            if (open < 0 || !s.endsWith(")")) continue;
            String inner = s.substring(open + 6, s.length() - 1);
            int comma = inner.indexOf(',');
            if (comma < 0) continue;
            String lo = boundText(inner.substring(0, comma), lits), hi = boundText(inner.substring(comma + 1), lits);
            String ns = s.substring(0, open + 6) + lo + "," + hi + ")";
            if (!ns.equals(s)) {
                if (res == row) res = new ArrayList<>(row);
                BytecodeToken o = row.get(k);
                res.set(k, new BytecodeToken(ns, o.file, o.line, o.kind));
            }
        }
        return res;
    }

    private static String boundText(String part, Map<String, String> lits) {
        String nm = part.startsWith("imut_") ? part.substring(5) : part.startsWith("mut_") ? part.substring(4) : null;
        if (nm != null && lits.containsKey(nm)) return lits.get(nm);
        return part;
    }
}
```

### FILE: src/main/java/caspien/optimizer/VariableShiftingPass.java
```java
package caspien.optimizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Variable shifting: a local scalar that is assigned constants more than once, and is otherwise a variable elision candidate (every
 * assignment is "ADDR x T / PUSH literal / ASSIGN", never address-taken, no other kind of mention), is split at each reassignment:
 * a new variable of the same type is allocated (its ALLOC is hoisted next to x's), the reassignment now writes to it, and every
 * reference after that point is renamed to it. Each resulting variable is assigned once, so VariableElisionPass can then take them.
 *
 *     let x = 1; use(x); x = 2; use(x)      ->     x = 1; use(x); x__s1 = 2; use(x__s1)
 *
 * Only straight-line splits are made. A reassignment is split only if it is reached by every path to the references after it (no
 * forward jump from before it lands between it and a later reference) and is not inside a loop (no backward jump after it lands
 * before it); a reassignment inside an 'if' branch or a loop body is left alone, and so is x's first assignment. A function with a
 * catch block (its body is emitted at the top of the function, out of textual order) or inline assembly is skipped.
 *
 * Off unless "variable-shifting: on" is set in compiler.config (see VariableConfig).
 */
public class VariableShiftingPass implements OptimizationPass {

    private final boolean enabled;

    public VariableShiftingPass() {
        this(false);
    }

    public VariableShiftingPass(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "variable-shifting";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return new PassResult(lines, false);
        }
        Map<Integer, String> rename = new HashMap<>();           // line index -> new variable name (token 1 of that line)
        Map<Integer, List<List<BytecodeToken>>> insertAfter = new TreeMap<>(); // ALLOC line index -> new ALLOC lines
        for (int[] f : VarAnalysis.functions(lines)) {
            if (VarAnalysis.hasMnemonic(lines, f[0], f[1], "ASM_START") || hasCatchLabel(lines, f[0], f[1])) continue;
            Map<String, VarAnalysis.Var> vars = VarAnalysis.scan(lines, f[0], f[1]);
            Map<String, Integer> labels = VarAnalysis.labels(lines, f[0], f[1]);
            List<int[]> jumps = VarAnalysis.jumps(lines, f[0], f[1], labels);
            Set<String> used = new HashSet<>();
            for (int i = f[0]; i <= f[1]; i++) {
                for (BytecodeToken t : lines.get(i)) used.add(t.text);
            }
            for (VarAnalysis.Var v : vars.values()) {
                if (v.bad || v.assigns.size() < 2) continue;
                int first = v.assigns.get(0);
                boolean declFirst = true;
                for (int r : v.reads) {
                    if (r < first) declFirst = false;
                }
                if (!declFirst) continue;
                // every mention after a given point, in order (reads and assignments)
                TreeMap<Integer, Boolean> mentions = new TreeMap<>(); // line index -> isAssign
                for (int r : v.reads) mentions.put(r, false);
                for (int a : v.assigns) mentions.put(a, true);
                String cur = v.name;
                int serial = 0;
                List<List<BytecodeToken>> newAllocs = new ArrayList<>();
                for (Map.Entry<Integer, Boolean> me : mentions.entrySet()) {
                    int idx = me.getKey();
                    if (me.getValue() && idx != first && canSplit(idx, mentions, jumps)) {
                        String nn;
                        do {
                            serial++;
                            nn = v.name + "__s" + serial;
                        } while (used.contains(nn));
                        used.add(nn);
                        cur = nn;
                        List<BytecodeToken> al = lines.get(v.allocIdx);
                        List<BytecodeToken> na = VarAnalysis.withToken(al, 1, nn);
                        newAllocs.add(na);
                    }
                    if (!cur.equals(v.name)) {
                        rename.put(idx, cur);
                    }
                }
                if (!newAllocs.isEmpty()) {
                    insertAfter.computeIfAbsent(v.allocIdx, k -> new ArrayList<>()).addAll(newAllocs);
                }
            }
        }
        if (rename.isEmpty()) {
            return new PassResult(lines, false);
        }
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size() + 8);
        for (int i = 0; i < lines.size(); i++) {
            String nn = rename.get(i);
            out.add(nn == null ? lines.get(i) : VarAnalysis.withToken(lines.get(i), 1, nn));
            List<List<BytecodeToken>> extra = insertAfter.get(i);
            if (extra != null) out.addAll(extra);
        }
        return new PassResult(out, true);
    }

    /** A reassignment at line a can start a new version only if it is reached on every path to the later mentions and is not in a loop. */
    private static boolean canSplit(int a, TreeMap<Integer, Boolean> mentions, List<int[]> jumps) {
        if (VarAnalysis.inLoop(a, jumps)) return false;
        for (int m : mentions.tailMap(a, false).keySet()) {
            if (!VarAnalysis.dominated(a, m, jumps)) return false;
        }
        return true;
    }

    private static boolean hasCatchLabel(List<List<BytecodeToken>> L, int s, int e) {
        for (int i = s; i <= e; i++) {
            List<BytecodeToken> l = L.get(i);
            if (l.size() == 1 && l.get(0).text.startsWith("@catch_")) return true;
        }
        return false;
    }
}
```
