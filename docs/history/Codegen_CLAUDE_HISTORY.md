# CLAUDE.md -- caspien-codegen Project Memory

Read this first in any new conversation thread before making changes.
This file describes the **current state** of this project only.

## New: x86 code for `BITS_AND` / `BITS_XOR` / `BITS_NOT`, one shift rule for `SHL` / `SHR` / `SAR` in stack and register form

Full account in the root `CLAUDE.md` (top section). `X86Backend`: stack-form cases `BITS_AND` / `BITS_XOR` / `BITS_OR` (and/xor/or on the zero-extended 8-byte words, narrow result re-zero-extended), `BITS_NOT` (`not`, then zero-extend to the operand width), `SHL` / `SHR` / `SAR` through `emitShiftCore`; register form `rfBin` (BAND/BOR/BXOR, commutative operand swap, memory/immediate operands, narrow results zero-extended, `narrowCmp` excludes these ops), `rfShift`, `rfUn` (BNOT). Shift semantics are independent of the hardware count masking: constant counts are resolved at compile time; variable counts use `%cl` (`rcx` is saved with push/pop because it may hold an already-loaded argument; `rdx` is not touched; `r13` is the scratch where the stack form must stash `rcx`) followed by `cmp $64,%rcx; sbb %rcx,%rcx; and %rcx,%rax` for `SHL`/`SHR` and a clamp of the count to 63 (`mov $63,%r15; cmp %r15,%rcx; cmova %r15,%rcx`) for `SAR`; narrow operands are zero- or sign-extended to 64 bits first and the count is the unsigned value of its own width. The Linux and windows_gnu paths share the AT&T code; the MASM/Intel path (`windows`) has the same sequences in Intel form. Verification: Linux execution of the tests above; the windows_gnu output of `bits_ops_test.caspien` (everything on and everything off) assembles and links with `x86_64-w64-mingw32-gcc` but was not run (no Wine here); the Intel (MASM) output of a small program was only sanity-assembled with GNU as in intel-syntax mode (the existing `movzx rax, dword [..]` loads of the MASM path are not valid there and are outside this change), real MASM and real Windows execution were NOT run.

## Fixed: `DOT` after `LOOKUP_DYN` / pointer-base `LOOKUP_ARRAY` of a struct element

Full account in the root `CLAUDE.md` (top section). `case "LOOKUP_DYN"` (value form) and the `pendingBlockSize == 0` pointer-base branch of `case "LOOKUP_ARRAY"` now set `lastValueBlockSize = elemSize` when `elemSize > 8`, so the `PUSH_FIELDNAME`/`DOT` that follows finds the pushed element block (it used to emit `TODO(codegen): DOT with no preceding pushed value` and push 0). Test: `tests/dynarray_struct_test.caspien`.

## New: `R_LEA` with an optional displacement

Full account in the root `CLAUDE.md` (top section). `case "R_LEA"` passes an optional 6th operand (a byte displacement) to `rfLea(dst, base, idx, scale, extra)`: an immediate index gives `imm*scale+extra`; a scaled register index emits `extra(%base,%idx,scale)` (Intel `[b+x*scale+extra]`) when `extra` fits imm32, else `lea` plus `rfAddImm`; the imul path (non-power-of-two scale) adds `extra` with `rfAddImm`. Used for dynarray element addressing (16-byte header: `[len][cap][elements]`). Rebuild `Codegen/out` after editing (a stale class silently emits `# TODO(codegen)` for unknown `R_*` lines). Linux-run only; the Intel form is unexecuted.

## New: r8/r9/r10 as variable registers `%v3..%v5` (step 5)

Full account in the root `CLAUDE.md` (top section). `RF_VAR_REGS = {r13, r14, r12, r8, r9, r10}`; `rfReg` sets `rfVarUsed` only for index < 3 (r8-r10 need no callee-saved save, `finishCalleeSaved` ignores them). The promotion pass guarantees these registers are only assigned to variables that are not live across any call, `INVOKE`, extern call, `SLEEP` or call bracket, and that r8/r9 are not used in a function with a 3rd or 4th argument (win64 argument registers) and r10 not in a function with a float-result stash. The backend itself only touches r8/r9 through `argReg` and r10 in the NEW repack (a blocked function), INVOKE and `PUSH_RET_FLOAT`/`R_GETRETF`. Linux-run only; windows_gnu assembles and links but was not run.

## New: float constant pool (`.LFC<k>`, AT&T targets) and `R_LDXI`/`R_STXI`

Full account in the root `CLAUDE.md` (top section). `floatPool` (LinkedHashMap, key `"n:bits"`, cleared in `generate()`) is filled by `floatPoolLabel`/`floatPoolMem` from `rfLoadXmm` (immediate operand: pool load, or `xorps` for zero), `rfFloatX`/`rfFloat` (immediate b operand as a memory operand) and `R_GTOX` (immediate path); `emitFloatPool()` runs before `emitFooter()` and writes `.section .rodata` (`.rdata` on windows_gnu), `.p2align 2|3`, `.long`/`.quad`. `floatPoolOn()` is `!isWindows()` (MASM keeps the old `movabs`+`movq`). `R_LDXI n %xK &sym %vK scale` and `R_STXI n &sym %vK scale %xK` (`rfLoadXIndexed`/`rfStoreXIndexed`): require scale == n (4 or 8), a global base and a `%v` index; one `lea sym(%rip), %r15` (`RF_SCRATCH`), then `movss`/`movsd` through `(r15,%vK,scale)` via `rfMemOperand` (`rfIdxReg`/`rfIdxScale`). Rebuild `Codegen/out` after editing: a stale class silently emits `# TODO(codegen)` comments for an unknown `R_*` line.

## New: `R_LDI` and `R_STI` (indexed global access)

`R_LDI n %tD &sym %vK scale` = `lea sym(%rip) -> tD` then the sized load through `(tD,%vK,scale)`; `R_STI n &sym %vK scale src` = `lea sym(%rip) -> r15` then the store (immediate or register) through `(r15,%vK,scale)`. Both go through `rfMemOperand`, which `loadSizedFromAddr` and `storeSizedToAddr` (non-odd sizes) now use for their memory operand; `rfIdxReg`/`rfIdxScale` are set only around these two calls, so every other load/store is unchanged. Sizes 1/2/4/8 and scale 1/2/4/8 are accepted, the index must be a `%v` register; the LowerOrderGenerator only produces size 8 / scale 8, so the narrow forms and the Intel forms (`[r15+r13*8]`) are unexecuted.

## New: `R_BRC`, `BITS_AND`, `R_BIN SHR/BAND`, word-wise `assignConstructionBlock`, `%v2` = r12

`R_BRC OP 8 a b @L` (`rfBrc`) emits `cmp` plus the conditional jump for the INVERSE condition (EQ->jne, NEQ->je, LT->jae, LT_EQ->ja, GT->jbe, GT_EQ->jb, SLT->jge, SLT_EQ->jg, SGT->jle, SGT_EQ->jl). An immediate first operand is swapped to the second position with the condition mirrored; two frame slots go through r15. `BITS_AND` is a stack-form case (pop rbx, rax; and; push) and `R_BIN` handles `SHR` (immediate count) and `BAND`. `assignConstructionBlock(size)` copies a range/struct construction of 8..64 bytes (multiple of 8) word by word through rdi (`mov r15,[rsp+size]`, per word `mov rdi,[rsp+o]; mov [r15+o],rdi`, `add rsp,size+8`) instead of `rep movsb`; other sizes keep the old path. `RF_VAR_REGS` is `{r13, r14, r12}`: `%v2` is r12, which is also temp 3, so the LowerOrderGenerator only hands it out to functions that never use `%t3`.

## Fix: 3/5/6/7-byte rows (construction and reads), `DOT` of a >8-byte field that is not a whole number of words, `assignBlock` of a non-word size

Three pre-existing bugs, one cause: the block layout is word k at rsp + (T-1-k)*8 with natural byte order inside a word, and the code assumed sizes that are 1/2/4/8 bytes or whole words.
(1) Odd sizes (3/5/6/7). No x86 instruction stores these widths, and `movSuffix`/`sizedReg` fell back to an 8-byte move, writing 1-5 bytes too many (past the end of a packed construction image that was the pointer sitting just above it). `isOddSize`, `storeOddSize` (a 4/2/1 sequence, the source rotated with `ror` between the pieces and rotated back with `rol`), `storePiece` and `rotReg` are used by `storeSizedToAddr`, `storeSizedToFrame`, `storeConstructionOrPlainPush` (both the first-store branch and the non-zero-offset branch, which had its own 8-byte mov) and `assignBlock`. `pushBlockFromFrameConstructionAware` copies piecewise through rax: inside a construction run each field is stored at its final place BELOW the current rsp, so `push rcx` or the `rep movsb` block copy (rdi/rsi/rcx) corrupted the fields already stored. `LOOKUP_ARRAY`: an odd-size element that straddles two words is read with `shrd`; an odd-size element of a small array held in one register is masked with shl/shr (a plain `and` immediate has no 3/5/6/7-byte form).
(2) `extractWordsFromReversedBlock(totalWords, size)`: the shared word extractor (result word j = `shrd` of source words w+j and w+j+1, count = byte offset inside the word * 8, then rsp raised by (totalWords - elemWords) * 8). Inputs: rax = address of the first byte's word, r14 = byte offset inside that word; r13 keeps rcx. Used by the multi-word `LOOKUP_ARRAY` branch (which now calls it instead of its own copy) and by `DOT`.
(3) `DOT` of a field wider than 8 bytes (`size > 8`): the address is computed like a `LOOKUP` (word = offset / 8 counted from the top, byte = offset % 8) and `extractWordsFromReversedBlock` builds the result. It used to shrink by `total*8 - size` bytes (rsp off by up to 7 for a 10-, 12- or 20-byte field); reads of fields that sit mid-word returned wrong bytes.
(4) `assignBlock(size)`: the destination offset of the word popped i-th was `size - 8 - 8i`, right only for whole-word sizes; it is now word `k = words-1-i` at byte `8k`, and the last partial word is stored with its exact byte count (`storePiece`/`storeOddSize`). A 12- or 20-byte array copy (`let a = mut s.v`) used to put its words 4 bytes too low.
Tests: `tests/array_row_odd_test.caspien` (14), `tests/dot_wide_field_test.caspien` (11), `tests/array_row_read_test.caspien` (15). The Intel forms of `storeOddSize`, the `shrd` blocks and the piecewise copies ran under Wine (`windows_gnu` is the Intel-syntax path); the MASM target is unverified.

## Fix: `LOOKUP_ARRAY` of a multi-word element (two bugs: block shrink, and rows that start mid-word)

Block layout on the stack: word k (bytes 8k..8k+7) at rsp + (T-1-k)*8, bytes natural inside a word. (1) The element-wider-than-8 branch did `add rsp, totalWords*8 - elemSize`; for a 12-byte row (`u32[3]` in a `u32[3][2]`) rsp ended up 4 bytes off and the copy wrote past the old block over the pending store address (`let ok = mut grid[1][2]` segfaulted; as a call argument it went unnoticed). (2) Row 1 of that array starts at byte 12, so its bytes span two reversed words and were read as if contiguous: `grid[1][1]` was wrong. Now each result word j = `shrd` of source word w+j and w+j+1 (word w+j+1 sits 8 bytes below; count 0 = aligned = unchanged), stored to the slot of source word j (rsp + (T-1-j)*8, ascending j never overwrites a word still to be read), then rsp += (T-E)*8. rcx (shift count, maybe a loaded argument) is stashed in r13; r11 holds the word address, r14 the shift/temp (LOOKUP_ARRAY is already an r13/r14 user). Test: `tests/array_row_read_test.caspien`. Not fixed: 3/5/6/7-byte rows built from row variables are stored wrongly (construction), and the `DOT` >8-byte non-word-multiple field shrink has the same pattern; see the root CLAUDE.md.

## Update: `GT_UNWIND MSG` carries the error message up a frame

`GT_UNWIND` with the operand `MSG` (emitted by the compiler in any program that uses `throw`) loads this frame's `gt_error_message` slot (rbp-16, right behind `gt_routine_address` at rbp-8) into rax after the callee-saved restores and before the epilogue, and stores it into the caller's rbp-16 after `pop rbp`, then loads the caller's rbp-8 and jumps as before. Plain `GT_UNWIND` (no throw in the program) is unchanged. Test: `tests/throw_message_test.caspien`.

## Update: `DOT` narrow-field address and `LOOKUP_ARRAY` `t8`/`ra`

`DOT` with size <= 8: the byte at struct offset X of a pushed block of W words is at `rsp + (W-1-X/8)*8 + X%8` (the old formula was only right for whole words). `LOOKUP_ARRAY` parses `t8` (pendingBlockSize = -8 when the target is an 8-byte by-value array) and `ra` (the element result is itself a small array: `lastValueBlockSize = -elemSize`). Intel forms mirror AT&T but are unexecuted; the Intel backend as a whole emits mixed NASM/MASM operand syntax and was not assemblable here (see root `CLAUDE.md`). Test `tests/struct_literal_nested_test.caspien`.

## New: stack struct literal repack (`emitAssignRepack`)

Full account in the root `CLAUDE.md`. An `ASSIGN size size size m8 m4 p4 ...` line with layout tokens after the three sizes (added by LowerOrderGenerator for a stack struct literal) that is not on the packed fast path (`constructionScopedAssign`) is repacked field by field from the 8-byte-word push image into the address sitting above it (`emitAssignRepack`, `copyBytes`); it uses only `%r10`/`%r11`/`%rax` and pops the words and the address. `a<elemBytes>x<n>` tokens (element-wise narrow arrays, n whole words) are handled in both `emitAssignRepack` and `emitNewRepack`. Also fixed: `DEREF` records the pushed aggregate's size (`lastValueBlockSize`) so `LOOKUP_ARRAY` can index an array read through a pointer; `LOOKUP_ARRAY`'s small-array shift and `SHL`/`SHR`/`SAR` save and restore `%rcx` (an argument register).

## New: float temporary registers `%y0..%y3` (SysV xmm4-7 / win64 xmm12-15) and `R_XMOV`

Full account in the root `CLAUDE.md`. `rfIsXvar`/`xvReg` treat `%y` like a variable register (`yPhys`, `YT_COUNT` = 4), so every float `R_*` instruction that accepts `%x` operands accepts `%y`; `R_XMOV dst src` copies between them. A `%y` register is never live across a call (SysV needs no spill; win64 xmm12-15 are saved by `finishCalleeSaved`). Intel/MASM forms unexecuted.

## New: float variable registers `%x0..%x5` (xmm8-13 SysV / xmm6-11 win64)

Full account in the root `CLAUDE.md`. `X86Backend`: `xvHome`/`xvPhys`/`xvReg`, cases `R_XVAR`, `R_XRELOAD`, `R_XTOG`, `R_GTOX`, `R_LDX`, `R_STX`, `R_FBINX`, `R_GETRETF`, `R_POPX`; SysV `emitCallByName`/`emitCallIndirect` spill/reload all xvars around every call (`xvSpillAll`/`xvReloadAll`); `finishCalleeSaved` already saves xmm6-15 on win64. `PROMOTE_F32_TO_F64` now uses `rfXmmA()` as scratch (was xmm8, which is an xvar on SysV). Intel/MASM forms unexecuted.

## New: variable registers `%v0`/`%v1` (= `r13`/`r14`) and `R_SETV`

Full account in the root `CLAUDE.md` (top section). The LowerOrderGenerator's `RegVarPromotionPass` can rename a frame slot operand in a register-form line to `%v0`/`%v1`. `X86Backend`: `rfReg` maps them to `r13`/`r14` (`RF_VAR_REGS`), `rfIsTemp` accepts them, a variable register as the index of `R_LEA` with a non power-of-2 scale is first copied to `r15`, `R_RMW` has a variable-register branch, `R_SETV n %vK src` truncates `src` to n bytes and zero-extends into the variable register (the invariant is "zero-extended to 64 bits", like a temp). r13/r14 are also used internally by `NEW*`, `CLONE`, `RESIZE*`, wide `DOT`, `LOOKUP_ARRAY` (set `rfClobberSeen`); the lowering pass never promotes in such functions, and `FUNC_END` throws `codegen internal error: '<fn>' keeps variables in r13/r14 but also has an instruction that uses them as scratch` if that guarantee is ever broken. Saving/restoring r13/r14 is the existing `finishCalleeSaved` (it scans the function text for them). Intel/MASM forms mirror the AT&T ones and are unexecuted.

