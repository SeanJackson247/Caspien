# master_code.md -- LowerOrderGenerator, full project mirror

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
stage 3 of the Caspien toolchain (higher-order bytecode ->
low-order bytecode -- the real address-lowering work). Compiled
`out/` class files are not part of this mirror. `compiler.config`
here is an identical copy of ASTGenerator's own -- both stages
need the same calling-convention data.

## File index

- CLAUDE.md
- compiler.config
- src/main/java/caspien/lowerorder/AddressLoweringPass.java
- src/main/java/caspien/lowerorder/ArgToAllocLoweringPass.java
- src/main/java/caspien/lowerorder/BranchFusionPass.java
- src/main/java/caspien/lowerorder/BytecodeParser.java
- src/main/java/caspien/lowerorder/BytecodeSerializer.java
- src/main/java/caspien/lowerorder/BytecodeToken.java
- src/main/java/caspien/lowerorder/CanonicalType.java
- src/main/java/caspien/lowerorder/CloneGenerationPass.java
- src/main/java/caspien/lowerorder/CompilerConfig.java
- src/main/java/caspien/lowerorder/DropGlueGenerationPass.java
- src/main/java/caspien/lowerorder/EnumTable.java
- src/main/java/caspien/lowerorder/FloatTempPass.java
- src/main/java/caspien/lowerorder/IndexedAccessPass.java
- src/main/java/caspien/lowerorder/JumpCleanupPass.java
- src/main/java/caspien/lowerorder/LowerOrderGenerator.java
- src/main/java/caspien/lowerorder/Main.java
- src/main/java/caspien/lowerorder/MembershipLoweringPass.java
- src/main/java/caspien/lowerorder/OptimizationPass.java
- src/main/java/caspien/lowerorder/PassResult.java
- src/main/java/caspien/lowerorder/RangeCheckFusionPass.java
- src/main/java/caspien/lowerorder/RangeEndHintPass.java
- src/main/java/caspien/lowerorder/RangeWordSplitPass.java
- src/main/java/caspien/lowerorder/RegVarPromotionPass.java
- src/main/java/caspien/lowerorder/RegisterFormPass.java
- src/main/java/caspien/lowerorder/StrengthReductionPass.java
- src/main/java/caspien/lowerorder/StructTable.java

### FILE: CLAUDE.md
```markdown
# CLAUDE.md -- caspien-lowerordergenerator Project Memory

Read this first in any new conversation thread before making changes.
This file describes the **current state** of this project only.

## New: `BITS_AND` / `BITS_XOR` / `BITS_NOT` lowering and register-form fusion of the whole bitwise family

Full account in the root `CLAUDE.md` (top section). `AddressLoweringPass`: `BITS_AND` / `BITS_XOR` join `BITS_OR` / `SHL` / `SHR` in the binary rewrite (`OP leftType rightType resultType` -> `OP size`), `BITS_NOT` is a new unary rule (`BITS_NOT argType resultType` -> `BITS_NOT size`, in `VALUE_UNARY_OPS`). `RangeEndHintPass` knows them in its BIN set. `RegisterFormPass`: `BIN_OPS` covers `BITS_AND/OR/XOR` and `SHL/SHR/SAR`, `fuseBinary` produces `R_BIN BAND|BOR|BXOR|SHL|SHR|SAR size dst a b` including VARIABLE shift counts (the count operand may be a temp, a frame value or an immediate), the new `BITS_NOT` handler produces `R_UN BNOT size dst a`, a fused narrow result is marked `zx` (zero-extended) only after the backend masks it, and `flushIsFaithful` was extended so a flush in front of a call keeps the earlier fusion. `StrengthReductionPass` still emits its internal `BITS_AND 8` for `x % 2^n`. Verified by `tests/bits_ops_test.caspien` and the four `bits_ops_matrix*_test.caspien` under full / origlinux / regonly / off.

## New: dynarray register-form handlers, `R_LEA` displacement, r8-r10 in allocating functions

Full account in the root `CLAUDE.md` (top section). `RegisterFormPass` handlers `LEN`, `LOOKUP_DYN`/`LOOKUP_DYN_LHS` (`emitLea(..., disp)` overload; value form adds `R_LD n %t %t` for n in 1/2/4/8 and declines when the next line starts with `LOOKUP` or `DOT`), `ZEXT`, `TRUNC` (`Entry.zx`, `loadedTemp()`; a relabel is only legal for a zero-extended temp because narrow `R_BIN` results are not masked). `R_LEA` may have 5 or 6 tokens (6th = displacement); `RegVarPromotionPass.mentionOk` accepts both, `IndexedAccessPass` fuses only the 5-token form. In `RegVarPromotionPass` a blocked function (uses r12-r14 as backend scratch) gets `intLimit` 0 but may still use r8-r10 for variables not live across a non-`VOL_SAFE` line. Tests: `tests/regform_dyn_check.sh` (47, env `LOB_CP`), `tests/regform_dyn_test.caspien` (26), `tests/regvars_blocked_test.caspien` (15), `tests/regvars_volatile_check.sh` (75).

## New: `RangeEndHintPass` and call-free volatile registers in `RegVarPromotionPass` (step 5)

Full account in the root `CLAUDE.md` (top section). `RangeEndHintPass` (after `RangeCheckFusionPass`, only with deferred-operands and variables-in-registers) splits the hidden 16-byte `for` range store into two 8-byte stores and hints the end word (`REGHINT <ro+8> 8 <weight>`, weight >= 8). `RegVarPromotionPass.shareRegisters` computes per variable `volOk` (never in in/out/def at a line whose mnemonic is outside the `VOL_SAFE` whitelist) and per function `volRegOk` (any `ARGk`, k >= 2, bans r8/r9; `PUSH_RET_FLOAT`/`R_GETRETF` ban r10); `colourInt` gives volOk variables the registers `%v3..%v5` (r8, r9, r10) first, then `%v0..%v2`. Only on the over-subscribed path; other output is unchanged. Tests: `tests/regvars_volatile_check.sh` (59 PASS, env `LOB_CP`), `tests/regvars_volatile_test.caspien`.

## New: `IndexedAccessPass` fuses float element access (`R_LDXI`/`R_STXI`); `FloatTempPass` keeps `R_FBIN` -> store chains in xmm

Full account in the root `CLAUDE.md` (top section). `R_LEA %tX &sym %vK scale` (scale 8 or 4) + a later `R_LDX n %x|%y &sym-address` or `R_STX n %tX %x|%y` with n equal to the lea's scale becomes `R_LDXI n %xK &sym %vK scale` / `R_STXI n &sym %vK scale %xK`. The integer forms (`R_LDI`/`R_STI`) still need scale 8 and size 8. `MAX_GAP` is 40; `DEST` has entries for `R_FBIN`(3), `R_FCMP`(3), `R_XTOG`(2) and -1 for the xmm-writing/memory forms; `PURE_DEF` has `R_FBIN`, `R_FCMP`, `R_XTOG`. `FloatTempPass`: after a Chain is created from an `R_FBIN` definition `c.floatUse = true`, so an f32/f64 result read only by a store stays in xmm. Test: `tests/floatindex_check.sh` (36 cases, env `LOB_CP`), `tests/floatindex_test.caspien`.

## New: `RangeCheckFusionPass` (stack form, before `RegisterFormPass`)

Full account in the root `CLAUDE.md` (top section). Rewrites the 14-line constant-bounds `match i in/into arr` test (`PUSH i / PUSH K0 / PUSH K1 / POP $r 16 / POP $l 8 / ... AND 1 / CMP / JMP`) to direct compares on the literals (just the upper compare when K0 = 0), only for unsigned compares, bounds 0..2^31-1 and temp slots ($r, $r+8, $l) mentioned nowhere else in the function (function = `FUNC_START`..`FUNC_END`). Called from `LowerOrderGenerator.run` after `StrengthReductionPass`, before the `deferred-operands` check, so it also runs with `deferred-operands: off`. `POP $r 16` puts the first-pushed word (K0) at the lower address. Tests: `tests/rangecheck_test.caspien`, `tests/rangecheck_check.sh` (needs `LowerOrderGenerator/out` built; `LOB_CP` for a mutant classpath).

## New: `RegisterFormPass` fuses global scalar reads and flushes (instead of rolling back) in front of a call or a plain stack op

Full account in the root `CLAUDE.md` (top section). `globalNames` is rebuilt at the start of every `run()` from `GLOBAL`/`ALLOC_STATIC` lines (dotted alias names excluded). `fusePush` sends `PUSH n <global>` to `fuseGlobalRead`: eager `R_LD n %tD &sym`, T entry of width n, region risky; declined with no free temp or with `ATOMIC_SWAP` at offset +2 (the backend's own check). In `run()` a risky region with remembered entries is flushed rather than rolled back when `flushIsFaithful(line)` (`CC_START`, or a mnemonic ending `_FLOAT`/`_INT`) and `State.plainWords()` (every entry size 8); otherwise `rollback` as before (end-of-run rollback unchanged). Handlers still must not emit before returning 0. Tests: `tests/regform_globals_test.caspien`, `tests/regform_globals_check.sh` (needs `LowerOrderGenerator/out` built; `LOB_CP` for a mutant classpath; the Drv4 driver shares ONE `RegisterFormPass` instance so a stale global set shows up).

## New: `RegVarPromotionPass` shares registers between non-overlapping variables, and promotes f64 slots (`R_MOV`)

Full account in the root `CLAUDE.md` (top section). `mentionOkX`/`rewrite` accept `R_MOV` on a float slot (`R_GTOX`/`R_XTOG`). When there are more candidates than registers (`intCands.size() > intLimit || xCands.size() > XVAR_COUNT`), `shareRegisters` runs a backward liveness over the register-form text and `colour` assigns candidates heaviest first; two share a register only if never simultaneously live and neither is written while the other is live-out. Edges: fallthrough, `JMP` (conditional after a bare `CMP`), `R_BRC`/`R_BRF`, and every call-like line to every label referenced by a non-jump line; `NO_FALLTHROUGH` = `RET`, `RET_FLOAT`, `R_RET`, `R_RETF`, `GT_UNWIND`, `EXIT`, `EXIT_THREAD`, `FUNC_END`. Defs are only `R_ST` (operand 2), `R_MOV` to the slot, and the float `ADDR..ASSIGN` pair (`R_POPX`/`R_GETRETF`); a NEW mnemonic that writes a slot must be added to the def rules or sharing becomes unsound. One `R_XVAR` per register. Without the trigger condition the original top-N path runs unchanged. Tests: `tests/regvars_share_test.caspien`, `tests/regvars_share_check.sh` (needs `LowerOrderGenerator/out` built; `LOB_CP` for a mutant classpath).

## New: `JumpCleanupPass` and `IndexedAccessPass` (always on, no switch); `for` tests only the upper bound

Both run on the final register-form text, in this order after `BranchFusionPass`. `JumpCleanupPass`: a jump after an unconditional jump is dropped, a `JMP` to the label that follows is dropped, `R_BRC C a b @L1 ; JMP @L2 ; @L1:` becomes `R_BRC C' a b @L2 ; @L1:` (C' = opposite condition), repeated up to 8 rounds; labels are never deleted. It also runs on stack-form code, where a `JMP` right after a bare `CMP` is the conditional jump and is never treated as unconditional (`followsCmp`). `IndexedAccessPass`: `R_LEA %tX &sym %vK 8` fused into the `R_LD 8`/`R_ST 8` that consumes `%tX` (`R_LDI 8 %tD &sym %vK 8`, `R_STI 8 &sym %vK 8 src`) when the lines between are known register-form mnemonics that do not mention `%tX` or write `%vK`, and `%tX` is dead afterwards (next read is a pure redefinition, or a label/jump comes first). `MembershipLoweringPass.buildRangeCheck` emits only `i < range.end` for a `$for_range_N` holder (the counter starts at `start`, is immutable in the body, and is only incremented after `i < end` held). Checks: `tests/loopjump_index_test.caspien`, `tests/lob_passes_check.sh` (hand-made register-form cases; needs `LowerOrderGenerator/out` built).

## New: `StrengthReductionPass`, `BranchFusionPass`, a third register variable (always on, no switch)

`StrengthReductionPass` (runs right after `AddressLoweringPass`): `PUSH 8 K; DIV_INT 8` with K = 2^n (n >= 1) becomes `PUSH 8 n; SHR 8`, and `PUSH 8 K; MOD_INT 8` with K a power of two in 2..2^31 becomes `PUSH 8 K-1; BITS_AND 8`. Unsigned size-8 operations only, and only with a decimal literal `PUSH 8` immediately before the operator (signed `SDIV_INT`/`SMOD_INT` are never touched: rounding differs). `RegisterFormPass.BIN_OPS` gained `SHR` (constant count 0..63 only) and `BITS_AND` (`BAND`), so the results stay in registers.
`BranchFusionPass` (runs last, after `FloatTempPass`): `R_BIN C 8 %tD a b; R_BRF %tD @L` becomes `R_BRC C 8 a b @L` (jump to L when NOT(a C b)); `&&` of two compares (`R_BIN AND 1` between them) becomes two `R_BRC`. Only 8-byte compares, never both operands immediate, and the operands must not read the other compare's destination temp.
`RegVarPromotionPass`: a function that never mentions `%t3` and is not blocked by `R13_R14_USERS` may promote up to `VAR_COUNT_MAX` = 3 variables (`%v2` = r12). r12 is the fourth temporary, which is why the `%t3` test exists; every backend sequence that uses r12 (NEW*, RESIZE, CLONE, DOT, LOOKUP_ARRAY, ASM) is already in `R13_R14_USERS`.

## Note: it never sees `SIZEOF`

The front end emits a symbolic `SIZEOF TypeName <type>` for struct sizes and `raw Struct` pointer-arithmetic scales; the Optimizer's `SizeofResolutionPass` (always on) rewrites every one to `PUSH n` before this stage runs, from the same STRUCT declarations (`STRUCT_MEMBER` + `STRUCT_PADDING`) that `AddressLoweringPass` lays out. Nothing here handles the mnemonic, and an unresolved one would be an Optimizer bug.

## Update: `ArgToAllocLoweringPass` keeps `gt_error_message` behind `gt_routine_address`

When a function starts with `ALLOC gt_routine_address` followed by `ALLOC gt_error_message`, both are copied through before the parameter allocs, so the message slot is at a fixed rbp-16 in every frame (it used to move with the parameter count). `GT_UNWIND MSG` in Codegen depends on this.

## Update: layout tokens for arrays of structs / multi-dimensional arrays / inline structs

`layoutTokens` now uses `splitValueUnits` + `layoutFromUnits`/`valueFromUnits` (recursive over struct layouts and array element types); the counting path remains as fallback. `NEW` matching uses the struct class id (`classIdOf`, `typeIdValue`, `findNewRunStart`). `LOOKUP_ARRAY` gets `t8` / `ra` tokens (a by-value 8-byte array target; a by-value array under 8 bytes as the result). See root `CLAUDE.md`, test `tests/struct_literal_nested_test.caspien`.

## New: struct literal `ASSIGN` layout tokens (`AddressLoweringPass.findStructLiteralAssigns`)

Full account in the root `CLAUDE.md`. Per function, a pre-scan finds each stack struct literal's own `ASSIGN T T T` (its `ADDR v T` is followed by `PUSH <digits> imut_u64`, the struct's type id) and the rewrite appends the struct's layout (`m<bytes>`/`p<bytes>` per entry, same as the `NEW` rewrite) so Codegen can repack a literal whose member values are not all plain pushes. A fixed array of scalars narrower than 8 bytes written element by element gets the token `a<elemBytes>x<n>` instead of `m<bytes>` (`elementwiseArrayToken`); heap `NEW` lines get the same treatment (`findNewRunStart`). Element-wise vs block array values are told apart per member by `splitValueUnits` (operand-stack simulation over the literal's lines, `layoutTokens`); an unparseable literal falls back to counting and is rejected when two members share an array type.

## New: `FloatTempPass` -- f32 temporaries in xmm registers (`float-temporaries-in-registers`, shipped off)

Full account in the root `CLAUDE.md`. Runs last (after `RegVarPromotionPass`) on the final text; finds float temp chains (producer `R_FBIN`/`R_LD 4`/`R_XTOG`, consumers `consumerOk`) and rewrites them to `%y0..%y3` (`R_FBINX`, `R_LDX`, `R_STX`, `R_XMOV`). A new float consumer/producer mnemonic must be added to `consumerOk`/`KNOWN_R` or its chain simply stays in general registers. Test: `tests/floattemps_test.caspien`.

## New: float register variables in `RegVarPromotionPass` (`float-variables-in-registers`, shipped off)

Full account in the root `CLAUDE.md`. Second register class: `Hint.isFloat` (from the optional `f` on `REGHINT`), `XVAR_COUNT` = 6, renames to `%x0..%x5`; `mentionOkX` decides renameability; `rewrite` turns `R_LD`/`R_ST` of a float slot into `R_XTOG`/`R_GTOX`; `fuse` peephole makes `R_FBINX`, `R_GETRETF`, `R_POPX` (guarded by `tempDeadAfter`); `R_XVAR` after the first line, `R_XRELOAD` after `@catch_` labels. New `R_*` float instructions must be added to `mentionOkX`/`VALUE_OPS` or the slot stays in memory. Test: `tests/regvars_float_test.caspien`.

## New: `RegVarPromotionPass` -- variables in registers (`variables-in-registers: on|off`)

Full account in the root `CLAUDE.md` (top section). `AddressLoweringPass` rewrites the Optimizer's `REGVAR name weight` into `REGHINT offset size weight` (offset/size from the frame layout; a name with no slot is dropped). `RegVarPromotionPass` runs last (after `RegisterFormPass`, only rename work on the final text): per function, bans hinted slots with any mention it cannot rename (`mentionOk`), picks the top `VAR_COUNT` = 2 by weight, and renames `$off` to `%v0`/`%v1`; `R_LD` -> `R_MOV 8`, `R_ST` n==8 -> `R_MOV`, narrow `R_ST` -> `R_SETV n %vK src`. Skips functions containing a `R13_R14_USERS` mnemonic (NEW*, CLONE, RESIZE*, DOT, LOOKUP_ARRAY, ASM_*). It always strips `REGHINT`/`REGVAR`; with the switch off (or `deferred-operands: off`) nothing else changes, so output is byte-identical to before. `CompilerConfig.variablesInRegisters` parses the switch. When adding a new `R_*` instruction, add it to `mentionOk` (default is "ban"), or promoted slots simply stay in memory. Tests: `tests/regvars_test.caspien`, `tests/callee_saved_probe.sh`.

## New: `RegisterFormPass` stage 2 (float, narrow-integer, pointer operands)

Full account in the root `CLAUDE.md` (top section). `RegisterFormPass.java` (~850 lines) now also emits `R_LD`, `R_ST`, `R_LEA`, `R_FBIN`, `R_FCMP`, `R_RMW`, `R_ARG`, `R_ARGA`, `R_FARG`, `R_RET`, `R_RETF` (list at the top of the file). Entries K/M/A/T carry a size; regions containing anything beyond plain 8-byte ints are "risky" and are rolled back to their original lines if they hit an unfusable instruction with entries remembered. `ASSIGN` of an 8-byte frame store still emits the legacy `R_MOV`. Call brackets flush the model. Regression tests: `tests/regform_test.caspien`, `tests/regform2_test.caspien`.

## New: `RegisterFormPass` (deferred operands, phase 1)

Full account in the root `CLAUDE.md` (top section). New file `RegisterFormPass.java`, run last in `LowerOrderGenerator.generate` (after address lowering) only when `compiler.config` says `deferred-operands: on` (`CompilerConfig.deferredOperands`, default false; `on|off` is the only accepted spelling). It keeps a compile-time model of the operand stack (entries K const / M frame-slot value / A slot address / T temp) and emits `R_MOV`/`R_BIN`/`R_UN`/`R_BRF`/`R_PUSH`/`R_PUSHA`. Every unfused instruction first flushes the model; `flush(true)` uses register-form pushes so a live temp is never clobbered. Fusable mnemonics are in the `handlers` map; a handler that finds no free temp declines and the caller flushes.

## New: `SAR` for signed `bits_right`

`AddressLoweringPass`'s SHL/SHR/BITS_OR rule now emits `SAR size` instead of `SHR size` when the left operand type is a signed integer (arithmetic shift); unsigned stays `SHR`. Not executed on Windows.

## New: integer tightening (`TRUNC`, signed compare/divide mnemonics)

Full account in the root `CLAUDE.md` (top section). What changed in THIS component:
- `AddressLoweringPass`: a comparison (`GT`/`LT`/`GT_EQ`/`LT_EQ`) or `DIV`/`MOD` whose left operand is `s8`/`s16`/`s32`/`s64` now lowers to `SGT_INT`/`SLT_INT`/`SGT_EQ_INT`/`SLT_EQ_INT`/`SDIV_INT`/`SMOD_INT size` (helper `isSignedIntBaseType`); unsigned and float operands lower exactly as before, `EQ`/`NEQ` are unchanged.
- The `SEXT`/`ZEXT` rewrite also covers `TRUNC srcType dstType` (-> `TRUNC srcSize dstSize`).

## Fixed: `NEW` carries the struct layout; `sizeOf` no longer misreads a generic struct's mangled name

Full account in the root `CLAUDE.md` (top section). What changed in THIS component (`AddressLoweringPass.java`):
- The lowered `NEW` now carries the struct's layout after the size: `NEW <size> m8 m8 p4 ...` (`m<n>` = member of n bytes, `p<n>` = n padding bytes), read by Codegen's `emitNewRepack`. Only emitted for a plain (no storage) struct type that the struct table knows.
- `Sizes.sizeOf(String)`: a name the struct table knows is used whole. Previously a generic instantiation's mangled name (`HashMapEntry_u64`) was parsed as `mutability_baseType`, giving 8 bytes instead of 32, so `RESIZE`/dynarray indexing of a generic struct element used the wrong element size.
- `DropGlueGenerationPass` is unchanged: its bare `PUSH`/`CALL __drop_T` shape is now handled in Codegen (`emitDropGlueCall`).

## Fixed: a space char literal (`' '`) was split into two tokens by every stage's bytecode parser -- Codegen crashed on it

Found while checking whether `stdlib/string.caspien` compiles end to end: `String.empty()` uses `mut ' '`, which reaches the bytecode as `PUSH ' ' indeterminate_char`. The Optimizer's, LowerOrderGenerator's and Codegen's `BytecodeParser` are identical copies that split a line on whitespace and only kept a `"..."` string whole, so `' '` became two stray `'` tokens. In LowerOrderGenerator that meant the line was never lowered (every other char literal became e.g. `PUSH 1 '\0'`, this one stayed `PUSH ' ' indeterminate_char`), and in Codegen `X86Backend` then died with `NumberFormatException: For input string: "'"`. Result: any program importing `stdlib/string.caspien` could not be linked, even if it never used `String`.

Fix, applied identically to all three `BytecodeParser.parseLine` copies: a token starting with `'` that has the exact char-literal shape `BytecodeEmitter.escapeForBytecode` produces (quote, one character or backslash-plus-one, closing quote, ending the token) is now kept as ONE token, the same way a quoted string is. Char literals without whitespace produce exactly the same single token as before, so only the `' '` case changes.

Verified: `stdlib/string.caspien` now goes through all four stages and links; the space literal now lowers to `PUSH 1 ' '` like every other char literal; Hello World output is unchanged. No regression fixtures (`examples/`) were available in the master code files to sweep.

## `instanceof`/`implements` proof-narrowing now resolves a subclass-only member access

The remaining half of this compiler's own documented "instanceof/
implements proof-relaxation" gap: `match b instanceof Sub{ let z = mut
b.y }` (`y` declared only on `Sub`, `b` declared as `Base`) already
**type-checked** correctly -- `TypeChecker`'s `narrowInstanceofSlots`
rebinds `b`'s own scope-local `TypeInfo` to `Sub` for the branch, exactly
the mechanism `cast x as Y{...}` already uses, and `checkDot` genuinely
resolves `b.y` against that narrowed type. But the fact that `b` was
narrowed never survives past that point: `BytecodeEmitter.emitDot`'s own
`qualifiedDotName` builds the emitted dotted chain ("PUSH b.y mut_u64")
purely from each node's own source text, never consulting
`op.left.resolvedType` -- so the higher-order bytecode this compiler
produces is completely correct and complete on its own terms, but
carries no trace of "b is Sub here, not Base" in any form. By the time
this pass's own `AddressLoweringPass.resolveAddress`/`memberLocOf` sees
"b.y", `localTypes.get("b")` is unconditionally `b`'s own *declared*
type ("Base") -- a single, per-function, per-name fact with no
per-branch narrowing concept at all -- so walking `Base`'s own real
layout can never find a member `Base` itself doesn't declare, and the
dotted text is left completely unresolved, falling through to
`X86Backend`'s generic `TODO(codegen): ... not a declared global`
placeholder. Confirmed directly against
`instanceof_narrowing_member_access_test.caspien`, exactly the fixture
this compiler's own header already named for this shape.

**The fix works structurally, not by threading the narrowed type through
three separate bytecode formats.** This format has no explicit "extends"
declaration at all -- a child struct's own `STRUCT_START`/`STRUCT_MEMBER`
block is already fully flattened by the compiler (parent's own fields,
then the child's own additional ones -- see the sibling
`caspien-compiler` project's own CLAUDE.md), and single inheritance is a
hard compiler-level rule. Put together, "does struct S extend Base"
is always recoverable purely by comparing two already-known layouts:
S extends Base (directly or transitively -- flattening makes this work
for a grandchild too, with no recursion needed) exactly when S's own
real layout begins, entry for entry, with all of Base's own layout, as
a strict prefix.

`AddressLoweringPass.memberLocOf` now falls back to exactly this scan
(`memberLocViaExtendingStruct`, new) whenever a direct lookup on the
declared type fails: walk every struct name `StructTable` knows about
(a new `StructTable.allStructNames()` accessor, since nothing needed to
enumerate every known struct before this), find every one whose own
layout is a strict prefix-extension of the declared type's own layout,
and see whether any of them genuinely declares the member being looked
up. Exactly one qualifying match resolves the access; the whole
resolution is deliberately abandoned (falls back to `null`, the same
"don't guess" precedent every other gap in this pass already follows)
the moment more than one differently-shaped match is found, since
nothing survives to this stage that could say which extending struct was
actually proven true at the real `instanceof`/`implements` site --
flagged as a genuine, real limitation: two different direct children of
`Base` independently declaring their own, differently-offset field with
the same name would silently produce an unresolved (never a wrong)
access. Every currently known real fixture exercising this shape has
only one qualifying extending struct, so this has not yet been a
practical problem; a fully unambiguous fix would still need the
narrowed type threaded through explicitly (`op.left.resolvedType` is
already computed and available at `BytecodeEmitter` emission time, just
never written down) -- left as a follow-up, not attempted here.

A second, narrower safety note worth recording (not a bug in this fix,
a limit of what "instanceof narrowing" can mean for an **inline**,
non-pointer value): `instanceof_narrowing_member_access_test.caspien`'s
own `b` is a plain inline `Base` value, allocated with only room for
`Base`'s own 16 bytes -- so the narrowed access this fix now resolves
computes an address 16 bytes past `b`'s own allocated frame slot (into
whatever else happens to sit there). This is provably never actually
reached at runtime for an inline value: `b`'s own classId is fixed at
construction time to `Base`'s own id, so the `instanceof Sub` check
this branch guards can never actually be true for a plain inline `Base`
-- only a **pointer**-typed narrowing (a `raw`/`ref imut Base` that
might genuinely point at a real, correctly-sized `Sub` heap/stack
allocation) can ever be reached this way in practice, and for that case
the underlying memory really is sized for the true runtime type, so the
computed offset is genuinely safe there. Confirmed, not merely assumed:
this exact fixture's own classId comparison requires `1..1` (Sub's own
singleton range) while `b`'s own real classId is `0` (Base's), so the
branch this fix resolves is unreachable dead code in this fixture by
construction, same as before this fix -- this fix only makes sure
*that* dead code lowers to something real instead of a TODO placeholder,
it does not (and could not, from this fixture alone) newly exercise the
pointer-narrowing case at runtime.

Verified: full 804-fixture corpus re-run before/after this change --
diffed directly -- shows exactly one fixture's own status changing
(`instanceof_narrowing_member_access_test`: `CODEGEN_TODO(1)` ->
clean), zero other fixtures affected in either direction. Verified
through the real shipped orchestrator on both `linux` and
`windows_gnu`/Wine: compiles with zero TODOs, runs to completion, exit
code 0 (no output -- this fixture prints nothing, per its own design).

## `instanceof`/`implements` now lower correctly for an interface-typed left operand

Previously, `tryRewriteInstanceofOrImplements`'s own eligibility gate
required `structTable.membersOf(structName)` (`structName` being the
left operand's own declared base type) to come back non-null *and*
carry a real `___type` field, before it would ever build the runtime
classId comparison at all. An interface carries no `STRUCT_START`/
`STRUCT_MEMBER` block of its own in `StructTable` -- only a concrete
struct does -- so `membersOf` always returned `null` for an
interface-typed left operand, the gate always bailed, and the original,
raw `INSTANCEOF`/`IMPLEMENTS` bytecode was always left standing
untouched. Since `X86Backend` has no case for either mnemonic at all
(the whole design has always been "this pass rewrites them away before
codegen ever sees them"), an interface-typed left operand's own
`instanceof`/`implements` always fell through to a generic
`TODO(codegen): not yet implemented` placeholder -- a genuine, live gap,
not just a documentation note (confirmed directly against
`interface_typed_instanceof_test.caspien`, which this pass's own header
already named as exercising exactly this shape).

**The actual insight that fixes this, confirmed directly**: "the code
generator shouldn't care if the lefthand is struct-typed or
interface-typed -- it's just a pointer to get the `___type` from."
Whatever an interface reference actually points to at runtime is always
some real, concrete struct object, and every real struct's own hidden
`___type` classId field is always at a fixed offset -- **0** -- by the
compiler's own "`___type` must be first" invariant (see the sibling
`caspien-compiler` project's own CLAUDE.md, "The hidden `___type` field
... must be first"). That invariant holds regardless of which *static*
type (the concrete struct itself, or an interface with no layout of its
own at all) was used to reach the pointer -- so there was never a real
reason for this lowering to need the interface's own struct layout in
the first place.

**Two-part fix**, mirroring where the struct-layout dependency actually
lived:
- `tryRewriteInstanceofOrImplements` no longer bails when
  `structTable.membersOf(structName)` is `null` -- `checkInstanceof`/
  `checkImplementsOperator` already guarantee `structName` names a real
  struct or a real interface by the time this bytecode exists, so `null`
  here means "interface," never "unresolvable name." The `hasTypeField`
  check is kept, but now only fires when `members` is non-null -- a
  purely defensive guard against a real, *registered* struct somehow
  missing its own `___type` field (should never happen, given the
  compiler's own invariant).
- `AddressLoweringPass.memberLocOf` now resolves a `"___type"` member
  lookup directly and unconditionally -- offset 0, type `imut_u64` --
  *before* falling through to the ordinary `pseudoLayoutOf`/layout-walk
  logic below it, which would otherwise fail outright for an interface
  (no registered layout to walk at all). This changes nothing for the
  already-working concrete-struct case (walking a real struct's own
  layout already produced this exact same "(0, imut_u64)" answer, since
  `___type` is always that struct's own first member) -- it only adds
  the missing, structure-agnostic fallback for a base type
  `StructTable` has never heard of.

**Verified**: `interface_instanceof_dispatch_cg_test.caspien` (new
fixture -- two structs implementing a shared interface, dispatched via
`match c implements C.enum{ A:{...} B:{...} }` through an
interface-typed `c` parameter) compiles with zero `TODO(codegen)` lines
and correctly prints `a=1 b=2`, verified through the real shipped
orchestrator on both `linux` and `windows_gnu`/Wine. Three
pre-existing fixtures that also exercise interface dispatch internally
(`interface_dispatch_test`, `interface_dispatch_by_hand_test`,
`interface_dispatch_default_fallback_test`) went from carrying real
`TODO(codegen)` lines to zero, as a direct side effect of this same
fix -- confirmed by a before/after diff of the full example corpus
(357 -> 360 fixtures compiling with zero codegen TODOs, no other
fixture's status changed). `interface_typed_instanceof_test.caspien`
and `interface_dispatch_test.caspien` themselves still can't be run to
completion end-to-end -- **not** because of this fix, but because both
import the real stdlib's `gt_register`/`gt_init` ghost-table routines,
which have their own, separate, pre-existing runtime bug (confirmed
directly: a minimal `new A{...}` with zero `instanceof`/interface
content at all, importing that same real stdlib, segfaults inside
`__gtWriteSlot` identically) -- flagged here, not fixed, out of scope
for this round. The new regression fixture sidesteps this by using
hand-written `camelCase` ghost-table stubs instead, the same pattern
several other fixtures in this corpus already use for the identical
reason.

**Still open, not addressed by this fix** (unchanged from before):
`instanceof_narrowing_member_access_test` still carries one real,
separate `TODO(codegen)` (`PUSH of 'b.y' (not a declared global)`) --
the already-flagged "instanceof/implements proof-relaxation" gap
(member access isn't narrowed by an active instanceof-proof yet, so
`b.y` inside a proven-`B` match arm still isn't treated as a safe,
resolvable access). `class_hierarchy_enum_test`/`nested_enum_test`'s
own `TODO(codegen): PUSH of 'Class.X'/'Hierarchy.X' (not a declared
global)` lines are a wholly unrelated gap (pushing a bare class-enum
member name as a value), untouched by this fix.

## `MembershipLoweringPass`'s `IN`/`WITHIN` eligibility gates -- 4 narrow call sites widened to match a 5th, already-correct one

Found while verifying the sibling `caspien-compiler` project's new
`range(dynarray)` shape (see that project's own CLAUDE.md, "`RANGE`
retired"): `for i in range(d)` compiled cleanly through every stage but
never actually rewrote to a real bounds check at runtime, because this
pass's own `IN`/`WITHIN` rewrite-eligibility checks only ever recognized
a bounds-known `range(...)`-prefixed type (`rightBaseType.startsWith
("range(")`), never the bare, boundless `range` type a dynarray-sourced
range (or a `range`-typed function parameter) actually carries.

This was **not** a gap in the actual rewrite logic -- `buildRangeCheck`/
`buildRangeSubsetCheck` never read a range's own type text for its bounds
at all; they always emit real, generic dotted-field reads
(`PUSH rangeName.start`/`.end`), fully correct and fully generic
regardless of whether the compiler happened to know the bounds ahead of
time. The bug was purely in the narrower question of *whether* to invoke
that already-correct mechanism -- a distinction worth being precise
about, since a first, too-broad reading of this bug (understandably,
from the outside) could look like "the IN/WITHIN operator never really
worked at all." It did; only four of its five eligibility checks were
too narrow to reach it for this one type shape.

**This exact class of bug had already been found and fixed once**, at a
fifth call site (`tryRewriteIs`'s "r is base" RANGE_PARAM rewrite, ~line
1018) -- its own doc comment already explains the fix and even predicted
this exact remaining gap ("WITHIN's incomplete-range case," per
`caspien-codegen`'s own class-level doc comment) -- it just hadn't been
propagated to the other four sites yet. Fixed by widening all four to
match that fifth site's own pattern exactly:
`baseType.equals("range") || baseType.startsWith("range(")`, at:
`tryRewriteIn`'s Shape 2/3 (`rightIsRange`), Shape 4 (both
`leftIsRangeShaped`/`rightIsRangeShaped`), and `tryRewriteWithin`'s
defensive assertion.

`AddressLoweringPass` needed no fix -- its own three `.start`/`.end`
pseudo-member resolution call sites already used this exact widened
pattern correctly; only this file's own eligibility gates had regressed
behind it.

Verified via `examples/range_dynarray_membership_cg_test.caspien` (the
sibling `caspien-compiler` project's own new fixture) run end to end
through the full pipeline: `for i in range(d)` now genuinely lowers to a
real bounds check and executes correctly (`i=0 v=10` / `i=1 v=20` /
`i=2 v=30` / `i=3 v=40`), and via re-checking
`membership_lowering_manual_check_test.caspien` (pre-existing) for
regressions (none).

## `ADDR_OF`-drop rule was over-broad -- narrowed to the two real cases

Found via a real segfault (a null-pointer write inside stdlib's own
`gtReadSlot`), only ever exercised once `caspien-compiler`'s new
`GT_REGISTER` emission (see that project's own CLAUDE.md) made the
ghost table's `len` become greater than 0 for the first time ever.
`AddressLoweringPass`'s "drop `ADDR_OF` entirely, it's a no-op retag"
rule used to fire whenever the immediately preceding line's own
trailing type carried *any* storage keyword at all (`storage != null`).
That's too broad: it was only ever meant to cover two real shapes --
`REF` re-aliasing an existing `owns` value, and `RAW` on an
already-hoisted string literal (`storage == "static"`) -- but it also,
incorrectly, matched a third, unrelated shape: `raw`/`auto` on a plain
*local variable* whose own declared type simply happens to be
pointer-typed (`gtReadSlot`'s own `let val: raw mut u8 = null; memcopy(raw
val, mut 8, slot)`, among others). For that third shape, `val` is an
ordinary addressable stack slot needing a real, computed address, not a
no-op retag of whatever pointer value currently happens to sit inside
it (which, before that address is ever written, is `null` -- exactly
the wrong value `gtReadSlot`'s own `memcopy` was reading through as its
destination).

Fixed by replacing the blanket `storage != null` check with a new
`isAlreadyCorrectPointerRetag(opText, precedingStorage)` helper that
checks the actual `(opText, storage)` pair -- `("REF", "owns")` or
`("RAW", "static")`, nothing else -- so a plain pointer-typed local
still gets a real, computed address below, exactly like a
non-pointer-typed one always did. Verified against the pre-existing
`addr_of_lowering_test.caspien` fixture (no regression: none of its
four existing shapes hit the narrowed condition any differently than
before) and via direct low-order-bytecode inspection: `gtReadSlot`'s
own `ADDR_OF RAW $-16` line is now correctly preserved instead of being
silently dropped.

## New project: split out of caspien-optimizer

This project is the real lowering half of what used to be a single,
combined `caspien-optimizer` project, confirmed directly: "separate the
optimizer into two programs -- the shallow methods architecture at the
start of the optimizer will be extracted and be the optimizer, and the
actual work thats currently been done in the optimizer will just be
called the LowerOrderGenerator."

**This project contains every pass that does real, non-no-op work**:
`MembershipLoweringPass`, `CloneGenerationPass`, `DropGlueGenerationPass`,
`ArgToAllocLoweringPass`, `AddressLoweringPass` -- run in that fixed
order, once each, by `LowerOrderGenerator.generate(...)`. This is
exactly the tail of the old combined project's own pipeline (everything
from "membership lowering" onward), moved verbatim, package renamed
from `caspien.optimizer` to `caspien.lowerorder`. `StructTable`,
`EnumTable`, `CanonicalType`, and `CompilerConfig` moved with it, since
these five passes are the only things in the old combined project that
ever referenced any of those four classes (confirmed directly by grep
before splitting) -- the sibling `caspien-optimizer` project's own
shallow passes never touched any of them and so needed no new
dependency to keep working without this project.

**Not included here**: the two reordering passes
(`StructMemberReorderingPass`, `VariableAllocationReorderingPass`).
Both moved to the sibling
`caspien-optimizer` project instead -- see that project's own CLAUDE.md,
"Split into two programs," for the full reasoning (short version: their
originally-intended position, immediately after ARG-to-ALLOC lowering
and before address lowering, no longer exists as an in-process "middle"
now that the two passes either side of it live in a different program,
reached only through plain-text bytecode -- so they were placed at the
end of the *other* project's own pipeline instead, as the closest
available equivalent, and flagged as worth revisiting once either
grows real logic).

**Verified end to end**: `caspien-compiler`'s own output, piped through
`caspien-optimizer`'s `Main`, then through this project's own `Main`,
produces byte-for-byte identical low-order output to what the single,
pre-split combined optimizer used to produce directly.

## Usage

    lowerordergenerator -i input.txt output.txt

`input.txt` is expected to be the sibling `caspien-optimizer` project's
own plain-text output (bytecode that has already reached that project's
shallow-pipeline fixed point) -- this project does not re-run any of
that project's own passes itself, and does not require it to have run
first at the type level (it's still just plain-text bytecode in), only
by convention.

`AddressLoweringPass` still requires `compiler.config` (fixed path,
resolved relative to the current working directory) for calling-
convention data -- unchanged from before the split, missing entirely is
a fatal error there. A copy of `compiler.config` lives at this
project's own root for that reason.

## Files

- `BytecodeToken.java`, `BytecodeParser.java`, `BytecodeSerializer.java`
  -- parsing/reserializing the plain-text bytecode interchange format
  (own copy, package `caspien.lowerorder` -- identical in substance to
  the sibling `caspien-optimizer` project's own copy, per the
  no-shared-code convention every stage in this toolchain already
  follows)
- `OptimizationPass.java`, `PassResult.java` -- the shared pass
  interface (own copy, same reasoning)
- `CanonicalType.java` -- canonical type-string parsing/size lookups
- `StructTable.java` -- reads `STRUCT_START`/`STRUCT_MEMBER`/
  `STRUCT_PADDING`/`STRUCT_END` blocks
- `EnumTable.java` -- reads `ENUM`/`ENUM Class`/`ENUM ClassID` blocks
- `CompilerConfig.java` -- loads/parses `compiler.config` (calling
  conventions)
- `MembershipLoweringPass.java`, `CloneGenerationPass.java`,
  `DropGlueGenerationPass.java`, `ArgToAllocLoweringPass.java`,
  `AddressLoweringPass.java` -- the real lowering pipeline, unchanged
  from the pre-split combined project (see each file's own header for
  full design)
- `LowerOrderGenerator.java` -- wires the pipeline together
- `Main.java` -- CLI entry point
- `compiler.config` -- calling-convention data `AddressLoweringPass`
  needs

## Provenance

This project was originally split out of a single, combined
`master_code.md` that used to hold all three pipeline stages (compiler,
optimizer, codegen) together, then split a second time out of that
combined optimizer stage. It is now one of four sibling projects:

- **caspien-compiler** -- code (.caspien) -> bytecode (text)
- **caspien-optimizer** -- bytecode -> bytecode (shallow methods only,
  currently all no-op passes)
- **caspien-lowerordergenerator** (this project) -- bytecode ->
  low-order bytecode (the real lowering work)
- **caspien-codegen** -- low-order bytecode -> x86 assembly (config-
  driven target; under construction)

Each stage's plain-text bytecode is the interchange format between
them -- there is no shared library, no shared build, no shared repo.

## New: f64 support

`RegisterFormPass`, `RegVarPromotionPass` and `FloatTempPass` treat 8-byte floats like f32 with the width carried in every float register-form line; `REGVAR name weight f` hints cover both widths. See the root CLAUDE.md.
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

### FILE: src/main/java/caspien/lowerorder/AddressLoweringPass.java
```java
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
```

### FILE: src/main/java/caspien/lowerorder/ArgToAllocLoweringPass.java
```java
package caspien.lowerorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * "ARG-to-ALLOC lowering" -- the transformation that erases the
 * compiler's own bare `ARG name type` signature-annotation line
 * entirely, replacing it with a real `ALLOC name type` frame slot plus
 * an explicit load sequence moving the parameter's actual incoming
 * value into it. Confirmed directly this belongs here, in the optimizer,
 * not in the compiler: "i just said i wanted the ARG to ALLOC
 * conversions to take place in the optimizer" -- an earlier version of
 * this change lived entirely in `caspien-compiler`'s own
 * `BytecodeEmitter` (`emitParamAllocs`/`emitParamLoads`), which was
 * wrong from the start. `ARG name type` is a pure signature fact (a
 * name and a canonical type, nothing more) the type checker already
 * fully owns; turning it into a real frame address and a load sequence
 * is genuine high-order-to-low-order lowering work, the same category
 * of work `AddressLoweringPass` already does for every other name/type
 * in the program. This pass runs immediately before `AddressLoweringPass`
 * in `BytecodeOptimizer.optimize` -- after it, every parameter is just
 * an ordinary `ALLOC`'d local as far as the rest of the pipeline is
 * concerned, with no special-casing left anywhere else.
 *
 * Per parameter, in declared order: `ADDR name type`, then one
 * `PUSH ARGn type` per word this parameter's canonical type breaks down
 * into (`n` a simple, uniformly incrementing logical word index across
 * this *function's entire parameter list* -- never reset at a register-
 * count boundary, since this pass has no calling-convention data of its
 * own and doesn't need any: deciding which `ARGn` values are register-
 * transferred versus stack-passed, and computing a stack-passed word's
 * own real, positive base-pointer-relative offset, is entirely
 * `AddressLoweringPass`'s job, immediately after this one -- see that
 * pass's own "Parameter word classification" doc comment), then one
 * `ASSIGN type type type`. No new mnemonics: `PUSH ARGn` is the same
 * plain, pre-existing `PUSH` every literal/local push already uses.
 *
 * **Frame position, the one genuinely tricky part:** every parameter's
 * own `ALLOC` must land "the first of the ALLOCs for that function" --
 * ahead of every ordinary local's own `ALLOC` (wherever in the body its
 * `let` actually appears), but still *after* `gt_routine_address`'s own
 * single reserved-slot `ALLOC` when that's present at all (its own
 * fixed, function-count-independent offset from the base pointer this
 * project already depends on in every frame would otherwise shift by
 * however many parameters each individual function happens to declare).
 * The compiler emits `ARG` lines textually *before* `gt_routine_address`
 * ever appears (right after `RETURNS`, the identical position the
 * original, pre-ARG-to-ALLOC `ARG` line always occupied) -- so this pass
 * can't just replace the `ARG` lines in place; it has to remove them
 * from there and re-insert the synthesized parameter `ALLOC`s at the
 * *correct* frame position instead:
 *
 *   1. Copy the function's own header lines (`FUNC_START`, `EXPORT`,
 *      every `FUNC_DECORATE`, `RETURNS`) through unchanged, collecting
 *      each `ARG name type` line's `name`/`type` instead of copying it
 *      (so no `ARG` line survives into the output at all).
 *   2. If the very next line is `ALLOC gt_routine_address code_addr`,
 *      copy it through first, then insert every parameter's own `ALLOC`
 *      right after it. Otherwise (a program using no `owns`/`ref`/
 *      `dyn`/`new`/`throw` anywhere gets no `gt_routine_address` `ALLOC`
 *      in *any* function at all -- see the sibling `caspien-compiler`
 *      project's `emitGtRoutineAlloc`) insert the parameter `ALLOC`s
 *      right there instead, ahead of whatever comes next (an ordinary
 *      local's own `ALLOC`, or straight into the function's body if it
 *      has no locals either).
 *   3. Copy through the rest of the function's own already-contiguous
 *      `ALLOC` run (ordinary locals, "Whole-function ALLOC hoisting"
 *      already guarantees these are all contiguous from here) --
 *      now immediately preceded by the parameter `ALLOC`s from step 2,
 *      so the whole run stays one single contiguous block.
 *   4. Insert every parameter's own load sequence (`ADDR`/`PUSH ARGn`/
 *      `ASSIGN`) right there, in declared order -- "after all the
 *      ALLOCs, but before any routines," the identical placement the
 *      compiler-side version of this used to use, now enforced here
 *      instead.
 *   5. Copy the rest of the function (the gt_routine body, if any, then
 *      the real statement body, then `FUNC_END`) through unchanged.
 *
 * **Word count per parameter** (`argWordTypesOf`): a hand-duplicated
 * copy of `BytecodeEmitter`'s own identical logic (this project has no
 * dependency on the compiler's code -- see CLAUDE.md, "What this
 * project is" -- so this is a second, independently-kept-in-sync copy
 * of the same structural predicate, purely string-based, needing
 * nothing beyond the canonical type string already sitting in the `ARG`
 * line's own text): a by-value range (no storage prefix at all,
 * immediately `imut`/`mut`/`indeterminate_range(...)`) is two words,
 * both untyped `indeterminate_u64` (`.start`/`.end`); anything else
 * (including a storage-bearing, single-word pointer to a range -- never
 * split, unlike a genuine by-value one) is exactly one word, of its own
 * type. (This used to also split a by-value slice argument into three
 * words -- its own origin type, then the same two untyped bounds; the
 * slice type has been removed from the language entirely, so that case
 * is gone along with it.)
 */
public class ArgToAllocLoweringPass implements OptimizationPass {

    private static final BytecodeParser PARSER = new BytecodeParser();

    private static final Pattern BY_VALUE_RANGE_ARG =
            Pattern.compile("^(?:imut|mut|indeterminate)_range(?:\\(.*\\))?$");

    @Override
    public String name() {
        return "arg-to-alloc-lowering";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        List<List<BytecodeToken>> out = new ArrayList<>();
        boolean changed = false;

        int i = 0;
        int n = lines.size();
        while (i < n) {
            List<BytecodeToken> line = lines.get(i);
            if (line.isEmpty() || !line.get(0).text.equals("FUNC_START")) {
                out.add(line);
                i++;
                continue;
            }

            int start = i;
            int end = start;
            while (end < n && !(lines.get(end).size() > 0 && lines.get(end).get(0).text.equals("FUNC_END"))) {
                end++;
            }
            end = Math.min(end, n - 1);

            int before = out.size();
            lowerFunction(lines, start, end, out);
            changed |= (out.size() - before) != (end - start + 1) || !sameLines(lines, start, end, out, before);

            i = end + 1;
        }

        return new PassResult(out, changed);
    }

    private boolean sameLines(List<List<BytecodeToken>> lines, int start, int end,
            List<List<BytecodeToken>> out, int outStart) {
        int len = end - start + 1;
        if (out.size() - outStart != len) {
            return false;
        }
        for (int k = 0; k < len; k++) {
            List<BytecodeToken> a = lines.get(start + k);
            List<BytecodeToken> b = out.get(outStart + k);
            if (a.size() != b.size()) {
                return false;
            }
            for (int t = 0; t < a.size(); t++) {
                if (!a.get(t).text.equals(b.get(t).text)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void lowerFunction(List<List<BytecodeToken>> lines, int start, int end, List<List<BytecodeToken>> out) {
        List<String> paramNames = new ArrayList<>();
        List<String> paramTypes = new ArrayList<>();

        int idx = start;
        while (idx <= end) {
            List<BytecodeToken> l = lines.get(idx);
            String mnemonic = l.get(0).text;
            if (mnemonic.equals("ARG") && l.size() >= 3) {
                paramNames.add(l.get(1).text);
                paramTypes.add(l.get(2).text);
                idx++;
                continue;
            }
            if (mnemonic.equals("FUNC_START") || mnemonic.equals("EXPORT")
                    || mnemonic.equals("FUNC_DECORATE") || mnemonic.equals("RETURNS")) {
                out.add(l);
                idx++;
                continue;
            }
            break;
        }

        boolean insertedParamAllocs = false;
        if (idx <= end) {
            List<BytecodeToken> l = lines.get(idx);
            if (l.size() >= 2 && l.get(0).text.equals("ALLOC") && l.get(1).text.equals("gt_routine_address")) {
                out.add(l);
                idx++;
                // the second reserved slot (present only in programs that use throw) stays right behind the first, so both sit at a
                // fixed offset (-8 and -16) in every frame: GT_UNWIND copies the error message up a frame through that fixed slot.
                if (idx <= end) {
                    List<BytecodeToken> m2 = lines.get(idx);
                    if (m2.size() >= 2 && m2.get(0).text.equals("ALLOC") && m2.get(1).text.equals("gt_error_message")) {
                        out.add(m2);
                        idx++;
                    }
                }
                emitParamAllocs(out, paramNames, paramTypes);
                insertedParamAllocs = true;
            }
        }
        if (!insertedParamAllocs) {
            emitParamAllocs(out, paramNames, paramTypes);
        }

        while (idx <= end) {
            List<BytecodeToken> l = lines.get(idx);
            if (l.isEmpty() || !l.get(0).text.equals("ALLOC")) {
                break;
            }
            out.add(l);
            idx++;
        }

        emitParamLoads(out, paramNames, paramTypes);

        while (idx <= end) {
            out.add(lines.get(idx));
            idx++;
        }
    }

    private void emitParamAllocs(List<List<BytecodeToken>> out, List<String> paramNames, List<String> paramTypes) {
        for (int i = 0; i < paramNames.size(); i++) {
            emit(out, "ALLOC " + paramNames.get(i) + " " + paramTypes.get(i));
        }
    }

    private void emitParamLoads(List<List<BytecodeToken>> out, List<String> paramNames, List<String> paramTypes) {
        // `wordIndex` stays one single, uniformly-incrementing counter
        // across the *whole* parameter list, regardless of which words
        // turn out to be "ARGn" vs "FARGn" -- this pass still has no
        // calling-convention data of its own and still doesn't need any
        // (see this class's own header): a word's real register-vs-stack
        // fate, and which specific register it gets, is entirely
        // AddressLoweringPass's job, immediately after this one, exactly
        // as it already was for a plain "ARGn" before this split. Picking
        // the mnemonic itself (ARG vs FARG) needs nothing beyond the
        // type already sitting right here on the word -- never inferred,
        // never new information, the same "the fact was always explicit
        // right here" property this pass already relies on for the word
        // count itself (`argWordTypesOf`).
        int wordIndex = 0;
        for (int i = 0; i < paramNames.size(); i++) {
            String name = paramNames.get(i);
            String canonical = paramTypes.get(i);
            emit(out, "ADDR " + name + " " + canonical);
            for (String wordType : argWordTypesOf(canonical)) {
                emit(out, "PUSH " + (isFloatCanonical(wordType) ? "FARG" : "ARG") + wordIndex + " " + wordType);
                wordIndex++;
            }
            emit(out, "ASSIGN " + canonical + " " + canonical + " " + canonical);
        }
    }

    /**
     * True for a real float base type -- "f32" only, the sole
     * floating-point primitive this language has (see the sibling
     * `caspien-compiler` project's own `TypeChecker.PRIMITIVE_SIZE`, and
     * this project's own `AddressLoweringPass.isFloatBaseType`, which
     * checks the identical fact off an already-parsed `CanonicalType`
     * instead of raw text -- this method exists separately because
     * `argWordTypesOf`'s own word types are always either a struct-free
     * scalar canonical string or the fixed literal "indeterminate_u64",
     * never something `CanonicalType.parse` needs for this check). A
     * canonical string always carries its real base type as the very
     * last segment (confirmed directly against `TypeInfo.canonical()`'s
     * own construction order, on the compiler side), so checking the
     * tail is exact.
     */
    private static boolean isFloatCanonical(String canonical) {
        return canonical.equals("f32") || canonical.endsWith("_f32") || canonical.equals("f64") || canonical.endsWith("_f64");
    }

    private List<String> argWordTypesOf(String canonical) {
        if (BY_VALUE_RANGE_ARG.matcher(canonical).matches()) {
            return Arrays.asList("indeterminate_u64", "indeterminate_u64");
        }
        return Collections.singletonList(canonical);
    }

    private static void emit(List<List<BytecodeToken>> out, String text) {
        out.addAll(PARSER.parse(Collections.singletonList(text), "<arg-to-alloc-lowered>"));
    }
}
```

### FILE: src/main/java/caspien/lowerorder/BranchFusionPass.java
```java
package caspien.lowerorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Compare-and-branch fusion on the final register-form text (always on; a pure code-quality rewrite). A comparison whose
 * 0/1 result is only used to decide a jump no longer materialises that value (setcc / movzbq / test / je); it compares and
 * jumps:
 *
 *   R_BIN C 8 %tD a b ; R_BRF %tD @L                                   ->  R_BRC C 8 a b @L
 *   R_BIN C1 8 %tX a b ; R_BIN C2 8 %tY c d ; R_BIN AND 1 %tR %tX %tY ; R_BRF %tR @L
 *                                                                       ->  R_BRC C1 8 a b @L ; R_BRC C2 8 c d @L
 *
 * "R_BRC C size a b @L" jumps to L when NOT (a C b). The second shape is the test of a `for` loop over a range
 * (start <= i and i < end); the two compares are pure reads, so evaluating the second only when the first held changes nothing.
 * Only 8-byte compares (narrow ones zero/sign-extend first and stay as they are), and only when the compare result temps are
 * dead afterwards, which the register-form model guarantees (a temp is never live across a jump or label). Runs after
 * RegVarPromotionPass and FloatTempPass so neither needs to know the new line.
 */
public class BranchFusionPass {

    private static final Set<String> CMP = Set.of("EQ", "NEQ", "LT", "LT_EQ", "GT", "GT_EQ", "SLT", "SLT_EQ", "SGT", "SGT_EQ");

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        int i = 0;
        while (i < lines.size()) {
            List<BytecodeToken> l = lines.get(i);
            // shape 2
            if (i + 3 < lines.size() && isCmp(l)) {
                List<BytecodeToken> l2 = lines.get(i + 1);
                List<BytecodeToken> l3 = lines.get(i + 2);
                List<BytecodeToken> l4 = lines.get(i + 3);
                if (isCmp(l2) && isAnd(l3) && isBrf(l4)) {
                    String x = t(l, 3), y = t(l2, 3);
                    String r = t(l3, 3);
                    boolean operands = (t(l3, 4).equals(x) && t(l3, 5).equals(y)) || (t(l3, 4).equals(y) && t(l3, 5).equals(x));
                    if (!x.equals(y) && operands && t(l4, 1).equals(r) && !mentions(l2, x) && !mentions(l, y)
                            && notBothImm(l) && notBothImm(l2)) {
                        // AND is commutative and both compares are pure reads: branch on the first, then on the second.
                        out.add(brc(l, l4));
                        out.add(brc(l2, l4));
                        i += 4;
                        continue;
                    }
                }
            }
            // shape 1
            if (i + 1 < lines.size() && isCmp(l) && isBrf(lines.get(i + 1)) && t(lines.get(i + 1), 1).equals(t(l, 3)) && notBothImm(l)) {
                out.add(brc(l, lines.get(i + 1)));
                i += 2;
                continue;
            }
            out.add(l);
            i++;
        }
        return out;
    }

    private static String t(List<BytecodeToken> l, int i) {
        return l.get(i).text;
    }

    private static boolean isCmp(List<BytecodeToken> l) {
        return l.size() == 6 && t(l, 0).equals("R_BIN") && CMP.contains(t(l, 1)) && t(l, 2).equals("8") && t(l, 3).startsWith("%t");
    }

    private static boolean isAnd(List<BytecodeToken> l) {
        return l.size() == 6 && t(l, 0).equals("R_BIN") && t(l, 1).equals("AND") && t(l, 2).equals("1")
                && t(l, 3).startsWith("%t") && t(l, 4).startsWith("%t") && t(l, 5).startsWith("%t");
    }

    private static boolean isBrf(List<BytecodeToken> l) {
        return l.size() == 3 && t(l, 0).equals("R_BRF") && t(l, 1).startsWith("%t");
    }

    private static boolean notBothImm(List<BytecodeToken> l) {
        return !(t(l, 4).startsWith("#") && t(l, 5).startsWith("#"));
    }

    private static boolean mentions(List<BytecodeToken> l, String tok) {
        return t(l, 4).equals(tok) || t(l, 5).equals(tok);
    }

    private static List<BytecodeToken> brc(List<BytecodeToken> cmp, List<BytecodeToken> brf) {
        BytecodeToken h = cmp.get(0);
        List<BytecodeToken> n = new ArrayList<>();
        n.add(new BytecodeToken("R_BRC", h.file, h.line, h.kind));
        n.add(cmp.get(1));
        n.add(cmp.get(2));
        n.add(cmp.get(4));
        n.add(cmp.get(5));
        n.add(brf.get(2));
        return n;
    }
}
```

### FILE: src/main/java/caspien/lowerorder/BytecodeParser.java
```java
package caspien.lowerorder;

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

### FILE: src/main/java/caspien/lowerorder/BytecodeSerializer.java
```java
package caspien.lowerorder;

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

### FILE: src/main/java/caspien/lowerorder/BytecodeToken.java
```java
package caspien.lowerorder;

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

### FILE: src/main/java/caspien/lowerorder/CanonicalType.java
```java
package caspien.lowerorder;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Parses a bytecode canonical type string ("storage_mutability_basetype",
 * e.g. "owns_mut_Box", "imut_u64", "raw_mut_u8") the same way the
 * compiler's own TypeChecker.TypeInfo.canonical() builds one -- see that
 * class's own doc comment for the format this mirrors exactly. Storage
 * is optional (absent for a plain stack-resident value); when present
 * it's always one of the five keywords below, stripped straight off the
 * front -- never present as part of an ordinary base type name, since
 * none of the five is a legal identifier prefix in real source.
 *
 * baseType itself is left otherwise unparsed here -- it can be a plain
 * primitive/struct name, or a composite shape written the same way the
 * rest of this bytecode already writes one: "elem[N]" for a fixed-size
 * array, "dynarray(elem)" for a dynarray, and so on, arbitrarily nested
 * (e.g. "dynarray(mut_u64)[2]"). The static helpers below peel off one
 * such wrapper at a time; callers that need to walk all the way down
 * recurse by re-parsing whatever text a helper hands back.
 *
 * This parser needs nothing from the compiler project -- every type a
 * downstream, standalone stage ever needs to reason about already
 * arrives as plain canonical text on the relevant bytecode line (a
 * STRUCT_MEMBER's own operand, an ALLOC/PUSH's own type operand),
 * matching this project's "no dependency on the compiler or codegen
 * stages" rule.
 */
public class CanonicalType {

    private static final Set<String> STORAGE_KEYWORDS =
            new HashSet<>(Arrays.asList("owns", "ref", "raw", "auto", "static"));

    public final String storage;    // nullable
    public final boolean isSome;    // "storage, then 'some', then mutability, then baseType" -- TypeChecker.TypeInfo.canonical()'s own emission order for a value explicitly annotated "some" (a definitely-present pointer, or -- the shape that surfaced this -- every string literal, e.g. "static_some_imut_string")
    public final String mutability; // nullable only if the input didn't match the expected shape at all
    public final boolean isAtomic;  // "storage?_some?_mutability_atomic?_floatStates?_baseType" -- TypeChecker.TypeInfo.canonical()'s own emission order puts "atomic" right after mutability, before baseType (and before any floatStates segment, which never co-occurs with atomic since ATOMIC_ELIGIBLE_BASE_TYPES excludes every float type). Previously unhandled here: this parser silently folded "atomic_X" straight into baseType as one string (e.g. baseType="atomic_char" instead of baseType="char", isAtomic=true), which meant sizes.sizeOf(...) never recognized the real base type and silently fell back to its generic 8-byte default for every atomic type narrower than 8 bytes (char/bool/u8/u16/u32) at every call site except ATOMIC_SWAP's own narrow, already-existing workaround (see AddressLoweringPass's ATOMIC_SWAP rewrite, which sidesteps this by reading size from a freshly-constructed plain TypeInfo instead of the atomic-tagged one). Fixed generally here instead, mirroring isSome's own exact stripping pattern.
    public final String baseType;
    public final String raw;

    private CanonicalType(String storage, boolean isSome, String mutability, boolean isAtomic, String baseType, String raw) {
        this.storage = storage;
        this.isSome = isSome;
        this.mutability = mutability;
        this.isAtomic = isAtomic;
        this.baseType = baseType;
        this.raw = raw;
    }

    /**
     * Format mirrors `TypeChecker.TypeInfo.canonical()` exactly:
     * "storage?_some?_mutability_baseType" -- storage and the "some" tag
     * are each independently optional, but when both are present, storage
     * always comes first (`TypeInfo.canonical()`'s own comment: "str:owns
     * some mut String" -- storage, then "some", then mutability").
     *
     * Found and fixed directly while lowering `IN_SCAN` (`MembershipLoweringPass`):
     * a real compiled string literal's own canonical type is always
     * "static_some_imut_string" (every string value in this language is
     * canonically "static" storage, and a literal additionally carries
     * "some" -- confirmed directly, "static_some" is not a two-word
     * storage keyword, "some" is its own separate, real segment). The
     * original two-underscore-split version of this parser had no idea
     * "some" could appear there at all, so it silently misparsed this
     * exact shape -- "some_imut_string" was split at *its own* first
     * underscore, yielding `mutability="some"` and `baseType="imut_string"`
     * (never "string") -- wrong for every caller here, and, confirmed
     * separately, for `AddressLoweringPass.lookupMnemonicFor` too (a
     * `LOOKUP` directly into a string literal was silently failing to
     * ever resolve to `LOOKUP_ARRAY` at all, a real, pre-existing,
     * previously-unnoticed gap this uncovered -- nothing before `IN_SCAN`
     * ever exercised a string literal as a `LOOKUP`/`IN`-family operand's
     * *right* side closely enough to hit it). Fixed generally, not just
     * for the one call site that surfaced it: an optional "some" segment
     * is now stripped, independently of storage, right after storage and
     * before the mutability/baseType split, mirroring `canonical()`'s own
     * emission order exactly.
     */
    public static CanonicalType parse(String canonical) {
        String rest = canonical;
        String storage = null;
        int firstUnderscore = rest.indexOf('_');
        if (firstUnderscore > 0) {
            String candidate = rest.substring(0, firstUnderscore);
            if (STORAGE_KEYWORDS.contains(candidate)) {
                storage = candidate;
                rest = rest.substring(firstUnderscore + 1);
            }
        }
        boolean isSome = false;
        int someUnderscore = rest.indexOf('_');
        if (someUnderscore > 0 && rest.substring(0, someUnderscore).equals("some")) {
            isSome = true;
            rest = rest.substring(someUnderscore + 1);
        }
        int secondUnderscore = rest.indexOf('_');
        String mutability;
        String baseType;
        if (secondUnderscore > 0) {
            mutability = rest.substring(0, secondUnderscore);
            baseType = rest.substring(secondUnderscore + 1);
        } else {
            // Doesn't match "mutability_basetype" at all -- fall back to
            // treating the whole remainder as the base type rather than
            // guessing at a split that isn't there.
            mutability = null;
            baseType = rest;
        }
        boolean isAtomic = false;
        if (baseType.startsWith("atomic_")) {
            isAtomic = true;
            baseType = baseType.substring("atomic_".length());
        }
        return new CanonicalType(storage, isSome, mutability, isAtomic, baseType, canonical);
    }

    public boolean isOwnsStorage() {
        return "owns".equals(storage);
    }

    public String dynArrayElementType() {
        return dynArrayElementTypeOf(baseType);
    }

    public String fixedArrayElementType() {
        return fixedArrayElementTypeOf(baseType);
    }

    public int fixedArrayLength() {
        return fixedArrayLengthOf(baseType);
    }

    /** "dynarray(elem)" -- elem's own canonical text, or null if `baseType` isn't a (safe) dynarray shape. */
    public static String dynArrayElementTypeOf(String baseType) {
        if (baseType.startsWith("dynarray(") && baseType.endsWith(")")) {
            return baseType.substring("dynarray(".length(), baseType.length() - 1);
        }
        return null;
    }

    /**
     * "unsafe_dynarray(elem)" -- elem's own canonical text, or null if
     * `baseType` isn't an unsafe dynarray shape. Deliberately a separate
     * helper from `dynArrayElementTypeOf`, not folded into it -- an
     * unsafe dynarray's own text is a genuinely distinct prefix
     * ("unsafe_dynarray(", never "dynarray(" -- see `TypeInfo.
     * unsafeDynArray`'s own comment in the compiler, "a distinct string
     * from the safe variant's own 'dynarray(...)', deliberately so the
     * two are never mutually coercible"), and, since the fix to
     * `lookupMnemonicFor` this accompanies, the two shapes now also need
     * genuinely different *addressing* treatment (see that method's own
     * doc comment) -- not just a different label on the same shape.
     */
    public static String unsafeDynArrayElementTypeOf(String baseType) {
        if (baseType.startsWith("unsafe_dynarray(") && baseType.endsWith(")")) {
            return baseType.substring("unsafe_dynarray(".length(), baseType.length() - 1);
        }
        return null;
    }

    /** "elem[N]" -- elem's own canonical text, or null if `baseType` isn't a fixed-size array shape. */
    public static String fixedArrayElementTypeOf(String baseType) {
        int[] bracket = fixedArrayBracketOf(baseType);
        return bracket == null ? null : baseType.substring(0, bracket[0]);
    }

    /** The declared length of a fixed-size array base type, or -1 if `baseType` isn't one (or the length isn't a plain literal). */
    public static int fixedArrayLengthOf(String baseType) {
        int[] bracket = fixedArrayBracketOf(baseType);
        if (bracket == null) {
            return -1;
        }
        String digits = baseType.substring(bracket[0] + 1, bracket[1]);
        if (digits.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) {
                return -1;
            }
        }
        return Integer.parseInt(digits);
    }

    private static int[] fixedArrayBracketOf(String baseType) {
        if (baseType.isEmpty() || baseType.charAt(baseType.length() - 1) != ']') {
            return null;
        }
        int open = baseType.lastIndexOf('[');
        if (open < 0) {
            return null;
        }
        return new int[]{open, baseType.length() - 1};
    }

    /** A version of `text` safe to splice into a generated label/function name -- every non-alphanumeric character becomes '_'. */
    public static String sanitizeForLabel(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }
}
```

### FILE: src/main/java/caspien/lowerorder/CloneGenerationPass.java
```java
package caspien.lowerorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
public class CloneGenerationPass implements OptimizationPass {

    private static final BytecodeParser PARSER = new BytecodeParser();

    @Override
    public String name() {
        return "clone-generation";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        return new Worker(StructTable.read(lines)).process(lines);
    }

    private static final class Worker {

        private final StructTable structTable;
        private final Set<String> neededPointerRoutines = new LinkedHashSet<>();
        private final Set<String> generatedPointerRoutines = new HashSet<>();
        private final Set<String> neededValueRoutines = new LinkedHashSet<>();
        private final Set<String> generatedValueRoutines = new HashSet<>();
        private int labelCounter = 0;
        private int tempCounter = 0;

        Worker(StructTable structTable) {
            this.structTable = structTable;
        }

        PassResult process(List<List<BytecodeToken>> lines) {
            List<List<BytecodeToken>> rewritten = new ArrayList<>();
            boolean changedAnyCallSite = false;

            int i = 0;
            int n = lines.size();
            while (i < n) {
                List<BytecodeToken> line = lines.get(i);
                if (line.isEmpty() || !line.get(0).text.equals("FUNC_START")) {
                    rewritten.add(line);
                    i++;
                    continue;
                }

                int start = i;
                int end = start;
                while (end < n && !(lines.get(end).size() > 0 && lines.get(end).get(0).text.equals("FUNC_END"))) {
                    end++;
                }
                end = Math.min(end, n - 1);

                for (int k = start; k <= end; k++) {
                    List<BytecodeToken> fl = lines.get(k);
                    if (!fl.isEmpty() && fl.get(0).text.equals("CLONE") && fl.size() == 3) {
                        String sourceType = fl.get(1).text;
                        List<String> replacement = tryRewriteClone(sourceType);
                        if (replacement != null) {
                            rewritten.addAll(PARSER.parse(replacement, "<generated-clone-glue>"));
                            changedAnyCallSite = true;
                            continue;
                        }
                    }
                    rewritten.add(fl);
                }

                i = end + 1;
            }

            boolean generatedAnyRoutine = !neededPointerRoutines.isEmpty() || !neededValueRoutines.isEmpty();
            while (!neededPointerRoutines.isEmpty() || !neededValueRoutines.isEmpty()) {
                while (!neededPointerRoutines.isEmpty()) {
                    String next = neededPointerRoutines.iterator().next();
                    neededPointerRoutines.remove(next);
                    if (generatedPointerRoutines.contains(next)) {
                        continue;
                    }
                    generatedPointerRoutines.add(next);
                    rewritten.addAll(generatePointerCloneRoutine(next));
                }
                while (!neededValueRoutines.isEmpty()) {
                    String next = neededValueRoutines.iterator().next();
                    neededValueRoutines.remove(next);
                    if (generatedValueRoutines.contains(next)) {
                        continue;
                    }
                    generatedValueRoutines.add(next);
                    rewritten.addAll(generateValueCloneRoutine(next));
                }
            }

            return new PassResult(rewritten, changedAnyCallSite || generatedAnyRoutine);
        }

        /** `sourceType` is `CLONE`'s own first operand -- the canonical type of whatever pointer is already sitting on top of the stack, now carried directly on the instruction itself rather than read off the preceding line. Returns the replacement instructions, or null to leave the original "CLONE" bytecode completely untouched. */
        private List<String> tryRewriteClone(String sourceType) {
            CanonicalType t = CanonicalType.parse(sourceType);
            if (!structTable.isOwnsBearing(t.baseType)) {
                return null; // flat -- a plain allocate-and-copy is already exactly correct
            }
            return ownsBearingCloneInstructions(sourceType);
        }

        /**
         * `canonicalType`'s own base type is already confirmed
         * owns-bearing, and its pointer value is already sitting on top
         * of the stack. Returns the instructions that turn it into a
         * freshly, deeply cloned replacement, or null if this is one of
         * the two documented gap shapes (see this pass's own header) --
         * the caller falls back to the plain, flat "CLONE" contract
         * either way, same as any other pointer whose pointee doesn't
         * need this at all.
         */
        private List<String> ownsBearingCloneInstructions(String canonicalType) {
            CanonicalType t = CanonicalType.parse(canonicalType);

            String dynElem = CanonicalType.dynArrayElementTypeOf(t.baseType);
            if (dynElem != null) {
                String elemRoutine = elementRoutineTagFor(dynElem);
                // The result is always "owns" storage, regardless of
                // what storage the source pointer itself had (a
                // dynarray borrowed via "ref", say, at a top-level
                // `clone()` call site) -- same reasoning as the struct
                // branch below.
                return buildCloneLoop(canonicalType, dynElem, elemRoutine, "owns_mut_" + t.baseType);
            }

            if (structTable.hasStruct(t.baseType)) {
                neededPointerRoutines.add(t.baseType);
                // The routine's own, fixed return type ("RETURNS
                // owns_mut_StructName" in generatePointerCloneRoutine
                // below) -- never the source pointer's own storage kind,
                // which (at a top-level `clone()` call site) can be
                // anything checkCloneBuiltin accepts ("raw", "ref", ...),
                // not necessarily "owns" at all.
                return Arrays.asList("CALL " + pointerRoutineNameFor(t.baseType),
                        "PUSH_RET owns_mut_" + t.baseType);
            }

            return null; // a bare owns pointer directly at a fixed array -- the same known gap DropGlueGenerationPass already has
        }

        /**
         * CLONE_LOOP's own "elemRoutine" operand for a dynarray element
         * of type `dynElem` (already known to need *some* form of
         * per-element recursion -- the caller only reaches here once
         * `structTable.isOwnsBearing` has already confirmed the whole
         * dynarray needs work) -- see this pass's own header for the
         * "NONE:PTR"/"PTR:.../VAL:..." tag contract.
         */
        private String elementRoutineTagFor(String dynElem) {
            CanonicalType elemT = CanonicalType.parse(dynElem);
            if (elemT.isOwnsStorage()) {
                if (structTable.hasStruct(elemT.baseType) && structTable.isOwnsBearing(elemT.baseType)) {
                    neededPointerRoutines.add(elemT.baseType);
                    return "PTR:" + pointerRoutineNameFor(elemT.baseType);
                }
                return "NONE:PTR"; // a leaf owns pointer -- still needs a fresh per-element CLONE, never a raw copy
            }
            // Not itself a pointer -- an inline struct value directly in
            // the buffer (dynArray()'s own element-type text never
            // carries storage at all, see this pass's own header), and
            // since the whole dynarray was already confirmed
            // owns-bearing, this inline element must be the reason why.
            neededValueRoutines.add(elemT.baseType);
            return "VAL:" + valueRoutineNameFor(elemT.baseType);
        }

        /**
         * Builds a real, generated JMP/CMP loop that deep-clones
         * `srcType`'s own dynarray elements into a freshly allocated
         * destination of the same runtime length, in place of the
         * original "CLONE_LOOP srcType elemType elemRoutine resultType"
         * contract this pass used to hand off to a not-yet-built codegen
         * stage (see this pass's own header for the full reasoning).
         * Precondition/postcondition mirror the single "value-producing
         * instruction" shape the "CLONE_LOOP ..." line it replaces always
         * had: the source pointer is already sitting on the stack
         * (pushed by whatever line precedes this call site -- the
         * original "PUSH ... srcType" line, left completely untouched),
         * and this method's own returned lines leave exactly the
         * freshly cloned destination dynarray (of type `resultType`) on
         * the stack when they finish, so every existing caller
         * (`tryRewriteClone`'s top-level splice, `emitClonedValue`'s
         * nested-member splice) keeps working unchanged.
         *
         * The one real wrinkle, not present in `DropGlueGenerationPass`'s
         * own "GT_LOOP" loop-building mirror
         * (`emitDynArrayElementDrop`): that pass's own loop only ever
         * *reads* the dynarray it walks (destruction never allocates),
         * whereas this one needs a *destination* dynarray of the exact
         * same runtime length as the source, with nothing existing in
         * this bytecode able to allocate one directly:
         *   - The already-pushed source pointer is captured into a
         *     fresh, named hidden temp via "POP name type" -- the only
         *     way this bytecode can give a bare stack value a name at
         *     all (the identical trick `MembershipLoweringPass`'s own
         *     `declareTemp`/`POP` already established for materializing
         *     a non-simple "in" operand) -- needed here because, unlike
         *     `DropGlueGenerationPass`'s `path` (always a real,
         *     resolvable name/dotted-chain), the value this method
         *     starts from is whatever the *caller's own* "PUSH ..." line
         *     already left on the stack, with no name of its own.
         *   - The destination is bootstrapped via "NEW_DYN dynarray(T) 0"
         *     (a fresh, empty dynarray -- "NEW_DYN dynarray(T) count"
         *     only ever takes a *compile-time-literal* count, the same
         *     shape a real "dyn([...])" array literal already uses,
         *     popping exactly that many already-pushed element values;
         *     "0" is the one literal count that needs no elements pushed
         *     at all, so it's the only way this already-general contract
         *     can produce a dynarray from nothing -- never itself
         *     emitted by the compiler, which has no "dyn([])" literal
         *     syntax, but a reasonable, unforced reading of an
         *     already-fully-specified contract, flagged here rather than
         *     silently assumed) -- then grown to the source's own
         *     runtime length via `resize`'s own *unsafe*, 2-argument,
         *     no-fill-value form -- emitted here as `URESIZE`, the
         *     mnemonic that shape now carries (`AddressLoweringPass`
         *     erases `RESIZE`/`URESIZE` differently: `RESIZE` reads its
         *     element size off a fill value that's guaranteed to exist;
         *     `URESIZE` has none, so it reads the same size off the
         *     dynarray operand's own declared element type instead --
         *     `AddressLoweringPass`'s own `URESIZE` rule tries
         *     `unsafeDynArrayElementTypeOf` first, then falls back to
         *     `dynArrayElementTypeOf`, specifically so this one
         *     synthesized call site -- built against a nominally *safe*
         *     `owns_mut_dynarray(...)` destination, not a genuinely
         *     unsafe one -- still resolves correctly). The 3-argument
         *     safe form (real `RESIZE`) was considered and rejected: it
         *     needs a fill value, and `elemType` here is arbitrary (could
         *     be an owns-bearing struct, could be a leaf pointer), so
         *     there's no way to synthesize a generic one -- the 2-arg
         *     form's own uninitialized memory is never actually
         *     observed, since every slot gets written by this loop
         *     before anything ever reads it. `resize`'s own compile-time
         *     "not inside a dynarray for-loop" restriction
         *     (`checkResizeBuiltin`, `scope.insideDynArrayForLoopBody`)
         *     is a `TypeChecker`-only check on real source text -- it's
         *     never reflected in the bytecode itself, so it doesn't bind
         *     bytecode generated directly here either. `resize` may
         *     return a different address than it was given (modeled
         *     directly on a real `realloc`), so the destination temp is
         *     re-captured (a second "POP") from its own result, never
         *     assumed to be the same address the initial "NEW_DYN ... 0"
         *     produced.
         * From there, the loop itself is exactly `DropGlueGenerationPass`
         * own's "GT_LOOP" mirror -- a hidden `mut_u64` counter, the same
         * `LEN`/`LT`/`CMP`/`JMP` bound check, `INC`/`ASSIGN` increment --
         * except each iteration produces a value (per `elemRoutine`'s own
         * tag, exactly as this pass's header already documents) and
         * stores it into the destination at the same index via
         * `LOOKUP_LHS`/`ASSIGN`, rather than merely destructing what it
         * finds.
         */
        private List<String> buildCloneLoop(String srcType, String dynElem, String elemRoutine, String resultType) {
            List<String> lines = new ArrayList<>();

            String srcTemp = newTemp(lines, "clone_loop_src", srcType);
            lines.add("POP " + srcTemp + " " + srcType);

            String dstType = "owns_mut_dynarray(" + dynElem + ")";
            String dstTemp = newTemp(lines, "clone_loop_dst", dstType);
            lines.add("NEW_DYN mut_dynarray(" + dynElem + ") 0");
            lines.add("POP " + dstTemp + " " + dstType);
            lines.add("PUSH " + dstTemp + " " + dstType);
            lines.add("PUSH " + srcTemp + " " + srcType);
            lines.add("LEN");
            lines.add("URESIZE " + dstType + " indeterminate_u64");
            lines.add("POP " + dstTemp + " " + dstType); // resize may relocate -- never assume the "NEW_DYN ... 0" address survives

            String counter = newTemp(lines, "clone_loop_i", "mut_u64");
            lines.add("ADDR " + counter + " mut_u64");
            lines.add("PUSH 0 indeterminate_u64");
            lines.add("ASSIGN mut_u64 mut_u64 mut_u64");

            String topLabel = newLabel("clone_loop");
            String endLabel = newLabel("clone_loop_end");
            lines.add(topLabel + ":");
            lines.add("PUSH " + counter + " mut_u64");
            lines.add("PUSH " + srcTemp + " " + srcType);
            lines.add("LEN");
            lines.add("LT mut_u64 indeterminate_u64 indeterminate_bool");
            lines.add("CMP");
            lines.add("JMP " + endLabel);

            // Destination slot address first (the same "push the
            // destination, then the value, then ASSIGN" order every
            // other assignment in this bytecode already uses), then
            // this iteration's freshly cloned value, exactly per
            // `elemRoutine`'s own tag contract (this pass's own header).
            lines.add("PUSH " + dstTemp + " " + dstType);
            lines.add("PUSH " + counter + " mut_u64");
            lines.add("LOOKUP_LHS " + dstType + " mut_u64 " + dynElem);

            CanonicalType elemT = CanonicalType.parse(dynElem);
            if (elemRoutine.equals("NONE:PTR")) {
                lines.add("PUSH " + srcTemp + " " + srcType);
                lines.add("PUSH " + counter + " mut_u64");
                lines.add("LOOKUP " + srcType + " mut_u64 " + dynElem);
                lines.add("CLONE " + dynElem + " owns_mut_" + elemT.baseType);
            } else if (elemRoutine.startsWith("PTR:")) {
                lines.add("PUSH " + srcTemp + " " + srcType);
                lines.add("PUSH " + counter + " mut_u64");
                lines.add("LOOKUP " + srcType + " mut_u64 " + dynElem);
                lines.add("CALL " + elemRoutine.substring("PTR:".length()));
                lines.add("PUSH_RET owns_mut_" + elemT.baseType);
            } else { // "VAL:name"
                lines.add("PUSH " + srcTemp + " " + srcType);
                lines.add("PUSH " + counter + " mut_u64");
                lines.add("LOOKUP_LHS " + srcType + " mut_u64 " + dynElem);
                lines.add("CALL " + elemRoutine.substring("VAL:".length()));
                lines.add("PUSH_RET mut_" + elemT.baseType);
            }
            lines.add("ASSIGN " + dynElem + " " + dynElem + " " + dynElem);

            lines.add("ADDR " + counter + " mut_u64");
            lines.add("PUSH " + counter + " mut_u64");
            lines.add("INC mut_u64 indeterminate_u64");
            lines.add("ASSIGN mut_u64 indeterminate_u64 indeterminate_u64");
            lines.add("JMP " + topLabel);
            lines.add(endLabel + ":");

            lines.add("PUSH " + dstTemp + " " + dstType);
            return lines;
        }

        private String newTemp(List<String> lines, String tag, String type) {
            tempCounter++;
            String name = "$" + tag + tempCounter;
            lines.add("ALLOC " + name + " " + type);
            return name;
        }

        private String newLabel(String prefix) {
            labelCounter++;
            return "@" + prefix + "_" + labelCounter;
        }

        private static String pointerRoutineNameFor(String structName) {
            return "__clone_" + CanonicalType.sanitizeForLabel(structName);
        }

        private static String valueRoutineNameFor(String structName) {
            return "__clone_value_" + CanonicalType.sanitizeForLabel(structName);
        }

        /**
         * Builds the full FUNC_START..FUNC_END block for `structName`'s
         * own pointer-based clone-glue routine (takes the pointer,
         * returns a freshly allocated, deeply cloned owns pointer) --
         * see this pass's own header for why this deliberately gets no
         * `gt_routine` prologue, and `emitParamLoad`'s own doc comment
         * (below) for why its one parameter loads via a plain `PUSH $16`
         * -- the sibling `caspien-compiler` project's own "ARG-to-ALLOC
         * lowering" contract, applied to this pass's own generated
         * routines too, now that the old `ARG name type` declaration line
         * is extinct everywhere in this pipeline's bytecode.
         */
        private List<List<BytecodeToken>> generatePointerCloneRoutine(String structName) {
            List<List<BytecodeToken>> out = new ArrayList<>();
            String paramType = "raw_imut_" + structName;
            emit(out, "FUNC_START " + pointerRoutineNameFor(structName));
            emit(out, "FUNC_DECORATE @clone_glue");
            emit(out, "RETURNS owns_mut_" + structName);
            emitParamLoad(out, "src", paramType);
            emitMemberPushSequence(out, structName, "src");
            emit(out, "NEW " + structName);
            emit(out, "RET owns_mut_" + structName);
            emit(out, "FUNC_END");
            return out;
        }

        /**
         * Builds the full FUNC_START..FUNC_END block for `structName`'s
         * own *value*-based clone-glue routine -- takes the *address* of
         * an existing value (never an owns pointer of its own -- this is
         * for an inline struct sitting directly inside, say, a
         * dynarray's own buffer) and returns a freshly built value, with
         * no "NEW"/allocation of its own, the same "the instance IS its
         * members' values, laid out inline" shape an ordinary, un-`new`'d
         * "StructName{...}" literal already has
         * (`BytecodeEmitter.emitInstantiate` never emits "NEW" itself --
         * only `emitNew`'s own wrapper around the `new` keyword does).
         */
        private List<List<BytecodeToken>> generateValueCloneRoutine(String structName) {
            List<List<BytecodeToken>> out = new ArrayList<>();
            String paramType = "raw_imut_" + structName;
            emit(out, "FUNC_START " + valueRoutineNameFor(structName));
            emit(out, "FUNC_DECORATE @clone_glue");
            emit(out, "RETURNS mut_" + structName);
            emitParamLoad(out, "src", paramType);
            emitMemberPushSequence(out, structName, "src");
            emit(out, "RET mut_" + structName);
            emit(out, "FUNC_END");
            return out;
        }

        /**
         * Emits one generated routine's own single-parameter "ARG-to-
         * ALLOC lowering" -- a real `ALLOC name type` (trivially "the
         * first of the ALLOCs for that function," since it's the only
         * one), then loaded via `ADDR`/`PUSH $16`/`ASSIGN`, the same
         * plain, pre-existing `PUSH` (no new mnemonic) the sibling
         * `caspien-compiler` project's own stack-passed parameters use --
         * see that project's CLAUDE.md, "ARG-to-ALLOC lowering." Both of
         * this pass's generated routines are always called through a bare
         * `PUSH value` / `CALL name` (see `tryRewriteClone`), never a real
         * `CC_START`/`CC_END`/register-transfer bracket, so the one
         * argument never touches a register and is always exactly stack
         * word 0 -- unconditionally, whatever real `@call_convention` the
         * rest of the program actually uses (this pass has no
         * `compiler.config` of its own to resolve one from). `16` is the
         * same fixed saved-rbp-plus-return-address base the compiler side
         * uses (`16 + shadowStack + stackWordIndex*8`), with
         * `shadowStack=0` and `stackWordIndex=0` here since this bare
         * `PUSH`/`CALL` shape has no real calling convention and always
         * exactly one stack-passed word -- the identical reasoning
         * `DropGlueGenerationPass.generateRoutine`'s own doc comment
         * gives for its own, structurally identical one-parameter
         * routine.
         */
        private void emitParamLoad(List<List<BytecodeToken>> out, String name, String type) {
            emit(out, "ALLOC " + name + " " + type);
            emit(out, "ADDR " + name + " " + type);
            emit(out, "PUSH $16 " + type);
            emit(out, "ASSIGN " + type + " " + type + " " + type);
        }

        /** Pushes `structName`'s own classId (if any, copied verbatim off `basePath`) followed by every ordinary member's own freshly cloned value, in declared order -- shared by both routine shapes above, matching NEW's/an inline struct literal's own identical construction order (BytecodeEmitter.emitInstantiate). */
        private void emitMemberPushSequence(List<List<BytecodeToken>> out, String structName, String basePath) {
            List<StructTable.Member> members = structTable.membersOf(structName);
            // classId, when present, is always the *first* STRUCT_MEMBER
            // (BytecodeEmitter.emitStruct emits it before every
            // user-declared field -- see that method's own "load-bearing,
            // not cosmetic" comment) -- checked at index 0, not the last
            // index. (Found stale while wiring up struct padding: this
            // used to check `members.get(members.size() - 1)`, a leftover
            // from before the compiler-side "___type declared last" bug
            // was fixed there; it had silently gone uncorrected here ever
            // since, this pass's own "found missing/stale, not new"
            // report gap.)
            boolean classIdBearing = !members.isEmpty() && members.get(0).name.equals("___type");
            List<StructTable.Member> ordinary = classIdBearing ? members.subList(1, members.size()) : members;

            // "___type" is never part of a struct's own declared member
            // list (BytecodeEmitter.emitStruct appends it separately,
            // first, only for a classId-bearing struct) -- copied here
            // directly off the source, matching NEW's own real
            // construction order (classId first, then ordinary members,
            // see BytecodeEmitter.emitInstantiate), rather than needing
            // this pass to know the actual numeric classId at all: a
            // clone always targets the exact same concrete struct type
            // as its source, so simply copying the source's own already-
            // correct value across is always right.
            if (classIdBearing) {
                emit(out, "PUSH " + basePath + ".___type imut_u64");
            }
            for (StructTable.Member m : ordinary) {
                emitClonedValue(out, basePath + "." + m.name, m.canonicalType);
            }
        }

        /**
         * Pushes a freshly cloned value for one member (or one unrolled
         * fixed-array element, or one recursively-visited inline
         * struct's own sub-member) at `path`, of declared type
         * `canonicalType` -- exactly one value pushed per call, for
         * whatever's assembling a "NEW ..." sequence out of these to
         * consume. Mirrors DropGlueGenerationPass.emitValueDestruct's
         * own dispatch shape.
         */
        private void emitClonedValue(List<List<BytecodeToken>> out, String path, String canonicalType) {
            CanonicalType t = CanonicalType.parse(canonicalType);

            if (t.isOwnsStorage()) {
                emit(out, "PUSH " + path + " " + canonicalType);
                List<String> recurse = structTable.isOwnsBearing(t.baseType)
                        ? ownsBearingCloneInstructions(canonicalType) : null;
                if (recurse != null) {
                    for (String l : recurse) {
                        emit(out, l);
                    }
                } else {
                    // A leaf owns pointer (nothing further inside to
                    // clone), or one of the two documented gap shapes --
                    // the plain, flat "CLONE" contract is exactly
                    // correct either way. Carries the same trailing
                    // "argType returnType" operand pair the compiler's
                    // own "clone()" builtin now emits, so this generated
                    // line is indistinguishable from a real one by the
                    // time AddressLoweringPass -- which runs after this
                    // pass -- reduces it to a plain size.
                    emit(out, "CLONE " + canonicalType + " owns_mut_" + t.baseType);
                }
                return;
            }

            if (structTable.hasStruct(t.baseType)) {
                if (structTable.isOwnsBearing(t.baseType)) {
                    // An inline (no-storage) embedded struct that itself
                    // owns something further down -- recurse into its
                    // own members in place, exactly as if they were this
                    // routine's own top-level ones, so the freshly built
                    // value never aliases whatever the embedded struct
                    // itself owns.
                    emitMemberPushSequence(out, t.baseType, path);
                    return;
                }
                // No owns content anywhere inside -- safe to copy as one
                // opaque block of bytes, same as any other flat value.
                emit(out, "PUSH " + path + " " + canonicalType);
                return;
            }

            String fixedElem = t.fixedArrayElementType();
            if (fixedElem != null) {
                CanonicalType elemT = CanonicalType.parse(fixedElem);
                boolean elemNeedsWork = elemT.isOwnsStorage()
                        || (structTable.hasStruct(elemT.baseType) && structTable.isOwnsBearing(elemT.baseType))
                        || CanonicalType.fixedArrayElementTypeOf(elemT.baseType) != null;
                if (elemNeedsWork) {
                    int count = t.fixedArrayLength();
                    for (int i = 0; i < count; i++) {
                        emitClonedValue(out, path + "." + i, fixedElem);
                    }
                    return;
                }
                // Falls through -- no owns content anywhere inside the
                // array, safe to copy the whole thing as one block.
            }

            // A plain scalar, or any other shape with no owns content.
            emit(out, "PUSH " + path + " " + canonicalType);
        }

        private static void emit(List<List<BytecodeToken>> out, String text) {
            out.addAll(PARSER.parse(Collections.singletonList(text), "<generated-clone-glue>"));
        }
    }
}
```

### FILE: src/main/java/caspien/lowerorder/CompilerConfig.java
```java
package caspien.lowerorder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and parses this project's own copy of "compiler.config" -- a
 * hand-duplicated copy of the sibling `caspien-compiler` project's own
 * class of the identical name and identical parser, confirmed directly
 * this is the right way to keep the two projects working off the same
 * calling-convention numbers without a cross-project import: "they are
 * all to work off the same config file... it can just be duplicated
 * for now it should be a small file, easy to keep in sync." This
 * project still has zero imports of `caspien.*` (the compiler's own
 * package) -- see this project's CLAUDE.md, "What this project is" --
 * so the two `CompilerConfig` classes are two independent, hand-kept-
 * in-sync copies of the same tiny parser, not a shared dependency.
 *
 * `AddressLoweringPass` is this project's only consumer: it needs a
 * function's own resolved calling convention's `argumentRegisters`
 * count (to decide whether a given `PUSH ARGn` word is register- or
 * stack-transferred) and `shadowStack` size (to compute a stack-
 * transferred word's own real, positive base-pointer-relative offset)
 * -- see that pass's own "Address lowering" section in CLAUDE.md.
 *
 * Expected shape (identical to the compiler's own copy -- see that
 * project's `CompilerConfig.java` for the full grammar notes; not
 * repeated here to avoid the two doc comments drifting out of sync
 * with each other independently of the two parsers themselves):
 *
 *     CallingConventions:
 *         win64:
 *             cleanup: caller
 *             argument-registers: ["RCX", "RDX", "R8", "R9"]
 *             argument-registers-float: ["XMM0", "XMM1", "XMM2", "XMM3"]
 *             return-register: "RAX"
 *             return-register-float: "XMM0"
 *             alignment: 16
 *             shadow-stack: 32
 *         sysv_x64:
 *             ...
 *         default: win64
 *
 * A fixed path ("compiler.config", resolved relative to the current
 * working directory), hard-required: missing entirely is a fatal
 * error, never a silently-applied built-in fallback -- the identical
 * "hard-required, no fallback" contract the compiler side's own copy
 * already follows. `#` starts a whole-line comment (only at the start
 * of a line, after its own leading indentation). Blank lines are
 * ignored anywhere. No regex anywhere, matching this project's own
 * "no regex, ever" rule (the compiler side's own copy already follows
 * the identical rule, for the identical reason).
 */
public class CompilerConfig {

    /** One named entry under "CallingConventions:" -- every field mirrors the config file's own key names. `argumentRegisters.size()`/`argumentRegistersFloat.size()`, `shadowStack`, and `sharedArgumentPosition` are read by `AddressLoweringPass`'s own per-function parameter-word resolution; the rest are parsed and validated (so a malformed config still fails loudly) but not otherwise consumed here. */
    public static class CallingConvention {
        public String name;
        public String cleanup;
        public List<String> argumentRegisters = new ArrayList<>();
        public List<String> argumentRegistersFloat = new ArrayList<>();
        public String returnRegister;
        public String returnRegisterFloat;
        public int alignment;
        public int shadowStack;
        /** True only for a convention where an integer argument and a float argument advance one shared running position counter (win64's own documented behavior) rather than two independent per-bank counters (SysV, arm64's AAPCS64) -- see the compiler-side `CompilerConfig`'s own copy of this same field for the full reasoning (a real, fixed ABI fact, not derived from the two register lists' lengths). Defaults false when the config omits this key. */
        public boolean sharedArgumentPosition;
    }

    public final Map<String, CallingConvention> callingConventions = new LinkedHashMap<>();
    public String defaultConvention;
    /** Optional top-level 'deferred-operands: on|off' (default off): enables the LowerOrderGenerator's RegisterFormPass. */
    public boolean deferredOperands = false;
    /** Optional top-level \'variables-in-registers: on|off\' (default off; needs deferred-operands: on): promote REGVAR-hinted scalar locals to registers. */
    public boolean variablesInRegisters = false;
    /** Optional top-level 'float-variables-in-registers: on|off' (default off; needs variables-in-registers: on): keep hot f32 locals in xmm registers instead of memory. */
    public boolean floatVariablesInRegisters = false;
    /** Optional top-level 'float-temporaries-in-registers: on|off' (default off; needs deferred-operands: on): f32 expression temporaries stay in xmm registers instead of round-tripping through general registers. */
    public boolean floatTemporariesInRegisters = false;

    public CallingConvention getDefault() {
        return callingConventions.get(defaultConvention);
    }

    public static CompilerConfig load(String path) {
        String content;
        try {
            content = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("required config file '" + path + "' could not be read: " + e.getMessage(), e);
        }
        return parse(content, path);
    }

    private static CompilerConfig parse(String content, String path) {
        List<String> lines = splitLines(content);
        CompilerConfig config = new CompilerConfig();

        int i = 0;
        int lineNo = 0;

        boolean foundHeader = false;
        while (i < lines.size()) {
            lineNo++;
            String raw = stripCrAndComment(lines.get(i));
            i++;
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (leadingWhitespace(raw) != 0) {
                throw configErr(path, lineNo, "unexpected indentation before 'CallingConventions:'");
            }
            if (startsWithLiteral(trimmed, "loop-unrolling:") || startsWithLiteral(trimmed, "loop-unroll-") || startsWithLiteral(trimmed, "function-inlining:") || startsWithLiteral(trimmed, "inline-max-")
                    || startsWithLiteral(trimmed, "constant-folding:") || startsWithLiteral(trimmed, "variable-elision:")
                    || startsWithLiteral(trimmed, "variable-shifting:") || startsWithLiteral(trimmed, "struct-unpacking:") || startsWithLiteral(trimmed, "dead-control-flow-removal:") || startsWithLiteral(trimmed, "dead-function-removal:") || startsWithLiteral(trimmed, "unused-declaration-removal:") || startsWithLiteral(trimmed, "variable-allocation-reordering:") || startsWithLiteral(trimmed, "struct-member-reordering:")) {
                // Loop-unrolling and constant-folding settings belong to the Optimizer, which validates them; every stage that reads
                // compiler.config just has to accept them.
                continue;
            }
            if (startsWithLiteral(trimmed, "deferred-operands:")) {
                // Optional switch for the LowerOrderGenerator's RegisterFormPass; parsed here so every stage
                // that reads compiler.config accepts it. Absent = off.
                String v = unquoteOrBare(trimmed.substring("deferred-operands:".length()).trim());
                if (!v.equals("on") && !v.equals("off")) {
                    throw configErr(path, lineNo, "'deferred-operands' must be 'on' or 'off', found '" + v + "'");
                }
                config.deferredOperands = v.equals("on");
                continue;
            }
            if (startsWithLiteral(trimmed, "float-temporaries-in-registers:")) {
                // Optional switch: FloatTempPass keeps f32 expression temporaries in xmm registers; parsed here so every stage
                // that reads compiler.config accepts it. Absent = off.
                String v = unquoteOrBare(trimmed.substring("float-temporaries-in-registers:".length()).trim());
                if (!v.equals("on") && !v.equals("off")) {
                    throw configErr(path, lineNo, "'float-temporaries-in-registers' must be 'on' or 'off', found '" + v + "'");
                }
                config.floatTemporariesInRegisters = v.equals("on");
                continue;
            }
            if (startsWithLiteral(trimmed, "float-variables-in-registers:")) {
                // Optional switch: hot f32 locals (REGVAR hints with the float marker) live in xmm registers; parsed here so every stage
                // that reads compiler.config accepts it. Absent = off.
                String v = unquoteOrBare(trimmed.substring("float-variables-in-registers:".length()).trim());
                if (!v.equals("on") && !v.equals("off")) {
                    throw configErr(path, lineNo, "'float-variables-in-registers' must be 'on' or 'off', found '" + v + "'");
                }
                config.floatVariablesInRegisters = v.equals("on");
                continue;
            }
            if (startsWithLiteral(trimmed, "variables-in-registers:")) {
                // Optional switch: RegisterFormPass keeps hot scalar locals (REGVAR hints) in registers; parsed here so every stage
                // that reads compiler.config accepts it. Absent = off.
                String v = unquoteOrBare(trimmed.substring("variables-in-registers:".length()).trim());
                if (!v.equals("on") && !v.equals("off")) {
                    throw configErr(path, lineNo, "'variables-in-registers' must be 'on' or 'off', found '" + v + "'");
                }
                config.variablesInRegisters = v.equals("on");
                continue;
            }
            if (!trimmed.equals("CallingConventions:")) {
                throw configErr(path, lineNo, "expected top-level key 'CallingConventions:', found '" + trimmed + "'");
            }
            foundHeader = true;
            break;
        }
        if (!foundHeader) {
            throw configErr(path, lineNo, "missing top-level 'CallingConventions:' key");
        }

        int conventionIndent = -1;
        int fieldIndent = -1;
        CallingConvention current = null;

        while (i < lines.size()) {
            lineNo++;
            String raw = stripCrAndComment(lines.get(i));
            i++;
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int indent = leadingWhitespace(raw);

            if (indent == 0) {
                throw configErr(path, lineNo,
                        "unexpected top-level content '" + trimmed + "' after 'CallingConventions:'");
            }

            if (conventionIndent == -1) {
                conventionIndent = indent;
            }

            if (indent == conventionIndent) {
                current = null;
                if (startsWithLiteral(trimmed, "default:")) {
                    String value = trimmed.substring("default:".length()).trim();
                    config.defaultConvention = unquoteOrBare(value);
                    continue;
                }
                if (!trimmed.endsWith(":")) {
                    throw configErr(path, lineNo,
                            "expected a calling-convention name ('name:') or 'default: name', found '"
                                    + trimmed + "'");
                }
                String name = trimmed.substring(0, trimmed.length() - 1).trim();
                if (config.callingConventions.containsKey(name)) {
                    throw configErr(path, lineNo, "calling convention '" + name + "' is already declared");
                }
                current = new CallingConvention();
                current.name = name;
                config.callingConventions.put(name, current);
                continue;
            }

            if (fieldIndent == -1) {
                fieldIndent = indent;
            }
            if (indent != fieldIndent) {
                throw configErr(path, lineNo, "inconsistent indentation");
            }
            if (current == null) {
                throw configErr(path, lineNo, "a field must be indented under a calling-convention name");
            }

            int colonIdx = trimmed.indexOf(':');
            if (colonIdx < 0) {
                throw configErr(path, lineNo, "expected 'field: value', found '" + trimmed + "'");
            }
            String key = trimmed.substring(0, colonIdx).trim();
            String valueText = trimmed.substring(colonIdx + 1).trim();
            switch (key) {
                case "cleanup":
                    current.cleanup = unquoteOrBare(valueText);
                    break;
                case "argument-registers":
                    current.argumentRegisters = parseStringList(valueText, path, lineNo);
                    break;
                case "argument-registers-float":
                    current.argumentRegistersFloat = parseStringList(valueText, path, lineNo);
                    break;
                case "return-register":
                    current.returnRegister = unquoteOrBare(valueText);
                    break;
                case "return-register-float":
                    current.returnRegisterFloat = unquoteOrBare(valueText);
                    break;
                case "alignment":
                    current.alignment = parseIntField(valueText, path, lineNo, "alignment");
                    break;
                case "shadow-stack":
                    current.shadowStack = parseIntField(valueText, path, lineNo, "shadow-stack");
                    break;
                case "shared-argument-position":
                    current.sharedArgumentPosition = parseBoolField(valueText, path, lineNo, "shared-argument-position");
                    break;
                default:
                    throw configErr(path, lineNo, "unknown calling-convention field '" + key + "'");
            }
        }

        if (config.callingConventions.isEmpty()) {
            throw configErr(path, lineNo, "'CallingConventions:' declares no calling conventions");
        }
        if (config.defaultConvention == null) {
            throw configErr(path, lineNo, "missing 'default: <conventionName>'");
        }
        if (!config.callingConventions.containsKey(config.defaultConvention)) {
            throw configErr(path, lineNo,
                    "'default: " + config.defaultConvention + "' does not name a declared calling convention");
        }
        for (CallingConvention cc : config.callingConventions.values()) {
            if (cc.argumentRegisters.isEmpty()) {
                throw configErr(path, lineNo,
                        "calling convention '" + cc.name + "' has no 'argument-registers'");
            }
        }
        return config;
    }

    // ---- hand-written scanning helpers -- no regex anywhere, matching this project's own rule ----

    private static List<String> splitLines(String content) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines.add(content.substring(start, i));
                start = i + 1;
            }
        }
        lines.add(content.substring(start));
        return lines;
    }

    private static String stripCrAndComment(String line) {
        String noCr = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        int i = 0;
        while (i < noCr.length() && (noCr.charAt(i) == ' ' || noCr.charAt(i) == '\t')) {
            i++;
        }
        if (i < noCr.length() && noCr.charAt(i) == '#') {
            return noCr.substring(0, i);
        }
        return noCr;
    }

    private static int leadingWhitespace(String s) {
        int n = 0;
        while (n < s.length() && (s.charAt(n) == ' ' || s.charAt(n) == '\t')) {
            n++;
        }
        return n;
    }

    private static boolean startsWithLiteral(String s, String prefix) {
        return s.length() >= prefix.length() && s.substring(0, prefix.length()).equals(prefix);
    }

    private static int parseIntField(String text, String path, int lineNo, String field) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw configErr(path, lineNo, "'" + field + "' expects an integer, got '" + text + "'");
        }
    }

    private static boolean parseBoolField(String text, String path, int lineNo, String field) {
        String trimmed = text.trim();
        if (trimmed.equals("true")) {
            return true;
        }
        if (trimmed.equals("false")) {
            return false;
        }
        throw configErr(path, lineNo, "'" + field + "' expects 'true' or 'false', got '" + trimmed + "'");
    }

    private static List<String> parseStringList(String text, String path, int lineNo) {
        text = text.trim();
        if (!text.startsWith("[") || !text.endsWith("]")) {
            throw configErr(path, lineNo, "expected a '[...]' list, got '" + text + "'");
        }
        String inner = text.substring(1, text.length() - 1).trim();
        List<String> result = new ArrayList<>();
        if (inner.isEmpty()) {
            return result;
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            if (inner.charAt(i) == ',') {
                parts.add(inner.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(inner.substring(start));
        for (String part : parts) {
            result.add(unquoteOrBare(part.trim()));
        }
        return result;
    }

    private static String unquoteOrBare(String text) {
        text = text.trim();
        if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private static RuntimeException configErr(String path, int lineNo, String message) {
        return new RuntimeException("config error in '" + path + "' at line " + lineNo + ": " + message);
    }
}
```

### FILE: src/main/java/caspien/lowerorder/DropGlueGenerationPass.java
```java
package caspien.lowerorder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The high-order-to-low-order lowering step that moves recursive
 * GT_DESTRUCT expansion out of the codegen stage and into this one, per
 * the design settled on directly (see this project's CLAUDE.md,
 * "Ownership-aware destructor generation" section, for the full
 * conversation this pass implements). Runs once, as the final stage of
 * the pipeline (BytecodeOptimizer.optimize) -- not part of either
 * fixed-point loop, since it's a one-shot generation step (new function
 * bodies get appended to the program) rather than an iterative rewrite.
 *
 * Background: the compiler emits exactly one flat "GT_DESTRUCT name"
 * per top-level owns slot, and nothing about what that pointer's own
 * struct might itself own -- "this compiler's own bytecode now emits
 * exactly one GT_DESTRUCT per top-level owns slot again, nothing about
 * what's inside it... a later, separate stage (not yet built) is
 * responsible for recursing into a struct's own owns members at
 * destruct time, using its own knowledge of struct layout," confirmed
 * directly in this compiler's own TypeChecker doc comments. This pass
 * is that stage. The runtime `gt_destruct` primitive itself stays flat
 * and generic -- it only ever frees the one pointer it's handed
 * ("don't worry about members of structs... frees exactly the one
 * pointer given, never anything it might itself point to or contain,"
 * confirmed directly in stdlib/gt_destruct.caspien) -- so all the
 * recursion has to happen as real, generated bytecode before GT_DESTRUCT
 * is ever reached.
 *
 * Design:
 *   - One shared drop-glue routine is generated per distinct
 *     owns-bearing struct shape (never per call site, never per
 *     allocation) -- self-referential/recursive struct types are
 *     illegal in this language, so there's no termination hazard to
 *     design around, but sharing still avoids duplicating the same
 *     type's own teardown at every scope exit that declares it.
 *   - Each routine is a genuinely lean, ordinary function: "(ref) void"
 *     -- it takes a `ref`-storage pointer (never `raw`: this parameter
 *     is always the compiler's own proof that the address came from an
 *     existing `owns` value, never something `unsafe` has to vouch for)
 *     and returns nothing. It never allocates and never throws, and it
 *     needs no gt-suppression at all -- confirmed directly against the
 *     real compiler source: the gt_routine/GT_UNWIND prologue (the
 *     reserved gt_routine_address slot, the entry label, ending in
 *     GT_UNWIND instead of RET) is written by BytecodeEmitter.
 *     emitGtRoutinePrologue at compile time, as a decision the compiler
 *     makes once per function it emits and bakes directly into that
 *     function's own bytecode text. It is never re-derived later by
 *     codegen from a function's shape or decorators -- codegen only
 *     ever translates whatever's already textually present. Since this
 *     pass hand-writes these routines' bytecode directly, after the
 *     compiler has already run, that machinery is simply never invoked
 *     for them -- they come out lean by construction, with no codegen
 *     change needed for that. The "FUNC_DECORATE @drop_glue" line below
 *     is kept purely as a human/tooling-readable marker of "this
 *     function was generated by this pass," not because anything reads
 *     it to decide suppression.
 *   - A routine never destructs its own top-level parameter -- only
 *     descendants reachable through it. Self-destruction (the real,
 *     original GT_DESTRUCT) always happens at the call site: "recurse
 *     into children, then GT_DESTRUCT self" is the shape everywhere,
 *     never "GT_DESTRUCT self" inside the routine.
 *   - Recursing into an owns member is null-guarded (comparing it
 *     against `null` and skipping the recursive step, exactly like an
 *     ordinary 'if', on a false/null result) -- dereferencing a null
 *     pointer to read its own children would segfault. The final
 *     GT_DESTRUCT of that member is left unconditional either way,
 *     matching the runtime's own "no-op on null" behavior (no guard
 *     needed there).
 *   - A dynarray member's elements are walked by a real, generated
 *     JMP/CMP loop (`emitDynArrayElementDrop`) rather than unrolled here
 *     at compile time -- the element count is only known at runtime, so
 *     a hidden counter local (the same "$"-prefixed, never-collides-
 *     with-a-real-identifier convention this bytecode already
 *     establishes -- `MembershipLoweringPass`'s own temps, the
 *     compiler's own "$for_range_N" for-loop desugaring) walks
 *     `0..LEN(dynarrayOperand)` using the identical bare-`LEN`/`CMP`/
 *     `JMP` shape already fully specified and already relied on
 *     elsewhere in this pipeline (`MembershipLoweringPass`'s own
 *     "x in dynarray" lowering; the compiler's own `for`/`loop`
 *     desugaring). Originally this was left as a new, not-yet-
 *     implemented "GT_LOOP dynarrayOperand elemType elemRoutine"
 *     instruction, deferred to a not-yet-built codegen stage -- since
 *     replaced with the real loop directly, once it became clear this
 *     pass already has everything it needs to build one itself, and
 *     the stated preference was against adding a further pass to do it
 *     ("please implement their lowering all the same," with a
 *     preference against a whole new pass for it). Per-element
 *     behavior is unchanged from the original contract -- null-guard +
 *     call + unconditional `GT_DESTRUCT` if `elemType` itself is
 *     owns-storage; a plain `CALL` (no guard, no `GT_DESTRUCT`) if
 *     `elemType` is an inline owns-bearing composite; nothing at all if
 *     `elemType` has no owns content -- see `emitDynArrayElementDrop`'s
 *     own doc comment for exactly how each shape is now built.
 *   - A fixed-size array member is unrolled directly here (the count is
 *     always a compile-time literal), extending this bytecode's own
 *     existing dotted-numeric-index convention for composite values
 *     ("name.0", "name.1", ... -- see BytecodeEmitter.emitStaticAlloc)
 *     from its original compile-time-constant-array use to this
 *     ordinary runtime one. This is a deliberate choice, not just the
 *     path of least resistance: the real compiler has a confirmed
 *     runtime array-addressing instruction (LOOKUP_LHS -- see
 *     BytecodeEmitter.emitAssignTarget's own "LOOKUP" case), but it
 *     produces an address as a bare stack value, and every instruction
 *     this pass otherwise relies on for a member (GT_DESTRUCT, ADDR,
 *     PUSH) takes a named/dotted operand, never a value already sitting
 *     on the stack -- GT_DESTRUCT in particular has no confirmed "act on
 *     whatever's on top of the stack" form anywhere in this bytecode.
 *     Extending the dotted-name convention keeps every element
 *     addressable the same way an ordinary struct field already is;
 *     flagged here so the codegen stage's own ADDR/member-addressing
 *     logic can be checked against it (or extended to it) directly.
 *
 * Known gap: an `owns` pointer directly at a bare fixed array (rather
 * than at a struct or a dynarray -- e.g. "owns mut Thing[3]") is not
 * recursed into. Only STRUCT and DYNARRAY pointees are handled; this
 * shape falls back to the ordinary unconditional GT_DESTRUCT with no
 * attempt to reach further in. Flagged rather than guessed at, since
 * recursing into it would need array-element addressing-by-computed-
 * index bytecode this project has no confirmed shape for yet.
 *
 * Verified directly against the real compiler source (BytecodeEmitter's
 * own COMPARISON_NAMES map and emitComparison): the null-guard below
 * emits "NEQ leftType rightType returnType" to test "member != null"
 * ahead of the existing CMP/JMP conditional shape (BytecodeEmitter.
 * emitIfChain) -- "NEQ" and the trailing "imut_bool" result type both
 * match the real emitter exactly (TypeChecker's own comparison-result
 * TypeInfo is literally `new TypeInfo(null, "imut", "bool")`).
 */
public class DropGlueGenerationPass implements OptimizationPass {

    private static final BytecodeParser PARSER = new BytecodeParser();

    @Override
    public String name() {
        return "drop-glue-generation";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        return new Worker(StructTable.read(lines)).process(lines);
    }

    /**
     * Holds this one run's own mutable state (the routine worklist, the
     * label counter) so the outer pass class itself stays a stateless,
     * reentrant OptimizationPass -- a fresh Worker per run() call, never
     * shared across calls.
     */
    private static final class Worker {

        private final StructTable structTable;
        private final Set<String> needed = new LinkedHashSet<>();
        private final Set<String> generated = new HashSet<>();
        private int labelCounter = 0;
        private int tempCounter = 0;

        Worker(StructTable structTable) {
            this.structTable = structTable;
        }

        PassResult process(List<List<BytecodeToken>> lines) {
            List<List<BytecodeToken>> rewritten = new ArrayList<>();
            boolean changedAnyCallSite = false;

            int i = 0;
            int n = lines.size();
            while (i < n) {
                List<BytecodeToken> line = lines.get(i);
                if (line.isEmpty() || !line.get(0).text.equals("FUNC_START")) {
                    rewritten.add(line);
                    i++;
                    continue;
                }

                // A whole function's own line range, FUNC_START..FUNC_END
                // inclusive. Every `owns` local's own ALLOC is "hoisted to
                // the top of its enclosing scope" *conceptually* (per this
                // bytecode's own spec -- see BytecodeEmitter's own doc
                // comment on ALLOC), but not textually: a real compiled
                // function's own gt_routine unwind-path copy of its
                // GT_DESTRUCT list sits right after the prologue, *before*
                // the ordinary body's ALLOC lines appear in the text below
                // it (confirmed directly by running this pass against real
                // compiler output -- an early single forward-pass version
                // of this method missed exactly that copy). So every
                // ALLOC in the whole function is collected first, in a
                // pass of its own, before any GT_DESTRUCT in that same
                // function is resolved against it. Parameters need no
                // separate check here any more -- the sibling
                // `caspien-compiler` project's own "ARG-to-ALLOC lowering"
                // means a real parameter is now just an ordinary `ALLOC`
                // too (the old, now-extinct `ARG name type` declaration
                // line this used to also check for never appears in real
                // compiler output any more, and this pass's own generated
                // routines emit `ALLOC` for their own parameter as well --
                // see `generateRoutine`'s own doc comment).
                int start = i;
                int end = start;
                while (end < n && !(lines.get(end).size() > 0 && lines.get(end).get(0).text.equals("FUNC_END"))) {
                    end++;
                }
                end = Math.min(end, n - 1); // index of the FUNC_END line itself (or the last line, if malformed)

                Map<String, String> localTypes = new HashMap<>();
                for (int k = start; k <= end; k++) {
                    List<BytecodeToken> fl = lines.get(k);
                    if (!fl.isEmpty() && fl.get(0).text.equals("ALLOC") && fl.size() >= 3) {
                        localTypes.put(fl.get(1).text, fl.get(2).text);
                    }
                }

                for (int k = start; k <= end; k++) {
                    List<BytecodeToken> fl = lines.get(k);
                    if (!fl.isEmpty() && fl.get(0).text.equals("GT_DESTRUCT") && fl.size() >= 2) {
                        String targetName = fl.get(1).text;
                        String targetType = resolveType(targetName, localTypes);
                        if (targetType != null) {
                            CanonicalType t = CanonicalType.parse(targetType);
                            if (t.isOwnsStorage() && structTable.isOwnsBearing(t.baseType)) {
                                String topLevelDynElem = CanonicalType.dynArrayElementTypeOf(t.baseType);
                                if (topLevelDynElem != null) {
                                    // A bare, top-level owns local that is
                                    // itself a dynarray (never a struct --
                                    // `enqueue`/`generateRoutine` below are
                                    // both struct-shaped, calling
                                    // `structTable.membersOf` /
                                    // requiring `structTable.hasStruct`,
                                    // neither of which a dynarray baseType
                                    // ever satisfies). This used to fall
                                    // into the struct branch below anyway
                                    // -- `isOwnsBearing` already recognizes
                                    // a dynarray baseType as owns-bearing
                                    // (recursing into its own element type,
                                    // `StructTable.computeOwnsBearing`) --
                                    // emitting a real "CALL
                                    // __drop_dynarray_..." call site, but
                                    // `enqueue`'s own `hasStruct` guard
                                    // silently refused to ever add it to
                                    // the generation worklist, so that
                                    // routine was never actually generated
                                    // at all: a call to a genuinely
                                    // nonexistent function, caught directly
                                    // against a real fixture
                                    // (`clone_generation_manual_check_test`'s
                                    // own top-level `arr`/`arrCloned`
                                    // locals) while verifying this pass's
                                    // own new dynarray-element loop
                                    // generation. Fixed by handling this
                                    // shape inline, the same way an
                                    // owns-bearing dynarray *member*
                                    // already is (`emitOwnsMemberDestruct`)
                                    // -- no separate generated routine at
                                    // all, just this local's own elements
                                    // walked directly at the call site.
                                    emitDynArrayElementDrop(rewritten, targetName, targetType, topLevelDynElem);
                                } else {
                                    emit(rewritten, "PUSH " + targetName + " " + targetType);
                                    emit(rewritten, "CALL " + routineNameFor(t.baseType));
                                    enqueue(t.baseType);
                                }
                                changedAnyCallSite = true;
                            }
                        }
                    }
                    rewritten.add(fl); // the original line always survives unchanged -- including the original GT_DESTRUCT itself, which still runs last, after any children
                }

                i = end + 1;
            }

            boolean generatedAnyRoutine = !needed.isEmpty();
            while (!needed.isEmpty()) {
                String next = needed.iterator().next();
                needed.remove(next);
                if (generated.contains(next)) {
                    continue;
                }
                generated.add(next);
                rewritten.addAll(generateRoutine(next));
            }

            return new PassResult(rewritten, changedAnyCallSite || generatedAnyRoutine);
        }

        /** Chases a (possibly dotted) destruct target's declared type through ALLOC and struct-member lookups; null if any segment can't be resolved. */
        private String resolveType(String name, Map<String, String> localTypes) {
            int dot = name.indexOf('.');
            String head = (dot < 0) ? name : name.substring(0, dot);
            String currentType = localTypes.get(head);
            if (currentType == null) {
                return null;
            }
            String remainder = (dot < 0) ? "" : name.substring(dot + 1);
            while (!remainder.isEmpty()) {
                int nextDot = remainder.indexOf('.');
                String segment = (nextDot < 0) ? remainder : remainder.substring(0, nextDot);
                CanonicalType t = CanonicalType.parse(currentType);
                List<StructTable.Member> members = structTable.membersOf(t.baseType);
                if (members == null) {
                    return null;
                }
                String found = null;
                for (StructTable.Member m : members) {
                    if (m.name.equals(segment)) {
                        found = m.canonicalType;
                        break;
                    }
                }
                if (found == null) {
                    return null;
                }
                currentType = found;
                remainder = (nextDot < 0) ? "" : remainder.substring(nextDot + 1);
            }
            return currentType;
        }

        private void enqueue(String baseType) {
            if (structTable.hasStruct(baseType) && !generated.contains(baseType)) {
                needed.add(baseType);
            }
        }

        private static String routineNameFor(String baseType) {
            return "__drop_" + CanonicalType.sanitizeForLabel(baseType);
        }

        private String newLabel(String prefix) {
            labelCounter++;
            return "@" + prefix + "_" + labelCounter;
        }

        private static void emit(List<List<BytecodeToken>> out, String text) {
            out.addAll(PARSER.parse(Collections.singletonList(text), "<generated-drop-glue>"));
        }

        /**
         * Declares a fresh, uniquely-named hidden local (the same
         * "$"-prefixed convention `MembershipLoweringPass`'s own
         * `declareTemp` and the compiler's own "$for_range_N" for-loop
         * desugaring both already establish) right where it's needed --
         * no separate hoisting-to-top step required, since
         * `AddressLoweringPass` already scans every `ALLOC` anywhere in
         * a function, in whatever order it appears, to assign stack
         * offsets (confirmed directly against its own `run` method), not
         * just ones sitting in a hoisted prologue block.
         */
        private String newTemp(List<List<BytecodeToken>> out, String tag, String type) {
            tempCounter++;
            String name = "$" + tag + tempCounter;
            emit(out, "ALLOC " + name + " " + type);
            return name;
        }

        /**
         * Builds the full FUNC_START..FUNC_END block for `structName`'s
         * own drop-glue routine. Its one parameter is loaded per the
         * sibling `caspien-compiler` project's own "ARG-to-ALLOC
         * lowering" contract (see that project's CLAUDE.md) -- a real
         * `ALLOC`, "the first of the ALLOCs for that function" (trivially
         * true here: it's the *only* one), then loaded via `ADDR`/
         * `PUSH $16`/`ASSIGN` -- the same plain, pre-existing `PUSH` (no
         * new mnemonic) the compiler side uses for its own stack-passed
         * parameters -- since this routine's own call site (just below,
         * in `process`) is a bare `PUSH value` / `CALL name` with no
         * `CC_START`/`CC_END`/register-transfer bracket at all -- a
         * deliberately minimal, ad hoc calling shape distinct from an
         * ordinary, convention-driven call, under which the value never
         * touches a register and is simply left sitting on the stack,
         * exactly the "left as a plain PUSH" shape a real call's own
         * stack-passed arguments (beyond its convention's own register
         * count) already get -- so `p` is always stack word 0,
         * unconditionally, regardless of whatever real `@call_convention`
         * the rest of the program actually uses (this pass has no
         * `compiler.config` of its own to resolve one from). `16` is the
         * compiler side's own fixed saved-rbp-plus-return-address base
         * (`16 + shadowStack + stackWordIndex*8`), with `shadowStack=0`
         * and `stackWordIndex=0` here since this bare `PUSH`/`CALL` shape
         * has no real calling convention and always exactly one
         * stack-passed word.
         */
        private List<List<BytecodeToken>> generateRoutine(String structName) {
            List<List<BytecodeToken>> out = new ArrayList<>();
            String paramType = "ref_mut_" + structName;
            emit(out, "FUNC_START " + routineNameFor(structName));
            emit(out, "FUNC_DECORATE @drop_glue");
            emit(out, "RETURNS imut_void");
            emit(out, "ALLOC p " + paramType);
            emit(out, "ADDR p " + paramType);
            emit(out, "PUSH $16 " + paramType);
            emit(out, "ASSIGN " + paramType + " " + paramType + " " + paramType);

            // `p` itself is genuinely pointer-wrapped (`ref`-storage) --
            // AddressLoweringPass.resolveAddress deliberately, by design,
            // refuses to resolve any dotted chain whose root (or any
            // segment) still carries non-null storage (its own "pointer
            // indirection -- known gap" bail), because real, type-checked
            // Caspien source can never produce that shape in the first
            // place (TypeChecker.checkDot's requiresAliveProof gate
            // rejects any '.' access rooted in a pointer outright). This
            // pass hand-writes its own bytecode after the compiler has
            // already run, bypassing that gate, so "p.member" paths built
            // directly off the raw pointer parameter would hit exactly
            // that unresolvable shape. Fixed the same way
            // MembershipLoweringPass's own derefInto already establishes
            // for the identical situation elsewhere in this codebase:
            // dereference the pointer once, into a fresh, storage-free
            // local, and dot into THAT instead -- every member path below
            // is now rooted in an ordinary, non-pointer name, exactly the
            // shape resolveAddress already resolves correctly for plain
            // (non-nested) owns locals and for ordinary compiled '.field'
            // access alike.
            CanonicalType paramCanonical = CanonicalType.parse(paramType);
            String derefType = paramCanonical.mutability != null
                    ? paramCanonical.mutability + "_" + paramCanonical.baseType
                    : paramCanonical.baseType;
            String root = newTemp(out, "drop_self", derefType);
            emit(out, "PUSH p " + paramType);
            emit(out, "DEREF " + derefType);
            emit(out, "POP " + root + " " + derefType);

            for (StructTable.Member member : structTable.membersOf(structName)) {
                emitValueDestruct(out, root + "." + member.name, member.canonicalType);
            }
            emit(out, "RET imut_void");
            emit(out, "FUNC_END");
            return out;
        }

        /**
         * Emits whatever destruction work `path` (an existing,
         * addressable operand -- a plain name or a dotted chain) needs
         * for a value of type `canonicalType`, exactly as if `path`
         * were an ordinary struct member being visited on the way to
         * freeing whatever owns it. Shared by every place this pass
         * needs "destruct whatever's reachable through here" -- a
         * struct's own top-level members, and each element of an
         * unrolled fixed array.
         */
        private void emitValueDestruct(List<List<BytecodeToken>> out, String path, String canonicalType) {
            CanonicalType t = CanonicalType.parse(canonicalType);

            if (t.isOwnsStorage()) {
                emitOwnsMemberDestruct(out, path, canonicalType, t.baseType);
                return;
            }

            if (structTable.hasStruct(t.baseType)) {
                if (structTable.isOwnsBearing(t.baseType)) {
                    emit(out, "ADDR " + path + " " + canonicalType);
                    emit(out, "CALL " + routineNameFor(t.baseType));
                    enqueue(t.baseType);
                }
                return;
            }

            String fixedElem = t.fixedArrayElementType();
            if (fixedElem != null) {
                CanonicalType elemT = CanonicalType.parse(fixedElem);
                boolean elemNeedsWork = elemT.isOwnsStorage()
                        || (structTable.hasStruct(elemT.baseType) && structTable.isOwnsBearing(elemT.baseType))
                        || CanonicalType.fixedArrayElementTypeOf(elemT.baseType) != null;
                if (elemNeedsWork) {
                    int count = t.fixedArrayLength();
                    for (int i = 0; i < count; i++) {
                        emitValueDestruct(out, path + "." + i, fixedElem);
                    }
                }
                return;
            }

            // A bare dynarray value can't reach this, inline branch --
            // "a dynarray value is always owns-storage" (see this
            // project's CLAUDE.md notes on dynarray codegen) -- so
            // dynarray handling only ever happens from the owns branch
            // above, via emitOwnsMemberDestruct's own GT_LOOP emission.
            // A plain scalar/non-owns-bearing struct needs nothing at
            // all -- falls through with no bytecode emitted.
        }

        /**
         * The owns-member case: null-guard the recursive descent into
         * this pointer's own children, then unconditionally GT_DESTRUCT
         * the pointer itself (safe even on null -- the runtime's own
         * gt_destruct is a documented no-op there).
         */
        private void emitOwnsMemberDestruct(List<List<BytecodeToken>> out, String path, String canonicalType, String baseType) {
            String skipLabel = newLabel("drop_skip");

            emit(out, "PUSH " + path + " " + canonicalType);
            emit(out, "PUSH null " + canonicalType);
            emit(out, "NEQ " + canonicalType + " " + canonicalType + " imut_bool");
            emit(out, "CMP");
            emit(out, "JMP " + skipLabel);

            String dynElem = CanonicalType.dynArrayElementTypeOf(baseType);
            if (dynElem != null) {
                emitDynArrayElementDrop(out, path, canonicalType, dynElem);
            } else if (structTable.hasStruct(baseType) && structTable.isOwnsBearing(baseType)) {
                emit(out, "PUSH " + path + " " + canonicalType);
                emit(out, "CALL " + routineNameFor(baseType));
                enqueue(baseType);
            }
            // else: either a bare fixed array pointee (the documented
            // known gap above) or a shape with no owns content at all --
            // nothing further to recurse into.

            emit(out, skipLabel + ":");
            emit(out, "GT_DESTRUCT " + path);
        }

        /** The routine to call for a dynarray element of type `elemCanonicalType` (see `emitDynArrayElementDrop`), or the literal "NONE" when there's nothing to recurse into for that element shape. */
        private String elementRoutineFor(String elemCanonicalType) {
            CanonicalType elemT = CanonicalType.parse(elemCanonicalType);
            if (structTable.hasStruct(elemT.baseType) && structTable.isOwnsBearing(elemT.baseType)) {
                enqueue(elemT.baseType);
                return routineNameFor(elemT.baseType);
            }
            return "NONE";
        }

        /**
         * Builds a real, generated JMP/CMP loop over `path`'s own
         * elements in place of the original "GT_LOOP dynarrayOperand
         * elemType elemRoutine" contract this pass used to hand off to a
         * not-yet-built codegen stage (see this pass's own header for
         * the full reasoning). `path` is already known to be null-guarded
         * by the caller (`emitOwnsMemberDestruct`), and its own
         * unconditional `GT_DESTRUCT` (freeing the dynarray's own
         * backing buffer) still happens right after this method returns,
         * unchanged -- this method only ever handles the *elements*.
         *
         * Built entirely out of primitives already fully specified
         * elsewhere in this pipeline: a hidden `mut_u64` counter,
         * initialized and incremented exactly the way the compiler's own
         * `for`/`loop` desugaring already does (`ADDR`/`PUSH`/`ASSIGN`
         * for init, `ADDR`/`PUSH`/`INC`/`ASSIGN` for the increment); the
         * loop condition is a plain `PUSH counter / PUSH dynarrayOperand
         * / LEN / LT / CMP / JMP end` -- the exact same bare-`LEN`
         * contract `MembershipLoweringPass`'s own "x in dynarray"
         * lowering already relies on, and in fact the same comparison a
         * real `for i in dynarrayExpr{...}` loop's own "i in dynarray"
         * eventually lowers to anyway.
         *
         * Per-element handling mirrors this pass's own non-loop member
         * handling (`emitOwnsMemberDestruct`/`emitValueDestruct`)
         * exactly, just reading through a computed index instead of a
         * static name:
         *   - `dynElem` itself owns-storage (a dynarray of owns
         *     pointers) -- each element is read (`LOOKUP`, a value read)
         *     into a fresh per-iteration hidden temp, purely so it has a
         *     *name* -- "GT_DESTRUCT name" is this bytecode's only
         *     existing "free this owns pointer" primitive, and (per this
         *     pass's own header) it has no confirmed "act on a bare
         *     stack value" form, unlike `CALL`, whose own operand is
         *     just a routine name, never a location. The temp is then
         *     null-guarded and (if `elementRoutineFor` found a routine)
         *     recursed into exactly like an ordinary owns member, then
         *     unconditionally `GT_DESTRUCT`ed -- safe even on null, the
         *     runtime's own documented no-op there. The temp itself
         *     needs no cleanup of its own: it's an ordinary local from
         *     this point on, reused (reassigned, not re-`ALLOC`'d) every
         *     iteration, gone the same way any other local already is
         *     once the function returns.
         *   - `dynElem` an inline (no-storage) owns-bearing composite
         *     value -- no temp needed at all: `LOOKUP_LHS` produces the
         *     element's own address directly as a bare stack value, and
         *     since this pass's own generated routines are already
         *     called through nothing more than "bare PUSH / CALL" (no
         *     named operand on `CALL` itself), that address can be
         *     handed straight to the routine with no name ever needed
         *     for it -- the identical "no guard, no destruct, just
         *     recurse" shape `emitValueDestruct`'s own inline-struct-
         *     member case already uses.
         *   - Neither of the above -- this method is never called at
         *     all (see the caller): nothing to do, exactly like an
         *     ordinary scalar/no-owns-bearing member.
         */
        private void emitDynArrayElementDrop(List<List<BytecodeToken>> out, String path, String canonicalType,
                String dynElem) {
            CanonicalType elemT = CanonicalType.parse(dynElem);
            boolean elemIsOwnsPointer = elemT.isOwnsStorage();
            boolean elemIsInlineOwnsStruct = !elemIsOwnsPointer
                    && structTable.hasStruct(elemT.baseType) && structTable.isOwnsBearing(elemT.baseType);
            if (!elemIsOwnsPointer && !elemIsInlineOwnsStruct) {
                return; // a plain scalar element, or a non-owns-bearing struct element -- nothing to do
            }

            String counter = newTemp(out, "gt_loop_i", "mut_u64");
            emit(out, "ADDR " + counter + " mut_u64");
            emit(out, "PUSH 0 indeterminate_u64");
            emit(out, "ASSIGN mut_u64 mut_u64 mut_u64");

            String topLabel = newLabel("gt_loop");
            String endLabel = newLabel("gt_loop_end");
            emit(out, topLabel + ":");
            emit(out, "PUSH " + counter + " mut_u64");
            emit(out, "PUSH " + path + " " + canonicalType);
            emit(out, "LEN");
            emit(out, "LT mut_u64 indeterminate_u64 indeterminate_bool");
            emit(out, "CMP");
            emit(out, "JMP " + endLabel);

            if (elemIsOwnsPointer) {
                String elemTemp = newTemp(out, "gt_loop_elem", dynElem);
                emit(out, "ADDR " + elemTemp + " " + dynElem);
                emit(out, "PUSH " + path + " " + canonicalType);
                emit(out, "PUSH " + counter + " mut_u64");
                emit(out, "LOOKUP " + canonicalType + " mut_u64 " + dynElem);
                emit(out, "ASSIGN " + dynElem + " " + dynElem + " " + dynElem);

                String elemRoutine = elementRoutineFor(dynElem);
                if (!elemRoutine.equals("NONE")) {
                    String skipRecurse = newLabel("gt_loop_elem_skip");
                    emit(out, "PUSH " + elemTemp + " " + dynElem);
                    emit(out, "PUSH null " + dynElem);
                    emit(out, "NEQ " + dynElem + " " + dynElem + " imut_bool");
                    emit(out, "CMP");
                    emit(out, "JMP " + skipRecurse);
                    emit(out, "PUSH " + elemTemp + " " + dynElem);
                    emit(out, "CALL " + elemRoutine);
                    emit(out, skipRecurse + ":");
                }
                emit(out, "GT_DESTRUCT " + elemTemp);
            } else {
                String elemRoutine = elementRoutineFor(dynElem); // guaranteed != "NONE" here, by elemIsInlineOwnsStruct
                emit(out, "PUSH " + path + " " + canonicalType);
                emit(out, "PUSH " + counter + " mut_u64");
                emit(out, "LOOKUP_LHS " + canonicalType + " mut_u64 " + dynElem);
                emit(out, "CALL " + elemRoutine);
            }

            emit(out, "ADDR " + counter + " mut_u64");
            emit(out, "PUSH " + counter + " mut_u64");
            emit(out, "INC mut_u64 indeterminate_u64");
            emit(out, "ASSIGN mut_u64 indeterminate_u64 indeterminate_u64");
            emit(out, "JMP " + topLabel);
            emit(out, endLabel + ":");
        }
    }
}
```

### FILE: src/main/java/caspien/lowerorder/EnumTable.java
```java
package caspien.lowerorder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every "ENUM" line's own variant data, read directly out of the
 * program's own already-emitted "ENUM Name variant1 ... variantN ..."
 * lines -- the same self-describing-bytecode approach StructTable
 * already takes for struct layout. Only the two shapes
 * MembershipLoweringPass actually needs are kept:
 *
 *   - range-valued ("ENUM Name variant1 lo1..hi1 variant2 lo2..hi2 ...")
 *     -- used for exactly one compiler-synthesized enum, "Class"
 *     (TypeChecker.generateClassHierarchyEnums): every struct/abstract's
 *     own pre-order-DFS subtree range, keyed by that struct/abstract's
 *     own name.
 *   - value-valued ("ENUM Name variant1 val1 variant2 val2 ...") -- used
 *     both for ordinary user "guaranteed" enums and for every
 *     compiler-synthesized "$enum_for_OwnerName" enum
 *     (TypeChecker.registerEnumForEnum): each direct implementer's/
 *     child's own ClassID.
 *
 * A plain enum (no values at all, "ENUM Name variant1 variant2 ...")
 * used to carry nothing any pass here needed, and was left untracked
 * entirely -- true right up until address lowering itself needed to
 * resolve an "EnumName.Variant" reference down to its own concrete
 * value (see AddressLoweringPass's own "literal enum values resolve to
 * their actual values" rewrite): a plain enum's variants are never
 * explicitly numbered in source, but they still each get one, "as in
 * C" -- 0, 1, 2, ... in declaration order -- the moment anything
 * downstream actually needs a number rather than a name. `variantValue`
 * below is the one place that number is computed, for either shape
 * (explicit or implicit) uniformly.
 *
 * Every shape here (range-valued, explicit-value-valued, and now plain)
 * is distinguished purely by inspecting the token immediately after the
 * first variant name (see isRangeLiteral/isPlainU64Literal below); "no
 * partial mixing" (an enum is always uniformly one shape, confirmed
 * directly against TypeChecker's own collectEnum invariant) means
 * checking just the first pair is always enough -- a plain enum simply
 * has no second token to check at all for its first variant (or, for a
 * two-variant plain enum, a second token that's neither a plain u64 nor
 * a range literal -- either way, "not value/range-shaped" is exactly
 * "plain," so every remaining token is a bare variant name, one per
 * variant, in declaration order). No regex, anywhere -- manual
 * character-by-character checks throughout, matching this project's own
 * coding convention.
 */
public class EnumTable {

    private static final class RangeInfo {
        final long lo;
        final long hi;
        RangeInfo(long lo, long hi) {
            this.lo = lo;
            this.hi = hi;
        }
    }

    private final Map<String, Map<String, RangeInfo>> rangeEnums = new LinkedHashMap<>();
    private final Map<String, Map<String, Long>> valueEnums = new LinkedHashMap<>();
    private final Map<String, List<String>> valueVariantOrder = new LinkedHashMap<>();
    private final Map<String, List<String>> plainEnumVariantOrder = new LinkedHashMap<>();

    public static EnumTable read(List<List<BytecodeToken>> lines) {
        EnumTable table = new EnumTable();
        for (List<BytecodeToken> line : lines) {
            if (line.isEmpty() || !line.get(0).text.equals("ENUM") || line.size() < 2) {
                continue;
            }
            String enumName = line.get(1).text;
            List<String> rest = new ArrayList<>();
            for (int i = 2; i < line.size(); i++) {
                rest.add(line.get(i).text);
            }
            if (rest.isEmpty()) {
                continue; // malformed -- "ENUM Name" with no variants at all, shouldn't happen
            }
            // Value-carrying and range-carrying shapes are always exactly
            // two tokens per variant (name, value); tested by inspecting
            // just the token right after the very first variant name --
            // "no partial mixing" (TypeChecker's own collectEnum
            // invariant) means every remaining pair is guaranteed the
            // same shape. Anything else (including an odd-sized
            // remainder, which a value/range enum can never produce) is
            // a plain enum -- see this class's own header.
            boolean pairShaped = rest.size() >= 2 && rest.size() % 2 == 0
                    && (isRangeLiteral(rest.get(1)) || isPlainU64Literal(rest.get(1)));
            if (pairShaped && isRangeLiteral(rest.get(1))) {
                Map<String, RangeInfo> variants = new LinkedHashMap<>();
                for (int i = 0; i + 1 < rest.size(); i += 2) {
                    long[] bounds = parseRangeLiteral(rest.get(i + 1));
                    if (bounds != null) {
                        variants.put(rest.get(i), new RangeInfo(bounds[0], bounds[1]));
                    }
                }
                table.rangeEnums.put(enumName, variants);
            } else if (pairShaped) {
                Map<String, Long> variants = new LinkedHashMap<>();
                List<String> order = new ArrayList<>();
                for (int i = 0; i + 1 < rest.size(); i += 2) {
                    Long v = parseU64Literal(rest.get(i + 1));
                    if (v != null) {
                        variants.put(rest.get(i), v);
                        order.add(rest.get(i));
                    }
                }
                table.valueEnums.put(enumName, variants);
                table.valueVariantOrder.put(enumName, order);
            } else {
                // Plain enum -- one bare variant name per token, in
                // declaration order; "as in C," each gets an implicit
                // 0, 1, 2, ... value the moment anything actually asks
                // for one (see `variantValue`).
                table.plainEnumVariantOrder.put(enumName, new ArrayList<>(rest));
            }
        }
        return table;
    }

    /**
     * The variant's own concrete scalar integer value -- explicit for a
     * value-valued enum (including every compiler-synthesized
     * "$enum_for_..." ClassID enum), or implicit/sequential ("as in C",
     * 0/1/2/... in declaration order) for a plain one. Returns null for
     * a range-valued enum (no single scalar value exists for one of
     * those -- see `classRangeOf` instead) or when `enumName`/
     * `variantName` isn't a real variant of any tracked enum at all
     * (most commonly because `enumName` isn't an enum, e.g. an ordinary
     * struct/instance name that merely happens to precede a '.' too --
     * callers resolving an arbitrary dotted name should treat null here
     * as "not an enum reference," not as an error).
     */
    public Long variantValue(String enumName, String variantName) {
        Map<String, Long> values = valueEnums.get(enumName);
        if (values != null) {
            return values.get(variantName);
        }
        List<String> order = plainEnumVariantOrder.get(enumName);
        if (order != null) {
            int idx = order.indexOf(variantName);
            return idx < 0 ? null : (long) idx;
        }
        return null;
    }

    /**
     * The "Class" enum's own [lo,hi] pre-order-DFS subtree range for a
     * struct/abstract name -- confirmed by TypeChecker.walkClassHierarchy
     * to always be exactly one contiguous range per name -- or null when
     * there's no synthesized "Class" enum at all (no eligible struct in
     * the program) or `name` isn't one of its variants (most commonly
     * because it's an interface, which never gets a "Class" entry --
     * only "$enum_for_" ones, see implementerClassIdsOf).
     */
    public long[] classRangeOf(String structOrAbstractName) {
        Map<String, RangeInfo> classEnum = rangeEnums.get("Class");
        if (classEnum == null) {
            return null;
        }
        RangeInfo info = classEnum.get(structOrAbstractName);
        return info == null ? null : new long[]{info.lo, info.hi};
    }

    /**
     * Every direct implementer's/child's own ClassID value, in
     * declaration order, from the compiler-synthesized
     * "$enum_for_<ownerName>" enum (TypeChecker.registerEnumForEnum) --
     * or an empty list when there's no such enum (no implementers were
     * ever registered for that interface/parent, or `ownerName` was
     * never one of the compiler's own synthesized enum owners at all).
     */
    public List<Long> implementerClassIdsOf(String ownerName) {
        String syntheticName = "$enum_for_" + ownerName;
        Map<String, Long> variants = valueEnums.get(syntheticName);
        List<String> order = valueVariantOrder.get(syntheticName);
        List<Long> result = new ArrayList<>();
        if (variants == null || order == null) {
            return result;
        }
        for (String name : order) {
            result.add(variants.get(name));
        }
        return result;
    }

    /** Every explicit (variant,value) pair of an ordinary user-written value-valued (a.k.a. "guaranteed") enum, in declaration order -- empty if `enumName` isn't a value-valued enum at all. */
    public List<Map.Entry<String, Long>> valueVariantsOf(String enumName) {
        Map<String, Long> variants = valueEnums.get(enumName);
        List<String> order = valueVariantOrder.get(enumName);
        List<Map.Entry<String, Long>> result = new ArrayList<>();
        if (variants == null || order == null) {
            return result;
        }
        for (String name : order) {
            result.add(new java.util.AbstractMap.SimpleEntry<>(name, variants.get(name)));
        }
        return result;
    }

    private static boolean isPlainU64Literal(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static Long parseU64Literal(String s) {
        return isPlainU64Literal(s) ? Long.parseLong(s) : null;
    }

    private static boolean isRangeLiteral(String s) {
        return parseRangeLiteral(s) != null;
    }

    /** "lo..hi" -- both sides plain u64 literals, exactly one ".." separator -- or null if `s` doesn't match that shape at all. */
    private static long[] parseRangeLiteral(String s) {
        int dotDot = -1;
        for (int i = 0; i + 1 < s.length(); i++) {
            if (s.charAt(i) == '.' && s.charAt(i + 1) == '.') {
                dotDot = i;
                break;
            }
        }
        if (dotDot < 0) {
            return null;
        }
        String left = s.substring(0, dotDot);
        String right = s.substring(dotDot + 2);
        if (!isPlainU64Literal(left) || !isPlainU64Literal(right)) {
            return null;
        }
        return new long[]{Long.parseLong(left), Long.parseLong(right)};
    }
}
```

### FILE: src/main/java/caspien/lowerorder/FloatTempPass.java
```java
package caspien.lowerorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * f32 expression temporaries in xmm registers -- runs last, on the final register-form text, only when compiler.config says
 * "float-temporaries-in-registers: on" (which needs "deferred-operands: on").
 *
 * <p>The register-form pass models every temporary as a general register holding a bit pattern, so each float operation
 * copies its operands into xmm scratch registers, computes, and copies the result back (two {@code movd} per operation).
 * This pass finds a float temp that is produced by a float operation and consumed only by float operations, and keeps it in
 * an xmm register "%yK" for its whole life:
 * <pre>
 *   producers:  R_FBIN OP n %tD a b   -> R_FBINX OP n %yK a b            (n = 4 for f32, 8 for f64)
 *               R_LD n %tD addr       -> R_LDX n %yK addr
 *               R_XTOG n %tD %xJ      -> (uses read %xJ directly when it is not rewritten in between) or R_XMOV %yK %xJ
 *   consumers:  the a/b operand of R_FBIN / R_FBINX / R_FCMP (size n), R_FARG n, R_RETF        -> token %tD becomes %yK
 *               R_ST n addr %tD -> R_STX n addr %yK        R_GTOX n %xJ %tD -> R_XMOV %xJ %yK
 * All the instructions of one chain have the same width n. A chain must have at least one genuinely float consumer (a float
 * operation, a float argument or a float return), so a plain integer copy R_LD 8 / R_ST 8 is never turned into xmm moves.
 * </pre>
 * A "chain" is one definition of a temp up to its last read (the next write of the same temp ends it). A chain is rewritten only
 * if EVERY read of it is one of the consumers above (all-or-nothing, decided on the final text, nothing to roll back), the span
 * between definition and last read holds only register-form lines (no label, jump, call, stack-form instruction), at most
 * {@link #Y_COUNT} chains are live at once, and, if it lies inside a call bracket, that call passes fewer than
 * {@link #Y_COUNT} float arguments (the SysV %y registers are xmm4-7, which are argument registers). Anything else stays as it was.
 * Register-form temps are never live across labels, jumps or call brackets, so a %y register is never live across a call.
 */
public class FloatTempPass {

    /** how many xmm temporaries exist (%y0..%y3 -> SysV xmm4-7, win64 xmm12-15) */
    public static final int Y_COUNT = 4;

    private final boolean enabled;

    public FloatTempPass(boolean enabled) {
        this.enabled = enabled;
    }

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        if (!enabled) {
            return lines;
        }
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        int i = 0;
        while (i < lines.size()) {
            List<BytecodeToken> line = lines.get(i);
            if (!line.isEmpty() && line.get(0).text.equals("FUNC_START")) {
                int end = i;
                while (end < lines.size() && !(!lines.get(end).isEmpty() && lines.get(end).get(0).text.equals("FUNC_END"))) {
                    end++;
                }
                int stop = Math.min(end + 1, lines.size());
                processFunction(lines.subList(i, stop), out);
                i = stop;
                continue;
            }
            out.add(line);
            i++;
        }
        return out;
    }

    private static final Set<String> KNOWN_R = new HashSet<>(Arrays.asList(
            "R_MOV", "R_LD", "R_ST", "R_LEA", "R_BIN", "R_UN", "R_FBIN", "R_FCMP", "R_RMW", "R_ARG", "R_ARGA", "R_FARG",
            "R_RET", "R_RETF", "R_PUSH", "R_PUSHA", "R_SETV", "R_XTOG", "R_GTOX", "R_LDX", "R_STX", "R_FBINX", "R_XMOV"));

    /** operand index of the destination temp, or -1 */
    private static int dstIndex(String m) {
        switch (m) {
            case "R_LD": case "R_MOV": case "R_XTOG": return 2;
            case "R_LEA": return 1;
            case "R_BIN": case "R_UN": case "R_FBIN": case "R_FCMP": return 3;
            default: return -1;
        }
    }

    private static final class Chain {
        int def;
        int last = -1;
        boolean ok = true;
        String temp;
        List<int[]> uses = new ArrayList<>(); // {line, operand index}
        int y = -1;
        String w = "4";
        boolean floatUse = false;
    }

    private static String m(List<String> l) {
        return l.isEmpty() ? "" : l.get(0);
    }

    private static boolean isR(List<String> l) {
        String mm = m(l);
        return mm.startsWith("R_") && !mm.equals("R_BRF");
    }

    /** is operand k of line l (which reads a float temp) a place this pass can turn into an xmm register? */
    private static boolean consumerOk(List<String> l, int k, String w) {
        switch (m(l)) {
            case "R_FBIN": case "R_FCMP": case "R_FBINX":
                return l.size() == 6 && l.get(2).equals(w) && (k == 4 || k == 5);
            case "R_FARG":
                return l.size() == 4 && l.get(2).equals(w) && k == 3;
            case "R_RETF":
                return l.size() == 2 && k == 1;
            case "R_ST":
                return l.size() == 4 && l.get(1).equals(w) && k == 3 && !l.get(2).equals(l.get(3));
            case "R_GTOX":
                return l.size() == 4 && l.get(1).equals(w) && k == 3;
            default:
                return false;
        }
    }

    /** a consumer that is a real float operation (not a plain copy) */
    private static boolean isFloatConsumer(List<String> l) {
        switch (m(l)) {
            case "R_FBIN": case "R_FCMP": case "R_FBINX": case "R_FARG": case "R_RETF":
                return true;
            default:
                return false;
        }
    }

    private void processFunction(List<List<BytecodeToken>> fn, List<List<BytecodeToken>> out) {
        int n = fn.size();
        List<List<String>> t = new ArrayList<>(n);
        for (List<BytecodeToken> l : fn) {
            List<String> r = new ArrayList<>(l.size());
            for (BytecodeToken b : l) {
                r.add(b.text);
            }
            t.add(r);
        }
        // float-argument count of every call bracket (an %y register may not sit on an argument register that is in use)
        int[] bracketFargs = new int[n];
        int[] open = new int[n];
        int sp = 0;
        for (int i = 0; i < n; i++) {
            String mm = m(t.get(i));
            if (mm.equals("CC_START")) {
                open[sp++] = i;
            } else if (mm.equals("CC_END")) {
                if (sp > 0) {
                    sp--;
                }
            }
            if (sp > 0) {
                int f = fargIndex(t.get(i));
                if (f >= 0) {
                    for (int q = 0; q < sp; q++) {
                        bracketFargs[open[q]] = Math.max(bracketFargs[open[q]], f + 1);
                    }
                }
            }
        }
        int[] inBracketMax = new int[n];
        sp = 0;
        for (int i = 0; i < n; i++) {
            String mm = m(t.get(i));
            if (mm.equals("CC_START")) {
                open[sp++] = i;
            } else if (mm.equals("CC_END")) {
                if (sp > 0) {
                    sp--;
                }
            }
            int mx = 0;
            for (int q = 0; q < sp; q++) {
                mx = Math.max(mx, bracketFargs[open[q]]);
            }
            inBracketMax[i] = mx;
        }

        // find chains
        List<Chain> chains = new ArrayList<>();
        for (int d = 0; d < n; d++) {
            List<String> l = t.get(d);
            String mm = m(l);
            String tmp = null;
            String w = null;
            if (mm.equals("R_FBIN") && l.size() == 6 && (l.get(2).equals("4") || l.get(2).equals("8"))) {
                tmp = l.get(3);
                w = l.get(2);
            } else if (mm.equals("R_LD") && l.size() == 4 && (l.get(1).equals("4") || l.get(1).equals("8"))) {
                tmp = l.get(2);
                w = l.get(1);
            } else if (mm.equals("R_XTOG") && l.size() == 4) {
                tmp = l.get(2);
                w = l.get(1);
            }
            if (tmp == null || !tmp.startsWith("%t")) {
                continue;
            }
            Chain c = new Chain();
            c.def = d;
            c.temp = tmp;
            c.w = w;
            if (mm.equals("R_FBIN")) {
                c.floatUse = true; // the definition is itself a float operation: a chain whose only reader is a store (`x -= ...` on an array element) is worth keeping in xmm
            }
            for (int j = d + 1; j < n; j++) {
                List<String> lj = t.get(j);
                String mj = m(lj);
                boolean mentions = false;
                for (int k = 1; k < lj.size(); k++) {
                    if (lj.get(k).equals(tmp)) {
                        mentions = true;
                    }
                }
                if (!isR(lj) || !KNOWN_R.contains(mj)) {
                    if (mentions) {
                        c.ok = false;
                    }
                    break; // end of the straight-line run: register-form temps are never live past it
                }
                int dst = dstIndex(mj);
                boolean writes = false;
                for (int k = 1; k < lj.size(); k++) {
                    if (!lj.get(k).equals(tmp)) {
                        continue;
                    }
                    if (k == dst) {
                        writes = true;
                    } else {
                        if (!consumerOk(lj, k, c.w)) {
                            c.ok = false;
                        }
                        if (isFloatConsumer(lj)) {
                            c.floatUse = true;
                        }
                        c.uses.add(new int[] {j, k});
                        c.last = j;
                    }
                }
                if (writes) {
                    break;
                }
            }
            if (c.uses.isEmpty() || !c.floatUse) {
                c.ok = false;
            }
            if (c.ok && inBracketMax[d] >= Y_COUNT) {
                c.ok = false;
            }
            if (c.ok && c.last >= 0 && inBracketMax[c.last] >= Y_COUNT) {
                c.ok = false;
            }
            chains.add(c);
        }

        // assign %y registers (a chain may take the register a chain frees on the very line it is defined)
        boolean[] freeAt = new boolean[Y_COUNT];
        Arrays.fill(freeAt, true);
        int[] busyUntil = new int[Y_COUNT];
        Arrays.fill(busyUntil, -1);
        for (Chain c : chains) {
            if (!c.ok) {
                continue;
            }
            int pick = -1;
            for (int k = 0; k < Y_COUNT; k++) {
                if (busyUntil[k] <= c.def) {
                    pick = k;
                    break;
                }
            }
            if (pick < 0) {
                c.ok = false;
                continue;
            }
            c.y = pick;
            busyUntil[pick] = c.last;
        }

        boolean changed = false;
        for (Chain c : chains) {
            if (!c.ok) {
                continue;
            }
            String y = "%y" + c.y;
            List<String> def = t.get(c.def);
            String dm = m(def);
            String repl = y;
            List<String> newDef = null;
            if (dm.equals("R_FBIN")) {
                newDef = new ArrayList<>(def);
                newDef.set(0, "R_FBINX");
                newDef.set(3, y);
            } else if (dm.equals("R_LD")) {
                newDef = new ArrayList<>(Arrays.asList("R_LDX", c.w, y, def.get(3)));
            } else { // R_XTOG n %tD %xJ
                String xj = def.get(3);
                boolean rewritten = false;
                for (int j = c.def + 1; j <= c.last && !rewritten; j++) {
                    rewritten = writesXmm(t.get(j), xj);
                }
                if (!rewritten) {
                    newDef = new ArrayList<>(Arrays.asList("R_XMOV", xj, xj)); // placeholder, dropped below
                    repl = xj;
                } else {
                    newDef = new ArrayList<>(Arrays.asList("R_XMOV", y, xj));
                }
            }
            for (int[] u : c.uses) {
                List<String> lu = t.get(u[0]);
                lu.set(u[1], repl);
                switch (m(lu)) {
                    case "R_ST":
                        lu.set(0, "R_STX");
                        break;
                    case "R_GTOX": { // R_GTOX n %xJ %y -> R_XMOV %xJ %y
                        List<String> mv = new ArrayList<>(Arrays.asList("R_XMOV", lu.get(2), lu.get(3)));
                        t.set(u[0], mv);
                        break;
                    }
                    case "R_FBIN": case "R_FCMP": case "R_FBINX": case "R_FARG": case "R_RETF":
                        break;
                    default:
                        break;
                }
            }
            t.set(c.def, newDef);
            changed = true;
        }
        if (!changed) {
            out.addAll(fn);
            return;
        }
        for (int i = 0; i < n; i++) {
            List<String> l = t.get(i);
            List<BytecodeToken> orig = fn.get(i);
            if (l.size() == 3 && l.get(0).equals("R_XMOV") && l.get(1).equals(l.get(2))) {
                continue; // an R_XTOG whose uses now read the variable directly
            }
            if (sameText(l, orig)) {
                out.add(orig);
                continue;
            }
            BytecodeToken ref = orig.isEmpty() ? null : orig.get(0);
            List<BytecodeToken> r = new ArrayList<>(l.size());
            for (String s : l) {
                r.add(new BytecodeToken(s, ref == null ? null : ref.file, ref == null ? 0 : ref.line, BytecodeToken.Kind.CODE));
            }
            out.add(r);
        }
    }

    private static boolean sameText(List<String> a, List<BytecodeToken> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i).text)) {
                return false;
            }
        }
        return true;
    }

    /** does this line write float variable register xj? */
    private static boolean writesXmm(List<String> l, String xj) {
        switch (m(l)) {
            case "R_FBINX": return l.size() > 3 && l.get(3).equals(xj);
            case "R_GTOX": case "R_LDX": case "R_POPX": return l.size() > 2 && l.get(2).equals(xj);
            case "R_XMOV": return l.size() > 1 && l.get(1).equals(xj);
            case "R_GETRETF": return l.size() > 2 && l.get(2).equals(xj);
            default: return false;
        }
    }

    /** the float argument register index a line loads, or -1 */
    private static int fargIndex(List<String> l) {
        String mm = m(l);
        try {
            if (mm.equals("R_FARG") && l.size() > 1) {
                return Integer.parseInt(l.get(1));
            }
            if (mm.equals("POP") && l.size() > 1 && l.get(1).startsWith("FARG")) {
                return Integer.parseInt(l.get(1).substring(4));
            }
        } catch (NumberFormatException e) {
            return 99;
        }
        return -1;
    }
}
```

### FILE: src/main/java/caspien/lowerorder/IndexedAccessPass.java
```java
package caspien.lowerorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Indexed global access on the final register-form text (always on; a pure code-quality rewrite, runs after JumpCleanupPass).
 * A global array element used to cost two address instructions:
 *
 *   R_LEA %tX &sym %vK scale ; ... ; R_LD n %tD %tX        ->  R_LDI n %tD &sym %vK scale
 *   R_LEA %tX &sym %vK scale ; ... ; R_ST n %tX src        ->  R_STI n &sym %vK scale src
 *
 * (`leaq sym(%rip),%rax; leaq (%rax,%r13,8),%rax; movq (%rax),...` becomes `leaq sym(%rip),%rax; movq (%rax,%r13,8),...`:
 * RIP-relative addressing cannot carry an index register, so the base address still takes one lea.)
 * The float forms fuse the same way (`R_LDX n %xK|%yK %tX` -> `R_LDXI n %xK|%yK &sym %vK n`, `R_STX n %tX %xK|%yK` -> `R_STXI n &sym %vK n %xK|%yK`) with scale = access width (4 for f32, 8 for f64).
 * Only a global base, an index held in a variable register (%v), scale 8 and an 8-byte access (the only shape that occurs in any program of tests/ or benchmarks/; the backend also has the 1/2/4-byte forms, which no program reaches and which are therefore unexecuted). The lines
 * between the lea and its consumer must be known register-form mnemonics that neither mention %tX nor write the index register
 * (the index is read later than before), and %tX must be dead after the consumer (never read again before a pure redefinition,
 * a label or a jump; register-form temps are never live across those).
 */
public class IndexedAccessPass {

    private static final int MAX_GAP = 40;

    /** position of the register a mnemonic writes (-1: writes none) */
    private static final Map<String, Integer> DEST = Map.ofEntries(
            Map.entry("R_MOV", 2), Map.entry("R_LD", 2), Map.entry("R_LDI", 2), Map.entry("R_BIN", 3), Map.entry("R_UN", 3),
            Map.entry("R_LEA", 1), Map.entry("R_SETV", 2), Map.entry("R_RMW", 3), Map.entry("R_ST", -1), Map.entry("R_STI", -1),
            // float forms: only R_FBIN, R_FCMP and R_XTOG write a general register (a temp); the rest write xmm registers or memory
            Map.entry("R_FBIN", 3), Map.entry("R_FCMP", 3), Map.entry("R_XTOG", 2), Map.entry("R_GTOX", -1), Map.entry("R_LDX", -1),
            Map.entry("R_STX", -1), Map.entry("R_FBINX", -1), Map.entry("R_XMOV", -1), Map.entry("R_LDXI", -1), Map.entry("R_STXI", -1));

    private static final Set<String> PURE_DEF = Set.of("R_MOV", "R_LD", "R_LDI", "R_LEA", "R_BIN", "R_UN", "R_FBIN", "R_FCMP", "R_XTOG");

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        int n = lines.size();
        List<List<BytecodeToken>> cur = new ArrayList<>(lines);
        boolean[] gone = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (gone[i]) {
                continue;
            }
            List<BytecodeToken> lea = cur.get(i);
            if (!isIndexedLea(lea)) {
                continue;
            }
            String x = t(lea, 1), idx = t(lea, 3);
            for (int j = i + 1; j < n && j - i <= MAX_GAP; j++) {
                if (gone[j]) {
                    continue;
                }
                List<BytecodeToken> l = cur.get(j);
                if (!mentions(l, x)) {
                    if (!passable(l, idx)) {
                        break;
                    }
                    continue;
                }
                List<BytecodeToken> fused = fuse(lea, l, x);
                if (fused != null && deadAfter(cur, gone, j + 1, x, fused.get(0).text.equals("R_LDI") && t(l, 2).equals(x))) {
                    cur.set(j, fused);
                    gone[i] = true;
                }
                break;
            }
        }
        List<List<BytecodeToken>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (!gone[i]) {
                out.add(cur.get(i));
            }
        }
        return out;
    }

    private static boolean isIndexedLea(List<BytecodeToken> l) {
        if (l.size() != 5 || !t(l, 0).equals("R_LEA") || !t(l, 1).startsWith("%t") || !t(l, 2).startsWith("&") || !t(l, 3).startsWith("%v")) {
            return false;
        }
        String s = t(l, 4);
        return s.equals("8") || s.equals("4");
    }

    /** the fused line for consumer l of the address in x, or null */
    private static List<BytecodeToken> fuse(List<BytecodeToken> lea, List<BytecodeToken> l, String x) {
        if (l.size() != 4) {
            return null;
        }
        BytecodeToken h = l.get(0);
        List<BytecodeToken> n = new ArrayList<>();
        // float element (f32: scale 4, f64: scale 8): the access width must equal the scale
        if (t(l, 0).equals("R_LDX") && t(l, 1).equals(t(lea, 4)) && isXmm(t(l, 2)) && t(l, 3).equals(x)) {
            n.add(new BytecodeToken("R_LDXI", h.file, h.line, h.kind));
            n.add(l.get(1));
            n.add(l.get(2));
            n.add(lea.get(2));
            n.add(lea.get(3));
            n.add(lea.get(4));
            return n;
        }
        if (t(l, 0).equals("R_STX") && t(l, 1).equals(t(lea, 4)) && t(l, 2).equals(x) && isXmm(t(l, 3))) {
            n.add(new BytecodeToken("R_STXI", h.file, h.line, h.kind));
            n.add(l.get(1));
            n.add(lea.get(2));
            n.add(lea.get(3));
            n.add(lea.get(4));
            n.add(l.get(3));
            return n;
        }
        if (!okSize(t(l, 1)) || !t(lea, 4).equals("8")) {
            return null;
        }
        if (t(l, 0).equals("R_LD") && t(l, 2).startsWith("%t") && t(l, 3).equals(x)) {
            n.add(new BytecodeToken("R_LDI", h.file, h.line, h.kind));
            n.add(l.get(1));
            n.add(l.get(2));
            n.add(lea.get(2));
            n.add(lea.get(3));
            n.add(lea.get(4));
            return n;
        }
        if (t(l, 0).equals("R_ST") && t(l, 2).equals(x) && !t(l, 3).equals(x)
                && (t(l, 3).startsWith("#") || t(l, 3).startsWith("%t") || t(l, 3).startsWith("%v"))) {
            n.add(new BytecodeToken("R_STI", h.file, h.line, h.kind));
            n.add(l.get(1));
            n.add(lea.get(2));
            n.add(lea.get(3));
            n.add(lea.get(4));
            n.add(l.get(3));
            return n;
        }
        return null;
    }

    private static boolean isXmm(String tok) {
        return tok.startsWith("%x") || tok.startsWith("%y");
    }

    private static boolean okSize(String s) {
        return s.equals("8");
    }

    /** a line that may sit between the lea and its consumer: known mnemonic, does not write the index register */
    private static boolean passable(List<BytecodeToken> l, String idx) {
        Integer d = DEST.get(t(l, 0));
        if (d == null) {
            return false;
        }
        return d < 0 || d >= l.size() || !t(l, d).equals(idx);
    }

    private static boolean mentions(List<BytecodeToken> l, String tok) {
        for (int k = 1; k < l.size(); k++) {
            if (t(l, k).equals(tok)) {
                return true;
            }
        }
        return false;
    }

    /** x is dead from line `from` on; selfDefined: the consumer itself redefines x */
    private static boolean deadAfter(List<List<BytecodeToken>> cur, boolean[] gone, int from, String x, boolean selfDefined) {
        if (selfDefined) {
            return true;
        }
        for (int j = from; j < cur.size(); j++) {
            if (gone[j]) {
                continue;
            }
            List<BytecodeToken> l = cur.get(j);
            if (l.size() == 1 && t(l, 0).startsWith("@")) {
                return true;
            }
            if (t(l, 0).equals("JMP")) {
                return true;
            }
            if (!mentions(l, x)) {
                continue;
            }
            if (!PURE_DEF.contains(t(l, 0))) {
                return false;
            }
            int d = DEST.get(t(l, 0));
            if (d >= l.size() || !t(l, d).equals(x)) {
                return false;
            }
            for (int k = 1; k < l.size(); k++) {
                if (k != d && t(l, k).equals(x)) {
                    return false;
                }
            }
            return true;
        }
        return true;
    }

    private static String t(List<BytecodeToken> l, int i) {
        return l.get(i).text;
    }
}
```

### FILE: src/main/java/caspien/lowerorder/JumpCleanupPass.java
```java
package caspien.lowerorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Jump cleanup on the final register-form text (always on; a pure code-quality rewrite, runs after BranchFusionPass).
 * `if c { break }` and friends leave chains such as
 *
 *   R_BRC EQ 8 a b @L1 ; JMP @Lend ; JMP @L1 ; @L1:
 *
 * which assemble to `jne L1; jmp Lend; jmp L1; L1:`. Three local rewrites, repeated until nothing changes:
 *   1. a JMP or R_BRC directly after an unconditional JMP is unreachable (nothing can fall into it; a label would stop the
 *      scan) and is dropped;
 *   2. a JMP whose target label is the next thing in the text (only labels in between) is dropped;
 *   3. `R_BRC C s a b @L1 ; JMP @L2 ; @L1:` becomes `R_BRC C' s a b @L2 ; @L1:` with C' the opposite condition
 *      ("R_BRC C" jumps when NOT (a C b), so jumping to L2 when (a C b) holds is the jump of the opposite condition).
 * A `JMP` directly after a bare `CMP` (stack form) is the conditional jump and is never treated as unconditional.
 * Labels are never removed, so every other reference stays valid. Only JMP / R_BRC lines are ever deleted.
 */
public class JumpCleanupPass {

    private static final Map<String, String> OPPOSITE = Map.ofEntries(
            Map.entry("EQ", "NEQ"), Map.entry("NEQ", "EQ"),
            Map.entry("LT", "GT_EQ"), Map.entry("GT_EQ", "LT"),
            Map.entry("LT_EQ", "GT"), Map.entry("GT", "LT_EQ"),
            Map.entry("SLT", "SGT_EQ"), Map.entry("SGT_EQ", "SLT"),
            Map.entry("SLT_EQ", "SGT"), Map.entry("SGT", "SLT_EQ"));

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        List<List<BytecodeToken>> cur = lines;
        for (int round = 0; round < 8; round++) {
            List<List<BytecodeToken>> next = step(cur);
            boolean same = next.size() == cur.size();
            cur = next;
            if (same) {
                break;
            }
        }
        return cur;
    }

    private List<List<BytecodeToken>> step(List<List<BytecodeToken>> in) {
        List<List<BytecodeToken>> out = new ArrayList<>(in.size());
        int n = in.size();
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = in.get(i);
            // 1. dead jump right after an unconditional jump
            if (!out.isEmpty() && isJmp(out.get(out.size() - 1)) && !followsCmp(out, out.size() - 1) && (isJmp(l) || isBrc(l))) {
                continue;
            }
            // 2. jump to the label that follows
            if (isJmp(l) && !followsCmp(out, out.size()) && nextLabelsContain(in, i + 1, t(l, 1))) {
                continue;
            }
            // 3. conditional jump over an unconditional one
            if (isBrc(l) && i + 2 < n && isJmp(in.get(i + 1)) && isLabelDef(in.get(i + 2), t(l, 5)) && OPPOSITE.containsKey(t(l, 1))
                    && !t(in.get(i + 1), 1).equals(t(l, 5))) {
                List<BytecodeToken> inv = new ArrayList<>(l);
                BytecodeToken c = l.get(1);
                inv.set(1, new BytecodeToken(OPPOSITE.get(c.text), c.file, c.line, c.kind));
                inv.set(5, in.get(i + 1).get(1));
                out.add(inv);
                i++; // the JMP is consumed
                continue;
            }
            out.add(l);
        }
        return out;
    }

    /** In stack form a `JMP` right after a bare `CMP` is the conditional jump (jump when the compared value is false), not a plain one. */
    private static boolean followsCmp(List<List<BytecodeToken>> list, int idx) {
        return idx > 0 && list.get(idx - 1).size() == 1 && t(list.get(idx - 1), 0).equals("CMP");
    }

    private static boolean nextLabelsContain(List<List<BytecodeToken>> in, int from, String label) {
        for (int j = from; j < in.size(); j++) {
            List<BytecodeToken> l = in.get(j);
            if (!isLabel(l)) {
                return false;
            }
            if (t(l, 0).equals(label + ":")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLabel(List<BytecodeToken> l) {
        return l.size() == 1 && t(l, 0).startsWith("@") && t(l, 0).endsWith(":");
    }

    private static boolean isLabelDef(List<BytecodeToken> l, String label) {
        return isLabel(l) && t(l, 0).equals(label + ":");
    }

    private static boolean isJmp(List<BytecodeToken> l) {
        return l.size() == 2 && t(l, 0).equals("JMP") && t(l, 1).startsWith("@");
    }

    private static boolean isBrc(List<BytecodeToken> l) {
        return l.size() == 6 && t(l, 0).equals("R_BRC") && t(l, 5).startsWith("@");
    }

    private static String t(List<BytecodeToken> l, int i) {
        return l.get(i).text;
    }
}
```

### FILE: src/main/java/caspien/lowerorder/LowerOrderGenerator.java
```java
package caspien.lowerorder;

import java.util.List;

/**
 * Top-level orchestrator for the real lowering work -- split out of the
 * original combined caspien-optimizer project into its own program,
 * confirmed directly: "the actual work thats currently been done in
 * the optimizer will just be called the LowerOrderGenerator." This
 * project takes the (still fairly high-order-shaped) bytecode text
 * produced by the sibling caspien-optimizer project's own shallow
 * pipeline and turns it into genuinely low-order bytecode: every name
 * resolved to a byte offset, every type erased to a byte size, every
 * struct/membership/ownership-aware construct expanded into its real,
 * addressable form.
 *
 * Pipeline, run once each, in this fixed order (unchanged from the
 * original project's own sequencing, minus the two now-relocated
 * placeholder reordering passes -- see caspien-optimizer's own
 * BytecodeOptimizer class doc for where those ended up and why):
 *
 *   membership lowering (see MembershipLoweringPass's own header)
 *   clone generation (see CloneGenerationPass's own header)
 *   drop-glue generation (see DropGlueGenerationPass's own header)
 *   arg-to-alloc lowering (see ArgToAllocLoweringPass's own header)
 *   address lowering (see AddressLoweringPass's own header)
 *   strength reduction (see StrengthReductionPass's own header) -- always on
 *   register-form (see RegisterFormPass's own header) -- only when
 *     compiler.config says "deferred-operands: on"
 *   register-variable promotion (see RegVarPromotionPass's own header) --
 *     renames the hot scalar locals the Optimizer hinted ("REGVAR") to
 *     variable registers when "variables-in-registers: on"; always strips
 *     the hints
 *   float temporaries (see FloatTempPass's own header)
 *   compare-and-branch fusion (see BranchFusionPass's own header) -- always on
 */
public class LowerOrderGenerator {

    private final MembershipLoweringPass membershipLowering = new MembershipLoweringPass();
    private final CloneGenerationPass cloneGeneration = new CloneGenerationPass();
    private final DropGlueGenerationPass dropGlueGeneration = new DropGlueGenerationPass();
    private final ArgToAllocLoweringPass argToAllocLowering = new ArgToAllocLoweringPass();
    private final AddressLoweringPass addressLowering = new AddressLoweringPass();
    private final RegisterFormPass registerForm = new RegisterFormPass();

    public List<List<BytecodeToken>> generate(List<List<BytecodeToken>> input) {
        List<List<BytecodeToken>> lines = input;
        lines = membershipLowering.run(lines).lines;
        lines = cloneGeneration.run(lines).lines;
        lines = dropGlueGeneration.run(lines).lines;
        lines = argToAllocLowering.run(lines).lines;
        lines = addressLowering.run(lines).lines;
        // Unsigned / and % by a constant power of two -> shift and mask (always on, see StrengthReductionPass).
        lines = new StrengthReductionPass().run(lines).lines;
        // `match i in arr` bounds test of a literal-bounds range without the range copy (always on, see RangeCheckFusionPass).
        lines = new RangeCheckFusionPass().run(lines);
        CompilerConfig config = CompilerConfig.load("compiler.config");
        // The end of a `for` range gets its own register hint (see RangeEndHintPass); only useful when variables can live in registers.
        if (config.deferredOperands && config.variablesInRegisters) {
            lines = new RangeEndHintPass().run(lines);
        }
        if (config.deferredOperands) {
            // 16-byte range constructions and copies become two 8-byte stores each (see RangeWordSplitPass).
            lines = new RangeWordSplitPass().run(lines);
            lines = registerForm.run(lines).lines;
        }
        // Always run: strips the REGHINT lines, and promotes variables only when both switches are on.
        lines = new RegVarPromotionPass(config.deferredOperands && config.variablesInRegisters, config.floatVariablesInRegisters).run(lines);
        // f32 temporaries in xmm registers (a no-op unless "float-temporaries-in-registers: on", which needs deferred-operands).
        lines = new FloatTempPass(config.deferredOperands && config.floatTemporariesInRegisters).run(lines);
        // A comparison that only feeds a jump compares and jumps (always on; a no-op without register-form lines).
        lines = new BranchFusionPass().run(lines);
        // Jump chains left by `if c { break }` and similar: dead jumps, jumps to the next label, conditional jump over a jump.
        lines = new JumpCleanupPass().run(lines);
        // A global array element addressed by a variable register: one lea fewer (R_LEA + R_LD/R_ST -> R_LDI/R_STI).
        lines = new IndexedAccessPass().run(lines);
        return lines;
    }
}
```

### FILE: src/main/java/caspien/lowerorder/Main.java
```java
package caspien.lowerorder;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * CLI entry point for the LowerOrderGenerator stage -- see
 * LowerOrderGenerator's own class doc for the pipeline this runs.
 *
 * Usage: lowerordergenerator -i input.txt output.txt
 *
 * `input.txt` is expected to be the sibling caspien-optimizer project's
 * own plain-text output (bytecode that has already reached the shallow
 * pipeline's fixed point) -- this project does not re-run any of that
 * project's own passes itself.
 *
 * AddressLoweringPass reads `compiler.config` (fixed path, resolved
 * relative to the current working directory) for calling-convention
 * data, the same way caspien-compiler's own Main does -- required,
 * missing entirely is a fatal error there, unchanged from before the
 * split.
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
            System.err.println("Usage: lowerordergenerator -i <input.txt> <output>");
            System.exit(1);
            return;
        }
        String inputPath = args[1];
        String outputPath = args[2];

        List<String> rawLines = Files.readAllLines(Paths.get(inputPath), StandardCharsets.UTF_8);

        BytecodeParser parser = new BytecodeParser();
        List<List<BytecodeToken>> parsed = parser.parse(rawLines, inputPath);

        LowerOrderGenerator generator = new LowerOrderGenerator();
        List<List<BytecodeToken>> result = generator.generate(parsed);

        BytecodeSerializer serializer = new BytecodeSerializer();
        String bytecode = serializer.serialize(result);

        System.out.print(bytecode);

        writeFile(outputPath, bytecode);
        writeFile("output.txt", bytecode);

        System.err.println("\n[info] wrote low-order bytecode to " + outputPath + " and output.txt ("
                + bytecode.lines().count() + " instructions/lines)");
    }

    private static void writeFile(String path, String content) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Paths.get(path), StandardCharsets.UTF_8))) {
            pw.print(content);
        }
    }
}
```

### FILE: src/main/java/caspien/lowerorder/MembershipLoweringPass.java
```java
package caspien.lowerorder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Lowers the four "type-overloaded-by-membership-kind" operators --
 * "IN" (its ordinary range/dynarray shape, and its guaranteed-enum
 * shape), "WITHIN", "INSTANCEOF", and "IMPLEMENTS" -- into sequences of
 * ordinary comparison/logic primitives this bytecode already needs
 * regardless (GE/LT/EQ-shaped comparisons, AND/OR/NOT, and fixed-offset
 * dotted-name reads), per the design settled on directly: "so the plan
 * is to compile them all out in to more primitive instructions" --
 * confirmed yes. Runs once, as a final, one-shot stage
 * (BytecodeOptimizer.optimize), the same "not part of either fixed-point
 * loop" placement DropGlueGenerationPass already established for a
 * lowering step that only ever needs to see the program once its shape
 * has settled.
 *
 * "WITHIN" was originally left deliberately unhandled here (see the old
 * "Known gaps" entry this replaces), on the theory that its own strict-
 * subset semantics were "genuinely more complex than a single bounds
 * check." Once "in"'s own range-vs-range subset test (tryRewriteIn's
 * Shape 4, and the range-vs-dynarray shape right after it) was built,
 * that turned out to be wrong: "within" needs exactly the same
 * materialize/dereference machinery plus one small variation on the
 * same comparison (see tryRewriteWithin, buildRangeStrictSubsetCheck) --
 * not a genuinely different mechanism, just a different final formula.
 * Scrapped and rebuilt on top of "in"'s own machinery accordingly,
 * confirmed directly: "within is very similar to in... i think instead
 * scrap what we currently do with within and build it off of what we do
 * with in." This also retired the compiler-side "PTR_WITHIN" mnemonic
 * (see caspien-compiler's own CLAUDE.md) -- it existed only because,
 * before this pass had its own pointer-dereference mechanism, the
 * compiler itself had to pick a mnemonic based on operand pointerness at
 * emission time; "in" never needed a "PTR_IN" for the identical reason
 * this pass's own dereference step already handles it downstream, and
 * "within" doesn't need "PTR_WITHIN" for the same reason once it reuses
 * that same step.
 *
 * Most rewrites below repeat an *already emitted* "PUSH name type" line
 * (or read a dotted field off that same name) -- that covers whichever
 * operand needs to be read more than once (the left of "in EnumName"/
 * "in range", or the struct-typed left of "instanceof"/"implements")
 * whenever it was itself emitted as a single, bare "PUSH name type"
 * line immediately before the operator -- i.e. a plain variable
 * reference, a dotted field access on one, or a bare literal, the
 * shapes this bytecode compresses down to one PUSH line (see
 * BytecodeEmitter.emitDot's qualified-name fast path, and emitExpr's
 * ordinary VARREF/literal handling).
 *
 * A genuinely complex left operand of "in" (the result of a CALL, an
 * arithmetic expression, a LOOKUP into something not itself a bare
 * name, ...) never ends its own emission with a solitary "PUSH" line --
 * it ends with whatever instruction actually produced it (ADD, CALL's
 * own PUSH_RET, DOT, ...). Confirmed directly -- "the instruction
 * should have expectation based on what it presumes is on the stack,
 * not what from a most recent instruction type thing... its ok for
 * this to mean making a temporary variable or position on the stack" --
 * this is since handled too, for "in"'s left operand specifically (see
 * tryRewriteIn's own "materialize the left operand" branch): a fresh,
 * uniquely-named temp local ("$in_tmpN", declareTemp) is declared, and
 * the already-computed value already guaranteed to be sitting on the
 * stack at that point (by the compiler's own left-then-right emission
 * order -- no backward tracing of the left operand's own arbitrary
 * bytecode needed at all, its own emission is left completely
 * untouched) is popped straight into it via a widened "POP name type"
 * (AddressLoweringPass's own POP rewrite, previously hardcoded to only
 * ever resolve call-argument "ARGn" slots, now also resolves a real,
 * ALLOC-declared local through the identical `resolveAddress` lookup
 * PUSH/ADDR already use). `ASSIGN` couldn't do this instead -- it needs
 * its destination address pushed *before* the value being stored, which
 * would mean inserting a line ahead of an already-emitted expression
 * whose start was never (and still isn't) located, exactly the
 * backward-tracing this rewrite avoids by construction; `POP name type`
 * needs nothing pushed beforehand, so it drops in cleanly right after
 * the value it's popping, wherever that value already ends. The temp
 * needs no separate cleanup -- it's an ordinary local from that point
 * on, gone the same way any other local already is once the function
 * returns.
 *
 * The right operand gets the identical treatment when it isn't a simple
 * push either (e.g. "x in someFunc()"), popped *before* left since it's
 * the one sitting on top of the stack. This surfaced a real, subtler
 * point worth flagging: outSoFar's second-to-last entry only reliably
 * *is* left's own final line when right's own span is known to be
 * exactly one line (a simple push) -- once right can be an arbitrary,
 * multi-line expression too (a CALL sequence is four lines), that
 * assumption breaks, and inspecting that position as "left's push"
 * blindly read the wrong line entirely the first time this was tried.
 * The fix: whenever right isn't simple, left is unconditionally
 * materialized too, regardless of what that position actually holds --
 * never inspected, never removed -- relying only on the same
 * stack-order guarantee everything else here leans on already.
 *
 * Runtime semantics assumed for the range case (confirmed by inference
 * from TypeChecker.checkForLoop's own desugaring -- a for-loop's own
 * per-iteration "x in $range" condition is the loop's "keep going" test,
 * and the loop variable starts at $range.start and is compared against
 * $range.end as its stopping point): a range's bounds are half-open,
 * start inclusive, end exclusive -- "x in range" lowers to
 * "x >= range.start && x < range.end".
 *
 * Known gaps, deliberately left as original, unmodified IN/WITHIN/
 * INSTANCEOF/IMPLEMENTS bytecode (per the reassessment settled on
 * directly before this pass was written):
 *   - A *pointer*-typed right operand of "u64 in range"/"dynarray"
 *     ("raw range(...)", "raw dynarray(...)", legally constructible in
 *     unsafe code and legally accepted by checkIn, which only checks
 *     baseType text, never storage) is since handled by dereferencing
 *     first: "PUSH name type" / "DEREF derefType" / "POP derefTemp
 *     derefType", reusing the identical declareTemp/widened-POP machinery
 *     this pass already has, landing the pointee's own value in a fresh,
 *     storage-free temp before handing it to buildRangeCheck/
 *     buildDynArrayCheck -- confirmed the correct minimal fix since
 *     `deref()` already exists end-to-end (TypeChecker.checkDerefBuiltin,
 *     BytecodeEmitter's "deref" case: "PUSH pointer" / "DEREF
 *     pointeeType") and the dereferenced temp's own type has storage
 *     stripped (mutability_baseType, matching checkDerefBuiltin's own
 *     TypeInfo), so its ".start"/".end" resolves in AddressLoweringPass
 *     exactly like an ordinary, non-pointer range already does. (`DEREF`
 *     itself used to carry no operand at all -- a real, separate,
 *     later-found gap, fixed alongside a real dereference genuinely being
 *     a copy that needs its own byte count; see `derefInto`'s own doc
 *     comment and this project's own CLAUDE.md for the full story.)
 *
 *     **A real, separate correctness bug, found and fixed directly:**
 *     "does this operand need dereferencing" is NOT the same question as
 *     "is `canonical.storage` non-null" -- an ordinary dynarray value
 *     *always* carries "owns" storage itself (the same "buffer pointer
 *     plus bookkeeping" handle LEN/LOOKUP_DYN etc. already read
 *     directly, no dereference needed -- CanonicalType.isOwnsStorage),
 *     so the plain `storage != null` version of this check wrongly
 *     dereferenced *every* ordinary, non-pointer "x in dynarray" too,
 *     not just the genuinely pointer-wrapped ("raw dynarray(...)") ones
 *     it was meant for -- confirmed directly against a real compiled
 *     fixture using a plain local dynarray (no pointer, no materialized
 *     call result) as the right operand, the first fixture in this
 *     pass's own history to actually exercise that exact shape. Fixed
 *     by `isGenuinePointer` (`storage != null && !isOwnsStorage()`),
 *     used everywhere this pass decides whether an operand needs
 *     dereferencing, in place of the bare storage-null check.
 *   - A range-typed left operand of "in" against a range-shaped *or*
 *     dynarray-shaped right operand is since handled too:
 *       - "r1 in r2" -- the ordinary *non-strict* subset test,
 *         "r1.start >= r2.start && r1.end <= r2.end" (allows r1 == r2;
 *         WITHIN is the separate *strict* variant, built the same way,
 *         that additionally excludes the equal-bounds case -- see
 *         tryRewriteWithin/buildRangeStrictSubsetCheck above).
 *       - "r in dynarray" -- "is the whole range a valid index span into
 *         the dynarray," i.e. `r.end <= LEN(dynarray)` (the lower bound
 *         `r.start >= 0` is trivially true for a u64 range, the same
 *         reasoning already used to drop it for a scalar `u64` left).
 *         Deliberately `<=`, not `<`: a dynarray of length N has valid
 *         indices `0..N-1`, and the range `0..N` (`end == N`) is
 *         *exactly* that full valid span, so it must test as fully "in"
 *         the dynarray -- confirmed directly, "N < N" would wrongly
 *         reject it, "N <= N" is correct.
 *     Both sides get the identical pointer-dereference treatment
 *     described just above, independently -- a range-shaped left can
 *     genuinely be pointer-wrapped here (unlike Shape 2/3's scalar
 *     "u64" left, which checkIn itself never lets be a pointer), so
 *     "r1 in r2"/"r in dynarray" and every combination of either side
 *     being a raw pointer to its own operand all lower correctly, each
 *     independently materialized into a temp first when needed, same as
 *     Shape 2/3. Originally, *any* left operand shape reaching this
 *     rewrite at all was firing the plain-`u64` logic unconditionally --
 *     a real, separate bug found and fixed directly: no left-operand-
 *     shape guard existed at first, so a range-typed left (16 bytes) got
 *     compared via a same-sized GT_EQ_INT/LT_INT against a scalar bound,
 *     producing genuinely wrong bytecode (confirmed against real
 *     compiled fixtures, "in_operator_range_range_test") before the
 *     guard -- and later the two dedicated subset/dynarray-check
 *     builders -- were added.
 *   - "u64 in string" -- already compiled to its own dedicated
 *     "IN_SCAN" mnemonic by the compiler itself (BytecodeEmitter), never
 *     plain "IN" -- this pass never sees it.
 *   - Interface-typed "instanceof"/"implements" targets, or any target
 *     name this pass can't resolve in the program's own "Class"/
 *     "$enum_for_" enum tables (EnumTable.classRangeOf/
 *     implementerClassIdsOf both return an empty/null result for these,
 *     so the match simply fails and the original bytecode is left
 *     alone). An interface-typed *left* operand is likewise never
 *     lowered -- confirmed directly against a real compiled fixture
 *     ("interface_typed_instanceof_test," which legally uses an
 *     interface-typed left operand as of a later round) -- an interface
 *     is never registered in StructTable (it has no STRUCT_START/
 *     STRUCT_MEMBER block of its own, only concrete structs do), so it
 *     never carries the hidden "___type" field this pass's rewrite
 *     needs to read, and hasTypeField's own check simply fails, leaving
 *     the original INSTANCEOF/IMPLEMENTS bytecode untouched -- exactly
 *     the same "let the pattern match fail rather than guess" fallback
 *     every other gap here already relies on.
 *   - A non-simple-push left *or* right operand of a "u64 in range"/
 *     "dynarray" is since handled (see the design note above) --
 *     each is independently materialized into its own temp when it
 *     isn't a simple named push, so e.g. "x in someFunc()" and
 *     "(3+4) in someFunc()" both lower correctly now. The guaranteed-
 *     enum shape ("u64 in EnumName") is untouched by this -- its right
 *     side is a bare type-name label, not a runtime value, so
 *     materializing it wouldn't mean anything. "instanceof"/
 *     "implements"'s own struct-typed left operand also still must be a
 *     simple push -- its own materialization wasn't asked for or
 *     attempted here, though the identical mechanism would extend to it
 *     the same way if that's ever wanted.
 */
public class MembershipLoweringPass implements OptimizationPass {

    private static final BytecodeParser PARSER = new BytecodeParser();

    @Override
    public String name() {
        return "membership-lowering";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        return new Worker(StructTable.read(lines), EnumTable.read(lines)).process(lines);
    }

    private static final class Worker {

        private final StructTable structTable;
        private final EnumTable enumTable;

        // Both reset once per function by rewriteFunctionBody -- see
        // declareTemp and tryRewriteIn's own "materialize the left
        // operand" branch.
        private int allocInsertIndex;
        private int tempCounter;

        // Never reset per function -- unlike tempCounter/allocInsertIndex,
        // labels have to stay unique across the *whole* program, the
        // identical "one Worker-lifetime counter, never zeroed" precedent
        // BytecodeEmitter.labelCounter and CloneGenerationPass.Worker's own
        // labelCounter already establish. Only tryRewriteInScan (below)
        // needs this at all -- every other rewrite in this pass produces
        // straight-line replacement text, never a real loop.
        private int labelCounter;

        Worker(StructTable structTable, EnumTable enumTable) {
            this.structTable = structTable;
            this.enumTable = enumTable;
        }

        PassResult process(List<List<BytecodeToken>> lines) {
            List<List<BytecodeToken>> rewritten = new ArrayList<>();
            boolean changed = false;

            int i = 0;
            int n = lines.size();
            while (i < n) {
                List<BytecodeToken> line = lines.get(i);
                if (line.isEmpty() || !line.get(0).text.equals("FUNC_START")) {
                    rewritten.add(line);
                    i++;
                    continue;
                }

                int start = i;
                int end = start;
                while (end < n && !(lines.get(end).size() > 0 && lines.get(end).get(0).text.equals("FUNC_END"))) {
                    end++;
                }
                end = Math.min(end, n - 1);

                List<List<BytecodeToken>> body = new ArrayList<>();
                for (int k = start; k <= end; k++) {
                    body.add(lines.get(k));
                }
                RewriteOutcome outcome = rewriteFunctionBody(body);
                rewritten.addAll(outcome.lines);
                changed |= outcome.changed;

                i = end + 1;
            }

            return new PassResult(rewritten, changed);
        }

        private static final class RewriteOutcome {
            final List<List<BytecodeToken>> lines;
            final boolean changed;
            RewriteOutcome(List<List<BytecodeToken>> lines, boolean changed) {
                this.lines = lines;
                this.changed = changed;
            }
        }

        /** Walks one function's own line range looking for a rewritable IN/INSTANCEOF/IMPLEMENTS line, three lines at a time (the operator plus its two preceding operand pushes). */
        private RewriteOutcome rewriteFunctionBody(List<List<BytecodeToken>> body) {
            List<List<BytecodeToken>> out = new ArrayList<>();
            boolean changed = false;
            int k = 0;
            int n = body.size();

            // Where a freshly-needed temp local's own "ALLOC name type"
            // line belongs -- right after this function's existing ALLOC
            // prologue block (see declareTemp, and tryRewriteIn's own
            // "materialize the left operand" branch), the same "ALLOC/
            // RETURNS prologue, then real instructions" layout every real
            // function already follows. Computed once, up front, against
            // `body` rather than `out` -- nothing before this point is
            // ever itself rewritten by this pass, so the two stay
            // index-aligned for as long as this scan cares about.
            //
            // `ARG`/`EXPORT` are real, ordinary header lines at THIS
            // pass's own stage -- `ArgToAllocLoweringPass` (which turns
            // `ARG` into a real `ALLOC` plus a load sequence) doesn't run
            // until later, so a real function's header here is always
            // "FUNC_START, [EXPORT], FUNC_DECORATE*, RETURNS, ARG*,
            // ALLOC*" -- both were missing from this skip-set, a real,
            // found-and-fixed bug: whenever a temp actually needed
            // inserting in a function that also declared real parameters,
            // this loop stopped at the *first* `ARG` line instead of
            // skipping past all of them, landing `allocInsertIndex` mid-
            // way through the parameter list -- splitting it in two
            // (some `ARG` lines before the newly-inserted temp `ALLOC`,
            // some after). `ArgToAllocLoweringPass`'s own header scan
            // (`lowerFunction`) stops collecting `ARG` lines the moment it
            // hits anything else, so it silently gave up after collecting
            // only the first few, treating the split-off remainder as
            // ordinary body text and leaving it completely unlowered --
            // confirmed directly: this is exactly the failure mode that
            // surfaced once `isRealNameToken` (below) started correctly
            // recognizing a `for` loop's own compiler-synthesized
            // "$for_range_N" range holder as a real name (fixing one bug)
            // and, for the first time, stopped needlessly materializing
            // it -- which had been the ONLY reason a temp was ever
            // getting inserted into these particular ARG-bearing stdlib
            // functions at all; a *different* future change that made a
            // temp genuinely necessary in an ARG-bearing function would
            // have hit this identical, independent gap regardless. Both
            // are fixed here together so the second one can't resurface
            // silently the next time something else needs to materialize
            // a value in a function that also takes real parameters.
            allocInsertIndex = 0;
            while (allocInsertIndex < n) {
                List<BytecodeToken> pl = body.get(allocInsertIndex);
                String ph = pl.isEmpty() ? "" : pl.get(0).text;
                if (ph.equals("FUNC_START") || ph.equals("EXPORT") || ph.equals("FUNC_DECORATE")
                        || ph.equals("RETURNS") || ph.equals("ARG") || ph.equals("ALLOC")) {
                    allocInsertIndex++;
                } else {
                    break;
                }
            }
            tempCounter = 0;

            while (k < n) {
                List<BytecodeToken> line = body.get(k);
                String head = line.isEmpty() ? "" : line.get(0).text;

                if (head.equals("IN") && line.size() == 4 && out.size() >= 2) {
                    InMatch match = tryRewriteIn(line, out);
                    if (match != null) {
                        // Each operand push is dropped only if this match
                        // says it's safe to (a *simple* push -- the
                        // replacement text below just re-derives it fresh
                        // by name, so the original line is now redundant);
                        // a push this match had to *materialize* instead
                        // (see tryRewriteIn's own "materialize the left
                        // operand" branch) is essential, real bytecode and
                        // is left standing exactly where it already was.
                        // Removed by explicit index, right push first --
                        // it's the tail, so removing it first never
                        // disturbs the left push's own index, whichever of
                        // the two (or neither, or both) actually gets
                        // removed.
                        int rightIndex = out.size() - 1;
                        int leftIndex = out.size() - 2;
                        if (!match.keepRightPush) {
                            out.remove(rightIndex);
                        }
                        if (!match.keepLeftPush) {
                            out.remove(leftIndex);
                        }
                        out.addAll(PARSER.parse(match.lines, "<generated-membership-lowering>"));
                        changed = true;
                        k++; // only the IN line itself was consumed from `body` beyond the two already-appended pushes
                        continue;
                    }
                } else if (head.equals("WITHIN") && line.size() == 4 && out.size() >= 2) {
                    // Identical operand-push bookkeeping to "IN" above --
                    // see tryRewriteWithin's own header for why this is
                    // always the range-vs-range shape now.
                    InMatch match = tryRewriteWithin(line, out);
                    if (match != null) {
                        int rightIndex = out.size() - 1;
                        int leftIndex = out.size() - 2;
                        if (!match.keepRightPush) {
                            out.remove(rightIndex);
                        }
                        if (!match.keepLeftPush) {
                            out.remove(leftIndex);
                        }
                        out.addAll(PARSER.parse(match.lines, "<generated-membership-lowering>"));
                        changed = true;
                        k++; // only the WITHIN line itself was consumed beyond the two already-appended pushes
                        continue;
                    }
                } else if ((head.equals("INSTANCEOF") || head.equals("IMPLEMENTS")) && line.size() == 2
                        && k >= 1 && out.size() >= 2) {
                    // line.get(1) is the left operand's own real type,
                    // written directly onto this line by BytecodeEmitter
                    // (see its own "instanceof"/"implements" case) -- not
                    // inferred from whatever bytecode line happens to sit
                    // just before this one, which is unreliable the
                    // moment the left operand is a multi-push composite
                    // with no consolidating instruction (a bare, non-
                    // `new` struct literal -- see tryRewriteInstanceofOrImplements's
                    // own header for the real, confirmed bug this closes).
                    String leftType = line.get(1).text;
                    List<BytecodeToken> targetPushLine = body.get(k - 1);
                    InstanceofMatch match = tryRewriteInstanceofOrImplements(head, targetPushLine, leftType, out);
                    if (match != null) {
                        // The target/interface-name push (outSoFar's very
                        // last entry) is always exactly one line, always a
                        // bare compile-time name -- checkInstanceof/
                        // checkImplementsOperator both require a bare
                        // VARREF on the right, so BytecodeEmitter only
                        // ever emits it as a single, invariant "PUSH name"
                        // line -- never a computed value, so it's always
                        // safe to drop unconditionally, whether or not the
                        // left operand below needed materializing.
                        //
                        // The left operand (outSoFar's second-to-last
                        // entry, i.e. its own final line however many
                        // lines produced it) is dropped only when it was a
                        // simple push read fresh by name in the
                        // replacement text -- a materialized (kept) left
                        // operand's own original bytecode is essential
                        // (real computation, possibly with side effects)
                        // and is left standing exactly where it already
                        // was, with a "POP" already appended right after
                        // it to consume its value into the fresh temp the
                        // replacement text reads instead. Unlike "IN", the
                        // right/target operand here can never be multi-
                        // line, so -- unlike the miswiring "IN" had to
                        // guard against -- outSoFar.size() - 2 always
                        // reliably names the left operand's own last line,
                        // regardless of whether it turns out to be simple.
                        out.remove(out.size() - 1);
                        if (!match.keepLeftPush) {
                            out.remove(out.size() - 1);
                        }
                        out.addAll(PARSER.parse(match.lines, "<generated-membership-lowering>"));
                        changed = true;
                        k++; // the bare INSTANCEOF/IMPLEMENTS line
                        continue;
                    }
                } else if (head.equals("IS_BASE") && line.size() == 2 && out.size() >= 1) {
                    // "r is base" -- BytecodeEmitter.emitMatchCondition's
                    // own "PUSH x / IS_BASE -- instead of push base and
                    // then IS" shape: unlike INSTANCEOF/IMPLEMENTS (always
                    // two preceding pushes, left operand plus a bare
                    // target name) or IN/WITHIN (always two preceding
                    // pushes, left and right operands), IS_BASE has
                    // exactly one preceding operand push -- there's no
                    // "right side" at all, "base" isn't a real value
                    // anything gets pushed for.
                    // line.get(1) is the range operand's own real type,
                    // written directly onto this line by BytecodeEmitter
                    // (see its own "is" case) -- not inferred from
                    // whatever line precedes IS_BASE, which is unreliable
                    // the moment that operand is an inline range literal
                    // (two raw scalar pushes, no consolidating
                    // instruction -- see tryRewriteIsBase's own header
                    // for the real, confirmed bug this closes).
                    String rangeOperandType = line.get(1).text;
                    InstanceofMatch match = tryRewriteIsBase(rangeOperandType, out);
                    if (match != null) {
                        // outSoFar's very last entry is the range
                        // operand's own final line -- the only operand
                        // IS_BASE has -- dropped only when it was a
                        // simple push read fresh by name in the
                        // replacement text, exactly the same "materialize
                        // vs. simple" bookkeeping every other shape in
                        // this pass already follows.
                        if (!match.keepLeftPush) {
                            out.remove(out.size() - 1);
                        }
                        out.addAll(PARSER.parse(match.lines, "<generated-membership-lowering>"));
                        changed = true;
                        k++; // the bare IS_BASE line
                        continue;
                    }
                } else if (head.equals("IN_SCAN") && line.size() == 4 && out.size() >= 2) {
                    // "u64 in string" -- identical operand-push bookkeeping
                    // to "IN"/"WITHIN" above (always two preceding pushes,
                    // left then right) -- see tryRewriteInScan's own header.
                    InMatch match = tryRewriteInScan(line, out);
                    if (match != null) {
                        int rightIndex = out.size() - 1;
                        int leftIndex = out.size() - 2;
                        if (!match.keepRightPush) {
                            out.remove(rightIndex);
                        }
                        if (!match.keepLeftPush) {
                            out.remove(leftIndex);
                        }
                        out.addAll(PARSER.parse(match.lines, "<generated-membership-lowering>"));
                        changed = true;
                        k++; // only the IN_SCAN line itself was consumed beyond the two already-appended pushes
                        continue;
                    }
                } else if (head.equals("LEN_SCAN") && line.size() == 3 && out.size() >= 2) {
                    // "len(unsafeDynArr, terminator)" -- unlike every
                    // other shape here (including IN_SCAN), LEN_SCAN's
                    // own raw line is only 3 tokens (mnemonic + two
                    // operand types, no trailing resultType -- see
                    // BytecodeEmitter's own "line(\"LEN_SCAN \" +
                    // targetArg.resolvedType + \" \" +
                    // termArg.resolvedType)"); the result type is always,
                    // unconditionally, exactly "indeterminate_u64"
                    // (checkLenBuiltin's own fixed `TypeInfo(null,
                    // "indeterminate", "u64")` for this shape), so
                    // tryRewriteLenScan hardcodes it rather than reading a
                    // fourth token that doesn't exist -- see
                    // tryRewriteInScan's own header for the identical
                    // "operand-push bookkeeping" shape otherwise (two
                    // preceding pushes, target then terminator).
                    InMatch match = tryRewriteLenScan(line, out);
                    if (match != null) {
                        int rightIndex = out.size() - 1;
                        int leftIndex = out.size() - 2;
                        if (!match.keepRightPush) {
                            out.remove(rightIndex);
                        }
                        if (!match.keepLeftPush) {
                            out.remove(leftIndex);
                        }
                        out.addAll(PARSER.parse(match.lines, "<generated-membership-lowering>"));
                        changed = true;
                        k++; // only the LEN_SCAN line itself was consumed beyond the two already-appended pushes
                        continue;
                    }
                }

                out.add(line);
                k++;
            }
            return new RewriteOutcome(out, changed);
        }

        /** A rewrite's replacement text for "IN", plus whether each of `outSoFar`'s trailing operand-push lines (left, then right) must be left standing rather than dropped -- true when that operand had to be materialized into a temp instead of being a simple named push (see tryRewriteIn's own "materialize the left/right operand" handling): its own original bytecode is essential and is never dropped, unlike a simple push's now-redundant original line. */
        private static final class InMatch {
            final List<String> lines;
            final boolean keepLeftPush;
            final boolean keepRightPush;
            InMatch(List<String> lines, boolean keepLeftPush, boolean keepRightPush) {
                this.lines = lines;
                this.keepLeftPush = keepLeftPush;
                this.keepRightPush = keepRightPush;
            }
        }

        /**
         * `inLine` is "IN t1 t2 t3". `outSoFar` is every already-rewritten
         * line of this function emitted before it -- its last two entries
         * are the original left/right operand pushes (unless this "IN"
         * doesn't qualify for lowering at all, checked as each shape is
         * tried). Returns the raw replacement text, or null if this
         * particular "IN" doesn't match any of the shapes this pass
         * knows how to lower.
         */
        private InMatch tryRewriteIn(List<BytecodeToken> inLine, List<List<BytecodeToken>> outSoFar) {
            String t1 = inLine.get(1).text;
            String t2 = inLine.get(2).text;
            String t3 = inLine.get(3).text;

            List<BytecodeToken> rightPush = outSoFar.get(outSoFar.size() - 1);
            List<BytecodeToken> leftPush = outSoFar.get(outSoFar.size() - 2);

            // Shape 1: "u64 in GuaranteedEnumName" -- the right side is a
            // bare enum name, pushed as "PUSH EnumName" (2 tokens, no
            // type -- see BytecodeEmitter's own guaranteed-enum "in"
            // case), never a real, type-carrying operand push.
            List<Map.Entry<String, Long>> enumVariants = enumTable.valueVariantsOf(t2);
            if (!enumVariants.isEmpty() && isBarePush(rightPush) && rightPush.get(1).text.equals(t2)
                    && isSimplePush(leftPush)) {
                String leftName = leftPush.get(1).text;
                return new InMatch(buildOrChain(leftName, t1, enumVariants, t3), false, false);
            }

            // Shape 2/3: the ordinary bounds/length comparison -- right
            // side is a real range or dynarray value.
            // buildRangeCheck/buildDynArrayCheck both assume a scalar u64 left operand
            // (comparing it directly against range bounds / treating it
            // as a plain value against LEN), so this branch only fires
            // when the left operand is confirmed to be a plain "u64" --
            // no storage, exact base-type match, the same isPlainU64
            // gate the compiler's own TypeChecker already uses for this
            // same distinction. checkIn legally also permits a "range"
            // left operand -- firing this rewrite for that blindly
            // produced genuinely wrong bytecode (a 16-byte range
            // compared via a same-sized GT_EQ_INT/LT_INT against a
            // scalar bound), confirmed directly against real compiled
            // fixtures. Per the call made on this directly -- "if its
            // not a u64 on the left it should remain unhandled" -- bail
            // out and leave the original IN bytecode untouched, joining
            // the same "left as original, unmodified" category a
            // range-left operand still occupies, rather than attempting
            // real range-left subset-check semantics here.
            CanonicalType leftCanonical = CanonicalType.parse(t1);
            boolean leftIsPlainU64 = leftCanonical.storage == null && leftCanonical.baseType.equals("u64");
            if (leftIsPlainU64) {
                CanonicalType rightCanonical = CanonicalType.parse(t2);
                String rightBaseType = rightCanonical.baseType;
                // Bare "range" (bounds unknown to the compiler -- a
                // parameter, or a value like `range(dynarray)` whose
                // length is a genuine runtime read) is exactly as real a
                // range as a literal-bounds "range(0,5)" -- buildRangeCheck
                // just below reads ".start"/".end" as ordinary runtime
                // dotted-field accesses either way, never the type text's
                // own bounds, so there was never a reason to exclude it
                // here. This exact narrow-check bug was already found and
                // fixed once, at this file's own "r is base" rewrite (see
                // its own doc comment) -- missed here and at the two
                // "within" gates just below when that fix landed, until
                // caught again, directly, against real "for i in
                // range(dynarray)" output.
                boolean rightIsRange = rightBaseType.equals("range") || rightBaseType.startsWith("range(");
                boolean rightIsDynArray = rightBaseType.startsWith("dynarray(");
                if (rightIsRange || rightIsDynArray) {
                    // Either operand's own name (an ordinary, already-
                    // addressable local -- "PUSH name type" -- so
                    // buildRangeCheck/buildDynArrayCheck can just re-push
                    // it fresh, as many times as they need to read it), or,
                    // when it wasn't a simple push at all (e.g. "(3+4) in
                    // r", or "u64 in someFunc()") -- confirmed directly,
                    // "the instruction should have expectation based on
                    // what it presumes is on the stack, not what from a
                    // most recent instruction type thing" -- a freshly
                    // declared temp local that a new "POP name type" pops
                    // the already-computed value into right where it
                    // already sits, so buildRangeCheck/buildDynArrayCheck
                    // can re-read *that* by name instead. Left completely
                    // alone otherwise -- this rewrite never touches,
                    // relocates, or duplicates either operand's own
                    // original bytecode (however long it is), it only ever
                    // consumes the single value each is already guaranteed
                    // (by the compiler's own left-then-right emission
                    // order) to have left on the stack.
                    //
                    // Right is materialized (popped) *before* left, since
                    // it's the one sitting on top of the stack (pushed
                    // last) -- popping it first is what makes left's own
                    // value reachable at all when left also needs popping.
                    //
                    // `leftPush` (outSoFar's second-to-last entry) is only
                    // trustworthy as "left's own last line" when right's
                    // own span is known to be exactly the single line at
                    // outSoFar's very last entry -- i.e. when right is a
                    // simple push. The moment right *isn't* (a CALL's own
                    // "CC_START .../CALL .../CC_END .../PUSH_RET ..." block
                    // is several lines, not one), "outSoFar.size() - 2"
                    // no longer reliably points at left's own last line at
                    // all -- it could be mid-way through right's own
                    // multi-line span instead (confirmed directly: this
                    // produced a real, wrong double-POP the first time
                    // this was tried, materializing garbage instead of
                    // `x` for "x in makeArr()"). So whenever right isn't
                    // simple, left is *always* materialized too,
                    // regardless of what `leftPush` actually looks like --
                    // never inspected, never removed -- relying only on
                    // the same stack-order guarantee: once right's own
                    // value has just been popped off, whatever's left
                    // underneath is unconditionally left's own value,
                    // however many lines produced it.
                    List<String> lines = new ArrayList<>();
                    boolean rightSimple = isSimplePush(rightPush);
                    String rightName;
                    if (rightSimple) {
                        rightName = rightPush.get(1).text;
                    } else {
                        rightName = declareTemp(outSoFar, t2);
                        lines.add("POP " + rightName + " " + t2);
                    }
                    boolean leftSimple = rightSimple && isSimplePush(leftPush);
                    String leftName;
                    if (leftSimple) {
                        leftName = leftPush.get(1).text;
                    } else {
                        leftName = declareTemp(outSoFar, t1);
                        lines.add("POP " + leftName + " " + t1);
                    }

                    // A pointer-typed right operand ("raw range(...)",
                    // "raw dynarray(...)", ...) -- legally accepted by
                    // checkIn (isRangeType/isDynArrayType only look at
                    // baseType text, never storage), but wrong to hand
                    // straight to buildRangeCheck/buildDynArrayCheck as-is:
                    // those functions read the name directly ("PUSH name
                    // type", "PUSH name.start ...") assuming it's already
                    // the range/dynarray value itself, not a pointer *to*
                    // one -- confirmed directly against real compiled
                    // fixtures ("PUSH p.start"/"LEN" on a raw pointer's own
                    // address, never the pointee). Fixed here by
                    // dereferencing first, using the identical `deref()`
                    // builtin the compiler itself already lowers to
                    // "PUSH pointer / DEREF pointeeType" (BytecodeEmitter's
                    // own "deref" case) -- reusing this pass's existing
                    // declareTemp/widened-POP machinery to land the
                    // dereferenced value in a fresh, storage-free temp:
                    // "PUSH rightName t2" / "DEREF derefType" / "POP
                    // derefName derefType" (see derefInto's own doc
                    // comment for why DEREF carries that operand at all) --
                    // and the resulting temp's type has no storage of its
                    // own (mutability_
                    // baseType, storage stripped, exactly like TypeChecker.
                    // checkDerefBuiltin's own TypeInfo), so its ".start"/
                    // ".end" access resolves in AddressLoweringPass exactly
                    // like an ordinary, non-pointer range already does.
                    //
                    // isGenuinePointer -- NOT plain `storage != null` --
                    // is the right gate here: a real, correctness bug
                    // found and fixed directly. An ordinary dynarray value
                    // *always* carries "owns" storage itself (that's just
                    // how a dynarray's own runtime handle is represented,
                    // the identical "buffer pointer plus bookkeeping"
                    // shape LEN/LOOKUP_DYN etc. already read directly, no
                    // prior DEREF needed), so "owns" storage on a dynarray
                    // means "this is a plain dynarray value," never "this
                    // is a pointer wrapping one" -- unlike "raw"/"ref",
                    // which genuinely do mean the latter. The plain
                    // `storage != null` version of this check wrongly
                    // dereferenced *every* ordinary "x in dynarray" (any
                    // bare, non-pointer dynarray local), not just the
                    // genuinely pointer-wrapped ones -- confirmed directly
                    // against a real compiled fixture using a plain local
                    // dynarray (not a pointer, not a materialized call
                    // result) as the right operand, the first fixture in
                    // this pass's own history to actually exercise that
                    // exact shape.
                    if (isGenuinePointer(rightCanonical)) {
                        String[] deref = derefInto(rightName, t2, rightCanonical, outSoFar, lines);
                        rightName = deref[0];
                        t2 = deref[1];
                    }

                    if (rightIsRange) {
                        lines.addAll(buildRangeCheck(leftName, t1, rightName, t3));
                    } else {
                        lines.addAll(buildDynArrayCheck(leftName, t1, rightName, t2, t3));
                    }
                    return new InMatch(lines, !leftSimple, !rightSimple);
                }
            }

            // Shape 4: a *range*-shaped left operand (bare "range(...)"
            // or a pointer to one -- checkIn's own isRangeType only looks
            // at baseType text, never storage, so both are legally
            // accepted, same as the right side already was) against
            // either a range-shaped or a dynarray-shaped right operand:
            //   - "r1 in r2" -- the ordinary non-strict subset test,
            //     "r1.start >= r2.start && r1.end <= r2.end" -- confirmed
            //     directly as the right pairing with "in" (as opposed to
            //     "within", which is the separate, still-unhandled
            //     *strict* subset variant that additionally excludes the
            //     case where both sides carry identical bounds, "1..2 is
            //     within 0..3 but 0..3 is not within 0..3" -- see this
            //     pass's own header).
            //   - "r in dynarray" -- "is the whole range a valid index
            //     span into the dynarray," i.e. "r.end <= LEN(dynarray)"
            //     (the lower bound "r.start >= 0" is trivially true for a
            //     u64 range, the same reasoning buildDynArrayCheck already
            //     uses to drop it for a scalar u64 left). Confirmed
            //     directly this must be "<=", not "<": a dynarray of
            //     length 5 has valid indices 0..4, and the range "0..5"
            //     (start=0, end=5) is *exactly* that full valid span, so
            //     it must test as fully "in" the dynarray -- "5 < 5" is
            //     false (would wrongly reject the range that exactly
            //     covers the whole array), "5 <= 5" is true (correct).
            //     Consistent with the range-subset rule just above,
            //     treating the dynarray as an implicit "0..LEN" range and
            //     dropping the always-true lower bound the identical way
            //     the scalar case already does.
            // See the identical widening (and its own doc comment) just
            // above, in Shape 2/3's own "rightIsRange" -- bare "range" is
            // exactly as real a range as a literal-bounds one here too.
            boolean leftIsRangeShaped = leftCanonical.baseType.equals("range") || leftCanonical.baseType.startsWith("range(");
            if (leftIsRangeShaped) {
                CanonicalType rightCanonical = CanonicalType.parse(t2);
                boolean rightIsRangeShaped = rightCanonical.baseType.equals("range") || rightCanonical.baseType.startsWith("range(");
                boolean rightIsDynArrayShaped = rightCanonical.baseType.startsWith("dynarray(");
                if (rightIsRangeShaped || rightIsDynArrayShaped) {
                    // Identical materialize-into-temp treatment as Shape
                    // 2/3 above -- right popped before left (it's the one
                    // sitting on top of the stack), left unconditionally
                    // materialized too whenever right isn't a simple push
                    // (same "outSoFar's second-to-last entry isn't
                    // trustworthy once right spans more than one line"
                    // reasoning documented above).
                    List<String> lines = new ArrayList<>();
                    boolean rightSimple = isSimplePush(rightPush);
                    String rightName;
                    String rightType = t2;
                    if (rightSimple) {
                        rightName = rightPush.get(1).text;
                    } else {
                        rightName = declareTemp(outSoFar, t2);
                        lines.add("POP " + rightName + " " + t2);
                    }
                    boolean leftSimple = rightSimple && isSimplePush(leftPush);
                    String leftName;
                    String leftType = t1;
                    if (leftSimple) {
                        leftName = leftPush.get(1).text;
                    } else {
                        leftName = declareTemp(outSoFar, t1);
                        lines.add("POP " + leftName + " " + t1);
                    }

                    // Unlike Shape 2/3 (where the left operand is always
                    // a plain scalar "u64", never a pointer -- checkIn
                    // itself rejects a pointer-typed "u64" left outright),
                    // a range-shaped left can genuinely be pointer-wrapped
                    // here too, so both sides get the identical
                    // dereference-into-a-fresh-temp treatment, each
                    // independently, whichever side(s) actually need it.
                    // isGenuinePointer, not plain `storage != null` -- see
                    // the identical fix and its "owns" explanation just
                    // above in Shape 2/3; matters here specifically for a
                    // dynarray-shaped right operand (an ordinary dynarray
                    // value's own "owns" storage must never trigger a
                    // dereference). A range-shaped operand's own storage
                    // is always null by construction (TypeChecker.
                    // checkRange), so this makes no difference for the
                    // range-vs-range pairing -- only for range-vs-dynarray.
                    if (isGenuinePointer(rightCanonical)) {
                        String[] deref = derefInto(rightName, rightType, rightCanonical, outSoFar, lines);
                        rightName = deref[0];
                        rightType = deref[1];
                    }
                    if (isGenuinePointer(leftCanonical)) {
                        String[] deref = derefInto(leftName, leftType, leftCanonical, outSoFar, lines);
                        leftName = deref[0];
                    }

                    if (rightIsRangeShaped) {
                        lines.addAll(buildRangeSubsetCheck(leftName, rightName, t3));
                    } else {
                        lines.addAll(buildRangeInDynArrayCheck(leftName, rightName, rightType, t3));
                    }
                    return new InMatch(lines, !leftSimple, !rightSimple);
                }
            }

            return null; // some other shape this pass doesn't lower
        }

        /**
         * `withinLine` is "WITHIN t1 t2 t3". `checkWithin` (the compiler's
         * own type rule) guarantees both operands are always range-shaped
         * (bare "range(...)" or a pointer to one) -- unlike "in", `within`
         * never legally accepts a dynarray on either side, and a plain
         * "u64" is never legal on the left either (only a range being
         * compared against another range is meaningful for "within" --
         * "a single point has nothing to be within"). So there's only
         * ever this one shape to handle, not a per-shape dispatch the way
         * `tryRewriteIn` needs -- `outSoFar`'s last two entries are the
         * original left/right operand pushes, exactly as for "in".
         *
         * The compiler no longer emits a separate "PTR_WITHIN" mnemonic
         * for a pointer-wrapped operand (see this project's own CLAUDE.md,
         * "Membership-operator lowering" -- that split predates this
         * pass's own pointer-dereference mechanism and is now redundant
         * with it, the identical "IN never needed a PTR_IN either" logic
         * the sibling caspien-compiler project's own CLAUDE.md records):
         * a genuinely pointer-wrapped operand is detected here, by
         * `isGenuinePointer`, exactly the same way "in"'s own range-left
         * shape (`tryRewriteIn`'s Shape 4) already does, and dereferenced
         * into a fresh temp before the comparison, independently per
         * side.
         */
        private InMatch tryRewriteWithin(List<BytecodeToken> withinLine, List<List<BytecodeToken>> outSoFar) {
            String t1 = withinLine.get(1).text;
            String t2 = withinLine.get(2).text;
            String t3 = withinLine.get(3).text;

            List<BytecodeToken> rightPush = outSoFar.get(outSoFar.size() - 1);
            List<BytecodeToken> leftPush = outSoFar.get(outSoFar.size() - 2);

            CanonicalType leftCanonical = CanonicalType.parse(t1);
            CanonicalType rightCanonical = CanonicalType.parse(t2);
            // Bare "range" accepted alongside "range(...)" here too -- see
            // the identical widening (and its own doc comment) in
            // tryRewriteIn's Shape 2/3 "rightIsRange" above. This was the
            // one real, previously-documented gap this narrow check
            // caused ("WITHIN's incomplete-range case," flagged in
            // caspien-codegen's own X86Backend class comment as needing a
            // fix "one layer up in caspien-lowerordergenerator itself") --
            // fixed at the actual root here, not patched around downstream.
            if ((!leftCanonical.baseType.equals("range") && !leftCanonical.baseType.startsWith("range("))
                    || (!rightCanonical.baseType.equals("range") && !rightCanonical.baseType.startsWith("range("))) {
                // Should never actually happen -- checkWithin's own type
                // rule guarantees both sides are range-shaped -- but the
                // same "let the pattern fail rather than guess" fallback
                // every other gap in this pass already relies on, in case
                // that rule is ever loosened later without this pass
                // being revisited.
                return null;
            }

            // Identical materialize-into-temp treatment as "in"'s own
            // range-vs-range shape -- right popped before left (it's the
            // one sitting on top of the stack), left unconditionally
            // materialized too whenever right isn't a simple push (see
            // tryRewriteIn's own header for the full "outSoFar's second-
            // to-last entry isn't trustworthy once right spans more than
            // one line" reasoning).
            List<String> lines = new ArrayList<>();
            boolean rightSimple = isSimplePush(rightPush);
            String rightName;
            if (rightSimple) {
                rightName = rightPush.get(1).text;
            } else {
                rightName = declareTemp(outSoFar, t2);
                lines.add("POP " + rightName + " " + t2);
            }
            boolean leftSimple = rightSimple && isSimplePush(leftPush);
            String leftName;
            if (leftSimple) {
                leftName = leftPush.get(1).text;
            } else {
                leftName = declareTemp(outSoFar, t1);
                lines.add("POP " + leftName + " " + t1);
            }

            // Dereference whichever side is genuinely pointer-wrapped --
            // `isGenuinePointer`, not plain `storage != null` (see that
            // helper's own doc for why -- irrelevant here in practice,
            // since a range's own storage is always null by construction,
            // but kept consistent with every other call site in this
            // pass rather than special-cased).
            if (isGenuinePointer(rightCanonical)) {
                String[] deref = derefInto(rightName, t2, rightCanonical, outSoFar, lines);
                rightName = deref[0];
            }
            if (isGenuinePointer(leftCanonical)) {
                String[] deref = derefInto(leftName, t1, leftCanonical, outSoFar, lines);
                leftName = deref[0];
            }

            lines.addAll(buildRangeStrictSubsetCheck(leftName, rightName, t3));
            return new InMatch(lines, !leftSimple, !rightSimple);
        }

        /**
         * "r is base" -- "match RANGE_PARAM is base{...}," confirmed
         * directly: a reserved, special-purpose match condition (never a
         * general "is" operator -- `TypeChecker`'s own dedicated "is"
         * case, not a `resolveExprType` dispatch entry), true exactly
         * when the range's own bounds have already met ("its start has
         * reached its end," checked purely at runtime since a range's
         * bounds aren't necessarily known at compile time). Structurally
         * closest to `tryRewriteInstanceofOrImplements` -- a single
         * preceding operand push, no second operand at all (unlike
         * IN/WITHIN's own always-two-pushes shape) -- reusing the
         * identical `InstanceofMatch`/`resolveLeftName`/`isGenuinePointer`/
         * `derefInto` machinery rather than inventing parallel plumbing
         * for what's otherwise the same "materialize a non-simple
         * operand, dereference a genuinely pointer-wrapped one" shape
         * every other rewrite in this pass already follows.
         *
         * `checkIs`'s own `isRangeType` check (TypeChecker) only looks at
         * `baseType` text, exactly like `checkIn`'s range-left shape --
         * never at storage -- so a pointer-wrapped range ("raw
         * range(...)") is just as legal here as a bare one, and gets the
         * identical dereference treatment `tryRewriteIn`'s own Shape 4
         * and `tryRewriteWithin` already established for a range-typed
         * operand.
         *
         * "DEBUG can be removed... I mean the full thing, no special
         * debug() keyword or builtin in the language," confirmed
         * directly and done separately from this method (BytecodeEmitter/
         * TypeChecker/Lexer/Parser no longer know the word "debug" at
         * all) -- noted here only because IS_BASE was originally
         * investigated alongside a leftover DEBUG mnemonic search; the
         * two features are otherwise unrelated.
         *
         * `leftType` is read directly off the `IS_BASE leftType` line
         * itself (`BytecodeEmitter`'s own "is" case), not inferred from
         * whatever bytecode line happens to precede it -- a real,
         * confirmed bug, fixed directly: the earlier version of this
         * method read `leftPush`'s own trailing token as "the operand's
         * type," which is correct only when `leftPush` really is the
         * operand's one and only line (a bare, named range reference).
         * The moment the left operand is instead an inline range
         * *literal* ("match (2..5) is base{...}" -- legal; confirmed by
         * actually compiling it -- `checkMatchCondition`'s "is" case only
         * requires `isRangeType(leftType)`, never that `node.left` itself
         * be a bare VARREF), `BytecodeEmitter` pushes its two bounds as
         * two separate, ordinary scalar "PUSH n u64" lines with no
         * consolidating instruction -- so `leftPush` is only ever the
         * upper bound's own bare push, and its own trailing token is
         * "u64", never "range(...)". No amount of "materialize into a
         * temp of the right type" fixes that on its own, because the
         * *type itself* was never recoverable from `leftPush`'s own shape
         * to begin with -- the only fix that actually closes this is
         * reading it from where it still reliably exists, the `IS_BASE`
         * line's own now-real operand.
         */
        private InstanceofMatch tryRewriteIsBase(String leftType, List<List<BytecodeToken>> outSoFar) {
            List<BytecodeToken> leftPush = outSoFar.get(outSoFar.size() - 1);
            boolean leftSimple = isSimplePush(leftPush);
            CanonicalType leftCanonical = CanonicalType.parse(leftType);
            // `TypeChecker.isRangeType`'s own two-part check, not just
            // `startsWith("range(")` -- a "@recursive" function's own
            // final range parameter is legally declared as plain, bare
            // "range" (no known "start"/"end" field names at that scope,
            // "an incomplete range pointer" -- TypeChecker's own
            // isCompleteRange distinction), not necessarily a concrete
            // "range(x,y)". `AddressLoweringPass.pseudoMembersOf` already
            // handles both spellings identically for ".start"/".end"
            // resolution, so there's no reason for this rewrite to
            // recognize only the concrete one -- confirmed directly this
            // was the actual reason the very first "r is base" fixture
            // tried against this rewrite silently failed to match at all
            // (BytecodeEmitter emits the range parameter's own push as
            // bare "PUSH r imut_range", never a concrete "imut_range(...)"
            // -- only a *local* range variable's own declared type gets
            // to know its field names).
            if (!leftCanonical.baseType.equals("range") && !leftCanonical.baseType.startsWith("range(")) {
                return null; // should never actually happen -- checkIs's own isRangeType check guarantees a range-typed left
            }

            List<String> lines = new ArrayList<>();
            String rangeName = resolveLeftName(leftSimple, leftPush, leftType, outSoFar, lines);
            if (isGenuinePointer(leftCanonical)) {
                String[] deref = derefInto(rangeName, leftType, leftCanonical, outSoFar, lines);
                rangeName = deref[0];
            }
            lines.addAll(buildIsBaseCheck(rangeName));
            return new InstanceofMatch(lines, !leftSimple);
        }

        /**
         * "r is base" -- "r.start >= r.end," confirmed directly: the
         * range is exhausted once its own start has caught up to (or
         * somehow passed) its end. Deliberately `GT_EQ`, not `EQ` --
         * matching this pass's own established "don't assume a range's
         * bounds can only ever move one specific way" caution
         * (`buildRangeSubsetCheck`'s own bounds comparisons make no
         * assumption about how a range got to whatever state it's in
         * either), so a range that somehow overshot its own end (rather
         * than landing exactly on it) is still correctly reported as
         * "base" rather than silently falling through as neither
         * "before" nor "at" the end.
         */
        private List<String> buildIsBaseCheck(String rangeName) {
            List<String> lines = new ArrayList<>();
            lines.add("PUSH " + rangeName + ".start indeterminate_u64");
            lines.add("PUSH " + rangeName + ".end indeterminate_u64");
            lines.add("GT_EQ indeterminate_u64 indeterminate_u64 imut_bool");
            return lines;
        }

        /**
         * "u64 in string" -- lowered to a real byte-by-byte runtime scan
         * (see `buildStringScanCheck`), the first rewrite in this whole
         * pass that needs actual control flow (a loop) rather than a
         * single straight-line replacement -- confirmed directly: "so
         * that should be lowered to using strlen()," refined to a plain
         * forward scan with no `strlen()` call and no length known
         * upfront at all, since a raw scan to the null terminator carries
         * exactly the same semantics -- and exactly the same single
         * caveat (both stop at the very first '\0' byte) -- strlen()
         * itself already has; nothing about "weird"/non-ASCII byte values
         * introduces any additional risk, since every comparison here is
         * a plain numeric byte compare, never a text/locale-aware
         * operation.
         *
         * `checkIn`'s own "u64 in string" branch (this pass's own header,
         * "Known gaps" entry now retired) guarantees the left operand is
         * always exactly a plain "u64" (`isPlainU64` -- no storage, so
         * always exactly 8 bytes, never itself a pointer) and the right
         * operand's `baseType` is always exactly "string" -- though, like
         * every other shape in this pass, only `baseType` text is ever
         * checked there, never storage, so a genuinely pointer-wrapped
         * string ("raw string") is legally possible and gets the
         * identical `isGenuinePointer`/`derefInto` treatment every other
         * operand here already gets. Identical materialize-into-temp
         * bookkeeping to `tryRewriteIn`'s own Shape 2/3 otherwise -- right
         * popped before left, left unconditionally materialized too
         * whenever right isn't a simple push (see `tryRewriteIn`'s own
         * header for the full reasoning).
         */
        private InMatch tryRewriteInScan(List<BytecodeToken> inScanLine, List<List<BytecodeToken>> outSoFar) {
            String t1 = inScanLine.get(1).text;
            String t2 = inScanLine.get(2).text;
            String t3 = inScanLine.get(3).text;

            List<BytecodeToken> rightPush = outSoFar.get(outSoFar.size() - 1);
            List<BytecodeToken> leftPush = outSoFar.get(outSoFar.size() - 2);

            CanonicalType leftCanonical = CanonicalType.parse(t1);
            CanonicalType rightCanonical = CanonicalType.parse(t2);
            if (leftCanonical.storage != null || !leftCanonical.baseType.equals("u64")
                    || !rightCanonical.baseType.equals("string")) {
                return null; // should never actually happen -- checkIn's own "u64 in string" branch guarantees this shape
            }

            List<String> lines = new ArrayList<>();
            boolean rightSimple = isSimplePush(rightPush);
            String rightName;
            String rightType = t2;
            if (rightSimple) {
                rightName = rightPush.get(1).text;
            } else {
                rightName = declareTemp(outSoFar, t2);
                lines.add("POP " + rightName + " " + t2);
            }
            boolean leftSimple = rightSimple && isSimplePush(leftPush);
            String leftName;
            if (leftSimple) {
                leftName = leftPush.get(1).text;
            } else {
                leftName = declareTemp(outSoFar, t1);
                lines.add("POP " + leftName + " " + t1);
            }

            // NOT `isGenuinePointer` here -- a real, found-and-fixed bug,
            // confirmed directly against a real compiled+optimized probe
            // ("code in \"hello\""): a string literal's own canonical type
            // is always "static_some_imut_string" (see `CanonicalType`'s
            // own doc comment, fixed alongside this), and `isGenuinePointer`
            // (storage != null && !isOwnsStorage()) treats any non-"owns"
            // storage as a wrapping indirection needing a `DEREF` first --
            // correct for a *struct*-typed operand (where "raw"/"ref"/
            // "auto"/"static" all genuinely point *at* a boxed value sitting
            // elsewhere), but wrong for `string`: `string` is this
            // language's one and only `pointerLikeBaseType` (confirmed
            // directly, `TypeChecker.pointerLikeBaseTypes`), meaning its
            // value already *is* the pointer, at every one of the five
            // storage keywords -- "static" is a string's own intrinsic,
            // already-addressable representation the exact same way "owns"
            // already is for a dynarray's own runtime handle (this pass's
            // own `isGenuinePointer` doc comment), not a second indirection
            // layer. Naively dereferencing a "static"-storage string
            // produced genuinely wrong bytecode ("PUSH string_id1 / DEREF"
            // on a bare string-literal push, confirmed against the real
            // optimized probe output) before this fix. Only a genuinely
            // *further*-wrapping storage on a string ("raw"/"ref", a
            // pointer to a string sitting elsewhere -- legally possible per
            // `checkIn`'s own "only baseType is checked, never storage"
            // comment) still needs dereferencing here.
            boolean rightNeedsDeref = rightCanonical.storage != null && !"static".equals(rightCanonical.storage);
            if (rightNeedsDeref) {
                String[] deref = derefInto(rightName, rightType, rightCanonical, outSoFar, lines);
                rightName = deref[0];
                rightType = deref[1];
            }

            lines.addAll(buildStringScanCheck(leftName, t1, rightName, rightType, t3, outSoFar));
            return new InMatch(lines, !leftSimple, !rightSimple);
        }

        /**
         * "leftValue in rightString" -- a real byte-by-byte runtime scan,
         * walking forward from index 0 until either a matching byte is
         * found (pushes `true`) or the null terminator is hit first
         * (pushes `false`) -- see `tryRewriteInScan`'s own header for why
         * this needs no `strlen()` call and no length known upfront.
         *
         * Each loop iteration:
         *   1. Reads the string byte at `counter` via an ordinary
         *      `LOOKUP` (`AddressLoweringPass.lookupMnemonicFor` maps a
         *      string's own baseType to `LOOKUP_ARRAY`, exactly like a
         *      fixed array -- direct, fixed-width addressing). This is a
         *      genuinely unchecked, un-bounds-proven index read -- fine
         *      here specifically because `checkLookup`'s source-level
         *      "index must be a literal or have a live bounds-proof" gate
         *      only ever binds bytecode the *source* compiles through
         *      `TypeChecker`, never bytecode an optimizer pass fabricates
         *      directly (this pass already relies on that exact
         *      distinction implicitly everywhere else it synthesizes
         *      PUSH/POP/comparison sequences) -- and a forward scan that's
         *      guaranteed to stop at the first '\0' byte is exactly the
         *      same trust model `strlen`/`strchr` already rely on in C.
         *   2. Compares that byte against the literal `'\0'` first, at the
         *      true single-byte width both sides share (`imut_char`,
         *      confirmed 1 byte -- `SizeCalculator`) -- matters because
         *      `AddressLoweringPass`'s GT/LT/EQ family sizes a comparison
         *      from its *first* operand's type alone, so both pushed
         *      values must actually be the same width at runtime.
         *   3. Compares that same byte, widened to a full `u64` via `ZEXT`
         *      (always a genuine 1-to-8-byte zero-extend here, never a
         *      same-width no-op), against `leftName` -- both sides now
         *      genuinely 8 bytes wide, matching that same "first operand's
         *      type decides the whole comparison's width" rule.
         *   4. On no match, increments `counter` and loops back -- the
         *      identical `ADDR`/`PUSH`/`INC`/`ASSIGN` shape
         *      `CloneGenerationPass.buildCloneLoop`'s own counter
         *      increment already establishes, reused verbatim rather than
         *      inventing a second convention for the same operation.
         * The two divergent exits (found vs. hit-the-terminator) each push
         * their own boolean literal and fall straight through to the same
         * next instruction -- an ordinary stack-machine merge, no
         * different from how an if/else's own two branches already
         * converge with no explicit "phi" needed.
         */
        private List<String> buildStringScanCheck(String leftName, String leftType, String rightName,
                String rightType, String resultType, List<List<BytecodeToken>> outSoFar) {
            List<String> lines = new ArrayList<>();

            String counter = declareTemp(outSoFar, "mut_u64");
            lines.add("ADDR " + counter + " mut_u64");
            lines.add("PUSH 0 indeterminate_u64");
            lines.add("ASSIGN mut_u64 mut_u64 mut_u64");

            String charTemp = declareTemp(outSoFar, "imut_char");

            String topLabel = newLabel("in_scan_loop");
            String notFoundLabel = newLabel("in_scan_not_found");
            String incrementLabel = newLabel("in_scan_increment");
            String endLabel = newLabel("in_scan_end");

            lines.add(topLabel + ":");
            lines.add("PUSH " + rightName + " " + rightType);
            lines.add("PUSH " + counter + " mut_u64");
            lines.add("LOOKUP " + rightType + " mut_u64 imut_char");
            lines.add("POP " + charTemp + " imut_char");

            lines.add("PUSH " + charTemp + " imut_char");
            lines.add("PUSH '\\0' imut_char");
            lines.add("EQ imut_char imut_char imut_bool");
            lines.add("CMP");
            lines.add("JMP " + notFoundLabel);

            lines.add("PUSH " + charTemp + " imut_char");
            lines.add("ZEXT imut_char indeterminate_u64");
            lines.add("PUSH " + leftName + " " + leftType);
            lines.add("EQ indeterminate_u64 " + leftType + " imut_bool");
            lines.add("CMP");
            lines.add("JMP " + incrementLabel);

            lines.add("PUSH true " + resultType);
            lines.add("JMP " + endLabel);

            lines.add(incrementLabel + ":");
            lines.add("ADDR " + counter + " mut_u64");
            lines.add("PUSH " + counter + " mut_u64");
            lines.add("INC mut_u64 indeterminate_u64");
            lines.add("ASSIGN mut_u64 indeterminate_u64 indeterminate_u64");
            lines.add("JMP " + topLabel);

            lines.add(notFoundLabel + ":");
            lines.add("PUSH false " + resultType);

            lines.add(endLabel + ":");

            return lines;
        }

        private String newLabel(String prefix) {
            labelCounter++;
            return "@" + prefix + "_" + labelCounter;
        }

        /**
         * "len(unsafeDynArr, terminator)" -- the identical "no stored
         * length, must scan" situation `IN_SCAN` already had for a
         * string, just parameterized: the terminator is a real runtime
         * operand (any value, not hardcoded to `'\0'`), and the element
         * type is whatever the unsafe dynarray was declared with, not
         * fixed to `char`. Confirmed directly, right after `IN_SCAN`
         * shipped: "or base + 8 + index*width if 8 is for the length
         * stored at the beginning, if its safe one or an unsafe one...
         * this needs to be comprehensively fixed" -- the `LOOKUP`/
         * `LOOKUP_DYN` addressing fix that unblocked this (see this
         * project's own CLAUDE.md, "LOOKUP_DYN's 'handle indirection' was
         * a design bug") is what makes an ordinary `LOOKUP` into the
         * unsafe dynarray's own elements resolve correctly at all.
         *
         * Genuinely *simpler* than `buildStringScanCheck` in one respect:
         * `checkLenBuiltin`'s own 2-arg branch requires the terminator's
         * `baseType` to match the unsafe dynarray's element `baseType`
         * exactly, so both sides of the per-iteration comparison are
         * always already the same width -- no `ZEXT`/widening step is
         * ever needed here, unlike `IN_SCAN`'s fixed 1-byte-`char`-vs-
         * 8-byte-`u64` mismatch. And *no dereference handling at all* is
         * needed for the target: `TypeInfo`'s own `unsafeDynArray`
         * constructor unconditionally forces `"owns"` storage the moment
         * an "unsafe dynarray(T)" type annotation is parsed (rejecting
         * any other storage keyword outright,
         * `TypeChecker`'s own explicit check) -- so, unlike a string or
         * an ordinary dynarray, a genuinely pointer-wrapped unsafe
         * dynarray target can never legally exist in the first place;
         * `isGenuinePointer`/`derefInto` would never have anything to do
         * here.
         *
         * Unlike `IN_SCAN` (a boolean membership test), this is a real
         * *count*: the loop's own running index, at the moment the
         * terminator is found, IS the answer (the terminator itself is
         * never counted) -- so the loop's exit path pushes the counter's
         * own current value, not a `true`/`false` literal.
         */
        private InMatch tryRewriteLenScan(List<BytecodeToken> lenScanLine, List<List<BytecodeToken>> outSoFar) {
            String t1 = lenScanLine.get(1).text; // the unsafe dynarray target's own type
            String t2 = lenScanLine.get(2).text; // the terminator's own type

            List<BytecodeToken> rightPush = outSoFar.get(outSoFar.size() - 1); // the terminator -- pushed last, sits on top
            List<BytecodeToken> leftPush = outSoFar.get(outSoFar.size() - 2); // the target -- pushed first

            CanonicalType targetCanonical = CanonicalType.parse(t1);
            if (CanonicalType.unsafeDynArrayElementTypeOf(targetCanonical.baseType) == null) {
                return null; // should never actually happen -- checkLenBuiltin's own 2-arg branch guarantees an unsafe dynarray target
            }

            List<String> lines = new ArrayList<>();
            boolean rightSimple = isSimplePush(rightPush);
            String termName;
            if (rightSimple) {
                termName = rightPush.get(1).text;
            } else {
                termName = declareTemp(outSoFar, t2);
                lines.add("POP " + termName + " " + t2);
            }
            boolean leftSimple = rightSimple && isSimplePush(leftPush);
            String targetName;
            if (leftSimple) {
                targetName = leftPush.get(1).text;
            } else {
                targetName = declareTemp(outSoFar, t1);
                lines.add("POP " + targetName + " " + t1);
            }

            lines.addAll(buildLenScanCheck(targetName, t1, termName, t2, outSoFar));
            return new InMatch(lines, !leftSimple, !rightSimple);
        }

        /**
         * "len(targetName, termName)" -- walk forward from index 0,
         * comparing each element against the terminator, until a match is
         * found; the index at that point is the length. See
         * `tryRewriteLenScan`'s own header for why no widening and no
         * dereference are ever needed here, unlike `buildStringScanCheck`.
         */
        private List<String> buildLenScanCheck(String targetName, String targetType, String termName,
                String termType, List<List<BytecodeToken>> outSoFar) {
            List<String> lines = new ArrayList<>();

            String counter = declareTemp(outSoFar, "mut_u64");
            lines.add("ADDR " + counter + " mut_u64");
            lines.add("PUSH 0 indeterminate_u64");
            lines.add("ASSIGN mut_u64 mut_u64 mut_u64");

            String elemTemp = declareTemp(outSoFar, termType);

            String topLabel = newLabel("len_scan_loop");
            String incrementLabel = newLabel("len_scan_increment");
            String endLabel = newLabel("len_scan_end");

            lines.add(topLabel + ":");
            lines.add("PUSH " + targetName + " " + targetType);
            lines.add("PUSH " + counter + " mut_u64");
            lines.add("LOOKUP " + targetType + " indeterminate_u64 " + termType);
            lines.add("POP " + elemTemp + " " + termType);

            lines.add("PUSH " + elemTemp + " " + termType);
            lines.add("PUSH " + termName + " " + termType);
            lines.add("EQ " + termType + " " + termType + " imut_bool");
            lines.add("CMP");
            lines.add("JMP " + incrementLabel);

            lines.add("PUSH " + counter + " indeterminate_u64");
            lines.add("JMP " + endLabel);

            lines.add(incrementLabel + ":");
            lines.add("ADDR " + counter + " mut_u64");
            lines.add("PUSH " + counter + " mut_u64");
            lines.add("INC mut_u64 indeterminate_u64");
            lines.add("ASSIGN mut_u64 indeterminate_u64 indeterminate_u64");
            lines.add("JMP " + topLabel);

            lines.add(endLabel + ":");

            return lines;
        }

        /**
         * Dereferences a pointer-typed operand already sitting in a
         * named local (`name`, of canonical type `type`/`canonical`) into
         * a fresh, storage-free temp: "PUSH name type" / "DEREF derefType"
         * / "POP derefTemp derefType", appended to `lines`, reusing this
         * pass's existing `declareTemp`/widened-`POP` machinery. The
         * returned temp's own type has storage stripped (`mutability_
         * baseType`, matching `TypeChecker.checkDerefBuiltin`'s own
         * `TypeInfo`), so a dotted `.start`/`.end`/`.<field>` access on it
         * resolves in `AddressLoweringPass` exactly like an ordinary,
         * non-pointer value of that same shape already does. Returns
         * `{derefName, derefType}`. Only ever called when
         * `canonical.storage != null` -- callers check that themselves,
         * since "was this pointer-wrapped at all" also decides whether the
         * *type text* handed to a later comparison builder needs to
         * change to the dereferenced one.
         *
         * `DEREF` now carries its own pointee-type operand -- a real,
         * found-and-fixed gap, confirmed directly: "a deref is
         * essentially a copy," and on this bytecode's own byte-precise
         * stack, a copy needs to know how many bytes to move, which the
         * old bare "DEREF" (matching `BytecodeEmitter`'s own former
         * shape, before this same fix) never carried anywhere recoverable
         * -- the preceding "PUSH name type" line's own erased width is
         * always exactly 8 (every pointer is one machine word), which
         * describes the *pointer's* size, never the *pointee's*.
         * `derefType` (already computed here, for the exact same reason
         * the following `POP` line needs it) is exactly the operand
         * `DEREF` itself needs too, so this already-correct call site
         * needed no separate lookup -- just passing the same value
         * through to one more line.
         */
        private String[] derefInto(String name, String type, CanonicalType canonical,
                List<List<BytecodeToken>> outSoFar, List<String> lines) {
            String derefType = canonical.mutability != null
                    ? canonical.mutability + "_" + canonical.baseType
                    : canonical.baseType;
            String derefName = declareTemp(outSoFar, derefType);
            lines.add("PUSH " + name + " " + type);
            lines.add("DEREF " + derefType);
            lines.add("POP " + derefName + " " + derefType);
            return new String[]{derefName, derefType};
        }

        /**
         * Declares a fresh, uniquely-named scratch local ("$in_tmpN") for
         * materializing an "in" operand that wasn't a simple named push
         * (see tryRewriteIn's own "materialize the left operand" branch)
         * -- inserts its own "ALLOC name type" line into `out` right after
         * this function's existing ALLOC prologue block (tracked by
         * allocInsertIndex, computed once per function in
         * rewriteFunctionBody). A "$"-prefixed name can never collide with
         * a real source identifier (the same convention EnumTable's own
         * "$enum_for_" synthetic names already establish), and it needs no
         * separate cleanup of any kind -- it's an ordinary local from this
         * point on, gone the same way any other local already is once the
         * function returns.
         */
        private String declareTemp(List<List<BytecodeToken>> out, String type) {
            String name = "$in_tmp" + (++tempCounter);
            out.add(allocInsertIndex,
                    PARSER.parse(Collections.singletonList("ALLOC " + name + " " + type),
                            "<generated-membership-lowering>").get(0));
            allocInsertIndex++;
            return name;
        }

        /**
         * `keepLeftPush` mirrors `InMatch`'s own field of the same name --
         * true exactly when the left operand had to be materialized into a
         * fresh temp rather than being a simple named push (see
         * `tryRewriteInstanceofOrImplements`'s own "materialize the left
         * operand" handling, below), meaning its own original bytecode is
         * essential (real computation, possibly with side effects) and
         * must be left standing in `out` rather than dropped.
         */
        private static final class InstanceofMatch {
            final List<String> lines;
            final boolean keepLeftPush;
            InstanceofMatch(List<String> lines, boolean keepLeftPush) {
                this.lines = lines;
                this.keepLeftPush = keepLeftPush;
            }
        }

        /**
         * Originally required the struct-typed left operand of
         * "instanceof"/"implements" to already be a simple named push
         * ("PUSH name type"), unconditionally bailing (leaving the
         * original INSTANCEOF/IMPLEMENTS bytecode untouched) for anything
         * else -- e.g. "makeThing() instanceof Foo", where the left
         * operand is a multi-line CALL sequence ending in its own
         * "PUSH_RET type" line, never a bare "PUSH name type". This was
         * never a correctness bug the way "in"'s own analogous gap once
         * was (a *complex left operand of "in"* materialization mistake
         * this pass's own header documents at length) -- confirmed
         * directly by compiling and lowering a real "makeSub() instanceof
         * Base" fixture: the pass correctly detected the non-simple shape
         * and left the original bytecode standing, unmodified but
         * perfectly correct, just unoptimized. But it *was* an
         * incompleteness this pass simply hadn't gotten around to yet --
         * "this is because we did the implements/instanceof before the IN
         * work... the implements/instanceof just doesn't reflect that,"
         * confirmed directly -- so it's fixed here the same way "in"'s own
         * left operand was: materialize into a fresh temp via
         * `declareTemp`/a "POP name type" right after the operand's own
         * (untouched) emission, then read the temp back by name exactly
         * like a simple push would have been.
         *
         * Unlike "in", there is no analogous "right operand might be
         * multi-line, so don't trust outSoFar's second-to-last-entry
         * position" risk to guard against here at all: `checkInstanceof`/
         * `checkImplementsOperator` both require the right/target operand
         * to be a bare `VARREF` naming a real struct/interface (never a
         * computed value), so `BytecodeEmitter` only ever emits it as a
         * single, invariant "PUSH name" line (`isBarePush`, already
         * checked via `targetPushLine`). That means `outSoFar`'s second-
         * to-last entry is *always* reliably the left operand's own final
         * line, however many lines produced it -- no unconditional-
         * materialize-both-sides workaround (the "in" fix's own
         * safety net for its genuinely multi-line right operand) is
         * needed at all here.
         *
         * The left operand's own type (needed both to confirm it's a
         * classId-bearing struct at all, and to declare a correctly-typed
         * temp for it) is read off that same final line's own trailing
         * type token when it isn't a simple push -- true of every
         * terminal, single-value-producing instruction this bytecode
         * emits ("PUSH_RET type" for a CALL result, "LOOKUP ... resultType"
         * for an array/dynarray element, an ordinary "PUSH name type" for
         * a simple push, ...): the result type is always the line's very
         * last token, the same convention `declareTemp`'s own callers
         * already rely on elsewhere in this file. A genuinely typeless
         * final line (a bare, operand-free "DEREF", say) has no such
         * token at all -- `leftPush.size() < 2` catches that and bails,
         * joining the same "pattern doesn't match, leave it alone"
         * category every other unrecognized shape in this pass already
         * falls into, rather than guessing.
         *
         * Every bail-out that doesn't depend on the left operand's own
         * name (the struct/`___type`-field check, `INSTANCEOF`'s
         * `classRangeOf` lookup) happens *before* any temp is declared, so
         * a rewrite that ultimately can't proceed never leaves a stray,
         * unused "ALLOC"/orphaned "POP" behind -- `declareTemp` itself,
         * which mutates `outSoFar` immediately, is only ever called once
         * every other gate has already passed and the rewrite is
         * genuinely committed to succeeding.
         *
         * The zero-implementer `IMPLEMENTS` case (see the "PUSH false"
         * branch below) still materializes a non-simple left operand
         * exactly the same way, even though the constant-false result
         * never actually reads the temp back -- the original left
         * operand's own bytecode (and any side effects it has, e.g. a
         * real function call) must still run and have its value properly
         * consumed off the stack either way; only whether the generated
         * comparison text goes on to *reference* that temp differs.
         *
         * `leftType` is read directly off the `INSTANCEOF leftType`/
         * `IMPLEMENTS leftType` line itself (`BytecodeEmitter`'s own
         * "instanceof"/"implements" case), not inferred from whatever
         * bytecode line happens to precede it -- a real, confirmed bug,
         * fixed directly: the earlier version of this method read
         * `leftPush`'s own trailing token as "the operand's type," which
         * is correct only when `leftPush` really is the operand's one and
         * only line. `checkInstanceof` allows a bare, non-storage struct
         * value on the left (`requiresAliveProof`'s own carve-out for "an
         * inline (no-storage)... struct value"), and a bare, non-`new`
         * struct *literal* ("Point{x=1} instanceof Point" -- confirmed
         * legal by actually compiling it) is exactly that: emitted by
         * `emitInstantiate`'s non-`new` path as classId plus every member
         * pushed back-to-back with no consolidating instruction, so
         * `leftPush` in that case is only ever the *last member's own*
         * scalar push, whose trailing token is that member's own type,
         * never the struct's. Reading `leftType` off this operator's own
         * line instead closes that gap the same way `tryRewriteIsBase`'s
         * identical fix does.
         */
        private InstanceofMatch tryRewriteInstanceofOrImplements(String mnemonic, List<BytecodeToken> targetPushLine,
                String leftType, List<List<BytecodeToken>> outSoFar) {
            if (!isBarePush(targetPushLine)) {
                return null;
            }
            String targetName = targetPushLine.get(1).text;

            // outSoFar's last entry mirrors the target-name push itself
            // (already inspected separately, above, as `targetPushLine`
            // -- both refer to the same original line); the one before
            // it is the struct-typed left operand's own final line,
            // reliably so regardless of how many lines produced it (see
            // this method's own doc comment for why the target's
            // guaranteed single-line shape makes this safe, unlike "in").
            List<BytecodeToken> leftPush = outSoFar.get(outSoFar.size() - 2);
            boolean leftSimple = isSimplePush(leftPush);
            String structName = CanonicalType.parse(leftType).baseType;
            // An interface-typed left (`members == null` -- an interface
            // carries no STRUCT_START/STRUCT_MEMBER block of its own in
            // StructTable at all) is deliberately NOT rejected here
            // anymore -- confirmed directly: "the code generator
            // shouldn't care if the lefthand is struct-typed or
            // interface-typed, it's just a pointer to get the ___type
            // from." Whatever an interface reference actually points to
            // at runtime is always some real, concrete struct, and every
            // real struct's own hidden "___type" field is always at a
            // fixed offset (0) regardless of which static type (the
            // concrete struct itself, or an interface with no layout of
            // its own) was used to reach it -- see
            // AddressLoweringPass.memberLocOf, which now resolves
            // "___type" this same structure-agnostic way. `checkInstanceof`/
            // `checkImplementsOperator` already guarantee `structName`
            // names a real struct or a real interface by the time this
            // bytecode exists, so `members == null` here means
            // "interface," never "unresolvable name." The
            // `hasTypeField` check is kept, but now only as a defensive
            // guard against a real, *registered* struct somehow missing
            // its own "___type" field (should never happen, per the
            // compiler's own "___type is always first" invariant).
            List<StructTable.Member> members = structTable.membersOf(structName);
            if (members != null && !hasTypeField(members)) {
                return null; // a real, registered struct missing "___type" -- shouldn't happen, defensive bail
            }

            if (mnemonic.equals("INSTANCEOF")) {
                long[] range = enumTable.classRangeOf(targetName);
                if (range == null) {
                    return null; // targetName isn't in the "Class" hierarchy (most commonly: it's an interface)
                }
                List<String> lines = new ArrayList<>();
                String leftName = resolveLeftName(leftSimple, leftPush, leftType, outSoFar, lines);
                String typeFieldPath = typeFieldPathFor(leftName, leftType, outSoFar, lines);
                lines.addAll(buildRangeMembershipCheck(typeFieldPath, range[0], range[1]));
                return new InstanceofMatch(lines, !leftSimple);
            } else {
                List<Long> implementerIds = enumTable.implementerClassIdsOf(targetName);
                List<String> lines = new ArrayList<>();
                String leftName = resolveLeftName(leftSimple, leftPush, leftType, outSoFar, lines);
                if (implementerIds.isEmpty()) {
                    // "x implements SomeInterface" where SomeInterface has
                    // no registered implementers anywhere in the whole
                    // compilation unit (never a source-order thing: every
                    // "impl X for Y" block is collected before any function
                    // body is checked, so this is a genuinely whole-program
                    // fact, not "none found yet") -- legal source (the
                    // interface itself is real, `checkImplementsOperator`
                    // never requires it to have an implementer at all), but
                    // provably always false: there is no possible runtime
                    // classId `left.___type` could ever hold that would
                    // satisfy it. Confirmed directly, reachable from real
                    // source (a declared-but-unimplemented interface),
                    // unlike `INSTANCEOF`'s own empty-range case just above
                    // (never actually reachable -- every legal `instanceof`
                    // target is a real struct, and every real struct gets
                    // at least a singleton `[classId,classId]` entry in the
                    // "Class" table). Lowered to the same "single constant
                    // value" shape a real, non-empty chain would ultimately
                    // leave on the stack (a bare "imut_bool", matching
                    // `checkImplementsOperator`'s own result type) --
                    // `left`'s own push (or, when materialized, its own
                    // POP-consumed temp) is still dropped/discarded by the
                    // caller like any other rewrite here, since nothing
                    // about `left`'s actual runtime value is needed to
                    // know the answer -- only that whatever produced it
                    // (including any side effects) still actually ran.
                    // Deliberately not a compiler-side error or even a
                    // rejection here -- this pass's whole job is silent
                    // lowering; a human-facing diagnostic about a
                    // pointless/always-false check belongs at the type-
                    // checker level instead (see `checkImplementsOperator`'s
                    // own warning for this exact shape).
                    lines.add("PUSH false imut_bool");
                    return new InstanceofMatch(lines, !leftSimple);
                }
                // Only reached once there's a real, non-empty OR-chain to
                // build (so left's own runtime classId is actually
                // needed) -- unlike the "PUSH false" branch just above,
                // which never dereferences a pointer-typed left at all
                // since its result never depends on left's value.
                String typeFieldPath = typeFieldPathFor(leftName, leftType, outSoFar, lines);
                lines.addAll(buildValueOrChain(typeFieldPath, implementerIds));
                return new InstanceofMatch(lines, !leftSimple);
            }
        }

        /**
         * Resolves the left operand's own name for `typeFieldPath`
         * construction -- a simple push's own name, read fresh (no side
         * effect), or, for a non-simple left operand, the point where this
         * rewrite commits to materializing it: `declareTemp` (which
         * mutates `outSoFar` immediately, inserting a real "ALLOC" line)
         * is only ever called here, after every other gate that could
         * still bail this rewrite out has already passed, and the
         * resulting "POP name type" line -- which actually consumes the
         * left operand's already-computed value off the stack, preserving
         * whatever real computation (and any side effects) produced it --
         * is appended to `lines` before anything else.
         */
        private String resolveLeftName(boolean leftSimple, List<BytecodeToken> leftPush, String leftType,
                List<List<BytecodeToken>> outSoFar, List<String> lines) {
            if (leftSimple) {
                return leftPush.get(1).text;
            }
            String leftName = declareTemp(outSoFar, leftType);
            lines.add("POP " + leftName + " " + leftType);
            return leftName;
        }

        /**
         * Builds "leftName.___type" for `buildRangeMembershipCheck`/
         * `buildValueOrChain` -- dereferencing first, via the identical
         * `derefInto` machinery `tryRewriteIn`'s own pointer-typed range/
         * dynarray handling already uses, whenever `leftType` itself
         * carries a storage prefix. Found and fixed directly while
         * verifying the "materialize a non-simple left operand" work
         * above: a struct-returning function can *only* return a
         * storage-qualified value ("owns"/"ref"/"raw"/"auto"/"static" --
         * TypeChecker rejects returning a struct "by value" outright), so
         * the very shape that fix exists for ("makeThing() instanceof
         * Foo") is *always* storage-qualified -- but this pre-existing
         * gap turns out to affect the already-shipped *simple*-push case
         * identically: confirmed directly against a real compiled fixture
         * ("let sAuto = mut auto s; sAuto instanceof Base", no call
         * involved at all) that a bare "auto"-storage simple local hit
         * the exact same problem before either of today's fixes existed.
         * `AddressLoweringPass.resolveAddress`'s own dotted-chain
         * resolution unconditionally bails ("return null // pointer
         * indirection -- known gap") the moment any segment's own type
         * carries a storage prefix -- so emitting "leftName.___type"
         * directly against a storage-qualified `leftName` doesn't fail
         * loudly, it just leaves that exact dotted text sitting
         * unresolved, verbatim, in the supposedly-fully-lowered final
         * bytecode (confirmed directly: "PUSH 8 sAuto.___type" survived
         * all the way to the end of the pipeline, untouched, next to
         * ordinary already-resolved "$-N" addresses around it) --
         * silently wrong, not a clean bail. Unlike `isGenuinePointer`
         * (this pass's own dynarray-specific carve-out, which treats
         * "owns" as a dynarray value's own intrinsic representation, not
         * a wrapping indirection needing a further deref), a struct's own
         * storage-qualified local is *never* the inline struct sitting
         * directly at that address -- `resolveAddress`'s gate is
         * unconditional on any non-null storage, "owns" included, with no
         * such carve-out anywhere in `AddressLoweringPass` -- so the
         * right check here is plain `canonical.storage != null`, not
         * `isGenuinePointer`.
         */
        private String typeFieldPathFor(String leftName, String leftType, List<List<BytecodeToken>> outSoFar,
                List<String> lines) {
            CanonicalType canonical = CanonicalType.parse(leftType);
            if (canonical.storage != null) {
                String[] deref = derefInto(leftName, leftType, canonical, outSoFar, lines);
                return deref[0] + ".___type";
            }
            return leftName + ".___type";
        }

        // ---- shape checks -------------------------------------------------

        /**
         * True only for a genuine pointer *wrapping* a value ("raw X",
         * "ref X", ...) -- deliberately NOT true for "owns" storage,
         * which is a dynarray's (or an owns-allocated struct's) own
         * intrinsic representation, not a wrapping indirection. Plain
         * `canonical.storage != null` looks right but is a real, found-
         * and-fixed bug: every ordinary dynarray value already carries
         * "owns" storage itself (the same "buffer pointer plus
         * bookkeeping" handle LEN/LOOKUP_DYN already read directly, no
         * dereference needed), so that plain check wrongly dereferenced
         * *every* "x in dynarray" using a bare, non-pointer dynarray
         * local -- not just the genuinely pointer-wrapped ones this
         * pass's own dereference step exists for.
         */
        private static boolean isGenuinePointer(CanonicalType canonical) {
            return canonical.storage != null && !canonical.isOwnsStorage();
        }

        /**
         * True only for a token this compiler's own emission rules could
         * ever have produced as a *real, addressable reference* -- a bare
         * variable/parameter/temp name, a dotted chain rooted in one
         * ("p.x", "r.start"), or a hoisted string literal's own synthetic
         * id ("string_id1", a genuine, separately-declared name a later
         * stage resolves like any other global) -- as opposed to a bare
         * literal *value* that merely happens to sit in the same "PUSH X
         * type" text position: a raw integer/float digit sequence, a
         * quoted char literal, or one of the fixed literal keywords
         * (`true`/`false`/`null`).
         *
         * This is the real, root-cause fix for a bug found and reported
         * directly: `isSimplePush`/`isBarePush` used to check *shape*
         * only (token count + "PUSH"), which cannot tell a genuine single-
         * line reference apart from a bare literal that happens to have
         * the identical shape -- confirmed to actually misfire on an
         * inline range literal ("2..5"), which `BytecodeEmitter` emits as
         * two raw, back-to-back scalar pushes ("PUSH 2 type" / "PUSH 5
         * type") with no consolidating instruction: the *second* of those
         * two lines is indistinguishable, by shape alone, from a genuine
         * one-line "PUSH r type" reference to a whole range variable, so
         * every rewrite here that grabbed "the line right before this op"
         * and trusted it to be a real, dottable reference was silently
         * wrong whenever that operand was actually an inline range
         * literal instead.
         *
         * Why a syntactic check, not a symbol-table lookup: this pass runs
         * *first* in the fixed lowering pipeline (`MembershipLoweringPass`,
         * `CloneGenerationPass`, `DropGlueGenerationPass`,
         * `ArgToAllocLoweringPass`, `AddressLoweringPass` -- see this
         * project's own CLAUDE.md), strictly before `AddressLoweringPass`
         * ever builds the real name -> offset table that could answer "is
         * this actually a declared storage location" with certainty. There
         * is no such table here to consult, and forcing one into existence
         * this early would be a much larger, unjustified restructuring for
         * a fact that's already fully decidable without it: every real
         * name this compiler's own `BytecodeEmitter` ever writes into a
         * `PUSH` line's name position (a `VARREF`'s own text, `emitDot`'s
         * qualified-dot-name, a hoisted string id, or a compiler-
         * synthesized hidden local such as a `for` loop's own
         * `$for_range_N` range holder) is, by construction, a legal
         * identifier optionally prefixed with `$` -- and every literal
         * value shape it ever writes there instead (an `INTEGER`/`FLOAT`
         * token's digits, a quoted `CHAR`, or the fixed `true`/`false`/
         * `null` keywords) provably never starts either way. So "is this
         * syntactically shaped like a real name" is exactly as reliable a
         * signal, at this pass's own stage, as an actual symbol-table
         * lookup would be one stage later -- it isn't a weaker heuristic
         * standing in for the real check, it's the same fact, checked the
         * only way it's actually available yet.
         *
         * **`$` is a real name prefix here, not just a post-address-
         * lowering artifact** -- a genuine bug, found and fixed directly:
         * the first version of this check required a name to start with a
         * letter or underscore, missing that this compiler's own
         * `BytecodeEmitter` *already* synthesizes hidden locals with a
         * literal `$` prefix at the higher-order-bytecode stage this pass
         * itself runs at -- `for x in range{...}`'s own hidden range
         * holder is declared and pushed as a perfectly ordinary single
         * line, "PUSH $for_range_1 imut_range(...)" (confirmed directly
         * against real compiled `for`-loop output), and this pass's own
         * `declareTemp` uses the identical `$in_tmp` convention for
         * exactly the same reason. Rejecting a leading `$` wrongly
         * treated that whole, genuinely single-line reference as "not
         * simple," forcing an unneeded materialization -- which surfaced
         * immediately and loudly the first time a real stdlib function
         * with actual `ARG` parameters ahead of its `ALLOC` block (`for`
         * loops appear throughout `ghost_table.caspien`/
         * `gt_alive_check.caspien`/etc.) contained a `for` loop at all,
         * colliding with a second, independent, previously-latent gap in
         * `allocInsertIndex`'s own computation (see its own doc comment,
         * just below, for that half of the fix).
         */
        private static boolean isRealNameToken(String text) {
            if (text.isEmpty()) {
                return false;
            }
            if (text.equals("true") || text.equals("false") || text.equals("null")) {
                return false; // the fixed literal keywords -- identifier-shaped, but never a real name
            }
            char first = text.charAt(0);
            if (!(Character.isLetter(first) || first == '_' || first == '$')) {
                return false; // every literal shape (digits, a signed digit, a quoted char) fails this; every real name -- including a "$"-prefixed compiler-synthesized one -- passes it
            }
            for (int i = 1; i < text.length(); i++) {
                char c = text.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '_' || c == '.')) {
                    return false; // '.' allowed for a dotted qualified name ("p.x", "r.start")
                }
            }
            return true;
        }

        /** A plain "PUSH name" with no type operand -- the shape a bare type/interface/enum name is pushed as (never a real value read). Both of this pass's own call sites additionally compare the pushed text against a real, known-at-compile-time enum/class/interface name, so a literal could never satisfy either check anyway -- the `isRealNameToken` guard is added here too only for defense-in-depth/consistency with `isSimplePush`, not because either call site was actually found vulnerable. */
        private static boolean isBarePush(List<BytecodeToken> line) {
            return line.size() == 2 && line.get(0).text.equals("PUSH") && isRealNameToken(line.get(1).text);
        }

        /** A plain "PUSH name type" -- the *entire* emission of a bare variable reference, a dotted field access rooted at one, or a hoisted string id -- see this pass's own header for why that's the only shape it ever reuses, and `isRealNameToken`'s own doc comment for why a bare literal value that happens to share this shape (e.g. one half of an inline range literal) is deliberately excluded rather than mistaken for one. */
        private static boolean isSimplePush(List<BytecodeToken> line) {
            return line.size() == 3 && line.get(0).text.equals("PUSH") && isRealNameToken(line.get(1).text);
        }

        private static boolean hasTypeField(List<StructTable.Member> members) {
            for (StructTable.Member m : members) {
                if (m.name.equals("___type")) {
                    return true;
                }
            }
            return false;
        }

        // ---- code generation -----------------------------------------------

        /** "x in range" -- "x >= range.start && x < range.end" (half-open, see this pass's own header). */
        private List<String> buildRangeCheck(String leftName, String leftType, String rangeName, String resultType) {
            List<String> lines = new ArrayList<>();
            if (rangeName.startsWith("$for_range_")) {
                // The per-iteration test of a compiler-generated `for` loop: the counter starts at
                // `range.start`, is immutable in the body (requireNotLoopImmutable) and only ever
                // incremented by the loop itself, and each increment is taken only after `i < end`
                // held, so it cannot wrap. `i >= start` is therefore always true; drop it.
                lines.add("PUSH " + leftName + " " + leftType);
                lines.add("PUSH " + rangeName + ".end indeterminate_u64");
                lines.add("LT " + leftType + " indeterminate_u64 " + resultType);
                return lines;
            }
            lines.add("PUSH " + leftName + " " + leftType);
            lines.add("PUSH " + rangeName + ".start indeterminate_u64");
            lines.add("GT_EQ " + leftType + " indeterminate_u64 imut_bool");
            lines.add("PUSH " + leftName + " " + leftType);
            lines.add("PUSH " + rangeName + ".end indeterminate_u64");
            lines.add("LT " + leftType + " indeterminate_u64 imut_bool");
            lines.add("AND imut_bool imut_bool " + resultType);
            return lines;
        }

        /** "x in dynarray" -- "x < len(dynarray)" -- the lower bound ("x >= 0") is always true for a plain u64 (checkIn requires it), so it's never emitted at all, per "the whole point of all of this is to limit the amount of work done, not more." */
        private List<String> buildDynArrayCheck(String leftName, String leftType, String dynName, String dynType,
                String resultType) {
            List<String> lines = new ArrayList<>();
            lines.add("PUSH " + leftName + " " + leftType);
            lines.add("PUSH " + dynName + " " + dynType);
            lines.add("LEN");
            lines.add("LT " + leftType + " indeterminate_u64 " + resultType);
            return lines;
        }

        /**
         * "r1 in r2" -- "r1.start >= r2.start && r1.end <= r2.end", the
         * ordinary *non-strict* subset test (allows `r1 == r2`) -- unlike
         * `WITHIN`'s own still-unhandled *strict* variant, which
         * additionally excludes the equal-bounds case. Both bounds are
         * read as ordinary dotted fields off each already-named range
         * operand, the identical mechanism `buildRangeCheck` already uses
         * for a scalar left operand's own comparisons against a range's
         * bounds -- no new instruction needed, just two dotted reads
         * compared against two more dotted reads instead of against a
         * bare scalar push. The upper-bound comparison is `LT_EQ`, not
         * `buildRangeCheck`'s own strict `LT` -- a real, deliberate
         * difference: "x in range" excludes `x == range.end` (half-open,
         * `end` is exclusive), but a *range* is legitimately still a
         * subset of another range whose own `.end` it exactly reaches
         * (`r1.end == r2.end` is fine; only `r1.end > r2.end` fails).
         */
        private List<String> buildRangeSubsetCheck(String leftRangeName, String rightRangeName, String resultType) {
            List<String> lines = new ArrayList<>();
            lines.add("PUSH " + leftRangeName + ".start indeterminate_u64");
            lines.add("PUSH " + rightRangeName + ".start indeterminate_u64");
            lines.add("GT_EQ indeterminate_u64 indeterminate_u64 imut_bool");
            lines.add("PUSH " + leftRangeName + ".end indeterminate_u64");
            lines.add("PUSH " + rightRangeName + ".end indeterminate_u64");
            lines.add("LT_EQ indeterminate_u64 indeterminate_u64 imut_bool");
            lines.add("AND imut_bool imut_bool " + resultType);
            return lines;
        }

        /**
         * "r1 within r2" -- the *strict* subset test `buildRangeSubsetCheck`
         * just above deliberately isn't: "r1.start >= r2.start && r1.end
         * <= r2.end" (the same non-strict subset "in" uses) AND NOT both
         * bounds being exactly equal -- "1..2 is within 0..3 but 0..3 is
         * not within 0..3," confirmed directly. Built as the non-strict
         * subset test, ANDed with the negation of a separate "are both
         * bounds exactly equal" check, rather than inventing a new
         * comparison primitive -- every piece here (`GT_EQ`/`LT_EQ`/`EQ`/
         * `AND`/`NOT`) is already emitted elsewhere in this pass or the
         * compiler itself. Each dotted `.start`/`.end` field is re-read
         * fresh every time it's needed (matching this pass's own
         * established convention throughout -- see `buildRangeCheck`'s
         * own repeated pushes), rather than trying to reuse a
         * still-on-the-stack value across the two separate boolean
         * chains.
         */
        private List<String> buildRangeStrictSubsetCheck(String leftRangeName, String rightRangeName,
                String resultType) {
            List<String> lines = new ArrayList<>();
            lines.add("PUSH " + leftRangeName + ".start indeterminate_u64");
            lines.add("PUSH " + rightRangeName + ".start indeterminate_u64");
            lines.add("GT_EQ indeterminate_u64 indeterminate_u64 imut_bool");
            lines.add("PUSH " + leftRangeName + ".end indeterminate_u64");
            lines.add("PUSH " + rightRangeName + ".end indeterminate_u64");
            lines.add("LT_EQ indeterminate_u64 indeterminate_u64 imut_bool");
            lines.add("AND imut_bool imut_bool imut_bool"); // non-strict subset
            lines.add("PUSH " + leftRangeName + ".start indeterminate_u64");
            lines.add("PUSH " + rightRangeName + ".start indeterminate_u64");
            lines.add("EQ indeterminate_u64 indeterminate_u64 imut_bool");
            lines.add("PUSH " + leftRangeName + ".end indeterminate_u64");
            lines.add("PUSH " + rightRangeName + ".end indeterminate_u64");
            lines.add("EQ indeterminate_u64 indeterminate_u64 imut_bool");
            lines.add("AND imut_bool imut_bool imut_bool"); // both bounds exactly equal
            lines.add("NOT imut_bool imut_bool"); // ... and NOT equal
            lines.add("AND imut_bool imut_bool " + resultType); // non-strict subset AND NOT equal
            return lines;
        }

        /**
         * "r in dynarray" -- "r.end <= LEN(dynarray)": the whole range is
         * a valid index span into the dynarray. The lower bound
         * ("r.start >= 0") is never emitted at all -- always trivially
         * true for a u64 range, the identical reasoning `buildDynArrayCheck`
         * already uses to drop it for a scalar `u64` left. Deliberately
         * `LT_EQ`, not `LT` -- a dynarray of length N has valid indices
         * `0..N-1`, and the range `0..N` (`end == N`) is *exactly* that
         * full valid span, so it must test as fully "in": `N <= N` is
         * true, `N < N` would wrongly reject it.
         */
        private List<String> buildRangeInDynArrayCheck(String rangeName, String dynName, String dynType,
                String resultType) {
            List<String> lines = new ArrayList<>();
            lines.add("PUSH " + rangeName + ".end indeterminate_u64");
            lines.add("PUSH " + dynName + " " + dynType);
            lines.add("LEN");
            lines.add("LT_EQ indeterminate_u64 indeterminate_u64 " + resultType);
            return lines;
        }

        /** "u64 in GuaranteedEnum" -- an OR-chain of "leftValue == variantValue" per explicit-valued variant. */
        private List<String> buildOrChain(String leftName, String leftType, List<Map.Entry<String, Long>> variants,
                String resultType) {
            List<String> lines = new ArrayList<>();
            boolean lone = variants.size() == 1;
            for (int i = 0; i < variants.size(); i++) {
                long value = variants.get(i).getValue();
                lines.add("PUSH " + leftName + " " + leftType);
                lines.add("PUSH " + value + " indeterminate_u64");
                lines.add("EQ " + leftType + " indeterminate_u64 " + (lone ? resultType : "imut_bool"));
                if (i > 0) {
                    lines.add("OR imut_bool imut_bool " + (i == variants.size() - 1 ? resultType : "imut_bool"));
                }
            }
            return lines;
        }

        /** "value in [lo,hi]" -- "value >= lo && value <= hi", inclusive both ends (a "Class" hierarchy range, unlike an ordinary user-facing range, is inclusive on both bounds -- see TypeChecker.walkClassHierarchy's own startIdx/endIdx construction). */
        private List<String> buildRangeMembershipCheck(String valuePath, long lo, long hi) {
            List<String> lines = new ArrayList<>();
            lines.add("PUSH " + valuePath + " imut_u64");
            lines.add("PUSH " + lo + " indeterminate_u64");
            lines.add("GT_EQ imut_u64 indeterminate_u64 imut_bool");
            lines.add("PUSH " + valuePath + " imut_u64");
            lines.add("PUSH " + hi + " indeterminate_u64");
            lines.add("LT_EQ imut_u64 indeterminate_u64 imut_bool");
            lines.add("AND imut_bool imut_bool imut_bool");
            return lines;
        }

        /** "value in {v0, v1, ...}" -- an OR-chain of "value == vi", one per implementer. */
        private List<String> buildValueOrChain(String valuePath, List<Long> values) {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < values.size(); i++) {
                lines.add("PUSH " + valuePath + " imut_u64");
                lines.add("PUSH " + values.get(i) + " indeterminate_u64");
                lines.add("EQ imut_u64 indeterminate_u64 imut_bool");
                if (i > 0) {
                    lines.add("OR imut_bool imut_bool imut_bool");
                }
            }
            return lines;
        }
    }
}
```

### FILE: src/main/java/caspien/lowerorder/OptimizationPass.java
```java
package caspien.lowerorder;

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

### FILE: src/main/java/caspien/lowerorder/PassResult.java
```java
package caspien.lowerorder;

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

### FILE: src/main/java/caspien/lowerorder/RangeCheckFusionPass.java
```java
package caspien.lowerorder;

import java.util.ArrayList;
import java.util.List;

/**
 * Constant-bounds `match i in arr` / `match i into arr` check (always on, runs on the STACK-form text right after
 * StrengthReductionPass and before RegisterFormPass). MembershipLoweringPass builds the per-iteration bounds test of a
 * range with literal bounds by materialising the range on the stack and copying it into temps:
 *
 *   PUSH 8 $i ; PUSH 8 K0 ; PUSH 8 K1 ; POP $r 16 ; POP $l 8 ;
 *   PUSH 8 $l ; PUSH 8 $r ; GT_EQ_INT 8 ; PUSH 8 $l ; PUSH 8 $r+8 ; LT_INT 8 ; AND 1 ; CMP ; JMP @L
 *
 * ($r holds K0, $r+8 holds K1; $l holds a copy of $i). That is five stack operations and two frame stores per check, and
 * the `PUSH 8 $i` keeps the index out of a variable register. With the three temp slots mentioned nowhere else in the
 * function the whole thing is the same as
 *
 *   PUSH 8 $i ; PUSH 8 K0 ; GT_EQ_INT 8 ; PUSH 8 $i ; PUSH 8 K1 ; LT_INT 8 ; AND 1 ; CMP ; JMP @L
 *
 * and, when K0 is 0 (an unsigned index is always >= 0), just
 *
 *   PUSH 8 $i ; PUSH 8 K1 ; LT_INT 8 ; CMP ; JMP @L
 *
 * RegisterFormPass then fuses what is left. Only this exact 14-line shape is rewritten, only with decimal literal bounds in
 * 0..2^31-1, only for the unsigned compares (GT_EQ_INT / LT_INT), and only when no other line of the same function mentions
 * $l, $r or $r+8 (so the dropped stores cannot be observed).
 */
public class RangeCheckFusionPass {

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        int n = lines.size();
        int funcStart = 0;
        int funcEnd = n - 1;
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = lines.get(i);
            if (isMn(l, "FUNC_START")) {
                funcStart = i;
                funcEnd = i;
                for (int j = i + 1; j < n; j++) {
                    if (isMn(lines.get(j), "FUNC_END")) { funcEnd = j; break; }
                }
                if (funcEnd == i) { funcEnd = n - 1; }
            }
            List<List<BytecodeToken>> rep = i + 13 < n ? match(lines, i, funcStart, funcEnd) : null;
            if (rep != null) {
                out.addAll(rep);
                i += 13;
            } else {
                out.add(l);
            }
        }
        return out;
    }

    private static List<List<BytecodeToken>> match(List<List<BytecodeToken>> ls, int i, int fs, int fe) {
        List<BytecodeToken> a0 = ls.get(i), a1 = ls.get(i + 1), a2 = ls.get(i + 2), a3 = ls.get(i + 3), a4 = ls.get(i + 4);
        if (!(isPush8(a0) && isSlot(t(a0, 2)))) { return null; }
        Long k0 = lit(a1), k1 = lit(a2);
        if (k0 == null || k1 == null) { return null; }
        if (!(a3.size() == 3 && t(a3, 0).equals("POP") && t(a3, 2).equals("16") && isSlot(t(a3, 1)))) { return null; }
        if (!(a4.size() == 3 && t(a4, 0).equals("POP") && t(a4, 2).equals("8") && isSlot(t(a4, 1)))) { return null; }
        String r = t(a3, 1), lt = t(a4, 1);
        Long ro = off(r), lo = off(lt);
        if (ro == null || lo == null) { return null; }
        String r8 = "$" + (ro + 8);
        List<BytecodeToken> b0 = ls.get(i + 5), b1 = ls.get(i + 6), b2 = ls.get(i + 7), b3 = ls.get(i + 8), b4 = ls.get(i + 9),
                b5 = ls.get(i + 10), b6 = ls.get(i + 11), b7 = ls.get(i + 12), b8 = ls.get(i + 13);
        if (!(isPush8(b0) && t(b0, 2).equals(lt) && isPush8(b1) && t(b1, 2).equals(r) && isOp(b2, "GT_EQ_INT", "8"))) { return null; }
        if (!(isPush8(b3) && t(b3, 2).equals(lt) && isPush8(b4) && t(b4, 2).equals(r8) && isOp(b5, "LT_INT", "8"))) { return null; }
        if (!(isOp(b6, "AND", "1") && b7.size() == 1 && t(b7, 0).equals("CMP") && b8.size() == 2 && t(b8, 0).equals("JMP"))) { return null; }
        // the three temp slots must be mentioned nowhere else in this function
        for (int j = fs; j <= fe && j < ls.size(); j++) {
            if (j >= i && j <= i + 13) { continue; }
            for (BytecodeToken tk : ls.get(j)) {
                String s = tk.text;
                if (s.equals(r) || s.equals(lt) || s.equals(r8)) { return null; }
            }
        }
        String idx = t(a0, 2);
        List<List<BytecodeToken>> rep = new ArrayList<>();
        boolean dropLower = k0 == 0;
        if (!dropLower) {
            rep.add(a0);
            rep.add(a1);                       // PUSH 8 K0
            rep.add(b2);                       // GT_EQ_INT 8
            rep.add(copy(a0, idx));
            rep.add(a2);                       // PUSH 8 K1
            rep.add(b5);                       // LT_INT 8
            rep.add(b6);                       // AND 1
        } else {
            rep.add(a0);
            rep.add(a2);                       // PUSH 8 K1
            rep.add(b5);                       // LT_INT 8
        }
        rep.add(b7);
        rep.add(b8);
        return rep;
    }

    private static List<BytecodeToken> copy(List<BytecodeToken> l, String unused) {
        return new ArrayList<>(l);
    }

    private static boolean isMn(List<BytecodeToken> l, String m) { return !l.isEmpty() && t(l, 0).equals(m); }
    private static String t(List<BytecodeToken> l, int i) { return l.get(i).text; }
    private static boolean isPush8(List<BytecodeToken> l) { return l.size() == 3 && t(l, 0).equals("PUSH") && t(l, 1).equals("8"); }
    private static boolean isOp(List<BytecodeToken> l, String m, String sz) { return l.size() == 2 && t(l, 0).equals(m) && t(l, 1).equals(sz); }
    private static boolean isSlot(String s) { return off(s) != null; }

    private static Long off(String s) {
        if (s.length() < 2 || s.charAt(0) != '$') { return null; }
        try { return Long.parseLong(s.substring(1)); } catch (NumberFormatException e) { return null; }
    }

    /** `PUSH 8 <decimal literal in 0..2^31-1>` -> the literal, else null. */
    private static Long lit(List<BytecodeToken> l) {
        if (!isPush8(l)) { return null; }
        String s = t(l, 2);
        for (int i = 0; i < s.length(); i++) { if (!Character.isDigit(s.charAt(i))) { return null; } }
        if (s.isEmpty() || s.length() > 10) { return null; }
        long v = Long.parseLong(s);
        return v <= Integer.MAX_VALUE ? v : null;
    }
}
```

### FILE: src/main/java/caspien/lowerorder/RangeEndHintPass.java
```java
package caspien.lowerorder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The hidden range of a `for` loop, split so its END can live in a variable register (runs on the STACK-form text, after
 * RangeCheckFusionPass and before RegisterFormPass; only when both "deferred-operands" and "variables-in-registers" are on).
 *
 * <p>A `for i in a..b` loop builds its range as one 16-byte value in a hidden frame slot and tests `i < end` on every iteration:
 *
 * <pre>
 *   ADDR 16 $r ; &lt;a&gt; ; &lt;b&gt; ; ASSIGN 16 16 16          ($r = start, $r+8 = end: the first pushed word is the lower one)
 *   ...  PUSH 8 $i ; PUSH 8 $r+8 ; LT_INT 8 ; CMP ; JMP @for_end
 * </pre>
 *
 * Nothing names `$r+8` as a variable, so the register promotion never saw it and every iteration read the end from memory.
 * This pass rewrites the construction into two 8-byte stores (the memory layout is unchanged, so every other reader still
 * sees the same bytes):
 *
 * <pre>
 *   ADDR 8 $r ; &lt;a&gt; ; ASSIGN 8 8 8 ; ADDR 8 $r+8 ; &lt;b&gt; ; ASSIGN 8 8 8
 * </pre>
 *
 * and adds a "REGHINT $r+8 8 weight" line (right after the function's ALLOC, where the Optimizer's hints are), so
 * RegVarPromotionPass treats the end like any other hot scalar. The promotion's own all-or-nothing rule applies: if the
 * slot stays in memory the program is the same as before, only with two 8-byte stores instead of one 16-byte one.
 *
 * <p>Conditions (anything else is left alone): the construction is exactly ADDR 16 $r, two complete 8-byte value
 * expressions built only from `PUSH 8 x` and size-8 `ADD_INT`/`SUB_INT`/`MUL_INT`/`SHR`/`SHL`/`SAR`/`BITS_AND`/`BITS_OR`/`BITS_XOR` (so their stack effect is
 * known and neither can read the half-written range), then ASSIGN 16 16 16; every OTHER mention of `$r` in the function is
 * `PUSH 8 $r` or another construction of this same shape; every other mention of `$r+8` is `PUSH 8 $r+8`; no token names an
 * offset strictly between $r and $r+16 except those two. The weight of the hint is the sum over the mentions of `$r+8` of
 * 8^min(loopDepth, 4), a backward jump to an earlier label marking a loop (the same rule as the Optimizer's hints); a weight
 * below 8 gets no hint.
 */
public class RangeEndHintPass {

    private static final Set<String> BIN = new HashSet<>(java.util.Arrays.asList("ADD_INT", "SUB_INT", "MUL_INT", "SHR", "SHL", "SAR", "BITS_AND", "BITS_OR", "BITS_XOR"));

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        int i = 0;
        int n = lines.size();
        while (i < n) {
            if (isMn(lines.get(i), "FUNC_START")) {
                int end = i;
                while (end < n && !isMn(lines.get(end), "FUNC_END")) {
                    end++;
                }
                int stop = Math.min(end + 1, n);
                out.addAll(function(lines.subList(i, stop)));
                i = stop;
            } else {
                out.add(lines.get(i));
                i++;
            }
        }
        return out;
    }

    /** one candidate construction: the index of its ADDR line, the split point of its two expressions, the ASSIGN index */
    private static final class Site {
        int addr;
        int split;   // first line of the second expression
        int assign;
        long off;
    }

    private List<List<BytecodeToken>> function(List<List<BytecodeToken>> fn) {
        int n = fn.size();
        List<Site> sites = new ArrayList<>();
        for (int i = 0; i + 3 < n; i++) {
            Site s = match(fn, i);
            if (s != null) {
                sites.add(s);
            }
        }
        if (sites.isEmpty()) {
            return fn;
        }
        // loop depth per line: a backward jump to an earlier label defines a span
        Map<String, Integer> labelAt = new HashMap<>();
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = fn.get(i);
            if (l.size() == 1 && l.get(0).text.startsWith("@") && l.get(0).text.endsWith(":")) {
                String t = l.get(0).text;
                labelAt.put(t.substring(0, t.length() - 1), i);
            }
        }
        int[] depth = new int[n + 1];
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = fn.get(i);
            if (l.size() == 2 && l.get(0).text.equals("JMP")) {
                Integer a = labelAt.get(l.get(1).text);
                if (a != null && a < i) {
                    for (int k = a; k <= i; k++) {
                        depth[k]++;
                    }
                }
            }
        }
        // group the sites by slot; a slot is usable only if every mention outside its constructions is a plain 8-byte read
        Map<Long, List<Site>> bySlot = new HashMap<>();
        for (Site s : sites) {
            bySlot.computeIfAbsent(s.off, k -> new ArrayList<>()).add(s);
        }
        Set<Integer> splitAddr = new HashSet<>();
        List<long[]> hints = new ArrayList<>(); // {offset of the end slot, weight}
        List<Site> accepted = new ArrayList<>();
        for (Map.Entry<Long, List<Site>> e : bySlot.entrySet()) {
            long ro = e.getKey();
            long weight = usable(fn, ro, e.getValue(), depth);
            if (weight < 0) {
                continue;
            }
            accepted.addAll(e.getValue());
            if (weight >= 8) {
                hints.add(new long[] {ro + 8, weight});
            }
        }
        if (accepted.isEmpty()) {
            return fn;
        }
        Map<Integer, Site> byAddr = new HashMap<>();
        for (Site s : accepted) {
            byAddr.put(s.addr, s);
        }
        List<List<BytecodeToken>> res = new ArrayList<>(n + 4 + hints.size());
        boolean hinted = false;
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = fn.get(i);
            Site s = byAddr.get(i);
            if (s != null) {
                // ADDR 8 $r ; <a> ; ASSIGN 8 8 8 ; ADDR 8 $r+8 ; <b> ; ASSIGN 8 8 8
                res.add(mk(l.get(0), "ADDR", "8", "$" + s.off));
                for (int k = s.addr + 1; k < s.split; k++) {
                    res.add(fn.get(k));
                }
                res.add(mk(l.get(0), "ASSIGN", "8", "8", "8"));
                res.add(mk(l.get(0), "ADDR", "8", "$" + (s.off + 8)));
                for (int k = s.split; k < s.assign; k++) {
                    res.add(fn.get(k));
                }
                res.add(mk(l.get(0), "ASSIGN", "8", "8", "8"));
                i = s.assign;
                continue;
            }
            res.add(l);
            if (!hinted && isMn(l, "ALLOC")) {
                hinted = true;
                for (long[] h : hints) {
                    res.add(mk(l.get(0), "REGHINT", String.valueOf(h[0]), "8", String.valueOf(h[1])));
                }
            }
        }
        if (!hinted && !hints.isEmpty()) {
            return fn; // no ALLOC line to hang the hint on: leave the function exactly as it was
        }
        return res;
    }

    /**
     * -1 when the slot cannot be treated this way, else the weight of its end slot. Every other mention of $ro must be PUSH 8 $ro,
     * every mention of $ro+8 PUSH 8 $ro+8 (or the construction itself), and no token may name an offset strictly inside the 16 bytes.
     */
    private static long usable(List<List<BytecodeToken>> fn, long ro, List<Site> sites, int[] depth) {
        String rs = "$" + ro;
        String es = "$" + (ro + 8);
        Set<Integer> own = new HashSet<>();
        Set<Integer> addrLines = new HashSet<>();
        for (Site s : sites) {
            addrLines.add(s.addr);
            for (int k = s.addr; k <= s.assign; k++) {
                own.add(k);
            }
        }
        long weight = 0;
        for (Site s : sites) {
            weight += w(depth[s.addr]); // the store of the end
        }
        for (int i = 0; i < fn.size(); i++) {
            List<BytecodeToken> l = fn.get(i);
            if (own.contains(i)) {
                // inside a construction: the two value expressions may not name the range at all (the ADDR line itself may)
                if (!addrLines.contains(i)) {
                    for (int k = 1; k < l.size(); k++) {
                        Long o = slotOff(l.get(k).text);
                        if (o != null && o > ro - 8 && o < ro + 16) {
                            return -1;
                        }
                    }
                }
                continue;
            }
            for (int k = 1; k < l.size(); k++) {
                String tk = l.get(k).text;
                Long o = slotOff(tk);
                if (o == null) {
                    continue;
                }
                if (o == ro) {
                    if (!(isPush8(l) && k == 2)) {
                        return -1;
                    }
                } else if (o == ro + 8) {
                    if (!(isPush8(l) && k == 2)) {
                        return -1;
                    }
                    weight += w(depth[i]);
                } else if (o > ro && o < ro + 16) {
                    return -1;
                } else if (o > ro - 16 && o < ro) {
                    // a block that starts below $r and reaches into it cannot be told from here: only an exact slot name is allowed
                    // to sit closer than 16 bytes below, and that is a different variable, so nothing to do
                    continue;
                }
            }
            // a wide access that STARTS below $r can still overlap it ("PUSH 24 $r-8"): such a line names a slot below ro with size > distance
            if (l.size() >= 3 && (isMn(l, "PUSH") || isMn(l, "ADDR"))) {
                Long o = slotOff(l.get(2).text);
                Long sz = num(l.get(1).text);
                if (o != null && sz != null && o < ro && o + sz > ro) {
                    return -1;
                }
            }
        }
        return weight;
    }

    private static long w(int depth) {
        long v = 1;
        for (int k = 0; k < Math.min(depth, 4); k++) {
            v *= 8;
        }
        return v;
    }

    /** ADDR 16 $r ; expression ; expression ; ASSIGN 16 16 16 starting at i, or null */
    private static Site match(List<List<BytecodeToken>> fn, int i) {
        List<BytecodeToken> a = fn.get(i);
        if (!(a.size() == 3 && isMn(a, "ADDR") && t(a, 1).equals("16"))) {
            return null;
        }
        Long ro = slotOff(t(a, 2));
        if (ro == null) {
            return null;
        }
        int depth = 0;
        int split = -1;
        for (int j = i + 1; j < fn.size() && j <= i + 24; j++) {
            List<BytecodeToken> l = fn.get(j);
            if (isMn(l, "ASSIGN")) {
                if (depth == 2 && split > 0 && l.size() == 4 && t(l, 1).equals("16") && t(l, 2).equals("16") && t(l, 3).equals("16")) {
                    Site s = new Site();
                    s.addr = i;
                    s.split = split;
                    s.assign = j;
                    s.off = ro;
                    return s;
                }
                return null;
            }
            if (isPush8(l)) {
                depth++;
            } else if (l.size() == 2 && BIN.contains(t(l, 0)) && t(l, 1).equals("8")) {
                depth--;
                if (depth < 1) {
                    return null;
                }
            } else {
                return null;
            }
            if (depth == 1) {
                split = j + 1; // the last point where one value is pending: the second expression starts after it
            }
            if (depth > 2 && split < 0) {
                return null;
            }
        }
        return null;
    }

    private static boolean isMn(List<BytecodeToken> l, String m) {
        return !l.isEmpty() && l.get(0).text.equals(m);
    }

    private static String t(List<BytecodeToken> l, int i) {
        return l.get(i).text;
    }

    private static boolean isPush8(List<BytecodeToken> l) {
        return l.size() == 3 && t(l, 0).equals("PUSH") && t(l, 1).equals("8");
    }

    private static Long num(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long slotOff(String s) {
        if (s.length() < 2 || s.charAt(0) != '$') {
            return null;
        }
        return num(s.substring(1));
    }

    private static List<BytecodeToken> mk(BytecodeToken ref, String... texts) {
        List<BytecodeToken> r = new ArrayList<>(texts.length);
        for (String x : texts) {
            r.add(new BytecodeToken(x, ref.file, ref.line, BytecodeToken.Kind.CODE));
        }
        return r;
    }
}
```

### FILE: src/main/java/caspien/lowerorder/RangeWordSplitPass.java
```java
package caspien.lowerorder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 16-byte range values built or copied word by word instead of through the stack (runs on the STACK-form text, after
 * RangeEndHintPass and before RegisterFormPass; only when "deferred-operands" is on).
 *
 * <p>A `range` is two 8-byte words (start at the lower address). The lowering of `@recursive` functions, `for` loops and
 * ordinary range code builds and copies them with 16-byte stack operations that RegisterFormPass cannot fuse:
 *
 * <pre>
 *   ADDR 16 $X ; PUSH 8 a ; PUSH 8 b ; ASSIGN 16 16 16      (construction)
 *   ADDR 16 $X ; PUSH 16 $Y ; ASSIGN 16 16 16                (copy)
 * </pre>
 *
 * Each becomes two independent 8-byte assignments, which the register-form pass turns into plain moves:
 *
 * <pre>
 *   ADDR 8 $X ; PUSH 8 a ; ASSIGN 8 8 8 ; ADDR 8 $X+8 ; PUSH 8 b ; ASSIGN 8 8 8
 * </pre>
 *
 * Conditions (anything else is left byte for byte): `a` and `b` are `$slot`, an integer literal or `ARGn`; a slot operand
 * is read AFTER the first word of the destination was written (b) so its 8 bytes must not overlap $X..$X+16; a copy needs
 * $Y == $X (left alone, it is a no-op) or $Y..$Y+16 disjoint from $X..$X+16. The bytes written are exactly the same.
 */
public class RangeWordSplitPass {

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        int i = 0;
        int n = lines.size();
        while (i < n) {
            if (!lines.get(i).isEmpty() && lines.get(i).get(0).text.equals("FUNC_START")) {
                int end = i;
                while (end < n && !(lines.get(end).size() > 0 && lines.get(end).get(0).text.equals("FUNC_END"))) {
                    end++;
                }
                int stop = Math.min(end + 1, n);
                out.addAll(hint(split(lines.subList(i, stop), splitSlots)));
                i = stop;
            } else {
                out.add(lines.get(i));
                i++;
            }
        }
        return out;
    }

    private final Set<Long> splitSlots = new HashSet<>();

    private List<List<BytecodeToken>> split(List<List<BytecodeToken>> lines, Set<Long> slots) {
        slots.clear();
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        int n = lines.size();
        int i = 0;
        while (i < n) {
            if (i + 3 < n && addr16(lines.get(i)) != null) {
                long x = addr16(lines.get(i));
                List<BytecodeToken> l1 = lines.get(i + 1);
                List<BytecodeToken> l2 = lines.get(i + 2);
                List<BytecodeToken> l3 = lines.get(i + 3);
                if (isAssign16(l2) && push16(l1) != null) {
                    long y = push16(l1);
                    if (y == x) {
                        i += 3;   // copying a range onto itself: nothing to do
                        continue;
                    }
                    if (y + 16 <= x || x + 16 <= y) {
                        BytecodeToken ref = lines.get(i).get(0);
                        slots.add(x);
                        out.add(mk(ref, "ADDR", "8", "$" + x));
                        out.add(mk(ref, "PUSH", "8", "$" + y));
                        out.add(mk(ref, "ASSIGN", "8", "8", "8"));
                        out.add(mk(ref, "ADDR", "8", "$" + (x + 8)));
                        out.add(mk(ref, "PUSH", "8", "$" + (y + 8)));
                        out.add(mk(ref, "ASSIGN", "8", "8", "8"));
                        i += 3;
                        continue;
                    }
                } else if (isAssign16(l3) && operand8(l1, x, false) && operand8(l2, x, true)) {
                    BytecodeToken ref = lines.get(i).get(0);
                    slots.add(x);
                    out.add(mk(ref, "ADDR", "8", "$" + x));
                    out.add(l1);
                    out.add(mk(ref, "ASSIGN", "8", "8", "8"));
                    out.add(mk(ref, "ADDR", "8", "$" + (x + 8)));
                    out.add(l2);
                    out.add(mk(ref, "ASSIGN", "8", "8", "8"));
                    i += 4;
                    continue;
                }
            }
            out.add(lines.get(i));
            i++;
        }
        return out;
    }

    /** REGHINT lines for the two words of every split range whose every mention is a plain 8-byte one (the promotion re-checks all of it). */
    private List<List<BytecodeToken>> hint(List<List<BytecodeToken>> fn) {
        if (splitSlots.isEmpty()) {
            return fn;
        }
        int n = fn.size();
        Map<String, Integer> labelAt = new HashMap<>();
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = fn.get(i);
            if (l.size() == 1 && l.get(0).text.startsWith("@") && l.get(0).text.endsWith(":")) {
                String tx = l.get(0).text;
                labelAt.put(tx.substring(0, tx.length() - 1), i);
            }
        }
        int[] depth = new int[n + 1];
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = fn.get(i);
            if (l.size() == 2 && l.get(0).text.equals("JMP")) {
                Integer a = labelAt.get(l.get(1).text);
                if (a != null && a < i) {
                    for (int k = a; k <= i; k++) {
                        depth[k]++;
                    }
                }
            }
        }
        Set<Long> hinted = new HashSet<>();
        for (List<BytecodeToken> l : fn) {
            if (l.size() == 4 && l.get(0).text.equals("REGHINT") && num(l.get(1).text) != null) {
                hinted.add(num(l.get(1).text));
            }
        }
        List<long[]> hints = new ArrayList<>();
        for (long x : splitSlots) {
            long[] w = usable(fn, x, depth);
            if (w == null) {
                continue;
            }
            if (w[0] >= 8 && !hinted.contains(x)) {
                hints.add(new long[] {x, w[0]});
            }
            if (w[1] >= 8 && !hinted.contains(x + 8)) {
                hints.add(new long[] {x + 8, w[1]});
            }
        }
        if (hints.isEmpty()) {
            return fn;
        }
        List<List<BytecodeToken>> res = new ArrayList<>(n + hints.size());
        boolean done = false;
        for (List<BytecodeToken> l : fn) {
            res.add(l);
            if (!done && !l.isEmpty() && l.get(0).text.equals("ALLOC")) {
                done = true;
                for (long[] h : hints) {
                    res.add(mk(l.get(0), "REGHINT", String.valueOf(h[0]), "8", String.valueOf(h[1])));
                }
            }
        }
        return done ? res : fn;
    }

    /** weights {start word, end word}, or null when some mention is not a plain 8-byte access or a wide access overlaps. */
    private static long[] usable(List<List<BytecodeToken>> fn, long x, int[] depth) {
        long[] w = new long[2];
        for (int i = 0; i < fn.size(); i++) {
            List<BytecodeToken> l = fn.get(i);
            boolean plain8 = l.size() >= 3 && (l.get(0).text.equals("PUSH") || l.get(0).text.equals("ADDR")) && l.get(1).text.equals("8");
            for (int k = 1; k < l.size(); k++) {
                Long o = slotOff(l.get(k).text);
                if (o == null) {
                    continue;
                }
                if (o == x || o == x + 8) {
                    if (!(plain8 && k == 2)) {
                        return null;
                    }
                    w[o == x ? 0 : 1] += wt(depth[i]);
                } else if (o > x && o < x + 16) {
                    return null;
                }
            }
            if (l.size() >= 3 && (l.get(0).text.equals("PUSH") || l.get(0).text.equals("ADDR"))) {
                Long o = slotOff(l.get(2).text);
                Long sz = num(l.get(1).text);
                if (o != null && sz != null && o < x + 16 && o + sz > x && !(o == x || o == x + 8) && !(o + sz <= x)) {
                    if (o < x || sz > 8) {
                        return null;
                    }
                }
            }
        }
        return w;
    }

    private static long wt(int depth) {
        long v = 1;
        for (int k = 0; k < Math.min(depth, 4); k++) {
            v *= 8;
        }
        return v;
    }

    private static Long addr16(List<BytecodeToken> l) {
        if (l.size() == 3 && t(l, 0).equals("ADDR") && t(l, 1).equals("16")) {
            return slotOff(t(l, 2));
        }
        return null;
    }

    private static Long push16(List<BytecodeToken> l) {
        if (l.size() == 3 && t(l, 0).equals("PUSH") && t(l, 1).equals("16")) {
            return slotOff(t(l, 2));
        }
        return null;
    }

    private static boolean isAssign16(List<BytecodeToken> l) {
        return l.size() == 4 && t(l, 0).equals("ASSIGN") && t(l, 1).equals("16") && t(l, 2).equals("16") && t(l, 3).equals("16");
    }

    /** PUSH 8 of an integer literal, ARGn, or a slot; a slot read after the destination's first word was written must not overlap it. */
    private static boolean operand8(List<BytecodeToken> l, long x, boolean afterFirstWrite) {
        if (l.size() != 3 || !t(l, 0).equals("PUSH") || !t(l, 1).equals("8")) {
            return false;
        }
        String o = t(l, 2);
        if (num(o) != null) {
            return true;
        }
        if (o.matches("ARG[0-9]+")) {
            return true;
        }
        Long s = slotOff(o);
        if (s == null) {
            return false;
        }
        return !afterFirstWrite || s + 8 <= x || x + 16 <= s;
    }

    private static String t(List<BytecodeToken> l, int i) {
        return l.get(i).text;
    }

    private static Long num(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long slotOff(String s) {
        if (s.length() < 2 || s.charAt(0) != '$') {
            return null;
        }
        return num(s.substring(1));
    }

    private static List<BytecodeToken> mk(BytecodeToken ref, String... texts) {
        List<BytecodeToken> r = new ArrayList<>(texts.length);
        for (String x : texts) {
            r.add(new BytecodeToken(x, ref.file, ref.line, BytecodeToken.Kind.CODE));
        }
        return r;
    }
}
```

### FILE: src/main/java/caspien/lowerorder/RegVarPromotionPass.java
```java
package caspien.lowerorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Register-variable promotion -- runs after {@link RegisterFormPass}, on its output, and only when compiler.config says
 * "variables-in-registers: on" (which needs "deferred-operands: on").
 *
 * <p>Input hints: "REGHINT offset size weight", one per scalar local that the Optimizer's RegVarHintPass marked as hot
 * ("REGVAR name weight", turned into a frame offset and width by AddressLoweringPass). The weight is the number of
 * source mentions, each counted 8^loopDepth times. The hint says nothing about WHICH register: this pass chooses.
 *
 * <p>What it does, per function: it picks the (at most {@link #VAR_COUNT}) hinted slots with the highest weight that are
 * <b>safe to promote</b> and renames every operand naming that frame slot ("$off") to a variable register "%vN"
 * (r13, r14 in the backend). A slot is safe only if EVERY mention of it in the function is an operand of a register-form
 * instruction that this pass knows how to rewrite, at exactly the slot's width. Any other mention -- a plain PUSH/ADDR/
 * ASSIGN the register-form pass could not fuse, an R_PUSHA or R_LEA base (the slot's address), a size mismatch --
 * leaves that slot in memory. Because the decision is made on the FINAL text there is nothing to roll back: the rename
 * is a pure change of where the value lives, line for line. (The Optimizer already excluded every variable whose
 * address is taken.)
 *
 * <p>The whole function is skipped when it contains an instruction whose backend code uses r13/r14 as its own scratch
 * ({@link #R13_R14_USERS}), or inline assembly.
 *
 * <p>Rewrites (the variable register always holds the value zero-extended to 64 bits, like a temp):
 * <pre>
 *   R_LD n %tD $x        -> R_MOV 8 %tD %vK
 *   R_ST n $x src        -> R_MOV 8 %vK src            (n == 8)
 *                        -> R_SETV n %vK src           (n &lt; 8: truncate, zero-extend)
 *   any other operand    -> the same line with $x replaced by %vK
 * </pre>
 * The backend maps %vN to a callee-saved register; every function that touches one already saves/restores it
 * (X86Backend.finishCalleeSaved), so a value held across a call, a throw or a thread start is safe.
 *
 * <p>Always strips REGHINT lines (and any stray REGVAR), so Codegen never sees them and, with the switch off, the output
 * is byte-identical to a build without this feature.
 *
 * <p><b>Float variables</b> ("float-variables-in-registers: on"): a hint carrying the trailing "f" marker (an f32 local) is
 * kept in an xmm register "%xK" (at most {@link #XVAR_COUNT} per function, chosen by weight) instead of competing for r13/r14.
 * The same all-or-nothing rule applies (every mention of the slot must be renameable; see {@link #mentionOkX}). Rewrites:
 * <pre>
 *   R_LD n %tD $x          -> R_XTOG n %tD %xK            (movd / movq; n = 4 or 8)
 *   R_ST n $x src          -> R_GTOX n %xK src
 *   PUSH n $x              -> PUSH n %xK                  (the backend pushes the zero-extended bits, as before)
 *   R_FBIN/R_FCMP/R_ARG/R_FARG/R_RETF ... $x ...  -> the same line with %xK (the backend reads the register directly)
 *   ADDR 4 $x / CC_START .. CC_END / PUSH_RET_FLOAT 4 / ASSIGN 4 4 4  ->  R_GETRETF 4 %xK after the call   (x = f(...))
 *   R_LD n %t addr ; R_GTOX n %xK %t          -> R_LDX n %xK addr         (temp dead afterwards)
 *   R_FBIN OP n %t a b ; R_GTOX n %xK %t      -> R_FBINX OP n %xK a b     (temp dead afterwards)
 *   R_XTOG n %t %xK ; R_ST n addr %t          -> R_STX n addr %xK
 * </pre>
 * Each function gets "R_XVAR %xK $home" declarations (its frame slot, for the ABIs whose xmm registers do not survive a call:
 * the backend spills/reloads around every call) and an "R_XRELOAD" right after every "@catch_" label (control arrives there
 * from an unwind). Functions with inline assembly are never touched; a function that only uses r13/r14 as backend scratch
 * (NEW, CLONE, ...) can still have float variables, since no backend sequence uses the variable xmm registers.
 */
public class RegVarPromotionPass {

    /** how many variable registers exist (%v0, %v1 -> r13, r14); a function that never needs a fourth temporary also gets %v2 -> r12 */
    public static final int VAR_COUNT = 2;

    /** the most variable registers any function can get (%v2 shares r12 with the fourth temporary %t3) */
    public static final int VAR_COUNT_MAX = 3;

    /** how many float variable registers exist (%x0..%x5 -> xmm8-13 on SysV, xmm6-11 on win64) */
    public static final int XVAR_COUNT = 6;

    /**
     * Variable registers 3..5 are r8, r9 and r10: caller-saved (r8/r9 are argument registers, r10 the INVOKE target and float-result
     * stash), so a variable may sit there only if it is never live at or across a line that could clobber them. Only the lines in
     * {@link #VOL_SAFE} are known not to (checked against the backend: none of them touches r8, r9 or r10). Used only when more
     * variables want registers than the callee-saved ones provide.
     */
    public static final int VOL_FIRST = 3;
    public static final int VOL_COUNT = 3;

    static final Set<String> VOL_SAFE = new HashSet<>(Arrays.asList(
            "R_MOV", "R_LD", "R_ST", "R_BIN", "R_UN", "R_BRC", "R_BRF", "R_LEA", "R_RMW", "R_SETV", "R_LDX", "R_STX", "R_FBIN",
            "R_FBINX", "R_FCMP", "R_XTOG", "R_GTOX", "R_XMOV", "R_PUSH", "R_PUSHA", "R_XVAR", "R_RET", "R_RETF", "JMP", "CMP",
            "ALLOC", "FUNC_START", "FUNC_END"));

    /** Mnemonics whose backend code uses r13/r14 internally (CLONE, DOT of wide fields, LOOKUP_ARRAY, NEW*, RESIZE*) or that run arbitrary code. */
    static final Set<String> R13_R14_USERS = new HashSet<>(Arrays.asList(
            "NEW", "NEW_DYN", "NEW_UDYN", "NEW_FROM_STRING", "CLONE", "RESIZE", "URESIZE",
            "DOT", "LOOKUP_ARRAY", "ASM_START", "ASM_END"));

    private final boolean enabled;
    private final boolean floatEnabled;

    public RegVarPromotionPass(boolean enabled) {
        this(enabled, false);
    }

    public RegVarPromotionPass(boolean enabled, boolean floatEnabled) {
        this.enabled = enabled;
        this.floatEnabled = enabled && floatEnabled;
    }

    static final class Hint {
        long off;
        int size;
        long weight;
        boolean banned;
        int reg = -1;
        /** the hint carried the float marker */
        boolean isFloat;
        /** promoted to an xmm register (float hint and the float switch is on) rather than r13/r14 */
        boolean xmm;
        /** may live in a caller-saved register (r8/r9/r10): never live across or at a line that could clobber them */
        boolean volOk;
    }

    public List<List<BytecodeToken>> run(List<List<BytecodeToken>> lines) {
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        int i = 0;
        while (i < lines.size()) {
            List<BytecodeToken> line = lines.get(i);
            if (!line.isEmpty() && line.get(0).text.equals("FUNC_START")) {
                int end = i;
                while (end < lines.size() && !(!lines.get(end).isEmpty() && lines.get(end).get(0).text.equals("FUNC_END"))) {
                    end++;
                }
                int stop = Math.min(end + 1, lines.size());
                processFunction(lines.subList(i, stop), out);
                i = stop;
                continue;
            }
            if (isHint(line)) {
                i++;
                continue;
            }
            out.add(line);
            i++;
        }
        return out;
    }

    /** true when the function text mentions the fourth temporary (%t3 = r12) anywhere */
    private static boolean usesTemp3(List<List<BytecodeToken>> fn) {
        for (List<BytecodeToken> l : fn) {
            for (BytecodeToken t : l) {
                if (t.text.equals("%t3")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isHint(List<BytecodeToken> line) {
        if (line.isEmpty()) {
            return false;
        }
        String m = line.get(0).text;
        return m.equals("REGHINT") || m.equals("REGVAR");
    }

    private void processFunction(List<List<BytecodeToken>> fn, List<List<BytecodeToken>> out) {
        Map<Long, Hint> hints = new HashMap<>();
        boolean blocked = false;      // an instruction that uses r13/r14 as backend scratch: no integer variables here
        boolean hardBlocked = false;  // inline assembly: touch nothing
        for (List<BytecodeToken> l : fn) {
            if (l.isEmpty()) {
                continue;
            }
            String m = l.get(0).text;
            if (m.equals("REGHINT") && (l.size() == 4 || l.size() == 5)) {
                try {
                    Hint h = new Hint();
                    h.off = Long.parseLong(l.get(1).text);
                    h.size = Integer.parseInt(l.get(2).text);
                    h.weight = Long.parseLong(l.get(3).text);
                    h.isFloat = l.size() == 5 && l.get(4).text.equals("f");
                    h.xmm = floatEnabled && h.isFloat && (h.size == 4 || h.size == 8);
                    if (h.size == 1 || h.size == 2 || h.size == 4 || h.size == 8) {
                        hints.putIfAbsent(h.off, h);
                    }
                } catch (NumberFormatException e) {
                    // ignore a malformed hint
                }
            } else if (R13_R14_USERS.contains(m)) {
                blocked = true;
                if (m.equals("ASM_START") || m.equals("ASM_END")) {
                    hardBlocked = true;
                }
            }
        }
        if (!enabled || hardBlocked || hints.isEmpty()) {
            for (List<BytecodeToken> l : fn) {
                if (!isHint(l)) {
                    out.add(l);
                }
            }
            return;
        }
        // r13/r14 are the backend's scratch in a function that has such an instruction, so no callee-saved variable register is used
        // there (intLimit 0 below). The caller-saved r8/r9/r10 stay available: a variable gets one only if no instruction outside
        // VOL_SAFE (which includes every one of those scratch users) has it live, so it is never live across one.

        // A float variable assigned by the stack form: ADDR 4 $x / <value computation> / ASSIGN 4 4 4. The value computation may hold
        // calls (x = x + f(y)); only lines that are known to leave the pushed address alone are allowed inside, and nested
        // ADDR/ASSIGN pairs (the call-site staging stores) are matched by counting. The pair becomes "value; R_POPX %xK";
        // when the computation is exactly one call bracket plus PUSH_RET_FLOAT it becomes "call; R_GETRETF %xK".
        List<int[]> pairs = new ArrayList<>(); // {addrIdx, assignIdx, direct(1)/general(0), retIdx}
        Map<Integer, Long> pairSlot = new HashMap<>();
        java.util.Set<Integer> approvedAddr = new HashSet<>();
        for (int i = 0; i + 1 < fn.size(); i++) {
            List<BytecodeToken> l = fn.get(i);
            if (!(l.size() == 3 && l.get(0).text.equals("ADDR") && (l.get(1).text.equals("4") || l.get(1).text.equals("8")) && l.get(2).text.startsWith("$"))) {
                continue;
            }
            Hint h = slotHint(hints, l.get(2).text);
            if (h == null || !h.xmm || !l.get(1).text.equals(String.valueOf(h.size))) {
                continue;
            }
            int c = 1;
            int j = i + 1;
            boolean ok = true;
            for (; j < fn.size(); j++) {
                List<BytecodeToken> jl = fn.get(j);
                if (jl.isEmpty()) {
                    continue;
                }
                String jm = jl.get(0).text;
                if (jm.equals("ADDR")) {
                    c++;
                } else if (jm.equals("ASSIGN") || jm.equals("ATOMIC_ASSIGN")) {
                    c--;
                    if (c == 0) {
                        break;
                    }
                } else if (jm.equals("POP")) {
                    if (!(jl.size() >= 2 && (jl.get(1).text.startsWith("ARG") || jl.get(1).text.startsWith("FARG")))) {
                        ok = false;
                        break;
                    }
                } else if (!(jm.startsWith("R_") || VALUE_OPS.contains(jm))) {
                    ok = false;
                    break;
                }
            }
            if (!ok || j >= fn.size() || c != 0) {
                continue;
            }
            List<BytecodeToken> asg = fn.get(j);
            String hw = String.valueOf(h.size);
            if (!(asg.size() == 4 && asg.get(0).text.equals("ASSIGN") && asg.get(1).text.equals(hw)
                    && asg.get(2).text.equals(hw) && asg.get(3).text.equals(hw))) {
                continue;
            }
            int direct = 0;
            int retIdx = -1;
            if (j - 1 > i + 1 && fn.get(i + 1).get(0).text.equals("CC_START") && fn.get(j - 2).get(0).text.equals("CC_END")) {
                List<BytecodeToken> ret = fn.get(j - 1);
                if (ret.size() == 2 && ret.get(0).text.equals("PUSH_RET_FLOAT") && ret.get(1).text.equals(hw)) {
                    // the bracket that opens right after the ADDR must be the one that closes right before PUSH_RET_FLOAT
                    int depth = 0;
                    boolean whole = true;
                    for (int q = i + 1; q <= j - 2; q++) {
                        String qm = fn.get(q).isEmpty() ? "" : fn.get(q).get(0).text;
                        if (qm.equals("CC_START")) {
                            depth++;
                        } else if (qm.equals("CC_END")) {
                            depth--;
                            if (depth == 0 && q != j - 2) {
                                whole = false;
                                break;
                            }
                        }
                    }
                    if (whole && depth == 0) {
                        direct = 1;
                        retIdx = j - 1;
                    }
                }
            }
            pairs.add(new int[] {i, j, direct, retIdx});
            pairSlot.put(i, h.off);
            approvedAddr.add(i);
        }

        // Pass 1: ban every hinted slot with a mention we cannot rewrite.
        for (int idx = 0; idx < fn.size(); idx++) {
            List<BytecodeToken> l = fn.get(idx);
            if (l.isEmpty() || isHint(l) || approvedAddr.contains(idx)) {
                continue;
            }
            String m = l.get(0).text;
            for (int k = 1; k < l.size(); k++) {
                Hint h = slotHint(hints, l.get(k).text);
                if (h != null && !(h.xmm ? mentionOkX(m, l, k, h) : mentionOk(m, l, k, h))) {
                    h.banned = true;
                }
            }
        }

        // Choose per register file: highest weight first (lower offset breaks ties, for a stable result).
        Map<Long, Hint> chosenInt = new HashMap<>();
        Map<Long, Hint> chosenX = new HashMap<>();
        List<Hint> intCands = new ArrayList<>();
        List<Hint> xCands = new ArrayList<>();
        for (Hint h : hints.values()) {
            if (!h.banned) {
                (h.xmm ? xCands : intCands).add(h);
            }
        }
        java.util.Comparator<Hint> byWeight = (a, b) -> a.weight != b.weight ? Long.compare(b.weight, a.weight) : Long.compare(a.off, b.off);
        intCands.sort(byWeight);
        xCands.sort(byWeight);
        int intLimit = blocked ? 0 : VAR_COUNT;
        if (!blocked && !usesTemp3(fn)) {
            intLimit = VAR_COUNT_MAX;
        }
        if (intCands.size() > intLimit || xCands.size() > XVAR_COUNT) {
            // More candidates than registers (typical after inlining: dozens of hot variables from different inlined bodies in one
            // function). Variables whose live ranges never overlap can share a register, so colour by interference, heaviest first.
            shareRegisters(fn, hints, pairs, intCands, xCands, intLimit);
            for (Hint h : intCands) if (h.reg >= 0) chosenInt.put(h.off, h);
            for (Hint h : xCands) if (h.reg >= 0) chosenX.put(h.off, h);
        } else {
            for (int k = 0; k < intCands.size() && k < intLimit; k++) {
                intCands.get(k).reg = k;
                chosenInt.put(intCands.get(k).off, intCands.get(k));
            }
            for (int k = 0; k < xCands.size() && k < XVAR_COUNT; k++) {
                xCands.get(k).reg = k;
                chosenX.put(xCands.get(k).off, xCands.get(k));
            }
        }
        if (chosenInt.isEmpty() && chosenX.isEmpty()) {
            for (List<BytecodeToken> l : fn) {
                if (!isHint(l)) {
                    out.add(l);
                }
            }
            return;
        }

        // Pass 2: rewrite.
        java.util.Set<Integer> dropped = new HashSet<>();
        Map<Integer, Hint> retReplace = new HashMap<>();   // PUSH_RET_FLOAT line -> R_GETRETF
        Map<Integer, Hint> assignReplace = new HashMap<>(); // ASSIGN line -> R_POPX
        for (int[] pr : pairs) {
            Hint h = chosenX.get(pairSlot.get(pr[0]));
            if (h == null) {
                continue;
            }
            dropped.add(pr[0]);
            if (pr[2] == 1) {
                dropped.add(pr[1]);
                retReplace.put(pr[3], h);
            } else {
                assignReplace.put(pr[1], h);
            }
        }
        List<List<BytecodeToken>> res = new ArrayList<>();
        for (int idx = 0; idx < fn.size(); idx++) {
            List<BytecodeToken> l = fn.get(idx);
            if (isHint(l) || dropped.contains(idx)) {
                continue;
            }
            BytecodeToken ref = l.isEmpty() ? null : l.get(0);
            if (retReplace.containsKey(idx)) {
                res.add(mk(ref, "R_GETRETF", String.valueOf(retReplace.get(idx).size), "%x" + retReplace.get(idx).reg));
                continue;
            }
            if (assignReplace.containsKey(idx)) {
                res.add(mk(ref, "R_POPX", String.valueOf(assignReplace.get(idx).size), "%x" + assignReplace.get(idx).reg));
                continue;
            }
            res.add(rewrite(l, chosenInt, chosenX));
            if (idx == 0 && !chosenX.isEmpty()) {
                // one declaration per register: its home slot (where the backend spills it around calls) is the slot of the heaviest
                // variable that lives in it; the slot is free for that use because every mention of the variable was renamed
                Map<Integer, Hint> homeOf = new java.util.TreeMap<>();
                for (Hint h : chosenX.values()) {
                    Hint cur = homeOf.get(h.reg);
                    if (cur == null || h.weight > cur.weight || (h.weight == cur.weight && h.off < cur.off)) homeOf.put(h.reg, h);
                }
                for (Hint h : homeOf.values()) {
                    res.add(mk(ref, "R_XVAR", "%x" + h.reg, "$" + h.off, String.valueOf(h.size)));
                }
            }
            if (!chosenX.isEmpty() && l.size() == 1 && l.get(0).text.startsWith("@catch_") && l.get(0).text.endsWith(":")) {
                res.add(mk(ref, "R_XRELOAD"));
            }
        }
        out.addAll(chosenX.isEmpty() ? res : fuse(res));
    }

    /** Mnemonics that may transfer control into this function's landing pads / catch labels (the callee unwinds into them). */
    private static final Set<String> MAY_UNWIND = new HashSet<>(Arrays.asList("CALL", "INVOKE", "EXTERN_CALL", "SLEEP"));

    /** Mnemonics after which control does not continue to the next line. */
    private static final Set<String> NO_FALLTHROUGH = new HashSet<>(Arrays.asList(
            "RET", "RET_FLOAT", "R_RET", "R_RETF", "GT_UNWIND", "EXIT", "EXIT_THREAD", "FUNC_END"));

    private static boolean isLabelDefLine(List<BytecodeToken> l) {
        return l.size() == 1 && l.get(0).text.startsWith("@") && l.get(0).text.endsWith(":");
    }

    /**
     * Register sharing. Two candidate variables may live in the same register only if no program point has both live, and neither is
     * written while the other is still live (a write to a variable that is never read still overwrites the register). Liveness is the
     * usual backward dataflow over the lines of the function (fallthrough, JMP, R_BRC/R_BRF, stack-form CMP+JMP, and an edge from every
     * call to every label whose address the function stages for unwinding). A line counts as a WRITE only when it is a plain store of
     * the whole slot (R_ST, R_MOV with the slot as destination, the assignment that ends a float "ADDR x ... ASSIGN" pair); every other
     * mention is a read, which can only make a range longer, never shorter. Candidates are coloured heaviest first; one that finds no
     * register free of its neighbours stays in memory. Floats of different width never share a register (the backend spills a register
     * with one width). Sets {@code Hint.reg} (-1 = not promoted).
     */
    private void shareRegisters(List<List<BytecodeToken>> fn, Map<Long, Hint> hints, List<int[]> pairs,
                                List<Hint> intCands, List<Hint> xCands, int intLimit) {
        List<Hint> all = new ArrayList<>(intCands.size() + xCands.size());
        all.addAll(intCands);
        all.addAll(xCands);
        int nh = all.size();
        Map<Long, Integer> idOf = new HashMap<>();
        for (int i = 0; i < nh; i++) {
            all.get(i).reg = -1;
            idOf.put(all.get(i).off, i);
        }
        int n = fn.size();
        // labels and branch targets
        Map<String, Integer> labels = new HashMap<>();
        for (int i = 0; i < n; i++) {
            if (isLabelDefLine(fn.get(i))) {
                String t = fn.get(i).get(0).text;
                labels.put(t.substring(0, t.length() - 1), i);
            }
        }
        List<Integer> padTargets = new ArrayList<>();
        java.util.Set<String> seenPad = new HashSet<>();
        for (List<BytecodeToken> l : fn) {
            if (l.isEmpty() || isLabelDefLine(l)) continue;
            String m = l.get(0).text;
            if (m.equals("JMP") || m.equals("R_BRC") || m.equals("R_BRF")) continue;
            for (int k = 1; k < l.size(); k++) {
                String t = l.get(k).text;
                if (t.startsWith("@") && labels.containsKey(t) && seenPad.add(t)) padTargets.add(labels.get(t));
            }
        }
        // successors
        int[][] succ = new int[n][];
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = fn.get(i);
            String m = l.isEmpty() ? "" : l.get(0).text;
            List<Integer> sc = new ArrayList<>(2);
            boolean fall = !NO_FALLTHROUGH.contains(m);
            if (m.equals("JMP") && l.size() == 2) {
                boolean cond = i > 0 && fn.get(i - 1).size() == 1 && fn.get(i - 1).get(0).text.equals("CMP");
                Integer tg = labels.get(l.get(1).text);
                if (tg != null) sc.add(tg);
                fall = cond;
            } else if ((m.equals("R_BRC") || m.equals("R_BRF")) && l.size() >= 3) {
                Integer tg = labels.get(l.get(l.size() - 1).text);
                if (tg != null) sc.add(tg);
            }
            if (fall && i + 1 < n) sc.add(i + 1);
            if (MAY_UNWIND.contains(m)) sc.addAll(padTargets);
            succ[i] = sc.stream().mapToInt(Integer::intValue).toArray();
        }
        // reads and writes per line
        java.util.BitSet[] use = new java.util.BitSet[n];
        java.util.BitSet[] def = new java.util.BitSet[n];
        for (int i = 0; i < n; i++) {
            use[i] = new java.util.BitSet(nh);
            def[i] = new java.util.BitSet(nh);
        }
        java.util.Set<Integer> approved = new HashSet<>();
        for (int[] pr : pairs) {
            approved.add(pr[0]);
            Integer id = idOf.get(pairSlotOf(fn, pr[0], hints));
            if (id == null) continue;
            def[pr[2] == 1 ? pr[3] : pr[1]].set(id);   // the store happens where the value lands: R_GETRETF / R_POPX
        }
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = fn.get(i);
            if (l.isEmpty() || isHint(l) || approved.contains(i)) continue;
            String m = l.get(0).text;
            for (int k = 1; k < l.size(); k++) {
                Hint h = slotHint(hints, l.get(k).text);
                if (h == null) continue;
                Integer id = idOf.get(h.off);
                if (id == null) continue;
                boolean store = (m.equals("R_ST") && k == 2 && l.size() == 4) || (m.equals("R_MOV") && k == 2 && l.size() == 4);
                if (store) def[i].set(id); else use[i].set(id);
            }
        }
        // backward liveness
        java.util.BitSet[] in = new java.util.BitSet[n];
        java.util.BitSet[] out = new java.util.BitSet[n];
        for (int i = 0; i < n; i++) {
            in[i] = new java.util.BitSet(nh);
            out[i] = new java.util.BitSet(nh);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = n - 1; i >= 0; i--) {
                java.util.BitSet o = new java.util.BitSet(nh);
                for (int s : succ[i]) o.or(in[s]);
                java.util.BitSet ni = (java.util.BitSet) o.clone();
                ni.andNot(def[i]);
                ni.or(use[i]);
                if (!o.equals(out[i])) { out[i] = o; changed = true; }
                if (!ni.equals(in[i])) { in[i] = ni; changed = true; }
            }
        }
        // interference
        java.util.BitSet[] adj = new java.util.BitSet[nh];
        for (int i = 0; i < nh; i++) adj[i] = new java.util.BitSet(nh);
        for (int i = 0; i < n; i++) {
            for (int d = def[i].nextSetBit(0); d >= 0; d = def[i].nextSetBit(d + 1)) {
                for (int v = out[i].nextSetBit(0); v >= 0; v = out[i].nextSetBit(v + 1)) {
                    if (v != d) { adj[d].set(v); adj[v].set(d); }
                }
            }
        }
        if (n > 0) {
            for (int a = in[0].nextSetBit(0); a >= 0; a = in[0].nextSetBit(a + 1)) {
                for (int b = in[0].nextSetBit(0); b >= 0; b = in[0].nextSetBit(b + 1)) {
                    if (a != b) adj[a].set(b);
                }
            }
        }
        // which candidates may use the caller-saved registers
        boolean[] volRegOk = {true, true, true};   // r8, r9, r10
        for (List<BytecodeToken> l : fn) {
            if (l.isEmpty()) continue;
            String m = l.get(0).text;
            if (m.equals("PUSH_RET_FLOAT") || m.equals("R_GETRETF")) volRegOk[2] = false;
            for (int k = 1; k < l.size(); k++) {
                String t = l.get(k).text;
                if (t.length() > 3 && t.startsWith("ARG") && Character.isDigit(t.charAt(3))) {
                    try {
                        if (Integer.parseInt(t.substring(3)) >= 2) { volRegOk[0] = false; volRegOk[1] = false; }
                    } catch (NumberFormatException e) {
                        volRegOk[0] = volRegOk[1] = volRegOk[2] = false;
                    }
                }
            }
        }
        boolean[] volOk = new boolean[nh];
        java.util.Arrays.fill(volOk, true);
        for (int i = 0; i < n; i++) {
            List<BytecodeToken> l = fn.get(i);
            if (l.isEmpty() || isHint(l) || isLabelDefLine(l) || VOL_SAFE.contains(l.get(0).text)) continue;
            for (int v = 0; v < nh; v++) {
                if (in[i].get(v) || out[i].get(v) || def[i].get(v)) volOk[v] = false;
            }
        }
        for (int v = 0; v < nh; v++) all.get(v).volOk = volOk[v];
        // colour, heaviest first, per register file
        colourInt(all, intCands, adj, idOf, intLimit, volRegOk);
        colour(all, xCands, adj, idOf, XVAR_COUNT, true);
    }

    /** Integer colouring: callee-saved registers 0..intLimit-1, and for variables that may (volOk) also r8/r9/r10 as 3..5. */
    private static void colourInt(List<Hint> all, List<Hint> cands, java.util.BitSet[] adj, Map<Long, Integer> idOf, int intLimit,
                                  boolean[] volRegOk) {
        for (Hint h : cands) {
            int id = idOf.get(h.off);
            boolean[] taken = new boolean[VOL_FIRST + VOL_COUNT];
            for (int v = adj[id].nextSetBit(0); v >= 0; v = adj[id].nextSetBit(v + 1)) {
                Hint o = all.get(v);
                if (o.reg >= 0 && o.reg < taken.length && !o.xmm) taken[o.reg] = true;
            }
            List<Integer> order = new ArrayList<>();
            if (h.volOk) {
                for (int r = 0; r < VOL_COUNT; r++) if (volRegOk[r]) order.add(VOL_FIRST + r);
            }
            for (int r = 0; r < intLimit; r++) order.add(r);
            for (int r : order) {
                if (!taken[r]) { h.reg = r; break; }
            }
        }
    }

    private static void colour(List<Hint> all, List<Hint> cands, java.util.BitSet[] adj, Map<Long, Integer> idOf, int regCount, boolean xmm) {
        int[] width = new int[regCount];
        for (Hint h : cands) {
            int id = idOf.get(h.off);
            boolean[] taken = new boolean[regCount];
            for (int v = adj[id].nextSetBit(0); v >= 0; v = adj[id].nextSetBit(v + 1)) {
                Hint o = all.get(v);
                if (o.reg >= 0 && o.reg < regCount && o.xmm == xmm) taken[o.reg] = true;
            }
            for (int r = 0; r < regCount; r++) {
                if (taken[r]) continue;
                if (xmm && width[r] != 0 && width[r] != h.size) continue;
                h.reg = r;
                if (xmm) width[r] = h.size;
                break;
            }
        }
    }

    private static long pairSlotOf(List<List<BytecodeToken>> fn, int addrIdx, Map<Long, Hint> hints) {
        Hint h = slotHint(hints, fn.get(addrIdx).get(2).text);
        return h == null ? Long.MIN_VALUE : h.off;
    }

    private static List<BytecodeToken> mk(BytecodeToken ref, String... texts) {
        List<BytecodeToken> r = new ArrayList<>(texts.length);
        for (String t : texts) {
            r.add(new BytecodeToken(t, ref.file, ref.line, BytecodeToken.Kind.CODE));
        }
        return r;
    }

    /**
     * Peephole over the rewritten function: a value that is computed into a temp and immediately moved into a float variable
     * is computed straight into the variable's register instead (the temp must be dead afterwards, see {@link #tempDeadAfter}).
     */
    private static List<List<BytecodeToken>> fuse(List<List<BytecodeToken>> res) {
        List<List<BytecodeToken>> o = new ArrayList<>(res.size());
        int i = 0;
        while (i < res.size()) {
            List<BytecodeToken> a = res.get(i);
            List<BytecodeToken> b = i + 1 < res.size() ? res.get(i + 1) : null;
            String am = a.isEmpty() ? "" : a.get(0).text;
            // R_GTOX n %xK %tN
            if (b != null && b.size() == 4 && b.get(0).text.equals("R_GTOX") && b.get(3).text.startsWith("%t")) {
                String tmp = b.get(3).text;
                String xk = b.get(2).text;
                String bw = b.get(1).text;
                if (am.equals("R_LD") && a.size() == 4 && a.get(1).text.equals(bw) && a.get(2).text.equals(tmp)
                        && tempDeadAfter(res, i + 2, tmp)) {
                    o.add(mk(a.get(0), "R_LDX", bw, xk, a.get(3).text));
                    i += 2;
                    continue;
                }
                if (am.equals("R_FBIN") && a.size() == 6 && a.get(2).text.equals(bw) && a.get(3).text.equals(tmp)
                        && tempDeadAfter(res, i + 2, tmp)) {
                    o.add(mk(a.get(0), "R_FBINX", a.get(1).text, bw, xk, a.get(4).text, a.get(5).text));
                    i += 2;
                    continue;
                }
            }
            // R_XTOG n %tN %xK ; R_ST n addr %tN
            if (am.equals("R_XTOG") && a.size() == 4 && b != null && b.size() == 4 && b.get(0).text.equals("R_ST")
                    && b.get(1).text.equals(a.get(1).text) && b.get(3).text.equals(a.get(2).text) && !b.get(2).text.equals(a.get(2).text)
                    && tempDeadAfter(res, i + 2, a.get(2).text)) {
                o.add(mk(a.get(0), "R_STX", a.get(1).text, b.get(2).text, a.get(3).text));
                i += 2;
                continue;
            }
            o.add(a);
            i++;
        }
        return o;
    }

    /** stack-form mnemonics that may sit between "ADDR 4 $x" and its "ASSIGN 4 4 4" without touching the pushed address */
    private static final java.util.Set<String> VALUE_OPS = new HashSet<>(Arrays.asList(
            "PUSH", "PUSH_RET_FLOAT", "PUSH_RET_INT", "PUSH_LABEL", "ADD_FLOAT", "SUB_FLOAT", "MUL_FLOAT", "DIV_FLOAT",
            "ADD_INT", "SUB_INT", "MUL_INT", "CC_START", "CC_END", "CALL", "VARARGS_XMM_COUNT", "PROMOTE_F32_TO_F64", "FCONV", "NEG_FLOAT"));

    private static final java.util.Set<String> BLOCK_END = new HashSet<>(Arrays.asList(
            "JMP", "R_BRF", "R_RET", "R_RETF", "RET", "RET_FLOAT", "GT_UNWIND", "THROW", "CC_START", "CC_END", "CALL", "INVOKE", "FUNC_END"));

    /**
     * True when temp t is not read again after position from: scan forward; a read means "live", a write first means "dead", and the
     * end of the straight-line run (a label, a jump, a call bracket, a return) means "dead" too -- the register-form pass flushes
     * its operand model at all of those, so no temp is ever live across them.
     */
    private static boolean tempDeadAfter(List<List<BytecodeToken>> res, int from, String t) {
        for (int i = from; i < res.size() && i < from + 60; i++) {
            List<BytecodeToken> l = res.get(i);
            if (l.isEmpty()) {
                continue;
            }
            String m = l.get(0).text;
            if (l.size() == 1 && m.startsWith("@") && m.endsWith(":")) {
                return true;
            }
            if (BLOCK_END.contains(m)) {
                // a return/branch may still read the temp as its operand
                for (int k = 1; k < l.size(); k++) {
                    if (l.get(k).text.equals(t)) {
                        return false;
                    }
                }
                return true;
            }
            int dst = dstIndex(m);
            boolean read = false;
            boolean write = false;
            for (int k = 1; k < l.size(); k++) {
                if (l.get(k).text.equals(t)) {
                    if (k == dst) {
                        write = true;
                    } else {
                        read = true;
                    }
                }
            }
            if (read) {
                return false;
            }
            if (write) {
                return true;
            }
        }
        return true;
    }

    /** operand position of the destination temp of a register-form instruction, or -1 */
    private static int dstIndex(String m) {
        switch (m) {
            case "R_LD": case "R_MOV": case "R_XTOG": return 2;
            case "R_LEA": return 1;
            case "R_BIN": case "R_UN": case "R_FBIN": case "R_FCMP": return 3;
            default: return -1;
        }
    }

    private static Hint slotHint(Map<Long, Hint> hints, String tok) {
        if (tok.length() < 2 || tok.charAt(0) != '$') {
            return null;
        }
        try {
            return hints.get(Long.parseLong(tok.substring(1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intOf(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** May operand k of this line name the hinted slot h (and be renamed to its register)? */
    private static boolean mentionOk(String m, List<BytecodeToken> l, int k, Hint h) {
        switch (m) {
            case "R_MOV": // R_MOV 8 dst src
                return h.size == 8 && l.size() == 4 && (k == 2 || k == 3);
            case "R_LD": // R_LD n %tD addr
                return l.size() == 4 && k == 3 && intOf(l.get(1).text) == h.size;
            case "R_ST": // R_ST n addr src  (the address only)
                return l.size() == 4 && k == 2 && intOf(l.get(1).text) == h.size;
            case "R_LEA": // R_LEA %tD base idx scale [disp] -- the index value, never the base address
                return (l.size() == 5 || l.size() == 6) && k == 3 && h.size == 8;
            case "R_BIN": // R_BIN OP size %tD a b -- only full-width operands (a narrow op loads its operands first)
                return l.size() == 6 && (k == 4 || k == 5) && h.size == 8 && intOf(l.get(2).text) == 8;
            case "R_UN": // R_UN OP size %tD a
                return l.size() == 5 && k == 4 && h.size == 8;
            case "R_FBIN":
            case "R_FCMP": // R_FBIN OP size %tD a b -- an operand read straight from memory has the op's own width
                return l.size() == 6 && (k == 4 || k == 5) && intOf(l.get(2).text) == h.size;
            case "R_RMW": // R_RMW OP 8 $x [b]
                return h.size == 8 && (k == 3 || (k == 4 && l.size() == 5));
            case "R_ARG":
            case "R_FARG": // R_ARG n size src
                return l.size() == 4 && k == 3 && intOf(l.get(2).text) == h.size;
            case "R_RET":
            case "R_RETF": // R_RET src
                return l.size() == 2 && k == 1 && h.size == 8;
            case "R_PUSH": // R_PUSH 8 src
                return l.size() == 3 && k == 2 && h.size == 8;
            default:
                return false; // anything else (PUSH, ADDR, ASSIGN, R_PUSHA, R_ARGA, ...) needs the slot in memory
        }
    }

    /** May operand k of this line name the float-variable slot h (and be renamed to its xmm register)? */
    private static boolean mentionOkX(String m, List<BytecodeToken> l, int k, Hint h) {
        switch (m) {
            case "R_LD": // R_LD n %tD $x
                return l.size() == 4 && k == 3 && intOf(l.get(1).text) == h.size;
            case "R_ST": // R_ST n $x src (the address only)
                return l.size() == 4 && k == 2 && intOf(l.get(1).text) == h.size;
            case "R_MOV": { // R_MOV 8 %tD $x (load) / R_MOV 8 $x %tS|#imm (store): how RegisterFormPass spells every 8-byte slot access
                if (!(l.size() == 4 && h.size == 8 && intOf(l.get(1).text) == 8)) return false;
                String other = l.get(k == 2 ? 3 : 2).text;
                if (k == 3) return other.startsWith("%t");                       // load into a temp
                return k == 2 && (other.startsWith("%t") || other.startsWith("#")); // store from a temp or an immediate
            }
            case "R_FBIN":
            case "R_FCMP": // R_FBIN OP n %tD a b
                return l.size() == 6 && (k == 4 || k == 5) && intOf(l.get(2).text) == h.size;
            case "R_ARG":
            case "R_FARG": // R_ARG n size src
                return l.size() == 4 && k == 3 && intOf(l.get(2).text) == h.size;
            case "R_RETF": // R_RETF src
                return l.size() == 2 && k == 1;
            case "PUSH": // PUSH n $x
                return l.size() == 3 && k == 2 && intOf(l.get(1).text) == h.size;
            default:
                return false;
        }
    }

    private static List<BytecodeToken> rewrite(List<BytecodeToken> l, Map<Long, Hint> chosenInt, Map<Long, Hint> chosenX) {
        if (l.isEmpty()) {
            return l;
        }
        String m = l.get(0).text;
        boolean touchesInt = false;
        boolean touchesX = false;
        List<String> t = new ArrayList<>(l.size());
        for (int k = 0; k < l.size(); k++) {
            String s = l.get(k).text;
            if (k > 0) {
                Hint hi = slotHint(chosenInt, s);
                Hint hx = hi == null ? slotHint(chosenX, s) : null;
                if (hi != null) {
                    s = "%v" + hi.reg;
                    touchesInt = true;
                } else if (hx != null) {
                    s = "%x" + hx.reg;
                    touchesX = true;
                }
            }
            t.add(s);
        }
        if (!touchesInt && !touchesX) {
            return l;
        }
        if (touchesX) {
            if (m.equals("R_LD")) {
                // R_LD n %tD $x -> R_XTOG n %tD %xK
                t = new ArrayList<>(Arrays.asList("R_XTOG", t.get(1), t.get(2), t.get(3)));
            } else if (m.equals("R_ST")) {
                // R_ST n $x src -> R_GTOX n %xK src
                t = new ArrayList<>(Arrays.asList("R_GTOX", t.get(1), t.get(2), t.get(3)));
            } else if (m.equals("R_MOV")) {
                // R_MOV 8 %tD %xK -> R_XTOG 8 %tD %xK ; R_MOV 8 %xK src -> R_GTOX 8 %xK src
                t = new ArrayList<>(Arrays.asList(t.get(2).startsWith("%x") ? "R_GTOX" : "R_XTOG", t.get(1), t.get(2), t.get(3)));
            }
        } else if (m.equals("R_LD")) {
            // the variable register already holds the zero-extended value
            t.set(0, "R_MOV");
            t.set(1, "8");
        } else if (m.equals("R_ST")) {
            if (intOf(l.get(1).text) == 8) {
                t.set(0, "R_MOV");
            } else {
                t.set(0, "R_SETV");
            }
        }
        BytecodeToken a = l.get(0);
        List<BytecodeToken> r = new ArrayList<>(t.size());
        for (String s : t) {
            r.add(new BytecodeToken(s, a.file, a.line, BytecodeToken.Kind.CODE));
        }
        return r;
    }
}
```

### FILE: src/main/java/caspien/lowerorder/RegisterFormPass.java
```java
package caspien.lowerorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Register-form pass ("deferred operands") -- runs LAST, on the final
 * low-order bytecode, and only when compiler.config says
 * "deferred-operands: on".
 *
 * <p>The low-order bytecode is a pure stack machine: every operand is
 * pushed, every operator pops and pushes. This pass keeps a compile-time
 * model of that operand stack instead of emitting the pushes: a constant,
 * a variable's value (still in its frame slot), an address or a value
 * held in a temp register is just remembered, and is only turned into
 * code when something consumes it. Operators whose operands are all
 * remembered are emitted as three-address "register-form" instructions:
 *
 * <pre>
 *   R_MOV  8 dst src           dst = src            (dst: %tN or $off, src: #imm, $off or %tN)
 *   R_LD   n %tD addr          %tD = n bytes at addr, zero-extended  (addr: $off frame slot, &amp;sym global, %tN pointer)
 *   R_ST   n addr src          n bytes at addr = src (addr as above, src: #imm or %tN)
 *   R_LEA  %tD base idx scale  %tD = base + idx*scale  (base: $off address of a slot, &amp;sym, %tN; idx: #imm, $off value, %tN)
 *   R_BIN  OP size %tD a b     %tD = a OP b         (OP: ADD SUB MUL AND OR EQ NEQ LT LT_EQ GT GT_EQ SLT SLT_EQ SGT SGT_EQ)
 *   R_UN   OP size %tD a       %tD = OP a           (OP: INC DEC NEG NOT)
 *   R_FBIN OP size %tD a b     float %tD = a OP b   (OP: ADD SUB MUL DIV; operands are raw bit patterns)
 *   R_FCMP OP size %tD a b     %tD = a OP b (0/1)   (OP: EQ NEQ LT LT_EQ GT_EQ GT)
 *   R_RMW  OP 8 $x b           $x OP= b             (OP: ADD SUB INC DEC -- one memory-operand instruction)
 *   R_ARG  n size src          integer argument register n = src
 *   R_ARGA n addr              integer argument register n = address ($off or &amp;sym)
 *   R_FARG n size src          float argument register n = src bits
 *   R_RET  src / R_RETF src    return src in rax / xmm0 (then the function epilogue)
 *   R_BRF  src @label          jump if src == 0  (replaces CMP + JMP)
 *   R_PUSH 8 src               push src (#imm, $off or %tN) on the real stack (flush only)
 *   R_PUSHA 8 addr             push an address ($off or &amp;sym) on the real stack (flush only)
 *   R_SETV n %vK src           (written only by RegVarPromotionPass) variable register = n bytes of src, zero-extended
 * </pre>
 *
 * Operands: {@code #imm} an immediate (a float constant is its IEEE bit
 * pattern), {@code $off} a frame slot, {@code &sym} a global, {@code %tN} a
 * temporary register (N below {@link #TEMP_COUNT}); the backend maps temps to
 * real registers. This pass never keeps a variable in a register -- only temporaries. (The separate
 * {@link RegVarPromotionPass}, which runs after this one when "variables-in-registers: on", renames the frame-slot
 * operands of hot scalar locals to variable registers "%vN" in this pass's output.)
 * A temp holding a value narrower than 8 bytes is always zero-extended to 64
 * bits, exactly like a word pushed by the stack form.
 *
 * <p><b>Safety net 1 -- flush.</b> Before ANY instruction this pass does not
 * fuse, every remembered entry is written back out, bottom to top, as the
 * original PUSH/ADDR line (temps as R_PUSH), and the instruction is then
 * emitted unchanged. So temporaries never live across a label, jump, call,
 * CC_*, LOOKUP*, NEW*, atomics, or any mnemonic this pass has never heard of.
 *
 * <p><b>Safety net 2 -- rollback.</b> A "region" runs from one point where
 * the model is empty to the next. A region that uses anything beyond plain
 * 8-byte integers (narrow values, floats, pointers, globals, computed
 * addresses, argument registers) is "risky": a stack word written back from
 * such a value would not necessarily be the word the original code pushed
 * (construction runs, small-array tagging). So if a risky region hits
 * something it cannot fuse while entries are still remembered, the whole
 * region is discarded and its ORIGINAL lines are emitted instead. Every
 * fusion is therefore all-or-nothing per region, and the output of a region
 * that cannot be fused completely is exactly the stack code.
 *
 * <p>Hazards handled at fusion time: a deferred read of a frame slot is
 * loaded into a temp before any store that could overwrite it (an exact
 * overlap test for a store to a known slot; every deferred read for a store
 * through a computed pointer).
 *
 * <p><b>Extension points.</b> Entries carry a size, operand text is produced in one place ({@link #operandOf}), and
 * fusable mnemonics live in the {@link #handlers} table. Promoted-variable registers turned out not to need any change
 * here: every operand this pass emits for a frame slot is already a plain "$off" text, so {@link RegVarPromotionPass}
 * can rename them afterwards, on the final text, where a slot with any mention it cannot rewrite is simply left alone.
 */
public class RegisterFormPass implements OptimizationPass {

    /** How many temporaries exist; the backend maps %t0..%t{N-1} to registers. */
    public static final int TEMP_COUNT = 4;

    enum Kind {
        /** a constant (integer, or a float's bit pattern) */ K,
        /** a value still sitting in a frame slot (size 1/2/4/8) */ M,
        /** an address: of a frame slot ("$off") or of a global ("&sym") */ A,
        /** a value held in a temp register, zero-extended to 64 bits */ T
    }

    static final class Entry {
        Kind kind;
        /** the original line (K, M, A) so a flush can re-emit it verbatim */
        List<BytecodeToken> orig;
        /** K: literal text; M: the "$off" text; A: "$off" or "&sym" */
        String text;
        /** M/A(frame): parsed frame offset */
        long off;
        /** T: which temp */
        int temp = -1;
        /** width in bytes of an M value or a loaded T (arithmetic results are 8) */
        int size = 8;
        /** A only: the address is a global symbol */
        boolean global;
        /** T only: the register holds the value ZERO-EXTENDED to 64 bits (a load of `size` bytes). Arithmetic results are not: a narrow ADD leaves carry bits above its width. */
        boolean zx;
    }

    /** One fusable mnemonic. Returns how many input lines it consumed, or 0 to decline (caller flushes/rolls back and passes through). A handler must not emit anything before it returns 0. */
    interface Handler {
        int tryFuse(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx);
    }

    private final Map<String, Handler> handlers = new HashMap<>();

    /** Names declared by GLOBAL / ALLOC_STATIC lines of the program being processed (refilled by every run). */
    private final java.util.Set<String> globalNames = new java.util.HashSet<>();

    private static final Map<String, String> BIN_OPS = new HashMap<>();
    static {
        BIN_OPS.put("ADD_INT", "ADD");
        BIN_OPS.put("SUB_INT", "SUB");
        BIN_OPS.put("MUL_INT", "MUL");
        BIN_OPS.put("EQ_INT", "EQ");
        BIN_OPS.put("NEQ_INT", "NEQ");
        BIN_OPS.put("LT_INT", "LT");
        BIN_OPS.put("LT_EQ_INT", "LT_EQ");
        BIN_OPS.put("GT_INT", "GT");
        BIN_OPS.put("GT_EQ_INT", "GT_EQ");
        BIN_OPS.put("SLT_INT", "SLT");
        BIN_OPS.put("SLT_EQ_INT", "SLT_EQ");
        BIN_OPS.put("SGT_INT", "SGT");
        BIN_OPS.put("SGT_EQ_INT", "SGT_EQ");
        BIN_OPS.put("AND", "AND");
        BIN_OPS.put("OR", "OR");
        // The bitwise group, every operand width, constant or variable shift count (a variable count goes through %rcx in the backend,
        // saved and restored around the shift). SHR/BITS_AND are also what StrengthReductionPass makes of x / 2^n and x % 2^n.
        BIN_OPS.put("SHL", "SHL");
        BIN_OPS.put("SHR", "SHR");
        BIN_OPS.put("SAR", "SAR");
        BIN_OPS.put("BITS_AND", "BAND");
        BIN_OPS.put("BITS_OR", "BOR");
        BIN_OPS.put("BITS_XOR", "BXOR");
    }

    private static final Map<String, String> FLOAT_BIN = new HashMap<>();
    private static final Map<String, String> FLOAT_CMP = new HashMap<>();
    static {
        FLOAT_BIN.put("ADD_FLOAT", "ADD");
        FLOAT_BIN.put("SUB_FLOAT", "SUB");
        FLOAT_BIN.put("MUL_FLOAT", "MUL");
        FLOAT_BIN.put("DIV_FLOAT", "DIV");
        FLOAT_CMP.put("EQ_FLOAT", "EQ");
        FLOAT_CMP.put("NEQ_FLOAT", "NEQ");
        FLOAT_CMP.put("LT_FLOAT", "LT");
        FLOAT_CMP.put("LT_EQ_FLOAT", "LT_EQ");
        FLOAT_CMP.put("GT_EQ_FLOAT", "GT_EQ");
        FLOAT_CMP.put("GT_FLOAT", "GT");
    }

    public RegisterFormPass() {
        handlers.put("PUSH", this::fusePush);
        handlers.put("ADDR", this::fuseAddr);
        for (String m : BIN_OPS.keySet()) {
            handlers.put(m, this::fuseBinary);
        }
        handlers.put("INC_INT", this::fuseUnary);
        handlers.put("DEC_INT", this::fuseUnary);
        handlers.put("NEG", this::fuseUnary);
        handlers.put("NOT", this::fuseUnary);
        handlers.put("BITS_NOT", this::fuseUnary);
        handlers.put("ASSIGN", this::fuseAssign);
        handlers.put("CMP", this::fuseCmpJmp);
        for (String m : FLOAT_BIN.keySet()) {
            handlers.put(m, this::fuseFloatBin);
        }
        for (String m : FLOAT_CMP.keySet()) {
            handlers.put(m, this::fuseFloatCmp);
        }
        handlers.put("DEREF", this::fuseDeref);
        handlers.put("LEN", this::fuseLen);
        handlers.put("LOOKUP_DYN", this::fuseLookupDyn);
        handlers.put("LOOKUP_DYN_LHS", this::fuseLookupDyn);
        handlers.put("ZEXT", this::fuseZext);
        handlers.put("TRUNC", this::fuseTrunc);
        handlers.put("LOOKUP_ARRAY_LHS", this::fuseLookupArrayLhs);
        handlers.put("PUSH_FIELDNAME", this::fuseFieldName);
        handlers.put("DOT_LHS", this::fuseDotLhs);
        handlers.put("POP", this::fusePop);
        handlers.put("RET", this::fuseRet);
        handlers.put("RET_FLOAT", this::fuseRet);
    }

    @Override
    public String name() {
        return "register-form";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        globalNames.clear();
        for (List<BytecodeToken> l : lines) {
            if (l.size() >= 2 && (l.get(0).text.equals("GLOBAL") || l.get(0).text.equals("ALLOC_STATIC"))) {
                // Dotted names are member aliases into a parent block; the backend's alias handling stays on the stack path.
                if (l.get(1).text.indexOf('.') < 0) {
                    globalNames.add(l.get(1).text);
                }
            }
        }
        State st = new State();
        boolean changed = false;
        int i = 0;
        while (i < lines.size()) {
            List<BytecodeToken> line = lines.get(i);
            if (line.isEmpty()) {
                st.out.add(line);
                i++;
                continue;
            }
            Handler h = handlers.get(line.get(0).text);
            int consumed = h == null ? 0 : h.tryFuse(st, line, lines, i);
            if (consumed > 0) {
                changed = true;
                i += consumed;
            } else {
                if (st.risky && !st.stack.isEmpty() && !(flushIsFaithful(line) && st.plainWords())) {
                    st.rollback(lines, i);
                } else {
                    st.flush();
                }
                st.out.add(line);
                i++;
            }
            if (st.stack.isEmpty()) {
                // A region boundary: nothing is remembered, so everything before this point is final.
                Arrays.fill(st.tempUsed, false);
                st.cpIn = i;
                st.cpOut = st.out.size();
                st.risky = false;
            }
        }
        if (st.risky && !st.stack.isEmpty()) {
            st.rollback(lines, lines.size());
        } else {
            st.flush();
        }
        return new PassResult(st.out, changed);
    }

    /**
     * Mnemonics in front of which a RISKY region may still be flushed instead of rolled back (when
     * {@link State#plainWords} holds): a call bracket, and the plain stack arithmetic ops. For these the stack words
     * a flush writes are exactly the words the original code pushed (every remembered entry is one 8-byte word), and
     * the backend does not look at the lines before them (unlike ASSIGN / NEW / LOOKUP / DOT, which find a struct
     * or array construction run by scanning back). Without this, one call in the middle of a statement
     * (an inlined sqrt under a pending "e = e - ...") threw away the whole region, including the loads already fused.
     */
    private static boolean flushIsFaithful(List<BytecodeToken> line) {
        String m = line.get(0).text;
        return m.equals("CC_START") || m.endsWith("_FLOAT") || m.endsWith("_INT")
                || m.equals("SHL") || m.equals("SHR") || m.equals("SAR") || m.startsWith("BITS_");
    }

    // ------------------------------------------------------------------
    // State: the abstract operand stack, the temp pool, and the output.
    // ------------------------------------------------------------------

    static final class State {
        final List<List<BytecodeToken>> out = new ArrayList<>();
        final List<Entry> stack = new ArrayList<>();
        final boolean[] tempUsed = new boolean[TEMP_COUNT];
        private BytecodeToken anchor; // any token, for file/line of synthesized lines
        /** region start: the input line index and output size at the last empty-model point */
        int cpIn = 0;
        int cpOut = 0;
        /** the current region uses something a plain stack flush could not reproduce faithfully */
        boolean risky = false;

        int freeTemps() {
            int n = 0;
            for (boolean u : tempUsed) {
                if (!u) n++;
            }
            return n;
        }

        int allocTemp() {
            for (int t = 0; t < TEMP_COUNT; t++) {
                if (!tempUsed[t]) {
                    tempUsed[t] = true;
                    return t;
                }
            }
            return -1;
        }

        void freeTemp(int t) {
            tempUsed[t] = false;
        }

        void emit(String... texts) {
            out.add(mk(texts));
        }

        List<BytecodeToken> mk(String... texts) {
            List<BytecodeToken> l = new ArrayList<>(texts.length);
            BytecodeToken a = anchor;
            for (String t : texts) {
                l.add(new BytecodeToken(t, a == null ? "<register-form>" : a.file, a == null ? 0 : a.line, BytecodeToken.Kind.CODE));
            }
            return l;
        }

        /** every remembered entry is one plain 8-byte word (a constant, a frame value, an address, a full-width temp) */
        boolean plainWords() {
            for (Entry e : stack) {
                if (e.size != 8) {
                    return false;
                }
            }
            return true;
        }

        /** Discards this region's output and emits its original input lines [cpIn, upTo) instead. */
        void rollback(List<List<BytecodeToken>> all, int upTo) {
            out.subList(cpOut, out.size()).clear();
            for (int k = cpIn; k < upTo; k++) {
                out.add(all.get(k));
            }
            stack.clear();
            Arrays.fill(tempUsed, false);
        }

        /** Writes every remembered entry back out, bottom to top, then empties the model. */
        void flush() {
            flush(false);
        }

        /**
         * @param tempLiveAbove true when a temp that is NOT on the model (e.g. a branch condition being
         *                      consumed) is still live in a register while this flush runs
         *
         * An entry's ORIGINAL line is re-emitted by the backend's ordinary PUSH/ADDR code, which uses rax/rbx
         * as scratch -- so it must not run while any temp is still live. Whenever a temp sits above the
         * entry (or is live off-model), the entry is written with a register-form push instead, which only
         * ever uses the spare scratch register. When no temp is live above, the original line is kept, so the
         * struct/array construction shapes the backend recognises reach it unchanged. (Only non-risky regions
         * are ever flushed while entries remain; see the class comment.)
         */
        void flush(boolean tempLiveAbove) {
            int lastT = -1;
            for (int i = 0; i < stack.size(); i++) {
                if (stack.get(i).kind == Kind.T) {
                    lastT = i;
                }
            }
            for (int i = 0; i < stack.size(); i++) {
                Entry e = stack.get(i);
                if (e.kind == Kind.T) {
                    emit("R_PUSH", "8", "%t" + e.temp);
                    freeTemp(e.temp);
                } else if (tempLiveAbove || i < lastT) {
                    if (e.kind == Kind.A) {
                        emit("R_PUSHA", "8", e.text);
                    } else {
                        emit("R_PUSH", "8", operandOf(e));
                    }
                } else {
                    out.add(e.orig);
                }
            }
            stack.clear();
        }
    }

    // ------------------------------------------------------------------
    // Operand text -- the one place that knows how an entry is written.
    // ------------------------------------------------------------------

    private static String operandOf(Entry e) {
        switch (e.kind) {
            case K:
                return "#" + e.text;
            case M:
                return e.text;
            case T:
                return "%t" + e.temp;
            default:
                throw new IllegalStateException("an address entry is not a value operand");
        }
    }

    /**
     * Can this entry be the address operand of a store / dereference / index? An address entry, or an 8-byte
     * pointer value. The width test also keeps a struct-construction run apart from a scalar store: a field
     * that sits below the last value of a run is narrower than the whole (a run's total is the ASSIGN width,
     * at most 8), so it can never be an 8-byte value -- only a genuine address is.
     */
    private static boolean isAddress(Entry e) {
        return e.kind == Kind.A || ((e.kind == Kind.T || e.kind == Kind.M) && e.size == 8);
    }

    /** anything that is a value (not an address) */
    private static boolean isValue(Entry e) {
        return e.kind != Kind.A;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static boolean is(List<BytecodeToken> line, int n) {
        return line.size() == n;
    }

    private static Long parseIntLiteral(String s) {
        if (s.equals("null")) {
            return 0L;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            try {
                return Long.parseUnsignedLong(s);
            } catch (NumberFormatException e2) {
                return null;
            }
        }
    }

    private static Long parseSlot(String s) {
        if (!s.startsWith("$")) {
            return null;
        }
        try {
            return Long.parseLong(s.substring(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseSize(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isWidth(int n) {
        return n == 1 || n == 2 || n == 4 || n == 8;
    }

    private static boolean isFloatLiteral(String s) {
        return s.matches("-?\\d+\\.\\d+([eE][+-]?\\d+)?");
    }

    private static boolean fitsImm32(String text) {
        Long v = parseIntLiteral(text);
        return v != null && v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE;
    }

    /** Loads a memory-resident or constant entry into a fresh temp (the caller has checked a temp is free). */
    private static void loadInto(State st, Entry e) {
        int t = st.allocTemp();
        if (e.kind == Kind.M) {
            if (e.size == 8) {
                st.emit("R_MOV", "8", "%t" + t, e.text);
            } else {
                st.emit("R_LD", String.valueOf(e.size), "%t" + t, e.text);
            }
        } else {
            st.emit("R_MOV", "8", "%t" + t, operandOf(e)); // a constant
        }
        boolean wasMem = e.kind == Kind.M;
        e.kind = Kind.T;
        e.temp = t;
        e.orig = null;
        e.zx = wasMem; // R_LD / an 8-byte move of a frame value: zero-extended; a constant is taken as written
    }

    /** a temp that an R_LD of `size` bytes just filled: zero-extended */
    private static Entry loadedTemp(int t, int size) {
        Entry r = newTemp(t, size);
        r.zx = true;
        return r;
    }

    private static Entry newTemp(int t, int size) {
        Entry r = new Entry();
        r.kind = Kind.T;
        r.temp = t;
        r.size = size;
        return r;
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private int fusePush(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 3)) {
            return 0;
        }
        int n = parseSize(line.get(1).text);
        if (!isWidth(n)) {
            return 0;
        }
        String operand = line.get(2).text;
        Entry e = new Entry();
        e.orig = line;
        e.size = n;
        Long slot = parseSlot(operand);
        if (slot != null) {
            e.kind = Kind.M;
            e.text = operand;
            e.off = slot;
        } else if (isFloatLiteral(operand)) {
            // The backend pushes a float literal as its IEEE bit pattern (single up to 4 bytes, else double).
            long bits = n <= 4
                    ? Integer.toUnsignedLong(Float.floatToRawIntBits(Float.parseFloat(operand)))
                    : Double.doubleToRawLongBits(Double.parseDouble(operand));
            e.kind = Kind.K;
            e.text = Long.toUnsignedString(bits);
            st.risky = true;
        } else {
            Long lit = parseIntLiteral(operand);
            if (lit == null) {
                if (globalNames.contains(operand)) {
                    return fuseGlobalRead(st, line, all, idx, n, operand);
                }
                return 0;
            }
            e.kind = Kind.K;
            e.text = operand.equals("null") ? "0" : operand;
        }
        if (n < 8) {
            st.risky = true;
        }
        st.anchor = line.get(0);
        st.stack.add(e);
        return 1;
    }

    /**
     * {@code PUSH n <global>}: a read of a global scalar. Loaded eagerly into a temp
     * ({@code R_LD n %tD &sym}), so the read order is exactly the original one and no new store hazard exists.
     * Declined (stack form) when an atomic swap follows two lines later (the backend reads that push as an address)
     * or no temp is free.
     */
    private int fuseGlobalRead(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx, int n, String sym) {
        // The backend decides a global push by looking TWO lines ahead: "PUSH address / PUSH newValue / ATOMIC_SWAP" pushes
        // the global's ADDRESS, not its value (X86Backend lineAtOffsetIsAtomicSwap(2)).
        if (idx + 2 < all.size()) {
            List<BytecodeToken> swap = all.get(idx + 2);
            if (!swap.isEmpty() && swap.get(0).text.equals("ATOMIC_SWAP")) {
                return 0;
            }
        }
        if (st.freeTemps() < 1) {
            return 0;
        }
        st.anchor = line.get(0);
        int t = st.allocTemp();
        st.emit("R_LD", String.valueOf(n), "%t" + t, "&" + sym);
        st.stack.add(newTemp(t, n));
        st.risky = true;
        return 1;
    }

    private int fuseAddr(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 3)) {
            return 0;
        }
        String operand = line.get(2).text;
        Entry e = new Entry();
        e.kind = Kind.A;
        e.orig = line;
        Long slot = parseSlot(operand);
        if (slot != null) {
            e.text = operand;
            e.off = slot;
        } else {
            if (operand.isEmpty() || Character.isDigit(operand.charAt(0)) || operand.startsWith("-") || operand.startsWith("@")) {
                return 0;
            }
            e.text = "&" + operand;
            e.global = true;
            st.risky = true;
        }
        st.anchor = line.get(0);
        st.stack.add(e);
        return 1;
    }

    /** Must an M operand of an n-byte integer op be loaded into a register first? (a narrow value or a narrow op needs zero/sign extension in a register) */
    private static boolean needsLoad(Entry e, int n) {
        return e.kind == Kind.M && (e.size != 8 || n < 8);
    }

    private int fuseBinary(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        String mnemonic = line.get(0).text;
        boolean logical = mnemonic.equals("AND") || mnemonic.equals("OR");
        if (!is(line, 2)) {
            return 0;
        }
        int n = parseSize(line.get(1).text);
        if (logical ? n != 1 : !isWidth(n)) {
            return 0;
        }
        int sz = st.stack.size();
        if (sz < 2) {
            return 0;
        }
        Entry b = st.stack.get(sz - 1);
        Entry a = st.stack.get(sz - 2);
        if (!isValue(a) || !isValue(b)) {
            return 0;
        }
        if ((mnemonic.equals("SHL") || mnemonic.equals("SHR") || mnemonic.equals("SAR")) && b.kind == Kind.K && parseIntLiteral(b.text) == null) {
            return 0; // a constant count the backend could not read as an immediate
        }
        if (logical && (a.kind != Kind.T || b.kind != Kind.T)) {
            return 0; // boolean ops only on register-held 0/1 results
        }
        boolean loadA = needsLoad(a, n);
        boolean loadB = needsLoad(b, n);
        boolean aReg = a.kind == Kind.T || loadA;
        boolean bReg = b.kind == Kind.T || loadB;
        int need = (loadA ? 1 : 0) + (loadB ? 1 : 0) + ((!aReg && !bReg) ? 1 : 0);
        if (need > st.freeTemps()) {
            return 0;
        }
        st.anchor = line.get(0);
        if (n < 8 || loadA || loadB) {
            st.risky = true;
        }
        if (loadA) {
            loadInto(st, a);
        }
        if (loadB) {
            loadInto(st, b);
        }
        String aOp = operandOf(a);
        String bOp = operandOf(b);
        st.stack.remove(sz - 1);
        st.stack.remove(sz - 2);
        int dst;
        if (a.kind == Kind.T) {
            dst = a.temp;
            if (b.kind == Kind.T) {
                st.freeTemp(b.temp);
            }
        } else {
            if (b.kind == Kind.T) {
                st.freeTemp(b.temp); // dst may equal b's temp; the backend handles that
            }
            dst = st.allocTemp();
        }
        String bop = BIN_OPS.get(mnemonic);
        boolean bits = bop.equals("SHL") || bop.equals("SHR") || bop.equals("SAR") || bop.equals("BAND") || bop.equals("BOR") || bop.equals("BXOR");
        boolean arith = bits || bop.equals("ADD") || bop.equals("SUB") || bop.equals("MUL");
        st.emit("R_BIN", bop, line.get(1).text, "%t" + dst, aOp, bOp);
        Entry res = newTemp(dst, arith ? n : 1); // the width of the VALUE (a comparison or bool op yields one byte)
        res.zx = bits; // the backend cuts a narrow bitwise/shift result back to its width: the temp holds the zero-extended value
        st.stack.add(res);
        return 1;
    }

    private int fuseUnary(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        String mnemonic = line.get(0).text;
        boolean isNot = mnemonic.equals("NOT");
        boolean isBitsNot = mnemonic.equals("BITS_NOT");
        if (!is(line, 2)) {
            return 0;
        }
        int n = parseSize(line.get(1).text);
        if (isNot ? n != 1 : !isWidth(n)) {
            return 0;
        }
        int sz = st.stack.size();
        if (sz < 1) {
            return 0;
        }
        Entry a = st.stack.get(sz - 1);
        if (!isValue(a)) {
            return 0;
        }
        if (isNot && a.kind != Kind.T) {
            return 0;
        }
        boolean load = a.kind == Kind.M && a.size != 8;
        int need = (load || a.kind != Kind.T) ? 1 : 0;
        if (need > st.freeTemps()) {
            return 0;
        }
        st.anchor = line.get(0);
        if (n < 8 || load) {
            st.risky = true;
        }
        if (load) {
            loadInto(st, a);
        }
        String op = mnemonic.equals("INC_INT") ? "INC" : mnemonic.equals("DEC_INT") ? "DEC" : isBitsNot ? "BNOT" : mnemonic;
        String aOp = operandOf(a);
        st.stack.remove(sz - 1);
        int dst = a.kind == Kind.T ? a.temp : st.allocTemp();
        st.emit("R_UN", op, line.get(1).text, "%t" + dst, aOp);
        Entry res = newTemp(dst, isNot ? 1 : n);
        res.zx = isBitsNot; // the backend cuts the complement back to the operand width
        st.stack.add(res);
        return 1;
    }

    // ---- floats -------------------------------------------------------

    /** A float operand: a T or K is used as is, an M of the op's own width is read straight from memory, any other M is loaded first. */
    private static boolean floatNeedsLoad(Entry e, int n) {
        return e.kind == Kind.M && e.size != n;
    }

    private int fuseFloatBin(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        return fuseFloatOp(st, line, "R_FBIN", FLOAT_BIN.get(line.get(0).text));
    }

    private int fuseFloatCmp(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        return fuseFloatOp(st, line, "R_FCMP", FLOAT_CMP.get(line.get(0).text));
    }

    private int fuseFloatOp(State st, List<BytecodeToken> line, String rmn, String op) {
        if (!is(line, 2)) {
            return 0;
        }
        int n = parseSize(line.get(1).text);
        if (n != 4 && n != 8) {
            return 0;
        }
        int sz = st.stack.size();
        if (sz < 2) {
            return 0;
        }
        Entry b = st.stack.get(sz - 1);
        Entry a = st.stack.get(sz - 2);
        if (!isValue(a) || !isValue(b)) {
            return 0;
        }
        boolean loadA = floatNeedsLoad(a, n);
        boolean loadB = floatNeedsLoad(b, n);
        boolean aReg = a.kind == Kind.T || loadA;
        boolean bReg = b.kind == Kind.T || loadB;
        int need = (loadA ? 1 : 0) + (loadB ? 1 : 0) + ((!aReg && !bReg) ? 1 : 0);
        if (need > st.freeTemps()) {
            return 0;
        }
        st.anchor = line.get(0);
        st.risky = true;
        if (loadA) {
            loadInto(st, a);
        }
        if (loadB) {
            loadInto(st, b);
        }
        String aOp = operandOf(a);
        String bOp = operandOf(b);
        st.stack.remove(sz - 1);
        st.stack.remove(sz - 2);
        int dst;
        if (a.kind == Kind.T) {
            dst = a.temp;
            if (b.kind == Kind.T) {
                st.freeTemp(b.temp);
            }
        } else if (b.kind == Kind.T) {
            dst = b.temp;
        } else {
            dst = st.allocTemp();
        }
        st.emit(rmn, op, line.get(1).text, "%t" + dst, aOp, bOp);
        st.stack.add(newTemp(dst, rmn.equals("R_FCMP") ? 1 : n));
        return 1;
    }

    // ---- pointers, loads, computed addresses ---------------------------

    private int fuseDeref(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 2)) {
            return 0;
        }
        int n = parseSize(line.get(1).text);
        return derefN(st, line, n);
    }

    /** LEN -- the dynarray header's len field: an 8-byte load through the pointer on top of the stack. */
    private int fuseLen(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 1)) {
            return 0;
        }
        return derefN(st, line, 8);
    }

    private int derefN(State st, List<BytecodeToken> line, int n) {
        if (!isWidth(n)) {
            return 0;
        }
        int sz = st.stack.size();
        if (sz < 1) {
            return 0;
        }
        Entry p = st.stack.get(sz - 1);
        if (!isAddress(p)) {
            return 0;
        }
        if (p.kind != Kind.T && st.freeTemps() == 0) {
            return 0;
        }
        st.anchor = line.get(0);
        st.risky = true;
        st.stack.remove(sz - 1);
        if (p.kind == Kind.A) {
            int t = st.allocTemp();
            st.emit("R_LD", String.valueOf(n), "%t" + t, p.text);
            st.stack.add(loadedTemp(t, n));
        } else if (p.kind == Kind.M) {
            int t = st.allocTemp();
            st.emit("R_MOV", "8", "%t" + t, p.text);
            st.emit("R_LD", String.valueOf(n), "%t" + t, "%t" + t);
            st.stack.add(loadedTemp(t, n));
        } else {
            st.emit("R_LD", String.valueOf(n), "%t" + p.temp, "%t" + p.temp);
            st.stack.add(loadedTemp(p.temp, n));
        }
        return 1;
    }

    /** base + idx*scale into one temp. base: A, T or an 8-byte M; idx: K, T or an M (loaded when narrow). */
    private int fuseLookupArrayLhs(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 2)) {
            return 0;
        }
        int scale = parseSize(line.get(1).text);
        if (scale <= 0) {
            return 0;
        }
        int sz = st.stack.size();
        if (sz < 2) {
            return 0;
        }
        Entry ix = st.stack.get(sz - 1);
        Entry base = st.stack.get(sz - 2);
        if (!isValue(ix)) {
            return 0;
        }
        if (!isAddress(base)) {
            return 0;
        }
        return emitLea(st, line, base, ix, scale, sz);
    }

    private int emitLea(State st, List<BytecodeToken> line, Entry base, Entry ix, int scale, int sz) {
        return emitLea(st, line, base, ix, scale, sz, 0);
    }

    /** as above, plus a constant byte displacement (a sixth R_LEA operand, written only when non-zero). */
    private int emitLea(State st, List<BytecodeToken> line, Entry base, Entry ix, int scale, int sz, long disp) {
        boolean loadBase = base.kind == Kind.M;
        boolean loadIx = ix.kind == Kind.M && ix.size != 8;
        boolean baseReg = base.kind == Kind.T || loadBase;
        boolean ixReg = ix.kind == Kind.T || loadIx;
        int need = (loadBase ? 1 : 0) + (loadIx ? 1 : 0) + ((!baseReg && !ixReg) ? 1 : 0);
        if (need > st.freeTemps()) {
            return 0;
        }
        st.anchor = line.get(0);
        st.risky = true;
        if (loadBase) {
            loadInto(st, base);
        }
        if (loadIx) {
            loadInto(st, ix);
        }
        String bOp = base.kind == Kind.A ? base.text : operandOf(base);
        String iOp = operandOf(ix);
        st.stack.remove(sz - 1);
        st.stack.remove(sz - 2);
        int dst;
        if (base.kind == Kind.T) {
            dst = base.temp;
            if (ix.kind == Kind.T) {
                st.freeTemp(ix.temp);
            }
        } else if (ix.kind == Kind.T) {
            dst = ix.temp;
        } else {
            dst = st.allocTemp();
        }
        if (disp == 0) {
            st.emit("R_LEA", "%t" + dst, bOp, iOp, String.valueOf(scale));
        } else {
            st.emit("R_LEA", "%t" + dst, bOp, iOp, String.valueOf(scale), String.valueOf(disp));
        }
        st.stack.add(newTemp(dst, 8));
        return 1;
    }

    /**
     * LOOKUP_DYN n / LOOKUP_DYN_LHS n -- element address = pointer + 16 + index*n (the dynarray header is len, cap). The LHS form
     * leaves that address; the value form also loads n bytes (n = 1, 2, 4, 8) straight away, zero-extended.
     */
    private int fuseLookupDyn(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 2)) {
            return 0;
        }
        boolean lhs = line.get(0).text.equals("LOOKUP_DYN_LHS");
        int n = parseSize(line.get(1).text);
        if (n <= 0) {
            return 0;
        }
        if (!lhs) {
            if (!isWidth(n)) {
                return 0;
            }
            // the backend tags the pushed element for a following LOOKUP_ARRAY / DOT: leave those shapes alone
            if (idx + 1 < all.size()) {
                List<BytecodeToken> nx = all.get(idx + 1);
                String nm = nx.isEmpty() ? "" : nx.get(0).text;
                if (nm.startsWith("LOOKUP") || nm.startsWith("DOT")) {
                    return 0;
                }
            }
        }
        int sz = st.stack.size();
        if (sz < 2) {
            return 0;
        }
        Entry ix = st.stack.get(sz - 1);
        Entry base = st.stack.get(sz - 2);
        if (!isValue(ix) || !isAddress(base)) {
            return 0;
        }
        // the load below reuses the address temp, so it needs no extra register
        int r = emitLea(st, line, base, ix, n, sz, 16);
        if (r == 0 || lhs) {
            return r;
        }
        Entry top = st.stack.remove(st.stack.size() - 1);
        st.emit("R_LD", String.valueOf(n), "%t" + top.temp, "%t" + top.temp);
        st.stack.add(loadedTemp(top.temp, n));
        return r;
    }

    /**
     * ZEXT src dst -- zero-extension of the low `src` bytes. ZEXT 8 8 is nothing at all. A frame value is simply read at the narrower width. A temp that was LOADED at a width of at most
     * src bytes is already zero-extended (R_LD does it), so only its recorded width changes; a computed temp (a narrow ADD keeps carry
     * bits above its width) is masked (src 1 or 2) or left to the stack form (src 4: the mask does not fit an immediate).
     */
    private int fuseZext(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 3)) {
            return 0;
        }
        int src = parseSize(line.get(1).text);
        int dst = parseSize(line.get(2).text);
        int sz = st.stack.size();
        if (sz < 1 || !isWidth(src) || !isWidth(dst) || dst < src) {
            return 0;
        }
        if (src == 8 && dst == 8) {
            st.anchor = line.get(0);
            return 1;
        }
        Entry e = st.stack.get(sz - 1);
        if (e.kind == Kind.M) {
            // a frame value is zero-extended whenever it is read: keeping only its low `src` bytes is just reading fewer of them
            st.anchor = line.get(0);
            st.risky = true;
            if (e.size > src) {
                e.size = src;
                e.orig = st.mk("PUSH", String.valueOf(src), e.text); // a flush re-emits exactly the narrowed push
            }
            return 1;
        }
        if (e.kind != Kind.T) {
            return 0;
        }
        if (e.zx && e.size <= src) {
            st.anchor = line.get(0);
            st.risky = true;
            e.size = dst;
            return 1;
        }
        if (src == 1 || src == 2) {
            // computed (or wider) value: cut it down with an immediate mask
            st.anchor = line.get(0);
            st.risky = true;
            st.emit("R_BIN", "BAND", "8", "%t" + e.temp, "%t" + e.temp, src == 1 ? "#255" : "#65535");
            e.size = dst;
            e.zx = true;
            return 1;
        }
        return 0;
    }

    /**
     * TRUNC src dst -- keep the low `dst` bytes, zero-filled (a wrap:<T> or a proven narrowing). A frame value of at least dst bytes
     * is just read narrower (little endian: same address); a temp is masked in place (dst 1 or 2: the mask is an immediate).
     */
    private int fuseTrunc(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 3)) {
            return 0;
        }
        int src = parseSize(line.get(1).text);
        int dst = parseSize(line.get(2).text);
        int sz = st.stack.size();
        if (sz < 1 || src != 8 || (dst != 1 && dst != 2 && dst != 4)) {
            return 0;
        }
        Entry e = st.stack.get(sz - 1);
        if (e.kind == Kind.M && e.size >= dst) {
            st.anchor = line.get(0);
            st.risky = true;
            e.size = dst;
            e.orig = st.mk("PUSH", String.valueOf(dst), e.text); // a flush re-emits exactly the narrowed push
            return 1;
        }
        if (e.kind == Kind.T && dst != 4) {
            st.anchor = line.get(0);
            st.risky = true;
            st.emit("R_BIN", "BAND", "8", "%t" + e.temp, "%t" + e.temp, dst == 1 ? "#255" : "#65535");
            e.size = dst;
            e.zx = true;
            return 1;
        }
        return 0;
    }

    /** PUSH_FIELDNAME off size -- the field offset, an immediate that the DOT_LHS right after it adds to a base address. */
    private int fuseFieldName(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 3)) {
            return 0;
        }
        Long off = parseIntLiteral(line.get(1).text);
        if (off == null) {
            return 0;
        }
        Entry e = new Entry();
        e.kind = Kind.K;
        e.orig = line;
        e.text = line.get(1).text;
        st.anchor = line.get(0);
        st.risky = true;
        st.stack.add(e);
        return 1;
    }

    private int fuseDotLhs(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 2)) {
            return 0;
        }
        int sz = st.stack.size();
        if (sz < 2) {
            return 0;
        }
        Entry off = st.stack.get(sz - 1);
        Entry base = st.stack.get(sz - 2);
        if (off.kind != Kind.K) {
            return 0;
        }
        Long k = parseIntLiteral(off.text);
        if (k == null || !isAddress(base)) {
            return 0;
        }
        if (base.kind == Kind.A && !base.global) {
            // A field of a frame slot is just another frame address: no code at all.
            st.anchor = line.get(0);
            st.risky = true;
            st.stack.remove(sz - 1);
            st.stack.remove(sz - 2);
            Entry e = new Entry();
            e.kind = Kind.A;
            e.off = base.off + k;
            e.text = "$" + e.off;
            e.orig = st.mk("ADDR", "8", e.text);
            st.stack.add(e);
            return 1;
        }
        return emitLea(st, line, base, off, 1, sz);
    }

    // ---- stores ---------------------------------------------------------

    private int fuseAssign(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 4) || !line.get(0).text.equals("ASSIGN")) {
            return 0;
        }
        int n = parseSize(line.get(1).text);
        if (!isWidth(n) || !line.get(2).text.equals(line.get(1).text) || !line.get(3).text.equals(line.get(1).text)) {
            return 0;
        }
        int sz = st.stack.size();
        if (sz < 2) {
            return 0;
        }
        Entry val = st.stack.get(sz - 1);
        Entry addr = st.stack.get(sz - 2);
        if (!isAddress(addr) || !isValue(val)) {
            return 0; // not a plain scalar store -- includes every multi-entry construction run
        }
        boolean frameDst = addr.kind == Kind.A && !addr.global;
        boolean globalDst = addr.kind == Kind.A && addr.global;
        // Store hazard: a still-deferred read of memory this store may overwrite must be loaded first.
        List<Entry> hazards = new ArrayList<>();
        for (int k = 0; k < sz - 2; k++) {
            Entry e = st.stack.get(k);
            if (e.kind != Kind.M) {
                continue;
            }
            boolean overlaps;
            if (frameDst) {
                overlaps = e.off < addr.off + n && addr.off < e.off + e.size;
            } else {
                overlaps = !globalDst; // a store through a pointer may land on any frame slot
            }
            if (overlaps) {
                hazards.add(e);
            }
        }
        boolean legacy = n == 8 && frameDst; // the phase-1 shape: R_MOV $slot src
        boolean valLoad = val.kind == Kind.M
                || (val.kind == Kind.K && !legacy && n == 8 && !fitsImm32(val.text));
        boolean addrLoad = addr.kind == Kind.M;
        int needed = hazards.size() + (valLoad ? 1 : 0) + (addrLoad ? 1 : 0);
        if (needed > st.freeTemps()) {
            return 0;
        }
        st.anchor = line.get(0);
        if (!legacy || n != 8) {
            st.risky = true;
        }
        for (Entry h : hazards) {
            loadInto(st, h);
        }
        // Peephole: "x = x + c" / "x = x - c" / "x++" / "x--" on an 8-byte slot -> one memory-operand instruction.
        if (legacy && val.kind == Kind.T && tryRmw(st, addr, val, sz)) {
            return 1;
        }
        String src;
        int scratch = -1;
        if (valLoad) {
            scratch = st.allocTemp();
            if (val.kind == Kind.M) {
                if (val.size == 8) {
                    st.emit("R_MOV", "8", "%t" + scratch, val.text);
                } else {
                    st.emit("R_LD", String.valueOf(val.size), "%t" + scratch, val.text);
                }
            } else {
                st.emit("R_MOV", "8", "%t" + scratch, operandOf(val));
            }
            src = "%t" + scratch;
        } else {
            src = operandOf(val);
        }
        String dst;
        int addrScratch = -1;
        if (addrLoad) {
            addrScratch = st.allocTemp();
            st.emit("R_MOV", "8", "%t" + addrScratch, addr.text);
            dst = "%t" + addrScratch;
        } else if (addr.kind == Kind.T) {
            dst = "%t" + addr.temp;
        } else {
            dst = addr.text; // $off or &sym
        }
        if (legacy) {
            st.emit("R_MOV", "8", dst, src);
        } else {
            st.emit("R_ST", String.valueOf(n), dst, src);
        }
        if (scratch >= 0) {
            st.freeTemp(scratch);
        }
        if (addrScratch >= 0) {
            st.freeTemp(addrScratch);
        }
        if (val.kind == Kind.T) {
            st.freeTemp(val.temp);
        }
        if (addr.kind == Kind.T) {
            st.freeTemp(addr.temp);
        }
        st.stack.remove(sz - 1);
        st.stack.remove(sz - 2);
        return 1;
    }

    /**
     * The value about to be stored to slot $x is the result of the last emitted "R_BIN ADD|SUB 8 %tv $x b" (or
     * "R_UN INC|DEC 8 %tv $x"): replace that instruction and the store with one read-modify-write on the slot.
     * The temp is dead after the store, so nothing else reads it.
     */
    private boolean tryRmw(State st, Entry addr, Entry val, int sz) {
        if (st.out.isEmpty() || st.out.size() <= st.cpOut) {
            return false;
        }
        List<BytecodeToken> last = st.out.get(st.out.size() - 1);
        String vt = "%t" + val.temp;
        if (last.size() == 6 && last.get(0).text.equals("R_BIN") && last.get(2).text.equals("8") && last.get(3).text.equals(vt)) {
            String op = last.get(1).text;
            String a = last.get(4).text;
            String b = last.get(5).text;
            String other = null;
            if ((op.equals("ADD") || op.equals("SUB")) && a.equals(addr.text)) {
                other = b;
            } else if (op.equals("ADD") && b.equals(addr.text)) {
                other = a;
            }
            if (other == null || other.equals(addr.text)) {
                return false;
            }
            if (other.startsWith("#") && !fitsImm32(other.substring(1))) {
                return false; // the backend would need a second scratch register
            }
            st.out.remove(st.out.size() - 1);
            st.emit("R_RMW", op, "8", addr.text, other);
        } else if (last.size() == 5 && last.get(0).text.equals("R_UN") && last.get(2).text.equals("8")
                && last.get(3).text.equals(vt) && last.get(4).text.equals(addr.text)
                && (last.get(1).text.equals("INC") || last.get(1).text.equals("DEC"))) {
            String op = last.get(1).text;
            st.out.remove(st.out.size() - 1);
            st.emit("R_RMW", op, "8", addr.text);
        } else {
            return false;
        }
        st.freeTemp(val.temp);
        st.stack.remove(sz - 1);
        st.stack.remove(sz - 2);
        return true;
    }

    // ---- argument registers and returns ---------------------------------

    /** POP ARGn size / POP FARGn size -- straight into the argument register; anything else (POP $slot ...) is left alone. */
    private int fusePop(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 3)) {
            return 0;
        }
        String dest = line.get(1).text;
        boolean isF = dest.startsWith("FARG");
        boolean isI = !isF && dest.startsWith("ARG");
        if (!isF && !isI) {
            return 0;
        }
        int n = parseSize(dest.substring(isF ? 4 : 3));
        if (n < 0) {
            return 0;
        }
        int sz = st.stack.size();
        if (sz < 1) {
            return 0;
        }
        Entry v = st.stack.get(sz - 1);
        if (v.kind == Kind.A) {
            if (isF) {
                return 0;
            }
            st.anchor = line.get(0);
            st.risky = true;
            st.stack.remove(sz - 1);
            st.emit("R_ARGA", String.valueOf(n), v.text);
            return 1;
        }
        // K needs no size (a literal is pushed whole); M carries its own width; a T is already a whole word.
        st.anchor = line.get(0);
        st.risky = true;
        st.stack.remove(sz - 1);
        st.emit(isF ? "R_FARG" : "R_ARG", String.valueOf(n), String.valueOf(v.kind == Kind.M ? v.size : 8), operandOf(v));
        if (v.kind == Kind.T) {
            st.freeTemp(v.temp);
        }
        return 1;
    }

    /** RET size / RET_FLOAT size with exactly one remembered value: move it into rax / xmm0 and leave. */
    private int fuseRet(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 2)) {
            return 0;
        }
        boolean isF = line.get(0).text.equals("RET_FLOAT");
        if (!isF) {
            int n = parseSize(line.get(1).text);
            if (n <= 0 || n > 8) {
                return 0; // void (0) returns and wide values keep the stack form
            }
        }
        if (st.stack.size() != 1) {
            return 0;
        }
        Entry v = st.stack.get(0);
        if (!isValue(v)) {
            return 0;
        }
        st.anchor = line.get(0);
        st.risky = true;
        st.stack.remove(0);
        if (v.kind == Kind.M && v.size != 8) {
            st.emit("R_LD", String.valueOf(v.size), "%s", v.text);
            st.emit(isF ? "R_RETF" : "R_RET", "%s");
        } else {
            st.emit(isF ? "R_RETF" : "R_RET", operandOf(v));
        }
        if (v.kind == Kind.T) {
            st.freeTemp(v.temp);
        }
        return 1;
    }

    // ---- conditional branches ---------------------------------------------

    private int fuseCmpJmp(State st, List<BytecodeToken> line, List<List<BytecodeToken>> all, int idx) {
        if (!is(line, 1) || idx + 1 >= all.size()) {
            return 0;
        }
        List<BytecodeToken> next = all.get(idx + 1);
        if (next.size() != 2 || !next.get(0).text.equals("JMP")) {
            return 0;
        }
        int n = st.stack.size();
        if (n < 1) {
            return 0;
        }
        Entry c = st.stack.get(n - 1);
        if (c.kind != Kind.T) {
            return 0; // conditions are register-held 0/1 results
        }
        if (st.risky && n > 1) {
            return 0; // entries below the condition could only be written back with a plain stack flush
        }
        st.anchor = line.get(0);
        // Anything still deferred below the condition must be on the real stack before control can leave.
        // The condition's own temp is taken off the model first, so the flush leaves it alone.
        st.stack.remove(n - 1);
        st.flush(true);
        st.emit("R_BRF", "%t" + c.temp, next.get(1).text);
        st.freeTemp(c.temp);
        return 2;
    }
}
```

### FILE: src/main/java/caspien/lowerorder/StrengthReductionPass.java
```java
package caspien.lowerorder;

import java.util.ArrayList;
import java.util.List;

/**
 * Unsigned division and modulo by a constant power of two become a shift and a mask (always on; a pure code-quality
 * rewrite, it cannot change a result).
 *
 *   PUSH 8 K ; DIV_INT 8   ->   PUSH 8 n ; SHR 8        (K = 2^n, n >= 1)
 *   PUSH 8 K ; MOD_INT 8   ->   PUSH 8 K-1 ; BITS_AND 8  (K = 2^n, 2 <= K <= 2^31)
 *
 * Only the 8-byte operator on a literal divisor sitting directly before it, and only the unsigned mnemonics (the signed
 * SDIV_INT / SMOD_INT round toward zero and are left alone). A divisor of 1 and any other value are untouched, and
 * division by zero never reaches here (the language rejects a literal zero divisor).
 *
 * BITS_AND is a new stack-form mnemonic (pop two, and, push); the register-form pass turns both results into
 * R_BIN SHR / R_BIN BAND with an immediate.
 */
public class StrengthReductionPass implements OptimizationPass {

    @Override
    public String name() {
        return "strength-reduction";
    }

    @Override
    public PassResult run(List<List<BytecodeToken>> lines) {
        List<List<BytecodeToken>> out = new ArrayList<>(lines.size());
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            List<BytecodeToken> l = lines.get(i);
            if (i + 1 < lines.size() && isConstPush(l)) {
                List<BytecodeToken> next = lines.get(i + 1);
                if (next.size() == 2 && next.get(1).text.equals("8")) {
                    String m = next.get(0).text;
                    long k = Long.parseLong(l.get(2).text);
                    if (m.equals("DIV_INT") && k >= 2 && (k & (k - 1)) == 0) {
                        out.add(push(l, Long.numberOfTrailingZeros(k)));
                        out.add(mnem(next, "SHR"));
                        i++;
                        changed = true;
                        continue;
                    }
                    if (m.equals("MOD_INT") && k >= 2 && k <= (1L << 31) && (k & (k - 1)) == 0) {
                        out.add(push(l, k - 1));
                        out.add(mnem(next, "BITS_AND"));
                        i++;
                        changed = true;
                        continue;
                    }
                }
            }
            out.add(l);
        }
        return new PassResult(out, changed);
    }

    /** PUSH 8 <non-negative decimal literal> */
    private static boolean isConstPush(List<BytecodeToken> l) {
        if (l.size() != 3 || !l.get(0).text.equals("PUSH") || !l.get(1).text.equals("8")) {
            return false;
        }
        String v = l.get(2).text;
        if (v.isEmpty() || v.length() > 18) {
            return false;
        }
        for (int i = 0; i < v.length(); i++) {
            if (v.charAt(i) < '0' || v.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static List<BytecodeToken> push(List<BytecodeToken> old, long v) {
        List<BytecodeToken> n = new ArrayList<>(old);
        BytecodeToken t = old.get(2);
        n.set(2, new BytecodeToken(Long.toString(v), t.file, t.line, t.kind));
        return n;
    }

    private static List<BytecodeToken> mnem(List<BytecodeToken> old, String m) {
        List<BytecodeToken> n = new ArrayList<>(old);
        BytecodeToken t = old.get(0);
        n.set(0, new BytecodeToken(m, t.file, t.line, t.kind));
        return n;
    }
}
```

### FILE: src/main/java/caspien/lowerorder/StructTable.java
```java
package caspien.lowerorder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every struct's own member layout, read directly out of the program's
 * own STRUCT_START/STRUCT_MEMBER/STRUCT_END blocks -- the same
 * self-describing bytecode shape the codegen stage's own (now-removed,
 * see DropGlueGenerationPass's own header) recursive GT_DESTRUCT walk
 * used to depend on: "the assembly generator needs to check the struct
 * definition for the type to generate the appropriate routines," per
 * that stage's own gt_destruct_recursive_nested_test.caspien fixture
 * comment. Building this table needs nothing from the compiler project
 * itself -- struct shape is entirely present in the bytecode text
 * already, matching this project's own "no dependency on the compiler
 * or codegen stages" rule (README.md/CLAUDE.md).
 */
public class StructTable {

    public static class Member {
        public final String name;
        public final String canonicalType;

        public Member(String name, String canonicalType) {
            this.name = name;
            this.canonicalType = canonicalType;
        }
    }

    /**
     * One entry of a struct's own real, physical layout, in declared
     * order: either a real named member (`member` set, `paddingBytes`
     * 0), or a pure alignment gap the compiler itself already baked in
     * as a "STRUCT_PADDING n" line (`member` null, `paddingBytes` the
     * gap's own byte count) -- see BytecodeEmitter.emitStruct
     * (compiler project), which now computes and emits real natural-
     * alignment padding directly into a struct's own STRUCT_START/
     * STRUCT_MEMBER/STRUCT_END declaration, rather than leaving this
     * pass to (wrongly) assume a flat, gap-free member sum the way
     * `membersOf`'s own plain `Member` list always has. Never matched
     * against a member name and never itself sized by `SizeCalculator`
     * (its own byte count is already known outright) -- purely
     * something for an offset/size walk to add to its running total and
     * skip over.
     */
    public static final class LayoutEntry {
        public final Member member;     // null for a pure padding gap
        public final long paddingBytes; // 0 unless member == null

        private LayoutEntry(Member member, long paddingBytes) {
            this.member = member;
            this.paddingBytes = paddingBytes;
        }

        static LayoutEntry ofMember(Member m) {
            return new LayoutEntry(m, 0);
        }

        static LayoutEntry ofPadding(long bytes) {
            return new LayoutEntry(null, bytes);
        }
    }

    private final Map<String, List<Member>> structs = new LinkedHashMap<>();
    /** Same structs, same declared order, but including every "STRUCT_PADDING n" gap alongside the real members -- what a real offset/size walk (AddressLoweringPass's memberLocOf/structSizeOf) needs; `structs`/`membersOf` above stays real-members-only for every existing caller that only ever wanted named fields (drop-glue generation, clone generation, dotted-name type resolution, ...), none of which need to see a nameless padding gap at all. */
    private final Map<String, List<LayoutEntry>> layouts = new LinkedHashMap<>();
    private final Map<String, Boolean> ownsBearingCache = new HashMap<>();

    public static StructTable read(List<List<BytecodeToken>> lines) {
        StructTable table = new StructTable();
        String currentStruct = null;
        List<Member> currentMembers = null;
        List<LayoutEntry> currentLayout = null;
        for (List<BytecodeToken> line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            String head = line.get(0).text;
            if (head.equals("STRUCT_START") && line.size() >= 2) {
                currentStruct = line.get(1).text;
                currentMembers = new ArrayList<>();
                currentLayout = new ArrayList<>();
            } else if (head.equals("STRUCT_MEMBER") && currentMembers != null && line.size() >= 3) {
                Member m = new Member(line.get(1).text, line.get(2).text);
                currentMembers.add(m);
                currentLayout.add(LayoutEntry.ofMember(m));
            } else if (head.equals("STRUCT_PADDING") && currentLayout != null && line.size() >= 2) {
                currentLayout.add(LayoutEntry.ofPadding(Long.parseLong(line.get(1).text)));
            } else if (head.equals("STRUCT_END") && currentStruct != null) {
                table.structs.put(currentStruct, currentMembers);
                table.layouts.put(currentStruct, currentLayout);
                currentStruct = null;
                currentMembers = null;
                currentLayout = null;
            }
        }
        return table;
    }

    public boolean hasStruct(String name) {
        return structs.containsKey(name);
    }

    public List<Member> membersOf(String structName) {
        return structs.get(structName);
    }

    /** `structName`'s own real physical layout, real members interspersed with any real "STRUCT_PADDING" gaps, in declared order -- see `LayoutEntry`'s own doc comment. Null for the same cases `membersOf` returns null for. */
    public List<LayoutEntry> layoutOf(String structName) {
        return layouts.get(structName);
    }

    /**
     * Every real struct name this table knows about (every
     * "STRUCT_START name ... STRUCT_END" block this bytecode declared),
     * in declared order. Used by `AddressLoweringPass.memberLocOf`'s
     * "extends" fallback -- this bytecode format carries no explicit
     * "extends" relationship at all (a child struct's own STRUCT_START
     * block is already fully flattened, parent fields then its own,
     * with nothing marking which member came from which -- see
     * `caspien-compiler`'s own CLAUDE.md), so recovering "does S extend
     * Base" has to be done structurally, by comparing two structs' own
     * real layouts directly, rather than looked up from any stored
     * relationship -- this accessor is what makes that scan possible.
     */
    public Set<String> allStructNames() {
        return structs.keySet();
    }

    /**
     * True when a value of this base type (a struct name, a
     * "dynarray(elem)", or an "elem[N]" fixed array) either is itself
     * owns-storage somewhere inside it, or wraps/contains one -- the
     * exact test that decides whether this shape needs a generated
     * drop-glue routine at all (DropGlueGenerationPass) or can be left
     * as the bare, single GT_DESTRUCT the compiler already emits, with
     * nothing further to walk.
     *
     * Memoized -- struct types form a DAG, never a cycle
     * (self-referential/recursive struct shapes are illegal in this
     * language), so plain recursion with a cache is safe and always
     * terminates. The "seed false before descending" step below is a
     * purely defensive belt-and-braces guard against that assumption
     * ever being wrong; it should never actually be exercised.
     */
    public boolean isOwnsBearing(String baseType) {
        Boolean cached = ownsBearingCache.get(baseType);
        if (cached != null) {
            return cached;
        }
        ownsBearingCache.put(baseType, false);
        boolean result = computeOwnsBearing(baseType);
        ownsBearingCache.put(baseType, result);
        return result;
    }

    private boolean computeOwnsBearing(String baseType) {
        String dynElem = CanonicalType.dynArrayElementTypeOf(baseType);
        if (dynElem != null) {
            CanonicalType elemType = CanonicalType.parse(dynElem);
            return elemType.isOwnsStorage() || isOwnsBearing(elemType.baseType);
        }
        String arrElem = CanonicalType.fixedArrayElementTypeOf(baseType);
        if (arrElem != null) {
            CanonicalType elemType = CanonicalType.parse(arrElem);
            return elemType.isOwnsStorage() || isOwnsBearing(elemType.baseType);
        }
        List<Member> members = structs.get(baseType);
        if (members == null) {
            return false; // a primitive, or any other type this table has no members for
        }
        for (Member m : members) {
            CanonicalType t = CanonicalType.parse(m.canonicalType);
            if (t.isOwnsStorage()) {
                return true; // this member alone means the struct needs a routine, regardless of what it itself points to
            }
            if (t.storage == null && isOwnsBearing(t.baseType)) {
                return true; // an inline member with owns descendants further down
            }
        }
        return false;
    }
}
```
