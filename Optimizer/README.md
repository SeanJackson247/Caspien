# caspien-optimizer

Stage 2 of the Caspien toolchain: higher-order bytecode (HOB, plain text) in, higher-order bytecode out. It holds the "shallow" passes
that rewrite the bytecode before it is lowered: constant folding, variable elision/shifting, struct unpacking, dead code removal,
loop unrolling, function inlining, the two layout reorderings, `sizeof` resolution and the register hints. All real lowering (address
lowering, register form, register variables, fusion passes) lives in the next stage, `LowerOrderGenerator/`. The two stages share no
code; the text bytecode is the only interchange format.

Every pass is a pure function `List<List<BytecodeToken>> -> PassResult` (`OptimizationPass`). Passes know nothing about each other;
ordering and fixed-point looping live only in `BytecodeOptimizer`. No pass is a no-op stub any more.

Contents: [Usage](#usage) | [Input and output format](#input-and-output-format) | [Pipeline](#the-pipeline) | [Pass summary](#pass-summary) |
[Passes](#the-passes) | [Configuration reference](#configuration-reference) | [Diagnostics](#diagnostics) |
[Shared code](#shared-code-and-source-layout) | [Adding a pass](#adding-a-pass-and-gotchas) | [Tests](#tests) | [Known gaps](#known-gaps-and-rejected-ideas)

## Usage

    java -cp out caspien.optimizer.Main -i input.txt output.txt

`input.txt` is the ASTGenerator's HOB. The orchestrator (`Compiler.java` at the project root) runs this stage for you:
`java Compiler -i hello.caspien output/hello` runs all four stages, `--hob` stops after this one. A standalone run behaves like this:

- The whole result is printed to stdout, written to `<output>`, and ALSO written to `output.txt` in the current directory (a stray run
  artifact; `Optimizer/output.txt` is not shipped and is git-ignored).
- stderr gets one `[info] optimizer: ...` line with every setting (`Main` prints the `toString()` of the four config objects), the
  `[note]`/`[warning]` lines described under [Diagnostics](#diagnostics), and a final `[info] wrote optimized bytecode ...` line.
- Settings are read from `compiler.config` in the CURRENT DIRECTORY (see [Configuration reference](#configuration-reference)). A missing
  file or key means off; a malformed value is a `RuntimeException` (the run ends with a stack trace, non-zero exit); an I/O error prints
  `[io error]` and exits 1. `SizeofResolutionPass` throws `IllegalStateException` (internal error) for a `SIZEOF` naming a struct with no
  declaration.
- With every switch off the output is NOT necessarily identical to the input: `SizeofResolutionPass` (always on), the always-on
  stripping of `STRUCT_PIN` lines and `RegVarHintPass` (always on, adds `REGVAR` lines) still run.

## Build

    cd Optimizer && javac -d out $(find src/main/java -name '*.java')

Plain JDK, no dependencies. The zip ships `out/` prebuilt (`tests/*_hob_check.sh` use `Optimizer/out`; the stage cache hashes the
classes in it). Configuration in the whole compiler: `toolchain.config` at the project root; the orchestrator copies its
`===compiler.config===` section byte-for-byte into `ASTGenerator/`, `LowerOrderGenerator/` and `Optimizer/compiler.config` (never edit
those copies).

## Input and output format

HOB is one instruction per line, tokens separated by whitespace. `BytecodeParser` splits by hand (no regex): a `"quoted string"` (with
backslash escapes) is ONE token of kind `STRING`, a char literal such as `' '` or `'\n'` is one token, everything else is kind `CODE`.
`BytecodeSerializer` joins tokens back with single spaces. A token remembers the file and line it came from (`BytecodeToken.file/line`);
passes that create tokens copy that position from a neighbour so diagnostics keep working. `Kind.COMMENT` is reserved; HOB has no
comment syntax.

What the optimizer needs to know about the format (sample: any `output.txt`):

- Program level, before and between functions: `STRING id "text"`, `GLOBAL name type [value]` (a struct global also has `name.member` lines),
  `EXTERN name ret args... [VARARGS]`, `ENUM Name V1 V2 ...` (the flat `ENUM Class name id ...` carries the struct class ids),
  `STRUCT_START Name` / `STRUCT_DECORATE @x` / `STRUCT_MEMBER name type` / `STRUCT_PADDING n` / `STRUCT_END`, `STRUCT_PIN Name`,
  `ASM_START`...`ASM_END`.
- A function: `FUNC_START name`, `FUNC_DECORATE @dec ["file:line"]`*, `RETURNS type`, `ARG name type`*, `ALLOC name type`* (all locals are
  hoisted here by the front end), optional `EXPORT`, `ALLOC_STATIC`, then the body, `FUNC_END`. After `RegVarHintPass`: `REGVAR name weight [f]`
  right after the leading `ALLOC` run.
- Types are canonical text: `mut_u64`, `imut_f32`, `indeterminate_u64` (a literal that adapts), storage prefixes `owns_ ref_ raw_ auto_ static_ some_`,
  `atomic_`, arrays `T[n]`, `dynarray(..)`/`unsafe_dynarray(..)`, and `range(lo,hi)` text where the bounds are literals or variable names
  (`imut_range(0,4)`, `imut_range(2,mut_n)`).
- Expression code is a stack machine in postfix: `PUSH literal-or-name type`, `ADDR name type`, binary operators with three type operands
  (`ADD mut_u64 indeterminate_u64 mut_u64`: left, right, result), unary operators with two (`TRUNC from to`), `ASSIGN dstT srcT valT`.
  `CMP` followed by `JMP @L` is "jump to L when the condition is false"; a `JMP` NOT directly after a `CMP` is unconditional. Labels are
  `@name:` definitions and `@name` references; structural labels are `@if_branch_ @end_of_if_ @loop_ @loop_end_ @for_ @for_end_ @match_branch_
  @end_of_match_`; `@catch_N` and `@gt_callsite__f_N` are unwinding entries.
- Calls: `[ADDR x T] CC_START cc / argument code, each argument ending POP ARGn|FARGn T / CALL f / CC_END cc / [PUSH_RET T]`. Throw
  programs add a staging triple `ADDR gt_routine_address / PUSH_LABEL X / ASSIGN` before `CC_START`. Other call forms the passes know:
  `EXTERN_CALL`, `INVOKE` (function pointer), `RECURSIVE_CALL`.
- A `for` loop has a fixed shape (see `LoopUnrollingPass`); `FOR_DECORATE @unroll|@unroll(N)|@dont(unroll) "file:line"` lines may sit between
  the loop setup and the head label.
- Unwinding: `THROW` (a pure marker the backend ignores), `GT_UNWIND [MSG]`, `GT_DESTRUCT name`.

Output is the same format. Passes only ever add/remove/rewrite whole lines; hidden names start with `$` (`$for_range_N`, `$ret_dest`, `$mvN`),
and the ghost-table/throw/trace scaffolding slots are `gt_routine_address`, `gt_error_message` and `gt_trace_*` (see
[Gotchas](#adding-a-pass-and-gotchas)).

## The pipeline

Exactly as `BytecodeOptimizer.optimize` runs it:

    repeat until no pass in the round reports a change:                       (outer loop)
        1. StructMemberReorderingPass       struct-member-reordering   (also strips STRUCT_PIN, always)
        2. SizeofResolutionPass             always on
        3. StructUnpackingPass              struct-unpacking
        repeat until none of the three changes anything:                      (inner loop)
            4. ConstantFoldingPass          constant-folding
            5. VariableElisionPass          variable-elision
            6. VariableShiftingPass         variable-shifting
        7. DeadControlFlowRemovalPass       dead-control-flow-removal
        8. DeadFunctionRemovalPass          dead-function-removal
        9. UnusedDeclarationRemovalPass     unused-declaration-removal
       10. LoopUnrollingPass                loop-unrolling*
       11. FunctionInliningPass             function-inlining, inline-max-*
    after the loop, once each:
        LoopUnrollingPass.reportUnhonoured   [warning] for every @unroll still on a loop
        FunctionInliningPass.reportForced    [note]/[warning] for @inline
        VariableAllocationReorderingPass     variable-allocation-reordering
        RegVarHintPass                       always on

Why this order: layout first (reordering, then `SIZEOF` -> `PUSH n` against the layout as it now stands, so folding sees the literal);
struct unpacking before the scalar passes so the new per-member variables are folded/elided in the same run; unrolling and inlining
last in the round so the next round's folding, elision and dead-code passes see the merged code and can turn new literals into new
opportunities (a loop bound that elision turns into a literal is unrolled on a later round; an inlined body whose condition folds to
`true` is pruned, and the callee it no longer calls is then removed). The reordering of allocations and the register hints come after the
loop because they depend on the final set of locals and the final loop structure.

Termination: a pass must report `changed == true` only when it actually changed the program; every loop ends when a whole round changes
nothing. Persistent state keeps the budgeted passes finite (see the pass sections): `LoopUnrollingPass` keeps a per-function growth
counter and a label counter across rounds, `FunctionInliningPass` keeps the number of rounds done, per-function growth, program growth and
original sizes, `StructMemberReorderingPass` remembers the `STRUCT_PIN` names it stripped. The pass objects live for the whole
`optimize` call.

## Pass summary

All switches are keys of `compiler.config`; every switch is `off` unless set (shipped `toolchain.config` has every optimizer switch
`off`, and targets windows_gnu). "Always" = no switch.

| Pass | File | Switch (values) | Runs | Does |
|---|---|---|---|---|
| StructMemberReordering | `StructMemberReorderingPass` | `struct-member-reordering` on/off | outer loop, 1st | largest-alignment-first member order for plain scalar structs |
| SizeofResolution | `SizeofResolutionPass` | always on | outer loop, 2nd | `SIZEOF Name ..` -> `PUSH n ..` |
| StructUnpacking | `StructUnpackingPass` | `struct-unpacking` on/off | outer loop, 3rd | scalar-only local struct -> one local per member |
| ConstantFolding | `ConstantFoldingPass` | `constant-folding` on/off | inner loop | folds operators with adjacent literal operands |
| VariableElision | `VariableElisionPass` | `variable-elision` on/off | inner loop | once-assigned literal local -> literal at every read |
| VariableShifting | `VariableShiftingPass` | `variable-shifting` on/off | inner loop | splits straight-line reassignments into fresh variables |
| DeadControlFlowRemoval | `DeadControlFlowRemovalPass` | `dead-control-flow-removal` on/off | outer loop | folds `PUSH true/false; CMP; JMP`, removes what becomes unreachable |
| DeadFunctionRemoval | `DeadFunctionRemovalPass` | `dead-function-removal` on/off | outer loop | drops functions unreachable from the roots |
| UnusedDeclarationRemoval | `UnusedDeclarationRemovalPass` | `unused-declaration-removal` on/off | outer loop | drops unreferenced EXTERN/GLOBAL/ALLOC_STATIC/STRING |
| LoopUnrolling | `LoopUnrollingPass` | `loop-unrolling` off/conservative/balanced/aggressive + 4 numbers + `loop-unroll-nested`; also `@unroll`/`@dont(unroll)` | outer loop | full/partial unroll of literal-bound `for` loops |
| FunctionInlining | `FunctionInliningPass` | `function-inlining` off/conservative/balanced/aggressive + `inline-max-*`; also `@inline`/`@dont(inline)` | outer loop, last | replaces `CALL f` by a renamed copy of f's body |
| VariableAllocationReordering | `VariableAllocationReorderingPass` | `variable-allocation-reordering` on/off | once, after the loop | reorders the leading `ALLOC` run to remove frame padding |
| RegVarHint | `RegVarHintPass` | always on | once, last | `REGVAR name weight [f]` hints for hot scalar locals |

## The passes

### StructMemberReorderingPass (`struct-member-reordering`)

Reorders a struct's members, largest alignment first (stable inside a size class; `___type` stays first; the padding gathers at the end),
so interior padding disappears: `{u8, u64, u8, u32}` goes from 32 to 24 bytes. It runs first in the outer loop because it changes
declarations before anything measures them. Member access is by NAME everywhere, so only two things are positional and are rewritten
together: the `STRUCT_MEMBER`/`STRUCT_PADDING` lines of the declaration, and every construction site (the class-id `PUSH n imut_u64`, then one
member-expression unit per member in declaration order, with `STACK_LOCK n` for each padding gap, ended by `ASSIGN`, `NEW` or the next
class-id push for an array literal). Static initialisers (`GLOBAL g.member ...`) name their members and need no change. Member
expressions are evaluated in the new order (they are pure: PUSH, arithmetic/compare/cast lines only), which changes only the order of loads.

A struct is reordered only when ALL hold; otherwise it is left exactly as it was:
- at least two members, all plain scalars (u8..u64, s8..s64, bool, char, f32, f64; no pointer, atomic, nested struct or array), no decorator except `@pub` (so never `@lock`), `___type` is the first member, and it has a class id in the flat `ENUM Class`;
- no other struct has it as a member type in any form (by value, array, or pointer), and no `ALLOC_STATIC` names it and no `GLOBAL`/`ALLOC_STATIC` declares an array of it;
- no `raw` pointer to it appears anywhere, except the hidden destination pointer of a struct-returning call (`ARG/PUSH $ret_dest`, a `RET` of it, the address handed to a callee that declares `$ret_dest`): unsafe code can reach a struct by byte offset and C interop sees the layout;
- the front end did not fold its size into a static (`STRUCT_PIN Name`, emitted for `let static n = sizeof(Name)`);
- EVERY construction site parses, exactly one way (a unit has at most 64 lines; a site that does not parse or parses two ways blocks the struct; a literal that happens to equal a class id can do that too, which only costs the optimisation);
- the new layout (sum of member sizes plus 8 for `___type`, rounded up to 8) is strictly smaller than the old one.

`STRUCT_PIN` lines are ALWAYS stripped, even with the switch off (they must never reach the LowerOrderGenerator); the pass instance
remembers the pinned names across outer-loop rounds because the lines are gone after round one. Once reordered a struct is no longer
strictly improvable, so later rounds leave it alone.
Tests: `tests/structreorder_test.caspien` (every construction form; the last line prints `sizeof(Mixed)`, 32 off / 24 on, otherwise
output identical), `tests/structreorder_hob_check.sh` (hand-made HOB).

### SizeofResolutionPass (always on)

The front end folds `sizeof` for primitives, pointers and ranges, but leaves a struct (and an array of structs) symbolic, and the scale
of `raw Struct` pointer arithmetic (`p++`, `p + n`, `p - q`, `p += n`) likewise: `SIZEOF TypeName <type> [more operands]`. The pass rewrites each
`SIZEOF Name rest...` into `PUSH <n> rest...`, where n is the struct's size AS DECLARED NOW: the sum of its `STRUCT_MEMBER` sizes and
`STRUCT_PADDING` bytes (the front end bakes padding into the declaration; `___type` is the first member). Member sizes follow
the LowerOrderGenerator's `SizeCalculator`/`AddressLoweringPass.Sizes`: a storage keyword (`owns_ ref_ raw_ auto_ static_`) = 8, u8/s8/bool/char/void 1,
u16/s16 2, u32/s32/f32 4, u64/s64/f64/code_addr/string 8, range 16, dynarray 8, fixed array = element size times length, a known struct = its own size
(a mangled generic name is looked up whole), anything else (an enum) 8. Because it runs after reordering and before folding, the new `PUSH`
folds in the same run (`3 * sizeof(S)` becomes one literal) and any reordering/padding change is reflected. Fast exit when no `SIZEOF`
line exists. A `SIZEOF` for a struct with no declaration throws `IllegalStateException` (an internal error, never a silent 8). The
LowerOrderGenerator must never see a `SIZEOF`. The static-initialiser case (`let static x = mut sizeof(S)`) is not an optimizer matter:
statics are data lines folded by the front end's `BytecodeEmitter.foldStaticConstant` from the same layout, which is also why such a
struct carries a `STRUCT_PIN`.
Tests: `tests/sizeof_stride_test.caspien` (sizeof equals the real array stride, `raw P ++` steps sizeof bytes, static initialiser equals
the run-time value), `tests/sizeof_hob_check.sh`.

### StructUnpackingPass (`struct-unpacking`)

Turns a local struct variable into independent per-member variables. A local `ALLOC v T` with `T` = `mut_S`/`imut_S`, declared exactly
once, not `$`-named, where S is declared with at least one member and only plain scalar members (integers, f32/f64, bool; `___type` is
ignored), qualifies when EVERY mention of `v` (or `v.member`) is one of:
- its own `ALLOC`;
- a construction `ADDR v T ; PUSH <digits> imut_u64 (the ___type slot) ; one expression per member in declaration order (STACK_LOCK lines may sit anywhere) ; ASSIGN T T T`; a member expression is postfix and may contain only `PUSH`, the binary operators `ADD SUB MUL DIV MOD SHL SHR ROTL ROTR BITS_OR BITS_AND BITS_XOR AND OR EQ NEQ LT LT_EQ GT GT_EQ` and the unary `NEG NOT BITS_NOT TRUNC SEXT ZEXT FCONV`, and must not mention `v` (sequential assignment would change a swap);
- a member read `PUSH v.m MT` (type equals the declared member type, not followed by `ADDR_OF`);
- a member write `ADDR v T ; PUSH_FIELDNAME m MT ; DOT_LHS T MT MT` (assignment and compound assignment).

Anything else (whole-struct copy, `ADDR_OF`/`auto` pass, nested or `___type` access, an unknown line inside a construction, a name
collision with `v__m`) leaves `v` alone. Functions with `ASM_START` are skipped. Effect: one `ALLOC v__m MT` per member, reads renamed,
writes become `ADDR v__m MT`, and the construction becomes one `ADDR v__m MT ; expr ; ASSIGN MT MT MT` per member; the hidden `___type`
and the padding are dropped. The new variables are ordinary scalars, so elision, shifting and the register hints treat them like any other.
Test: `tests/structunpack_test.caspien`.

### ConstantFoldingPass (`constant-folding`)

One forward scan per run with the output list used as a stack: when an operator line arrives and the last one or two OUTPUT lines are literal
`PUSH lit T` lines, they are replaced by one `PUSH result <the operator's result type>`. Because the result stays on the output stack,
`(10 - 3) * 2` folds completely in one run. It only looks at directly preceding lines, so a label, a jump or any other line stops it, and it
never reads what a variable holds (propagation is elision's job). A literal must really be of its type's kind (integer text within the
type's range, simple decimal float text with no exponent, `true`/`false`); `indeterminate_`, `mut_`, `imut_` prefixes are accepted.

What folds (types are read from the operator line; the result keeps the operator's result type):
- Integers: `ADD SUB MUL DIV MOD` on u64/s64 ONLY (a narrower stack word keeps its high bits until stored, so a narrow fold could differ from the backend), computed in BigInteger and wrapped to 64 bits (signed wraps to two's complement).
- The bitwise group `BITS_AND BITS_OR BITS_XOR BITS_NOT SHL SHR ROTL ROTR` at EVERY width u8..s64 (`foldBits`): operands are reduced to their unsigned width-bit patterns, the operation runs on the patterns, the result is wrapped back (sign-extended for a signed type). Shifts follow the language rule: the count is the unsigned pattern of the right operand; a count >= the width gives 0 for `SHL`/`SHR` and the sign fill for `SHR` on a signed type (HOB has only `SHR`; the arithmetic shift `SAR` is introduced later, by the LowerOrderGenerator); rotates take the count modulo the width. Source literals are 64-bit, so narrow widths are reached through typed limit constants (`u8Max`, `s16Min`, ...) or after inlining.
- Compares `LT LT_EQ GT GT_EQ EQ NEQ` at every integer width (signed/unsigned as the type says) and for f32/f64; `TRUNC` to an unsigned (or non-negative) narrower type; `SEXT`/`ZEXT` to a 64-bit type (the extension kind must match the source signedness).
- Bool `AND OR NOT`.
- f32/f64 `ADD SUB MUL DIV` and `NEG`; f32 is computed in Java `float`, the result is printed as a plain decimal that round-trips (`Float/Double.toString` through `BigDecimal.toPlainString`, `.0` appended when needed). A NaN, infinite or -0.0 result is NOT folded (HOB has no literal for it).
- `IN` with three literal operands, `PUSH x; PUSH lo; PUSH hi; IN u64 range(..) bool`: half-open range test (`lo <= x < hi`, non-negative u64 values only). This is what a `match i in arr` proof becomes once unrolling has put a literal in place of `i`; the folded `PUSH true/false; CMP; JMP` then lets dead-control-flow removal drop the branch.

Never folded: division/modulo by zero, signed MIN / -1, integer `NEG` (the emitter already folds a negated literal), shifts/rotates are folded
at all widths but only via `foldBits`, anything with a non-literal operand, floats whose literal text has an exponent. Language facts it
relies on: a literal `/ 0` or `% 0` is already a compile error; float division by literal zero IS allowed by the language (inf/NaN at run
time), hence the not-folded rule.
Tests: `tests/constfold_test.caspien`, `tests/in_fold_test.caspien` (`IN` boundaries, expected values from a Python model),
`tests/bits_ops_test.caspien` + `bits_ops_matrix{8,16,32,64}_test.caspien` (generated by `tests/bits_ops_gen.py`) and
`tests/bits_ops_check.sh` (compile-time checks); the bits tests are split into five files because a single huge function with everything
inlined used to be slow in the older quadratic dead-flow pass.

### VariableElisionPass (`variable-elision`) and VariableShiftingPass (`variable-shifting`)

Both use `VarAnalysis.scan` (see [Shared code](#shared-code-and-source-layout)). A candidate is a local with exactly one `ALLOC`, a name not
starting with `$`, and a base type u8..u64, s8..s64, f32, f64 or bool (not `char`). Every mention must be its own `ALLOC`, a read
`PUSH x T` not followed by `ADDR_OF`, or the constant assignment `ADDR x T / PUSH literal T2 / ASSIGN` (same base type; a negative
literal in a narrow type is not relied on); anything else (compound assignment, `++`, address taken, struct field, `GT_DESTRUCT`, a parameter, a
non-scalar) marks the variable "bad". Functions containing `ASM_START` are skipped.

**Elision.** Exactly one assignment, every read after it and dominated by it (no forward `JMP` before the assignment may land after it and
at or before the read, so an assignment inside an `if` does not license reads after the branch): remove the assignment (3 lines) and the
`ALLOC`, replace each read by `PUSH <literal> <read's type>`. Names starting with `gt_trace_` are skipped (read only by the backend's
`TRACE_CAPTURE` walk; elision once deleted them). A `for` bound that was an elided constant variable appears by name in the hidden range's type text
(`imut_range(0,imut_n)`): elision rewrites every token spelling that type, within the function, to the literal (`imut_range(0,4)`; only
non-negative decimal integer literals) so `LoopUnrollingPass`, which reads trip counts only from that text, can unroll it. A bound
whose variable is not elided is left as is.

**Shifting.** A variable with two or more constant assignments and no read before the first is split at each LATER reassignment that
`canSplit` (not inside a loop - no backward `JMP` after it lands before it - and every later mention dominated by it): a fresh variable
`x__sN` (unique in the function) is allocated right after the original `ALLOC`, and that assignment and every mention up to the next
split are renamed. Each resulting variable has one assignment, so elision can then take them (shifting alone does nothing useful). The
first assignment is never split. Functions with a `@catch_` label are skipped too (hoisted catch bodies sit textually before the
reassignment, out of execution order). Termination: new variables have one assignment.
Tests: `tests/varelide_test.caspien` covers both (hand-computed expected values; output must be identical with both switches on and
off).

### DeadControlFlowRemovalPass (`dead-control-flow-removal`)

Finds a branch with a literal condition, `PUSH true|false T ; CMP ; JMP L` (where `CMP ; JMP L` means "jump to L when the condition is
false"; typically made literal by elision + folding, or by `IN` folding), and removes the code that can no longer run:
- `true`: the jump never happens, the three lines are deleted and execution falls into the branch body; the else side is dead when nothing else reaches it;
- `false`: the three lines become `JMP L` and the branch body between them and L is dead unless something jumps into it.

Dead code is decided by flow reachability over the function: lines flow to the next unless they are `RET`, `THROW`, `FUNC_END` or an
unconditional `JMP` (a `JMP` right after a bare `CMP` is conditional and flows on); a `JMP` flows to its label. Roots: the function start,
every label that is NOT one of the structural labels (`@if_branch_ @end_of_if_ @loop_ @loop_end_ @for_ @for_end_ @match_branch_
@end_of_match_`), and every label referenced by something other than a `JMP` (`PUSH_LABEL @catch_..`, `@gt_callsite__..`, i.e. catch entries and
unwinding call sites). Only lines that become unreachable BECAUSE of the rewrite are deleted: reachability is computed before and after
and lines already dead before are left alone. Never deleted: `ALLOC ARG REGVAR RETURNS FUNC_DECORATE STRING ALLOC_STATIC FUNC_START FUNC_END`. A label
that a kept line still names is reinstated (code that was already unreachable, e.g. the jump after an inlined early return, may still
refer to a label whose only reachable jumps just vanished). Afterwards an unconditional `JMP L` directly followed by `L:` is dropped. Functions
with `ASM_START` are not touched.

Cost: all foldable branches of a function are applied in ONE round (one reachability analysis before, one after, one rebuild), rounds repeat
until none is left, so it is linear per round (it used to be quadratic in the number of folded branches). With two or more folds a jump
straight to the next label is dropped first. Functions are processed last to first so earlier index ranges stay valid. Known leftovers:
the `GT_UNWIND` call-site entries of a call that sat in a removed branch stay (harmless), and some empty `@end_of_if_N:` labels stay.
Tests: `tests/deadflow_test.caspien`, `deadflow2_test.caspien`, `deadflow_many_test.caspien`, `tests/deadflow_scale_check.sh` (2400
foldable branches must compile in under 25 s).

### DeadFunctionRemovalPass (`dead-function-removal`)

Removes every function that is never called and never has its address taken. Does nothing at all when the program has no `main` (a
library: everything is an entry point) or contains any `INVOKE` (a call through a function pointer could reach any function). Otherwise a
function is live when reachable from a root through `CALL`, `RECURSIVE_CALL` or ANY other line whose CODE token equals its name
(so `PUSH f static_imut_func(..)` and the `par` trampolines count as uses). Strings are never references. Roots:
- `main`;
- the five ghost-table hooks `gt_init`, `gt_register`, `gt_alive_check`, `gt_destruct`, `gt_moved` (the backend calls them by fixed name, no `CALL` in the bytecode);
- any `export`ed function (it carries an `EXPORT` line; called from C);
- any function whose leading `FUNC_DECORATE` lines include something outside the inert set `@pub @throws @recursive @pure @pure(rt) @non(deterministic) @inline @dont(inline)` (the compiler finds `@gt_*`, `@par_call`, `@await_call`, `@sleep`, `@async`, `@lock`, `@unlock`, `@guard`... by decorator);
- every function named on a line outside every function (global initialisers).

Reachability (not "has a call site") means two functions that only call each other, or one that only calls itself, are removed too. Only complete
`FUNC_START..FUNC_END` blocks are deleted. Best right after dead-control-flow removal (which turns "called from a removed branch" into "never
called"); the now-unused externs, globals and strings are the next pass's job.
Tests: `tests/deadfunc_test.caspien`, `tests/deadfunc_hob_check.sh` (hand-made bytecode for what the language cannot easily express: recursion cycles, address-taken functions, `INVOKE`, no `main`, root decorators).

### UnusedDeclarationRemovalPass (`unused-declaration-removal`)

Deletes declarations nothing refers to any more: `EXTERN` lines, `GLOBAL` and `ALLOC_STATIC` declarations (a name and its `name.member` lines,
keyed by the base before the first `.`), and `STRING` literals. A declaration is used when any CODE token (not a quoted string or comment) on any
line that does not itself declare that key equals the name (for globals/statics, has that base). Kept unconditionally: the externs the
backend calls by fixed name (`malloc realloc free strlen exit pthread_exit sched_yield`), the global `ghost_table` (Codegen reads and writes its
lock state), and any `ALLOC_STATIC` whose base name is declared other than exactly once (function-local statics of the same name in
different functions, e.g. `m` in lock/match code, are not told apart). The whole pass does nothing when there is no `main` (a library
exports its declarations) or when any `ASM_START` exists (assembly text may name symbols). Only declaration lines are deleted.
Tests: `tests/unusedecl_test.caspien`, `tests/unusedecl_hob_check.sh`.

### LoopUnrollingPass (`loop-unrolling`, `loop-unroll-*`, `@unroll`, `@dont(unroll)`)

Unrolls counted `for` loops whose trip count can be read straight off the bytecode, and nothing else. A loop qualifies only when its hidden
range's type text carries two decimal literals (`ALLOC $for_range_3 imut_range(0,4)`; `lo <= hi`; `hi == lo` is an empty range that a zero-copy full unroll
removes). `imut_range(2,mut_n)` and every `loop{}` are never touched: the pass does no constant propagation or induction analysis (elision +
folding turn a bound into a literal, and a later round picks it up). The exact recognised shape (anything else is left alone):

    ADDR $for_range_N T ; PUSH lo ; PUSH hi ; ASSIGN T T T
    ADDR i vt ; PUSH $for_range_N.start indeterminate_u64 ; ASSIGN vt vt vt
    [FOR_DECORATE ...]*
    @for_A: ; PUSH i vt ; PUSH $for_range_N T ; IN vt T indeterminate_bool ; CMP ; JMP @for_end_B
    ...body...
    ADDR i vt ; PUSH i vt ; INC vt indeterminate_u64 ; ASSIGN ... ; JMP @for_A
    @for_end_B:

**Plans.** The preset decides (`UnrollConfig`): full unroll if trips N <= `full-max-trips` and `(N-1)*unit` fits the remaining per-function
growth budget (unit = body lines + 4); otherwise partial unroll by `factor` U if `U >= 2`, `N >= 2U` and the extra lines fit the budget; body longer than
`max-body-lines` is never copied. The growth counter is per function and persists across rounds. Presets (factor / full trips / body lines / growth):
conservative 2/4/40/400, balanced 4/16/100/2000, aggressive 8/4096/2000/200000. The four numbers can be overridden individually and do nothing
while the preset is off.
- **Full**: test and back-jump removed; N body copies. If the body only READS the induction variable (every mention is a plain `PUSH var vt` not followed by `ADDR_OF`), copy c gets the literal `lo+c` in its place and the increments between copies go (so `i * 8`, `bits >> i` fold). Otherwise the variable stays real and the 4-line increment is kept between copies. A body holding another (nested) loop is substituted only if the loop is `@unroll`ed or `loop-unroll-nested: on` (shipped off, benchmark FULL on; the AoS n-body `for i` over `for j in i+1..5` gained -37%): otherwise substituting would make the inner bounds literal and multiply the code. With substitution in a nested body, the locals the body declares (ALLOC'd once in this function, mentioned only inside the body span: `let j0 = mut (i + 1)`, the hidden `$for_range_N`, the inner `j`) get their own slots in copies 1..n-1 (`name__uK`, ALLOC queued in `pendingAllocs`, range-type bounds renamed with them), so elision/shifting see one assignment per name and the inner loops of every copy unroll. A name ALLOC'd several times in one function (the same `let j0` in several scopes) is NOT renamed: write distinct names (`benchmarks/programs/nbody/caspien/gen_aos.py` does). A range proof on the substituted literal is folded away by folding (`IN`).
- **Partial** (factor U): the hidden range's high bound (the `PUSH hi` literal and every occurrence of the range type text, including the hoisted `ALLOC`) becomes `lo + (N/U)*U`; U body copies each followed by the increment; the header label is renamed `@fu_A` so it is never matched again; the test's exit jumps to a new `@for_rem_K` in front of the `N mod U` remainder copies; `@for_end_B` stays last so `break` still leaves everything.
- Labels defined in a copied body get fresh names (prefix and numeric suffix kept; numbering continues above the program-wide maximum), so a copied inner `for` is still recognisable. Innermost candidates first; an outer loop is considered on a later round with its larger body.
- Skipped: a body containing `ALLOC ALLOC_STATIC FUNC_START FUNC_END STRUCT_START GLOBAL REGVAR`; a body mentioning its own range variable or header label; a body defining a label referenced from outside it (a try block, for instance). The reserved slots `gt_routine_address`, `gt_error_message` and `gt_trace_*` are never renamed as per-copy locals.

**Per-loop decorators.** `@unroll` / `@unroll(N)` / `@dont(unroll)` on the `for` statement arrive as `FOR_DECORATE @x "file:line"` lines between the loop
setup and `@for_N:` (`match` skips them; `Loop.forced/factor/dont/pos`). They work even with `loop-unrolling: off`. `plan()` is the pure decision: a forced
loop ignores the preset's trip/body/growth limits and has only `HARD_CAP` = 20000 added lines; `@unroll` or `@unroll(N)` with N >= trips = full, otherwise
partial by N; `@dont(unroll)` always wins. An `@unroll` loop is not held back by an inner loop that will stay a loop. `apply()` prints a
`[note]` for a forced unroll and for a `@dont(unroll)` that overrode the heuristic; `reportUnhonoured()` (once after the fixpoint, because a
variable bound can become a literal later) prints `[warning] file:line - @unroll not honoured: <reason>`. `Compiler.runProcess` collects `[note]`
lines and the stage cache replays them.
Tests: `tests/unroll_test.caspien`, `tests/unroll_decorator_test.caspien` + `tests/unroll_decorator_check.sh` (messages, off/aggressive, cache replay, bad factors),
`tests/nested_unroll_test.caspien` + `tests/nested_unroll_check.sh` (generated by `tests/gen_nested_unroll.py`), `tests/stride_index_test.caspien`,
`tests/static_aggregate_test.caspien` (AoS address work that rides on nested unrolling).

### FunctionInliningPass (`function-inlining`, `inline-max-*`, `@inline`, `@dont(inline)`)

Replaces a direct call by a copy of the callee's body. It runs last in the outer loop, so folding/elision/shifting see the merged code on the next round.

**Call site and callee shape.** A call site is `[ADDR x T] CC_START cc / argument code, each argument ending POP ARGn|FARGn T / CALL f / CC_END cc / [PUSH_RET T]` (plus, in throw
programs, the staging triple before `CC_START`; it is dropped on commit and its label is kept as the unwind target). A callee is
`FUNC_START, FUNC_DECORATE.., RETURNS, ARG.., ALLOC.., body, RET, FUNC_END`. Words are counted per parameter: a scalar or any pointer is one, a bare
`range` (`imut_range`/`mut_range` exactly) is two (the site passes start and end; they are built into the copy's range variable like a `lo..hi` literal;
range type text that embeds variable names, `imut_range(mut_lo,mut_hi)`, is renamed with them).

**What the copy looks like.**
- Each argument is assigned to a fresh local standing for the parameter: `ADDR p__iK T / <argument lines> / ASSIGN T T T`, evaluated left to right as before.
- The callee's parameters and locals are renamed `name__i<inst>` (a hidden `$for_range_N` gets a fresh number), labels keep their numeric-suffix shape (`@lbl_12` -> `@lbl_<fresh>`, so `@catch_` / `@gt_callsite__` prefixes and a copied `for` survive), and every callee `ALLOC` is hoisted into the caller's prologue (after its leading `FUNC_DECORATE/RETURNS/ARG/ALLOC/ALLOC_STATIC` run). A parameter redeclared as a local of the same type (the loop-converted recursive form emits this) is one variable; a different type is a refusal.
- Result modes: `STACK` (single tail `RET`, no control flow: the value is simply left on the stack, the call's `PUSH_RET` is dropped), `TEMP` (a result local `__ret_i<inst>`; each `RET` becomes an assign plus `JMP @inl_end_<n>`; the result is pushed at the end; found by walking the return expression back with each line's operand-stack effect), `DISCARD` (result unused: the pure return expression is dropped).
- Call sites in the middle of an expression or condition are inlined too (the walk-back understands labels, jumps, `ASSIGN`, `CMP`, `POP`, `INC/DEC`). What lies beneath the site on the operand stack is classified: 0 = nothing (statement start), 1 = exactly one address (`let x = f(..)`), 2 = other operands of an enclosing expression. With class 2 only a balanced, straight-line, plain-scalar callee (`Callee.simple`) is inlined; class 1 with a branching callee uses the TEMP form and pushes the address again after the copy.
- Owns moves: a call that moves an owns variable in has `ADDR o / PUSH null / ASSIGN` null-out triples after the last argument `POP` inside its bracket; those triples (and only those, `moveNullOuts`) are copied right after the parameter copies, any other line there refuses the site.

**Throw / catch programs.** Every function carries `gt_routine_address` (rbp-8) and `gt_error_message` (rbp-16) slots, stages a per-call-site label into its own slot before each call
and unwinds with `GT_UNWIND [MSG]` (tears its frame down, copies the message slot up, jumps through the CALLER's slot). Both `ALLOC`s are scaffolding
(`Callee.scaffoldSlots`) and are not copied: the inlined lines name the caller's slots, which is exactly where the callee's frame would have delivered
things (the caller must have the slot, else the callee is refused, `usesAddr/usesMsg`). `THROW` (a pure marker) is dropped, each `GT_UNWIND [MSG]` becomes `JMP X`
with X the label the caller staged for this call (`stagedLabel`; the caller's landing pad does the rest, or its `catch` reads `gt_error_message`), and the callee's
own staging lines, landing pads (`@gt_callsite__f_N: [GT_DESTRUCT..] GT_UNWIND MSG`) and hoisted catch blocks are copied with renamed labels. `PUSH_LABEL` is accepted only for
`@gt_callsite__` / `@catch_` labels defined in the callee. A jump from inlined code into a `@catch_` label needs no special care for float variables:
`RegVarPromotionPass` puts an `R_XSPILL` before every jump to a catch label.

**Never inlined (callee):** `main`; a function on a call cycle (a self-recursive function is already a loop when it reaches this pass:
`TypeChecker.lowerRecursiveFunctionToLoop` in the ASTGenerator); a function with a decorator outside `@pub @pure @pure(rt) @non(deterministic) @recursive @inline @throws @lock`
(so never `@async`, `@sleep`, `@gt_*`, `@par_call`, `@await_call`), or `@dont(inline)`; a body containing `EXIT EXIT_THREAD ASM_START ASM_END RECURSIVE_CALL ALLOC_STATIC
FUNC_START FUNC_END STRUCT_* GLOBAL REGVAR STRING EXTERN ENUM`; by-value struct/array/dynarray parameters (only scalar, pointer and bare range parameters are copied); `INVOKE` sites
(not a `CALL`) and externs (no body); a callee with no `RETURNS`; a body naming a label not defined inside it; a local name used in a position the renamer cannot classify; a callee that unwinds
at a site with no staged label (every old-form pad in a non-throw owns program); a callee that mentions a global name that the caller also declares (capture check);
a callee that defines its own `@catch_` at a class-2 site (a real unwind enters it with whatever the statement had pushed, and its catch falls out through the end label with those words
still on the stack); a callee that is not stack-balanced (`Callee.balanced`: the operand-stack depth simulation is 0 at every `RET`, statement boundary and the end, every mnemonic has a known effect)
at any site that has anything beneath it except the TEMP form with one address. A site is NOT inlined when an earlier argument of an enclosing call was already popped into an argument register (`argRegisterLoaded`/`regsLoaded`
in `expandRange`): inlined code has no register protection and the backend's block copies (rdi/rsi/rcx), divides (rdx) and shifts (rcx) clobber argument registers; if the outer call is itself inlined its
`POP`s vanish and the inner site is inlined next round. A value site whose callee never returns a value (`selfThrower`, no `RET`) stays a call.
Under stack traces (`TraceConfig.usesTrace`, whole-program) the front end writes `FUNC_DECORATE @dont(inline)` on every function (an inlined function would leave no frame and no id on the stack).

**Limits** (`InlineConfig`). Presets (callee lines / rounds ("depth") / lines added per function): conservative 12 / 1 / 200, balanced 40 / 3 / 2000, aggressive unlimited / 32 / 1e8.
`aggressive` also sets relative budgets: per caller at most `inline-max-growth-factor` (30) times its original size (never below `GROWTH_FLOOR` = 2000 lines) and the whole program at most
`inline-max-total-factor` (10) times its original size added (never below `TOTAL_FLOOR` = 50000 lines); 0 removes a limit; the two factor keys also apply to other presets if you set them. A call over a budget simply stays a call, never an
error. `inline-max-multi-callee-lines` (default 1000 whenever a preset is on, 0 = rule off): a callee longer than that is inlined only when the program has exactly one `CALL` to it (`Callee.calls`, counted per round); inlining a ~1100-line hash function at three sites into `main` made the merkle benchmark 34% slower. Depth counts
inlining ROUNDS over the whole run: `roundsDone` persists, so the budget is `max-depth` rounds in total (round 1 inlines the calls written in each function, round 2 also the calls that arrived with round 1's bodies, ...); within one `run()` call the rounds
repeat until one changes nothing or the budget is spent, and later outer-loop iterations retry only if rounds remain. Per-function growth (`grown`) also persists. The growth estimate of one site is
`callee body lines + allocs + words + 2*params + 4`.

**Decorators.** `@inline` makes the callee `forced`: inlined even with `function-inlining: off`, past `inline-max-callee-lines`, the per-function and program growth caps and the multi-site rule, in up to `FORCED_ROUNDS` = 8 rounds (a chain of marked functions needs one round per level); every
safety rule above still applies. `@dont(inline)` never. Both arrive as `FUNC_DECORATE @x "file:line"` (an optional STRING position operand) and are inert for dead-function removal. `reportForced()` (once after the fixpoint) prints
`[note] file:line - @inline: f inlined at N call sites` or `[warning] ... @inline not honoured at N call sites of f (K inlined): <reason>`. The stdlib marks `DynamicArray.get/set/setPtr` and `String.charAt/setCharAt` `@inline`.

Debug: set `CASPIEN_INLINE_WHY=1` and the pass prints `[inline] <function> refused: <reason>` to stderr for every refused callee, plus the program-growth and multi-site refusals; call-site refusals (nested argument registers, other lines after the
last argument `POP`) are not shown.
Tests: `tests/inline_test.caspien`, `inline_argreg_test.caspien` (calls in argument lists), `inline_throwprog_test.caspien`, `inline_throw_test.caspien`, `inline_owns_test.caspien`, `inline_owns_drop_test.caspien` + `inline_owns_drop_check.sh`,
`inline_owns_deref_copy_error_test.caspien`, `inline_owns_use_after_move_error_test.caspien`, `inline_budget_test.caspien` + `inline_budget_check.sh` (a 3-way fan-out chain that grew 3x per level before the budgets),
`inline_multi_callee_test.caspien` (+ `.py`) + `inline_multi_callee_check.sh`, `inline_decorator_test.caspien` + `inline_decorator_check.sh`, and hand-made bytecode in `tests/inline_hob_check.sh` (owns null-outs, scaffolding, unwinding,
decorators, externs/INVOKE, cycles, the three limits). Standard check: output identical with every switch off vs on at every preset.

### VariableAllocationReorderingPass (`variable-allocation-reordering`)

The LowerOrderGenerator's `AddressLoweringPass` assigns frame offsets purely from the order of a function's `ALLOC` lines (each slot goes just below the previous one, rounded down to its own alignment, wasting the gap above it), so `u8, u64, u8, u64` loses 7 bytes twice. This pass
reorders the function's LEADING contiguous `ALLOC` run (an `ALLOC` that appears later, such as an async `par` call's inline temporary, keeps its place):
1. alignment, largest first (8, 4, 2, 1); with every size a multiple of its alignment the only padding left is the final round-up of the frame to 16;
2. inside one class the larger use weight first (`8^loopDepth` per mention, depth capped at 4, the same weight `RegVarHintPass` uses), so hot variables sit nearest rbp where a one-byte displacement fits;
3. original order (stable), so equal keys never move.

Pinned at the front: `gt_routine_address` (rbp-8), then `gt_error_message` (rbp-16), then any `gt_trace_*` slots (rbp-24/-32): `GT_UNWIND`, its `MSG` copy and the trace walk address them by offset. A function whose run does not start with that pinned
prefix, that has such a name anywhere else, or that has `ASM_START`, is left alone. Parameters are `ARG` lines at this point; the LowerOrderGenerator's `ArgToAllocLoweringPass` inserts their slots right behind the pinned ones, so they stay ahead of every reordered local. Correctness needs no analysis (locals are only mentioned by name, offsets are computed afterwards, the pass never adds, removes or renames a line); a wrong alignment estimate could only cost padding.
The alignment table (`Align`) MIRRORS `AddressLoweringPass.Sizes.alignOf` and must be kept in step: storage pointers 8, u8/s8/bool/char 1, u16/s16 2, u32/s32/f32 4, u64/s64/f64/code_addr/string/range/dynarray 8, a fixed array its element's, a struct the max over its members, anything unknown 8.
Tests: `tests/allocreorder_test.caspien` (identical output with the switch off and on; the frame of `mixed` is smaller), `tests/allocreorder_hob_check.sh`.

### RegVarHintPass (always on, runs once, last)

Emits `REGVAR name weight [f]` right after a function's leading `ALLOC` run for every local worth keeping in a register. It names NO register; "this one is hot" is all it says. The LowerOrderGenerator (`RegVarPromotionPass`) decides whether and where (needs
`variables-in-registers: on`); otherwise the line is stripped there, and any stage that does not understand `REGVAR` ignores it. The `f` token marks a float local (f32 or f64) so a lowering with a separate float register file can use it.
Eligible: an `ALLOC` of a plain scalar (`mut|imut` + u8..u64, s8..s64, f32, f64, bool, char); every declaration of the name has the SAME type (the `i` of several `for` loops is one slot for the lower stages; a name declared with different types is `mixedType` and excluded); not
`$`-prefixed, not `gt_routine_address`/`gt_error_message`/`gt_trace_*`; mentioned only as the operand of `ALLOC`, `PUSH` and `ADDR` (any other mention, e.g. `GT_DESTRUCT`, disqualifies); never address-taken (a `PUSH`/`ADDR` directly followed by `ADDR_OF`).
Weight = sum over its `PUSH`/`ADDR` mentions of `8^min(loopDepth, 4)`, where a loop is a backward `JMP` to an already-seen label; hinted only when the weight is at least 8 (so used inside a loop). The pass is idempotent (a function that already
has a `REGVAR` is skipped). The header scan also skips `FUNC_DECORATE`. The pass proves nothing about the lowered code; the LowerOrderGenerator re-checks every mention before it promotes anything.
Tests: `tests/regvars_test.caspien`, `regvars3_test.caspien` (same-typed redeclared loop counters), `regvars_blocked_test.caspien`, `regvars_float_test.caspien`, `regvars_share_*`, `regvars_volatile_*` (these exercise the LowerOrderGenerator's use of the hints).

## Configuration reference

All keys are TOP-LEVEL lines of `compiler.config` (`key: value`; the calling-convention block below is indented and ignored here). The file is optional. `#` starts a comment anywhere in a line. A key that is absent is off.
Every other stage's parser skips these keys and this stage ignores all other keys (unknown keys are never errors here). The four config classes each re-read the file: `UnrollConfig`, `FoldConfig`, `VariableConfig`, `InlineConfig`.
Numbers are decimal digits only, at most 12 characters; `UnrollConfig`/`InlineConfig` accept the value in double quotes, the on/off parsers do not.
In the repository the file is generated from the `===compiler.config===` section of `toolchain.config`, and the stage cache's Optimizer view (`CompilerCache.OPT_KEYS`) is exactly the keys below; the shipped value of EVERY switch is off.

| Key | Values | Default | Read by |
|---|---|---|---|
| `constant-folding` | `on`/`off` | off | `FoldConfig` |
| `variable-elision` | `on`/`off` | off | `VariableConfig` |
| `variable-shifting` | `on`/`off` | off | `VariableConfig` |
| `struct-unpacking` | `on`/`off` | off | `VariableConfig` |
| `dead-control-flow-removal` | `on`/`off` | off | `VariableConfig` |
| `dead-function-removal` | `on`/`off` | off | `VariableConfig` |
| `unused-declaration-removal` | `on`/`off` | off | `VariableConfig` |
| `variable-allocation-reordering` | `on`/`off` | off | `VariableConfig` |
| `struct-member-reordering` | `on`/`off` | off | `VariableConfig` |
| `loop-unrolling` | `off`/`conservative`/`balanced`/`aggressive` | off | `UnrollConfig` |
| `loop-unroll-factor` | whole number (0 or 1 = never partial) | preset (2/4/8) | `UnrollConfig` |
| `loop-unroll-full-max-trips` | whole number (0 = never full) | preset (4/16/4096) | `UnrollConfig` |
| `loop-unroll-max-body-lines` | whole number | preset (40/100/2000) | `UnrollConfig` |
| `loop-unroll-max-growth` | whole number (added lines per function over the run) | preset (400/2000/200000) | `UnrollConfig` |
| `loop-unroll-nested` | `on`/`off` | off | `UnrollConfig` |
| `function-inlining` | `off`/`conservative`/`balanced`/`aggressive` | off | `InlineConfig` |
| `inline-max-callee-lines` | whole number | preset (12/40/unlimited) | `InlineConfig` |
| `inline-max-depth` | whole number (inlining rounds over the run) | preset (1/3/32) | `InlineConfig` |
| `inline-max-growth` | whole number (added lines per function) | preset (200/2000/1e8) | `InlineConfig` |
| `inline-max-growth-factor` | whole number (x original function size, floor 2000 lines; 0 = none) | 30 for aggressive, else 0 | `InlineConfig` |
| `inline-max-total-factor` | whole number (x original program size, floor 50000 lines; 0 = none) | 10 for aggressive, else 0 | `InlineConfig` |
| `inline-max-multi-callee-lines` | whole number (0 = rule off) | 1000 when a preset is on | `InlineConfig` |

Rules: a bad value on/off or preset name, or a non-numeric number, stops the run with `path:line: ...`. The unroll numbers, `loop-unroll-nested` and all `inline-max-*` keys do nothing while their preset is `off` (`@unroll`, `@dont(unroll)`, `@inline` and `@dont(inline)`
work regardless of the preset, with their own limits: `HARD_CAP` 20000 lines per forced unroll; forced inlining ignores the size/growth caps and has up to 8 rounds).
`SizeofResolutionPass`, the `STRUCT_PIN` stripping and `RegVarHintPass` have no switch. The benchmark/test sweep presets live in `tests/run_tests.py` (`shipped`, `allon` = FULL with inlining balanced, `aggressive` = FULL, `alloff`) and `benchmarks/harness/caspien.py` (`FULL`, `OFF`); neither
dictionary contains the two reordering switches, which are covered by their own tests.

## Diagnostics

stderr lines the orchestrator collects (`Compiler.runProcess` keeps `[note]` and warning lines; the stage cache stores them and replays them on a hit; `[info]` lines are not replayed):

| Line | From | Meaning |
|---|---|---|
| `[info] optimizer: <settings>` | `Main` | the effective settings of all four config objects |
| `[note] file:line - @unroll: unrolled fully (N iterations, +K lines)` / `unrolled by U` | `LoopUnrollingPass.apply` | a forced unroll happened |
| `[note] file:line - @dont(unroll): this loop is kept as a loop (the heuristic would have unrolled it)` | same | the decorator overrode the preset |
| `[warning] file:line - @unroll not honoured: <reason>` | `reportUnhonoured` | bounds not compile-time constants, never runs, shape changed, also `@dont(unroll)`, label used from outside the body, over 20000 lines, inner loop in the way |
| `[note] file:line - @inline: f inlined at N call sites` | `reportForced` | `@inline` took effect everywhere |
| `[warning] file:line - @inline not honoured at N call sites of f (K inlined): <reason>` | `reportForced` | some calls stayed calls |
| `[inline] f refused: <reason>` (env `CASPIEN_INLINE_WHY`) | `FunctionInliningPass` | debug aid, callee-level reasons |

## Shared code and source layout

`src/main/java/caspien/optimizer/`, one file per pass plus:

- `BytecodeOptimizer`: the pipeline (above) and the constructor chain `BytecodeOptimizer(UnrollConfig, FoldConfig, VariableConfig, InlineConfig)`; the no-argument and partial constructors build an all-off optimizer (used by tests that call it directly).
- `Main`: CLI (`-i input output`), reads the four configs from `compiler.config`, parse -> optimize -> serialize.
- `OptimizationPass` (`name()`, `PassResult run(List<List<BytecodeToken>>)`), `PassResult` (`lines`, `changed`; `changed` drives every fixed-point loop and must be false when nothing was done).
- `BytecodeToken` (`text`, `file`, `line`, `kind` = CODE/STRING/COMMENT), `BytecodeParser` (hand-written scanner: quoted strings and char literals stay one token), `BytecodeSerializer` (single-space join).
- `VarAnalysis` (package-private; shared by elision, shifting and several others): `functions()` (FUNC_START..FUNC_END index pairs), `mnemonic()`, `hasMnemonic()`, `labels()` (label definition line = one token `@name:`),
  `jumps()` (`JMP @label` as {line, target}; targets outside the function ignored), `dominated(a, m, jumps)` (false when a forward jump before line a lands after a and at or before m), `inLoop(a, jumps)` (true when a backward jump after a lands before it),
  `scan()` (per-function candidate variables and their reads/assignments, described under elision) and `withToken()`. `ConstantFoldingPass` exports `base/isInt/width/lit` helpers that elision, shifting and unpacking use for type tests.
- `*Config` classes: see [Configuration reference](#configuration-reference); `UnrollConfig.toString()` and friends feed the `[info]` line.

## Adding a pass and gotchas

Add `FooPass implements OptimizationPass`, a switch in the matching `*Config` class (or a new one, parsed the same way: top-level keys only, `#` comments, strict values), construct it in the `BytecodeOptimizer` constructor and call it at the right place in `optimize`.
Also: add the key to `CompilerCache.OPT_KEYS` (so the stage-2 cache view includes it), to `toolchain.config` (documented, shipped off) and `docs/COMPILER_REFERENCE.md`; add a `tests/*.caspien` runtime test (print PASS/FAIL lines) plus a `*_hob_check.sh` if the language cannot express the shapes, and sweep all tests under several configs.

Things that have bitten before:
- A mnemonic missing from a pass's operator lists makes the function/struct "unknown" and refused: new operators must be added to `ConstantFoldingPass` (the `tryFold` switch), `FunctionInliningPass` (`BINARY`/`UNARY`/`SIMPLE`/`PUSH1` and the stack-effect code), `StructUnpackingPass` and `StructMemberReorderingPass` (`BINARY`/`UNARY`). `ROTL`/`ROTR` and the `BITS_*` family went through exactly this.
- Stack form: a `JMP` right after a bare `CMP` is conditional; every flow analysis here (dead flow, `VarAnalysis.jumps`/`dominated`) treats jumps by line position and labels, not by a CFG.
- Passes must be pure functions of the whole program; any state kept across rounds (counters, budgets) lives in the pass object and must make the loop terminate.
- Reserved scaffolding slots: `gt_routine_address`, `gt_error_message` (rbp-8/-16) and, under stack traces, `gt_trace_id` / `gt_trace_ptr` / `gt_trace_buf` (rbp-24/-32/...; written and read only by the backend's `TRACE_CAPTURE` walk, so they look like unused locals). Guards today: `VariableElisionPass` (skips `gt_trace_*`), `VariableAllocationReorderingPass` (pins them),
  `LoopUnrollingPass` (never renames them as per-copy locals), `RegVarHintPass` (never hints them); the inliner never sees them because under `usesTrace` every function is `@dont(inline)`. A new pass that deletes unused locals or moves slots must leave them alone (elision once deleted them).
- `AddressLoweringPass.Sizes.alignOf` (LowerOrderGenerator) is mirrored by `VariableAllocationReorderingPass.Align` and, for sizes, by `SizeofResolutionPass.Sizes`: keep all three in step.
- Never compile two programs at once in one compiler tree: the orchestrator's scratch files and the stage cache are shared.

## Tests

Runtime programs live in the repo-root `tests/` (run with `python3 tests/run_tests.py [--configs shipped,allon,aggressive,alloff] [--checks]`, which sweeps every `tests/*.caspien` and `docs/examples` program through the stage cache under several configs); hand-made-bytecode scripts
(`*_hob_check.sh`) run the prebuilt `Optimizer/out` on small HOB files with one switch set and count or compare lines:
`inline_hob_check.sh`, `deadfunc_hob_check.sh`, `unusedecl_hob_check.sh`, `sizeof_hob_check.sh`, `structreorder_hob_check.sh`, `allocreorder_hob_check.sh`. Others touching this stage: `bits_ops_check.sh`, `unroll_decorator_check.sh`, `nested_unroll_check.sh`,
`inline_decorator_check.sh`, `inline_budget_check.sh`, `inline_multi_callee_check.sh`, `inline_owns_drop_check.sh`, `deadflow_scale_check.sh`, `trace_off_check.sh` (trace mode off gives byte-identical output), `cache_check.sh` / `cache_views_check.py` (stage-2 cache keys and the claim that
each stage ignores the keys outside its view). Per-pass test names are listed in the pass sections above. The standard check for any change: output of every test program identical with every switch off vs on, at every inlining/unrolling preset, old tree vs new tree.

## Known gaps and rejected ideas

- No common-subexpression elimination, no loop-invariant hoisting (the LowerOrderGenerator's `LoopHoistPass` hoists array base pointers only), no `sqrt` intrinsic in this stage.
- Constant folding is purely local (adjacent literal operands); propagation through variables exists only as elision of once-assigned literals and shifting of straight-line reassignments (not in loops or branches). Narrow-width integer `ADD/SUB/MUL/DIV/MOD` is not folded (only the bitwise group, compares and casts are).
- Loop unrolling needs literal bounds in the range type text; a variable bound is unrolled only after elision + folding make it literal. A repeated same-named `let` in one function shares one slot and defeats per-copy renaming. Unrolling alone did not speed up the looping n-body programs (hot loop bound is a variable); nested unrolling plus AoS work did (-37% on the AoS n-body).
- Inlining: the shapes listed under "Never inlined" stay calls; `@inline` is not a guarantee. Inlining a large callee at several sites can lose (merkle, 34%), hence the multi-site rule. Function-pointer calls (`INVOKE`) are never touched, and one `INVOKE` anywhere disables dead-function removal.
- Dead-control-flow removal only folds `PUSH true|false; CMP; JMP`; it does not do general unreachable-code analysis, and a few call-site entries/labels can remain.
- Struct unpacking and member reordering only handle structs of plain scalars; reordering is blocked by any `raw` pointer, nesting, `@lock`/decorators, a `sizeof` static pin, or an unparsable construction site.
- External provers/solvers are not part of this stage (owner decision: never part of ordinary compilation).
- Platform: Linux is the main executed target; `windows_gnu` has been run under Wine; there is no MASM target. The optimizer itself is target-independent text rewriting, but nothing in it has been verified on real Windows.
- Recorded in the archived history, not re-verified against the code: a `catch` that ends in `continue` leaks one operand word per throw (same before and after inlining; accepted, not fixed). Archived per-change history: `docs/history/Optimizer_CLAUDE_HISTORY.md`; the short current-state notes: `Optimizer/CLAUDE.md`; project memory: root `CLAUDE.md`.

## Related documents

Root `README.md` (orchestrator, `toolchain.config`), root `CLAUDE.md` (project memory), `docs/COMPILER_REFERENCE.md` (per-switch reference, stage cache), `stack_trace.md` (trace mode and inlining), `LowerOrderGenerator/` (register form, register variables and everything else that does the real code-quality work).