## New: callee-saved registers are saved/restored per function (`finishCalleeSaved`, `CSR_MARK`)

Full account in the root `CLAUDE.md` (top section). Every exit (`emitFunctionEpilogue`, `GT_UNWIND`) emits `@@CSR@@`; `FUNC_END` scans the function's text, grows the single `ALLOC` by the save area (below the locals), saves right after it, and replaces each mark with the restores. `isWinAbi()` selects the win64 set (adds `rdi`, `rsi`, `xmm6-15`); `isWindows()` only selects Intel syntax. Checked by `tests/check_callee_saved.py` and `tests/callee_saved_probe.sh`.

## New: register-form instructions, stage 2 (`R_LD`, `R_ST`, `R_LEA`, `R_FBIN`, `R_FCMP`, `R_RMW`, `R_ARG`, `R_ARGA`, `R_FARG`, `R_RET`, `R_RETF`)

Full account in the root `CLAUDE.md` (top section). New `X86Backend` cases after `R_PUSHA` (which now also accepts `&sym`) and `rf*` helpers before `mangleLabel` (`rfLoad`, `rfStore`/`rfTrunc`, `rfLea`, `rfLoadXmm`/`rfGprToXmm`/`rfXmmToGpr`, `rfFloat`, `rfSetcc`, `rfRmw`, `rfExtend`, `rfArgReg`, ...). Float ops go through xmm scratch registers (SysV xmm14/15, win64 xmm4/5) and back to a GPR; narrow loads zero-extend, narrow stores truncate, narrow compares extend by signedness. `R_ARG*` call `noteArgRegLoaded`; `R_RET`/`R_RETF` end with `emitFunctionEpilogue`. Intel (MASM) forms mirror the AT&T ones and are unexecuted.

## New: register-form instructions (phase 1) (`R_MOV`, `R_BIN`, `R_UN`, `R_BRF`, `R_PUSH`, `R_PUSHA`)

Full account in the root `CLAUDE.md` (top section). `X86Backend` gained these cases (before `case "ADD_INT":`) plus the `rf*` helpers: temps `%t0..%t3` -> `rax, rbx, r11, r12`, `%s` -> `r15` (scratch, for `dst == b` on a non-commutative op and for immediates that do not fit imm32). Compares are `cmp` + `setcc` + `movzbq`, bit-identical to the stack forms; AND/OR at size 1 use byte ops. `R_BRF` is `test` + `je`. `R_PUSH`/`R_PUSHA` go through `pushReg` (stack-delta tracking only understands that form). Errors are `IllegalStateException`. Verified on `linux` and `windows_gnu` (Wine); the Intel/MASM (`windows`) forms mirror the AT&T ones and were NOT executed.

## New: MIN/-1 guard, `SAR`, zero-extended narrow compares

`X86Backend`: `SDIV_INT`/`SMOD_INT` compare the sign-extended divisor with -1 and negate (DIV) / zero (MOD) instead of `idiv` (no SIGFPE on MIN/-1); new `SAR size` case (sign-extend, `sar`); new `zeroExtendReg` used by the unsigned compares, `DIV_INT`/`MOD_INT` and `SHL`/`SHR` (and to re-normalise shift results). Intel/win64 branches mirror AT&T and are not executed.

## New: integer tightening (`TRUNC`, signed compare/divide mnemonics)

