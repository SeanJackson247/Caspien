# LowerOrderGenerator

Stage 3 of the Caspien compiler. It reads the Optimizer's **higher-order bytecode (HOB)** and writes **low-order bytecode (LOB)**
for `Codegen`: type names become byte sizes, variable names become frame offsets (`$off`) or global symbols (`&sym`), the
ownership constructs (`clone`, drop of `owns` values, membership operators) are expanded into ordinary instructions, and, with the
register switches on, the stack-machine code is rewritten into a three-address "register form" (`R_*` lines) whose temporaries and
hot variables live in machine registers.

Package `caspien.lowerorder`, plain JDK, no dependencies, no code shared with the other stages (the parser/token/pass-interface
classes are hand-kept copies and must stay identical across stages). This file is the place where the component's knowledge lives;
`CLAUDE.md` next to it is a short current-state note. Deep detail on the two biggest passes is in
[docs/AddressLoweringPass.md](docs/AddressLoweringPass.md) and [docs/MembershipLoweringPass.md](docs/MembershipLoweringPass.md); the
reasons behind their current shape (bugs found, rejected designs) are in [docs/history.md](docs/history.md). The archived per-change
history of the old `CLAUDE.md` is `docs/history/LowerOrderGenerator_CLAUDE_HISTORY.md` at the repository root.

Contents: 1 Place in the pipeline - 2 Usage and build - 3 Formats - 4 The pass pipeline - 5 Configuration - 6 Passes -
7 Shared utilities and adding a pass - 8 Tests - 9 Known gaps, rejected ideas, stale comments.

---

## 1. Place in the pipeline

```
ASTGenerator  ->  Optimizer  ->  LowerOrderGenerator  ->  Codegen  ->  as / gcc
 source -> HOB    HOB -> HOB      HOB -> LOB (this)        LOB -> x86-64 asm
```

`Compiler.java` (repository root) runs the four stages as separate processes through a content-addressed stage cache
(`CompilerCache`, `.cache/`). Stage 3's cache key is: this stage's classes, `compiler.config` with the Optimizer-only keys removed,
and the hash of stage 2's output. `java Compiler --lob` stops after this stage and writes the LOB.

What this stage never sees: `SIZEOF` (the Optimizer's `SizeofResolutionPass` rewrites every one to `PUSH n` first, from the same
`STRUCT_MEMBER`/`STRUCT_PADDING` lines this stage lays out; an unresolved one would be an Optimizer bug), and nothing here handles
the mnemonic.

---

## 2. Usage and build

```
lowerordergenerator -i input.txt output.txt        # java -cp out caspien.lowerorder.Main -i in.txt out.txt
```

* `input.txt` is the Optimizer's plain-text output. The result is printed on stdout, written to `output.txt` (the path given) **and**
  to a file named `output.txt` in the current directory (a leftover copy sits in this folder); a one-line `[info]` summary goes to stderr.
* **`compiler.config` must exist in the current working directory** (fixed path, hard-required, no fallback; a copy sits in this
  folder, `Compiler.java` overwrites it on every run from the `===compiler.config===` section of `toolchain.config`). It carries the
  calling conventions and the register switches (section 5).
* The stage is also a library, `LowerOrder.generate(hobText, origin, compilerConfigText)` -> `Result{lob, messages}` (text in, text out; no files); the orchestrator calls it in its own JVM and `Main` is the same call wrapped as a program (it reads `./compiler.config`).
* Build: `../build.sh` (the Caspien exe `out/lowerorder`; the Java build instructions no longer apply to this folder).
* Never compile two programs at once in one compiler tree (scratch files are shared).

Source layout (`src/main/java/caspien/lowerorder/`): `Main`, `LowerOrderGenerator` (the pipeline), one class per pass, and the
shared utilities listed in section 7.

---

## 3. Formats

### 3.1 Input: HOB (what the passes see)

Plain text, one instruction per line, whitespace-separated tokens (a `"..."` string and a one-character `'c'` literal, `' '`
included, stay one token). Names and canonical types are still present:

* Canonical type text is `storage_some_mutability_base`, e.g. `owns_mut_Box`, `imut_u64`, `raw_mut_u8`, `static_some_imut_string`;
  composite bases are written `elem[N]`, `dynarray(elem)`, `unsafe_dynarray(elem)`, `range(..)`/`range` (`CanonicalType`).
* Declarations: `STRUCT_START/STRUCT_MEMBER name type/STRUCT_PADDING n/STRUCT_END`, `ENUM ...`, `STRUCT_DECORATE`, `GLOBAL`,
  `ALLOC_STATIC`, `EXTERN`, `STRING`, `FUNC_START name`, `EXPORT`, `FUNC_DECORATE @x`, `RETURNS type`, `ARG name type`, `ALLOC name type`,
  `FUNC_END`.
