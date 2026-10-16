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

Verified: `stdlib/string.caspien` now goes through all four stages and links; the space literal now lowers to `PUSH 1 ' '` like every other char literal; Hello World output is unchanged. No regression fixtures (`examples/`) were available to sweep.

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
codebase that held all three pipeline stages (compiler,
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