Full account in the root `CLAUDE.md` (top section). What changed in THIS component:
- New `SDIV_INT`/`SMOD_INT size` (sign-extend both operands with `signExtendReg`, `cqto`/`idivq`; remainder takes the dividend's sign) and `SLT_INT`/`SLT_EQ_INT`/`SGT_INT`/`SGT_EQ_INT size` (sign-extend, `cmpq`, `setl`/`setle`/`setg`/`setge`). The unsigned `DIV_INT`/`MOD_INT`/`LT_INT`/... are unchanged.
- New `TRUNC srcSize dstSize` (keeps the low dstSize bytes, zero-fills the rest of the stack word).
- `SEXT` now clears the sign bits above the destination width when it is under 8 bytes.
- `PUSH` of an integer literal above 2^63-1 (`18446744073709551615`) no longer throws in `Long.parseLong` (`parseUnsignedLong`).
- Windows/Intel branches mirror the AT&T ones and were not executed.

## Fixed: stack-passed arguments (7th and later integer arguments on Linux/SysV, 5th and later on win64) arrived in the wrong order

Found while fixing nested call arguments (`eight(1,2,3,4,5,6,7,8)` saw `g` and `h` swapped; a `printf` with eight `%llu` printed `7 8 6` for the last three); fixed on request ("yes please fix that").

**The bug (Codegen, `X86Backend`).** Arguments are evaluated and pushed left-to-right, so at the `call` the first stack-passed word was deepest and the last was on top. Both conventions (and every callee, Caspien's own reading `$16`, `$24`, ... and C's `printf`) require the FIRST stack-passed argument at the LOWEST address, immediately above the return address. With two or more stack-passed words every one of them was read from the mirrored slot. One stack-passed argument (the only case the older overflow fixtures covered) looked fine, which is how it went unnoticed.

**The fix.** `resolveStackArgBytesAndCall` now calls the new `reverseStackArgWords(stackArgBytes)` after every argument has been produced and before the win64 shadow-space reservation and the `call`: an in-place reversal of the stack-passed 8-byte slots (scratch: `rax` and `r11`; `r10`, which may hold an `INVOKE` target, is untouched). Evaluation order stays strictly left-to-right, no bytecode or front-end change was needed, and calls with fewer than two stack-passed words emit exactly what they did before.

**Verified (Linux/sysv_x64).** `tests/stack_args_test.caspien` (new): 7, 8, 9 and 10-argument Caspien calls (1 to 4 stack-passed words), calls in the stack-passed slots and in every slot, `printf` with ten integer varargs and with eight call-result varargs, a mixed int/float call with stack-passed words of both kinds, and an 11-`f32` call. The program printed wrong values before this change. All of `tests/stdlib_test.caspien`, `tests/nested_call_args_test.caspien`, Hello World and the String/DynamicArray/HashMap/generics/harness programs still give the same output. Not executed: the win64 target (the reversal is emitted in Intel form for it; no mingw/Wine here).

## Fixed: a call used as an argument of another call no longer clobbers the outer call's already-loaded argument registers

Found while writing `tests/stdlib_test.caspien` (check names printed as garbage when a check's second argument was itself a call), reproduced in a small program, and fixed on request ("yes fix it like this please").

**The bug (Codegen, `X86Backend`).** Each argument is popped into its ABI register the moment it is evaluated (`POP ARGn`). `show("nested", two(1, 2), 9)` therefore loaded the string into `%rdi`, then evaluated `two(1, 2)`, whose own argument setup overwrote `%rdi`/`%rsi`; the result was popped into `%rsi`, and `show` was finally called with `%rdi` = 1. `show("x", 9, two(1, 2))` lost the `9` the same way. Any call inside another call's argument list was affected (user functions, methods, externs such as `printf("%llu", f(x))`); the result was garbage or a segfault. The bytecode was already correct at every earlier stage.

**The fix.** `X86Backend` now records, per open `CC_START`/`CC_END` bracket, which argument registers its `POP ARGn`/`POP FARGn` lines have loaded (`loadedArgRegsStack`, `noteArgRegLoaded`). A `CC_START` that is nested inside another call pushes the enclosing call's loaded registers (float-bank registers through `r11`) before opening its own bracket; the matching `CC_END` pops them back in reverse order, leaving `rax` (an integer result) untouched. The inner call's result is pushed after the restore and popped into its own argument register as before. The pushes and pops cancel exactly, so the enclosing call's stack-passed-byte accounting is unchanged. One special case: xmm0 is both float argument 0 and the float return register, so if xmm0 has to be restored the possible float result is first stashed in `r10` and the next `PUSH_RET_FLOAT` reads it from there (`floatResultInR10`).

**Verified (Linux/sysv_x64).** `tests/nested_call_args_test.caspien` (new) covers a call in the first, middle and last argument slot, two calls in one argument list, deep nesting, calls in every slot of a six-argument call, a string argument before a nested call, a `printf` with call varargs, and floats (an `f32` argument before a nested int call, and nested float calls). With the old Codegen that program crashes; now every case passes. `tests/stdlib_test.caspien` was switched back to the natural `check("name", d.get(d, i) == 20)` style and all its checks pass. Hello World, and the String, DynamicArray, HashMap, generics, overloading and harness programs give the same output as before. The Windows target (Intel/win64 register set) was only compiled to assembly, never executed: no mingw/Wine here.

**Found here and fixed separately:** stack-passed arguments arrived in the wrong order for calls with more than six integer arguments (see the section above this one).

## Fixed: heap `new` construction layout, `RESIZE` fill width, drop-glue calls, wide dynarray element reads (String / DynamicArray / HashMap now run)

Full account in the root `CLAUDE.md` (top section). What changed in THIS component (`X86Backend.java`):
- `NEW <size> m.. p..`: the LowerOrderGenerator now appends the struct layout to `NEW`. `precomputeConstructionOffsets` records the `NEW` lines it packed in `packedNewLines`; any other `NEW` with a descriptor goes to `emitNewRepack`, which copies each chunk out of the reversed stack image into the heap buffer at its true offset. A `NEW` with no descriptor, or an already-packed one, is emitted exactly as before (Hello World assembly is byte-identical).
- `RESIZE`: the fill store is `elemSize` bytes wide (it was always 8, overrunning a `char` array); a fill wider than a word is the whole pushed block, copied per element by `emitResizeWideFillCopy`. `sizedReg` now has low forms for `r8`-`r15` (`r14b`/`r14w`/`r14d`).
- `CALL __drop_*` outside a `CC_START` bracket goes through `emitDropGlueCall` (the generic aligned call put its alignment slot between the pushed pointer and the return address, so the routine read garbage).
- `pushSizedOrBlockFromAddr` loads through `r11` (it loaded into `rax`, which `LOOKUP_DYN` also uses as the address register, so any dynarray element wider than 8 bytes read back garbage).
- Not verified on the Windows targets (no mingw/Wine here); the Intel/win64 branches mirror the AT&T ones.

## Fixed: a space char literal (`' '`) was split into two tokens by every stage's bytecode parser -- Codegen crashed on it

Found while checking whether `stdlib/string.caspien` compiles end to end: `String.empty()` uses `mut ' '`, which reaches the bytecode as `PUSH ' ' indeterminate_char`. The Optimizer's, LowerOrderGenerator's and Codegen's `BytecodeParser` are identical copies that split a line on whitespace and only kept a `"..."` string whole, so `' '` became two stray `'` tokens. In LowerOrderGenerator that meant the line was never lowered (every other char literal became e.g. `PUSH 1 '\0'`, this one stayed `PUSH ' ' indeterminate_char`), and in Codegen `X86Backend` then died with `NumberFormatException: For input string: "'"`. Result: any program importing `stdlib/string.caspien` could not be linked, even if it never used `String`.

Fix, applied identically to all three `BytecodeParser.parseLine` copies: a token starting with `'` that has the exact char-literal shape `BytecodeEmitter.escapeForBytecode` produces (quote, one character or backslash-plus-one, closing quote, ending the token) is now kept as ONE token, the same way a quoted string is. Char literals without whitespace produce exactly the same single token as before, so only the `' '` case changes.

Verified: `stdlib/string.caspien` now goes through all four stages and links; the space literal now lowers to `PUSH 1 ' '` like every other char literal; Hello World output is unchanged. No regression fixtures (`examples/`) were available to sweep.

## Known, NOT fixed here (now rejected upstream at compile time instead): an ordinary struct-by-value function *parameter* segfaults at runtime

Found (and confirmed with a minimal, unrelated repro,
`struct_arg_gt16_probe.caspien` in the sibling `caspien-compiler`
project's own `examples/`) while that project was extending its
constructors feature to generic structs -- see that project's own
CLAUDE.md, "A real, pre-existing `caspien-codegen` bug this surfaced,"
for the full account, including just how wide this actually turned out
to be: since every real struct always carries a hidden 8-byte classId
field in addition to its own declared members, the smallest struct with
even one real member is already 16 bytes -- wider than the one 8-byte
register/stack word this pipeline can actually transfer for a
parameter. In practice this meant most existing struct **methods** hit
it too (a method's own by-value `_self` receiver is exactly this
shape), confirmed by actually running several already-existing,
previously-"passing" (compile-only) fixtures and watching them segfault.
Nothing about the original repro involves a generic, a constructor, or
even an `impl` block, though: a plain `func takesRange(r: mut Range)
void {...}` where `Range` is a 24-byte struct, called with an ordinary
already-built struct value, compiles and links cleanly but segfaults the
moment the callee tries to use `r`.

The sibling `caspien-compiler` project now rejects this shape outright
at compile time (`TypeChecker.buildFuncInfo`, a new check right next to
its existing array-parameter-storage-modifier check) rather than let it
reach this stage and silently miscompile -- so as of that change, this
bug can no longer actually be triggered through a normal compile. That
check is, correctly, **unconditional** -- any plain (non-pointer)
struct-typed parameter is rejected regardless of size, not just ones
above some byte threshold, since passing a struct on the stack rather
than by pointer is illegal in the language at all, independent of how
big the struct is (an earlier draft of the check there used a size
threshold instead, on the mistaken theory that a small-enough struct
could legally be passed by value; that was wrong and has been
corrected). This project's own root cause, below, is unchanged and
still real; it just isn't reachable any more until someone implements
the missing calling-convention support and the front-end's rejection is
loosened to match.

Root cause, in `X86Backend`: `case "PUSH"`'s `operand.startsWith("ARG")`
branch (and the equivalent `FARG` branch) unconditionally does `movq
%argReg, %rax` and then pushes/stores that single register's worth as
*the whole value*, regardless of the line's own declared `size` -- correct
whenever a parameter genuinely fits in one register (a scalar, a
pointer), silently wrong the moment `size > 8`: this toolchain's own
calling-convention lowering apparently leaves a wider-than-one-word
struct argument's own register/stack slot holding a *pointer to* the
real value (the same "invisible reference" shape the RVO *return*
convention already implements deliberately, elsewhere), never
dereferenced here. `case "ASSIGN"`'s `assignBlock` fallback (used
whenever a `>8`-byte value wasn't recognized as a construction-run
member) then reads its own destination address from `words * 8(%rsp)`,
assuming `words = ceil(size/8)` real words were pushed ahead of it --
but only one word (the raw, undereferenced pointer) actually was, so
this reads someone else's stack memory as if it were the destination
address and writes through it: a wild pointer write.

**Not fixed** -- this is a real, separate feature gap in this project's
own calling-convention support (there is currently no "a struct wider
than a register passes by hidden reference, and any later `PUSH ARGn`/
`ASSIGN` of it has to re-dereference rather than re-push" convention
implemented anywhere here), not a small local bug, and fixing it
properly would mean deciding, and then implementing end to end, exactly
that convention (likely needing a cooperating change in the sibling
`caspien-lowerordergenerator` project too, wherever a struct-by-value
parameter's own word-count/type gets decided). Flagged here rather than
silently worked around; the `caspien-compiler` project's own new generic-
constructor test fixture deliberately avoids this shape (builds its own
`>8`-byte struct member via a nested struct literal inside the
constructor body, rather than accepting it as an already-built by-value
parameter) specifically to route around this gap while still verifying
its own actual feature.

## Fixed: `PUSH`'s construction-run detection never applied to a `>8`-byte single-entry push, silently byte-reversing it

Found via real execution while the sibling `caspien-compiler` project
was implementing constructors (see that project's own CLAUDE.md,
"New: constructors," for the full front-end feature and how this bug
was found): `?new MyClass(11,13)`, where `MyClass`'s own constructor
first builds the value into a real stack slot (via the pre-existing
struct-RVO call sequence) and `new` then pushes that *already-fully-
built* value whole and heap-allocates a copy of it, compiled and ran
without crashing but printed `y=1` (the struct's own hidden classId)
instead of `y=13`; `x=11` came out correct only because it happens to
be the symmetric middle field of a 3-word struct.

**Root cause**: `case "NEW"`'s own straight, order-preserving `rep
movsb` copy (see its own doc comment) is correct only when the bytes
already sitting at the top of the real stack are in natural, ascending-
offset order -- true for an ordinary `new X{...}` struct literal, whose
individual field pushes each go through `storeConstructionOrPlainPush`
(consulting `pushStoreOffsetByLine`, populated by
`precomputeConstructionOffsets`/`populateDirectMembers`, to land each
field at its own true, natural position). But `precomputeConstructionOffsets`
only ever routes a **`<=8`-byte** `"PUSH size $offset"` line through
that mechanism (`case "PUSH"`'s own `size<=8` branch calls
`storeConstructionOrPlainPush`) -- its `size>8` branch (a single,
already-computed multi-word value, e.g. a whole struct read back from
a local) always fell through to the older, unconditional
`pushBlockFromFrame`, a genuinely different, pre-existing convention
(shared with `DEREF`'s own multi-word read) that pushes words in
**reversed** order -- the highest source offset ends up nearest the
top of the stack. `pushStoreOffsetByLine` still got populated for this
exact line by `populateDirectMembers` (a single-entry run always
resolves to offset 0, regardless of entry size), but that computed
offset was simply never consulted by the `size>8` branch at all -- the
gap wasn't in *detecting* the construction run, only in *acting* on
that detection once found.

Net effect for a 3-word struct (`classId`, `x`, `y`): `NEW`'s straight
copy transposed the source's front and back words end for end -- the
malloc'd buffer's own offset 0 ended up holding the source's *last*
word (`y`), offset 16 its *first* (`classId`) -- exactly matching the
observed symptom (`y` reading back as the classId; `x`, the
self-symmetric middle word, coming out right by coincidence).

**Fix**: a new `pushBlockFromFrameConstructionAware(baseOffset, size)`,
used in place of the unconditional `pushBlockFromFrame` call in `case
"PUSH"`'s `size>8` branch. It checks `pushStoreOffsetByLine` first,
exactly as `storeConstructionOrPlainPush` already does for the `<=8`
case; when absent (the ordinary case -- ordinary whole-value reads,
`LOOKUP_ARRAY`/`DOT` feed values, etc., completely unaffected), it
falls straight through to the original `pushBlockFromFrame` unchanged.
When present, it reserves exactly this entry's own declared width
(`subq $size, %rsp`) and does a **straight, order-preserving** `rep
movsb` copy (source: `%rbp+baseOffset`, ascending; destination:
`%rsp+constructionOffset`, ascending -- `%rcx` saved/restored around
it, the same reason `assignConstructionBlock` already does) instead of
a word-by-word reversed push, landing the bytes at the exact packed
position the run's own layout already computed for it (always offset
0 for a lone, whole-value entry, but the same general mechanism a
future multi-entry run containing one of these could rely on too).

**Verified**: `constructor_new_test.caspien` (the fixture that
surfaced this) now prints `x=11 y=13`, exit 0, through the real
4-stage pipeline. Full `_cg_test` execution-suite regression sweep (44
fixtures, real compile -> optimize -> lower -> codegen -> assemble ->
link -> run) re-run after the fix: zero regressions -- every fixture
that previously ran correctly (including every construction-packing-
sensitive one from earlier rounds: `struct_mixed_width_cg_test`,
`struct_array_member_narrow_elem_cg_test`,
`array_literal_narrow_elem_cg_test`, the chained-lookup and 2D-array
fixtures, the ghost-table/gt_register fixtures reachable from this
compile stage) still gives byte-identical output; the only fixtures
not run to completion are the same, already-documented, unrelated
pre-existing gaps (`arrays_safe_dynarray_full_api_with_gt_register_cg_test`,
`atomic_swap_global_scalar_cg_test` -- both still exactly their own
prior failure mode) plus a handful of fixtures that predate the
front-end's "`new` must be wrapped in `try`/`catch`" requirement and
fail at the *compile* stage, before ever reaching this project at all.

## Fixed: `DOT` of a >8-byte nested field -- the "DOT-after-LOOKUP" gap, closed for real this time

Asked for directly: "can you fill in the TODO(codegen)" -- the one
specific placeholder found (and precisely routed around, not fixed) by
the sibling `caspien-compiler` project's own RVO work while extending
`return arr[i]` support (see that project's own CLAUDE.md, the
`isReadableValueChain`/`containsLookup` restriction's own doc comment,
for the full front-end-side account of how this gap was found and
scoped around). `case "DOT":`'s own `size > 8` branch -- reading a
struct- or array-typed field wider than one machine word off a base
that itself arrived as a whole pushed value, never a plain address
(e.g. `arr[i].point`, where `arr[i]` came from `LOOKUP_ARRAY`'s own
multi-word branch as a still-pending block, and `.point` is a
16-byte-or-wider field within it) -- used to just discard the pending
block and push a placeholder `0`, flagged with a
`TODO(codegen): DOT of a >8-byte nested field not yet implemented`
comment. Never previously exercised for real (every DOT read this
backend had been checked against before now was either scalar-sized or
rooted directly in a nameable frame slot, never chained after a
`LOOKUP_ARRAY`), confirmed by this being the first time this exact
comment was ever removed rather than merely narrowed around.

**The address `rax` (shared with the already-correct `size <= 8`
branch) computes is not the field's front-most word.** A first attempt
at this fix assumed it was, by analogy with `LOOKUP_ARRAY`'s own
already-fixed `elemSize > 8` branch, and copied its exact reversed-copy
shape verbatim -- wrong, caught immediately by a real execution test
(`arr[1].point` printed `x=8 y=0` instead of the real `x=21 y=22`).
Working the address algebra through directly: for a field at byte
offset `off` covering word indices `off/8 .. off/8+fieldWords-1` within
a `totalWords`-word containing block, `rax = rsp + totalWords*8 - size
- off` lands on word index `off/8 + fieldWords - 1` -- the field's own
**back**-most word (nearest `rsp`), not its front-most one. This is
exactly the same structural role plain `%rsp` itself already plays for
a *whole* pushed block (`address_of_wordIndex(totalWords-1) == rsp+0`
is this exact formula's own shape with `size == totalWords*8, off ==
0`). Since `rax` is already a same-role "back-word anchor" for the
field the way `rsp` is for the whole block, relocating the field's own
words into a fresh, `rsp`-anchored block needs **no reversal at all** --
a word at relative offset `k*8` from `rax` goes to relative offset
`k*8` from the new (post-shrink) `rsp`, `k` ascending, automatically
preserving this backend's own "back-most word nearest rsp" convention
(`LOOKUP_ARRAY`'s own `rax`, by contrast, genuinely is a front-word
anchor for its own different formula -- the two cases aren't the same
shape, only superficially similar).

**A second, real bug found only once the addressing direction above was
already correct: forward-order copying corrupts an overlapping
source/destination range.** `rsp` is shrunk to the field's own final
size *first* (mirroring the *reason*, not the copy shape, of
`LOOKUP_ARRAY`'s own fix -- `rax`, captured into `%r14` beforehand, is
an absolute address unaffected by moving `rsp` afterward, so this
ordering is always safe and guarantees the copy's destination is an
address range that will actually still be live). But whenever the
field isn't already flush with the containing block's own back end
(`shrinkAmount = totalWords*8 - size > 0`, i.e. there's a field *after*
this one in the containing block that just got discarded), the
destination address ends up `shrinkAmount` bytes *higher* than the
matching source address for every word -- and the two ranges genuinely
overlap whenever `shrinkAmount < size`. Copying low-to-high in that
case clobbers a not-yet-read source word with an already-relocated one.
Found via a real execution test with a field that has *both* a
preceding and a following sibling field (`Holder{tag, point, tail}`,
reading `.point` out of the middle): printed `x=22 y=22` -- both
reading the just-relocated `y`'s own value -- instead of the real
`x=21 y=22`. This is the standard overlapping-copy (`memmove`) hazard:
since destination > source here, copying high-to-low (highest word
first) is always safe -- fixed by iterating the copy loop from
`fieldWords - 1` down to `0`, matching `assignBlock`'s own copy
direction for the identical reason.

**Verified four ways**, via real execution, not just a clean compile:
(1) The degenerate case, a field that occupies the *entire* containing
block (`Holder{point: mut Point}`, `arr[1].point`) -- `x=21 y=22`,
correct (this is the shape the first, wrong reversed-copy attempt also
happened to get right by accident, since a whole-block field has no
overlap or reversal to get wrong -- not a reliable check on its own,
which is exactly why the two follow-on tests below were needed to catch
the real bugs). (2) A field preceded by another field, at both array
indices (`Holder{tag: mut u64, point: mut Point}`, `arr[0].point` and
`arr[1].point`) -- both indices correct, confirming the fix is
genuinely offset-general, not a single-case coincidence. (3) A field
with siblings on *both* sides (`Holder{tag, point, tail}`), read at
both indices -- `p.x=11 p.y=12 q.x=21 q.y=22`, all four values correct;
this is the case that caught the overlapping-copy bug above. (4) A full
sweep of the entire 874-fixture `examples/*.caspien` corpus (compile ->
optimize -> lower -> codegen -> assemble, for every fixture that
reaches codegen cleanly): the exact `DOT of a >8-byte nested field`
`TODO(codegen)` comment now appears in **zero** fixtures' own generated
assembly (previously present wherever a DOT-after-LOOKUP nested-field
read occurred), the three fixtures still carrying any `TODO(codegen)`
comment at all (`class_hierarchy_enum_test`, `nested_enum_test`,
`range_enum_test`) carry only pre-existing, unrelated ones (`PUSH of
'<EnumType.Member>' (not a declared global)`, line ~2013's own separate
gap), and the six fixtures that fail to assemble are all pre-existing,
unrelated inline-asm-block/type-alias fixtures (`asm_file_form_*`,
`asm_named_block_root_test`, `asm_shadowing_test`,
`asm_unnamed_block_test`, `type_alias_overload_test`) with nothing to
do with `DOT` or struct field access -- zero regressions.

Three new dedicated fixtures added to the shared `examples/` corpus by
this round's own verification:
`dot_after_lookup_multiword_field_test.caspien` (case 1 above),
`dot_after_lookup_multiword_field_middle_test.caspien` (case 3 above,
the one that caught the overlapping-copy bug) -- case 2's own
Holder-with-a-preceding-field-only shape was folded into a temporary
local check, not kept as its own permanent fixture, since case 3
already subsumes it (a field with siblings on both sides covers the
"preceded by another field" shape too).

**Remaining `TODO(codegen)` count after this fix**: 8 (confirmed via
`grep -n 'TODO(codegen)' X86Backend.java`, this file remains the only
one in the whole codegen project carrying this marker) -- `PUSH`
(2-token form), `PUSH` of an incomplete-range pseudo-member, `PUSH` of
a name that isn't a declared global, `DOT` with no preceding pushed
value (a different, still-unobserved-in-practice gap from the one just
fixed here), the generic catch-all fallback (two separate occurrences),
`GT_DESTRUCT` with a non-stack-offset operand, and `POP` of a
non-stack-offset destination -- see each one's own comment in
`X86Backend.java` for what specific bytecode shape it's still flagging
rather than guessing at.

## `THROW` is now a no-op marker -- real control transfer moved inline (per-call-site/per-throw-site unwind redesign)

Supersedes this file's own older "GT_UNWIND/THROW/EXIT implemented"
entry further down for `THROW`'s specific behavior -- that entry's
account of `GT_UNWIND` and `EXIT` is still accurate and unchanged;
only `THROW`'s own shape changed. See `caspien-compiler`'s own
CLAUDE.md, "Redesigned: per-call-site unwind staging + per-throw-site
destruct lists..." for the full front-end design this follows.

**Old bytecode shape**: `THROW <value> <label>` -- a plain,
unconditional `jmp <label>` to the thrown-from function's own single,
shared `gt_routine__<func>` label.

**New bytecode shape**: `THROW <value>` -- one operand only. The
compiler now resolves a throw to its own precise, inline destruct-and-
terminate sequence at compile time (the exact same `GT_DESTRUCT`/
`EXIT`/`EXIT_THREAD`/`GT_UNWIND` lines this backend already implements,
just emitted directly after `THROW` rather than behind a jump to a
shared label), so `THROW` itself carries no control-transfer
responsibility anymore. `case "THROW":` is now a pure no-op (`return;`)
-- the thrown value operand still isn't read or propagated anywhere
(unchanged from before: there is still no `catch` construct in this
language to consume it).

This is a strict simplification for this backend, not a new
capability: nothing new needed implementing here, since every mnemonic
that now follows `THROW` inline (`GT_DESTRUCT`, `EXIT`, `EXIT_THREAD`,
`GT_UNWIND`) was already correctly implemented for the return-site and
old-throw-site cases alike. Also relevant to this backend even though
it needed no code change: the `ADDR gt_routine_address code_addr` /
`PUSH_LABEL <label> code_addr` / `ASSIGN code_addr code_addr code_addr`
staging sequence (already fully implemented, see `case "ADDR"`/
`case "PUSH_LABEL"`/`case "ASSIGN"` and their own doc comments) now
appears many more times per function -- once per ordinary call site
instead of once in the prologue -- each targeting its own unique label;
this required no change either, since this backend's own handling of
that sequence was never keyed to a fixed count or position of
occurrences.

**Verified**: rebuilt clean against the updated front end; the
pre-existing `gt_unwind_throw_multi_frame_cg_test.caspien` (three real
stack frames, `main -> g -> f`, `f` throwing) re-verified byte-for-byte
against its own long-documented expected output (three "destruct"
lines, innermost first, exit code 1, "unreached" never printed) through
the real shipped `Compiler.java` orchestrator, on both `linux`/sysv_x64
and `windows_gnu`/win64/Wine -- unchanged from before this redesign.
Three new dedicated fixtures added by this same round (`gt_callsite_
precise_destruct_test`, `gt_callsite_move_ordering_no_double_destruct_
test`, `gt_callsite_multiple_landing_pads_test` -- see the compiler
project's own CLAUDE.md for what each one specifically proves) also
verified correct on both targets. Full example-corpus regression sweep:
zero new regressions attributable to this backend (the only fixtures
affected by the front-end change at all were ones that needed the new
`@throws` decorator added at the source level, unrelated to anything in
this file).

## New mnemonic: `GT_DESTRUCT_ADDR <type>` -- the address-based sibling of `GT_DESTRUCT $offset`, for the "GT_DESTRUCT dotted-operand gap" fix

Closes the last of this session's three array/ownership-codegen gaps
(see `caspien-compiler`'s own CLAUDE.md, "Fixed: the 'GT_DESTRUCT
dotted-operand gap'..." for the full account and the front-end half of
this fix). The existing `GT_DESTRUCT` case only ever accepted an
immediate, compile-time-known frame offset (`"GT_DESTRUCT $-240"`) --
read the owns pointer's value directly off that fixed offset via
`loadSizedFromFrame`, then call `gt_destruct`. That's correct for a bare
local or a `.` chain with no pointer anywhere in it, but has nothing to
offer a chain that crosses a real pointer (`pp.leaf = ...`, `pp` a `raw
mut Node`) -- there IS no single compile-time offset for that case,
which is exactly why the compiler side used to just leave the operand
unresolved and this case's own `else` branch silently skipped the
destructor entirely (a `TODO(codegen): ... not yet implemented`
comment, no call).

`GT_DESTRUCT_ADDR <type>` is the fix's other half: the compiler now
computes the target's own real, possibly-pointer-crossing ADDRESS at
runtime (the exact same `DOT_LHS`/`LOOKUP_LHS` machinery an ordinary
assignment target already uses, proven correct through pointers by the
chained-array-indexing fix), duplicates it (`DUP_TOP`), and this new
case consumes one copy: pop the address, dereference it once
(`loadSizedFromAddr`, since the owns pointer's own value -- what
`gt_destruct` actually wants, confirmed from its own body: ordinary
by-value parameter handling -- lives AT that address, one level further
in than the plain case's fixed frame slot), move it into `argReg(0)`,
call `gt_destruct`. The operand is a type string only, unused for
dispatch (an owns pointer is always exactly 8 bytes regardless of what
it points to) -- kept purely for this bytecode format's "every
instruction states the type it operates on" convention.

No `AddressLoweringPass` (lowerorder) change needed at all: that pass's
`tryRewrite` returns `null` for any mnemonic it doesn't explicitly
match, and its caller then keeps the original line completely unchanged
-- so `GT_DESTRUCT_ADDR` passes through it untouched by construction,
exactly like every other already-resolved, no-name-operand instruction
does.

Verified via `caspien-compiler/examples/gt_destruct_pointer_crossing_
cg_test.caspien`: a self-written `@gt_destruct` stub that prints on
every call confirms the destructor fires exactly once for the old value
before a pointer-crossing reassignment, on both `linux`/sysv_x64 and
`windows_gnu`/Wine/win64; the generated assembly shows the expected
load-address/dereference/move-to-`argReg(0)`/`call gt_destruct`
sequence, no `TODO(codegen)` placeholder. Full regression sweep: zero
new regressions.

## `LOOKUP_ARRAY_LHS` write-side gap (documented further below) is now CLOSED -- fixed in `caspien-compiler`, not here

The "Known separate gap... `LOOKUP_ARRAY_LHS` write-side gap" entry
further down this file (originally found while regression-testing the
narrow-load/store fix, kept open at the time as "left for a dedicated
round on array codegen") turned out NOT to need any codegen change at
all. It was fixed as a side effect of `caspien-compiler`'s own chained-
array-indexing write fix (`BytecodeEmitter.emitDotLhsRoot`'s new
recursive branch) -- see that project's own CLAUDE.md, "Chained array
indexing (`grid[1][1]`)" section, "side effect" paragraph, for the full
account. The bug was never in `LOOKUP_ARRAY_LHS` itself (its own doc
comment/implementation, below, was always correct given a real address
as input) -- it was the compiler's own front end handing it a *value*
instead of an *address* for any non-bare-VARREF assignment-target base
(a `.` or LOOKUP root), which is exactly the same bug this file's own
narrow-load/store fix's regression testing happened to surface through
one specific shape (`bd.cells[2] = 8`). Confirmed directly: both
`array_struct_member_cg_test.caspien` and `arrays_of_struct_field_write_
segfault_cg_test.caspien` (in `caspien-compiler/examples/`) previously
segfaulted and now run correctly, with no change to this project's own
source. The "Known separate gap" writeup below is left in place,
unedited, as the historical record of how the bug was found and
root-caused -- read it for that, not as a statement of current status.

## Fixed: `printf("%f", ...)` (and any other variadic float argument) always printed `0.000000` -- three bugs, all in the caller-side variadic call path

Asked for directly, immediately after the register/stack-argument-
overflow fix just below flagged it as a separate, out-of-scope bug:
"ah bugger. does printf not accept f32s? i thought float in C was an
f32" -- answered, then: "if you could implement that for me please."

**Root cause 1 (the actual bug asked about): no default-argument
promotion.** C requires every `float` argument reaching the `...`
portion of a variadic call to be widened to `double` *before* the call,
unconditionally -- this language's own `f32` is a real, 32-bit `float`
by construction, but a variadic function's own fixed, non-vararg prefix
parameters (`printf`'s own format-string argument) are explicitly
*not* promoted, only the true varargs are. Nothing here ever did this
widening at all -- a raw, unpromoted 4-byte value was always passed,
which glibc's own `printf` (which trusts the promotion completely, and
reads every "%f" argument as a genuine 8-byte double) silently
misinterpreted.

**Fix**: a new `PROMOTE_F32_TO_F64` bytecode mnemonic
(`BytecodeEmitter.emitArgWord`/`emitCallSequence`'s own
`varargStartIndex` parameter decides, per call, which argument index is
the first *true* vararg -- `info.paramTypes.size()` for a variadic
extern -- so this only ever fires for a real vararg, never a variadic
function's own fixed prefix). Codegen's own implementation pops the
already-pushed 4-byte value, reinterprets it as a real `float`,
`cvtss2sd`-widens it to a genuine `double`, and pushes the resulting
8-byte bit pattern back -- occupying the exact same one stack word a raw
`f32` word already did, so nothing about register-vs-stack argument
counting needed to change at all.

**Root cause 2: SysV's `%al` varargs-register-count rule was hardcoded
to 0.** `emitAlignedCall`'s own preamble unconditionally zeroed `%al`
before any variadic call, on the stale, already-wrong-at-the-time claim
"this backend never passes float args in xmm registers at all yet" --
but a register-passed float argument already went through a real "POP
FARGn" into a real xmm register by then. glibc's own varargs
implementation trusts `%al` completely to decide whether to even *look*
at any xmm register at all, so this alone was enough to make `printf`
silently ignore a correctly-promoted, correctly-registered float
argument. **Fixed** by threading the real, compile-time-known count of
float-bank registers a call's own arguments actually consumed (a new
`ArgCounters.floatRegistersUsed()`, clamped to the convention's real
register count -- not the same as the unclamped `floatIdx` running
total) through a new `VARARGS_XMM_COUNT` bytecode line, emitted
immediately before a real variadic call's own "CALL" line and consumed
by both of this backend's own non-win64 `%al`-zeroing sites
(`emitAlignedCall`'s own branch, and a second, previously entirely
missing site in `resolveStackArgBytesAndCall`'s own stack-overflow
branch -- fixed alongside the first rather than left as a second,
adjacent, same-class gap). Win64 has no equivalent convention at all;
this whole mechanism is SysV-only.

**Root cause 3, found and fixed during this round's own verification
(not asked about, not previously known): win64's own separate,
additional variadic-float-argument rule.** Even after 1+2 above, `printf
("%f", x)` still printed `0.000000` under `windows_gnu`/Wine
specifically. The Microsoft x64 ABI requires a **register-passed**
variadic floating-point argument to be duplicated into *both* its real
xmm register *and* the corresponding general-purpose register -- win64
has no register-save-area/count convention the way SysV's `%al` rule
does, so the callee can't statically tell, from its own shared int/float
argument-position counter alone, which of its own already-populated
registers were meant to be read as a float and which as an integer.
**Fixed** via a new, generic `DUP_TOP` bytecode mnemonic (duplicates the
top 8-byte stack word in place -- pop, then push it back twice; not
itself float- or win64-specific, just a general "make a second,
independent copy of this value available to consume" primitive) plus a
new branch in `BytecodeEmitter.emitArgTransferTail`: a true vararg,
`f32`-typed, *register-passed* (never stack-overflowing) argument under
a `sharedArgumentPosition` convention (`ArgCounters.isShared()`) now
gets `DUP_TOP` inserted right after promotion, so both a "POP FARGn" and
a "POP ARGn" can each consume their own copy of the identical,
already-promoted double bit pattern. Both of `ArgCounters`' own register
tables are always sized identically (4 and 4) for a `shared` convention,
so a valid float register index always has a real, same-index integer
register to duplicate into.

**Two further, genuinely separate bugs found only while verifying root
causes 1-3 above, both fixed in the same round:**

- **A real, confirmed register-clobber, the identical class of hazard
  already documented below for `LOOKUP_ARRAY`'s own scratch-register
  choice.** `PROMOTE_F32_TO_F64`'s first version used `%xmm0`
  unconditionally as its own scratch register for the widening
  conversion. The moment a call has *more than one* float vararg, the
  first one's own promoted value is already sitting live in the real
  `%xmm0` argument register (its own "POP FARG0" destination) by the
  time the second one's own promotion runs -- using `%xmm0` as scratch
  for the second silently overwrote the first's already-transferred
  value before the call ever ran. Confirmed directly: a two-float
  `printf` call printed *both* floats as the second one's own value.
  Fixed by using `%xmm8` instead -- never a real SysV or win64 argument
  register in either convention, so it can never collide with a live,
  not-yet-consumed `FARGn` transfer no matter how many float arguments a
  call has.
- **A real, previously-latent stack-alignment bug, predating this whole
  round entirely.** `X86Backend.alignPadFor`'s own two branches were
  inverted -- confirmed against the real x86-64 ABI text (%rsp must read
  0 mod 16 immediately *before* a `call` instruction, not 8 mod 16 as
  this method's own prior doc comment incorrectly claimed). This bug was
  already live in the original register/stack-argument-overflow fix
  just below, but went completely undetected there because both of that
  round's own verification fixtures (`register_overflow_args_even/
  odd_cg_test`) call a plain, all-integer function, which never performs
  any alignment-sensitive SSE stack access and so silently tolerates an
  8-byte-misaligned call site. It surfaced for real only once a call
  combining a stack-passed argument *with* a genuinely alignment-
  sensitive callee was tested for the first time: nine `f32` varargs to
  `printf` (one more than sysv_x64's 8-register float limit) segfaulted
  deep inside glibc's own `__printf`, confirmed via gdb to be a real
  stack-misalignment fault (every argument's own value, register- and
  stack-passed alike, was already confirmed correct by that point).
  Fixed by swapping `alignPadFor`'s two branches to match the real ABI
  requirement.

**Verified**, via real execution on both `linux` (sysv_x64) and
`windows_gnu`/Wine (win64):
- `printf_float_arg_promoted_to_double_cg_test` (a single,
  non-overflowing float argument): `val=3.500000` (previously
  `0.000000`), on both targets.
- `printf_mixed_int_float_varargs_cg_test` (one int vararg, two float
  varargs, in the same call -- pins down both the `VARARGS_XMM_COUNT`
  real-count fix and the `%xmm8`-scratch register-clobber fix):
  `a=7 b=1.500000 c=2.500000` (previously `a=7 b=2.500000 c=2.500000`,
  both floats reading the second one's own value).
- `printf_float_varargs_stack_overflow_cg_test` (nine float varargs, the
  9th genuinely stack-passed -- pins down the `alignPadFor` inversion):
  `1.000000 2.000000 ... 9.000000` (previously a segfault inside
  glibc's own `__printf`).
- `win64_printf_float_varargs_cg_test` (win64's own additional
  register-duplication rule -- pins down `DUP_TOP`): `val=3.500000`
  under `windows_gnu`/Wine (previously `0.000000` even after the SysV
  half of this fix alone).
- `float_register_overflow_args_cg_test` (pre-existing, its own doc
  comment updated): now correctly prints `total=45.000000`, no caveat
  needed -- the exact bug it used to flag as out-of-scope.
- Full `_cg_test` execution-suite regression check (every non-win64
  fixture actually run to completion or confirmed-segfault, not just a
  compile-stage TODO scan, on `linux`/`sysv_x64`): zero regressions --
  the same four pre-existing, unrelated failures as always
  (`array_struct_member`, `arrays_of_struct_field_write_segfault`,
  `arrays_safe_dynarray_full_api_with_gt_register`,
  `atomic_swap_global_scalar`) are still exactly that, confirmed
  unchanged. The two win64 overflow fixtures
  (`win64_register_overflow_args_cg_test`/
  `win64_mixed_int_float_overflow_args_cg_test`) re-verified correct
  under `windows_gnu`/Wine, unaffected by any of this round's changes.

## Fixed: a call with more arguments than the calling convention has registers for (either bank, either convention) corrupted the stack or segfaulted -- "Register-overflow function arguments/parameters"

Asked for directly: "we never checked passing arguments over the
stack ... run a check on passing arguments by the stack (exceeding the
4 and 6 limits respectively). I suspect the problem is bigger than
integers and may not even be integer-specific." Confirmed exactly
right, on both counts. `register_overflow_args_even_cg_test.caspien`/
`_odd_cg_test.caspien` (both sysv_x64-scoped, `sum8`/`sum9`, 2/3
integer words beyond sysv_x64's own 6-register limit) had existed in
this corpus for a while, both still segfaulting, both citing a
"Register-overflow function arguments/parameters" CLAUDE.md section
that didn't actually exist anywhere in this file -- this section is
that write-up, for real this time.

**Root cause, found via gdb, was two independent bugs, not one:**

1. **The stack-passed word itself was correct, but the return address
   ended up in the wrong place relative to it.** A stack-passed
   argument is already emitted correctly by the compiler -- a bare
   "PUSH size operand" with no following "POP ARGn"/"POP FARGn" at all
   (see `BytecodeEmitter.emitArgTransferTail`'s own doc comment: a
   register-passed word always gets popped into its register right
   after its own value is computed; a stack-passed one never does,
   deliberately, so it's left sitting on the real stack exactly where
   the callee's own `AddressLoweringPass.resolveArgWordSlots` expects
   it, at a positive `%rbp`-relative offset). But `emitAlignedCall`'s
   own 16-byte stack-alignment machinery (needed on *every* call, for
   the ABI's own call-site alignment requirement) wraps only the
   literal `call` instruction -- which runs *after* the stack-passed
   words have already been pushed -- so its own "reserve a slot, align,
   save the pre-alignment rsp there" sequence landed *between* the
   pushed arguments and the return address `call` was about to push.
   The callee's own (correct) `%rbp+16+n*8` read formula then read that
   alignment machinery's own reserved slot/saved-rsp value instead of
   the real argument.
2. **Nothing ever removed the stack-passed words from the real stack
   afterward.** This backend's own "PUSH"/"POP" bytecode mnemonics are
   real, one-to-one `pushq`/`popq` on the *actual* x86 stack -- there is
   no separate, purely-abstract value-stack model underneath -- so every
   word deliberately left un-popped by the bytecode (point 1 above) is a
   word this backend's own push/pop accounting never balances either,
   unless something else explicitly discards it. Nothing did: every
   bytecode line emitted after the call returned (starting with the
   very next `PUSH_RET_INT`/`ASSIGN` pair) silently operated against a
   stack that was N words deeper than it assumed -- confirmed directly
   against `sum8(1..8)`: the caller's own `let total = sum8(...)` ended
   up popping argument `h`'s own value (8) into `%rbx` as if it were
   `total`'s own address, then writing through it -- a wild pointer
   write, the exact, confirmed segfault.

Both bugs are caller-side stack-accounting/-ordering bugs, neither
specific to integers nor to one calling convention -- confirmed by
fixing them once and testing across the whole matrix the user asked
for (see "Verified" below): they fire identically for a stack-passed
float, a narrow type, or a call under win64, since `emitAlignedCall`/
`case "CALL"`/`case "INVOKE"` are the one shared, convention-agnostic
choke point every call goes through.

**The fix (`argTrackDeltaStack`, `callBufferStack`, `case "CC_START"`/
`case "CC_END"`/`resolveStackArgBytesAndCall`, X86Backend.java -- see
each one's own doc comment for the full design):** the 16-byte-
alignment reservation now happens *before* any argument -- stack-passed
or not -- is pushed, sized to already account for the real number of
bytes the stack-passed arguments will add before the call, so the
return address ends up immediately adjacent to the last stack-passed
argument, exactly where sysv_x64/win64 both require it; the
alignment slot itself sits further down the stack, out of the way; and
that same byte count is used again, after the call returns, to
actually discard those words from the real stack via the restore
already reading a *variable* offset rather than a fixed one.

The one added subtlety: this byte count **can't be read off the
bytecode text alone.** An individual argument's own "PUSH ... ; POP
ARGn"-or-not shape can have any number of ordinary intermediate lines
in between the two -- found via a real, confirmed regression during
this fix's own verification: `raw arr` (a `raw`-taken fixed array
parameter, entirely unrelated to argument-count overflow) compiles to
"PUSH 24 $-24 / ADDR_OF RAW $-24 / POP ARG0 8" -- a whole 24-byte array
block is pushed, then immediately collapsed back down to a single
8-byte pointer by `ADDR_OF`'s own real codegen, *then* popped into a
register. A first attempt at this fix used a naive "is the very next
line a POP ARGn" text check, which misclassified this as 24 stack-
passed bytes and corrupted an otherwise-correct, all-register call
(`arrays_raw_param_value_semantics_cg_test.caspien` started segfaulting
-- caught by re-running the full `_cg_test` execution suite, not by
inspection). Trying to special-case every such shape (`ADDR_OF`, `DOT`,
`LOOKUP_ARRAY`, `CLONE`, ... -- any mnemonic whose own codegen can
shrink or discard an already-pushed value) from bytecode text alone is
exactly the kind of whack-a-mole this fix's own final design avoids
entirely: instead of reading intent from the bytecode text, it measures
the real thing directly, tracking every *actual* `pushq`/`popq`/`add
rsp,N`/`sub rsp,N` this backend emits between a call's own "CC_START"
and its "CALL"/"INVOKE" (`raw()`'s own new `trackStackDelta` hook).
Whatever real bytes are still unaccounted for at the "CALL" line are,
by construction, exactly the stack-passed argument bytes: every
*register*-passed argument's own production sequence, however many
lines long, always nets to zero the moment its "POP ARGn"/"POP FARGn"
runs. Emission itself had to be deferred to make this work (the
reservation has to be the *first* thing emitted for the call, but its
own size is only known once every argument has already been produced,
at the "CALL" line) -- `case "CC_START"` now opens a buffer instead of
emitting anything immediately, and `case "CC_END"` flushes it once
`case "CALL"`/`"INVOKE"` have resolved and used the real byte count,
correctly nesting when a call appears inside another call's own
argument expression (`foo(bar(), x)`).

**A second, narrower bug found only once the above was already
correct and win64 was tested**: the new path omitted win64's own fixed
32-byte "shadow space" reservation entirely (`AddressLoweringPass.
resolveArgWordSlots`'s own callee-side offset formula already bakes
this in as `convention.shadowStack` -- the caller has to actually
reserve it for that formula to be true). `emitAlignedCall`'s own
original, zero-stack-arg path already reserves/undoes this correctly
around the call; the new path needed the identical reservation, just
placed *after* the stack-passed arguments are pushed (immediately
before the bare `call`) rather than immediately after the alignment
slot -- 32 is already a multiple of 16, so it never changes the
alignment parity the reservation size already solved for. Found via
`win64_register_overflow_args_cg_test.caspien` printing a wrong value
under Wine before this was added.

The ordinary, overwhelmingly common all-register-arguments case is
completely unaffected in its generated assembly by any of this -- it
still goes through the original, already-verified `emitAlignedCall`
wrapping just the `call` itself.

**Verified**, via gdb register/memory inspection and real end-to-end
execution, across the matrix the user specifically asked for:
- sysv_x64 integer overflow: `register_overflow_args_even_cg_test`/
  `_odd_cg_test` (`sum8`/`sum9`, 2/3 words beyond the 6-register limit)
  -- both now print the correct sum (`36`, `450`) instead of
  segfaulting, on both `linux` (real gcc/ld) and confirmed via the real
  shipped `Compiler.java` orchestrator.
- win64 integer overflow: `win64_register_overflow_args_cg_test`
  (`sum5`, 1 word beyond the 4-register limit) -- correct (`1500`)
  under Wine, real orchestrator, `windows_gnu` target.
- win64 mixed int/float overflow under `shared-argument-position`:
  `win64_mixed_int_float_overflow_args_cg_test` (`mix5`, alternating
  int/float params, the 5th -- an *integer* word immediately following
  two float register-passed ones -- overflowing win64's one shared
  position counter) -- correct (`60`) under Wine.
- sysv_x64 float overflow: `float_register_overflow_args_cg_test`
  (`sumf9`, 1 word beyond the 8-xmm-register limit) -- the stack-passed
  float argument's real value and the arithmetic using it are both
  confirmed correct directly via gdb (`%xmm0` holds the exact bit
  pattern for `45.0` the instant `sumf9` returns); this fixture's own
  `printf("%f", ...)` line prints `0.000000` regardless, a separate,
  pre-existing, unrelated bug (this backend's `f32`-returning/argument
  path never widens a 4-byte `f32` to the 8-byte `double` C's own
  varargs ABI requires for `%f` -- confirmed present even for a single,
  non-overflowing `printf("%f", x)` with zero other floats or overflow
  involved) -- flagged here, not fixed, out of scope for this round.
- Full `_cg_test` execution-suite regression check (not just the
  compile-stage TODO scan, which can't see this class of bug at all --
  every fixture actually *run* to completion or confirmed-segfault, on
  both `linux`/`sysv_x64` and `windows_gnu`/win64/Wine): zero
  regressions in either direction (the one true regression this fix's
  own first attempt introduced, `arrays_raw_param_value_semantics_cg_test`,
  was caught this same way and fixed before delivery -- see above); the
  same pre-existing, unrelated failures as always
  (`array_struct_member`, `arrays_of_struct_field_write_segfault`,
  `arrays_safe_dynarray_full_api_with_gt_register`,
  `atomic_swap_global_scalar`) are still exactly that -- pre-existing
  and unrelated, confirmed unchanged by this fix.

## Fixed: `LOOKUP_ARRAY`'s read-side address formula and its multi-word copy were both wrong for any element wider than 8 bytes -- every 2D-array row (or array-of-structs element) read back as element 0's

Found while cataloguing array codegen behavior end to end (a fresh,
systematic test pass, not a fix request against a known bug): a real
2D fixed array (`let grid = mut [[1,2,3],[4,5,6],[7,8,9]]`) had every
row read back identical to row 0's own data, regardless of the real
index used (`grid[1]` and `grid[2]` both silently returned `[1,2,3]`).
Traced to two separate, adjacent bugs in `LOOKUP_ARRAY`'s own
`pendingBlockSize > 0` branch (the case where the whole array was
pushed by value, still fully present on the real stack -- see that
branch's own pre-existing doc comment):

**Bug 1 -- the base-address formula subtracted the wrong width.** The
shared address computation (used by both the `elemSize <= 8` and
`elemSize > 8` sub-branches) computed `rsp + (totalWords*8 - elemSize)
- rbx` (`rbx` = `index*elemSize`), intending to locate the target
element's own front-most *word*. That's only correct when `elemSize`
happens to equal one machine word (8 bytes) -- true for every ordinary
scalar element this backend had ever been checked against before now,
which is exactly why this went unnoticed. For any element *wider* than
one word (a `u64[3]` row, a struct), or, independently, any element
*narrower* than one word sharing an 8+-byte pending block (untested
until now -- see the sibling fix below), this located a
completely different word than the one actually wanted. Fixed by
subtracting a fixed one-word `8` instead of `elemSize` in that shared
term -- the formula now correctly locates the *front-most word* of the
target element regardless of how wide the element itself is.

**Bug 2 -- the multi-word (`elemSize > 8`) copy wrote to a destination
that got discarded before ever being read.** Once the (now-correct)
source address of the element's own front-most word is known, the old
code copied the element's words down to the *current* top of stack
(`rsp+0`, `rsp+8`, ...) and only *afterward* shrank `rsp` up to its
final size. Shrinking `rsp` upward discards the *lowest* addresses --
precisely the ones that copy had just written to. So the copy was
silently thrown away on every single call, and whatever pre-existing
data already happened to occupy the one address range that actually
*survives* the shrink (a fixed range, entirely independent of the real
index) was what came out instead -- which is exactly why every row
read back as row 0's: the surviving address range always happened to
still hold row 0's own original data from the initial push.

Fixed by reordering: shrink `rsp` to its final size *first* (safe --
the copy's own source address, `r14`, was already computed as an
absolute address before this point, so moving `rsp` afterward can't
invalidate it), so the copy's destination is now the one address range
that will actually still be live afterward. The copy loop itself was
also corrected to walk the source *backward* (`r14`, `r14-8`, `r14-16`,
...) as the destination offset counts *up* from 0 to `(elemWords-1)*8`
-- preserving this backend's own "front word ends up at the
deepest/highest address" convention for the retained block (needed so
a `DOT` chained right after this, e.g. an array-of-structs field read,
can keep extracting from it correctly) -- the old loop walked both
source and destination in the same, forward direction, which (once bug
1's address were fixed) would have landed the front word at the
*shallowest* address instead, backward from what every other pushed
value-block here already expects.

**Verified three ways.** (1) The real, motivating repro
(`examples/arrays_2d_row_index_wrong_value_full_cg_test.caspien`, a 3x3
`u64` grid, each row bound to its own local and printed) now prints
`row0=1,2,3 / row1=4,5,6 / row2=7,8,9` -- previously `row0=1,2,3 /
row1=1,2,3 / row2=1,2,3`. Its sibling
(`arrays_2d_row_index_via_local_wrong_value_cg_test.caspien`, the same
bug reached through a single chained lookup) now prints the correct
`v=5` (previously `v=2`). (2) Every fixture this session's earlier
fixes had verified by hand (`within_bare_range_param_cg_test`,
`range_dynarray_membership_cg_test`, `struct_mixed_width_cg_test`,
`struct_array_member_narrow_elem_cg_test`,
`array_literal_narrow_elem_cg_test`, `builtin_range_literal_test`,
`match_validation_bounds_test`, `interface_instanceof_dispatch_cg_test`,
`instanceof_narrowing_member_access_test`,
`ghost_table_range_construction_cg_test`, plus the array-catalog
fixtures `array_lookup_cg_test`, `dynarray_array_member_cg_test`,
`unsafe_dynarray_resize_test`) re-run individually, unchanged --
including `array_struct_member_cg_test`, confirmed to still segfault
exactly as before (that fixture exercises the separate, still-unfixed
`LOOKUP_ARRAY_LHS` write-side gap documented below, untouched by this
fix). (3) A full sweep of the entire ~811-fixture corpus: identical
compile/TODO-count results before and after (this fix only changes
*runtime* behavior of already-clean-compiling code, which that sweep's
own TODO-count check can't observe either way -- the execution-based
checks in (1) and (2) are what actually verify it). Verified end to
end through the real orchestrator on both `linux` and `windows_gnu`/
Wine targets against the same 2D-array repro.

**A separate, distinct sibling bug, found while checking this fix's own
edge cases and fixed in the same round, once asked directly** ("can you
not make it so that arrays elements are read at their correct offsets?
... a char is 1 byte, like in C"):
`examples/arrays_narrow_element_packed_word_cg_test.caspien` -- a
*narrow* (`<8`-byte) element array whose own real total size reaches 8+
bytes (avoiding the small-array "packed into one register word" fast
path just above this branch) has *multiple logical elements packed
into a single real 8-byte pushed word* (this language's own tight
sub-word construction packing -- ten `char`s really occupy two real
8-byte words, not ten). Bug 1's own fix above still assumed each pushed
word holds exactly one logical element -- true for anything a whole
word or wider, false here. Reading `arr[3]` (`'d'`, byte 3, still
inside the first packed word) returned garbage instead.

**Fixed** by splitting the target element's own logical byte offset
(`rbx`, already computed as `index*elemSize`) into a word index and a
byte-within-word remainder before converting to a real address: `%r14
= rbx & 7` (byte-within-word, added on *unreversed* -- bytes inside one
already-placed word were never reordered by the original push), then
`rbx = (rbx >> 3) * 8` (word index, given the same reversed treatment
every other word here gets), and the address formula's `rbx` term
becomes this now-word-aligned value instead of the raw byte offset,
with `%r14`'s own byte-within-word remainder added back in afterward.
For an ordinary scalar element (`elemSize` a multiple of 8), the
byte-within-word remainder is always 0 and this is a no-op on top of
bug 1's fix -- confirmed this changes nothing for every already-correct
case.

**A second, real bug found and fixed while verifying this same fix,
this time a register-clobber, not an addressing-math mistake:** the
first version of this fix used `%rdx` as the byte-within-word scratch
register -- wrong, caught by a real execution test before ever being
shipped: `printf("row0=%llu,%llu,%llu\n", row0[0], row0[1], row0[2])`
printed the *middle* value as `0` on every row, not just row 0's.
Traced via gdb: each of `printf`'s three array-read arguments is popped
directly into its own real SysV calling-convention register the moment
its own `LOOKUP_ARRAY` finishes (`%rsi`, then `%rdx`, then `%rcx`) --
so by the time the *third* argument's own `LOOKUP_ARRAY` runs, the
*second* argument's already-computed, not-yet-called value is already
sitting live in `%rdx`, and that third lookup's own `and $7, %rdx`
(this fix's new scratch use) silently overwrote it before `printf` was
ever reached. This is the exact class of gap this project's own
"Known gaps" section already flags for a different instruction
(`array_literal_narrow_elem_cg_test.caspien`'s own doc comment
mentions the identical hazard for `EXTERN_CALL`'s later arguments) --
worth remembering whenever a new scratch register is introduced
anywhere a `LOOKUP_ARRAY`/similar sequence might run mid-argument-list.
Fixed by using `%r14` instead -- already this branch's own scratch
register for the `elemSize > 8` case, never a calling-convention
argument register in either ABI, and not used to carry a live,
not-yet-consumed value across separate `LOOKUP_ARRAY` calls anywhere
else in this file.

**Verified**: `arr[3]`/`arr[5]`/`arr[9]` (all inside the second of the
two real, packed words) now correctly read `d`/`f`/`j`; `arr[0]` (word
0, always correct even before this fix) unchanged. The three-argument
`printf` case that exposed the register-clobber bug
(`arrays_2d_row_index_wrong_value_full_cg_test.caspien`'s own `row0=
%llu,%llu,%llu` line) now correctly prints all three values on every
row. Every fixture from bug 1/bug 2's own verification above re-run
again, unchanged. Full corpus sweep re-run again: zero regressions,
one new fixture (this one, now compiling and running correctly instead
of carrying a documented-but-unfixed gap).

## Fixed: `PUSH`'s `"$offset"` branch never checked `pushStoreOffsetByLine` at all -- silently corrupting any construction with a variable-sourced field

**A genuinely new, previously-undiscovered bug, unrelated to the
`%rcx`-clobbering fix just below** (that fix corrupted a *different*
construction-related invariant, and both were found back to back purely
because they happen to live in adjacent code) -- likely present since
the original construction-packing work itself (see "Fixed: struct/array
construction packing had a `NEW`-specific knowledge leak" further down),
not introduced by anything done earlier this session.

Found while root-causing a real, reproducible segfault the user flagged
as an open "ghost table bug" from earlier testing. Traced with gdb/
objdump, not guessed at: a minimal repro (`new A{...}` plus the real
stdlib's `@gt_init`/`@gt_register` decorators -- nothing instanceof- or
range-specific about the *test* itself) segfaulted inside
`stdlib/ghost_table.caspien`'s own `gtWriteSlot`, writing through a
wild computed address. Working backward: `gt_init` itself was confirmed
correct (`capacity=4`, `base` a real heap address, read directly out of
the live process via gdb) -- so the corruption was happening later, not
at initialization. The actual site: `gtSlotAt(base, index)` contains
`for i in 0..index { slot++ }` -- an ordinary `".."` range literal, but
with one *immediate* bound (`0`) and one *runtime* bound (`index`, a
function parameter, loaded via a `"$offset"` PUSH operand). Disassembling
the real generated code for this one construction showed the two entries
handled inconsistently: entry0 (`"0"`) correctly went through
construction-offset packing (`subq $8,%rsp` at its own precomputed
offset); entry1 (`"$-16"`, i.e. `index`) did a plain, unconditional
`movq -16(%rbp),%rax; pushq %rax` instead -- completely bypassing the
packed layout the terminating `ASSIGN`/`assignConstructionBlock` expects.

**Root cause**: `case "PUSH"` has always had two structurally separate
code paths for deciding how to emit an operand: the `"$offset"` branch
(read another local's own already-computed value off the frame) and the
generic fallback branch (bare literals, `null`, `ARG`/`FARG`, string-pool
ids, declared globals/functions). Only the *fallback* branch was ever
taught to check `pushStoreOffsetByLine` (the map `populateDirectMembers`
fills in with each direct construction-member's own precomputed,
tightly-packed offset) when construction-packing was first added -- the
`"$offset"` branch was simply never touched, and kept doing its
original, pre-packing, unconditional `pushReg("rax")`. Net effect: **any
construction (struct, array, or range literal) with at least one entry
whose value comes from copying another variable's own value (rather than
a literal, an argument, or a global) silently corrupts that one entry's
own slot in the packed layout** -- with no crash or visible symptom at
the construction site itself, only later, whenever the corrupted memory
is actually read back (here, `gtSlotAt`'s own computed "slot" address,
consumed by `gtWriteSlot`'s `memcopy`, several calls removed from the
actual bug).

This is a strictly more general bug than anything range/`within`-
specific: it affects *any* construction, of any kind, with a
`"$offset"`-sourced field -- the ghost-table runtime's own `gtSlotAt`
just happens to be the first (and, per the full regression sweep below,
apparently only *previously-exercised* by the corpus) real-world case
of it, since most struct/array literal fixtures in this corpus build
every field from a fresh literal or argument, not from copying another
already-computed local's value.

**Fix**: extracted the existing "check `pushStoreOffsetByLine`, do a
tight packed store if present, else plain `pushReg`" decision into one
shared helper, `storeConstructionOrPlainPush(String regName, int size)`
(placed right after `populateDirectMembers`, next to
`pushStoreOffsetByLine`'s other consumers), and routed *both* the
`"$offset"` branch and the generic fallback branch through it -- so any
future new operand shape added to `PUSH` automatically participates in
construction-packing too, rather than needing this same decision
re-duplicated (and potentially re-missed) a third time.

**Verified three ways.** (1) The exact real-world repro -- a minimal
program (`new A{x=mut 7}` plus the real stdlib `gt_init`/`gt_register`/
`gt_alive_check`/`gt_destruct` imports, no test-specific stubs) that
previously segfaulted now compiles with 0 TODOs and runs to completion,
exit code 0 -- kept permanently as
`examples/ghost_table_range_construction_cg_test.caspien`. (2) Every
fixture this session's earlier fixes (the `%rcx`-clobber fix and the
`instanceof`-narrowing fix, both documented elsewhere in this file/the
sibling `caspien-lowerordergenerator` project's CLAUDE.md) had verified
by hand -- `within_bare_range_param_cg_test`,
`range_dynarray_membership_cg_test`, `struct_mixed_width_cg_test`,
`struct_array_member_narrow_elem_cg_test`,
`array_literal_narrow_elem_cg_test`, `builtin_range_literal_test`,
`match_validation_bounds_test`, `interface_instanceof_dispatch_cg_test`,
`instanceof_narrowing_member_access_test` -- re-run individually,
unchanged. (3) A full sweep of the entire ~804-fixture
`examples/*.caspien` corpus (compile -> optimize -> lower -> codegen),
diffed line-for-line against the immediately-prior baseline: **zero
regressions and zero newly-fixed pre-existing fixtures** -- the only
difference in the diff is the one new fixture added by this fix's own
verification. This confirms the bug, while real and structurally
serious, was not previously being silently masked by anything else in
the existing 804-fixture corpus -- the ghost-table runtime (only
exercised end-to-end by fixtures using the real stdlib gt_* imports,
which are a minority of the corpus) was the first real-world path to
actually hit it.

## Fixed: `assignConstructionBlock` silently clobbered `%rcx`, corrupting a live incoming argument register

**This was a real, previously-unreported correctness bug introduced by
this session's own earlier "construction packing" fix, below** (the
`ASSIGN`/`ATOMIC_ASSIGN` generalization that made a plain, stack-resident
parameter reconstruction go through `assignConstructionBlock` the same
way a heap `NEW` construction always did) -- flagged here plainly, not
folded silently into that section's own history, since a zip built from
this project before this fix genuinely miscompiled any function with
two or more multi-word (construction-scoped) by-value parameters.

Found while verifying the sibling `caspien-lowerordergenerator` project's
`MembershipLoweringPass` gate-widening fix (see that project's own
CLAUDE.md) against a real function-parameter call site:
`checkSubset(inner: imut range, outer: imut range)`, called as
`checkSubset(a, b)`, computed the wrong `within` result (`0` instead of
the correct `1`) -- but only when reached through a real function call;
the identical computation inlined directly in `main` (no call at all)
gave the correct answer. Isolated via gdb: `outer.end` (the 4th SysV
integer argument, `%rcx`, value `5`) read back as `0` by the time
`outer`'s own parameter reconstruction got to it.

**Root cause**: `assignConstructionBlock`'s `rep movsb` needs a byte
counter, and used `%rcx` directly, with no save/restore -- so `inner`'s
own reconstruction (which runs first, and itself calls
`assignConstructionBlock`) silently zeroed out `%rcx` via the movsb's
own countdown, before `outer`'s reconstruction ever got a chance to read
its own not-yet-consumed `ARG3` value out of that same register. This is
exactly the class of bug this project's own `case "NEW"` and `case
"CLONE"` are already immune to, but for a genuinely different reason:
both of those call `emitMallocCall` (a real function call, which already
clobbers every caller-saved register including `%rcx` per the ABI
regardless) *before* their own `rep movsb`, so no live `ARGn` register
could ever have survived to that point anyway. `assignConstructionBlock`
has no such call in front of it -- it copies directly from the stack to
an already-known destination address -- so its own, unprotected use of
`%rcx` was a genuine, additional corruption source with nothing already
covering for it.

**Fix**: `pushq %rcx` (non-Windows) / `push rcx` (Windows) immediately
before setting the movsb counter, and `popq %rcx` / `pop rcx`
immediately after `rep movsb` completes, before the existing
`addq $size, %rsp` / `add rsp, size` cleanup. Safe because `rdi`
(the destination address) and `rsi` (`= %rsp`, captured before the push)
are both already fixed register values by this point, unaffected by a
temporary, strictly-deeper `%rsp` shift; the pushed word sits below
(lower address than) the value block `rsi` reads from, so it can't
collide with the source data being copied; and the push/pop pair nets to
zero additional stack usage.

**Audited every other `rep movsb` site in this file for the identical
risk** -- confirmed none of the others need this fix: `case "NEW"`/
`case "CLONE"` (the array-literal-construction `NEW` case, and the
`clone()` builtin) both call `emitMallocCall` first, for the reason
above; `case "MEMCOPY"` is a general-purpose byte-copy builtin, never
part of a parameter-reconstruction sequence, so it has no live,
not-yet-read `ARGn` register to protect in the first place.

Verified: `examples/within_bare_range_param_cg_test.caspien` (new
regression fixture, added specifically for this bug -- two 16-byte
by-value `range` parameters back to back, the exact shape that surfaced
it) now correctly prints `result=1`. Re-ran every previously-verified
fixture this session's earlier construction-packing fix touched
(`struct_mixed_width_cg_test`, `struct_array_member_narrow_elem_cg_test`,
`array_literal_narrow_elem_cg_test`) plus the new range fixtures
(`builtin_range_literal_test`, `match_validation_bounds_test`,
`range_dynarray_membership_cg_test`) -- all unchanged, all correct. Full
804-fixture corpus sweep (compile -> optimize -> lower -> codegen)
re-run afterward with no new `CODEGEN_FAIL`/`CODEGEN_TODO` entries
introduced by this change (the pipeline's own pre-existing, unrelated
compile-stage and codegen-stage gaps are untouched by this fix, since it
only ever affects `assignConstructionBlock`'s own instruction sequence).

## Fixed: struct/array construction packing had a `NEW`-specific knowledge leak (three sub-bugs)

The "construction-scoped push" mechanism (`findRunStart`/`collectRunEntries`
[formerly `populateDirectMembers`'s own private scan]/`pushStoreOffsetByLine`)
exists so a struct or array literal's own fields are tightly packed --
each field reserving only its own declared width on the stack, not a
blind always-8-byte word -- and its own doc comments always described it
as a generic, construction-agnostic mechanism, usable by *any* consumer.
In practice it had silently regressed to only ever being triggered by a
`"NEW <size>"` line (heap allocation): a plain, stack-resident struct or
array literal (`let p = mut Mixed{...}`, no `new`) never triggered it at
all, silently falling back to the old blind, always-8-byte-word push/pop
path -- correct only by accident when every field happened to be exactly
8 bytes wide. Flagged directly by the user: *"the NEW instruction isnt
supposed to even have that knowledge leak."* Fixed as three chained
sub-bugs, all traced via real disassembly/gdb against
`struct_mixed_width_cg_test`/`struct_array_member_narrow_elem_cg_test`/
`array_literal_narrow_elem_cg_test` (all three previously-passing-by-
accident or previously-broken fixtures, not new test cases), then
verified against the full 801-fixture corpus with zero regressions:

1. **`precomputeConstructionOffsets` generalized to a second terminator.**
   It now recognizes *both* `"NEW <size>"` lines *and*
   `"ASSIGN"/"ATOMIC_ASSIGN size size size"` lines (three equal sizes) as
   valid construction-run terminators, reusing the exact same, completely
   unmodified `findRunStart`/`collectRunEntries` detection `NEW` alone
   used before -- nothing about *how* a run is found changed; only
   *which lines are allowed to trigger that same detection* grew a
   second case. Tracked via a new `constructionScopedAssign` set (which
   ASSIGN lines qualified). A matching `assignConstructionBlock(size)`
   mirrors `NEW`'s own trivial `rep movsb` copy, but targets an
   already-known destination address instead of a fresh `malloc` buffer.

   The size operands alone don't distinguish a real construction from an
   ordinary scalar assign or a whole-block copy (`let b = a`) -- this
   bytecode always emits three *equal* sizes for an ASSIGN regardless,
   and a copy's declared size can be large (a big struct) exactly when a
   literal's can be small (a two-`char` array). A size threshold (`>8`)
   was tried first and was wrong on both ends -- it missed small literals
   like a bare `char[4]` and would have misfired on copies below the
   threshold. What actually distinguishes them is the **number of direct
   pushes** feeding the ASSIGN: a scalar assign or a whole-block copy is
   always fed by exactly *one* PUSH (one value, or one `$offset`
   reference to the whole source block); a real literal construction,
   however small, is always fed by *more than one* -- one per member. So
   the run is found the same way regardless, and only afterward,
   `collectRunEntries`'s own entry count is what decides -- a single-
   entry run is left on the pre-existing, already-correct path (`ASSIGN`
   case: plain scalar `storeSizedToAddr` for `size<=8`, `assignBlock` for
   larger whole-block copies).

   In `case "ASSIGN"`/`"ATOMIC_ASSIGN"`, the `constructionScopedAssign`
   check now runs *before* the `size<=8` check (not after) -- a small
   (say, 4-byte) construction is exactly as real a construction as a
   large one, and the old ordering would have sent it into the plain
   scalar pop/pop path, which pops a full 8-byte word off a value block
   that construction-scoped pushes only ever reserved 4 bytes for,
   desynchronizing with the address word sitting right above it.

2. **`movzqq` -- an invalid instruction for byte counts 3/5/6/7.**
   `loadSizedFromFrame`/`loadSizedFromAddr`'s `movz<suffix>q` construction
   has no real x86 width for those sizes (only 1/2/4/8 are real); a
   `char[3]` array's own declared read-back size (3) hit this. Fixed via
   `roundUpToLoadableWidth(int size)` (`size<=2?size : size<=4?4 : 8`),
   applied in both methods -- safe because `ALLOC` always rounds frame
   size up to 16 bytes (guaranteed slack past any field) and only the
   declared low `size` bytes are ever semantically read back by any real
   consumer.

3. **`LOOKUP_ARRAY` couldn't distinguish "small array pushed by value as
   one word" from "a plain pointer value."** Its existing dispatch only
   split `pendingBlockSize<=0` (a plain pointer, the unsafe-dynarray
   fallback) from `pendingBlockSize>0` (a multi-word block via
   `pushBlockFromFrame`'s reversed-word convention). A small (<8-byte)
   fixed array pushed by value as one natural-byte-order word (e.g.
   `letters: mut char[3]`) fell into the pointer fallback, corrupting the
   packed value into a bogus address and segfaulting on dereference.
   Fixed via a negative-sentinel encoding: `case PUSH`'s `$offset`,
   `size<=8` branch now sets `lastValueBlockSize = -size` specifically
   when `pendingBlockSize==0 && size<8` (nothing already pending, and
   narrower than a pointer, which is always exactly 8 bytes). `LOOKUP_ARRAY`
   gained a `pendingBlockSize<0` branch (checked before the `==0`
   fallback): pops index and the packed value, computes
   `index*elemSize*8` as a bit-shift count, right-shifts, masks to
   `elemSize` bytes if `elemSize<8`, pushes the result -- pure register
   arithmetic, never a memory dereference.

**Known separate gap surfaced while regression-testing this fix (NOT
fixed here, NOT caused by this fix):** `LOOKUP_ARRAY_LHS` (the *write*
side of array indexing) has no equivalent to sub-bug 3 above -- its own
doc comment assumes its base always arrives via a plain `ADDR` (a single
address word), but `bd.cells[2] = 8` (writing through an array-typed
*struct member*) instead emits `PUSH <memberSize> $offset` (the read-side
convention, pushing the member's value/block) ahead of
`LOOKUP_ARRAY_LHS`, which then treats the last-popped stack word as if it
were an address. Confirmed via gdb against `array_struct_member_cg_test`
(segfaults, pre-existing, unrelated to the fix above -- `LOOKUP_ARRAY_LHS`
is a separate switch case this fix never touched). Would need the same
`pendingBlockSize` treatment as `LOOKUP_ARRAY`'s read side, but for
writes the fix must also produce a real *address* to store into rather
than just a value -- not a small change, left for a dedicated round on
array codegen (per the user's own "arrays fine, we haven't really
addressed arrays yet, but what's wrong with structs" framing, this round
stayed scoped to structs).

## Fixed: char literals had no `PUSH` implementation at all -- silently pushed 0 instead

Found while auditing the whole language's keyword/builtin list for the
user, then verifying by compiling every one of the 801
`caspien-compiler/examples/*.caspien` fixtures all the way through to
real generated assembly: `'a'`/`'\n'`/`'\0'`/... -- a char literal used
anywhere as a `PUSH` operand -- had **zero** branches in `PUSH`'s own
operand dispatch to recognize it at all. It silently fell all the way
through to the generic "not a declared global" fallback, which pushes a
placeholder `0` and emits a `TODO(codegen)` comment -- meaning *every*
char literal in the entire language, in any context (a bare `let c =
'a'`, an array-of-char literal, a comparison, a struct field, ...),
silently compiled to the wrong value, always 0, everywhere, since this
project began. Integer and float literals both had their own dedicated
branches here from early on; char literals were simply never added
alongside them.

This one bug turned out to be hiding behind three fixtures already
being tracked, session after session, as "pre-existing, unrelated
baseline failures" (`array_literal_narrow_elem_cg_test`,
`struct_array_member_narrow_elem_cg_test`, `struct_mixed_width_cg_test`)
-- each of those uses char literals, so each one's compiled output used
to carry this exact `TODO(codegen): PUSH of '...' (not a declared
global)` comment. Confirmed via a full before/after sweep of all 801
example fixtures (grepping the real generated `.s` output of every one
that compiles cleanly, not just spot-checking): 22 distinct fixtures hit
this before the fix, 0 after.

Fixed with two new helpers, `isCharLiteral`/`decodeCharLiteral`, added
right next to `isFloatLiteral` and wired into `PUSH`'s operand dispatch
the same way the int/float branches already are. A char-literal operand
is always exactly `BytecodeEmitter.escapeForBytecode`'s own serialized
shape -- a single quote, one real character's worth of content (either
one literal byte, or a two-character `\x`-style escape for any of the
ten escapes this language's lexer recognizes: `\\`, `\"`, `\n`, `\t`,
`\r`, `\0`, `\b`, `\f`, `\a`, `\v`), then a closing single quote --
`decodeCharLiteral` is the exact structural inverse of that method, kept
in lockstep with it deliberately. A char is always exactly one
compile-time-known byte (`PRIMITIVE_SIZE.put("char", 1L)` in the front
end), so this needs no runtime work at all -- an immediate-load into
`%rax`, the identical "no instruction, direct literal replacement"
treatment an integer/float literal already gets.

**Verified three ways.** (1) A dedicated test pushing all ten escape
characters plus three ordinary printable ones (`'a'`, `'Z'`, `'!'`)
through a real `printf("%d\n", c)` call: printed `0 9 13 8 12 7 11 10 97
90 33` -- every value exactly correct against real ASCII. (2) The
existing `char_literal_escape_bytecode_test.caspien` fixture (which
already covered the eight control-character escapes) now compiles with
zero TODOs, where it previously had eight. (3) Re-ran the full 801-
fixture sweep before and after: the exact 22 fixtures carrying a
char-literal `TODO(codegen)` comment before the fix now carry none,
and nothing else in the corpus changed. The three previously-"unrelated
baseline failure" fixtures named above **still fail** after this fix
-- but now for their own separate, genuine bugs (an invalid `movzqq`
instruction mnemonic in one, a plain segfault in the other two),
completely unmasked now that the char-literal TODO that used to sit on
top of them is gone. Not fixed here -- flagged as their own separate,
still-open issues.

## Fixed: a `void`-returning function with no explicit `return` leaked garbage as its exit code

Found while manually verifying the `SLEEP`-mnemonic work below on a real
program run directly (not through a C test harness that always itself
returned 0, which is how every earlier real-execution check in this
project's own history happened to mask this): a bare
`func main() void { printf("hello\n") }`, with no `return` statement at
all, exited with code **6** instead of 0 -- the exact character count
`printf` itself returns for a 6-character format string.

Root cause: `FUNC_END`'s own case (the assembly emitted at a function's
true textual end) calls `emitFunctionEpilogue()` directly, which never
touches `%rax`. The existing `RET` case already zeroes `%rax`/`eax`
before handing off to that same epilogue helper, for exactly this
reason (see its own doc comment -- "a void function now explicitly
zeroes it, for a predictable exit 0"), but that zeroing lives on the
`RET` line itself. A void function with no explicit `return` statement
never emits a `RET` line at all -- control falls straight through into
`FUNC_END`'s own copy of the epilogue, which skipped the zeroing
entirely, letting whatever the function's last real instruction left in
`%rax` (here, `printf`'s own return value) leak through as the process
exit code once `main` is linked and run directly (as opposed to called
from a C test harness that never inspected `main`'s own return value).

Fixed by zeroing `%rax` in `FUNC_END`'s own case too, mirroring `RET`'s
`size == 0` branch exactly. This is safe unconditionally: a non-void
function is guaranteed by the type-checker to return a real value on
every reachable path, so `FUNC_END`'s own epilogue copy can only ever
be *live* for a void fall-through -- for a non-void function it's the
same harmless dead code (unreachable after an already-emitted `RET`)
this method's own doc comment already described.

Verified: the same bare `printf`-only `main` now exits 0. A `main`
returning `imut s32` via a real value (not a fall-through) still
returns the correct value unchanged (confirmed with a small extern-based
fixture returning 5, both before and after this fix). Re-ran the full
21-fixture `examples/*_cg_test.caspien` suite: identical pass/fail/
segfault results in every case (the same pre-existing, unrelated
failures as always -- `array_literal_narrow_elem_cg_test`,
`array_struct_member_cg_test`, `atomic_swap_global_scalar_cg_test`,
`register_overflow_args_even/odd_cg_test`,
`struct_array_member_narrow_elem_cg_test`, `struct_mixed_width_cg_test`
-- unchanged), and several previously-"passing but with an untested
garbage exit code" fixtures now confirmed exiting 0 as well.

## `YIELD` implemented -- a plain call to `sched_yield()`

Previously fell through to the generic `TODO(codegen)` comment (silent
no-op) like every other unimplemented mnemonic. Now a new `case
"YIELD"` calls POSIX's own `sched_yield(void)` directly, by its own
fixed, literal C symbol name -- no `EXTERN` declaration needed, the
same "already linked in, call it by name" precedent `exit`/
`pthread_exit` already established for `EXIT`/`EXIT_THREAD` just below.
No arguments, no return value read by anything (`sched_yield` itself
returns an `int`, unconsumed here).

Confirmed empirically, not assumed, to link and run correctly on
*both* real targets with **zero per-target branching needed at all**:
glibc (`linux`) and mingw-w64's own libpthread/`sched.h` compatibility
layer (`windows_gnu`, already linked in via `-pthread`/`-static` for
`pthread_create`/`_join`/`_exit`) both export `sched_yield` under this
exact same name. `pthread_yield` -- the more obvious-looking choice,
given `pthread_create`/`_join`/`_exit` already being called this exact
way -- was tried first and rejected: it's a non-standard GNU extension
glibc happens to also provide, but mingw-w64's own winpthreads does
not export it at all, confirmed by a real "undefined reference to
'pthread_yield'" link failure under the `windows_gnu` target.

`sleep(...)` (the language's own new keyword for this, see the sibling
`caspien-compiler` project's own CLAUDE.md) does **not** go through the
ordinary `CALL`/`EXTERN_CALL` machinery at all -- the front end emits a
dedicated `SLEEP <realFuncName> <durationType>` mnemonic instead (e.g.
`SLEEP sleep_call indeterminate_u64`), with the real, dynamically
resolved `@sleep` function's own mangled name carried directly as the
line's own operand (never a hardcoded literal, the same care
`GT_INIT`/etc.'s own naming bug -- see the sibling project's CLAUDE.md
-- showed is needed whenever codegen calls something by name at all).
New `case "SLEEP"` (right after `case "YIELD"`): pops the one pushed
duration argument off the real stack into `%rax`, moves it into
`argReg(0)` (the ABI-correct first-argument register/syntax for
whichever target this is, win64 or SysV -- not hardcoded), calls the
real function by the name given in the line's own second token, then
pushes `%rax` back as the call's own u64 result (the real C `sleep`'s
remaining-unslept-seconds return value). Modeled on `GT_ALIVE_CHECK`'s
own shape (pop -> move to `argReg(0)` -> call -> push result), not the
full `CC_START`/`CC_END`/`PUSH_RET` machinery, since a `@sleep`
function's argument/return shape is always exactly one fixed 8-byte
value each way, by construction (`TypeChecker.requireSleepSignature`).
The line's own trailing duration-type operand is not actually read or
branched on here -- the width is always 8 bytes by construction -- it's
only there because every other bytecode instruction that names a type
at all states it (`NEG`/`DEREF`/`CLONE`/`LEN_SCAN`), and `SLEEP`
follows that same convention for consistency/future-proofing.

## `EXIT_THREAD`, and pushing a real function's own address as a value

Two small, related additions for real, OS-thread-backed "par"/"await"
(see the sibling `caspien-compiler` project's own CLAUDE.md for the
full design and the compiler-side bugs found along the way -- this
project's own share of that work is these two items).

**`EXIT_THREAD`** -- the real-thread counterpart to `EXIT`, emitted
unconditionally at the tail of every `@async` function's own
`gt_routine` unwind label (already true, unmodified, before this
round: `emitGtRoutineBody`'s existing `else if (info.isAsync) {
line("EXIT_THREAD"); }`), reached the identical way `EXIT` is -- an
unwind propagating all the way up through every frame this thread's own
call stack has. Ending the whole *process* here the way `EXIT` does
(calling `exit`) would be wrong: an `@async` function only ever runs on
its own OS thread (started by `@par_call`/`@await_call`'s own
`pthread_create`), never on the process's own main/startup path, so
calling `exit` there would tear down every *other* thread too --
including whichever one is `await`-ing or `par`-polling this one.
Implemented by mirroring `EXIT`'s own shape exactly but calling
`pthread_exit` instead, passed a null `void*` return value (nothing
downstream -- no real `pthread_join` call site in `@await_call` ever
reads a thread's own exit-value pointer back). `pthread_exit` needs no
new `EXTERN`-style handling here: like `exit` before it, it's called
directly by its own fixed, literal C symbol name, already linked in
(this toolchain's `-pthread` link flag, not this project's concern).

**A bare function's own address can now be pushed as a value** --
`declaredFunctionNames`, a new `Set<String>` populated during
`collectDeclarations`'s existing single scan (alongside the pre-existing
`GLOBAL`/`ALLOC_STATIC`/`STRING` collection) by also recording every
`"FUNC_START name"` line's own `name`. `case "PUSH"`'s final `else`
branch (previously: anything not a `$offset`, `null`, integer, float,
incomplete-range pseudo-member, `ARG`/`FARG`, string-pool id, or
declared global fell straight to the generic `TODO(codegen): ... not a
declared global` placeholder, pushing `0`) now checks this set first:
a name matching a real, compiled function in this same compilation unit
pushes that function's own address (`leaGlobalToReg`, the identical
"decays to its own address" mechanism a string literal's own PUSH
already uses) -- **never** followed by a value-load the way an
ordinary global's PUSH is (a function's own "value" *is* its address;
there is no separate stored value to read). This was found necessary,
not hypothetical: the sibling `caspien-compiler` project's own
`par`/`await` desugaring needs to push a compiler-synthesized
trampoline function's own address as the first argument to
`@par_call`/`@await_call` (pthread's own `void*(void*)` start-routine
argument), and the low-order bytecode this produces is genuinely just
`"PUSH 8 __trampoline_someFunc"` -- a bare function name, indistinguishable
in shape from any other unresolved symbolic name this pass has always
had to flag, until this addition let it tell the two apart by checking
whether that exact name was ever really compiled as a function in this
same run.

**Verified end to end**: `objdump -T` on a real, fully linked test
binary exercising both `par` and `await` on both a scalar-argument and
a void `@async` function shows genuine dynamic-symbol references to
`pthread_create`/`pthread_join`/`pthread_exit`, confirming the pushed
trampoline address, the `EXIT_THREAD`-driven `pthread_exit` call, and
the whole real-OS-thread path all actually work together, not just
individually. Re-ran the full 21-fixture `examples/*_cg_test.caspien`
suite before and after both changes: identical pass/fail/segfault
results in every case (the suite's own pre-existing failures --
undefined `gt_init`/`gt_register`/`gt_destruct` references from
fixtures using hand-written camelCase stubs instead of the real
stdlib, one unrelated assembler error on an unsupported instruction
mnemonic -- are all unchanged, confirmed by inspecting each failing
fixture's own compiled assembly and low-order bytecode: none of them
reference `EXIT_THREAD`, `pthread`, or push a bare name that happens to
coincide with a declared function).

## GT_UNWIND/THROW/EXIT implemented -- exception unwinding now actually works

Previously `GT_UNWIND` and `THROW` both fell through to the generic
`TODO(codegen)` comment (silently no-op'd), and `EXIT` was implemented
on the false assumption that it "falls through to the FUNC_END that
always immediately follows" -- true only because `GT_UNWIND`/`THROW`
were themselves no-ops, so `gt_routine__main:` was never actually
reached at all. Prompted by the user directly: the exception-handling
design (the `gt_routine` prologue every function gets, `THROW`'s own
bytecode shape, `GT_UNWIND`'s job) was already fully and accurately
documented -- see the compiler's own design notes
(`emitThrow`/`emitGtRoutineAlloc`/`emitGtRoutineBody`'s doc
comments) -- nothing about the *design* was ever missing or lost. What
was missing was ever actually implementing it here. The original
`examples/gt_unwind_*_cg_test.caspien` fixtures also proved a
further-along backend (named `AssemblyEmitter`, run via a `CodegenMain
--run` entry point) once existed with a real, execution-verified
implementation of this -- but neither that class nor that entry point
exists anywhere in this tree; only the fixtures' own doc-comments (and their expected
output) survive. This round is a fresh implementation against that
same documented design and cross-checked against those exact fixtures'
own documented expected output, not a recovery of any lost source.

**`GT_UNWIND`** -- "make the assembly to return from a function as you
normally would, but then locate the first variable in that stack frame
... and set the instruction pointer to that," confirmed directly (the
user's own notes, verified to match the Project doc's own `emitThrow`/
`emitGtRoutineBody` comments verbatim). Implemented as: the ordinary
function epilogue (`movq %rbp,%rsp; popq %rbp` -- deallocate this
frame, restore the *caller's* rbp), then, instead of `ret`-ing back
into the caller's body, the return address left on the stack is
discarded unread (`addq $8,%rsp`) and control jumps straight to
whatever the caller's own `gt_routine_address` slot holds. That slot
is always the very first local in every frame that has one (see
`emitGtRoutineAlloc`'s own doc comment) and was confirmed, directly
against this project's own real lowered bytecode, to always land at a
fixed `$-8` -- so `GT_UNWIND` reads `-8(%rbp)` (post-restore, i.e. the
*caller's* own rbp) and jumps there. This chains correctly through an
arbitrary call depth with no special-casing, since the gt_routine
scaffolding is a whole-program trigger applied uniformly to every
function that needs it (`checker.usesOwnsRefDynNew() ||
checker.usesThrow()`), so a caller reached this way is always
guaranteed to have that same slot at that same offset.

**`THROW <value> <label>`** -- a plain, unconditional `jmp` to the
named label (always this same function's own `gt_routine__<name>`
label -- confirmed by `BytecodeEmitter.emitThrow`'s own doc comment, a
throw can only ever name its own enclosing function's label). The
thrown value operand is deliberately not read, stored, or propagated
anywhere -- there is no `catch` construct anywhere in this language yet
(no syntax, no type-checking support, no runtime protocol for a
handler to retrieve a thrown value from), so nothing downstream could
consume it regardless. Only the documented control-transfer half of
`THROW` is real, working behavior; a real `catch` mechanism, if this
language ever gets one, is a separate, later piece of work.

**`EXIT`** -- found, via this exact work, to have been *wrong*, not
just unimplemented, the moment `GT_UNWIND`/`THROW` became real: `EXIT`
is reached only when an unwind propagates all the way up to the true
root (`main`/`@event_loop`) itself, and the label immediately following
it in the bytecode (`end_of_gt_routine__main:`) is main's own *ordinary
body* -- not a second copy of the epilogue. The old "falls through to
FUNC_END" implementation, now actually exercised for the first time,
fell straight through into `end_of_gt_routine__main:` and silently
re-ran main's entire body a second time instead of ending the program.
Fixed by having `EXIT` actually call the C library's own `exit(1)` --
matching the Project's own `gt_unwind_throw_multi_frame_cg_test`
fixture doc comment: unwinding to the true root has its own distinct,
documented status (exit code 1), separate from an ordinary,
exception-free `exit 0` completion of main.

**Verified two ways**, both directly against the Project's own
original fixture doc-comments' documented expected
behavior:
1. A single-function throw (`main` constructs an `owns` local, calls a
   helper that immediately throws, never returns normally): prints
   "main enter" / "foo enter" / "destruct called" (proving the owns
   local was genuinely destructed before the process ended), then exit
   code 1, with the statement after the call never reached.
2. A real three-frame-deep throw (`main` -> `g` -> `f`, `f` throwing,
   each frame declaring its own `owns` local): prints "main enter" /
   "g enter" / "f enter" (the call chain genuinely ran), then exactly
   three "destruct" lines in innermost-first order (f's own local, then
   g's, then main's -- proving `GT_UNWIND` correctly chains
   caller-to-caller through every frame, never skipping or
   double-destructing one), then exit code 1, with "unreached" never
   printed -- an exact match, line for line, to
   `gt_unwind_throw_multi_frame_cg_test.caspien`'s own documented
   expected output.

Also re-ran the full 21-fixture `_cg_test` regression suite (`linux`
target) before and after this change: identical pass/fail/segfault
results in every case except the two `gt_unwind_*_cg_test` fixtures
themselves (still failing, but on a different, unrelated, pre-existing
bug -- see below) -- zero regressions.

**A separate, pre-existing bug found along the way -- now fixed** (in
`caspien-compiler`/`ASTGenerator`, not here; see that project's own
CLAUDE.md for the full writeup): the two `gt_unwind_*_cg_test.caspien`
fixtures (which use self-written, camelCase-named
`@gt_init`/`@gt_register`/etc. stub functions, e.g. `func gtInit()`,
rather than importing the real `stdlib/gt_*.caspien`) used to fail to
link -- `undefined reference to 'gt_init'`/`'gt_register'`/etc.
`GT_INIT`/`GT_REGISTER`/`GT_DESTRUCT`/`GT_ALIVE_CHECK`'s own
codegen-side implementations all call these by their fixed, literal
bytecode-level names (`gt_init`, not `gtInit`), but a `@gt_init`-
decorated function used to be emitted under its own declared source
name, not renamed/aliased to the literal name the bytecode expects --
confirmed directly: the real `stdlib/gt_init.caspien` etc. happen to
*also* name their functions literally `gt_init`/`gt_register` already,
which is why every other gt-using fixture and this session's own
`test5.caspien`/multi-frame verification programs (both of which
import the real stdlib) never hit this. Fixed front-end-side
(`TypeChecker.forceGhostTableFunctionNames` now forces the mangled name
of any `@gt_init`/`@gt_alive_check`/`@gt_destruct`/`@gt_register`-
decorated function to the literal decorator name, regardless of its
declared source name) -- not a codegen-stage change, and unrelated to
this round's `GT_UNWIND`/`THROW`/`EXIT` work. Both fixtures now link
and run correctly, verified through the real shipped orchestrator on
both `linux` and `windows_gnu`/Wine targets.

## Three real bugs found and fixed via one end-to-end test program

A minimal real test (`let p = mut new Point{...}; match Some(p){
unsafe{ printf("%d", mut (p.x+p.y)) } }`) compiled cleanly at every
stage but printed nothing instead of the expected `23`. Chasing that
down to a real, verified fix surfaced three separate, previously-latent
bugs in this backend, in the order they were found:

**1. `GT_REGISTER` -- new opcode, added alongside `caspien-compiler`'s
own `NEW` fix** (see that project's CLAUDE.md for the compiler-side
half). New `case "GT_REGISTER"` mirrors `GT_ALIVE_CHECK`/`GT_DESTRUCT`'s
own shape (pop the pointer, move it into the first argument register,
call the real stdlib function by its own unmistakable name) with one
difference: the pointer that must survive the call is kept on the real
stack (pushed twice, one throwaway copy consumed as the argument, one
surviving copy left underneath), never in a register. This was found to
be *necessary*, not just cautious, by a real segfault: "callee-saved,
survives the call" (the assumption `NEW`'s own r12/r14 usage relies on)
only holds when the callee is a real, externally-compiled function
(malloc) that genuinely honors the ABI's callee-saved registers --
`gt_register` is a Caspien-compiled function, and `FUNC_START`/
`FUNC_END` (below) never save/restore r12-r15/rbx, only rbp.

**2. `emitAlignedCall`'s pre-alignment-rsp bookkeeping wasn't safe
across a call into a Caspien-compiled function that itself makes
calls.** This is the general form of bug 1 above, and it's the one that
actually explained the silent "prints nothing" symptom (bug 1's fix
alone just moved the failure from a segfault to this). Every call site
in this backend wraps its `call` in a save-align-restore sequence to
guarantee 16-byte stack alignment; the "save" half used to stash the
pre-alignment rsp in `%r13`, on the same false "callee-saved, survives
the call" assumption bug 1 exposed. Since r13 has no protected status,
a callee that itself makes further calls (like `gt_register`, calling
`gtSlotAt`/`gtReadSlot`) reuses r13 for its *own* copy of this exact
same dance, silently overwriting the value the outer call was relying
on. Confirmed directly via gdb: r13 held the correct pre-call rsp
immediately before `call gt_register`, but held something else entirely
(gt_register's own last internal save, off by over a hundred bytes) the
instant it returned -- which the caller then blindly copied into rsp,
corrupting its whole abstract stack depth (and with it, the just-`new`'d
pointer's eventual home) from that point on, silently, no crash.

Fixed by not trusting *any* register to survive a call at all: the
pre-alignment rsp is now stashed in a dedicated stack slot reserved
right at the call site (`subq $16,%rsp` after aligning; store to
`8(%rsp)`; after the call, `movq 8(%rsp), %rsp` loads it straight back).
A stack slot can't be silently reused by a nested call the way a shared
register can -- a nested call just gets its own slot, further down.
This relies on nothing but the one invariant every function here
already upholds regardless of what it's compiled from: a well-behaved
callee always returns with rsp exactly where it was the moment `call`
executed. Verified with no regressions by reverting to the old
r13-based version, rebuilding, and confirming byte-identical pass/fail/
segfault results across every `examples/*_cg_test.caspien` fixture,
before and after.

**3. `windows_gnu` conflated syntax with ABI at several call sites --
found only once this whole fix was checked through the *real* shipped
orchestrator and its actual default target, not just the dev-only
`linux` config this backend is normally tested against.** `isWindows()`
is true only for the MASM target; `isWinAbi()` is true for *either*
Windows target (see "What's implemented" below) -- but `emitMallocCall`,
the `NEW_FROM_STRING` strlen/malloc setup, `RESIZE`/`URESIZE`'s realloc
setup, and the `GT_REGISTER`/`GT_ALIVE_CHECK`/`GT_DESTRUCT` call setups
all picked their argument register(s) off `isWindows()` instead of
`isWinAbi()` -- so `windows_gnu` (AT&T syntax, win64 ABI) fell into each
site's AT&T-syntax branch and got SysV's registers (`rdi`/`rsi`)
instead of win64's (`rcx`/`rdx`). Confirmed directly: malloc read its
size out of whatever garbage sat in `rcx` instead of the real size in
`rdi`, and the resulting wild/failed allocation surfaced downstream as
a crash in `NEW`'s own `rep movsb`. Fixed by routing every one of these
sites through the pre-existing `argReg(index)` helper (already
correctly `isWinAbi()`-gated, already used by the general `POP ARGn`
call-marshalling path) instead of a fresh ad-hoc `isWindows()` check,
keeping `isWindows()` for what it actually gates: assembly syntax. Zero
effect on `linux` or `windows` (MASM) -- `argReg(0)` returns the exact
same register text those two already had hardcoded.

All three fixes verified together, end to end, through the real shipped
orchestrator's actual default path: the test program above now compiles
and, run as a genuine PE32+ `.exe` under Wine (`windows_gnu`, the real
mingw-w64 cross-compiler, no dev-only config overrides), prints `23`
and exits 0 -- as does the identical program built for `linux`. See
"Verification" below for the updated real-execution record this adds.

## New project: stage 4, just starting

"Begin building the fourth program... this will generate assembly
instructions from the lower order bytecode. The code generator will
use a config file as necessary. Current target is x86 windows, but
whether windows or linux is the target is down to the config,"
confirmed directly.

This is a genuine **start**, not a finished backend. It correctly
implements a small, honest subset of the low-order instruction set --
enough for real function bodies made of locals, integer arithmetic,
and returns -- and reports anything else as a visible
`TODO(codegen): ... not yet implemented` comment in its output rather
than silently dropping or guessing at it.

## What's implemented

`X86Backend` translates the low-order bytecode's own stack-machine
model directly onto the real x86-64 hardware stack: every bytecode-level
push (`PUSH`, `ADDR`) becomes a real `push`, every bytecode-level pop
(`ASSIGN`, `ADD_INT`/`SUB_INT`/`MUL_INT`, `RET`) becomes a real `pop`.
This is deliberately not an optimizing translation -- a real backend
would keep short-lived values in registers -- it's the simplest correct
mapping, chosen so this first version could be checked by actually
assembling and running its output, not just read for plausibility.

Mnemonics handled: `FUNC_START`/`FUNC_END` (prologue/epilogue), `ALLOC`
(frame reservation, rounded to 16 bytes), `ADDR` (both a stack-relative
`$offset` and a bare global name), `PUSH` (a stack-relative `$offset`,
a plain integer literal, or `null`), `ASSIGN` (a plain store, ignoring
the three now-identical size operands -- this version only really
handles 8-byte word), `ADD_INT`/`SUB_INT`/`MUL_INT`, `RET`, `CALL`
(name only, no real argument marshalling yet), `CC_START`/`CC_END`
(currently no-ops), and bare labels (`@name:` -> a real assembly
label).

One `CodegenConfig` (`codegen.config`, fixed path like
`compiler.config`) selects the target: `target windows`,
`target windows_gnu`, or `target linux`. All three share the identical
`X86Backend` class and translation logic; what varies is:

- **assembly syntax** -- GAS AT&T (`linux`, `windows_gnu`) vs
  MASM-style Intel (`windows`) -- gated internally on `isWindows()`,
  which is true *only* for the MASM target;
- **ABI** -- SysV (`linux`: rdi/rsi/rdx/rcx/r8/r9, no shadow space, the
  varargs `%al` convention) vs win64 (`windows`, `windows_gnu`:
  rcx/rdx/r8/r9, 32-byte shadow space, no varargs convention) -- gated
  internally on a separate `isWinAbi()`, true for *either* Windows
  target.

`windows` and `windows_gnu` share the exact same ABI and differ only in
syntax -- `windows_gnu` was added after the first "windows" target
turned out to mean MASM/ml64, but the real intended Windows toolchain
is gcc/mingw-w64, whose own assembler (GNU `as`) wants the same AT&T
syntax as Linux, just with the win64 ABI instead of SysV's. Splitting
syntax and ABI into two independent flags (rather than widening the one
`isWindows()` boolean) kept the change small: only the handful of
genuinely ABI-driven call sites (`argReg`'s register table, and
`emitAlignedCall`'s shadow-space/varargs choice) needed to switch from
`isWindows()` to `isWinAbi()`; every one of the ~90 other `isWindows()`
call sites is a pure syntax choice and was untouched.

## Verification

**All three targets have now actually been built and run**, end to
end, in this sandbox, using a real "Hello World!" program (`printf`
only, no locals) run through the real `caspien-compiler` ->
`caspien-optimizer` -> `caspien-lowerordergenerator` pipeline and then
this project's own `Main`:

- `target linux`: assembled with `as --64`, linked with `gcc -no-pie`
  against a tiny C harness, run directly. Output: `Hello World!`, exit 0.
- `target windows_gnu`: assembled *and* linked in one step with a real
  `x86_64-w64-mingw32-gcc` cross-compiler (installed into this sandbox
  for the purpose), producing a genuine PE32+ .exe, then actually run
  under Wine (also installed here). Output: `Hello World!`, exit 0
  (Wine logs a batch of unrelated GUI/COM warnings first, since this
  sandbox has no X server or wine32 for the desktop shell -- irrelevant
  to a console-only program; the program's own real stdout and exit
  code are what confirm it worked). **Since extended well past this
  trivial case** -- see "Three real bugs found and fixed" above: a real
  program with a heap-allocated struct, the ghost-table runtime's own
  register/lock/search logic, and a `printf` call all together now also
  runs correctly under this exact same real toolchain (mingw-w64 +
  Wine), through the real shipped orchestrator's own default path, not
  just this project's own dev-only `linux` config. This is the first
  time anything beyond "Hello World" was ever execution-verified on
  `windows_gnu` -- see that section for the three ABI-register-selection
  bugs this surfaced and fixed, none of which a `linux`-only test could
  ever have caught (`linux`'s SysV registers and win64's registers only
  ever disagree on `windows_gnu`/`windows`).
- `target windows` (MASM/ml64): still **not** execution-verified -- no
  MASM/ml64 toolchain exists in this sandbox, and none was installed
  for it (unlike `windows_gnu`, which had a real cross-toolchain
  available via `apt`). Treat this one target as structurally
  implemented but unverified, same as before.

An earlier, separate real test (still valid, not re-run after the
`windows`/`windows_gnu` split since it only exercises SysV-independent
code) covers plain integer locals/arithmetic on `target linux`:

    func compute() mut u64{
    	let a = mut 3
    	let b = mut 4
    	let c = mut (a + b)
    	return c
    }

-- assembled with `as`, linked against a tiny C harness with `gcc`, and
run. Real output: `compute() = 7`.

A much larger real-execution pass (structs, fixed arrays, heap
`raw`/`deref`, safe/unsafe dynarrays with resize, the ghost-table
runtime's own lock/search logic, multi-argument `printf` calls) is also
verified on `target linux` -- see the "code generator: ~70 remaining
mnemonics" work log (or ask the assistant that did it) for that
fixture set and its real output.

## Known gaps -- what this version does *not* do yet

- **No real calling-convention argument marshalling.** `CC_START`/
  `CC_END` are no-ops; a call with register-transferred arguments
  (`POP ARGn`) isn't handled -- only a zero-argument `CALL name` works
  today. This is the very next thing a real version needs, and it's
  where `codegen.config`'s own per-target register-name table (win64:
  rcx/rdx/r8/r9 + 32-byte shadow space; SysV: rdi/rsi/rdx/rcx/r8/r9, no
  shadow space) will actually get used -- not built yet.
- ~~No float support~~ **Stale -- fixed long ago, not caught here until
  now.** `ADD_FLOAT`/`SUB_FLOAT`/`MUL_FLOAT`/`DIV_FLOAT`, every
  `EQ_FLOAT`/`NEQ_FLOAT`/`LT_FLOAT`/... comparison, `RET_FLOAT`, and
  `FLOAT_CHECK` (the `match f{ finite:{} infinite:{} nan:{} }` float-
  classification feature lowers to this) are all implemented with real
  SSE/xmm-register work, not the generic `TODO(codegen)` comment this
  bullet used to describe -- this whole bullet was simply never updated
  when that work was done. Verified directly, again, while investigating
  a user question about `FLOAT_CHECK`'s status: a real program running
  all three categories together (a finite value, `1.0/0.0`, `0.0/0.0`)
  compiles with zero TODOs in the generated assembly and correctly
  prints `finite`/`infinite`/`nan`.
- **Every other mnemonic not listed above** (`AND`/`OR`/`NOT`,
  `ATOMIC_*`, `NEW`/`CLONE`/`DEREF`/`ADDR_OF`, `LOOKUP_*`,
  `DOT`/`DOT_LHS`, `STACK_LOCK`, struct/enum/global declarations, ...)
  -- may still fall through to the generic `TODO(codegen)` comment,
  though this "Known gaps" list as a whole predates most of the real
  implementation work now documented in the sections above (calling-
  convention argument marshalling, `AWAIT_CALL`/`PAR_CALL`/`YIELD`/
  `SLEEP`, `GT_UNWIND`/`THROW`/`EXIT`, float support just corrected
  above) and has not been comprehensively re-audited against the
  current source -- treat any specific claim in this bullet as
  unverified until checked directly against `X86Backend.java`, the way
  `FLOAT_CHECK` just was. `JOIN` no longer applies at all -- removed
  from the language entirely (see the sibling `caspien-compiler`
  project's own CLAUDE.md). None of what genuinely is still missing is
  silently miscompiled; a truly unhandled mnemonic is visibly flagged
  in the generated assembly text via that same `TODO(codegen)` comment,
  so a partially-covered program's output makes clear exactly where it
  stops being trustworthy.
- **No real register allocation** -- the whole-stack-machine-to-real-
  stack translation is correct but slow (every intermediate value round-
  trips through memory via a real `push`/`pop`), deliberately, for this
  first version's own verifiability.
- **The `windows` (MASM/ml64) path is still unverified** -- no MASM/
  ml64 toolchain exists in this sandbox. `windows_gnu` is no longer in
  this category; see "Verification" above.

## Files

- `BytecodeToken.java`, `BytecodeParser.java` -- own copies (package
  `caspien.codegen`) of the plain-text bytecode reader every sibling
  stage in this toolchain already has its own copy of
- `CodegenConfig.java` -- loads `codegen.config`, resolves the target
- `CodegenException.java` -- the one exception type for a fatal codegen
  error
- `X86Backend.java` -- the actual translation, both targets
- `Main.java` -- CLI entry point
- `codegen.config` -- this project's own config (currently set to
  `target linux`, since that's the only execution-verified target here)

## Usage

    codegen -i input.txt output.s

`input.txt` is expected to be the sibling `caspien-lowerordergenerator`
project's own plain-text output. `codegen.config` (current working
directory) selects the target.

## Provenance

This project was originally split out of a single, combined
codebase that held all three pipeline stages (compiler,
optimizer, codegen) together as one hypothetical whole -- the optimizer
stage was later itself split in two, and this project is the first
real code written for the codegen stage that was always named in that
original split's own Provenance notes. It is now one of four sibling
projects:

- **caspien-compiler** -- code (.caspien) -> bytecode (text)
- **caspien-optimizer** -- bytecode -> bytecode (shallow methods only,
  currently all no-op passes)
- **caspien-lowerordergenerator** -- bytecode -> low-order bytecode
  (the real lowering work)
- **caspien-codegen** (this project) -- low-order bytecode -> x86
  assembly (config-driven target; just starting -- see "Known gaps")

Each stage's plain-text bytecode is the interchange format between
them -- there is no shared library, no shared build, no shared repo.

## New: f64 support (size-8 float forms)

`X86Backend` handles `R_XTOG`/`R_GTOX`/`R_LDX`/`R_STX`/`R_POPX`/`R_GETRETF`/`R_XVAR`/`R_FBINX` at size 8 (`movq`/`movsd`/`addsd`... vs the size-4 `movd`/`movss`), `FCONV` (cvtss2sd / cvtsd2ss), f64 statics as 8-byte IEEE bits. Verified on Linux only (`tests/f64_test.caspien`); win64 varargs duplication of an f64 and the Intel/MASM forms are unexecuted. See the root CLAUDE.md.