* Typed operators: `PUSH name|literal type`, `ADDR name type`, `ASSIGN t t t`, `GT/LT/GT_EQ/LT_EQ/EQ/NEQ l r ret`, `ADD/SUB/MUL/DIV/MOD l r ret`,
  `AND/OR`, `NOT`, `NEG`, `SHL/SHR/ROTL/ROTR/BITS_*`, `INC/DEC`, `SEXT/ZEXT/TRUNC/FCONV`, `LOOKUP`, `LOOKUP_LHS`, `PUSH_FIELDNAME f type`,
  `DOT`/`DOT_LHS`, `NEW`, `NEW_DYN`, `NEW_UDYN`, `RESIZE`, `URESIZE`, `CLONE`, `DEREF`, `MEMCOPY`, `RET`, `PUSH_RET`, `EXTERN_CALL`,
  `RECURSIVE_CALL`, `STACK_LOCK`, `GT_DESTRUCT name`, `REGVAR name weight [f]` (the Optimizer's register-variable hint), labels `@x:`,
  `CMP`/`JMP`, `CC_START conv`/`CC_END conv`, `CALL`.
* Membership operators still unlowered: `IN`, `WITHIN`, `IN_SCAN`, `LEN_SCAN`, `INSTANCEOF`, `IMPLEMENTS`, `IS_BASE`; and the glue markers
  `CLONE` (bare pointer clone), `CLONE_FILL`, `GT_DESTRUCT_TAIL`, `GT_DROP_ADDR`, which only the passes below understand.

### 3.2 Output, stack form (always produced; the only form with `deferred-operands: off`)

Types erased to byte sizes (`OP size`), names resolved to a signed frame offset `$off` (negative below the base pointer; incoming
stack-passed parameters positive), globals as `&sym`/bare symbol. Mnemonics keep their stack-machine meaning and split where the
runtime meaning differs (`_INT`/`_FLOAT`, `S`-prefixed signed compare/divide, `LOOKUP_ARRAY`/`LOOKUP_DYN`, `RET`/`RET_FLOAT`,
`PUSH_RET_INT`/`PUSH_RET_FLOAT`). A function has ONE combined `ALLOC totalSize` (rounded up to 16). The full rewrite table, the
`NEW`/struct-literal layout tokens (`m<bytes>`, `p<bytes>`, `a<elemBytes>x<n>`, `t8`, `ra`) and the frame-layout rules are in
[docs/AddressLoweringPass.md](docs/AddressLoweringPass.md). Struct/enum declarations are consumed and dropped.

Frame order at the top of every function (fixed offsets that the backend relies on): `gt_routine_address` (rbp-8), `gt_error_message`
(rbp-16; `GT_UNWIND MSG` copies the message up a frame through it), then, in programs that use stack traces, `gt_trace_id` (rbp-24)
and `gt_trace_ptr` (rbp-32), then the parameters, then ordinary locals (`ArgToAllocLoweringPass` keeps this order).

### 3.3 Output, register form (`deferred-operands: on`)

A compile-time model of the operand stack replaces pushes and pops by three-address `R_*` lines. Operand syntax:

| Operand | Meaning |
|---|---|
| `#imm` | immediate (a float constant is its IEEE bit pattern) |
| `$off` | frame slot (the VALUE of the slot as an operand; the address for `R_LEA`/`R_PUSHA`/`R_ARGA` bases) |
| `&sym`, `&sym+N` | global symbol (`+N` = constant byte offset, written by `GlobalConstAddrPass`, RIP-relative in the backend) |
| `%t0..%t3` | temporaries = rax, rbx, r11, r12 (`RegisterFormPass.TEMP_COUNT` = 4) |
| `%v0..%v9` | variable registers = r13, r14, r12, r8, r9, r10, rsi, rdi, rdx, rcx |
| `%x0..%x5` | float variable registers (SysV xmm8-13, win64 xmm6-11) |
| `%y0..%y3` | float temporaries (SysV xmm4-7, win64 xmm12-15) |
| `%z0..%z3` | xmm0-3, borrowed by `StaticFloatCachePass` |
| `@label` | label; `@loop_rot_N` is minted by `LoopRotationPass` |
| scratch | `r15` (`%s`, backend scratch), xmm14/15 (win64 xmm4/5) |

Invariants: a `%t` or `%v` register always holds its value **zero-extended to 64 bits**; narrow `R_BIN` results are NOT masked (only
loads are zero-extended), so a relabel of a temp to a narrower width is legal only for a zero-extended (`zx`) temp. Register-form
temporaries are never live across a label, jump, call bracket or any unfused instruction (the model is flushed at those).
Size-first operand order: `R_LD n %tD addr`, `R_ST n addr src`, `R_MOV 8 dst src`, `R_BIN OP size dst a b`.

Instruction families (all written by this stage; semantics are the backend's, see `Codegen/CLAUDE.md`):

| Family | Lines |
|---|---|
| move/memory | `R_MOV 8 dst src`, `R_LD n %tD addr`, `R_ST n addr src`, `R_SETV n %vK src` (narrow store into a variable register: truncate, zero-extend), `R_RMW OP 8 $x b` (`x += c` as one memory-operand instruction; OP ADD SUB INC DEC) |
| address | `R_LEA %tD base idx scale [disp]` (base `$slot`/`&sym`/`%t`/`%v`; the 6th token is a byte displacement, e.g. the 16-byte safe-dynarray header) |
| integer ALU | `R_BIN OP size %tD a b` (OP: ADD SUB MUL AND OR EQ NEQ LT LT_EQ GT GT_EQ SLT SLT_EQ SGT SGT_EQ SHL SHR SAR ROTL ROTR BAND BOR BXOR), `R_UN OP size %tD a` (INC DEC NEG NOT BNOT), `R_DIVC DIV\|MOD n %tD a #k` (divide by a constant that is not a power of two; reciprocal multiply in the backend) |
| branches | `R_BRF src @L` (jump if zero), `R_BRC C size a b @L` (jump when **NOT (a C b)**; size 1/2/4/8, or `F4`/`F8` for a float compare), `R_BRCM C 8 a %base @L` (same, second operand = the 8-byte word at `(%base)`), `R_CMOV C n a b %vK X [Y]` |
| indexed/displaced | `R_LDI n %tD base idx scale [disp]`, `R_STI n base idx scale src [disp]`, `R_LDXI`/`R_STXI` (float twins), `R_LDD n %tD base disp`, `R_STD n base disp src`, `R_LDXD`, `R_STXD` |
| calls/returns | `R_ARG n size src`, `R_ARGA n addr`, `R_FARG n size src`, `R_RET src`, `R_RETF src`, `R_GETRET n %vK`, `R_GETRETF n %xK`, `R_POPV n %vK`, `R_POPX n %xK` |
| stack flush | `R_PUSH 8 src`, `R_PUSHA 8 addr` (written only when the model is flushed in front of an unfused instruction) |
| float | `R_FBIN OP n %tD a b` (ADD SUB MUL DIV; operands are raw bit patterns), `R_FCMP OP n %tD a b`, `R_FBINX OP n %xK\|%yK a b`, `R_LDX n %x\|%y addr`, `R_STX n addr %x\|%y`, `R_XTOG n %t %x`, `R_GTOX n %x %t`, `R_XMOV dst src`, `R_FSQRT n dst src` |
| float bookkeeping | `R_XVAR %xK $home size` (home slot for the ABIs whose xmm registers do not survive a call), `R_XRELOAD` (after every `@catch_` label), `R_XSPILL` (before a direct jump into a catch label) |

`REGHINT offset size weight [f]` (from the Optimizer's `REGVAR name weight [f]` via `AddressLoweringPass`; `f` = float local) exists
only between `AddressLoweringPass` and `RegVarPromotionPass`, which always strips it (and any stray `REGVAR`).

---

## 4. The pass pipeline

`LowerOrderGenerator.generate` runs every pass **exactly once**, in this fixed order. There is no fixed-point loop at this level
(`PassResult.changed` is ignored); the only internal iteration is `JumpCleanupPass` (up to 8 rounds). The config object is loaded
(`CompilerConfig.load("compiler.config")`) after the first six passes. `D` = `deferred-operands`, `V` = `variables-in-registers`.
"Shipped" = the shipped `toolchain.config` (D on, V on, every other switch off).

| # | Pass | Form it works on | Runs when | Shipped |
|---|---|---|---|---|
| 1 | `MembershipLoweringPass` | HOB | always | on |
| 2 | `CloneGenerationPass` | HOB | always | on |
| 3 | `DropGlueGenerationPass` | HOB | always | on |
| 4 | `ArgToAllocLoweringPass` | HOB | always | on |
| 5 | `AddressLoweringPass` | HOB -> stack LOB | always | on |
| 6 | `StrengthReductionPass` | stack | always | on |
| 7 | `RangeCheckFusionPass` | stack | always (also with D off) | on |
| 8 | `RangeEndHintPass` | stack | D and V | on |
| 9 | `RangeWordSplitPass` | stack | D | on |
| 10 | `RegisterFormPass` | stack -> register form | D | on |
| 11 | `LoopHoistPass.run` | register form | D, V and `hoist-array-bases` | off |
| 12 | `RegVarPromotionPass` | register form | always runs (strips hints); promotes iff D and V; float variables iff `float-variables-in-registers`; r12-r14 in allocating functions iff `variables-in-alloc-functions`; rsi/rdi/rdx/rcx iff `variables-in-arg-registers` | int vars on, rest off |
| 13 | `LoopHoistPass.finish` | register form | when step 11 hoisted something | - |
| 14 | `FloatTempPass` | register form | D and `float-temporaries-in-registers` | off |
| 15 | `FloatIntrinsicPass` | register form | always (matches only with float register operands and a libm `sqrt`/`sqrtf` extern) | on |
| 16 | `StaticFloatCachePass` | register form | D, V and `float-variables-in-registers` | off |
| 17 | `BranchFusionPass` | register form | always (no-op without register form) | on |
| 18 | `JumpCleanupPass` | register or stack form | always | on |
| 19 | `IndexedAccessPass` | register form | always | on |
| 20 | `GlobalConstAddrPass` | register form | always | on |
| 21 | `LengthCompareFusionPass` | register form | D and `fuse-length-compare` | off |
| 22 | `FieldDisplacementPass` | register form | always | on |
| 23 | `DestForwardingPass` | register form | always | on |
| 24 | `ConditionalMovePass` | register form | D and `conditional-move` | off |
| 25 | `CopyForwardPass` | register form | D and `copy-forward` | off |
| 26 | `LoopRotationPass` | register form | D and `loop-rotation` | off |

With a switch off, that pass is skipped and the output is byte-identical to a build without it. Passes 17-26 only find work in
register-form text, so they are free without D. Which passes need which: the register-form passes need D; promotion needs V; the
hoist/float-cache/arg-register/alloc-function switches need V; the float-variable switch needs V.

Order constraints worth knowing: `RangeCheckFusionPass` must precede `RegisterFormPass` (it removes stores the register-form pass
could not fuse); `RangeEndHintPass`/`RangeWordSplitPass` run on stack form so their `REGHINT`s exist before promotion;
`LoopHoistPass` sits between register form and promotion because the hidden copy needs a `REGHINT`; `BranchFusionPass` runs after
promotion and `FloatTempPass` so neither needs to know `R_BRC`; `IndexedAccessPass`/`FieldDisplacementPass`/`GlobalConstAddrPass` run
before `DestForwardingPass`, so loads are in final shape when a copy is folded into them; `ConditionalMovePass` and `CopyForwardPass`
precede `LoopRotationPass` so a rotated header copies the final lines.

---

## 5. Configuration

`compiler.config` (top-level keys come BEFORE the `CallingConventions:` header; a key after it is a parse error). Values are
`on`/`off` (anything else is an error naming the key). All switches default **off** when absent. The file is a section of
`toolchain.config` (`===compiler.config===`); ASTGenerator and Optimizer read their own keys from the same text.

| Key | Default | Needs | Read by |
|---|---|---|---|
| `deferred-operands` | off (shipped on) | - | gates passes 8-10 and 21-26 |
| `variables-in-registers` | off (shipped on) | D | `RangeEndHintPass`, `RegVarPromotionPass`, `LoopHoistPass`, `StaticFloatCachePass` |
| `float-variables-in-registers` | off | V | `RegVarPromotionPass` (xmm variables), `StaticFloatCachePass` |
| `float-temporaries-in-registers` | off | D | `FloatTempPass` |
| `hoist-array-bases` | off | V | `LoopHoistPass` |
| `variables-in-alloc-functions` | off | V | `RegVarPromotionPass` (3rd ctor argument) |
| `variables-in-arg-registers` | off | V | `RegVarPromotionPass` (4th ctor argument; `%v6..%v9`) |
| `fuse-length-compare` | off | D | `LengthCompareFusionPass` |
| `conditional-move` | off | D | `ConditionalMovePass` |
| `copy-forward` | off | D | `CopyForwardPass` |
| `loop-rotation` | off | D | `LoopRotationPass` |

A switch whose prerequisite is off is silently inert (e.g. V on with D off does nothing). The calling-convention block
(`CallingConventions:` / one entry per convention / `default: name`) is validated at load; this stage consumes
`argument-registers` and `argument-registers-float` counts, `shadow-stack` and `shared-argument-position` (in
`AddressLoweringPass.resolveArgWordSlots`); `cleanup`, `return-register*`, `alignment` are parsed and validated but unused here.
A function picks its convention from its `FUNC_DECORATE @call_convention(name)` line, else `default`.

**Keys this parser skips by prefix** (they belong to the Optimizer, which validates them): `loop-unrolling:`, `loop-unroll-*`,
`function-inlining:`, `inline-max-*`, `constant-folding:`, `variable-elision:`, `variable-shifting:`, `struct-unpacking:`,
`dead-control-flow-removal:`, `dead-function-removal:`, `unused-declaration-removal:`, `variable-allocation-reordering:`,
`struct-member-reordering:`. Any other unknown top-level key is an error ("expected top-level key 'CallingConventions:'"), so **a new
Optimizer/ASTGenerator key must be added to this skip list** (and to `CompilerCache.OPT_KEYS`; this stage's own keys go into
`CompilerCache.REG_KEYS` and into the ASTGenerator's `CompilerConfig`, which also parses them). Codegen keys live in a different file
(`codegen.config`) and never reach this stage.

---

## 6. Passes

Each section: what it does, the lines it reads and writes, switch, safety nets, recorded measurements, tests. Benchmark numbers
are the ones recorded in the project's notes; VM noise is 10-30%, so single figures are indicative. Everything was verified on Linux
(4-config sweeps: shipped / allon / aggressive / alloff via `tests/run_tests.py`); Windows/Wine was NOT run for the newer passes
unless stated.

### 6.1 Lowering of high-level constructs (HOB -> HOB/stack)

#### MembershipLoweringPass
Rewrites `IN`, `WITHIN`, `INSTANCEOF`, `IMPLEMENTS`, `IS_BASE`, `IN_SCAN` (`u64 in string`) and `LEN_SCAN` (`len(unsafeDynArr, terminator)`)
into compares, `AND/OR/NOT`, fixed-offset dotted reads and, for the two scans, a generated byte-by-byte loop. Always on, first.
* `x in range`: `x >= r.start && x < r.end` (bounds read as dotted run-time fields, so bare `range` works like `range(0,5)`); `x in dynarray`:
  `x < LEN`; range-in-range: non-strict subset; `r in dynarray`: `r.end <= LEN`; `within` = strict subset; `x in GuaranteedEnum` = OR chain of
  variant values; `x instanceof S` = one class-id test of `x.___type` (offset 0, found structure-agnostically so interface-typed operands work);
  `x implements I` = OR chain over the implementers' class ids (no implementer anywhere = constant false, left still evaluated).
* A `for` holder (`$for_range_N`) tests only `i < range.end` (the counter starts at `start`, is immutable in the body). Gates must accept both
  bounded `range(..)` and bare `range` types.
* A non-simple operand (call result, arithmetic) is materialised into a `$in_tmpN` local through a widened `POP name type`; pointer-wrapped
  operands (`raw range(..)`) are dereferenced into a storage-free temp first (`derefInto`).
* Detail, shapes, label/temp bookkeeping and the bugs found: [docs/MembershipLoweringPass.md](docs/MembershipLoweringPass.md), history in
  [docs/history.md](docs/history.md).
* Tests: `tests/in_fold_test.caspien`, `tests/range_split_test.caspien`, `tests/rangecheck_test.caspien`, `tests/dyn_for_forms_test.caspien`,
  `tests/recursion_base_test.caspien` (`is base`), `tests/instanceof_warn_test.caspien`, `docs/examples/11_types.caspien`. There are no dedicated
  `in`/`within`/`instanceof` fixtures beyond these in `tests/`.

#### CloneGenerationPass
Ownership-aware `clone`. A flat pointee (no `owns` content) keeps `CLONE size` (turned into `CLONE size` / `CLONE_DYN elemSize` by
`AddressLoweringPass`). For an owns-bearing pointee (`StructTable.isOwnsBearing`) it generates, once per struct shape:
* `__clone_T` (`FUNC_DECORATE @clone_glue`, returns `owns_mut_T`): phase 1 clones every `owns` leaf into a hidden local (a **NULL source leaf
  stays null, which is not a failure**; a failed clone is null-checked and `GT_REGISTER`ed), phase 2 pushes the fields and `NEW`s, the result is
  `GT_REGISTER`ed *before* its null check (so a ghost-table growth failure takes the ordinary out-of-memory path); the fail label
  `GT_DESTRUCT`s the locals and returns null, so nothing leaks. The caller (the emitter) null-checks and registers the final result.
* `__clone_value_T(dst, src)` for **inline** owns-bearing elements (two stack words; RVO-style store through `$ret_dest`; returns 1/0;
  `X86Backend.emitDropGlueCall` handles the pair).
* A real generated loop for dynarrays of owns pointers (the new array is filled with null first, so a failure destructs it whole); for dynarrays
  of inline owning structs, on failure the new array is cut down to the elements made.
* `CLONE_FILL arr type from` (emitted after a growing `resize` of an owning-element dynarray) becomes a loop replacing the bitwise-copied new
  slots by deep clones (the fill value stays with the caller; every new slot is a clone).
* Not recursed into (left as the original bare `CLONE`): a bare owns pointer directly at a fixed array. Recursive structs cannot occur.
* Tests: `clone_test`, `clone_deep_test`, `clone_oom_test` + `clone_oom_check.sh` (sweeps allocation failures), `dyn_owning_elems_test`
  (+ `_check.sh`, valgrind), `dyn_owning_oom_test` + `dyn_owning_oom_check.sh`, `generic_owning_dyn_test`, `owning_array_test`
  (+ `_check.sh`), `resize_fill_test`.

#### DropGlueGenerationPass
Expands the compiler's flat `GT_DESTRUCT name` (one per owns slot) into real recursive teardown, before `AddressLoweringPass` resolves names.
* `__drop_T` (`FUNC_DECORATE @drop_glue`, parameter `ref_mut_T`, lean: no ghost-table prologue, never allocates or throws) is generated once per
  owns-bearing struct. It never destructs its own parameter; the call site does `CALL __drop_T` then the original `GT_DESTRUCT` (which stays last).
  The parameter is dereferenced once into a storage-free local and members are reached through that.
* An owns member is null-guarded (`NEQ .. null`), then recursed into, then `GT_DESTRUCT`ed; dynarray elements are walked by a generated loop;
  fixed arrays are unrolled (`emitFixedArrayElementDrops`: element address via `ADDR`/`PUSH i`/`LOOKUP_LHS`, then `CALL __drop_T`; numeric path
  segments like `x.hs.1.w` are resolved by `AddressLoweringPass.resolveAddress`).
* A struct's `@drop` function (`StructTable.dropHookOf`, read from `FUNC_DECORATE @drop` + first `ARG`) is called by `__drop_T` first, while
  every member is intact.
* `GT_DESTRUCT_TAIL arr type startLocal` (shrinking `resize`) becomes the element-drop loop from `startLocal`; `GT_DROP_ADDR T` (address of an
  inline owning struct on the stack, emitted before overwriting it through a pointer/index) becomes `CALL __drop_T`.
* A `GT_DESTRUCT name`'s type is resolved from `ALLOC` **and** `ARG` lines (it runs before `ArgToAllocLoweringPass`; before 7 Oct a parameter's
  destruct was a bare free and its owns members leaked; test `param_drop_test`). Top-level owns-local drop is null-guarded.
* Known gap: an `owns` pointer directly at a bare fixed array is not recursed into (plain `GT_DESTRUCT`).
* Tests: `drop_hook_test` (+ `drop_hook_*_error_test`), `param_drop_test`, `owning_array_test`, `dyn_owning_elems_test`, `inline_owns_drop_test`.

#### ArgToAllocLoweringPass
Erases `ARG name type` and replaces it by a real `ALLOC name type` plus a load sequence per parameter (`ADDR name type`; one `PUSH ARGn type` /
`PUSH FARGn type` per word; `ASSIGN`). A by-value range parameter is two words (both `indeterminate_u64`); everything else is one word. `n` is a
uniformly increasing word index over the whole parameter list; which words are register- or stack-passed is decided later by
`AddressLoweringPass`. Frame order is preserved exactly: `gt_routine_address`, `gt_error_message`, `gt_trace_*` (if present), then the
parameter `ALLOC`s, then the function's other `ALLOC`s, then the parameter loads, then the body. The `ARG` lines are removed from where the
compiler emitted them. Test: any call test (`call_arg_regs_test`, `stack_args_test`, `inline_argreg_test`).

#### AddressLoweringPass
The biggest pass (about 1500 lines): the actual high-order-to-low-order step. It runs once after everything else is settled (every `ALLOC`,
including the glue passes' ones) and:
* gives every name one frame slot (one slot per NAME; a repeated `ALLOC` of a name in a sibling scope reuses the slot when at least as big and
  aligned, else takes a bigger one), lays out struct members by `StructTable.layoutOf` (padding included), collapses all `ALLOC` into one
  rounded to 16, resolves dotted chains (`x.y.z`, numeric segments into fixed arrays);
* erases types to sizes and splits mnemonics by runtime meaning (`_INT/_FLOAT`, signed `S*` compare/divide `SLT_INT..SMOD_INT`, `SAR`,
  `BITS_AND/OR/XOR/NOT`, `SHL/SHR/ROTL/ROTR`, `TRUNC src dst`, `NEG_FLOAT`, `RET`/`RET_FLOAT`, `PUSH_RET_INT/FLOAT`, `LOOKUP_ARRAY`/`LOOKUP_DYN`
  (+`_LHS`), `CLONE`/`CLONE_DYN`, `NEW size` + layout tokens, `NEW_DYN size count`, `NEW_UDYN`, `RESIZE`/`URESIZE`, `STACK_LOCK size`, `MEMCOPY`);
* decides register- vs stack-passed parameter words from the calling convention (`resolveArgWordSlots`: per-bank counters for SysV, one shared
  counter for win64's `shared-argument-position`; overflow words share one stack ordinal at `16 + shadowStack + n*8`);
* writes layout tokens for `NEW` and stack struct/array literals (`findStructLiteralAssigns`, `findArrayLiteralAssigns`, `splitValueUnits`: an
  operand-stack simulation; an RVO store through `$ret_dest` and an `@untyped` struct literal without class-id push are recognised; test
  `untyped_literal_test`);
* turns `REGVAR name weight [f]` into `REGHINT off size weight [f]` (a name with no slot drops the hint); resolves `true/false` and
  `Enum.Variant` literals; rewrites top-level `GLOBAL`/`ALLOC_STATIC`; drops struct/enum/decorator declarations.
* `sizeOf` uses a struct name whole when `StructTable` knows it (a generic instantiation like `HashMapEntry_u64` would otherwise parse as
  mutability+base and size as 8). `ADDR_OF` is dropped only for `REF` on an owns value and `RAW` on a hoisted string literal.
* Full format tables: [docs/AddressLoweringPass.md](docs/AddressLoweringPass.md); history: [docs/history.md](docs/history.md).
* Tests: `struct_literal_test`, `struct_literal_nested_test`, `array_member_shapes_test`, `untyped_literal_test`, `sizeof_stride_test`,
  `bits_ops_test` + `bits_ops_matrix*_test` + `bits_ops_check.sh`, `bits_rot_test` (+ `_check.sh`), `stack_args_test`, `call_arg_regs_test`,
  and the whole `tests/` sweep.

### 6.2 Stack-form clean-ups

#### StrengthReductionPass
Unsigned size-8 `PUSH 8 K ; DIV_INT 8` with K = 2^n (n >= 1) becomes `PUSH 8 n ; SHR 8`; `PUSH 8 K ; MOD_INT 8` with K a power of two in 2..2^31
becomes `PUSH 8 K-1 ; BITS_AND 8`. Needs a decimal literal `PUSH 8` immediately before the operator; the signed `SDIV_INT`/`SMOD_INT` are never
touched (rounding differs). Always on. The register-form pass turns the results into `R_BIN SHR`/`BAND` with an immediate. Test:
`strength_test.caspien`.

#### RangeCheckFusionPass
The 14-line constant-bounds `match i in arr` / `match i into arr` test (`PUSH i / PUSH K0 / PUSH K1 / POP $r 16 / POP $l 8 / ... AND 1 / CMP / JMP`)
becomes direct unsigned compares against the literals (just the upper compare when K0 = 0). Only for unsigned compares (`GT_EQ_INT`/`LT_INT`),
decimal literal bounds in 0..2^31-1, and only when the three temp slots (`$l`, `$r`, `$r+8`) are mentioned nowhere else in the function (so the
dropped stores cannot be observed). `POP $r 16` puts the first-pushed word (K0) at the lower address. Runs on stack form even with D off.
Measured (n-body, N = 5M, everything-on): the `loop` variants 1.64 -> 0.68 s (f64) and 1.59 -> 0.70 s (f32), others unchanged. Tests:
`rangecheck_test.caspien` (21 checks), `rangecheck_check.sh` (25 hand-made LOB cases, env `LOB_CP`).

#### RangeEndHintPass
A `for i in a..b` loop builds its hidden 16-byte range (`ADDR 16 $r ; <a> ; <b> ; ASSIGN 16 16 16`) and re-reads `$r+8` every iteration; nothing names
it as a variable, so it was never promoted. The pass splits the construction into two 8-byte stores (same bytes) and adds `REGHINT $r+8 8 weight`
right after the function's `ALLOC`. Conditions: both value expressions built only from `PUSH 8 x` and size-8 `ADD/SUB/MUL_INT`, `SHR/SHL/SAR`,
`BITS_*`; every other mention of `$r` is `PUSH 8 $r` or another construction of this shape, every other mention of `$r+8` is `PUSH 8 $r+8`; no
token names an offset strictly between `$r` and `$r+16`. Weight = sum over mentions of `8^min(loopDepth,4)` (a backward jump to an earlier label
marks a loop); weight below 8 gets no hint. Needs D and V; if the slot is not promoted the program is the same apart from two 8-byte stores. Part of
the "step 5" round: fannkuch-redux +14-17% together with the volatile registers (same-session, alternating old/new). Tests:
`regvars_volatile_test.caspien`, `regvars_volatile_check.sh`.

#### RangeWordSplitPass
16-byte range constructions (`ADDR 16 $X ; PUSH 8 a ; PUSH 8 b ; ASSIGN 16 16 16`) and copies (`ADDR 16 $X ; PUSH 16 $Y ; ASSIGN 16 16 16`) become two
independent 8-byte assignments, which the register-form pass can turn into moves. `a`/`b` must be `$slot`, an integer literal or `ARGn`; a slot
operand must not overlap `$X..$X+16`; a copy needs `$Y` disjoint from `$X` (`$Y == $X` is left alone). It also adds `REGHINT`s for the two words of
every split range whose every mention is a plain 8-byte one (weights by loop depth as above). Needs D. Test: `range_split_test.caspien`
(under Wine one `page fault` did not reproduce, 0/10).

### 6.3 Register form

#### RegisterFormPass
"Deferred operands". Keeps a compile-time model of the operand stack. Entries: **K** constant, **M** frame-slot value still in memory (size 1/2/4/8),
**A** address (`$off` or `&sym`), **T** value in a temp register (zero-extended, with a width). Operators whose operands are all remembered emit the
three-address `R_*` lines of section 3.3; everything else goes through the safety nets.
* Handlers (the `handlers` table): `PUSH` (frame values, constants, global scalar reads `R_LD n %t &sym` loaded eagerly, declined with no free temp or with
  `ATOMIC_SWAP` two lines later), `ADDR`, the integer and bitwise operators (including variable shift counts), `DIV_INT`/`MOD_INT` by a literal
  (`R_DIVC`, n = 1/2/4/8, divisor >= 3 and not a power of two; the narrow forms zero-extend the dividend), unary ops, `ASSIGN` (8-byte frame store as
  `R_MOV`, general stores `R_ST`, `x += c` as `R_RMW`), `CMP`+`JMP` (`R_BRF`), float arithmetic/compares, `DEREF`, `LEN`, `LOOKUP_DYN(_LHS)` (element
  address `base + 16 + idx*n` as one `R_LEA` with displacement; value form only for n = 1/2/4/8, declined when the next line starts with `LOOKUP`
  or `DOT`; also `LOOKUP_DYN n ; PUSH_FIELDNAME off w ; DOT w` = one scalar field of an element), `ZEXT`, `TRUNC`, `LOOKUP_ARRAY_LHS`, `LOOKUP_ARRAY n`
  over a pointer base (unsafe dynarray, string; not when the next line is `DOT` or a `LOOKUP_ARRAY .. t8`), `PUSH_FIELDNAME`/`DOT_LHS`, `POP ARGn`,
  `POP $slot`, `RET`/`RET_FLOAT`.
* **Safety net 1, flush:** before any instruction the pass does not fuse, every remembered entry is written back (bottom to top) as its original
  `PUSH`/`ADDR` line; entries below a live temp (or when a temp is live off-model) are written with `R_PUSH`/`R_PUSHA`, because an original
  `PUSH` line uses rax/rbx and would clobber the temp. Temps therefore never live across a label, jump, call, `CC_*`, `LOOKUP*`, `NEW*`, atomics
  or any unknown mnemonic.
* **Safety net 2, rollback:** a "region" runs from one empty-model point to the next. A region using anything beyond plain 8-byte integers (narrow values,
  floats, pointers, globals, computed addresses, argument registers) is "risky": if it hits an unfusable instruction while entries are still remembered,
  the whole region is discarded and its ORIGINAL lines are emitted (fusion is all-or-nothing per region). Exception: a faithful flush (`flushIsFaithful`:
  `CC_START`, mnemonics ending `_FLOAT`/`_INT`, the bitwise family, and `plainWords()` = every entry is one 8-byte word) keeps the earlier fusion.
* Hazard handling: a deferred read of a frame slot is loaded into a temp before any store that could overwrite it (exact overlap test for a known
  slot, every deferred read for a store through a computed pointer).
* Invariants for handlers: **emit nothing before returning 0 (decline)**; unknown mnemonics flush the model; at most 4 live temps (more falls back to
  stack form).
* Measured: a 1e9-iteration `x += 8` loop 6.6 s (off) vs 2.1 s (on); the n-body programs 1.3-3.5x faster. Global scalar reads (n-body scalars, N = 5M):
  1.27 -> 0.66 s (f64), 1.20 -> 0.64 s (f32). Dynarray register-form handlers + r8-r10 in allocating functions + `@lock` inlining: safe sieve (1e8)
  3.24 -> 1.85 s (C -O2 0.85 s), naive 7.24 -> about 3.4 s. Unsafe-dynarray element reads (`d[i].f` behind a pointer) fused by `fuseLookupPtrRead`:
  binarytrees_unsafe -44%, graph_struct_unsafe -59% (now faster than their safe twins).
* Tests: `regform_test`, `regform2_test`, `regform_globals_test` (26 checks) + `regform_globals_check.sh` (14 LOB cases), `regform_dyn_test` (26) +
  `regform_dyn_check.sh` (47), `udyn_read_test`/`udyn_field_read_test` (+ `_check.sh`), `div_narrow_test`, `bits_ops*`, `narrow_branch_test`,
  `mul_const_test`, `perf_codegen_test`.

#### LoopHoistPass (`hoist-array-bases`, shipped off)
In safe code every dynarray access reloads the array pointer from its frame slot twice (length check, element address). For a natural single-entry loop
(header label never jumped to from outside; no inner label referenced from outside) the pass gives a slot `$S` a hidden copy `$X`, filled by
`R_MOV 8 $X $S` before the loop header, points the in-loop reads at `$X` and adds `REGHINT $X 8 weight`, so `RegVarPromotionPass` decides whether it
gets a register. A slot qualifies only when: every in-loop mention is a plain read `R_MOV 8 %t $S` (any write, `ADDR`, `GT_DESTRUCT`, `RESIZE` operand,
or an unknown access overlapping its 8 bytes counts as a store; an unknown mention at offset T counts as a store to everything at or above T);
it is never `ADDR_OF`/`R_ARGA`-exposed anywhere in the function (an exposure within 4 KiB below S counts); and at least `MIN_READS` = 2 reads begin
a pointer access (followed by `R_LD 8 %t %t` or `R_LEA %t %t ..`). Loops are processed outermost first; copy slots are appended below the frame
(`ALLOC` +8 each, rounded to 16). Functions with inline assembly or more than 20000 lines are skipped. Loops with calls are fine (a non-address-taken
slot cannot change under a call; volatile r8-r10 are given out only to call-free ranges).
`finish` (step 13, after promotion, same instance): undoes every copy that stayed in memory (reads back to `$S`, copy line gone, frame size
restored), then folds `R_MOV 8 %tA %vK` into the next `R_LD n %tA %tA` / `R_LEA %tA %tA ..` (-> `R_LD n %tA %vK` / `R_LEA %tA %vK ..`).
Measured: paired gains 3-9% on graph_struct, lru, knucleotide, strings, none slower; `R_LEA` with the 16-byte header displacement fuses into
`R_LDI/R_STI` (always on). Not done: struct-member dynarrays (`d.backing`), hoisting the length word (handled by the fused memory compare),
copy-chains through hidden inline slots (measured: little to gain). Tests: `loop_hoist_test` (+ `.py` model, `loop_hoist_check.sh`),
`loop_hoist_passes_check.sh` (hand-made LOB).

#### RegVarPromotionPass
Moves hot scalar locals from frame slots into registers, by renaming. Input: `REGHINT off size weight [f]`. The hint says nothing about which
register; this pass chooses. Per function:
1. **Ban** every hinted slot with any mention it cannot rename (`mentionOk` / `mentionOkX`; default for an unknown mnemonic is ban, so the slot stays
   in memory). A slot is promoted only if every mention is a rewritable operand at exactly its width. The decision is made on the final text, so
   there is nothing to roll back.
2. **Choose**, heaviest weight first (lower offset breaks ties). Callee-saved integer registers: `%v0`, `%v1` (r13, r14); `%v2` (r12) only if the
   function never mentions `%t3` (r12 is the fourth temporary) -> up to `VAR_COUNT_MAX` = 3. A function containing a `R13_R14_USERS` mnemonic (`NEW*`,
   `CLONE*`, `RESIZE*`, `DOT`, `LOOKUP_ARRAY`, `ASM_*`) is "blocked": no r12-r14 variables, unless `variables-in-alloc-functions` is on (then the
   backend saves/restores the used ones around those instructions, `@@V1314S@@` marks, see `Codegen/CLAUDE.md`). Inline assembly always blocks the
   whole function. Float hints (`f`) go to `%x0..%x5` (`XVAR_COUNT` = 6) when the float switch is on.
3. **Register sharing** (`shareRegisters`/`colour`/`colourInt`) runs only when candidates exceed registers (typical after inlining): backward liveness
   over the register-form text; two variables share a register only if never simultaneously live and neither is written while the other is live-out.
   Edges: fallthrough, `JMP` (conditional after a bare `CMP`), `R_BRC`/`R_BRF`, and every call-like line (`CALL INVOKE EXTERN_CALL SLEEP`) to every
   label referenced by a non-jump line (catch pads). `NO_FALLTHROUGH` = `RET RET_FLOAT R_RET R_RETF GT_UNWIND EXIT EXIT_THREAD FUNC_END`. Defs are only
   `R_ST` operand 2, `R_MOV` to the slot, and the float `ADDR..ASSIGN` pair; **a new mnemonic that writes a slot but is not a def makes sharing
   unsound**. Floats of different width never share a register.
4. **Caller-saved registers** (only on the over-subscribed path): `%v3..%v5` = r8, r9, r10 for variables never live at/across a line whose mnemonic is
   outside `VOL_SAFE` (`volOk`); `ARGk` with k >= 2 bans r8/r9; `PUSH_RET_FLOAT`/`R_GETRETF` ban r10. With `variables-in-arg-registers`: `%v6`/`%v7` =
   rsi/rdi (`argSafe`: never mentioned inside a call bracket `CC_START..CC_END`), `%v8`/`%v9` = rdx/rcx (SysV only; rdx not live at an `R_DIVC`, rcx not
   live at a variable-count shift/rotate: `rdxOk`/`rcxOk`). Volatile registers are tried first, then callee-saved.
5. **Rewrites:** `R_LD n %tD $x` -> `R_MOV 8 %tD %vK`; `R_ST 8 $x src` -> `R_MOV 8 %vK src`; narrow `R_ST` -> `R_SETV n %vK src`; every other operand: `$x`
   -> `%vK`. An int slot filled by a call result (`ADDR..CC_START..PUSH_RET_INT..ASSIGN`) becomes `R_GETRET n %vK` (whole bracket) or `R_POPV n %vK`.
6. **Floats:** `R_LD`/`R_ST` of a float slot -> `R_XTOG`/`R_GTOX`; `PUSH n $x` -> `PUSH n %xK`; `R_GETRETF`/`R_POPX` after calls; peephole `fuse`
   merges `R_LD ; R_GTOX` -> `R_LDX`, `R_FBIN ; R_GTOX` -> `R_FBINX`, `R_XTOG ; R_ST` -> `R_STX` (guarded by `tempDeadAfter`). Each function with float
   variables gets `R_XVAR %xK $home size` after its first line (SysV has no callee-saved xmm: the backend spills/reloads around every call) and
   `R_XRELOAD` after every `@catch_` label; **`R_XSPILL` is inserted before every `JMP`/`R_BR*` to a `@catch_` label** (a direct jump into a catch is
   not a call, so nothing spilled the floats). Test: `float_catch_check.sh`.
* Always strips `REGHINT`/`REGVAR`. With promotion off the output is byte-identical to a build without the feature.
* **Callee-saved handling:** every function that touches `%v0..%v2` (and, on win64, rsi/rdi) is saved/restored by `Codegen`
  (`finishCalleeSaved`), so values held across a call, throw or thread start are safe. Test `tests/callee_saved_probe.sh` (Linux, gcc).
* **Adding an `R_*` line:** add it to `mentionOk`/`mentionOkX`/`VALUE_OPS` (default is ban) or promoted slots simply stay in memory.
* Measured: gain on the n-body programs is small; step 5 volatile registers: fannkuch +14-17%; float variables (first version) 0-15% on n-body, with
  sharing: spectral-norm naive 0.67 -> 0.47 s, optimized 0.67 -> 0.25 s, n-body natural f64 0.77 -> 0.67 s, scalars f64 1.47 -> 1.22 s;
  `variables-in-alloc-functions` paired gains up to 20% (sieve_safe, json_serde_safe), none slower in the first runs; `variables-in-arg-registers` helps only
  merkle (-9%).
* Tests: `regvars_test`, `regvars3_test`, `regvars_blocked_test`, `regvars_share_test` + `regvars_share_check.sh`, `regvars_volatile_test` +
  `regvars_volatile_check.sh`, `regvars_float_test`, `alloc_vars_test` (+ `.py`, `alloc_vars_check.sh`, `alloc_vars_passes_check.sh`),
  `arg_regs_more_test` (+ `_check.sh`), `arg_regs_passes_check.sh`, `callee_saved_test`.

### 6.4 Float passes

#### FloatTempPass (`float-temporaries-in-registers`, shipped off)
A float temp produced by a float operation (`R_FBIN`, `R_LD 4/8`, `R_XTOG`) and consumed only by float operations/arguments/returns/compares/stores
stays in an xmm register `%yK` for its whole life: `R_FBIN -> R_FBINX`, `R_LD -> R_LDX`, `R_ST -> R_STX`, `R_GTOX -> R_XMOV`. A "chain" is one definition up
to its last read; rewritten only if EVERY read is a known consumer (`consumerOk`/`KNOWN_R`), the span holds only register-form lines (no label, jump,
call, stack-form line), at most `Y_COUNT` = 4 chains are live, and a chain inside a call bracket leaves xmm4-7 free (the call passes fewer than 4 float
arguments). A chain needs at least one genuinely float consumer, so plain integer copies are never turned into xmm moves. `coalesceIntoVariables` (runs
only when the pass is enabled) writes a run `%y = a op b; %y = %y op c; ...; %xD = %y op d` straight into `%xD` (commutative `%y = %y op %xD` is flipped).
Measured (N = 1e6): nbody_arr 0.23 -> 0.13 s, nbody_plain 0.53 -> 0.38 s, nbody 0.33 -> 0.28 s, nbody_loop 0.65 -> 0.62 s; with float variables also on
nbody_plain 0.34 s, nbody_loop 0.57 s. A new float line must be added to `consumerOk`/`KNOWN_R` or its chain stays in general registers. Tests:
`floattemps_test`, `float_direct_test` (+ `_check.sh`), `fmul_two_test` (+ `_check.sh`), `floatindex_test` + `floatindex_check.sh` (36 cases).

#### FloatIntrinsicPass
No switch of its own. A user extern that IS libm's `sqrt`/`sqrtf` (declared exactly `EXTERN sqrt mut_f64 mut_f64` / `EXTERN sqrtf mut_f32 mut_f32`), called as
`CC_START conv ; <arg> ; R_FARG 0 n SRC ; CALL sqrt 1 ; CC_END conv ; R_GETRETF n DST` with SRC and DST xmm operands, becomes `R_FSQRT n DST SRC`
(no call, no spill/reload of float variables). Visible difference: `errno` is not set for a negative argument (same NaN). It can only fire when float
registers exist, so it is off whenever they are off. Test: `float_direct_check.sh`.

#### StaticFloatCachePass (needs D, V and `float-variables-in-registers`)
In a call-free loop that reads a `let static` float several times and never writes it, the static is loaded once before the loop into `%zK`
(`R_LDX n %zK &g`) and the in-loop loads become `R_XMOV %yK %zK`. `%z0..%z3` = xmm0-3, plus any `%x` register above the highest the function uses.
A loop qualifies only if: every line is on a small whitelist of register-form ops (no call, spill/reload or stack-form op that may use xmm0/xmm1 as scratch);
the header is entered only from inside; the static is "plain" program-wide (its address appears only as the address operand of `R_LDX/R_STX/R_LD/R_ST`)
and unwritten in the loop; the program has no inline assembly and no thread start (`par_call`/`await_call`/`pthread`); it is read at least twice. Outermost
loops first; registers used by an enclosing loop are not reused inside. (The root `CLAUDE.md` says "SysV only"; this pass does not look at the calling
convention.) Tests: `static_cache_test` (+ `.py`, `static_cache_check.sh`).

### 6.5 Fusion and peephole passes on the final register-form text

#### BranchFusionPass
`R_BIN C 8 %tD a b ; R_BRF %tD @L` -> `R_BRC C 8 a b @L` (jumps when NOT (a C b)); `R_FCMP C n %tD a b ; R_BRF` -> `R_BRC C Fn a b @L`; two compares joined by
`R_BIN AND 1` (the `for` range test `start <= i && i < end`) -> two `R_BRC`. 1/2/4-byte compares fuse too (`R_BRC C 1|2|4`: the backend emits a sized
`cmpb/cmpw/cmpl`; narrow only when neither operand is a `$` frame slot); never both operands immediate; the compare temps must be dead (guaranteed
by the register-form model). Always on. 5 Oct narrow-compare round: strings benchmark -12..-17%. Tests: `narrow_branch_test` (+ `_check.sh`,
`gen_narrow_branch.py`), `float_branch_test` (+ `_check.sh`), `lob_passes_check.sh`.

#### JumpCleanupPass
Up to 8 rounds of three local rewrites on register or stack form: (1) a `JMP`/`R_BRC` directly after an unconditional `JMP` is dropped; (2) a `JMP` to the
label that follows is dropped; (3) `R_BRC C s a b @L1 ; JMP @L2 ; @L1:` -> `R_BRC C' s a b @L2 ; @L1:` (C' = opposite condition). In stack form a `JMP` right
after a bare `CMP` is the conditional jump and is never treated as unconditional (`followsCmp`). **Labels are never deleted**; only `JMP`/`R_BRC` lines are.
Always on. Test: `loopjump_index_test`, `lob_passes_check.sh`.

#### IndexedAccessPass
`R_LEA %tX base %vK scale [disp] ; ... ; R_LD/R_ST n ..%tX` -> `R_LDI n %tD base %vK scale [disp]` / `R_STI n base %vK scale src [disp]`, and the float forms
`R_LDX/R_STX` -> `R_LDXI/R_STXI`. Base: `&global`, `$frameSlot`, or a `%t`/`%v` register holding a pointer; the index must be a variable register `%v`.
Scale 1/2/4/8 requires the access width == scale (floats: width 4/8 equal to the scale); any other stride (a struct element: 24, 56..) is allowed with a
register or frame-slot base (never a global) and the backend multiplies the index into `%r15` (`imulq $stride, %idx, %r15`, then `disp(%base,%r15,1)`;
integer width 1/2/4/8, floats 4/8 independent of the stride). Conditions: lines between lea and consumer are known register-form mnemonics that neither
mention `%tX` nor write the index (or a register base) - at most `MAX_GAP` = 40 lines; `%tX` is dead after the consumer (next mention a pure
redefinition, or a label/`JMP` first). Always on. AoS nbody: -19% executed instructions with strided access. Tests: `indexed_narrow_test` (1/2/4),
`indexed_bases_test` (frame and pointer bases), `floatindex_check.sh`, `stride_index_test` (+ `_check.sh`), `lob_passes_check.sh`. The tables `DEST`/`PURE_DEF`
here are shared with the other peepholes; a new `R_*` line must be added to them.

#### GlobalConstAddrPass
`R_LEA %t &W #k s ; R_LD/R_ST/R_LDX/R_STX ..%t` -> the access at `&W+N` (N = k*s), one RIP-relative instruction. Widths 1/2/4/8, lea temp dead after, lines between
are known register-form lines not mentioning the temp (`MAX_GAP` 40). Always on. Tests: `global_const_addr_test` (+ `.py`, `_check.sh`,
`global_const_addr_passes_check.sh`).

#### LengthCompareFusionPass (`fuse-length-compare`, shipped off)
Folds the safe-dynarray length load of a bounds check into the compare:
`R_MOV 8 %t0 $S ; R_LD 8 %t0 %t0 ; R_BRC LT 8 idx %t0 @out` -> `R_MOV 8 %t0 $S ; R_BRCM LT 8 idx %t0 @out` (form 1: the base stays in `%t0`, an identical
`R_MOV 8 %t0 $S` right after the branch is dropped; `idx` is a register or a frame slot), and with a hoisted base `R_LD 8 %t0 %vK ; R_BRC ..` ->
`R_BRCM LT 8 idx %vK @out` (form 2). The length temp must be dead on both paths (`deadAtTarget`; first mention after the label a pure redefinition).
Measured on lru_safe: ~10% faster. Tests: `length_fuse_test` (+ `.py`, `length_fuse_check.sh`, `length_fuse_passes_check.sh`).

#### FieldDisplacementPass
`R_LEA %t base #k scale [d]` (register base, immediate index) + `R_LD/R_ST/R_LDX/R_STX` -> `R_LDD n %tD base disp`, `R_STD n base disp src`, `R_LDXD`, `R_STXD`
(`disp = k*scale + d`, within 32 bits, widths 1/2/4/8 and floats 4/8, base not written between, lea temp dead after, `MAX_GAP` 40). Always on. Test:
`field_disp_test` (+ `.py`, `field_disp_check.sh`).

#### DestForwardingPass
Two peepholes on straight-line code (always on; nothing to match without `%v` registers): (a) *destination forwarding*: a value computed into a temp and copied into a
variable register on the next line is computed there (`R_LDI 8 %t0 %t0 %v3 8 ; R_MOV 8 %v5 %t0` -> `R_LDI 8 %v5 %t0 %v3 8`; producers: loads, `R_MOV 8`, `R_BIN` ADD SUB MUL AND OR BAND BOR
BXOR, shifts by an immediate); the temp must be dead after; (b) *dead initialisation*: `R_MOV 8 %vJ <src>` whose next mention of `%vJ` (straight line, only known
lines between) is a pure redefinition that does not read it is deleted (`let x = mut 0` followed by the real assignment). Tests: `dest_forward_test` (+ `.py`,
`dest_forward_check.sh`, `dest_forward_passes_check.sh`).

#### ConditionalMovePass (`conditional-move`, shipped off)
`R_BRC C n a b @E ; R_MOV 8 %vK X | R_SETV m %vK #c ; @E:` (one arm) and `R_BRC C n a b @L ; R_MOV 8 %vK X ; JMP @E ; @L: ; R_MOV 8 %vK Y ; @E:` (two arms, labels used only by these
jumps, X not `%vK`) -> `R_CMOV C n a b %vK X [Y]` = `%vK = (a C b) ? X : Y` (one arm: unchanged when the test fails). n must be 1/2/4/8 (no float compares); X/Y are a
register or an immediate (a SETV constant is cut to its width); a source that reads memory is never speculated. The backend: `cmp`, `mov Y` (leaves flags alone), `cmovCC X`.
Measured neutral: strings -3%, sorting +2% (data-dependent selects; candidate for `benchmarks/harness/specific.json`); FULL has it on so sweeps cover it. Test: `cmov_select_test`
(generator `gen_cmov_select.py`, Python model).

#### CopyForwardPass (`copy-forward`, shipped off)
`R_MOV 8 %tX %vY` (Y in 0..5) whose temp is only READ in value positions (`R_MOV`/`R_SETV` source, `R_BIN` a/b for ADD SUB MUL AND OR BAND BOR BXOR SHL SHR SAR, `R_ST/R_STD/R_STI` source,
`R_LD/R_LDD/R_ST/R_STD` address base, `R_LEA` base) before a redefinition/label/`JMP`, with no write to `%vY` and no call/branch in a 14-line window (`WINDOW`), has its reads replaced by
`%vY` and the copy deleted (the 3-operand add/lea/imul forms then save the `mov`). `R_MOV 8 %vK %vK` is deleted. Only `%v0..%v5` (no implicit clobbers by divide, shift or call).
Executed instructions -0..-6.5% (binarytrees -6.5%, lru -3%, knucleotide, sorting). Test: `copy_forward_test` (generator `gen_copy_forward.py`).

#### LoopRotationPass (`loop-rotation`, shipped off)
A back-edge `JMP @H` to a header of at most 3 pure lines (`R_MOV`/`R_LD`/`R_LDI`/`R_LDD` into `%t`, simple `R_BIN` ADD SUB MUL AND OR BAND BOR BXOR SHL SHR SAR into `%t`) followed by
`R_BRC`/`R_BRCM` with an opposite condition is replaced by a copy of those lines plus the inverted branch to a new label `@loop_rot_N` placed after the original header branch: one
conditional jump per iteration instead of a conditional plus an unconditional one. The original header stays (entry and other jumps). Only when the back edge is directly followed by
the header branch's exit label (so a header with a second back edge is rotated at most once). Sieve: -17% executed instructions. Test: `loop_rotation_test` (generator `gen_loop_rotation.py`).

---

## 7. Shared utilities and adding a pass

| Class | Role |
|---|---|
| `BytecodeToken` / `BytecodeStatement` / `BytecodeProgram` (shared `BytecodeParser/`, package `caspien.bytecode`) | token (`text`, `file`, `line`, `kind`), one line (list of tokens; empty = blank line), whole program (list of statements). Every pass reads and writes a `BytecodeProgram`. Generated instructions are built with `BytecodeStatement.fromText(text, origin)` |
| `BytecodeParser` / `BytecodeSerializer` (shared `BytecodeParser/`) | whitespace split, `"..."` and `'c'` (incl. `' '`) kept whole, no regex; serializer joins tokens with one space. Used only by `Main` (text -> program, program -> text); the config is also loaded there and handed to `LowerOrderGenerator(config)`. Optimizer uses the same classes; Codegen still has its own copy |
| `PassResult` | `PassResult{lines, changed}`. The first five passes, `StrengthReductionPass` and `RegisterFormPass` have `PassResult run(lines)`; the others are plain classes with `run(lines)` returning the list (`RegVarPromotionPass`, `LoopHoistPass` also have constructor switches) |
| `CanonicalType` | parses `storage_some_mutability_atomic_base` text; peels `dynarray(..)`, `unsafe_dynarray(..)`, `elem[N]`; `sanitizeForLabel` for generated routine names |
| `StructTable` | reads `STRUCT_*` blocks: `membersOf`, `layoutOf` (members plus padding entries), `isOwnsBearing`, `dropHookOf` (from `FUNC_DECORATE @drop`) |
| `EnumTable` | reads `ENUM` lines: `variantValue(enum, variant)` (explicit value, or 0,1,2.. for a plain enum), `implementerClassIdsOf`, `valueVariantsOf` |
| `CompilerConfig` | `compiler.config` parser (section 5), `getDefault()` calling convention |
| `AddressLoweringSizeCalculator` | sizes/alignments from canonical text plus `StructTable` (struct size = padded layout; alignment mirrors the compiler's `BytecodeEmitter`) |

**Adding a pass**
1. Write `XPass` with `run(BytecodeProgram)`. Work per function (`FUNC_START`..`FUNC_END`), return the input list unchanged when nothing applies
   (so a disabled/non-matching pass is byte-identical). A switch goes in the constructor.
2. Put it in `LowerOrderGenerator.generate` at the right place (section 4's order constraints). Add a key to `CompilerConfig` (field + parse branch + error text),
   to `toolchain.config` (with a comment), to the ASTGenerator's `CompilerConfig` and to `CompilerCache.REG_KEYS`, and to the benchmark `OFF`/`FULL` dictionaries.
3. If it adds or rewrites `R_*` lines, update every table that decides what a line means: `RegVarPromotionPass.mentionOk`/`mentionOkX`/`VALUE_OPS` (default = ban),
   `FloatTempPass.consumerOk`/`KNOWN_R`, `IndexedAccessPass.DEST`/`PURE_DEF` (also used by `FieldDisplacementPass`/`GlobalConstAddrPass`), `JumpCleanupPass`, the `DEST`/`STRAIGHT`
   tables of `DestForwardingPass`/`CopyForwardPass`, and, for register sharing, the def rules in `shareRegisters`. Then teach `Codegen` (`X86Backend`) the line.
4. Handlers/peepholes must fail closed: decline (emit nothing) rather than guess; a mnemonic you do not know is a barrier.
5. Tests: a `tests/*.caspien` that prints PASS/FAIL (expected values from an independent Python model), a `*_check.sh` for hand-made LOB, and a `*_passes_check.sh` when
   the pass changes the LOB shape. Sweep all `tests/*.caspien` + `docs/examples` under shipped/allon/aggressive/alloff old tree vs new tree (`python3 tests/run_tests.py
   --configs shipped,allon,aggressive,alloff --checks`), say plainly what was not run (Windows/Wine normally).
6. Update this README and `CLAUDE.md` in place (facts, not a changelog).

---

## 8. Tests

* Runtime tests are `tests/*.caspien` (PASS/FAIL lines); `python3 tests/run_tests.py [--configs shipped,allon,aggressive,alloff] [--checks]` runs them (and
  `docs/examples`) in a persistent Linux scratch tree through the compiler's stage cache, skips already-tested binaries (SHA-256 verdict cache, `--rerun` forces)
  and runs a leak check under `tests/alloc_shim.c`. Compare output across configs: everything on, shipped, register-form only, everything off.
* `*_check.sh` scripts that feed **hand-made LOB** straight to this stage (run from the repository root, need `LowerOrderGenerator/out` built, env `LOB_CP=<classpath>`
  runs a mutant build): `lob_passes_check.sh` (JumpCleanup/IndexedAccess), `rangecheck_check.sh`, `regform_globals_check.sh`, `regform_dyn_check.sh`,
  `regvars_share_check.sh`, `regvars_volatile_check.sh` (also RangeEndHint), `floatindex_check.sh`, `loop_hoist_passes_check.sh`, `length_fuse_passes_check.sh`,
  `alloc_vars_passes_check.sh`, `arg_regs_passes_check.sh`, `dest_forward_passes_check.sh`, `global_const_addr_passes_check.sh`.
* Other checks: `callee_saved_probe.sh` (+ `check_callee_saved.py`), `float_catch_check.sh`, `float_direct_check.sh`, `float_branch_check.sh`, `udyn_read_check.sh`,
  `udyn_field_read_check.sh`, `narrow_branch_check.sh`, `mul_const_check.sh`, `mul_big_const_check.sh`, `field_disp_check.sh`, `static_cache_check.sh`,
  `global_const_addr_check.sh`, `dest_forward_check.sh`, `arg_regs_more_check.sh`, `stride_index_check.sh`, `nested_unroll_check.sh`, `loop_hoist_check.sh`, `length_fuse_check.sh`,
  `alloc_vars_check.sh`, `dyn_register_check.sh`, `bits_ops_check.sh`, `bits_rot_check.sh`, `clone_oom_check.sh`, `dyn_owning_elems_check.sh`, `dyn_owning_oom_check.sh`,
  `owning_array_check.sh`, `generic_owning_dyn_check.sh`, `resize_oom_check.sh`, `wine_check.sh` (Windows layer under Wine).
* Generators (Python models): `gen_cmov_select.py`, `gen_copy_forward.py`, `gen_loop_rotation.py`, `gen_narrow_branch.py`, `gen_div_const.py`, `gen_div_narrow.py`, `gen_div_fast.py`,
  `gen_mul_const.py`, `gen_stride_index.py`, `gen_nested_unroll.py`, `gen_perf_codegen.py`, `bits_ops_gen.py`, `bits_rot_gen.py`.
* Note: when touching float or lookup code, also compare benchmark outputs old vs new (the benchmark sweep only checks PASS lines).

---

## 9. Known gaps, rejected ideas, stale comments

**Known gaps (still true)**
* Stack-form residues: more than 4 live temps in a region falls back to stack form; a narrow `M` flush rolls the region back; a loop `within` test is a full strict-subrange
  check; a few `POP` lines remain unfused (4 in `stdlib_test`, per the notes).
* Loop range START is not promoted (measured with hand-patched assembly: slower).
* `LoopHoistPass` does not hoist struct-member dynarrays (`d.backing`) or the length word, nor follow copy chains through hidden inline slots.
* An `owns` pointer directly at a bare fixed array is not recursed into by the clone/drop glue passes.
* No Intel/MASM forms exist any more (target removed); `windows_gnu` is run only under Wine, and most newer passes (hoist, alloc-function variables, arg registers, loop
  rotation, copy-forward, conditional move, narrow compare fusion, static float cache, destination forwarding, displacement/global-address fusion) were verified on Linux only.
* Float `R_BRC` (`F4`/`F8`) is inverted by `JumpCleanupPass` and `LoopRotationPass` (and a NaN-unaware opposite-condition table) without a size check; safe code needs a finite
  proof for float operations, so NaN cannot arise there, but unsafe code could produce one. No test uses NaN (`float_branch_test` has none). Unverified, worth a test.

**Evaluated and rejected after measuring (do not rebuild without new evidence)**
* `.p2align 4` on every loop head (fannkuch +5%, nbody +3%).
* Loop-scoped register copies for call-crossing scalars such as the strings hash accumulators (13 programs touched, all within +-4%: store-to-load forwarding hides the chain).
* `LoopHoistPass.MIN_READS` = 1 (no effect); hidden-slot copy forwarding and `d.backing` hoisting (displacement fusion cut instructions but binarytrees_naive stayed latency-bound).
* A run-time 32-bit-divide fast path for wide `divq` (lru +7%, sorting +12% slower in paired runs; Codegen).
* Range-based shift-guard removal (only knucleotide has a hot guard and its counts are run-time values; Codegen).
* A vectoriser / SLP pass (GCC's SLP gains at most ~12% on the unrolled AoS nbody reference, and only after the scalar gap is closed).
* r11 as a variable register was not attempted (it is the temp `%t2`; it would shrink the temp pool).
* `jcc-padding` on in the FULL benchmark config makes sieve ~7-10% slower on this VM (owner decision pending; Codegen/`Compiler.java`, `benchmarks/harness/specific.json` sets it off for sieve).

**Stale or inaccurate comments found in the source while writing this (code is right, text is old)**
* `LowerOrderGenerator.java` class comment lists only 11 stages (no hoist, float cache, indexed/displacement fusion, forwarding passes).
* `RegisterFormPass` header says it "runs LAST" and "never keeps a variable in a register"; `RegVarPromotionPass` header mentions only r13/r14 (the code gives up to three
  callee-saved registers, plus r8-r10 and the argument registers on the sharing path).
* `LengthCompareFusionPass` header says the switch is `length-compare-fusion: on`; the real key is `fuse-length-compare`.
* `CompilerConfig` header says `AddressLoweringPass` is its only consumer (the pipeline reads the switches too); `ArgToAllocLoweringPass` and `CopyForwardPass` use
  `java.util.regex`/`String.matches` although the project rule is "no regex".
* `docs/COMPILER_REFERENCE.md` still says `IndexedAccessPass` handles "8-byte elements only" (it now handles scale 1/2/4/8, float forms and strides) and describes
  `variables-in-registers` as keeping "a function containing `new`/`clone`... in memory" (only without `variables-in-alloc-functions`).
