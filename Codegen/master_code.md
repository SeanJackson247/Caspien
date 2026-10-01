# master_code.md -- Codegen, full project mirror

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
stage 4 of the Caspien toolchain (low-order bytecode -> x86-64
assembly text; the orchestrator itself invokes `as`/`gcc` for the
final assemble+link step). Compiled `out/` class files are not
part of this mirror.

## File index

- CLAUDE.md
- codegen.config
- src/main/java/caspien/codegen/BytecodeParser.java
- src/main/java/caspien/codegen/BytecodeToken.java
- src/main/java/caspien/codegen/CodegenConfig.java
- src/main/java/caspien/codegen/CodegenException.java
- src/main/java/caspien/codegen/Main.java
- src/main/java/caspien/codegen/X86Backend.java

### FILE: CLAUDE.md
```markdown
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

Verified: `stdlib/string.caspien` now goes through all four stages and links; the space literal now lowers to `PUSH 1 ' '` like every other char literal; Hello World output is unchanged. No regression fixtures (`examples/`) were available in the master code files to sweep.

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
documented -- see the attached Project's own `compiler_master_code.md`
doc (`emitThrow`/`emitGtRoutineAlloc`/`emitGtRoutineBody`'s doc
comments) -- nothing about the *design* was ever missing or lost. What
was missing was ever actually implementing it here. That same Project
doc's own `examples/gt_unwind_*_cg_test.caspien` fixtures also prove a
further-along backend (named `AssemblyEmitter`, run via a `CodegenMain
--run` entry point) once existed with a real, execution-verified
implementation of this -- but neither that class nor that entry point
exists anywhere in this dev tree, the Project's own earlier
`claude/codegen_master_code.md` snapshot, or anywhere else on this
machine; only the fixtures' own doc-comments (and their expected
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
`compiler_master_code.md` fixture doc-comments' documented expected
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
`master_code.md` that used to hold all three pipeline stages (compiler,
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
```

### FILE: codegen.config
```
# "target" selects the code generator's output: one of
#   linux         -- GAS AT&T syntax, SysV ABI (as/gcc/ld)
#   windows_gnu   -- GAS AT&T syntax, win64 ABI (gcc via mingw-w64)
#   windows       -- MASM/Intel syntax, win64 ABI (ml64/link -- this
#                    orchestrator can produce the .s but does not know
#                    how to assemble/link it; use --asm)
#
# Set this to "linux" if you move this project to a Linux machine building
# with native gcc/as instead.
target windows_gnu
```

### FILE: src/main/java/caspien/codegen/BytecodeParser.java
```java
package caspien.codegen;

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

### FILE: src/main/java/caspien/codegen/BytecodeToken.java
```java
package caspien.codegen;

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

### FILE: src/main/java/caspien/codegen/CodegenConfig.java
```java
package caspien.codegen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Loads `codegen.config` (fixed path, resolved relative to the current
 * working directory, the same convention `compiler.config` already
 * uses on the compiler/lowerordergenerator side) -- required, missing
 * entirely is a fatal error, same as that file.
 *
 * Format is deliberately minimal for this first version: one
 * "key value" pair per non-comment, non-blank line ('#' starts a
 * comment). The only key read so far is "target", one of "windows",
 * "windows_gnu", or "linux" -- "current target is x86 windows, but
 * whether windows or linux is the target is down to the config,"
 * confirmed directly; "windows_gnu" was added once it became clear the
 * real Windows toolchain in use is a GNU one (gcc/mingw-w64, assembled
 * with GNU `as`), not MASM/ml64 -- those two need genuinely different
 * assembly *syntax* (GAS AT&T vs MASM Intel) even though they share the
 * exact same win64 *ABI* (argument registers, 32-byte shadow space, no
 * SysV varargs %al convention), which is why they're separate `Target`
 * values rather than a single "windows" with a syntax flag bolted on --
 * every existing "windows" assumption in this backend already conflated
 * the two, so splitting them as distinct targets was the safer change.
 * Everything this stage needs to know about a target -- assembly
 * syntax, calling-convention register names, whether a call needs 32
 * bytes of shadow space reserved ahead of it -- is decided by which
 * `Target` this resolves to (see that enum), not read as further
 * key/value pairs here; more configurable knobs can be added the same
 * way `compiler.config` grew its own calling-convention table, once
 * this backend actually needs them.
 */
public class CodegenConfig {

    public enum Target {
        WINDOWS_X64,      // MASM/ml64 syntax, win64 ABI -- structurally implemented, still execution-unverified (no MASM toolchain in this sandbox)
        WINDOWS_GNU_X64,  // GAS AT&T syntax, win64 ABI -- for gcc/mingw-w64; verified in this sandbox with a real mingw-w64 cross-toolchain + Wine
        LINUX_X64         // GAS AT&T syntax, SysV ABI -- verified in this sandbox with the real as/gcc/ld toolchain
    }

    public final Target target;

    private CodegenConfig(Target target) {
        this.target = target;
    }

    public static CodegenConfig load(String path) {
        List<String> lines;
        try {
            lines = Files.readAllLines(Paths.get(path));
        } catch (IOException e) {
            throw new CodegenException("config", path, 0,
                    "required config file '" + path + "' could not be read: " + e.getMessage());
        }
        Target target = null;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i).trim();
            if (raw.isEmpty() || raw.startsWith("#")) {
                continue;
            }
            String[] parts = raw.split("\\s+", 2);
            if (parts.length != 2) {
                throw new CodegenException("config", path, i + 1, "expected 'key value', got: " + raw);
            }
            String key = parts[0];
            String value = parts[1].trim();
            if (key.equals("target")) {
                if (value.equalsIgnoreCase("windows")) {
                    target = Target.WINDOWS_X64;
                } else if (value.equalsIgnoreCase("windows_gnu") || value.equalsIgnoreCase("windows-gnu") || value.equalsIgnoreCase("mingw")) {
                    target = Target.WINDOWS_GNU_X64;
                } else if (value.equalsIgnoreCase("linux")) {
                    target = Target.LINUX_X64;
                } else {
                    throw new CodegenException("config", path, i + 1,
                            "unknown target '" + value + "' -- expected 'windows', 'windows_gnu', or 'linux'");
                }
            } else {
                throw new CodegenException("config", path, i + 1, "unknown config key '" + key + "'");
            }
        }
        if (target == null) {
            throw new CodegenException("config", path, 0, "missing required 'target' key");
        }
        return new CodegenConfig(target);
    }
}
```

### FILE: src/main/java/caspien/codegen/CodegenException.java
```java
package caspien.codegen;

/** The one exception type used for every fatal codegen error -- mirrors caspien-compiler's own CompilerException. */
public class CodegenException extends RuntimeException {
    public CodegenException(String kind, String file, int line, String message) {
        super("[" + kind + " error] " + file + ":" + line + " - " + message);
    }
}
```

### FILE: src/main/java/caspien/codegen/Main.java
```java
package caspien.codegen;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * CLI entry point for the (new, just-starting) caspien-codegen stage --
 * "begin building the fourth program... this will generate assembly
 * instructions from the lower order bytecode. The code generator will
 * use a config file as necessary. Current target is x86 windows, but
 * whether windows or linux is the target is down to the config,"
 * confirmed directly.
 *
 * Usage: codegen -i input.txt output.s
 *
 * `input.txt` is expected to be the sibling caspien-lowerordergenerator
 * project's own plain-text output. `codegen.config` (fixed path,
 * resolved relative to the current working directory) selects the
 * target -- see `CodegenConfig`'s own doc comment.
 */
public class Main {

    public static void main(String[] args) {
        try {
            run(args);
        } catch (CodegenException ce) {
            System.err.println(ce.getMessage());
            System.exit(1);
        } catch (IOException ioe) {
            System.err.println("[io error] " + ioe.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException {
        if (args.length < 3 || !args[0].equals("-i")) {
            System.err.println("Usage: codegen -i <input.txt> <output.s>");
            System.exit(1);
            return;
        }
        String inputPath = args[1];
        String outputPath = args[2];

        CodegenConfig config = CodegenConfig.load("codegen.config");

        List<String> rawLines = Files.readAllLines(Paths.get(inputPath), StandardCharsets.UTF_8);
        BytecodeParser parser = new BytecodeParser();
        List<List<BytecodeToken>> parsed = parser.parse(rawLines, inputPath);

        X86Backend backend = new X86Backend(config.target);
        String assembly = backend.generate(parsed);

        System.out.print(assembly);
        writeFile(outputPath, assembly);

        System.err.println("\n[info] wrote " + config.target + " assembly to " + outputPath + " ("
                + assembly.lines().count() + " lines)");
    }

    private static void writeFile(String path, String content) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(Paths.get(path), StandardCharsets.UTF_8))) {
            pw.print(content);
        }
    }
}
```

### FILE: src/main/java/caspien/codegen/X86Backend.java
```java
package caspien.codegen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one and only backend so far: x86-64, either target. This is a
 * *substantial* second pass, not a finished code generator -- "begin
 * building the fourth program... this will generate assembly
 * instructions from the lower order bytecode," confirmed directly, plus
 * a later, explicit "fix that [PUSH] bug and then work thru the
 * remaining 70+ [mnemonics]," confirmed directly. It now correctly
 * translates the great majority of the real low-order instruction set
 * (see the mnemonic list in `emitLine`'s own switch); anything still
 * genuinely unresolved is emitted as a visible `; TODO(codegen): <line>
 * not yet implemented` comment rather than silently dropped or guessed
 * at -- see this project's own CLAUDE.md, "Known gaps," for the
 * current, honest list of what's still missing and *why* (a few of
 * these turned out to need either a real runtime-protocol decision that
 * doesn't exist anywhere yet, such as GT_UNWIND/async, or a fix one
 * layer up in caspien-lowerordergenerator itself, such as WITHIN's
 * incomplete-range case and INSTANCEOF's class tag -- both still carry
 * unresolved symbolic operands even at "low order," not something
 * codegen alone can safely translate).
 *
 * Translation strategy: this bytecode is a stack machine (PUSH/POP/
 * ADDR all push or consume one 8-byte cell), and the host machine has
 * a real stack of its own -- so, for this first version, every
 * bytecode-level push/pop is translated 1:1 to a real `push`/`pop` of
 * the target's own hardware stack. This is deliberately not an
 * optimizing translation (a real backend would keep short-lived values
 * in registers instead) -- it is a straightforward, easy-to-verify
 * mapping, chosen so this first version can be checked for
 * correctness by actually assembling and running its output, not just
 * read for plausibility.
 *
 * Memory layout decisions this pass had to make on its own (nothing
 * else in the pipeline reads a dynarray's or a ghost-registered heap
 * pointer's actual bytes except the mnemonics this backend itself
 * implements, confirmed by grepping every sibling project's own source
 * for any DOT/PUSH_FIELDNAME-style structural access to one -- there is
 * none, so this backend is free to choose its own internal
 * representation as long as it is self-consistent):
 * - A dynarray value is a pointer to a malloc'd block laid out as
 *   [8-byte len][8-byte capacity][elements...]. `len`/`capacity` are
 *   always kept equal (no growth slack) for this first version's own
 *   simplicity -- see LEN/RESIZE/URESIZE/LOOKUP_DYN(_LHS).
 * - Every other heap value (NEW/CLONE) is a bare malloc'd block of
 *   exactly the given size, no header at all.
 *
 * Two concrete targets share this one class, distinguished by
 * `CodegenConfig.Target` (set from `codegen.config`):
 * - LINUX_X64: GNU assembler (GAS), AT&T syntax, SysV calling
 *   convention (rdi/rsi/rdx/rcx/r8/r9) -- the only target actually
 *   assembled, linked, and run in this sandbox; treat every claim of
 *   correctness in this file as scoped to this target only.
 * - WINDOWS_X64: MASM-style Intel syntax, win64 calling convention
 *   (rcx/rdx/r8/r9 + 32-byte shadow space). Structurally mirrored for
 *   every mnemonic this pass now handles, but **not** execution-
 *   verified anywhere -- no Windows/MASM/MinGW toolchain is available
 *   in this sandbox. Treat the Windows path as unverified until it is
 *   actually run somewhere that can.
 */
public class X86Backend {

    private final CodegenConfig.Target target;
    private final StringBuilder out = new StringBuilder();

    // ---- callee-saved register preservation (per function) ----
    // Any generated function that touches a callee-saved register of the target ABI (SysV: rbx, r12-r15; win64 also
    // rdi, rsi, xmm6-15) saves it in its frame and restores it on every way out (RET, the FUNC_END fall-through and
    // GT_UNWIND). The uses are only known once the whole body is emitted, so every exit emits CSR_MARK and FUNC_END
    // (finishCalleeSaved) replaces each mark with the restores, or with nothing, and grows the single ALLOC.
    private static final String CSR_MARK = "@@CSR@@";
    private int csrFuncStart = -1;
    private int csrAllocPos = -1;
    private long csrAllocBytes = 0;
    private String csrAllocLine = null;

    // Declarations collected on a first pass so they can be emitted
    // into proper .data/.rodata/.bss sections ahead of .text, instead
    // of wherever they happen to sit in the linear bytecode stream
    // (GLOBAL/STRING/ALLOC_STATIC/EXTERN lines are legal anywhere --
    // interleaved with function bodies -- but real assembly sections
    // can't be interleaved the same way).
    private final Map<String, long[]> globalsSizeInit = new LinkedHashMap<>(); // name -> [size, init] (init may be absent -> bss)
    private final Map<String, Boolean> globalsHasInit = new LinkedHashMap<>();
    private final Map<String, String> stringLiterals = new LinkedHashMap<>(); // id -> text
    // Float-constant pool (AT&T targets only): every float immediate a register-form float op needs is stored once, in a
    // read-only section, and used as a memory operand (`mulsd .LFC3(%rip), %xmm0`) or loaded with one `movsd`, instead of
    // `movabs $bits, %r15 ; movq %r15, %xmm` on every use. Key "n:bits" -> label; n = 4 or 8.
    private final Map<String, String> floatPool = new LinkedHashMap<>();
    // Every real function this compilation unit defines ("FUNC_START
    // name" lines, collected on the same declarations pass as globals/
    // strings below), so a bare "PUSH size funcName" -- a real function's
    // own *address* being pushed as a value, needed for "par"/"await"'s
    // own compiler-synthesized trampoline function pointer (see
    // caspien-compiler's own CLAUDE.md, "par"/"await" real-thread
    // desugaring) -- can be told apart from an unresolved name this pass
    // genuinely doesn't understand, rather than falling through to the
    // generic "not a declared global" TODO placeholder. A function's own
    // "value" is its address (there is no separate "stored value" to
    // load the way an ordinary global has), so this pushes the label's
    // address directly, the same `leaGlobalToReg` a string literal's own
    // PUSH already uses for the identical "decays to its own address"
    // reason -- never followed by a `loadSizedFromAddr` the way an
    // ordinary global PUSH is.
    private final java.util.Set<String> declaredFunctionNames = new java.util.HashSet<>();
    // "parent.field"-shaped GLOBAL declarations where "parent" is *also*
    // separately declared as its own whole-block global (e.g. "GLOBAL
    // ghost_table 40" alongside "GLOBAL ghost_table.lockState 8 0") are
    // not independent storage -- confirmed directly by a real, very
    // confusing bug this pass found: gt_init/gt_destruct's lock
    // acquire/release read and write "ghost_table.lockState" as a bare
    // global (its own separate .data slot) in some places but compute
    // the *same* field's address via "ADDR 40 ghost_table" +
    // PUSH_FIELDNAME/DOT_LHS (an offset into the "ghost_table" block)
    // in others -- two different addresses for what the bytecode
    // clearly intends as the same storage, so the unlock write and the
    // lock-check read landed in different places and the lock never
    // appeared to clear. The fix: a dotted GLOBAL whose prefix (up to
    // the last '.') names another declared whole-block global is
    // treated as a pure alias -- no storage of its own -- resolving to
    // that parent's address plus the cumulative size of any
    // same-parent dotted fields declared before it (matching the
    // PUSH_FIELDNAME offsets used elsewhere for the same struct).
    private final Map<String, Long> globalAliasOffset = new LinkedHashMap<>(); // dotted name -> byte offset into its parent
    private final Map<String, List<long[]>> aliasInits = new LinkedHashMap<>(); // parent -> [offset, size, bits] of each initialised element
    private final Map<String, String> globalAliasParent = new LinkedHashMap<>(); // dotted name -> parent global name
    // Set only while generate() is walking the line list, so a bare
    // global-name PUSH can peek a couple of lines ahead to tell whether
    // it's feeding an ATOMIC_SWAP (needs the global's address) or
    // anything else (needs its value) -- see that PUSH case's own doc
    // comment for why both are genuinely needed here.
    private List<List<BytecodeToken>> allLines;
    private int currentLineIndex;

    /** Set by the "ASM_START" case to the index of the "ASM_END" line that
     * closes the block it just copied verbatim, so every raw line in
     * between (already emitted as part of that one case) is skipped by
     * the main per-line dispatch instead of falling through to the
     * generic TODO comment a second time. -1 when no block is open. */
    private int asmSkipUntilIndex = -1;

    /** True when the bytecode line `offset` lines after the current one is a bare "ATOMIC_SWAP" -- used to spot the "PUSH addressOperand / PUSH newValue / ATOMIC_SWAP" shape genuinely seen in this pipeline's own ghost-table lock (see PUSH's global-name fallback). */
    private boolean lineAtOffsetIsAtomicSwap(int offset) {
        int idx = currentLineIndex + offset;
        if (allLines == null || idx < 0 || idx >= allLines.size()) {
            return false;
        }
        List<BytecodeToken> l = allLines.get(idx);
        return !l.isEmpty() && l.get(0).text.equals("ATOMIC_SWAP");
    }

    // ---- Construction-scoped push detection --------------------------------
    //
    // A struct/array/primitive construction ("X{...}"/"new X{...}") emits
    // a run of "PUSH"/"ATOMIC_PUSH"/"STACK_LOCK" lines immediately
    // followed by a "NEW <size>" line whose declared size equals the sum
    // of that run's own sizes/gaps. Within such a run, each push can
    // safely write fewer than a full 8-byte hardware word (its own real,
    // declared width) instead of the usual, always-8-byte `pushq`,
    // because "NEW" (see its own case, below) does one straight block
    // copy starting at `%rsp`, which is only byte-exact -- matching
    // `computeStructLayout`'s own tightly-packed layout -- when every
    // push and STACK_LOCK gap in that run reserved exactly its own
    // declared width. An ordinary push anywhere else in the program (a
    // comparison operand, a call argument, an "if" condition, ...) is
    // never part of such a run, so it keeps doing a full, ordinary
    // `pushq`, completely unaffected by any of this.
    //
    // A subtlety found and fixed the hard way, by actually running a
    // nested-construction repro (a struct with an "owns" member itself
    // constructed via a nested "new NestedStruct{...}"): a naive,
    // purely-linear backward/forward scan over raw "PUSH" text cannot
    // tell an outer run's own field pushes apart from an inner,
    // completed construction's field pushes sitting textually in the
    // middle of it -- both are just "PUSH size operand" lines, with
    // nothing to mark where one run ends and another begins except the
    // "NEW" lines themselves. From the *outer* run's own perspective, a
    // nested "new NestedStruct{...}" field contributes exactly one
    // 8-byte pointer word (its own "NEW"'s own final, ordinary,
    // always-8-byte `pushReg` call) -- never the nested construction's
    // own raw internal field bytes, which are already fully consumed by
    // the time that inner "NEW" returns. So this is computed as a real,
    // two-level (recursively, any-level) parse, not a flat scan: a
    // "NEW" line encountered while walking a run backward is treated as
    // one complete, opaque, already-resolved 8-byte unit (after
    // recursively confirming *its own* run resolves to *its own*
    // declared size), never as more raw pushes belonging to the run
    // being walked.
    //
    // A second subtlety: two independent, back-to-back constructions can
    // butt up against each other with zero other instructions between
    // them (e.g. a field whose value is itself "new Nested{...}", the
    // very last field of the outer literal -- the inner "NEW" and the
    // outer "NEW" end up on two consecutive lines, with no ASSIGN or
    // other instruction separating them). Processing *every* "NEW" line
    // in the whole function independently (each one validating and
    // resolving only its own direct, non-nested members) handles this
    // correctly without either run's own bookkeeping leaking into the
    // other's.
    //
    // Precomputed once per `generate()` call (`allLines` doesn't change
    // during it) into `pushStoreOffsetByLine`, keyed by line index --
    // `case "PUSH"` below does a plain map lookup rather than re-scanning
    // per instruction.
    private final Map<Integer, Long> pushStoreOffsetByLine = new java.util.HashMap<>();

    /**
     * The real fix for a real segfault
     * (`register_overflow_args_even_cg_test.caspien`/`_odd_cg_test`, both
     * a plain 8/9-`u64`-parameter function called with more parameters
     * than sysv_x64's own 6 integer argument registers): a stack-passed
     * argument word is already correctly emitted as a bare, un-popped
     * "PUSH size operand" (see `BytecodeEmitter.emitArgTransferTail`'s own
     * doc comment on the compiler side: register-passed gets a "POP
     * ARGn"/"POP FARGn" right after its own value is fully computed,
     * stack-passed gets nothing) -- but this backend's own "PUSH"/"POP"
     * bytecode mnemonics are real, one-to-one `pushq`/`popq` on the
     * *actual* x86 stack, so every such deliberately-un-popped word stays
     * sitting there, real memory, through to the `call` -- and a second,
     * independent bug (found only once the first was already fixed and
     * the program printed a wrong value instead of segfaulting) meant
     * `emitAlignedCall`'s own 16-byte alignment reservation used to land
     * *between* those pushed words and the return address, so the callee's
     * own (correct) `%rbp+16+n*8` read formula read the alignment
     * machinery's own saved-rsp slot instead of the real argument.
     *
     * Both bugs are caller-side stack-accounting/-ordering bugs, neither
     * specific to integers or to one calling convention -- they fire
     * identically for a stack-passed float, a narrow type, or a call
     * under win64, since `emitAlignedCall`/"CALL"/"INVOKE" are the one
     * shared, convention-agnostic choke point every call goes through.
     *
     * **The fix**: the 16-byte-alignment reservation now happens at
     * "CC_START" -- before any argument, stack-passed or not, is pushed
     * -- sized to already account for the real number of bytes the
     * stack-passed arguments will add before the call. That number can't
     * be known by inspecting the bytecode text alone: an individual
     * argument's own "PUSH ... ; POP ARGn"-or-not shape can have any
     * number of ordinary intermediate lines in between the two (found via
     * a real, confirmed regression: `raw arr` compiles to "PUSH 24 $-24 /
     * ADDR_OF RAW $-24 / POP ARG0 8" -- a whole 24-byte array block is
     * pushed, then immediately collapsed back down to a single 8-byte
     * pointer by `ADDR_OF`'s own real codegen, *then* popped into a
     * register; a naive "is the very next line a POP ARGn" check
     * misclassifies this as 24 stack-passed bytes and corrupts an
     * otherwise-correct, all-register call). Trying to special-case every
     * such shape (`ADDR_OF`, `DOT`, `LOOKUP_ARRAY`, `CLONE`, ... -- any
     * mnemonic whose own codegen can shrink or discard an already-pushed
     * value) from bytecode text alone is exactly the kind of whack-a-mole
     * this class of bug already warns against.
     *
     * So this doesn't try to read intent from the bytecode text at all --
     * it measures the real thing directly, by tracking every *actual*
     * `pushq`/`popq`/`add rsp,N`/`sub rsp,N` this backend emits between a
     * call's own "CC_START" and its "CALL"/"INVOKE" (`argTrackDelta`,
     * updated from `raw()` itself -- see that method's own doc comment).
     * Whatever real bytes are still unaccounted for at the "CALL" line
     * are, by construction, exactly the stack-passed argument bytes: every
     * *register*-passed argument's own production sequence, however many
     * lines long, always nets to zero the moment its "POP ARGn"/"POP
     * FARGn" runs (push some bytes computing it, pop exactly that many
     * back out into the register) -- so only genuinely-unconsumed,
     * stack-passed bytes are ever left over to measure.
     *
     * Emission itself has to be deferred to make this work: the alignment
     * reservation has to be the *first* thing emitted for the call (see
     * above, this has to happen before the stack-passed words are
     * pushed), but the byte count it needs is only known once every
     * argument has already been produced, at the "CALL" line. `case
     * "CC_START"` therefore doesn't emit anything immediately -- it opens
     * a fresh buffer (`callBufferStack`) that every `raw()` call until the
     * matching "CC_END" writes into instead of `out` directly; `case
     * "CALL"`/`"INVOKE"` -- now knowing the real byte count -- prepend the
     * correctly-sized reservation to the front of that same buffer, then
     * append the bare call and its own restore; `case "CC_END"` closes the
     * buffer out, flushing its now-complete contents into whatever buffer
     * (or `out`) was active one level up, so a call nested inside another
     * call's own argument expression (`foo(bar(), x)`) buffers and
     * resolves independently, correctly nested. See `raw()`, `case
     * "CC_START"`, `case "CALL"`/`"INVOKE"`, and `case "CC_END"` for where
     * each piece of this is actually implemented.
     *
     * The ordinary, overwhelmingly common all-register-arguments case
     * (byte count resolves to 0 at "CALL") is completely unaffected in
     * its generated assembly -- it still goes through the original,
     * already-verified `emitAlignedCall` wrapping just the `call` itself,
     * with buffering/flushing around it a no-op change in output (the
     * buffered text is identical to what `out` would have received
     * directly, just flushed one step later).
     */
    private final java.util.Deque<Long> argTrackDeltaStack = new java.util.ArrayDeque<>();

    /**
     * Suppresses `argTrackDeltaStack` tracking for the duration of a call
     * wrapped by `emitAlignedCall` (a counter, not a boolean, since one
     * such call's own argument can itself trigger another) -- every
     * instruction `emitAlignedCall` itself emits (the alignment
     * reservation, the win64 shadow-space adjustment, the final restore)
     * nets to exactly zero real stack effect by the time it returns, by
     * construction (that's its entire contract), so none of it should
     * ever be attributed to an *enclosing* "CC_START"/"CALL" bracket's own
     * running total -- tracking it would double-count real bytes that
     * were only ever transient scratch for this nested call's own
     * mechanics, not a surviving stack-passed argument of the outer call.
     */
    private int argTrackSuspendDepth = 0;

    /**
     * One level of buffered, not-yet-flushed assembly text per currently-
     * open "CC_START"/"CC_END" bracket, paired one-to-one with
     * `argTrackDeltaStack` (both pushed at "CC_START", both popped at
     * "CC_END") -- see `argTrackDeltaStack`'s own doc comment (just above)
     * for why buffering, rather than emitting directly into `out`, is
     * necessary at all. `raw()` always writes into `callBufferStack.peek()`
     * when this is non-empty, `out` otherwise. A call nested inside
     * another call's own argument expression (`foo(bar(), x)`) gets its
     * own, separate level here -- `bar()`'s own buffer/tally resolve and
     * flush (at its own "CC_END") well before `foo`'s own "CALL" ever
     * looks at *its* tally, so the two never interfere.
     */
    private final java.util.Deque<StringBuilder> callBufferStack = new java.util.ArrayDeque<>();

    /**
     * Per open "CC_START"/"CC_END" bracket (paired one-to-one with
     * `callBufferStack`): the argument registers this call's own "POP
     * ARGn"/"POP FARGn" lines have already loaded, as "i<n>" (integer
     * bank) or "f<n>" (float bank), in load order. A call nested inside
     * this call's remaining argument expressions (`foo(a, bar(b), c)`)
     * sets up *its own* arguments in the very same physical registers, so
     * without protection it silently overwrites what `foo` has already
     * loaded (and `foo`'s later arguments too, when `bar`'s result is
     * itself popped into a register that `bar`'s own argument setup
     * clobbered). `case "CC_START"` therefore pushes every register in the
     * enclosing call's list before opening the nested call's own bracket,
     * and `case "CC_END"` pops them back afterwards -- see those cases.
     */
    private final java.util.Deque<java.util.List<String>> loadedArgRegsStack = new java.util.ArrayDeque<>();

    /**
     * The registers each currently-open nested call saved at its own "CC_START"
     * (the enclosing call's already-loaded argument registers, in push order),
     * one list per open bracket, popped at the matching "CC_END".
     */
    private final java.util.Deque<java.util.List<String>> savedOuterArgRegsStack = new java.util.ArrayDeque<>();

    /**
     * True when the last "CC_END" had to restore xmm0 (the float-bank
     * argument 0 register) that a just-finished nested call may also have
     * used to *return* a float: the possible float result was stashed in
     * r10 first, and the very next `PUSH_RET_FLOAT` reads it from there
     * instead of xmm0.
     */
    private boolean floatResultInR10 = false;

    private void noteArgRegLoaded(String tag) {
        if (!loadedArgRegsStack.isEmpty() && !loadedArgRegsStack.peek().contains(tag)) {
            loadedArgRegsStack.peek().add(tag);
        }
    }

    /** Current sink for `raw()` -- the innermost open call's own buffer, or `out` directly when no "CC_START"/"CC_END" bracket is currently open. */
    private StringBuilder currentSink() {
        return callBufferStack.isEmpty() ? out : callBufferStack.peek();
    }

    /**
     * The real number of xmm (float-bank) argument registers this
     * upcoming call's own arguments used, for the one, single SysV
     * varargs call this value ever needs to reach: the "%al must hold
     * the number of vector registers used" rule (`emitAlignedCall`'s
     * non-win64 branch, `resolveStackArgBytesAndCall`'s own overflow
     * branch). -1 means "no pending value" -- either the upcoming call
     * isn't variadic at all (the front end only ever emits
     * "VARARGS_XMM_COUNT" immediately before a real variadic call's own
     * "CALL"/"INVOKE" line -- see `BytecodeEmitter.emitCallSequence`'s
     * own `varargStartIndex` doc comment), or it's win64 (no such
     * convention exists there -- consumed and ignored either way, since
     * only the two non-win64 `%al`-zeroing sites ever read this field).
     * Set by `case "VARARGS_XMM_COUNT"`, always consumed (read, then
     * reset to -1) by whichever of those two sites' preamble runs next
     * -- exactly one of them, for exactly the one call this value was
     * computed for; never left stale across calls, since every real
     * variadic call site emits a fresh "VARARGS_XMM_COUNT" of its own,
     * and a non-variadic call in between would otherwise see a stale
     * leftover count belonging to an unrelated, earlier call.
     */
    private int pendingVarargsXmmCount = -1;

    /** Consumes (reads and clears) `pendingVarargsXmmCount`, defaulting to 0 (correct for a wholly non-variadic call, or an already-conservative "no floats" guess if this is somehow reached for a variadic call whose count was never set -- should not happen, but 0 is what this backend always assumed before this field existed at all). */
    private int consumeVarargsXmmCount() {
        int count = pendingVarargsXmmCount < 0 ? 0 : pendingVarargsXmmCount;
        pendingVarargsXmmCount = -1;
        return count;
    }

    /**
     * Line indices of an "ASSIGN size size size"/"ATOMIC_ASSIGN size
     * size size" whose immediately preceding run of pushes was
     * confirmed (via the exact same `findRunStart`/`populateDirectMembers`
     * this whole section already uses for "NEW") to be a real, freshly-
     * built construction -- a plain, non-heap struct/array/primitive
     * literal ("X{...}", never "new X{...}") left stack-resident rather
     * than copied into a fresh heap buffer. Populated by
     * `precomputeConstructionOffsets` alongside `pushStoreOffsetByLine`
     * itself, since both come from literally the same scan.
     *
     * This is the fix for a real, previously-undiscovered bug, found
     * the same day this mechanism's own doc comments were re-read
     * carefully for the first time in a while: `precomputeConstructionOffsets`
     * only ever looked for a run *ending in a "NEW <size>" line* --
     * meaning the "each push in a construction run reserves only its
     * own declared width, not a blind 8-byte word" treatment those
     * comments describe as construction-agnostic ("NEW's whole job is
     * now genuinely trivial and completely struct-agnostic") was, in
     * practice, silently coupled to heap allocation specifically. A
     * plain stack-resident literal with any field narrower than 8 bytes
     * (`let p = mut Mixed{flag=true, a='X', b='Y', id=123456789}`,
     * with no "new") never went through this scan at all, so its
     * narrow fields still fell back to the old, blind, always-8-byte
     * `pushReg`/`assignBlock` word-popping path below -- correct only
     * by accident, whenever every field already happened to be exactly
     * 8 bytes wide. Confirmed directly as a real, live bug via two
     * fixtures already in the corpus (`struct_mixed_width_cg_test`,
     * a genuine segfault from writing to the wrong stack slot;
     * `struct_array_member_narrow_elem_cg_test`, an outright invalid
     * `movzqq` instruction that fails to assemble) -- both had been
     * sitting there the whole time.
     *
     * The real fix is exactly what the class-level "Construction-scoped
     * push detection" note already promised and never actually
     * delivered: the *same* run-detection this file already has is now
     * also triggered by an "ASSIGN"/"ATOMIC_ASSIGN size size size" line
     * (three equal sizes, >8 -- the shape a struct/array/primitive
     * literal's own top-level store always has), not only "NEW". No
     * new detection logic was written -- `findRunStart`/
     * `populateDirectMembers` are reused completely unchanged; this set
     * just remembers which ASSIGN lines qualified, so `case "ASSIGN"`
     * below can pick the matching "trivial, byte-exact copy" treatment
     * `case "NEW"` already uses, instead of the old per-word
     * `assignBlock`, exactly when it's actually warranted -- see
     * `constructionScopedAssign`'s own use, below, for why an ordinary
     * whole-value block copy (`let b = a`, an existing struct/array
     * variable's own value re-pushed via a single "PUSH size $offset")
     * still needs the *old* path instead.
     */
    private final Set<Integer> constructionScopedAssign = new java.util.HashSet<>();

    /**
     * "NEW" lines whose run of field pushes `precomputeConstructionOffsets`
     * resolved into one clean, packed image (every direct field a plain
     * PUSH/STACK_LOCK, sizes summing exactly to the declared size). Any
     * OTHER "NEW" -- a field value that is an expression, a call, a moved
     * `owns` variable, a nested `new`/`dyn(...)` (with its out-of-memory
     * check code) -- is NOT packed: every field was pushed as a blind
     * 8-byte word (a wider field as a word-reversed block), so the stack
     * holds those words in REVERSE field order and "NEW"'s straight block
     * copy would put the fields backwards (the type id where the first
     * field belongs, and so on). Those go through `emitNewRepack` instead,
     * using the struct layout descriptor LowerOrderGenerator appends to
     * the "NEW" line.
     */
    private final Set<Integer> packedNewLines = new java.util.HashSet<>();

    /**
     * Returns the start index of the run of field productions ending
     * exactly at `endExclusive` (i.e. lines endExclusive-1,
     * endExclusive-2, ... going backward) whose sizes sum to EXACTLY
     * `targetSize` -- or -1 if no such run exists. A "production" is an
     * ordinary "PUSH"/"ATOMIC_PUSH"/"STACK_LOCK" line (contributing its
     * own declared size/gap) or a complete nested "NEW <size>"
     * (contributing exactly 8 bytes -- the pointer it leaves behind --
     * once its *own* run is confirmed, recursively, to sum to that same
     * declared size).
     *
     * `targetSize` is load-bearing, not a mere sanity check: a nested
     * construction's own trailing field pushes are textually
     * indistinguishable from the outer run's own earlier field pushes
     * (both are just "PUSH size operand" lines, with no marker of its
     * own separating one run from the next) -- the *only* thing that
     * tells this scan where the nested run actually started is that its
     * own declared size (the nested "NEW"'s own operand) is reached
     * exactly. A scan with no target, greedily consuming every
     * syntactically-valid production it finds, cannot stop there: it
     * would keep walking straight past the nested run's own true start
     * and into the outer run's remaining fields, silently miscounting
     * both. So this stops the instant the running total reaches
     * `targetSize` (success) or would exceed it (failure) -- never
     * "as much as looks valid."
     */
    private int findRunStart(int endExclusive, long targetSize) {
        long total = 0;
        int idx = endExclusive;
        while (total < targetSize && idx > 0) {
            List<BytecodeToken> prev = allLines.get(idx - 1);
            if (prev.isEmpty()) {
                return -1;
            }
            String m = prev.get(0).text;
            if ((m.equals("PUSH") || m.equals("ATOMIC_PUSH")) && prev.size() >= 2) {
                total += Long.parseLong(prev.get(1).text);
                idx--;
            } else if (m.equals("STACK_LOCK") && prev.size() >= 2) {
                total += Long.parseLong(prev.get(1).text);
                idx--;
            } else if (m.equals("NEW") && prev.size() >= 2) {
                long declaredNestedSize = Long.parseLong(prev.get(1).text);
                int nestedStart = findRunStart(idx - 1, declaredNestedSize);
                if (nestedStart < 0) {
                    return -1;
                }
                idx = nestedStart;
                total += 8; // the nested NEW's own resulting pointer -- one ordinary 8-byte word to this (outer) run
            } else {
                return -1;
            }
        }
        return total == targetSize ? idx : -1;
    }

    /**
     * Populates `pushStoreOffsetByLine` for every *direct* (non-nested)
     * "PUSH"/"ATOMIC_PUSH" member of the run [start, endExclusive) --
     * each one's own value is `trueOffset - remainingBytes`, the signed
     * byte offset from `%rsp` *after that specific push's own `subq`*
     * to store its value at (see the class-level note above `case
     * "PUSH"`'s own doc comment for why plain "(%rsp)" -- offset 0 --
     * is wrong the moment a construction has more than one field: each
     * field's own `subq` grows the stack *downward*, so by itself that
     * lays fields out in the *reverse* of their real struct order;
     * `trueOffset - remainingBytes` corrects for exactly that). A
     * nested "NEW" found along the way is skipped as a single opaque
     * unit (it never itself needs a store-offset entry -- its own
     * `pushReg` inside `case "NEW"` is a plain, ordinary, always-8-byte
     * push, ineligible for this treatment entirely) -- its own direct
     * members are populated separately, when *that* "NEW" is processed
     * on its own by `precomputeConstructionOffsets`.
     */
    private void populateDirectMembers(int start, int endExclusive, long runTotal) {
        RunEntries entries = collectRunEntries(start, endExclusive);
        long before = 0;
        long after = runTotal;
        for (int i = 0; i < entries.sizes.size(); i++) {
            long sz = entries.sizes.get(i);
            after -= sz;
            int lineIdx = entries.storeLines.get(i);
            if (lineIdx >= 0) {
                pushStoreOffsetByLine.put(lineIdx, before - after);
            }
            before += sz;
        }
    }

    /**
     * After a value's own bits are already sitting in `regName` (always
     * "rax" today, but kept general for clarity), either stores it at its
     * own precomputed, tightly-packed construction offset (see
     * `populateDirectMembers`'s own doc comment) when this exact PUSH line
     * is a direct member of a struct/array/range/primitive construction,
     * or, when it isn't, does a plain, ordinary full-word `pushq`
     * (`pushReg`), the identical fallback every PUSH already had before
     * construction-packing existed.
     *
     * Shared between every PUSH operand shape (`case "PUSH"`'s "$offset"
     * branch and its own generic literal/ARG/global fallback) so newly-
     * added operand shapes automatically participate in construction-
     * packing rather than needing this decision duplicated -- **the exact
     * fix this doc comment itself documents**: the "$offset" branch used
     * to call `pushReg` unconditionally, silently NEVER checking
     * `pushStoreOffsetByLine` at all, so any construction with a variable-
     * sourced field (e.g. "0..index", a range literal whose end is a
     * parameter's own value, not a literal) got its variable-sourced entry
     * pushed as an ordinary, unrelated stack push -- corrupting the whole
     * tightly-packed image the terminating ASSIGN/`assignConstructionBlock`/
     * `NEW` expects, in a way that only manifests once that entry is
     * actually consumed (silently, no crash at the construction site
     * itself). Found via a real segfault inside the ghost table's own
     * `gtSlotAt`/`gtWriteSlot` machinery (`stdlib/ghost_table.caspien`'s
     * own "for i in 0..index" -- a range literal with one immediate ("0")
     * and one frame-loaded ("index") entry).
     */
    private void storeConstructionOrPlainPush(String regName, int size) {
        Long constructionOffset = pushStoreOffsetByLine.get(currentLineIndex);
        if (constructionOffset == null) {
            pushReg(regName);
            return;
        }
        raw(isWindows() ? ("    sub rsp, " + size) : ("    subq $" + size + ", %rsp"));
        if (constructionOffset == 0) {
            storeSizedToAddr(regName, "rsp", size);
        } else if (isOddSize(size)) {
            storeOddSize(regName, "rsp", constructionOffset, size);
        } else if (isWindows()) {
            raw("    mov [rsp" + signed(constructionOffset) + "], " + sizedRegWin(regName, size));
        } else {
            raw("    mov" + movSuffix(size) + " %" + sizedReg(regName, size) + ", " + constructionOffset
                    + "(%rsp)");
        }
    }

    /** Plain data holder for {@link #collectRunEntries}: parallel lists of each direct entry's own size and its store-instruction line index (-1 when it has none of its own -- a `STACK_LOCK` padding gap, or a nested `NEW`'s own opaque pointer). */
    private static final class RunEntries {
        final List<Long> sizes = new ArrayList<>();
        final List<Integer> storeLines = new ArrayList<>();
    }

    /**
     * The shared forward-scan used both to *count* a run's own direct
     * members (`precomputeConstructionOffsets`'s own way of telling a
     * genuine multi-field construction apart from an ordinary
     * single-value `ASSIGN` -- ordinary scalar assigns and whole-block
     * copies alike are always exactly *one* `PUSH`, whatever their
     * declared size, while any real struct/array literal is always
     * *more than one* -- so the entry *count*, not the byte size, is
     * what actually distinguishes them) and to lay out their real
     * store offsets (`populateDirectMembers`). Walking forward, a
     * nested construction's own field pushes are indistinguishable
     * from this (outer) run's own direct field pushes right up until
     * its terminating "NEW" line is actually reached -- so they get
     * recorded provisionally, exactly like any other direct member,
     * and are only recognized and collapsed retroactively, once that
     * "NEW" line is seen: pop entries off the back of what's been
     * recorded so far until their sizes sum to exactly this "NEW"'s
     * own declared size (an already-collapsed deeper-nested "NEW" pops
     * as a single 8-byte entry, so this handles arbitrary nesting
     * depth correctly), then replace them all with one opaque 8-byte
     * entry (the nested "NEW"'s own resulting pointer, pushed by its
     * own plain, ordinary, always-8-byte `pushReg` -- never eligible
     * for a store offset of its own).
     */
    private RunEntries collectRunEntries(int start, int endExclusive) {
        RunEntries entries = new RunEntries();
        int idx = start;
        while (idx < endExclusive) {
            List<BytecodeToken> l = allLines.get(idx);
            String m = l.get(0).text;
            if ((m.equals("PUSH") || m.equals("ATOMIC_PUSH")) && l.size() >= 2) {
                entries.sizes.add(Long.parseLong(l.get(1).text));
                entries.storeLines.add(idx);
                idx++;
            } else if (m.equals("STACK_LOCK") && l.size() >= 2) {
                entries.sizes.add(Long.parseLong(l.get(1).text));
                entries.storeLines.add(-1);
                idx++;
            } else if (m.equals("NEW") && l.size() >= 2) {
                long declaredNestedSize = Long.parseLong(l.get(1).text);
                long sum = 0;
                int popCount = 0;
                for (int j = entries.sizes.size() - 1; j >= 0; j--) {
                    sum += entries.sizes.get(j);
                    popCount++;
                    if (sum >= declaredNestedSize) {
                        break;
                    }
                }
                if (sum == declaredNestedSize) {
                    for (int k = 0; k < popCount; k++) {
                        entries.sizes.remove(entries.sizes.size() - 1);
                        entries.storeLines.remove(entries.storeLines.size() - 1);
                    }
                }
                entries.sizes.add(8L);
                entries.storeLines.add(-1);
                idx++;
            } else {
                break; // shouldn't happen -- the caller already validated this exact range
            }
        }
        return entries;
    }

    /**
     * "NEW size m8 m8 p4 m1 ..." where the run of field pushes could NOT be
     * packed (see `packedNewLines`). Every field production was pushed the
     * ordinary way: a scalar as one full 8-byte word, a wider field as a
     * word-reversed block (`pushBlockFromFrame`'s convention, highest-offset
     * word nearest %rsp), and each padding gap as an exact `subq` -- so the
     * stack region, read from %rsp upward, is the word-for-word REVERSE of
     * the "padded image" (fields in declared order, each rounded up to whole
     * words, gaps exact). This rebuilds the real, tightly-packed struct in a
     * fresh heap buffer straight from that description: for each layout
     * entry, copy its bytes from where that chunk sits on the stack to the
     * entry's true offset in the buffer. Unlike the packed path this needs
     * to know the layout, which LowerOrderGenerator appends to the line.
     */
    private void emitNewRepack(long size, List<BytecodeToken> line) {
        // Chunk sizes on the stack, in push order (first field first).
        List<Long> chunk = new ArrayList<>();
        List<Long> fieldSize = new ArrayList<>();   // 0 for a padding gap
        List<Long> elemSize = new ArrayList<>();    // > 0 for "a<elemBytes>x<n>": a fixed array written element by element, n whole words
        List<Long> elemCount = new ArrayList<>();
        for (int i = 2; i < line.size(); i++) {
            String tok = line.get(i).text;
            if (tok.charAt(0) == 'a') {
                int x = tok.indexOf('x');
                long e = Long.parseLong(tok.substring(1, x));
                long cnt = Long.parseLong(tok.substring(x + 1));
                chunk.add(cnt * 8);
                fieldSize.add(e * cnt);
                elemSize.add(e);
                elemCount.add(cnt);
                continue;
            }
            long n = Long.parseLong(tok.substring(1));
            if (tok.charAt(0) == 'm') {
                chunk.add(((n + 7) / 8) * 8);
                fieldSize.add(n);
            } else {
                chunk.add(n);
                fieldSize.add(0L);
            }
            elemSize.add(0L);
            elemCount.add(0L);
        }
        long total = 0;
        for (long c : chunk) {
            total += c;
        }
        raw(isWindows() ? "    mov r12, rsp" : "    movq %rsp, %r12"); // r12: the reversed field words on the stack
        emitMallocCall(size);
        raw(isWindows() ? "    mov r14, rax" : "    movq %rax, %r14"); // r14: the fresh buffer
        long dst = 0;      // running true offset in the buffer
        long consumed = 0; // bytes of chunks pushed so far (from the first field)
        for (int i = 0; i < chunk.size(); i++) {
            consumed += chunk.get(i);
            long chunkBase = total - consumed; // this chunk's lowest address, relative to r12
            long s = fieldSize.get(i);
            if (s == 0) {
                dst += chunk.get(i); // padding gap: bytes in the buffer stay whatever malloc left
                continue;
            }
            if (elemSize.get(i) > 0) {
                long cnt = elemCount.get(i);
                for (long k = 0; k < cnt; k++) {
                    copyBytesR12ToR14(chunkBase + (cnt - 1 - k) * 8, dst + k * elemSize.get(i), elemSize.get(i));
                }
                dst += s;
                continue;
            }
            long words = (s + 7) / 8;
            for (long k = 0; k < words; k++) {
                long bytes = Math.min(8, s - 8 * k);
                long srcOff = words > 1 ? chunkBase + (words - 1 - k) * 8 : chunkBase;
                copyBytesR12ToR14(srcOff, dst + 8 * k, bytes);
            }
            dst += s;
        }
        raw(isWindows() ? ("    add rsp, " + total) : ("    addq $" + total + ", %rsp"));
        pushReg("r14");
    }

    /**
     * "ASSIGN size size size m8 m4 p4 a4x3 ..." for a stack struct literal whose field pushes could not be packed (see
     * `emitNewRepack`, which does the same for a heap `new`): every scalar field was pushed as one whole 8-byte word, a wider
     * field as a word-reversed block, and each padding gap as an exact `subq`, so the stack region from %rsp upward is the
     * reverse of the padded image, with the destination address in the word just above it. Copies each field's bytes to its
     * true offset from that address, then drops the field words and the address. Uses only the scratch registers %r10 (source
     * base), %r11 (destination) and %rax.
     */
    private void emitAssignRepack(long size, List<BytecodeToken> line) {
        // One entry per layout token: chunk = bytes it occupies on the stack, fieldSize = real bytes (0 for a padding gap),
        // elemSize/count > 0 for "a<elemBytes>x<n>" (a fixed array written element by element: n whole words, one per element).
        List<Long> chunk = new ArrayList<>();
        List<Long> fieldSize = new ArrayList<>();
        List<Long> elemSize = new ArrayList<>();
        List<Long> elemCount = new ArrayList<>();
        for (int i = 4; i < line.size(); i++) {
            String tok = line.get(i).text;
            char kind = tok.charAt(0);
            if (kind == 'a') {
                int x = tok.indexOf('x');
                long e = Long.parseLong(tok.substring(1, x));
                long cnt = Long.parseLong(tok.substring(x + 1));
                chunk.add(cnt * 8);
                fieldSize.add(e * cnt);
                elemSize.add(e);
                elemCount.add(cnt);
                continue;
            }
            long n = Long.parseLong(tok.substring(1));
            if (kind == 'm') {
                chunk.add(((n + 7) / 8) * 8);
                fieldSize.add(n);
            } else {
                chunk.add(n);
                fieldSize.add(0L);
            }
            elemSize.add(0L);
            elemCount.add(0L);
        }
        long total = 0;
        for (long c : chunk) {
            total += c;
        }
        raw(isWindows() ? "    mov r10, rsp" : "    movq %rsp, %r10");
        raw(isWindows() ? ("    mov r11, [rsp" + signed(total) + "]") : ("    movq " + total + "(%rsp), %r11"));
        long dst = 0;
        long consumed = 0;
        for (int i = 0; i < chunk.size(); i++) {
            consumed += chunk.get(i);
            long chunkBase = total - consumed;
            long s = fieldSize.get(i);
            if (s == 0) {
                dst += chunk.get(i);
                continue;
            }
            if (elemSize.get(i) > 0) {
                long cnt = elemCount.get(i);
                for (long k = 0; k < cnt; k++) {
                    // element k was pushed k-th, so the last element sits nearest %rsp
                    copyBytes("r10", chunkBase + (cnt - 1 - k) * 8, "r11", dst + k * elemSize.get(i), elemSize.get(i));
                }
                dst += s;
                continue;
            }
            long words = (s + 7) / 8;
            for (long k = 0; k < words; k++) {
                long bytes = Math.min(8, s - 8 * k);
                long srcOff = words > 1 ? chunkBase + (words - 1 - k) * 8 : chunkBase;
                copyBytes("r10", srcOff, "r11", dst + 8 * k, bytes);
            }
            dst += s;
        }
        raw(isWindows() ? ("    add rsp, " + (total + 8)) : ("    addq $" + (total + 8) + ", %rsp"));
    }

    /** Copies `n` (1..8) bytes from srcOff(srcReg) to dstOff(dstReg) through %rax, in the fewest naturally-sized moves. */
    private void copyBytes(String srcReg, long srcOff, String dstReg, long dstOff, long n) {
        long done = 0;
        while (done < n) {
            long left = n - done;
            int w = left >= 8 ? 8 : left >= 4 ? 4 : left >= 2 ? 2 : 1;
            if (isWindows()) {
                raw("    mov " + sizedRegWin("rax", w) + ", [" + srcReg + signed(srcOff + done) + "]");
                raw("    mov [" + dstReg + signed(dstOff + done) + "], " + sizedRegWin("rax", w));
            } else {
                raw("    mov" + movSuffix(w) + " " + (srcOff + done) + "(%" + srcReg + "), %" + sizedReg("rax", w));
                raw("    mov" + movSuffix(w) + " %" + sizedReg("rax", w) + ", " + (dstOff + done) + "(%" + dstReg + ")");
            }
            done += w;
        }
    }

    /** Copies `n` (1..8) bytes from srcOff(%r12) to dstOff(%r14) through %rax, in the fewest naturally-sized moves. */
    private void copyBytesR12ToR14(long srcOff, long dstOff, long n) {
        long done = 0;
        while (done < n) {
            long left = n - done;
            int w = left >= 8 ? 8 : left >= 4 ? 4 : left >= 2 ? 2 : 1;
            if (isWindows()) {
                raw("    mov " + sizedRegWin("rax", w) + ", [r12" + signed(srcOff + done) + "]");
                raw("    mov [r14" + signed(dstOff + done) + "], " + sizedRegWin("rax", w));
            } else {
                raw("    mov" + movSuffix(w) + " " + (srcOff + done) + "(%r12), %" + sizedReg("rax", w));
                raw("    mov" + movSuffix(w) + " %" + sizedReg("rax", w) + ", " + (dstOff + done) + "(%r14)");
            }
            done += w;
        }
    }

    /**
     * Runs once per `generate()` call: finds every construction
     * *terminator* in the program -- a "NEW <size>" line (a heap
     * construction), or an "ASSIGN"/"ATOMIC_ASSIGN size size size" line
     * whose three size operands agree and exceed 8 (a plain,
     * stack-resident struct/array/primitive literal's own top-level
     * store -- never a scalar ASSIGN, which this bytecode's own
     * convention always carries as three *equal*, but not necessarily
     * `>8`, sizes too, so the `>8` check is what actually tells the two
     * apart) -- and, for each whose own immediately-preceding run of
     * pushes resolves to that exact declared size (`findRunStart`),
     * records every direct member's own store offset into
     * `pushStoreOffsetByLine`, exactly as it already did for "NEW"
     * alone.
     *
     * This single generalization is the whole fix for a real bug: the
     * "each push in a construction run reserves only its own declared
     * width" treatment only ever fired ahead of a "NEW" line before --
     * a plain, non-heap struct/array literal fell back to the old,
     * blind, always-8-byte-word path instead (see
     * `constructionScopedAssign`'s own doc comment for the real
     * fixtures this broke). Nothing about *how* a run is detected
     * changed at all -- `findRunStart`/`collectRunEntries` are the
     * identical, unmodified methods "NEW" alone used before; only
     * *which lines are allowed to trigger that same detection* grew a
     * second case.
     *
     * An "ASSIGN"/"ATOMIC_ASSIGN size size size" line's three equal
     * size operands alone do NOT tell a genuine construction (a
     * struct/array literal's own top-level store) apart from an
     * ordinary scalar assign or a whole-block copy (`let b = a`) --
     * this bytecode's own convention emits all three the exact same
     * way, and a copy's declared size can just as easily be large (a
     * big struct) as a literal's can be small (a two-element `char`
     * array, total 2 bytes). A size threshold was tried first and was
     * wrong on both ends: it misses small literals like a `char[4]`
     * (declared size 4, well under any plausible threshold) and it
     * would just as readily misfire on a large whole-struct copy given
     * a low enough one. What actually distinguishes them is the
     * *number of direct pushes* feeding the ASSIGN, not their combined
     * size: a scalar assign and a whole-block copy alike are always
     * fed by exactly *one* PUSH (a single value, or a single `$offset`
     * reference to the whole source block); a real literal construction
     * -- however small -- is always fed by *more than one*, one per
     * member. So the run is found the same way regardless (`findRunStart`
     * doesn't care what the caller intends to do with it), and only
     * *afterward*, once `collectRunEntries` has actually counted the
     * direct entries, is a single-entry run recognized as "not a
     * construction" and left on the old, already-correct blind path.
     */
    private void precomputeConstructionOffsets() {
        pushStoreOffsetByLine.clear();
        constructionScopedAssign.clear();
        packedNewLines.clear();
        for (int i = 0; i < allLines.size(); i++) {
            List<BytecodeToken> l = allLines.get(i);
            if (l.isEmpty()) {
                continue;
            }
            String m = l.get(0).text;
            boolean isNew = m.equals("NEW") && l.size() >= 2;
            boolean isBlockAssign = (m.equals("ASSIGN") || m.equals("ATOMIC_ASSIGN")) && l.size() >= 4
                    && l.get(1).text.equals(l.get(2).text) && l.get(2).text.equals(l.get(3).text);
            if (!isNew && !isBlockAssign) {
                continue;
            }
            long declaredSize = Long.parseLong(l.get(1).text);
            int runStart = findRunStart(i, declaredSize);
            if (runStart < 0) {
                continue;
            }
            if (isBlockAssign) {
                RunEntries entries = collectRunEntries(runStart, i);
                if (entries.sizes.size() <= 1) {
                    continue; // a single PUSH feeding this ASSIGN: an ordinary scalar assign or a whole-block copy, not a construction
                }
            }
            populateDirectMembers(runStart, i, declaredSize);
            if (isBlockAssign) {
                constructionScopedAssign.add(i);
            } else {
                packedNewLines.add(i);
            }
        }
    }

    public X86Backend(CodegenConfig.Target target) {
        this.target = target;
    }

    /** True only for the MASM/ml64-syntax target -- gates *assembly syntax* choices (Intel brackets/mnemonics/directives vs GAS AT&T). WINDOWS_GNU_X64 deliberately returns false here: it uses the exact same AT&T syntax as Linux, since that's what GNU `as` (mingw-w64's own assembler) actually accepts -- only its ABI differs from Linux, which isWinAbi() below covers separately. */
    private boolean isWindows() {
        return target == CodegenConfig.Target.WINDOWS_X64;
    }

    /** True for *either* Windows target -- gates win64 ABI choices (argument registers, 32-byte shadow space, no SysV varargs %al convention) independently of assembly syntax. WINDOWS_X64 and WINDOWS_GNU_X64 share this ABI; only isWindows() (syntax) tells them apart. */
    private boolean isWinAbi() {
        return target == CodegenConfig.Target.WINDOWS_X64 || target == CodegenConfig.Target.WINDOWS_GNU_X64;
    }

    public String generate(List<List<BytecodeToken>> lines) {
        out.setLength(0);
        globalsSizeInit.clear();
        globalsHasInit.clear();
        stringLiterals.clear();
        floatPool.clear();
        globalAliasOffset.clear();
        aliasInits.clear();
        globalAliasParent.clear();
        collectDeclarations(lines);
        emitHeader();
        emitDataSections();
        raw(isWindows() ? ".code" : ".text");
        allLines = lines;
        precomputeConstructionOffsets();
        callBufferStack.clear();
        argTrackDeltaStack.clear();
        argTrackSuspendDepth = 0;
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            currentLineIndex = lineIndex;
            List<BytecodeToken> line = lines.get(lineIndex);
            emitLine(line);
        }
        emitFloatPool();
        emitFooter();
        return out.toString();
    }

    // ---- Pass 1: declarations -------------------------------------------------

    private void collectDeclarations(List<List<BytecodeToken>> lines) {
        // Pass 1: every plain (non-dotted) GLOBAL/ALLOC_STATIC name, so
        // pass 2 below can tell a dotted "parent.field" declaration
        // apart from one whose "parent" isn't actually a separately
        // declared whole-block global (in which case it keeps its own
        // independent storage, the pre-existing behavior).
        java.util.Set<String> plainGlobalNames = new java.util.HashSet<>();
        for (List<BytecodeToken> line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            String mnemonic = line.get(0).text;
            if ((mnemonic.equals("GLOBAL") || mnemonic.equals("ALLOC_STATIC")) && line.size() >= 3) {
                String name = line.get(1).text;
                if (!name.contains(".")) {
                    plainGlobalNames.add(name);
                }
            }
            if (mnemonic.equals("FUNC_START") && line.size() >= 2) {
                declaredFunctionNames.add(line.get(1).text);
            }
        }
        // Pass 2: real declarations, aliasing dotted fields of a known
        // parent block instead of giving them their own storage.
        Map<String, Long> parentRunningOffset = new LinkedHashMap<>();
        for (List<BytecodeToken> line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            String mnemonic = line.get(0).text;
            if (mnemonic.equals("GLOBAL") || mnemonic.equals("ALLOC_STATIC")) {
                // "GLOBAL name size" (bss, zero-init) or
                // "GLOBAL name size initVal" (data, non-zero init) --
                // "ALLOC_STATIC name size initVal" is the identical
                // shape for a function-local 'static', just declared at
                // its first-use point inside a function body instead of
                // at top level.
                String name = line.get(1).text;
                long size = Long.parseLong(line.get(2).text);
                int dot = name.lastIndexOf('.');
                if (dot >= 0 && plainGlobalNames.contains(name.substring(0, dot)) && !globalAliasOffset.containsKey(name)) {
                    String parent = name.substring(0, dot);
                    long offset = parentRunningOffset.getOrDefault(parent, 0L);
                    globalAliasOffset.put(name, offset);
                    globalAliasParent.put(name, parent);
                    parentRunningOffset.put(parent, offset + size);
                    if (line.size() >= 4) {
                        // The element's own initial value (a static array
                        // literal's elements arrive as "GLOBAL a.0 4 1.5" ...)
                        // -- kept so the parent's storage can be emitted with
                        // them; before, they were dropped and every static
                        // array started zeroed.
                        aliasInits.computeIfAbsent(parent, k -> new ArrayList<>())
                                .add(new long[]{offset, size, initBits(line.get(3).text, size)});
                    }
                    continue;
                }
                if (line.size() >= 4) {
                    long init = initBits(line.get(3).text, size);
                    globalsSizeInit.put(name, new long[]{size, init});
                    globalsHasInit.put(name, true);
                } else if (!globalsSizeInit.containsKey(name)) {
                    globalsSizeInit.put(name, new long[]{size, 0});
                    globalsHasInit.put(name, false);
                }
            } else if (mnemonic.equals("STRING") && line.size() >= 3) {
                String id = line.get(1).text;
                String text = line.get(2).text;
                // Strip the surrounding quotes the parser keeps on a
                // STRING literal token -- real .asciz wants the raw text.
                if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
                    text = text.substring(1, text.length() - 1);
                }
                stringLiterals.put(id, text);
            }
        }
    }

    /** A GLOBAL initialiser's bit pattern for a slot of `size` bytes: null/integer as before, a float literal as its IEEE-754 bits (4 bytes: single, otherwise double) -- previously any float initialiser was silently zero. */
    private long initBits(String text, long size) {
        if (!text.equals("null") && !isInteger(text) && isFloatLiteral(text)) {
            return size <= 4
                    ? Integer.toUnsignedLong(Float.floatToRawIntBits(Float.parseFloat(text)))
                    : Double.doubleToRawLongBits(Double.parseDouble(text));
        }
        return parseInitValue(text);
    }

    private long parseInitValue(String text) {
        if (text.equals("null")) {
            return 0;
        }
        if (isInteger(text)) {
            return Long.parseLong(text);
        }
        // A float initializer, or anything else this first version
        // doesn't resolve to a plain integer -- zero-initialize rather
        // than guess at a bit pattern.
        return 0;
    }

    private void emitDataSections() {
        if (!globalsSizeInit.isEmpty()) {
            raw(isWindows() ? ".data" : ".data");
            for (Map.Entry<String, long[]> e : globalsSizeInit.entrySet()) {
                String label = mangleGlobalName(e.getKey());
                long size = e.getValue()[0];
                long init = e.getValue()[1];
                boolean hasInit = globalsHasInit.getOrDefault(e.getKey(), false);
                raw(isWindows() ? "PUBLIC " + label : ".globl " + label);
                raw(label + ":");
                List<long[]> parts = aliasInits.get(e.getKey());
                if (parts != null) {
                    // A whole-block global whose elements carry initial
                    // values: lay the bytes out little-endian.
                    byte[] image = new byte[(int) size];
                    for (long[] part : parts) {
                        for (int b = 0; b < part[1] && part[0] + b < size; b++) {
                            image[(int) (part[0] + b)] = (byte) (part[2] >>> (8 * b));
                        }
                    }
                    StringBuilder bytes = new StringBuilder();
                    for (int b = 0; b < image.length; b++) {
                        bytes.append(b == 0 ? "" : ", ").append(image[b] & 0xFF);
                    }
                    raw((isWindows() ? "    db " : "    .byte ") + bytes);
                } else {
                    emitStorageDirective(size, hasInit ? init : 0);
                }
            }
        }
        if (!stringLiterals.isEmpty()) {
            raw(isWindows() ? ".data" : ".section .rodata");
            for (Map.Entry<String, String> e : stringLiterals.entrySet()) {
                raw(e.getKey() + ":");
                raw(isWindows() ? ("    db \"" + e.getValue() + "\", 0")
                        : ("    .asciz \"" + e.getValue() + "\""));
            }
        }
    }

    /** Emits `size` bytes of storage, in units of quad/long/word/byte, initialized to `init` (repeated/truncated as needed -- good enough for the scalar globals this pass actually sees; anything wider than 8 bytes is zero-filled regardless of `init`, since no observed GLOBAL line initializes a multi-word aggregate). */
    private void emitStorageDirective(long size, long init) {
        if (size == 8) {
            raw(isWindows() ? ("    dq " + init) : ("    .quad " + init));
        } else if (size == 4) {
            raw(isWindows() ? ("    dd " + init) : ("    .long " + init));
        } else if (size == 2) {
            raw(isWindows() ? ("    dw " + init) : ("    .word " + init));
        } else if (size == 1) {
            raw(isWindows() ? ("    db " + init) : ("    .byte " + init));
        } else {
            raw(isWindows() ? ("    db " + size + " dup(0)") : ("    .zero " + size));
        }
    }

    /** "ghost_table.lockState" -> "ghost_table_lockState" -- '.' isn't valid in an assembly label; every GLOBAL declaration and every reference to it (PUSH/ADDR/ATOMIC_* of a bare name) goes through this same mangling, so the two always agree. */
    private String mangleGlobalName(String name) {
        return name.replace('.', '_');
    }

    private void emitHeader() {
        if (isWindows()) {
            out.append("; Generated by caspien-codegen (target: windows x86-64, MASM/ml64)\n");
            out.append("; NOTE: MASM-style syntax -- not execution-verified in this sandbox\n");
            out.append("; (no MASM/ml64 toolchain available here).\n");
        } else if (target == CodegenConfig.Target.WINDOWS_GNU_X64) {
            out.append("# Generated by caspien-codegen (target: windows_gnu x86-64, mingw-w64/GNU as)\n");
            out.append("# GAS AT&T syntax, win64 ABI -- assemble/link with x86_64-w64-mingw32-gcc.\n");
        } else {
            out.append("# Generated by caspien-codegen (target: linux x86-64)\n");
            out.append("# GAS AT&T syntax -- assemblable/runnable directly with as/ld/gcc.\n");
        }
    }

    private void emitFooter() {
        if (isWindows()) {
            out.append("end\n");
        }
    }

    private void comment(String text) {
        out.append(isWindows() ? "; " : "# ").append(text).append('\n');
    }

    /**
     * The single low-level text-emission sink every other emission helper
     * in this file ultimately funnels through -- which makes it the one
     * place that can both (a) redirect output into whichever call's own
     * buffer is currently open (`currentSink()`, `callBufferStack`) and
     * (b) track the real running stack-pointer delta a call's own
     * argument-marshalling sequence leaves behind (`argTrackDelta`) by
     * recognizing the handful of instruction shapes that actually move
     * `%rsp`/`rsp`, directly off the real text being emitted -- see
     * `argTrackDelta`'s own doc comment for why this has to measure the
     * real emitted instructions rather than infer intent from the
     * bytecode. Only ever silently *not* attributed when
     * `argTrackSuspendDepth > 0` (inside an `emitAlignedCall`-wrapped
     * nested call, whose own net effect is always zero by construction)
     * or when no "CC_START" bracket is currently open at all (ordinary
     * code outside any call's own argument marshalling never needs this).
     */
    private void raw(String text) {
        currentSink().append(text).append('\n');
        trackStackDelta(text);
    }

    /**
     * Recognizes the exact instruction shapes this backend ever emits
     * that move the real stack pointer by a literal, fixed amount --
     * `pushq %reg`/`push reg` (+8), `popq %reg`/`pop reg` (-8), `subq
     * $N, %rsp`/`sub rsp, N` (+N), `addq $N, %rsp`/`add rsp, N` (-N) --
     * and folds each into `argTrackDelta`. Deliberately does *not*
     * recognize an absolute assignment to rsp (`movq ..., %rsp`/`mov
     * rsp, ...`, the shape `emitAlignedCall`'s own restore and this same
     * fix's own "CC_START"/"CALL" reservation-and-restore both use) --
     * those aren't a *relative* move by a byte count this tracker could
     * even meaningfully add or subtract, and every real caller of one
     * already guarantees, by its own construction, that the net effect
     * across it is exactly zero.
     */
    private void trackStackDelta(String rawText) {
        if (argTrackSuspendDepth > 0 || argTrackDeltaStack.isEmpty()) {
            return;
        }
        String t = rawText.trim();
        long delta = 0;
        if (t.startsWith("pushq %") || (t.startsWith("push ") && !t.startsWith("pushq"))) {
            delta = 8;
        } else if (t.startsWith("popq %") || (t.startsWith("pop ") && !t.startsWith("popq"))) {
            delta = -8;
        } else if (t.startsWith("subq $") && t.endsWith(", %rsp")) {
            delta = parseRspDelta(t, "subq $", ", %rsp");
        } else if (t.startsWith("addq $") && t.endsWith(", %rsp")) {
            delta = -parseRspDelta(t, "addq $", ", %rsp");
        } else if (t.startsWith("sub rsp, ")) {
            delta = Long.parseLong(t.substring("sub rsp, ".length()).trim());
        } else if (t.startsWith("add rsp, ")) {
            delta = -Long.parseLong(t.substring("add rsp, ".length()).trim());
        }
        if (delta != 0) {
            argTrackDeltaStack.push(argTrackDeltaStack.pop() + delta);
        }
    }

    private long parseRspDelta(String t, String prefix, String suffix) {
        return Long.parseLong(t.substring(prefix.length(), t.length() - suffix.length()).trim());
    }

    /** push the 64-bit value in the given register onto the real machine stack. */
    private void pushReg(String reg) {
        raw(isWindows() ? ("    push " + reg) : ("    pushq %" + reg));
    }

    /** pop the top of the real machine stack into the given register. */
    private void popReg(String reg) {
        raw(isWindows() ? ("    pop " + reg) : ("    popq %" + reg));
    }

    private void movMemToReg(String reg, long offset) {
        if (isWindows()) {
            raw("    mov " + reg + ", [rbp" + signed(offset) + "]");
        } else {
            raw("    movq " + offset + "(%rbp), %" + reg);
        }
    }

    private void movRegToMem(String reg, long offset) {
        if (isWindows()) {
            raw("    mov [rbp" + signed(offset) + "], " + reg);
        } else {
            raw("    movq %" + reg + ", " + offset + "(%rbp)");
        }
    }

    private void leaMemToReg(String reg, long offset) {
        if (isWindows()) {
            raw("    lea " + reg + ", [rbp" + signed(offset) + "]");
        } else {
            raw("    leaq " + offset + "(%rbp), %" + reg);
        }
    }

    private void leaGlobalToReg(String reg, String globalName) {
        if (globalAliasOffset.containsKey(globalName)) {
            // An alias into a parent block's own storage (see
            // globalAliasOffset's doc comment) -- load the parent's
            // address and add the field's fixed offset, rather than
            // referencing a separate (nonexistent) label of its own.
            String parentLabel = mangleGlobalName(globalAliasParent.get(globalName));
            long offset = globalAliasOffset.get(globalName);
            if (isWindows()) {
                raw("    lea " + reg + ", [" + parentLabel + (offset != 0 ? "+" + offset : "") + "]");
            } else {
                raw("    leaq " + parentLabel + (offset != 0 ? "+" + offset : "") + "(%rip), %" + reg);
            }
            return;
        }
        String label = mangleGlobalName(globalName);
        if (isWindows()) {
            raw("    lea " + reg + ", [" + label + "]");
        } else {
            raw("    leaq " + label + "(%rip), %" + reg);
        }
    }

    private void movImmToReg(String reg, long imm) {
        if (isWindows()) {
            raw("    mov " + reg + ", " + imm);
        } else {
            raw("    movq $" + imm + ", %" + reg);
        }
    }

    private String signed(long v) {
        return v >= 0 ? ("+" + v) : String.valueOf(v);
    }

    // ---- Size-aware register/memory helpers ------------------------------

    /** al/ax/eax/rax-style sub-register name for a bare 64-bit register name and a byte width. */
    private String sizedReg(String reg64, int size) {
        switch (reg64) {
            case "rax": return size == 1 ? "al" : size == 2 ? "ax" : size == 4 ? "eax" : "rax";
            case "rbx": return size == 1 ? "bl" : size == 2 ? "bx" : size == 4 ? "ebx" : "rbx";
            case "rcx": return size == 1 ? "cl" : size == 2 ? "cx" : size == 4 ? "ecx" : "rcx";
            case "rdx": return size == 1 ? "dl" : size == 2 ? "dx" : size == 4 ? "edx" : "rdx";
            case "rsi": return size == 1 ? "sil" : size == 2 ? "si" : size == 4 ? "esi" : "rsi";
            case "rdi": return size == 1 ? "dil" : size == 2 ? "di" : size == 4 ? "edi" : "rdi";
            default:
                if (reg64.matches("r(8|9|1[0-5])") && size < 8) {
                    return reg64 + (size == 1 ? "b" : size == 2 ? "w" : "d");
                }
                return reg64;
        }
    }

    /** Sign-extends the low `size` bytes of a 64-bit register in place (no-op for size >= 8). */
    private void signExtendReg(String reg, int size) {
        if (size >= 8) {
            return;
        }
        if (isWindows()) {
            if (size == 4) {
                raw("    movsxd " + reg + ", " + sizedRegWin(reg, 4));
            } else {
                raw("    movsx " + reg + ", " + sizedRegWin(reg, size));
            }
        } else if (size == 4) {
            raw("    movslq %" + sizedReg(reg, 4) + ", %" + reg);
        } else {
            raw("    movs" + movSuffix(size) + "q %" + sizedReg(reg, size) + ", %" + reg);
        }
    }

    /**
     * The shared core of every variable-count shift (stack form SHL/SHR/SAR, register-form rfShift). On entry %rcx holds the shift
     * count already zero-extended from the operand width (the caller saved %rcx and loaded it); `v` is a 64-bit register holding
     * the value (stale bits above `size` bytes allowed). On exit `v` holds the result, zero-extended from `size` bytes, and %rcx
     * (and %r15 for SAR) are clobbered. The rule (see the SHL case): count >= 64 gives 0 (SHL/SHR) / sign fill (SAR); a count
     * in [size*8, 63] needs no special case, because SHL's extra bits fall off the truncation, SHR's zero-extended operand has
     * no bits left, and SAR's sign-extended operand already is all sign bits.
     */
    private void emitShiftCore(String op, int size, String v) {
        if (op.equals("SAR")) {
            signExtendReg(v, size);
            // count = min(count, 63)
            movImmToReg(RF_SCRATCH, 63);
            if (isWindows()) {
                raw("    cmp rcx, " + RF_SCRATCH);
                raw("    cmova rcx, " + RF_SCRATCH);
                raw("    sar " + v + ", cl");
            } else {
                raw("    cmpq %" + RF_SCRATCH + ", %rcx");
                raw("    cmovaq %" + RF_SCRATCH + ", %rcx");
                raw("    sarq %cl, %" + v);
            }
            zeroExtendReg(v, size);
            return;
        }
        boolean left = op.equals("SHL");
        if (!left) {
            zeroExtendReg(v, size);
        }
        if (isWindows()) {
            raw("    " + (left ? "shl " : "shr ") + v + ", cl");
            raw("    cmp rcx, 64");
            raw("    sbb rcx, rcx");   // rcx = -1 when count < 64 (borrow), else 0
            raw("    and " + v + ", rcx");
        } else {
            raw("    " + (left ? "shlq" : "shrq") + " %cl, %" + v);
            raw("    cmpq $64, %rcx");
            raw("    sbbq %rcx, %rcx");
            raw("    andq %rcx, %" + v);
        }
        if (left) {
            zeroExtendReg(v, size);
        }
    }

    /** Zero-extends the low `size` bytes of a 64-bit register in place (no-op for size >= 8 or an odd size). */
    private void zeroExtendReg(String reg, int size) {
        if (size != 1 && size != 2 && size != 4) {
            return;
        }
        if (isWindows()) {
            if (size == 4) {
                raw("    mov " + sizedRegWin(reg, 4) + ", " + sizedRegWin(reg, 4));
            } else {
                raw("    movzx " + reg + ", " + sizedRegWin(reg, size));
            }
        } else if (size == 4) {
            raw("    movl %" + sizedReg(reg, 4) + ", %" + sizedReg(reg, 4));
        } else {
            raw("    movz" + movSuffix(size) + "q %" + sizedReg(reg, size) + ", %" + reg);
        }
    }

    private String movSuffix(int size) {
        return size == 1 ? "b" : size == 2 ? "w" : size == 4 ? "l" : "q";
    }

    /**
     * Rounds a byte count up to a real, loadable hardware width -- 1, 2,
     * 4, or 8 -- the only widths a single x86 load instruction actually
     * has (there is no such thing as a 3, 5, 6, or 7-byte load; "movzqq"
     * -- naively asking `movSuffix` for one -- isn't a real instruction
     * at all, and used to be emitted verbatim, a genuine, confirmed
     * assembler-rejected gap: a struct with a packed sub-word array
     * member, e.g. `letters: mut char[3]`, produces exactly this "PUSH 3
     * $offset" shape whenever that member is read back by value (a
     * `LOOKUP_ARRAY` on it). Over-reading a few extra bytes past the
     * real value is harmless here: `ALLOC` always rounds a frame's own
     * size up to 16 bytes, so there is always a little slack past any
     * field; and every real consumer of a sub-8-byte loaded value only
     * ever reads back its own declared low `size` bytes (an array
     * element extraction, a narrow comparison, ...), never the whole
     * 8-byte register as one opaque scalar, so garbage sitting in the
     * unused high bytes is never actually observed.
     */
    private static int roundUpToLoadableWidth(int size) {
        return size <= 2 ? size : size <= 4 ? 4 : 8;
    }

    /** zero-extending load of `size` bytes at (%rbp+offset) into the full 64-bit `reg64`. */
    private void loadSizedFromFrame(String reg64, long offset, int size) {
        int loadSize = roundUpToLoadableWidth(size);
        if (isWindows()) {
            if (loadSize == 8) {
                raw("    mov " + reg64 + ", [rbp" + signed(offset) + "]");
            } else {
                raw("    movzx " + reg64 + ", " + winPtrSize(loadSize) + " [rbp" + signed(offset) + "]");
            }
            return;
        }
        if (loadSize == 8) {
            raw("    movq " + offset + "(%rbp), %" + reg64);
        } else if (loadSize == 4) {
            // movl auto-zero-extends into the full 64-bit register.
            raw("    movl " + offset + "(%rbp), %" + sizedReg(reg64, 4));
        } else {
            raw("    movz" + movSuffix(loadSize) + "q " + offset + "(%rbp), %" + reg64);
        }
    }

    /** store the low `size` bytes of `reg64` to (%rbp+offset). */
    private void storeSizedToFrame(String reg64, long offset, int size) {
        if (isOddSize(size)) {
            storeOddSize(reg64, "rbp", offset, size);
            return;
        }
        if (isWindows()) {
            raw("    mov [rbp" + signed(offset) + "], " + sizedRegWin(reg64, size));
            return;
        }
        raw("    mov" + movSuffix(size) + " %" + sizedReg(reg64, size) + ", " + offset + "(%rbp)");
    }

    /** zero-extending load of `size` bytes at (%addrReg) into `destReg64`. Same over-read-past-a-non-power-of-2-size reasoning as `loadSizedFromFrame` -- see `roundUpToLoadableWidth`'s own doc comment. */
    private void loadSizedFromAddr(String destReg64, String addrReg64, int size) {
        int loadSize = roundUpToLoadableWidth(size);
        String mem = rfMemOperand(addrReg64);
        if (isWindows()) {
            if (loadSize == 8) {
                raw("    mov " + destReg64 + ", " + mem);
            } else {
                raw("    movzx " + destReg64 + ", " + winPtrSize(loadSize) + " " + mem);
            }
            return;
        }
        if (loadSize == 8) {
            raw("    movq " + mem + ", %" + destReg64);
        } else if (loadSize == 4) {
            raw("    movl " + mem + ", %" + sizedReg(destReg64, 4));
        } else {
            raw("    movz" + movSuffix(loadSize) + "q " + mem + ", %" + destReg64);
        }
    }

    /**
     * Cuts a field / element of `size` (> 8) bytes out of the pushed value block of `totalWords` words and leaves it as a block of its own on the
     * stack (rsp raised past the words that are no longer needed). On entry `rax` = address of the field's first byte inside the block and `r14` =
     * that byte's offset inside its word (0..7). Block layout: word k (bytes 8k..8k+7) sits at rsp + (T-1-k)*8, bytes natural inside a word, so the
     * field's bytes are NOT contiguous in memory unless it starts on a word boundary: result word j is the 64 bits starting at byte `within` of source
     * word w+j, continued into word w+j+1 (8 bytes BELOW it), which is exactly `shrd` (count 0 for an aligned field leaves the word unchanged).
     * Result word j goes to the slot of source word j (rsp + (T-1-j)*8): once rsp is raised by (T - E)*8 that is where it must be (rsp' + (E-1-j)*8),
     * and writing in ascending j never overwrites a word still to be read. The word past the block end (read for the last, possibly partial, result
     * word) lies just below rsp: valid stack, and only bytes beyond the field come from it.
     * %rcx is the shift count register and may hold an already-loaded call argument: it is stashed in r13 (backend scratch).
     * (Used by LOOKUP_ARRAY for an element wider than a word, and by DOT for a field wider than a word: a field that is not a whole number of
     * words used to be cut out with a shrink by `total*8 - size` bytes, which is only right when the field's offset and size are whole words.)
     */
    private void extractWordsFromReversedBlock(long totalWords, int size) {
        long elemWords = (size + 7) / 8;
        if (isWindows()) {
            raw("    mov r11, rax");
            raw("    sub r11, r14");
            raw("    shl r14, 3");
            raw("    mov r13, rcx");
            raw("    mov rcx, r14");
        } else {
            raw("    movq %rax, %r11");
            raw("    subq %r14, %r11");
            raw("    shlq $3, %r14");
            raw("    movq %rcx, %r13");
            raw("    movq %r14, %rcx");
        }
        for (long j = 0; j < elemWords; j++) {
            long lo = -(j * 8);
            long hi = -(j * 8 + 8);
            long dst = (totalWords - 1 - j) * 8;
            if (isWindows()) {
                raw("    mov rax, [r11" + (lo == 0 ? "" : String.valueOf(lo)) + "]");
                raw("    mov r14, [r11" + hi + "]");
                raw("    shrd rax, r14, cl");
                raw("    mov [rsp+" + dst + "], rax");
            } else {
                raw("    movq " + lo + "(%r11), %rax");
                raw("    movq " + hi + "(%r11), %r14");
                raw("    shrdq %cl, %r14, %rax");
                raw("    movq %rax, " + dst + "(%rsp)");
            }
        }
        raw(isWindows() ? "    mov rcx, r13" : "    movq %r13, %rcx");
        if (totalWords > elemWords) {
            raw(isWindows() ? ("    add rsp, " + ((totalWords - elemWords) * 8))
                    : ("    addq $" + ((totalWords - elemWords) * 8) + ", %rsp"));
        }
    }


    /** 3, 5, 6 and 7 bytes: no single x86 store has these widths. */
    private static boolean isOddSize(int size) {
        return size == 3 || (size >= 5 && size <= 7);
    }

    /**
     * Stores exactly the low `size` bytes (3, 5, 6 or 7) of `srcReg64` at `off(base)`, as a 4/2/1-byte sequence. A single 8-byte store here (what
     * `movSuffix`/`sizedReg` used to fall back to) writes 1 to 5 bytes too many: past the end of a packed construction image that is the
     * pointer sitting just above it (a `u16[3][2]` built from two row variables lost the low 2 bytes of its destination address), in a frame
     * it is the neighbouring variable. The source register is rotated between the pieces and rotated back, so it is unchanged afterwards.
     */
    private void storeOddSize(String srcReg64, String baseReg64, long off, int size) {
        int[] widths = size == 3 ? new int[] {2, 1} : size == 5 ? new int[] {4, 1} : size == 6 ? new int[] {4, 2} : new int[] {4, 2, 1};
        long at = off;
        int rot = 0;
        for (int i = 0; i < widths.length; i++) {
            storePiece(srcReg64, baseReg64, at, widths[i]);
            at += widths[i];
            if (i + 1 < widths.length) {
                rotReg(srcReg64, true, widths[i] * 8);
                rot += widths[i] * 8;
            }
        }
        if (rot > 0) {
            rotReg(srcReg64, false, rot);
        }
    }

    private void storePiece(String srcReg64, String baseReg64, long off, int width) {
        if (isWindows()) {
            raw("    mov [" + baseReg64 + (off == 0 ? "" : signed(off)) + "], " + sizedRegWin(srcReg64, width));
        } else {
            raw("    mov" + movSuffix(width) + " %" + sizedReg(srcReg64, width) + ", " + (off == 0 ? "" : String.valueOf(off)) + "(%" + baseReg64 + ")");
        }
    }

    private void rotReg(String reg64, boolean right, int bits) {
        String op = right ? "ror" : "rol";
        raw(isWindows() ? ("    " + op + " " + reg64 + ", " + bits) : ("    " + op + "q $" + bits + ", %" + reg64));
    }

    /** store the low `size` bytes of `srcReg64` to (%addrReg64). */
    private void storeSizedToAddr(String srcReg64, String addrReg64, int size) {
        if (isOddSize(size)) {
            storeOddSize(srcReg64, addrReg64, 0, size);
            return;
        }
        if (isWindows()) {
            raw("    mov " + rfMemOperand(addrReg64) + ", " + sizedRegWin(srcReg64, size));
            return;
        }
        raw("    mov" + movSuffix(size) + " %" + sizedReg(srcReg64, size) + ", " + rfMemOperand(addrReg64));
    }

    private String winPtrSize(int size) {
        return size == 1 ? "byte" : size == 2 ? "word" : "dword";
    }

    private String sizedRegWin(String reg64, int size) {
        return sizedReg(reg64, size);
    }

    /** pushes a >8-byte value stored at (%rbp+baseOffset), one whole word at a time, lowest source offset first -- the highest-offset word ends up on top of the real stack. Mirror of `assignBlock`/POP's own block-store below. */
    private void pushBlockFromFrame(long baseOffset, long size) {
        long words = (size + 7) / 8;
        for (long i = 0; i < words; i++) {
            loadSizedFromFrame("rax", baseOffset + i * 8, 8);
            pushReg("rax");
        }
    }

    /**
     * The construction-aware sibling of `pushBlockFromFrame`, used only
     * when this exact "PUSH size $offset" line is itself a direct member
     * of a construction run `precomputeConstructionOffsets` already
     * resolved (see that "PUSH" case's own call site for the real bug
     * this fixes). Unlike `pushBlockFromFrame` -- which reverses word
     * order, matching DEREF's own "highest-offset word on top" pushed-
     * value convention -- this reserves exactly this entry's own
     * declared width and copies its bytes straight across, front to
     * back, preserving the source's own real, natural, ascending-offset
     * order, landing them at the exact packed position
     * (`constructionOffset`, possibly negative -- see
     * `storeConstructionOrPlainPush`'s own doc comment for why that's
     * both expected and safe) the run's own layout already computed for
     * it. `%rcx` is saved/restored around the copy for the identical
     * reason `assignConstructionBlock` already does -- this can run as
     * one entry inside a still-in-progress call's own argument
     * marshalling, where `%rcx` may already be carrying a live,
     * not-yet-consumed incoming parameter.
     */
    private void pushBlockFromFrameConstructionAware(long baseOffset, long size) {
        Long constructionOffset = pushStoreOffsetByLine.get(currentLineIndex);
        if (constructionOffset == null) {
            pushBlockFromFrame(baseOffset, size);
            return;
        }
        // Copy through rax in 8/4/2/1-byte pieces. This used to be `rep movsb` with rdi/rsi/rcx and a `push rcx` around it: the push writes 8 bytes
        // just below rsp, which is where the fields of this construction stored EARLIER already sit (each field is stored at its final place,
        // below the current rsp, before rsp reaches it), so an earlier narrow field (`tag: u8` before an array member) was overwritten; and rdi/rsi
        // are argument registers that an enclosing call may already have loaded.
        if (isWindows()) {
            raw("    sub rsp, " + size);
        } else {
            raw("    subq $" + size + ", %rsp");
        }
        long pos = 0;
        while (pos < size) {
            long left = size - pos;
            int w = left >= 8 ? 8 : left >= 4 ? 4 : left >= 2 ? 2 : 1;
            loadSizedFromFrame("rax", baseOffset + pos, w);
            long dst = constructionOffset + pos;
            if (isWindows()) {
                raw("    mov [rsp" + signed(dst) + "], " + sizedRegWin("rax", w));
            } else {
                raw("    mov" + movSuffix(w) + " %" + sizedReg("rax", w) + ", " + dst + "(%rsp)");
            }
            pos += w;
        }
    }

    /** the block-store counterpart of `pushBlockFromFrame`/DEREF's own multi-word push: pops a >8-byte value (top of stack = highest-offset word) together with the single-word address pushed just before it, and stores each word to its real position. The address is read non-destructively first (it sits `size` bytes below the current top, past the whole value block) so it can still be located after the value words are popped off. */
    private void assignBlock(long size) {
        long words = (size + 7) / 8;
        if (isWindows()) {
            raw("    mov r15, [rsp+" + (words * 8) + "]");
        } else {
            raw("    movq " + (words * 8) + "(%rsp), %r15");
        }
        for (long i = 0; i < words; i++) {
            popReg("rax");
            // Word k of the value sits at byte 8k. The top word popped first is the highest one, k = words-1. (This used `size - 8 - 8i`, which is
            // the same only when size is a whole number of words: a 12- or 20-byte array copy (`let a = mut s.v`) put its words 4 bytes too low.)
            long k = words - 1 - i;
            long destOff = k * 8;
            long n = Math.min(8, size - destOff); // the last word of a size that is not a multiple of 8 is partial: write only its real bytes
            if (n == 8) {
                if (isWindows()) {
                    raw("    mov [r15" + (destOff == 0 ? "" : signed(destOff)) + "], rax");
                } else {
                    raw("    movq %rax, " + (destOff == 0 ? "" : String.valueOf(destOff)) + "(%r15)");
                }
            } else if (isOddSize((int) n)) {
                storeOddSize("rax", "r15", destOff, (int) n);
            } else {
                storePiece("rax", "r15", destOff, (int) n);
            }
        }
        // The value block is now fully popped; the address word pushed
        // ahead of it is still sitting on top -- drop it for real now
        // that it's been read.
        raw(isWindows() ? "    add rsp, 8" : "    addq $8, %rsp");
    }

    /**
     * The construction-scoped counterpart of `assignBlock`, used only
     * when `constructionScopedAssign` confirms this exact "ASSIGN" line
     * is a plain, stack-resident struct/array/primitive literal's own
     * top-level store (see that field's own doc comment for the bug
     * this fixes). Stack, top to bottom: a real, byte-exact,
     * `size`-byte tightly-packed image of the value (every push in the
     * run reserved only its own declared width), then the single-word
     * destination address pushed ahead of it. Mirrors `case "NEW"`'s own
     * "genuinely trivial and completely struct-agnostic" copy exactly --
     * one straight `rep movsb` block copy, no field count, no offsets,
     * nothing type-specific -- except the destination is this already-
     * pushed address rather than a fresh `malloc` buffer, so there's no
     * allocation call here at all.
     */
    // 2026-09: fixed a real, silent register-clobber bug here -- see
    // this project's own CLAUDE.md, "assignConstructionBlock clobbered
    // %rcx" -- pushq/popq %rcx now bracket the rep movsb counter setup
    // so a still-unread incoming argument register (SysV ARG3/win64
    // ARG3, both %rcx) survives this method's own use of it.
    private void assignConstructionBlock(long size) {
        if (size > 0 && size % 8 == 0 && size <= 64) {
            // A small whole-word block (a range is 16 bytes, built on every `for` entry) is copied word by word: no rep prefix, no
            // rcx save/restore. Only r15 (destination) and rdi (data) are used; both were already clobbered by the general path below.
            if (isWindows()) {
                raw("    mov r15, [rsp+" + size + "]");
                for (long o = 0; o < size; o += 8) {
                    raw("    mov rdi, [rsp+" + o + "]");
                    raw("    mov [r15+" + o + "], rdi");
                }
                raw("    add rsp, " + (size + 8));
            } else {
                raw("    movq " + size + "(%rsp), %r15");
                for (long o = 0; o < size; o += 8) {
                    raw("    movq " + o + "(%rsp), %rdi");
                    raw("    movq %rdi, " + o + "(%r15)");
                }
                raw("    addq $" + (size + 8) + ", %rsp");
            }
            return;
        }
        if (isWindows()) {
            raw("    mov r15, [rsp+" + size + "]"); // destination address, sitting just past the value block
            raw("    mov rdi, r15");
            raw("    mov rsi, rsp");
            raw("    push rcx"); // rcx may still hold a live, not-yet-consumed incoming argument (e.g. win64's ARG3) -- rep movsb needs it as a byte counter, so save/restore around that use rather than clobbering it
            raw("    mov rcx, " + size);
            raw("    rep movsb");
            raw("    pop rcx");
            raw("    add rsp, " + size);
            raw("    add rsp, 8"); // the address word itself, now that it's been read
        } else {
            raw("    movq " + size + "(%rsp), %r15"); // destination address, sitting just past the value block
            raw("    movq %r15, %rdi");
            raw("    movq %rsp, %rsi");
            raw("    pushq %rcx"); // rcx may still hold a live, not-yet-consumed incoming argument (SysV's ARG3) -- rep movsb needs it as a byte counter, so save/restore around that use rather than clobbering it
            raw("    movq $" + size + ", %rcx");
            raw("    rep movsb");
            raw("    popq %rcx");
            raw("    addq $" + size + ", %rsp");
            raw("    addq $8, %rsp"); // the address word itself, now that it's been read
        }
    }

    private static boolean isFloatLiteral(String s) {
        return s.matches("-?\\d+\\.\\d+([eE][+-]?\\d+)?");
    }

    /** "'a'", "'\n'", "'\0'", ... -- a char-literal PUSH operand, exactly as `BytecodeEmitter.escapeForBytecode` serializes one: an opening single quote, one real character's worth of content, a closing single quote. Deliberately checked structurally (quote ... quote) rather than by first ruling out every other operand shape, since a bare identifier can never itself start *and* end with `'`. */
    private static boolean isCharLiteral(String s) {
        return s.length() >= 3 && s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'';
    }

    /**
     * Decodes a char-literal PUSH operand back to its real byte value --
     * the exact inverse of `BytecodeEmitter.escapeForBytecode`, which
     * this must stay in lockstep with: every one of that method's own
     * escape outputs ("\\\\", "\\\"", "\\n", "\\t", "\\r", "\\0", "\\b",
     * "\\f", "\\a", "\\v") is decoded back here, plus the plain,
     * unescaped-single-byte case for every other character. A char is
     * always exactly one byte in this language (`PRIMITIVE_SIZE.put(
     * "char", 1L)` in the front end), so the result is always a single
     * 0-255 value, never a multi-byte sequence.
     */
    private static long decodeCharLiteral(BytecodeToken operandTok) {
        String operand = operandTok.text;
        String content = operand.substring(1, operand.length() - 1);
        char value;
        if (content.length() == 1) {
            value = content.charAt(0);
        } else if (content.length() == 2 && content.charAt(0) == '\\') {
            switch (content.charAt(1)) {
                case '\\': value = '\\'; break;
                case '"': value = '"'; break;
                case 'n': value = '\n'; break;
                case 't': value = '\t'; break;
                case 'r': value = '\r'; break;
                case '0': value = '\0'; break;
                case 'b': value = '\b'; break;
                case 'f': value = '\f'; break;
                case 'a': value = 7; break; // bell -- no Java char-literal shorthand
                case 'v': value = 11; break; // vertical tab -- no Java char-literal shorthand
                default:
                    throw new CodegenException("codegen", operandTok.file, operandTok.line,
                            "unrecognized char-literal escape '" + operand + "'");
            }
        } else {
            throw new CodegenException("codegen", operandTok.file, operandTok.line,
                    "malformed char-literal operand '" + operand + "'");
        }
        return value;
    }

    // ---- Float (SSE) helpers -------------------------------------------------
    //
    // Every value on this backend's own value stack -- float bits included
    // -- already travels as opaque bytes through the ordinary
    // push/pop-a-64-bit-GPR mechanism (see PUSH's own isFloatLiteral
    // branch above, and ASSIGN/frame-load, neither of which interprets a
    // float's bits any differently from an int's). What genuinely needs
    // the separate SSE register file is *arithmetic*: `movq` between a
    // GPR and an xmm register moves the raw 64 bits across with zero
    // reinterpretation (the exact same "opaque bytes" property the
    // whole-word stack model already relies on), so every float op below
    // pops its operand(s) through the ordinary GPR path first, shuttles
    // them into xmm0/xmm1 with this one instruction, does the real
    // floating-point op there, and shuttles the result back the same way
    // before pushing it -- no persistent xmm allocation, matching this
    // pass's own documented "simplest correct mapping" philosophy
    // (CLAUDE.md, "What's implemented").

    private void movRegToXmm(String gpr64, String xmmReg) {
        raw(isWindows() ? ("    movq " + xmmReg + ", " + gpr64) : ("    movq %" + gpr64 + ", %" + xmmReg));
    }

    private void movXmmToReg(String xmmReg, String gpr64) {
        raw(isWindows() ? ("    movq " + gpr64 + ", " + xmmReg) : ("    movq %" + xmmReg + ", %" + gpr64));
    }

    /** "ss" (single-precision, 4-byte) vs "sd" (double-precision, 8-byte) SSE instruction suffix for a given byte size. `f32` is the only floating-point type this language has at all (checked directly, not assumed -- see FLOAT_CHECK's own doc comment below), so every real call site passes 4 here; the 8-byte ("sd") branch is kept only so this doesn't silently mishandle a size this pass has never actually seen, not because anything in the language can produce one today. */
    private String sseSuffix(int size) {
        return size <= 4 ? "ss" : "sd";
    }

    /** reads `size` bytes at (%addrReg64) and pushes them: a single sized load+push for size<=8, or a whole-word-at-a-time block push (lowest source offset first, highest last/top) for a larger aggregate -- the read-side counterpart of DOT/LOOKUP_ARRAY/LOOKUP_DYN's own "_LHS" (address-only) variants, generalized to structs/array elements bigger than one register. `addrReg64` is clobbered. */
    private void pushSizedOrBlockFromAddr(String addrReg64, int size) {
        if (size <= 8) {
            loadSizedFromAddr("rax", addrReg64, size);
            pushReg("rax");
            return;
        }
        long words = (size + 7) / 8;
        // The word is loaded into r11, never rax: callers such as
        // LOOKUP_DYN pass "rax" as the address register, and loading
        // the first word into it destroyed the address before the
        // second word was read (every dynarray element wider than one
        // word -- any struct -- read back garbage).
        for (long i = 0; i < words; i++) {
            loadSizedFromAddr("r11", addrReg64, 8);
            if (i < words - 1) {
                raw(isWindows() ? ("    add " + addrReg64 + ", 8") : ("    addq $8, %" + addrReg64));
            }
            pushReg("r11");
        }
    }

    // ---- Calling convention ------------------------------------------------

    private static final String[] LINUX_ARG_REGS = {"rdi", "rsi", "rdx", "rcx", "r8", "r9"};
    private static final String[] WIN_ARG_REGS = {"rcx", "rdx", "r8", "r9"};
    private static final String[] LINUX_ARG_REGS_FLOAT =
            {"xmm0", "xmm1", "xmm2", "xmm3", "xmm4", "xmm5", "xmm6", "xmm7"};
    private static final String[] WIN_ARG_REGS_FLOAT = {"xmm0", "xmm1", "xmm2", "xmm3"};

    private String argReg(int index) {
        String[] table = isWinAbi() ? WIN_ARG_REGS : LINUX_ARG_REGS; // register choice is an ABI question, not a syntax one -- WINDOWS_GNU_X64 uses these same win64 registers, just named/referenced in AT&T syntax
        if (index < table.length) {
            return table[index];
        }
        // Beyond the register-passed args: this first version doesn't
        // marshal stack-passed arguments -- no fixture this pass was
        // verified against needs a 5th+ (Linux) / 5th+ (Windows) arg.
        return "rax";
    }

    /** The float-bank counterpart of `argReg` -- `ARGn`/`FARGn`'s own real per-bank index already accounts for the two conventions' different counting rules (see `AddressLoweringPass.resolveArgWordSlots`/`CompilerConfig.CallingConvention.sharedArgumentPosition` on the sibling lowering project), so this is a plain, dumb table lookup, exactly like `argReg` -- no convention-mode awareness needed here at all. */
    private String argFloatReg(int index) {
        String[] table = isWinAbi() ? WIN_ARG_REGS_FLOAT : LINUX_ARG_REGS_FLOAT;
        if (index < table.length) {
            return table[index];
        }
        return "xmm0"; // beyond the register-passed args -- same documented stack-argument gap argReg's own fallback has.
    }

    /**
     * Wraps a call sequence with 16-byte stack alignment, saving and
     * restoring the *exact* current rsp around it -- needed because
     * this backend's own stack-machine-to-real-stack translation
     * pushes/pops arbitrarily many words between calls, so rsp's
     * alignment at any given `call` site isn't otherwise known or
     * guaranteed, and an unaligned rsp across a real `call` is
     * undefined behavior per the x86-64 ABI (some libc routines really
     * do fault on it). Restoring the saved rsp afterward keeps this
     * backend's own "abstract stack depth" bookkeeping exactly correct
     * regardless of the alignment adjustment.
     *
     * The pre-alignment rsp used to be stashed in %r13 for the
     * duration of the call, on the theory that r13 is callee-saved and
     * so survives any well-behaved call -- true for a real, externally
     * compiled function (malloc/printf/...), which genuinely honors
     * the ABI's callee-saved registers, but **false** for a call into
     * a function this same backend generated: `FUNC_START`/`FUNC_END`
     * only ever save/restore %rbp, never r12-r15/rbx, so a
     * Caspien-compiled callee that itself makes further calls reuses
     * r13 for its own identical save/align/restore dance around each
     * of *its* calls, silently overwriting the value the outer call
     * was relying on. Root-caused directly against `gt_register`
     * (itself calling `gtSlotAt`/`gtReadSlot`): confirmed via gdb that
     * r13 held the correct pre-call rsp immediately before `call
     * gt_register`, but held something else entirely (off by over a
     * hundred bytes -- gt_register's own last internal save) the
     * instant it returned, which was then blindly copied into rsp,
     * corrupting the caller's whole abstract stack depth from that
     * point on.
     *
     * Fixed by not trusting *any* register to survive the call at
     * all -- the pre-alignment rsp is stashed in a dedicated stack
     * slot instead, reserved right at the call site. A stack slot
     * can't be silently reused by a nested call the way a shared
     * register can: a nested call just gets its own slot, further
     * down, out of the way. This relies on nothing but the one
     * invariant every function here already upholds regardless of
     * what it's compiled from -- a well-behaved callee (real or
     * Caspien-compiled) always returns with rsp exactly where it was
     * the moment the `call` instruction executed -- so the slot is
     * still exactly where it was left, at a fixed offset from the
     * (unchanged) rsp, the instant the call returns.
     */
    /**
     * Wraps a call sequence with 16-byte stack alignment, saving and
     * restoring the *exact* current rsp around it -- needed because
     * this backend's own stack-machine-to-real-stack translation
     * pushes/pops arbitrarily many words between calls, so rsp's
     * alignment at any given `call` site isn't otherwise known or
     * guaranteed, and an unaligned rsp across a real `call` is
     * undefined behavior per the x86-64 ABI (some libc routines really
     * do fault on it). Restoring the saved rsp afterward keeps this
     * backend's own "abstract stack depth" bookkeeping exactly correct
     * regardless of the alignment adjustment.
     *
     * The pre-alignment rsp used to be stashed in %r13 for the
     * duration of the call, on the theory that r13 is callee-saved and
     * so survives any well-behaved call -- true for a real, externally
     * compiled function (malloc/printf/...), which genuinely honors
     * the ABI's callee-saved registers, but **false** for a call into
     * a function this same backend generated: `FUNC_START`/`FUNC_END`
     * only ever save/restore %rbp, never r12-r15/rbx, so a
     * Caspien-compiled callee that itself makes further calls reuses
     * r13 for its own identical save/align/restore dance around each
     * of *its* calls, silently overwriting the value the outer call
     * was relying on. Root-caused directly against `gt_register`
     * (itself calling `gtSlotAt`/`gtReadSlot`): confirmed via gdb that
     * r13 held the correct pre-call rsp immediately before `call
     * gt_register`, but held something else entirely (off by over a
     * hundred bytes -- gt_register's own last internal save) the
     * instant it returned, which was then blindly copied into rsp,
     * corrupting the caller's whole abstract stack depth from that
     * point on.
     *
     * Fixed by not trusting *any* register to survive the call at
     * all -- the pre-alignment rsp is stashed in a dedicated stack
     * slot instead, reserved right at the call site. A stack slot
     * can't be silently reused by a nested call the way a shared
     * register can: a nested call just gets its own slot, further
     * down, out of the way. This relies on nothing but the one
     * invariant every function here already upholds regardless of
     * what it's compiled from -- a well-behaved callee (real or
     * Caspien-compiled) always returns with rsp exactly where it was
     * the moment the `call` instruction executed -- so the slot is
     * still exactly where it was left, at a fixed offset from the
     * (unchanged) rsp, the instant the call returns.
     */
    /**
     * How many bytes `case "CC_START"` reserves below its own aligned
     * rsp, *before* `stackArgBytes` worth of stack-passed argument words
     * get pushed on top of that reservation and *before* the `call`
     * itself pushes its own 8-byte return address -- chosen so that,
     * after both of those, rsp lands back on a real 16-byte boundary
     * (the x86-64 ABI's own call-site alignment requirement) regardless
     * of `stackArgBytes`'s own value.
     *
     * `stackArgBytes` is always a multiple of 8 (every real `pushq`/`popq`/
     * `add rsp,N`/`sub rsp,N` `trackStackDelta` ever recognizes moves rsp
     * by a whole 8-byte word or a whole multiple of one -- see
     * `argTrackDeltaStack`'s own doc comment). Reasoned from a 16-aligned
     * starting point (right after "and rsp, -16"): the x86-64 ABI's own
     * rule is that %rsp must read 0 (mod 16) at the exact point the
     * `call` instruction executes -- confirmed directly against the real
     * ABI text ("the value (%rsp + 8) is always a multiple of 16 when
     * control is transferred to the function entry point," i.e.
     * immediately *after* `call`'s own 8-byte return-address push, which
     * only holds if %rsp was 0 mod 16 immediately *before* it) and
     * against this exact file's own long-since-verified, hardcoded
     * zero-stack-arg case (`emitAlignedCall`'s fixed `subq $16, %rsp`
     * reservation, with nothing pushed between it and `call`, which only
     * ever worked because 16-aligned-minus-16 is still 0 mod 16). An
     * 8-byte-granular reservation followed by an 8-byte-granular
     * argument push always lands on an even multiple of 8, so the only
     * question is *which* multiple -- exactly 0 (mod 16) when
     * `stackArgBytes` is itself a multiple of 16 (needing a full 16-byte
     * reservation to stay at 0), exactly 8 (mod 16, needing only an
     * 8-byte reservation to land back on 0) otherwise.
     *
     * **This ternary was previously inverted -- a real, confirmed bug,
     * not a hypothetical one.** The two, pre-existing overflow fixtures
     * that first verified this whole reservation/restore mechanism
     * (`register_overflow_args_even_cg_test`/`_odd_cg_test`) both call a
     * plain, all-integer function -- one that never performs any
     * alignment-sensitive SSE stack access -- so both silently tolerated
     * a call site that was, in fact, misaligned by 8 bytes the entire
     * time. This surfaced for real only once a call combining a
     * stack-passed argument *with* a genuinely alignment-sensitive
     * callee was tested: `printf(...)` with one float argument pushed
     * onto the stack (a true C varargs call, whose own glibc
     * implementation spills its incoming xmm registers into its
     * register-save area with alignment-requiring `movaps`) segfaulted
     * deep inside `__printf` itself -- confirmed via gdb to be a stack-
     * alignment fault, not a value-correctness one (the pushed argument's
     * own value, and every register-passed one, were already confirmed
     * correct by that point). Fixed by swapping the ternary's two
     * branches to match the real requirement above.
     */
    private int alignPadFor(long stackArgBytes) {
        return (stackArgBytes % 16 == 0) ? 16 : 8;
    }

    /**
     * The `case "CALL"`/`case "INVOKE"` half of the stack-passed-argument
     * fix (see `argTrackDeltaStack`'s own doc comment): restores rsp
     * to its real pre-reservation value by reading the value
     * `resolveStackArgBytesAndCall`'s own reservation saved back out of
     * its own reserved slot. That slot's own address never moved, but rsp has, by exactly `stackArgBytes`
     * (the argument words pushed on top of it since) -- so it's read from
     * `[rsp+stackArgBytes]`, not `[rsp]`, at this point. Called only when
     * `stackArgBytes > 0`; the ordinary all-register-arguments case never
     * reaches this at all, still cleaning up through `emitAlignedCall`'s
     * own original, unchanged restore instead.
     */
    private void emitStackArgRestore(long stackArgBytes) {
        // A single memory-to-register mov straight into rsp -- no scratch
        // register at all, deliberately: %rax holds this call's own
        // just-returned value at exactly this point (for a non-void
        // callee), so staging through it here (an earlier version of this
        // fix did exactly that) would silently clobber the real return
        // value before "PUSH_RET_INT" ever gets to read it. Found via a
        // real, confirmed bug: with the alignment-timing fix alone in
        // place, `sum8(1..8)` correctly computed and returned 36 in %rax
        // (confirmed directly via gdb), only for the very next
        // instruction to immediately overwrite it with a stray stack
        // address. `emitAlignedCall`'s own original restore ("movq
        // 8(%rsp), %rsp") already avoided this trap the same way; this
        // mirrors it exactly, just at a variable offset.
        raw(isWindows() ? ("    mov rsp, [rsp+" + stackArgBytes + "]")
                : ("    movq " + stackArgBytes + "(%rsp), %rsp"));
    }

    /**
     * Builds the same four-instruction alignment reservation
     * `emitAlignedCall` uses, but as plain text to `insert` at the very
     * front of a call's own buffer (`callBufferStack`) rather than
     * `raw()`-emitting it immediately -- see `argTrackDeltaStack`'s own
     * doc comment for why this has to be inserted retroactively, once
     * `stackArgBytes` is finally known at the "CALL" line, rather than
     * emitted up front at "CC_START" the way the ordinary, zero-stack-arg
     * case's `emitAlignedCall` gets away with. Deliberately built as a
     * plain string (not via `raw()`) so it is never itself mistaken for a
     * real, trackable stack-pointer move by `trackStackDelta` -- its own
     * net effect, paired with `emitStackArgRestore`, is zero by
     * construction, exactly like `emitAlignedCall`'s own reservation.
     */
    private String buildStackArgReservation(long stackArgBytes) {
        int pad = alignPadFor(stackArgBytes);
        if (isWindows()) {
            return "    mov rax, rsp\n"
                    + "    and rsp, -16\n"
                    + "    sub rsp, " + pad + "\n"
                    + "    mov [rsp], rax\n";
        }
        return "    movq %rsp, %rax\n"
                + "    andq $-16, %rsp\n"
                + "    subq $" + pad + ", %rsp\n"
                + "    movq %rax, (%rsp)\n";
    }

    /**
     * Puts the stack-passed argument words into the order the calling
     * conventions require: the FIRST stack-passed argument at the LOWEST
     * address (immediately above the return address), i.e. pushed
     * right-to-left. The front end evaluates and pushes arguments
     * left-to-right, so at the call the words sit in reverse: the first
     * stack-passed argument is deepest, the last is on top. A callee -- a
     * Caspien function reading `$16`, `$24`, ... or a C function such as
     * `printf` -- expects the opposite, so with two or more stack-passed
     * words the later ones were being read as the earlier ones (`eight(1,
     * ..., 8)` saw its seventh and eighth arguments swapped, and a
     * `printf` with eight integer varargs printed `7 8 6` for the last
     * three).
     *
     * Every stack-passed word is one whole 8-byte slot (`stackArgBytes` is
     * always a multiple of 8, see `alignPadFor`), and evaluation order
     * stays strictly left-to-right, so the fix is a plain in-place
     * reversal of those slots at the call, after every argument has been
     * produced. Uses rax and r11 as scratch (nothing live in either at
     * this point; r10 may hold an INVOKE target and is left alone).
     */
    private void reverseStackArgWords(long stackArgBytes) {
        long words = stackArgBytes / 8;
        for (long i = 0; i < words / 2; i++) {
            long lo = i * 8;
            long hi = (words - 1 - i) * 8;
            if (isWindows()) {
                raw("    mov rax, [rsp+" + lo + "]");
                raw("    mov r11, [rsp+" + hi + "]");
                raw("    mov [rsp+" + lo + "], r11");
                raw("    mov [rsp+" + hi + "], rax");
            } else {
                raw("    movq " + lo + "(%rsp), %rax");
                raw("    movq " + hi + "(%rsp), %r11");
                raw("    movq %r11, " + lo + "(%rsp)");
                raw("    movq %rax, " + hi + "(%rsp)");
            }
        }
    }

    /**
     * The shared `case "CALL"`/`case "INVOKE"` dispatch for a bytecode-
     * level call site: reads this call's own real stack-passed-argument
     * byte count (`argTrackDeltaStack`'s own doc comment explains how it
     * got there) and either takes the ordinary, unmodified
     * `emitAlignedCall` path (zero stack-passed bytes -- the
     * overwhelmingly common case) or, when there is at least one real
     * stack-passed argument, retroactively prepends the correctly-sized
     * alignment reservation to the front of this call's own buffer
     * (`callBufferStack`, opened back at this call's own "CC_START"),
     * emits the bare call, and restores rsp afterward -- see
     * `argTrackDeltaStack`'s own doc comment for the full design this
     * implements.
     */
    private void resolveStackArgBytesAndCall(Runnable callBody) {
        if (argTrackDeltaStack.isEmpty() || callBufferStack.isEmpty()) {
            // No open "CC_START" bracket at all -- shouldn't happen for a
            // real bytecode-level "CALL"/"INVOKE" (every one is always
            // wrapped in its own "CC_START"/"CC_END"), but falls back to
            // the always-safe, zero-stack-arg path rather than guessing.
            emitAlignedCall(callBody);
            return;
        }
        long stackArgBytes = argTrackDeltaStack.peek();
        if (stackArgBytes > 0) {
            callBufferStack.peek().insert(0, buildStackArgReservation(stackArgBytes));
            // win64 always reserves its own fixed 32-byte "shadow space"
            // immediately below the return address, *in addition to* any
            // real stack-passed arguments (`AddressLoweringPass.
            // resolveArgWordSlots`'s own callee-side offset formula
            // already bakes this in as `convention.shadowStack`) --
            // `emitAlignedCall`'s own zero-stack-arg path already
            // reserves/undoes this around its own call; this path needs
            // the identical reservation, just placed *after* the stack-
            // passed arguments are pushed rather than immediately after
            // the alignment slot, so it ends up adjacent to the return
            // address exactly where the callee's own formula expects it.
            // 32 is already a multiple of 16, so it never changes the
            // alignment parity `alignPadFor` already solved for.
            // Suspended (not tracked) for the same reason `emitAlignedCall`
            // suspends its own identical reservation: it's fully undone
            // before this call's own bracket closes, so it has zero real
            // net effect worth attributing to anything.
            boolean winAbi = isWinAbi();
            argTrackSuspendDepth++;
            try {
                reverseStackArgWords(stackArgBytes);
                if (winAbi) {
                    raw(isWindows() ? "    sub rsp, 32" : "    subq $32, %rsp");
                } else {
                    // Identical SysV "%al = vector registers used" rule as
                    // `emitAlignedCall`'s own non-win64 branch -- see that
                    // site's own doc comment for the bug this fixes. This
                    // path (a call with at least one real stack-passed
                    // argument) previously set %al to nothing at all, always
                    // leaving whatever value happened to already be in %eax
                    // -- worth fixing alongside the zero-stack-arg site
                    // rather than leaving this adjacent, same-class gap
                    // standing (this path is reached only via the AT&T-
                    // syntax `linux` target -- win64/MASM's own `isWindows()`
                    // branch is unconditionally `winAbi`, so it can never
                    // reach this `else`).
                    raw("    movl $" + consumeVarargsXmmCount() + ", %eax");
                }
                callBody.run();
                if (winAbi) {
                    raw(isWindows() ? "    add rsp, 32" : "    addq $32, %rsp");
                }
            } finally {
                argTrackSuspendDepth--;
            }
            emitStackArgRestore(stackArgBytes);
        } else {
            emitAlignedCall(callBody);
        }
    }

    private void emitAlignedCall(Runnable body) {
        // Suspended for this whole call (preamble, body, postamble alike)
        // -- see `argTrackSuspendDepth`'s own doc comment. Every
        // instruction below nets to exactly zero real stack effect by the
        // time this method returns, by construction; without this, the
        // preamble's own "sub rsp, 16"/"sub rsp, 32" would be tracked as
        // a real, surviving push (its own matching undo is an *absolute*
        // "mov rsp, ..." restore, a shape `trackStackDelta` deliberately
        // never recognizes as a relative move) and wrongly counted toward
        // an *enclosing* "CC_START"/"CALL" bracket's own stack-passed-
        // argument tally.
        argTrackSuspendDepth++;
        try {
            boolean winAbi = isWinAbi(); // shadow space vs SysV varargs %al -- an ABI question, independent of syntax
            if (isWindows()) {
                raw("    mov rax, rsp");
                raw("    and rsp, -16");
                raw("    sub rsp, 16"); // reserved slot for the pre-align rsp -- keeps 16-alignment (subtracting a multiple of 16)
                raw("    mov [rsp+8], rax");
                if (winAbi) {
                    raw("    sub rsp, 32"); // win64 shadow space
                }
                body.run();
                if (winAbi) {
                    raw("    add rsp, 32"); // undo shadow space -- rsp is back at the reserved slot
                }
                raw("    mov rsp, [rsp+8]"); // load the pre-align rsp straight into rsp -- also discards the reserved slot
            } else {
                raw("    movq %rsp, %rax");
                raw("    andq $-16, %rsp");
                raw("    subq $16, %rsp"); // reserved slot for the pre-align rsp -- keeps 16-alignment (subtracting a multiple of 16)
                raw("    movq %rax, 8(%rsp)");
                if (winAbi) {
                    raw("    subq $32, %rsp"); // win64 shadow space (WINDOWS_GNU_X64, AT&T syntax)
                } else {
                    // SysV varargs rule: %al must hold the number of vector
                    // (xmm) registers used for a varargs call (printf, most
                    // visibly). This used to be unconditionally zeroed here
                    // on the claim "this backend never passes float args in
                    // xmm registers at all yet" -- stale even at the time it
                    // was written (register-passed float arguments already
                    // went through "POP FARGn" into a real xmm register by
                    // then) and the root cause of a real, confirmed bug:
                    // printf("%f", floatValue) always printed 0.000000, even
                    // for one single, non-overflowing float argument with
                    // zero relation to varargs-argument-count overflow --
                    // glibc's own printf, trusting this (wrong) claim of
                    // "0 vector registers used," never even looks at %xmm0
                    // to find the real value. Fixed by reading the real,
                    // compile-time-known count the front end already
                    // computed for this exact call (`consumeVarargsXmmCount`
                    // -- see its own doc comment) instead of a hardcoded
                    // constant. Win64 has no equivalent convention at all,
                    // so this whole branch stays SysV-only.
                    raw("    movl $" + consumeVarargsXmmCount() + ", %eax");
                }
                body.run();
                if (winAbi) {
                    raw("    addq $32, %rsp"); // undo shadow space -- rsp is back at the reserved slot
                }
                raw("    movq 8(%rsp), %rsp"); // load the pre-align rsp straight into rsp -- also discards the reserved slot
            }
        } finally {
            argTrackSuspendDepth--;
        }
    }

    private void emitCallByName(String name) {
        xvSpillAll();
        raw(isWindows() ? ("    call " + name) : ("    call " + name));
        xvReloadAll();
    }

    private void emitCallIndirect(String reg64) {
        xvSpillAll();
        raw(isWindows() ? ("    call " + reg64) : ("    call *%" + reg64));
        xvReloadAll();
    }

    // ---- Main per-line dispatch --------------------------------------------

    private void emitLine(List<BytecodeToken> line) {
        if (line.isEmpty()) {
            return;
        }
        if (currentLineIndex <= asmSkipUntilIndex) {
            // Already copied verbatim by the "ASM_START" case below.
            return;
        }
        String first = line.get(0).text;

        // A bare label line, e.g. "@loop_1:" or "end_of_gt_routine__main:"
        // -- carried over as a real assembly label ("@" isn't valid in a
        // real label, so it's mangled to "L_"; a label with no "@" is
        // used unchanged, which is exactly what a JMP to the same text
        // must also do -- see mangleLabel).
        if (first.endsWith(":") && line.size() == 1) {
            raw(mangleLabel(first.substring(0, first.length() - 1)) + ":");
            return;
        }

        boolean wasCmp = lastWasCmp;
        lastWasCmp = false;

        // See "value-block" handling in PUSH/LOOKUP_ARRAY/DOT below:
        // captured and reset by default on every instruction, and only
        // restored where it needs to survive (an intervening small
        // PUSH, e.g. an index or a field offset).
        long pendingBlockSize = lastValueBlockSize;
        lastValueBlockSize = 0;

        String mnemonic = first;
        if (RF_R13_R14_USERS.contains(mnemonic)) {
            rfClobberSeen = true;
        }
        switch (mnemonic) {
            case "FUNC_START": {
                String name = line.get(1).text;
                currentFuncName = name;
                rfVarUsed = false;
                rfClobberSeen = false;
                xvHome.clear();
                xvWidth.clear();
                raw("");
                if (isWindows()) {
                    raw("PUBLIC " + name);
                    raw(name + " PROC");
                } else {
                    raw(".globl " + name);
                    raw(name + ":");
                }
                pushReg("rbp");
                movRegToRegRbpFromRsp();
                csrFuncStart = out.length();
                csrAllocPos = -1;
                csrAllocLine = null;
                return;
            }
            case "FUNC_END": {
                // A void function with no explicit "return" statement
                // never emits a "RET" line at all -- control just falls
                // straight through into this copy of the epilogue (see
                // RET's own doc comment for the case where a real RET
                // line *does* run first). RET's own "size 0" branch
                // explicitly zeroes %rax/%eax before handing off to
                // emitFunctionEpilogue precisely so a void function's
                // exit code is predictable rather than whatever garbage
                // its last real instruction happened to leave there --
                // but that zeroing lives on the RET line itself, so a
                // function that falls off the end without ever running
                // one skipped it entirely. Confirmed as a real, live bug
                // this way: a bare "func main() void { printf(...) }"
                // (no explicit return) exited with whatever count printf
                // itself returned (6, for a 6-character format string)
                // instead of 0, since printf's own return value was
                // still sitting in %rax when this fell through to here.
                // A non-void function can never reach this point live --
                // the type-checker requires every path to return a real
                // value -- so zeroing %rax here unconditionally is safe:
                // it only ever actually executes for a void fall-through,
                // and is otherwise this same harmless "dead code after an
                // already-emitted RET" this method's own doc comment
                // already describes.
                movImmToReg("rax", 0);
                emitFunctionEpilogue();
                if (rfVarUsed && rfClobberSeen) {
                    throw new RuntimeException("codegen internal error: '" + currentFuncName
                            + "' keeps variables in r13/r14 but also has an instruction that uses them as scratch");
                }
                finishCalleeSaved();
                if (isWindows()) {
                    raw(currentFuncName + " ENDP");
                }
                return;
            }
            case "ALLOC": {
                long size = Long.parseLong(line.get(1).text);
                long aligned = (size + 15) & ~15L;
                if (csrAllocPos < 0 && callBufferStack.isEmpty() && csrFuncStart >= 0) {
                    csrAllocPos = out.length();
                    csrAllocBytes = aligned;
                    csrAllocLine = isWindows() ? ("    sub rsp, " + aligned + "\n") : ("    subq $" + aligned + ", %rsp\n");
                }
                if (isWindows()) {
                    raw("    sub rsp, " + aligned);
                } else {
                    raw("    subq $" + aligned + ", %rsp");
                }
                return;
            }
            case "ADDR": {
                // "ADDR size $offset" -> push that address.
                // "ADDR size globalName" -> push that global's address.
                String operand = line.get(2).text;
                if (operand.startsWith("$")) {
                    leaMemToReg("rax", Long.parseLong(operand.substring(1)));
                } else {
                    leaGlobalToReg("rax", operand);
                }
                pushReg("rax");
                return;
            }
            case "PUSH": {
                if (line.size() < 3) {
                    // The 2-token "PUSH <bareName>" form -- a class/type
                    // tag operand (INSTANCEOF's own right-hand side, e.g.
                    // "PUSH Circle") rather than an ordinary "PUSH size
                    // operand" value push. INSTANCEOF itself is one of
                    // this pass's honest, documented gaps (see class doc
                    // comment and CLAUDE.md) -- the lowering pipeline
                    // never resolves this name to a runtime class tag, so
                    // there is nothing safe to push here either; flagged,
                    // not guessed at (this used to crash outright --
                    // "fix that bug," confirmed directly -- now it doesn't).
                    comment("TODO(codegen): PUSH (2-token form) '" + String.join(" ", textsOf(line)) + "' not yet implemented");
                    return;
                }
                int size = (int) Long.parseLong(line.get(1).text);
                String operand = line.get(2).text;
                if (operand.startsWith("$") || operand.startsWith("%x")) {
                    boolean fromXvar = operand.startsWith("%x"); // a float variable promoted to an xmm register (RegVarPromotionPass)
                    long offset = fromXvar ? 0 : Long.parseLong(operand.substring(1));
                    if (size <= 8) {
                        if (fromXvar) {
                            rfXmmToGpr(xvReg(operand), "rax", size == 8 ? 8 : 4); // movd/movq: the variable's bits, zero-extended -- what the frame load gave
                        } else {
                            loadSizedFromFrame("rax", offset, size);
                        }
                        storeConstructionOrPlainPush("rax", size);
                        if (pendingBlockSize == 0 && size < 8) {
                            // Nothing was already pending, and this value
                            // is narrower than a full 8-byte pointer --
                            // it can only be a whole small fixed array
                            // (`letters: mut char[3]`, say) pushed by
                            // value as a single, ordinary, natural-byte-
                            // order word (a pointer is always exactly 8
                            // bytes, never narrower), about to feed a
                            // following LOOKUP_ARRAY. Encoded as a
                            // *negative* size specifically so LOOKUP_ARRAY
                            // can tell this genuinely different shape
                            // apart from the multi-word block case just
                            // below (whose own reversed-word layout
                            // convention would extract the *wrong* byte
                            // entirely if applied here -- see that case's
                            // own doc comment). An ordinary, unrelated
                            // narrow scalar read harmlessly carries this
                            // same tag forward for exactly one
                            // instruction too, but this bytecode's own
                            // emission convention never actually follows
                            // one with a LOOKUP_ARRAY/DOT except as part
                            // of a real access chain, so nothing ever
                            // misreads it.
                            //
                            // A real, previously-undiscovered bug found
                            // this way: this shape used to leave
                            // `lastValueBlockSize` at 0 like any other
                            // ordinary scalar read, so the following
                            // LOOKUP_ARRAY fell into its own "no block
                            // pending" fallback -- built for an unsafe
                            // dynarray's own bare *pointer* value -- and
                            // treated this packed array value as if it
                            // were itself a memory address, corrupting it
                            // into a bogus pointer and dereferencing it.
                            // Confirmed as a real, live segfault via
                            // `struct_array_member_narrow_elem_cg_test`'s
                            // own `letters: mut char[3]` member.
                            lastValueBlockSize = -size;
                        } else {
                            // A small push right after a pushed value-block
                            // is always the index/field-offset that a
                            // following LOOKUP_ARRAY/PUSH_FIELDNAME+DOT
                            // consumes *alongside* that block -- preserve
                            // the pending block size across it rather than
                            // treating it as unrelated.
                            lastValueBlockSize = pendingBlockSize;
                        }
                    } else {
                        // A multi-word (>8 byte) value pushed by plain
                        // value -- a small struct passed/copied whole
                        // (e.g. "PUSH 24 $-64", "PUSH 48 $-200"), or an
                        // array pushed by its own full declared size for
                        // a following LOOKUP_ARRAY read (confirmed
                        // directly against array_lookup_cg_test.caspien's
                        // own real low-order output: a read
                        // ("PUSH 40 $-40 / PUSH idx / LOOKUP_ARRAY 8")
                        // pushes the *whole* array by value, while a
                        // write ("ADDR 40 $-40 / PUSH idx /
                        // LOOKUP_ARRAY_LHS 8") pushes only its address --
                        // two genuinely different conventions for the
                        // same LOOKUP_ARRAY family, not a codegen choice).
                        // Pushes ceil(size/8) whole words, lowest source
                        // offset first, so the highest-offset word ends
                        // up on top -- the same convention DEREF's own
                        // multi-word case and the "POP $addr size"
                        // block-store already share -- and remembers the
                        // total size for LOOKUP_ARRAY/DOT to extract a
                        // sub-range from afterward.
                        //
                        // UNLESS this exact line is itself a direct
                        // member of a construction run `precomputeConstructionOffsets`
                        // already resolved (`pushStoreOffsetByLine`) --
                        // i.e. a whole, already-fully-built value (e.g. a
                        // hidden RVO temp local) being fed, as one single
                        // >8-byte entry, straight into a following "NEW"/
                        // construction-scoped "ASSIGN". That terminator's
                        // own straight `rep movsb` copy (see "NEW"'s own
                        // doc comment) assumes the bytes sitting at the
                        // top of the stack are already in the same,
                        // natural, ascending-offset order the source
                        // struct/array itself has -- true for an ordinary
                        // multi-field literal (each field individually
                        // reserves and writes only its own declared
                        // width, via `storeConstructionOrPlainPush`,
                        // directly at its own natural position), but
                        // false for the reversed, "highest-offset word on
                        // top" order this branch's own ordinary path
                        // produces. Using the ordinary reversed path here
                        // silently swapped a struct's own front and back
                        // words end for end once copied into the final
                        // buffer -- confirmed as a real, live bug via
                        // `new` constructing a value already built by a
                        // separate call (a struct's own constructor,
                        // called via its stack-RVO destination pointer,
                        // then handed to `new` as one already-complete
                        // value) rather than via an inline literal:
                        // `new MyClass(11,13)`'s own `y` field silently
                        // read back as `1` (the struct's own hidden
                        // classId, its front-most word) instead of `13`.
                        // `pushBlockFromFrameConstructionAware` below
                        // keeps this entry's own bytes in their real,
                        // natural order and lands them at the exact
                        // packed offset `populateDirectMembers` already
                        // computed for it (0, unconditionally, whenever
                        // this is the run's *only* entry -- a whole-value
                        // feed like this always is one, since
                        // `collectRunEntries` only ever splits a value
                        // into more than one entry at real, separate
                        // "PUSH"/"ATOMIC_PUSH"/"STACK_LOCK" lines, and a
                        // single already-built value is always exactly
                        // one line), so the terminator's own straight,
                        // order-preserving copy sees exactly the bytes it
                        // already assumes.
                        pushBlockFromFrameConstructionAware(offset, size);
                        lastValueBlockSize = size;
                    }
                    return;
                } else if (operand.equals("null")) {
                    movImmToReg("rax", 0);
                } else if (isInteger(operand)) {
                    movImmToReg("rax", operand.startsWith("-") ? Long.parseLong(operand) : Long.parseUnsignedLong(operand));
                } else if (isFloatLiteral(operand)) {
                    // A float literal's own IEEE-754 bit pattern is just
                    // as pushable as an int's bits through this exact
                    // same integer-immediate/pushq mechanism -- this
                    // instruction (and the "ASSIGN"/frame-load machinery
                    // downstream of it) already treats every value as
                    // opaque bytes of the declared `size`, never
                    // interpreting them, so there is nothing SSE/xmm-
                    // specific needed just to get a literal's own bits
                    // to sit correctly in memory. The real xmm-register
                    // work this doesn't touch -- float *arithmetic*
                    // (ADD_FLOAT/SUB_FLOAT/MUL_FLOAT/DIV_FLOAT), float
                    // *comparison* (EQ_FLOAT/NEQ_FLOAT/LT_FLOAT/...),
                    // returning a float (RET_FLOAT), and FLOAT_CHECK
                    // (the finite/infinite/nan classification match
                    // lowers to) -- is all implemented elsewhere in this
                    // same file; none of it falls through to the generic
                    // TODO any more (verified directly: a real program
                    // exercising all three FLOAT_CHECK categories
                    // together -- a finite value, 1.0/0.0, and 0.0/0.0
                    // -- compiles with zero TODOs in the generated
                    // assembly and correctly prints "finite"/"infinite"/
                    // "nan").
                    long bits = size <= 4
                            ? Integer.toUnsignedLong(Float.floatToRawIntBits(Float.parseFloat(operand)))
                            : Double.doubleToRawLongBits(Double.parseDouble(operand));
                    movImmToReg("rax", bits);
                } else if (operand.matches("\\d+\\.(start|end)")) {
                    // A synthesized pseudo-member push against an
                    // *incomplete* range value ("5.start"/"5.end") --
                    // part of the same WITHIN/incomplete-range gap this
                    // pass already documents elsewhere (the lowering
                    // pipeline leaves this operand as a symbolic,
                    // non-address form even at "low order"); flagged
                    // rather than mismangled into a bogus symbol.
                    comment("TODO(codegen): PUSH of incomplete-range pseudo-member '" + operand + "' not yet implemented -- pushing 0 as a placeholder");
                    movImmToReg("rax", 0);
                } else if (operand.startsWith("ARG")) {
                    // A calling-convention register-transfer slot read
                    // directly as a value -- the callee side of the same
                    // convention "POP ARGn" uses on the caller side.
                    // Confirmed directly: every real site is the very
                    // first instruction touching registers right after
                    // FUNC_START/ALLOC (storing an incoming parameter to
                    // its own local), so the argument register still
                    // holds its true incoming value here untouched.
                    int idx = Integer.parseInt(operand.substring(3));
                    raw(isWindows() ? ("    mov rax, " + argReg(idx)) : ("    movq %" + argReg(idx) + ", %rax"));
                } else if (operand.startsWith("FARG")) {
                    // The float-bank counterpart of the "ARG" branch just
                    // above -- the incoming parameter's own real bits are
                    // already sitting in a real xmm register at function
                    // entry (the calling convention's own float-argument
                    // slot), never a GPR, so this moves the raw 64 bits
                    // out of it via the same bit-copying `movq` every
                    // other float op in this file already uses, rather
                    // than reading a GPR that was never written.
                    int idx = Integer.parseInt(operand.substring(4));
                    movXmmToReg(argFloatReg(idx), "rax");
                } else if (stringLiterals.containsKey(operand)) {
                    // A string-pool id -- always its address (a string
                    // literal decays to a pointer to its own .rodata
                    // bytes, exactly like a real array/struct global;
                    // there's no separate "value" to push instead,
                    // confirmed directly against every real string-
                    // literal PUSH site, e.g. printf's format-string
                    // argument and NEW_FROM_STRING's source operand).
                    leaGlobalToReg("rax", operand);
                } else if (globalsSizeInit.containsKey(operand) || globalAliasOffset.containsKey(operand)) {
                    // A bare (possibly dotted) declared GLOBAL/
                    // ALLOC_STATIC name -- ordinarily its *value* (the
                    // same "read what's stored there" semantics a local
                    // variable's own "PUSH size $offset" already has,
                    // and the same thing ATOMIC_PUSH does explicitly,
                    // atomically, for the identical bare-global-name
                    // case) -- confirmed directly, the hard way: this
                    // pass originally treated every bare global name as
                    // "push its address" across the board, which
                    // happened to work for the ghost-table lock (see
                    // below) but silently broke gt_destruct's own
                    // ghost-table *search* the first time a real test
                    // actually exercised it (ghost_table.len's real
                    // stored value is 0, but its *address* is some large
                    // nonzero number, so the address reading turned an
                    // always-skipped search loop into one that ran with
                    // garbage bounds and eventually dereferenced garbage
                    // -- a real, confusing segfault this pass had to
                    // bisect down to before finding the true convention
                    // split here). The one confirmed exception is a
                    // global immediately feeding an ATOMIC_SWAP two
                    // lines ahead (this pipeline's own lock-acquire
                    // shape, "PUSH lockGlobal / PUSH newValue /
                    // ATOMIC_SWAP" -- an in-place memory swap that
                    // genuinely needs the address, not the value, and is
                    // the one bare-global PUSH site this pass actually
                    // confirmed wants that) -- everywhere else (ASSIGN,
                    // arithmetic, comparisons, ARG passing) it's the
                    // value.
                    if (lineAtOffsetIsAtomicSwap(2)) {
                        leaGlobalToReg("rax", operand);
                    } else if (size > 8) {
                        // A multi-word global (a static array, most often,
                        // about to feed LOOKUP_ARRAY) is pushed as a whole
                        // value block -- the identical shape "PUSH size
                        // $offset" produces for a local of that size. Before,
                        // this fell to the single-word load below, so only
                        // the array's first 8 bytes were pushed and
                        // LOOKUP_ARRAY indexed into (and dereferenced) that
                        // word as if it were the array: reading any element
                        // of a static array segfaulted.
                        leaGlobalToReg("rax", operand);
                        pushSizedOrBlockFromAddr("rax", size);
                        lastValueBlockSize = size;
                        return;
                    } else {
                        leaGlobalToReg("rax", operand);
                        loadSizedFromAddr("rax", "rax", size);
                        if (pendingBlockSize == 0 && size < 8) {
                            // Same tag as the local "PUSH size $offset" case:
                            // a narrow value may be a whole small static array
                            // about to feed LOOKUP_ARRAY (see that case).
                            lastValueBlockSize = -size;
                            storeConstructionOrPlainPush("rax", size);
                            return;
                        }
                    }
                } else if (declaredFunctionNames.contains(operand)) {
                    // A real function's own address -- see
                    // "declaredFunctionNames"'s own doc comment. Never
                    // dereferenced afterward, unlike a global: the value
                    // *is* the address.
                    leaGlobalToReg("rax", operand);
                } else if (isCharLiteral(operand)) {
                    // A char literal -- "'a'", "'\n'", "'\0'", ... --
                    // BytecodeEmitter.escapeForBytecode's own serialized
                    // form: a single quote, one real character's worth of
                    // content (either one printable byte, or a two-
                    // character "\x"-style escape for anything this
                    // language's own lexer recognizes as an escape --
                    // "\\"/"\""/"\n"/"\t"/"\r"/"\0"/"\b"/"\f"/"\a"/"\v"),
                    // then a closing single quote. A real, previously-
                    // undiscovered gap: this operand shape had no branch
                    // here at all -- every char literal anywhere in the
                    // whole language silently fell to the generic
                    // "not a declared global" placeholder below, pushing
                    // 0 in place of the real character, every time,
                    // regardless of context (a bare `let c = 'a'`, an
                    // array-of-char literal, a match/comparison against a
                    // char, ...). Confirmed directly, not assumed: 22
                    // distinct example fixtures across the whole test
                    // corpus hit this, including three that had been
                    // separately tracked all along as unrelated,
                    // "pre-existing baseline failures"
                    // (array_literal_narrow_elem_cg_test,
                    // struct_array_member_narrow_elem_cg_test,
                    // struct_mixed_width_cg_test) -- all three were
                    // actually this one bug the whole time. Decoded the
                    // same way an integer/float literal already is just
                    // above: to its real numeric byte value, immediate-
                    // loaded into %rax, no runtime work needed (a char is
                    // always exactly one compile-time-known byte).
                    movImmToReg("rax", decodeCharLiteral(line.get(2)));
                } else {
                    comment("TODO(codegen): PUSH of '" + operand + "' (not a declared global) not yet implemented -- pushing 0 as a placeholder");
                    movImmToReg("rax", 0);
                }
                // Any of these forms (a literal index, "null", an ARG
                // read, a global address) can legitimately be the
                // index/offset half of a LOOKUP_ARRAY/DOT chain, so a
                // pending value-block from just before is preserved
                // through it, same as the small "$offset" branch above.
                lastValueBlockSize = pendingBlockSize;
                storeConstructionOrPlainPush("rax", size);
                return;
            }
            case "ASSIGN":
            case "ATOMIC_ASSIGN": {
                // Stack, top to bottom: value, address. size operands
                // (3 of them, always equal) tell us the real width to
                // store -- previously ignored (always did a full 8-byte
                // store, which is wrong for anything narrower, e.g. a
                // struct's own 'bool'/'u8'/'f32' field sitting between
                // other fields). A plain aligned store is already atomic
                // on x86-64, so ATOMIC_ASSIGN needs no different
                // instruction, just the same width-aware store.
                int size = (int) Long.parseLong(line.get(1).text);
                if (constructionScopedAssign.contains(currentLineIndex)) {
                    // A plain, stack-resident struct/array/primitive
                    // literal's own top-level store -- see
                    // `constructionScopedAssign`'s own doc comment. %rsp
                    // already holds a real, byte-exact, tightly-packed
                    // image of the value (every push in the run just
                    // reserved its own declared width, never a blind
                    // 8-byte word), exactly the same "genuinely trivial
                    // and completely struct-agnostic" copy `case "NEW"`
                    // already does for the identical shape -- just to an
                    // address already sitting on the stack (below the
                    // value block) rather than a freshly malloc'd one, so
                    // no allocation call is needed here at all. Checked
                    // *before* the `size <= 8` case below: a small (say,
                    // 4-byte, four-`char`) construction is exactly as
                    // real a construction as a large one -- its value
                    // block was reserved byte-exact too, not as one
                    // ordinary 8-byte word, so the plain scalar pop/pop
                    // path below would desynchronize with it (popping 8
                    // bytes of a 4-byte-reserved value block spills into
                    // the address word sitting right above it).
                    assignConstructionBlock(size);
                } else if (line.size() > 4) {
                    // A stack struct literal whose field values were not all plain pushes (an expression, a call, ...): every
                    // field was pushed as a whole 8-byte word, so the block-copy paths below would misread the stack. The layout
                    // descriptor LowerOrderGenerator appended tells us where each field really goes.
                    emitAssignRepack(size, line);
                } else if (size <= 8) {
                    popReg("rax"); // value
                    popReg("rbx"); // address
                    storeSizedToAddr("rax", "rbx", size);
                } else {
                    // Not a recognized construction -- an ordinary
                    // whole-value block copy (`let b = a`, passing a
                    // struct/array by value, ...), whose own pushed image
                    // came from `pushBlockFromFrame`'s uniform, always-
                    // whole-8-byte-word convention instead. Handled the
                    // old way, popping one word at a time.
                    assignBlock(size);
                }
                return;
            }
            case "R_MOV": {
                // Register-form (see the LowerOrderGenerator's RegisterFormPass): "R_MOV 8 dst src".
                rfMov(line.get(2).text, line.get(3).text);
                return;
            }
            case "R_BIN": {
                // "R_BIN OP size %tD a b" -- %tD = a OP b.
                rfBin(line.get(1).text, (int) Long.parseLong(line.get(2).text), line.get(3).text, line.get(4).text, line.get(5).text);
                return;
            }
            case "R_UN": {
                // "R_UN OP size %tD a" -- %tD = OP a.
                rfUn(line.get(1).text, (int) Long.parseLong(line.get(2).text), line.get(3).text, line.get(4).text);
                return;
            }
            case "R_BRF": {
                // "R_BRF %tN @label" -- jump if the temp is zero (replaces CMP + JMP).
                String reg = rfReg(line.get(1).text);
                raw(isWindows() ? ("    test " + reg + ", " + reg) : ("    testq %" + reg + ", %" + reg));
                raw("    je " + mangleLabel(line.get(2).text));
                return;
            }
            case "R_BRC": {
                // "R_BRC OP 8 a b @label" -- compare a with b and jump to the label when NOT (a OP b) (the fused form of
                // R_BIN cmp + R_BRF, made by the LowerOrderGenerator's BranchFusionPass; the compare result never exists).
                rfBrc(line.get(1).text, line.get(3).text, line.get(4).text, mangleLabel(line.get(5).text));
                return;
            }
            case "R_PUSH": {
                // "R_PUSH 8 src" -- materialise an operand (%tN, $off or #imm) on the real stack. Goes through
                // pushReg so the stack-delta tracker sees it; memory/immediate sources use the spare scratch.
                String src = line.get(2).text;
                if (rfIsTemp(src)) {
                    pushReg(rfReg(src));
                } else {
                    rfMov("%s", src);
                    pushReg(RF_SCRATCH);
                }
                return;
            }
            case "R_PUSHA": {
                // "R_PUSHA 8 $off" / "R_PUSHA 8 &sym" -- push the address of a frame slot / a global.
                rfAddrToReg(RF_SCRATCH, line.get(2).text);
                pushReg(RF_SCRATCH);
                return;
            }
            case "R_SETV": {
                // "R_SETV n %vK src" -- n bytes of src (#imm, %tN or %vN) into a promoted variable, zero-extended to 64 bits.
                int n = (int) Long.parseLong(line.get(1).text);
                String vr = rfReg(line.get(2).text);
                String src = line.get(3).text;
                if (rfIsImm(src)) {
                    movImmToReg(vr, rfExtImm(rfImm(src), n, false));
                } else {
                    rfRegToReg(vr, rfReg(src));
                    if (n < 8) {
                        zeroExtendReg(vr, n);
                    }
                }
                return;
            }
            case "R_LD": {
                // "R_LD n %tD addr" -- %tD = n bytes at addr (a frame slot, a global or the pointer in a temp), zero-extended.
                rfLoad((int) Long.parseLong(line.get(1).text), line.get(2).text, line.get(3).text);
                return;
            }
            case "R_ST": {
                // "R_ST n addr src" -- n bytes at addr = src (#imm or %tN).
                rfStore((int) Long.parseLong(line.get(1).text), line.get(2).text, line.get(3).text);
                return;
            }
            case "R_LDI": {
                // "R_LDI n %tD &sym %vK scale" -- %tD = n bytes at sym + vK*scale (an R_LEA folded into the load that used it).
                rfLoadIndexed((int) Long.parseLong(line.get(1).text), line.get(2).text, line.get(3).text, line.get(4).text, Long.parseLong(line.get(5).text));
                return;
            }
            case "R_STI": {
                // "R_STI n &sym %vK scale src" -- n bytes at sym + vK*scale = src (#imm or %tN).
                rfStoreIndexed((int) Long.parseLong(line.get(1).text), line.get(2).text, line.get(3).text, Long.parseLong(line.get(4).text), line.get(5).text);
                return;
            }
            case "R_LDXI": {
                // "R_LDXI n %xK &sym %vK scale" -- variable K = the float (n = 4 or 8 bytes) at sym + vK*scale (an R_LEA folded into R_LDX).
                rfLoadXIndexed(Integer.parseInt(line.get(1).text), line.get(2).text, line.get(3).text, line.get(4).text, Long.parseLong(line.get(5).text));
                return;
            }
            case "R_STXI": {
                // "R_STXI n &sym %vK scale %xK" -- the float in variable K stored at sym + vK*scale (an R_LEA folded into R_STX).
                rfStoreXIndexed(Integer.parseInt(line.get(1).text), line.get(2).text, line.get(3).text, Long.parseLong(line.get(4).text), line.get(5).text);
                return;
            }
            case "R_LEA": {
                // "R_LEA %tD base idx scale [disp]" -- %tD = base + idx*scale + disp.
                rfLea(line.get(1).text, line.get(2).text, line.get(3).text, Long.parseLong(line.get(4).text),
                        line.size() > 5 ? Long.parseLong(line.get(5).text) : 0L);
                return;
            }
            case "R_FBIN":
            case "R_FCMP": {
                // "R_FBIN OP size %tD a b" / "R_FCMP OP size %tD a b" -- scalar float arithmetic / compare on raw bit patterns.
                rfFloat(mnemonic.equals("R_FCMP"), line.get(1).text, (int) Long.parseLong(line.get(2).text), line.get(3).text, line.get(4).text, line.get(5).text);
                return;
            }
            case "R_XVAR": {
                // "R_XVAR %xK $off [n]" -- declaration only (no code): float variable K lives in an xmm register; $off is its
                // home frame slot, used to spill/reload around calls where the ABI does not preserve xmm registers; n is the
                // variable's width (4 = f32, the default; 8 = f64).
                int xk = Integer.parseInt(line.get(1).text.substring(2));
                xvHome.put(xk, rfSlot(line.get(2).text));
                xvWidth.put(xk, line.size() > 3 ? Integer.parseInt(line.get(3).text) : 4);
                return;
            }
            case "R_XMOV": {
                // "R_XMOV %xK|%yK %xJ|%yJ" -- register copy of a float value (FloatTempPass).
                rfMovaps(xvReg(line.get(1).text), xvReg(line.get(2).text));
                return;
            }
            case "R_XRELOAD": {
                // reload every float variable from its home slot: emitted where control can arrive from an unwind (catch entry),
                // after which the register-held copies are gone on ABIs whose xmm registers do not survive a call.
                xvReloadAll();
                return;
            }
            case "R_XTOG": {
                // "R_XTOG n %tD %xK" -- %tD = the n*8 float bits of variable K, zero-extended.
                rfXmmToGpr(xvReg(line.get(3).text), rfReg(line.get(2).text), Integer.parseInt(line.get(1).text));
                return;
            }
            case "R_GTOX": {
                // "R_GTOX n %xK src" -- variable K = the float bits in src (%tN or #imm).
                int gn = Integer.parseInt(line.get(1).text);
                String src = line.get(3).text;
                if (rfIsImm(src)) {
                    if (floatPoolOn()) {
                        rfLoadXmm(src, xvReg(line.get(2).text), gn);
                    } else {
                        movImmToReg(RF_SCRATCH, rfImm(src));
                        rfGprToXmm(xvReg(line.get(2).text), RF_SCRATCH, gn);
                    }
                } else {
                    rfGprToXmm(xvReg(line.get(2).text), rfReg(src), gn);
                }
                return;
            }
            case "R_LDX": {
                // "R_LDX n %xK addr" -- variable K = the float at addr (a frame slot, a global or the pointer in a temp).
                rfLoadX(xvReg(line.get(2).text), line.get(3).text, Integer.parseInt(line.get(1).text));
                return;
            }
            case "R_STX": {
                // "R_STX n addr %xK" -- the float in variable K stored at addr.
                rfStoreX(line.get(2).text, xvReg(line.get(3).text), Integer.parseInt(line.get(1).text));
                return;
            }
            case "R_FBINX": {
                // "R_FBINX OP 4 %xK a b" -- variable K = a OP b, computed in place when it can be.
                rfFloatX(line.get(1).text, (int) Long.parseLong(line.get(2).text), line.get(3).text, line.get(4).text, line.get(5).text);
                return;
            }
            case "R_POPX": {
                // "R_POPX n %xK" -- variable K = the float word on top of the real stack (what ASSIGN n n n stored through its address).
                popReg("rax");
                rfGprToXmm(xvReg(line.get(2).text), "rax", Integer.parseInt(line.get(1).text));
                return;
            }
            case "R_GETRETF": {
                // "R_GETRETF 4 %xK" -- variable K = the float a just-finished call returned (what PUSH_RET_FLOAT + ASSIGN did).
                String xk = xvReg(line.get(2).text);
                if (floatResultInR10) {
                    rfGprToXmm(xk, "r10", 8);
                    floatResultInR10 = false;
                } else {
                    raw(isWindows() ? ("    movaps " + xk + ", xmm0") : ("    movaps %xmm0, %" + xk));
                }
                return;
            }
            case "R_RMW": {
                // "R_RMW OP 8 $x [b]" -- one memory-operand read-modify-write on an 8-byte frame slot.
                rfRmw(line.get(1).text, line.get(3).text, line.size() > 4 ? line.get(4).text : null);
                return;
            }
            case "R_ARG": {
                // "R_ARG n size src" -- integer argument register n = src (what "PUSH src / POP ARGn" did).
                String reg = rfArgReg(Integer.parseInt(line.get(1).text));
                int asize = (int) Long.parseLong(line.get(2).text);
                String src = line.get(3).text;
                if (rfIsXvar(src)) {
                    rfXmmToGpr(xvReg(src), reg, asize == 8 ? 8 : 4);
                } else if (rfIsTemp(src)) {
                    rfRegToReg(reg, rfReg(src));
                } else if (rfIsImm(src)) {
                    movImmToReg(reg, rfImm(src));
                } else {
                    loadSizedFromFrame(reg, rfSlot(src), asize);
                }
                noteArgRegLoaded("i" + Integer.parseInt(line.get(1).text));
                return;
            }
            case "R_ARGA": {
                // "R_ARGA n addr" -- integer argument register n = an address ($off or &sym).
                String reg = rfArgReg(Integer.parseInt(line.get(1).text));
                rfAddrToReg(reg, line.get(2).text);
                noteArgRegLoaded("i" + Integer.parseInt(line.get(1).text));
                return;
            }
            case "R_FARG": {
                // "R_FARG n size src" -- float argument register n = the bit pattern src.
                int fidx = Integer.parseInt(line.get(1).text);
                String xr = argFloatReg(fidx);
                int fsize = (int) Long.parseLong(line.get(2).text);
                String src = line.get(3).text;
                if (rfIsXvar(src)) {
                    raw(isWindows() ? ("    movaps " + xr + ", " + xvReg(src)) : ("    movaps %" + xvReg(src) + ", %" + xr));
                } else if (rfIsTemp(src)) {
                    movRegToXmm(rfReg(src), xr);
                } else {
                    if (rfIsImm(src)) {
                        movImmToReg(RF_SCRATCH, rfImm(src));
                    } else {
                        loadSizedFromFrame(RF_SCRATCH, rfSlot(src), fsize);
                    }
                    movRegToXmm(RF_SCRATCH, xr);
                }
                noteArgRegLoaded("f" + fidx);
                return;
            }
            case "R_RET":
            case "R_RETF": {
                // "R_RET src" / "R_RETF src" -- the return value into rax / xmm0, then the function epilogue.
                String src = line.get(1).text;
                if (mnemonic.equals("R_RET")) {
                    if (rfIsTemp(src)) {
                        rfRegToReg("rax", rfReg(src));
                    } else if (rfIsImm(src)) {
                        movImmToReg("rax", rfImm(src));
                    } else {
                        movMemToReg("rax", rfSlot(src));
                    }
                } else if (rfIsXvar(src)) {
                    raw(isWindows() ? ("    movaps xmm0, " + xvReg(src)) : ("    movaps %" + xvReg(src) + ", %xmm0"));
                } else if (rfIsTemp(src)) {
                    movRegToXmm(rfReg(src), "xmm0");
                } else {
                    if (rfIsImm(src)) {
                        movImmToReg(RF_SCRATCH, rfImm(src));
                    } else {
                        movMemToReg(RF_SCRATCH, rfSlot(src));
                    }
                    movRegToXmm(RF_SCRATCH, "xmm0");
                }
                emitFunctionEpilogue();
                return;
            }
            case "ADD_INT":
            case "SUB_INT":
            case "MUL_INT": {
                popReg("rbx");
                popReg("rax");
                String op = mnemonic.equals("ADD_INT") ? "add" : mnemonic.equals("SUB_INT") ? "sub" : "imul";
                if (isWindows()) {
                    raw("    " + op + " rax, rbx");
                } else {
                    raw("    " + op + "q %rbx, %rax");
                }
                pushReg("rax");
                return;
            }
            case "SDIV_INT":
            case "SMOD_INT": {
                // Signed division/modulo (s8/s16/s32/s64 operands): sign-extend both operands from
                // their declared width, then cqto/idiv (remainder takes the dividend's sign, as in C).
                int size = (int) Long.parseLong(line.get(1).text);
                popReg("rbx");
                popReg("rax");
                signExtendReg("rax", size);
                signExtendReg("rbx", size);
                // MIN / -1 overflows and traps (SIGFPE) in idiv: a divisor of -1 is handled
                // without idiv (x / -1 = -x wrapping, x % -1 = 0).
                boolean isDiv = mnemonic.equals("SDIV_INT");
                String notMinusOne = newInternalLabel("sdiv_normal");
                String doneLabel = newInternalLabel("sdiv_done");
                if (isWindows()) {
                    raw("    cmp rbx, -1");
                    raw("    jne " + notMinusOne);
                    if (isDiv) {
                        raw("    neg rax");
                    } else {
                        raw("    xor rax, rax");
                    }
                    raw("    jmp " + doneLabel);
                    raw(notMinusOne + ":");
                    raw("    cqo");
                    raw("    idiv rbx");
                    if (!isDiv) {
                        raw("    mov rax, rdx");
                    }
                    raw(doneLabel + ":");
                } else {
                    raw("    cmpq $-1, %rbx");
                    raw("    jne " + notMinusOne);
                    if (isDiv) {
                        raw("    negq %rax");
                    } else {
                        raw("    xorq %rax, %rax");
                    }
                    raw("    jmp " + doneLabel);
                    raw(notMinusOne + ":");
                    raw("    cqto");
                    raw("    idivq %rbx");
                    if (!isDiv) {
                        raw("    movq %rdx, %rax");
                    }
                    raw(doneLabel + ":");
                }
                pushReg("rax");
                return;
            }
            case "SLT_INT":
            case "SLT_EQ_INT":
            case "SGT_EQ_INT":
            case "SGT_INT": {
                // Signed comparisons: sign-extend both operands from their declared width first.
                int size = (int) Long.parseLong(line.get(1).text);
                popReg("rbx");
                popReg("rax");
                signExtendReg("rax", size);
                signExtendReg("rbx", size);
                String scc = mnemonic.equals("SLT_INT") ? "setl"
                        : mnemonic.equals("SLT_EQ_INT") ? "setle"
                        : mnemonic.equals("SGT_EQ_INT") ? "setge"
                        : "setg";
                if (isWindows()) {
                    raw("    cmp rax, rbx");
                    raw("    " + scc + " al");
                    raw("    movzx rax, al");
                } else {
                    raw("    cmpq %rbx, %rax");
                    raw("    " + scc + " %al");
                    raw("    movzbq %al, %rax");
                }
                pushReg("rax");
                return;
            }
            case "DIV_INT":
            case "MOD_INT": {
                // Unsigned division -- this bytecode carries only a byte
                // size per operator, never a signedness flag, and this
                // language's own numeric literals default to unsigned
                // ("indeterminate_u64") throughout -- so, like LT_INT/
                // LT_EQ_INT/GT_EQ_INT below, this pass treats every
                // int comparison/division as unsigned. A real signed
                // s8/s16/s32/s64 division would need idiv/cqto instead;
                // documented as a known gap rather than silently wrong
                // for the (much more common, in this language) unsigned
                // case.
                int dsize = line.size() > 1 ? (int) Long.parseLong(line.get(1).text) : 8;
                popReg("rbx");
                popReg("rax");
                zeroExtendReg("rax", dsize);
                zeroExtendReg("rbx", dsize);
                if (isWindows()) {
                    raw("    xor rdx, rdx");
                    raw("    div rbx");
                } else {
                    raw("    xorq %rdx, %rdx");
                    raw("    divq %rbx");
                }
                pushReg(mnemonic.equals("DIV_INT") ? "rax" : "rdx");
                return;
            }
            case "SHL":
            case "SHR":
            case "SAR": {
                // Shifts have ONE well-defined meaning at every width, on both targets and in both forms (the register-form
                // twin is rfShift): the count is read as an UNSIGNED number of the operand's own width (a negative signed
                // count is therefore huge); a count >= the operand width gives 0 for SHL and SHR and the sign fill (0 or
                // -1) for SAR -- not x86's "count modulo 64". SAR = arithmetic shift right for signed operands (the value is
                // sign-extended from its width first); SHR zero-extends first. A narrow result is re-truncated, so the stack
                // word keeps holding the zero-extended value.
                int ssize = line.size() > 1 ? (int) Long.parseLong(line.get(1).text) : 8;
                popReg("rbx"); // shift count
                popReg("rax"); // value
                // %rcx is the shift-count register but also an argument register: save it around the shift (see LOOKUP_ARRAY's small-array path).
                pushReg("rcx");
                raw(isWindows() ? "    mov rcx, rbx" : "    movq %rbx, %rcx");
                zeroExtendReg("rcx", ssize);
                emitShiftCore(mnemonic, ssize, "rax");
                popReg("rcx");
                pushReg("rax");
                return;
            }
            case "BITS_OR":
            case "BITS_AND":
            case "BITS_XOR": {
                // "BITS_xxx size": pop two, and/or/xor, push. Size 8 is also what StrengthReductionPass makes of x % 2^n. A narrow
                // result is re-truncated, so the stack word keeps holding the zero-extended value (like the shifts).
                int bsize = line.size() > 1 ? (int) Long.parseLong(line.get(1).text) : 8;
                popReg("rbx");
                popReg("rax");
                String bop = mnemonic.equals("BITS_OR") ? "or" : mnemonic.equals("BITS_AND") ? "and" : "xor";
                raw(isWindows() ? ("    " + bop + " rax, rbx") : ("    " + bop + "q %rbx, %rax"));
                zeroExtendReg("rax", bsize);
                pushReg("rax");
                return;
            }
            case "BITS_NOT": {
                // "BITS_NOT size": the bitwise complement, truncated to the operand width (u8: ~0 = 255).
                int nsize = line.size() > 1 ? (int) Long.parseLong(line.get(1).text) : 8;
                popReg("rax");
                raw(isWindows() ? "    not rax" : "    notq %rax");
                zeroExtendReg("rax", nsize);
                pushReg("rax");
                return;
            }
            case "EQ_INT":
            case "NEQ_INT":
            case "LT_INT":
            case "LT_EQ_INT":
            case "GT_EQ_INT":
            case "GT_INT": {
                // "GT_INT" found genuinely missing here while building
                // float support -- the sibling `caspien-lowerordergenerator`
                // project's own AddressLoweringPass rewrites all six
                // comparison operators (GT/LT/GT_EQ/LT_EQ/EQ/NEQ) through
                // one identical rule (see its own doc comment on that
                // rewrite), so every one of them was always just as
                // reachable as GT_EQ_INT, right below it, which *was*
                // handled -- this one case was simply never added.
                // Unrelated to anything float-specific; fixed alongside it
                // rather than left now that it's been noticed, same
                // unsigned-only scope as its five siblings (see DIV_INT's
                // own note).
                int csize = line.size() > 1 ? (int) Long.parseLong(line.get(1).text) : 8;
                popReg("rbx");
                popReg("rax");
                zeroExtendReg("rax", csize);
                zeroExtendReg("rbx", csize);
                String setcc = mnemonic.equals("EQ_INT") ? "sete"
                        : mnemonic.equals("NEQ_INT") ? "setne"
                        : mnemonic.equals("LT_INT") ? "setb"
                        : mnemonic.equals("LT_EQ_INT") ? "setbe"
                        : mnemonic.equals("GT_EQ_INT") ? "setae"
                        : "seta"; // GT_INT (unsigned; see DIV_INT's own note on signedness)
                if (isWindows()) {
                    raw("    cmp rax, rbx");
                    raw("    " + setcc + " al");
                    raw("    movzx rax, al");
                } else {
                    raw("    cmpq %rbx, %rax");
                    raw("    " + setcc + " %al");
                    raw("    movzbq %al, %rax");
                }
                pushReg("rax");
                return;
            }
            case "ADD_FLOAT":
            case "SUB_FLOAT":
            case "MUL_FLOAT":
            case "DIV_FLOAT": {
                int size = (int) Long.parseLong(line.get(1).text);
                String sfx = sseSuffix(size);
                popReg("rbx"); // right operand bits
                popReg("rax"); // left operand bits
                movRegToXmm("rax", rfXmmA());
                movRegToXmm("rbx", rfXmmB());
                String op = mnemonic.equals("ADD_FLOAT") ? "add"
                        : mnemonic.equals("SUB_FLOAT") ? "sub"
                        : mnemonic.equals("MUL_FLOAT") ? "mul"
                        : "div";
                // AT&T "op src, dest" computes dest = dest OP src; xmm0
                // holds the left operand (pushed first, popped last,
                // exactly the ADD_INT/SUB_INT/MUL_INT convention just
                // above), xmm1 the right, so "opss %xmm1, %xmm0" gives
                // left-OP-right, in the correct order for the
                // non-commutative SUB_FLOAT/DIV_FLOAT cases.
                raw(isWindows() ? ("    " + op + sfx + " " + rfXmmA() + ", " + rfXmmB()) : ("    " + op + sfx + " %" + rfXmmB() + ", %" + rfXmmA()));
                movXmmToReg(rfXmmA(), "rax");
                pushReg("rax");
                return;
            }
            case "EQ_FLOAT":
            case "NEQ_FLOAT":
            case "LT_FLOAT":
            case "LT_EQ_FLOAT":
            case "GT_EQ_FLOAT":
            case "GT_FLOAT": {
                // Same unordered-compare caveat DIV_INT/MOD_INT's own note
                // flags for unsigned-only integer division: `ucomiss` sets
                // CF=ZF=PF=1 whenever either operand is NaN ("unordered"),
                // so a NaN compared with anything reads as both "less than"
                // and "equal to" it here, rather than IEEE-754's own rule
                // that every ordered comparison against NaN is false. This
                // mirrors the file's existing precedent for a scoped,
                // documented gap rather than a silent one: correct for
                // every ordinary (non-NaN) float comparison, not yet
                // NaN-aware.
                int size = (int) Long.parseLong(line.get(1).text);
                String cmp = size <= 4 ? "ucomiss" : "ucomisd";
                popReg("rbx");
                popReg("rax");
                movRegToXmm("rax", rfXmmA());
                movRegToXmm("rbx", rfXmmB());
                raw(isWindows() ? ("    " + cmp + " " + rfXmmA() + ", " + rfXmmB()) : ("    " + cmp + " %" + rfXmmB() + ", %" + rfXmmA()));
                String setcc = mnemonic.equals("EQ_FLOAT") ? "sete"
                        : mnemonic.equals("NEQ_FLOAT") ? "setne"
                        : mnemonic.equals("LT_FLOAT") ? "setb"
                        : mnemonic.equals("LT_EQ_FLOAT") ? "setbe"
                        : mnemonic.equals("GT_EQ_FLOAT") ? "setae"
                        : "seta"; // GT_FLOAT
                if (isWindows()) {
                    raw("    " + setcc + " al");
                    raw("    movzx rax, al");
                } else {
                    raw("    " + setcc + " %al");
                    raw("    movzbq %al, %rax");
                }
                pushReg("rax");
                return;
            }
            case "AND":
            case "OR": {
                popReg("rbx");
                popReg("rax");
                raw(isWindows()
                        ? ("    " + (mnemonic.equals("AND") ? "and" : "or") + " al, bl")
                        : ("    " + (mnemonic.equals("AND") ? "and" : "or") + "b %bl, %al"));
                pushReg("rax");
                return;
            }
            case "NOT": {
                popReg("rax");
                raw(isWindows() ? "    xor al, 1" : "    xorb $1, %al");
                pushReg("rax");
                return;
            }
            case "NEG": {
                popReg("rax");
                raw(isWindows() ? "    neg rax" : "    negq %rax");
                pushReg("rax");
                return;
            }
            case "NEG_FLOAT": {
                // Float negation flips the IEEE-754 sign bit; an integer `neg`
                // would two's-complement the bit pattern (wrong value).
                int size = (int) Long.parseLong(line.get(1).text);
                popReg("rax");
                if (size <= 4) {
                    raw(isWindows() ? "    xor eax, 80000000h" : "    xorl $0x80000000, %eax");
                } else {
                    movImmToReg("rbx", Long.MIN_VALUE);
                    raw(isWindows() ? "    xor rax, rbx" : "    xorq %rbx, %rax");
                }
                pushReg("rax");
                return;
            }
            case "INC_INT":
            case "DEC_INT": {
                popReg("rax");
                raw(isWindows()
                        ? ("    " + (mnemonic.equals("INC_INT") ? "inc" : "dec") + " rax")
                        : ("    " + (mnemonic.equals("INC_INT") ? "inc" : "dec") + "q %rax"));
                pushReg("rax");
                return;
            }
            case "INC_FLOAT":
            case "DEC_FLOAT": {
                // No SSE "increment by one" instruction exists (unlike
                // `inc`/`dec` on a GPR) -- 1.0's own IEEE-754 bit pattern
                // is loaded as a plain integer immediate (exactly how a
                // float literal already reaches a register in PUSH's own
                // isFloatLiteral branch above) and added/subtracted as a
                // real SSE operand instead.
                int size = (int) Long.parseLong(line.get(1).text);
                long oneBits = size <= 4
                        ? Integer.toUnsignedLong(Float.floatToRawIntBits(1.0f))
                        : Double.doubleToRawLongBits(1.0);
                popReg("rax");
                movRegToXmm("rax", rfXmmA());
                movImmToReg("rbx", oneBits);
                movRegToXmm("rbx", rfXmmB());
                String sfx = sseSuffix(size);
                String op = mnemonic.equals("INC_FLOAT") ? "add" : "sub";
                raw(isWindows() ? ("    " + op + sfx + " " + rfXmmA() + ", " + rfXmmB()) : ("    " + op + sfx + " %" + rfXmmB() + ", %" + rfXmmA()));
                movXmmToReg(rfXmmA(), "rax");
                pushReg("rax");
                return;
            }
            case "CMP": {
                // Pops the boolean condition and sets the real flags
                // from it -- "if true, fall through; if false, jump
                // away" (emitForLoop's own doc comment, confirmed
                // directly), and CMP is always immediately followed by
                // exactly one JMP (verified against every real low-order
                // output this pass was checked against) -- so the
                // *next* JMP becomes a conditional "jump if false"
                // rather than an unconditional jump; see the JMP case.
                popReg("rax");
                raw(isWindows() ? "    test rax, rax" : "    testq %rax, %rax");
                lastWasCmp = true;
                return;
            }
            case "JMP": {
                String label = mangleLabel(line.get(1).text);
                if (wasCmp) {
                    raw(isWindows() ? ("    je " + label) : ("    je " + label));
                } else {
                    raw(isWindows() ? ("    jmp " + label) : ("    jmp " + label));
                }
                return;
            }
            case "SEXT": {
                int srcSize = (int) Long.parseLong(line.get(1).text);
                popReg("rax");
                if (isWindows()) {
                    raw("    movsx rax, " + sizedRegWin("rax", srcSize));
                } else if (srcSize == 4) {
                    raw("    movslq %eax, %rax");
                } else {
                    raw("    movs" + movSuffix(srcSize) + "q %" + sizedReg("rax", srcSize) + ", %rax");
                }
                if (line.size() > 2) {
                    // Narrow results are kept zero-extended in the stack word: drop the sign bits above dstSize.
                    int sextDst = (int) Long.parseLong(line.get(2).text);
                    if (sextDst < 8) {
                        if (isWindows()) {
                            raw(sextDst == 4 ? "    mov eax, eax" : "    movzx rax, " + sizedRegWin("rax", sextDst));
                        } else if (sextDst == 4) {
                            raw("    movl %eax, %eax");
                        } else {
                            raw("    movz" + movSuffix(sextDst) + "q %" + sizedReg("rax", sextDst) + ", %rax");
                        }
                    }
                }
                pushReg("rax");
                return;
            }
            case "TRUNC": {
                // "TRUNC srcSize destSize" -- integer narrowing (only ever emitted where a
                // `match x fits T` proof shows the value is representable in T). Keeps the low
                // destSize bytes and zero-fills the rest of the stack word.
                int dstSize = (int) Long.parseLong(line.get(2).text);
                popReg("rax");
                if (dstSize < 8) {
                    if (isWindows()) {
                        if (dstSize == 4) {
                            raw("    mov eax, eax");
                        } else {
                            raw("    movzx rax, " + sizedRegWin("rax", dstSize));
                        }
                    } else if (dstSize == 4) {
                        raw("    movl %eax, %eax");
                    } else {
                        raw("    movz" + movSuffix(dstSize) + "q %" + sizedReg("rax", dstSize) + ", %rax");
                    }
                }
                pushReg("rax");
                return;
            }
            case "ZEXT": {
                int srcSize = (int) Long.parseLong(line.get(1).text);
                popReg("rax");
                if (isWindows()) {
                    raw("    movzx rax, " + sizedRegWin("rax", srcSize));
                } else if (srcSize == 4) {
                    raw("    movl %eax, %eax"); // writing the 32-bit half auto-zeroes the upper 32 bits
                } else if (srcSize < 8) {
                    raw("    movz" + movSuffix(srcSize) + "q %" + sizedReg("rax", srcSize) + ", %rax");
                }
                pushReg("rax");
                return;
            }
            case "DUP_TOP": {
                // Duplicates the top 8-byte stack word in place (pop,
                // then push it back twice) -- used only by the front
                // end's own win64-varargs-float-duplication rule (see
                // `BytecodeEmitter.emitArgTransferTail`'s own doc
                // comment): "for variadic functions, floating-point
                // arguments must be passed in both the XMM register and
                // the corresponding general-purpose register" is a
                // win64-only ABI requirement with no SysV equivalent --
                // the one already-promoted 8-byte double bit pattern
                // needs to reach both a "POP FARGn" and a "POP ARGn"
                // line, and this is what makes a second, independent
                // copy of it available to consume. A generic "duplicate
                // the top of stack" primitive, not itself float-specific
                // -- nothing about this mnemonic cares what the 8 bytes
                // mean.
                popReg("rax");
                pushReg("rax");
                pushReg("rax");
                return;
            }
            case "FCONV": {
                // "FCONV srcSize dstSize" -- float conversion: 4 -> 8 widens (cvtss2sd, exact), 8 -> 4 narrows (cvtsd2ss, rounds to
                // nearest); equal sizes leave the value alone. The value travels as its bit pattern in one stack word.
                int fromSize = (int) Long.parseLong(line.get(1).text);
                int toSize = (int) Long.parseLong(line.get(2).text);
                if (fromSize == toSize) {
                    return;
                }
                popReg("rax");
                movRegToXmm("rax", rfXmmA());
                String cv = fromSize == 4 ? "cvtss2sd" : "cvtsd2ss";
                raw(isWindows() ? ("    " + cv + " " + rfXmmA() + ", " + rfXmmA()) : ("    " + cv + " %" + rfXmmA() + ", %" + rfXmmA()));
                if (toSize == 4) {
                    rfXmmToGpr(rfXmmA(), "rax", 4); // movd: zero-extends the 32 result bits
                } else {
                    movXmmToReg(rfXmmA(), "rax");
                }
                pushReg("rax");
                return;
            }
            case "PROMOTE_F32_TO_F64": {
                // C's own "default argument promotion" rule: a `float`
                // (this language's `f32`) argument reaching the `...`
                // portion of a variadic call (printf's own "%f" and
                // friends) is promoted to `double` *before* the call,
                // unconditionally -- never left as a raw 4-byte value,
                // regardless of what the callee's own varargs-reading
                // code assumes. This is a real, separate ABI step from
                // ordinary argument marshalling: the fixed, non-varargs
                // prefix of a variadic function's own declared
                // parameters (e.g. printf's own format-string argument)
                // is NOT promoted -- only the true varargs are -- so this
                // mnemonic is only ever emitted by the front end
                // (`emitArgWord`'s own promote flag) for an argument
                // index at or past the extern's own declared fixed
                // parameter count, on a genuinely `f32`-typed argument.
                // No `f64`/`double` type exists anywhere in this
                // language (confirmed directly, see `FLOAT_CHECK`'s own
                // doc comment above) -- this mnemonic's whole job is
                // purely a *value*-level ABI conversion, in place, on
                // the stack; it introduces no new type anywhere in the
                // rest of this bytecode format.
                //
                // Every value on this backend's own stack already
                // travels as an opaque 8-byte word (see the "Float
                // (SSE) helpers" doc comment above) -- a pushed `f32`
                // sits in the low 4 bytes of that word, upper 4 bytes
                // zero (confirmed directly against real generated
                // assembly: `movq %xmm0, %rax` zero-extends because the
                // source xmm register's own upper 32 bits are zero for
                // a real `f32` value). So: pop that word, reinterpret
                // its low 32 bits as a real `float` in a scratch xmm
                // register (`movq`, the same opaque-bit-move
                // `movRegToXmm` already uses elsewhere -- exact here
                // since the upper garbage bits are already known zero),
                // widen with a genuine `cvtss2sd` (the one real
                // floating-point conversion this mnemonic exists for),
                // move the resulting 8-byte `double` bit pattern back to
                // a GPR, and push it -- now a full, ABI-correct 8-byte
                // double occupying the same one stack word a raw `f32`
                // word already occupied, so nothing downstream
                // (register-vs-stack argument counting, `POP FARGn`, an
                // overflowing stack-passed word) needs to change at
                // all: this still consumes exactly one float-bank
                // argument slot, exactly as an unpromoted `f32` word
                // already did.
                //
                // **Deliberately uses `xmm8`, never `xmm0`, as scratch --
                // a real, confirmed register-clobber bug otherwise, the
                // identical class of hazard already documented for
                // `LOOKUP_ARRAY`'s own scratch-register choice** (this
                // project's own CLAUDE.md, "a second, real bug found
                // ... this time a register-clobber"): a multi-float
                // variadic call (e.g. `printf("%f %f", a, b)`) already
                // has `a`'s own promoted value sitting live in `xmm0`
                // (its real "POP FARG0" destination) by the time `b`'s
                // own "PUSH b / PROMOTE_F32_TO_F64 / POP FARG1" sequence
                // runs -- using `xmm0` as this mnemonic's own scratch
                // register for `b` would silently overwrite `a`'s
                // already-transferred value before the call ever runs
                // (confirmed directly: printed both floats as `b`'s own
                // value before this fix). `xmm8` is never a real SysV or
                // win64 argument register in either convention (both cap
                // out at `xmm7`/`xmm3` respectively), so it can never
                // collide with a live, not-yet-consumed `FARGn`
                // transfer, no matter how many float arguments a call
                // has or how many of them have already landed in their
                // own real argument register by the time this mnemonic
                // runs for a later one.
                popReg("rax");
                movRegToXmm("rax", rfXmmA());
                raw(isWindows() ? ("    cvtss2sd " + rfXmmA() + ", " + rfXmmA()) : ("    cvtss2sd %" + rfXmmA() + ", %" + rfXmmA()));
                movXmmToReg(rfXmmA(), "rax");
                pushReg("rax");
                return;
            }
            case "PUSH_FIELDNAME": {
                // Already address-lowered to "PUSH_FIELDNAME offset
                // size" -- pushes the (compile-time-constant) field
                // offset as an immediate, for the DOT/DOT_LHS that
                // always immediately follows to add to the base address
                // already on the stack.
                long offset = Long.parseLong(line.get(1).text);
                movImmToReg("rax", offset);
                pushReg("rax");
                // Preserve a pending value-block across this (see PUSH's
                // own note above) -- e.g. a DOT chained right after a
                // LOOKUP_ARRAY read needs to know the element value
                // LOOKUP_ARRAY just left on the stack is still there.
                lastValueBlockSize = pendingBlockSize;
                return;
            }
            case "DOT_LHS": {
                int size = (int) Long.parseLong(line.get(1).text);
                popReg("rbx"); // field offset (from PUSH_FIELDNAME)
                popReg("rax"); // base address
                raw(isWindows() ? "    add rax, rbx" : "    addq %rbx, %rax");
                pushReg("rax"); // the computed field address itself
                return;
            }
            case "DOT": {
                // Like LOOKUP_ARRAY vs LOOKUP_ARRAY_LHS above: DOT_LHS's
                // base is a plain address, but a *read* DOT's base is
                // the whole containing value pushed by value (confirmed
                // directly by the same evidence: a bare struct's own
                // "PUSH structSize $offset" before PUSH_FIELDNAME+DOT --
                // e.g. reading a field off an array-of-structs element
                // LOOKUP_ARRAY (non-LHS) just extracted, "PUSH 48 $-200 /
                // PUSH idx / LOOKUP_ARRAY 24 / PUSH_FIELDNAME off size /
                // DOT size"). `pendingBlockSize` is that containing
                // value's own total byte size.
                int size = (int) Long.parseLong(line.get(1).text);
                if (pendingBlockSize <= 0) {
                    // Never observed in any fixture this pass was
                    // checked against (every real DOT read this pass was
                    // checked against was chained after a pushed value,
                    // never a bare address) -- flagged rather than
                    // guessed at.
                    comment("TODO(codegen): DOT with no preceding pushed value not yet implemented -- pushing 0 as a placeholder");
                    popReg("rbx"); // discard field offset
                    movImmToReg("rax", 0);
                    pushReg("rax");
                    return;
                }
                long totalWords = (pendingBlockSize + 7) / 8;
                popReg("rbx"); // field offset (in bytes, from the value's own front)
                if (size <= 8) {
                    // A field inside one word. The block's words are reversed (the front word is the deepest), so the byte at struct offset X lives at
                    // rsp + (totalWords-1-X/8)*8 + X%8. (The formula below is right only for a whole aligned word or a wider field: for a narrower field it
                    // pointed into the wrong half of a word, e.g. `arr[i].a` for a u32 at offset 8 read the byte at offset 12.)
                    if (isWindows()) {
                        raw("    mov rax, rbx");
                        raw("    and rax, -8");
                        raw("    neg rax");
                        raw("    add rax, " + ((totalWords - 1) * 8));
                        raw("    and rbx, 7");
                        raw("    add rax, rbx");
                        raw("    add rax, rsp");
                    } else {
                        raw("    movq %rbx, %rax");
                        raw("    andq $-8, %rax");
                        raw("    negq %rax");
                        raw("    addq $" + ((totalWords - 1) * 8) + ", %rax");
                        raw("    andq $7, %rbx");
                        raw("    addq %rbx, %rax");
                        raw("    addq %rsp, %rax");
                    }
                } else if (isWindows()) {
                    // a field wider than a word: rax = address of its first byte, r14 = its offset inside that word (see extractWordsFromReversedBlock)
                    raw("    mov r14, rbx");
                    raw("    and r14, 7");
                    raw("    shr rbx, 3");
                    raw("    imul rbx, 8");
                    raw("    mov rax, " + (totalWords * 8));
                    raw("    sub rax, 8");
                    raw("    sub rax, rbx");
                    raw("    add rax, r14");
                    raw("    add rax, rsp");
                } else {
                    raw("    movq %rbx, %r14");
                    raw("    andq $7, %r14");
                    raw("    shrq $3, %rbx");
                    raw("    imulq $8, %rbx, %rbx");
                    raw("    movq $" + (totalWords * 8) + ", %rax");
                    raw("    subq $8, %rax");
                    raw("    subq %rbx, %rax");
                    raw("    addq %r14, %rax");
                    raw("    addq %rsp, %rax");
                }
                boolean fieldIsSmallArray = line.size() > 2 && line.get(2).text.equals("ra");
                if (size <= 8 && fieldIsSmallArray) {
                    // A fixed array of at most 8 bytes: its bytes may straddle two of the block's (reversed) words, so they are not contiguous in
                    // memory. rax = address of the field's first byte, rbx = its offset within its word. Read the word it starts in and the next
                    // struct word (the next-lower address) -- only when the field really straddles, otherwise the same word again, so nothing below the pushed block is read -- and shift the pair together (shrd); bytes past the field are junk, as for any small array.
                    if (isWindows()) {
                        raw("    mov r14, rcx");
                        raw("    mov rcx, rbx");
                        raw("    shl rcx, 3");
                        raw("    sub rax, rbx");
                        raw("    lea r11, [rax-8]");
                        raw("    cmp rbx, " + (8 - size));
                        raw("    cmovbe r11, rax");   // no straddle: read the same word again, never the word below the block
                        raw("    mov r11, [r11]");
                        raw("    mov rax, [rax]");
                        raw("    shrd rax, r11, cl");
                        raw("    mov rcx, r14");
                        raw("    add rsp, " + (totalWords * 8));
                    } else {
                        raw("    movq %rcx, %r14");
                        raw("    movq %rbx, %rcx");
                        raw("    shlq $3, %rcx");
                        raw("    subq %rbx, %rax");
                        raw("    leaq -8(%rax), %r11");
                        raw("    cmpq $" + (8 - size) + ", %rbx");
                        raw("    cmovbeq %rax, %r11");   // no straddle: read the same word again, never the word below the block
                        raw("    movq (%r11), %r11");
                        raw("    movq (%rax), %rax");
                        raw("    shrdq %cl, %r11, %rax");
                        raw("    movq %r14, %rcx");
                        raw("    addq $" + (totalWords * 8) + ", %rsp");
                    }
                    pushReg("rax");
                    lastValueBlockSize = -size;
                } else if (size <= 8) {
                    loadSizedFromAddr("rax", "rax", size);
                    raw(isWindows() ? ("    add rsp, " + (totalWords * 8)) : ("    addq $" + (totalWords * 8) + ", %rsp"));
                    pushReg("rax");
                } else {
                    // A multi-word nested field (a struct- or array-typed
                    // field read off a base that arrived as a whole pushed
                    // value, e.g. "arr[i].point" -- the array element
                    // itself came from LOOKUP_ARRAY as a still-pending
                    // multi-word block, and this DOT is now reading a
                    // >8-byte field out of it).
                    //
                    // `rax` (just computed above, shared with the size<=8
                    // branch) is *not* the field's front-most word the way
                    // LOOKUP_ARRAY's own analogous `elemSize > 8` fix's
                    // `rax` is (that formula subtracts a fixed one-word `8`;
                    // this one subtracts the field's own full `size`) --
                    // confirmed by working the address algebra through for
                    // a field at byte-offset `off` covering word indices
                    // `off/8 .. off/8+fieldWords-1` within the containing
                    // block: this formula's `rax` lands on word index
                    // `off/8 + fieldWords - 1`, i.e. the field's own
                    // *back*-most word (nearest rsp), the same structural
                    // role plain `%rsp` itself already plays for a whole
                    // pushed block (address_of_wordIndex(totalWords-1) ==
                    // rsp+0, exactly this formula's own shape with
                    // size==totalWords*8, off==0). Since `rax` is already
                    // a same-role "back-word anchor" for the field the way
                    // `rsp` is for the whole block, moving the field's own
                    // words into a fresh, `rsp`-anchored block needs no
                    // reversal at all -- a word at relative offset `k*8`
                    // from `rax` goes to relative offset `k*8` from the new
                    // (post-shrink) `rsp`, k ascending, preserving this
                    // backend's own "back-most word nearest rsp" convention
                    // automatically. (A first attempt at this copied
                    // LOOKUP_ARRAY's own reversed-copy shape verbatim,
                    // reasoning `rax` was a front-word anchor like that
                    // fix's own `rax` -- confirmed wrong via a real
                    // execution test, `arr[i].point` printing `x=8 y=0`
                    // instead of the real field values, before this
                    // same-relative-offset version was worked out and
                    // reverified.)
                    //
                    // rsp is shrunk to the field's own final size FIRST
                    // (mirroring the *reason*, not the shape, of
                    // LOOKUP_ARRAY's own fix: `rax`, captured into `r14`
                    // beforehand, is an absolute address unaffected by
                    // moving rsp afterward, so this ordering is always
                    // safe and guarantees the copy's own destination is
                    // the address range that will actually still be live).
                    extractWordsFromReversedBlock(totalWords, size);
                    lastValueBlockSize = size; // for a chained DOT right after this
                }
                return;
            }
            case "LOOKUP_ARRAY_LHS": {
                // The write side always gets its base via a plain
                // "ADDR" (a single address word), confirmed directly
                // against array_lookup_cg_test.caspien's own real
                // low-order output -- ordinary address arithmetic.
                int elemSize = (int) Long.parseLong(line.get(1).text);
                popReg("rbx"); // index
                popReg("rax"); // base address
                if (isWindows()) {
                    raw("    imul rbx, " + elemSize);
                    raw("    add rax, rbx");
                } else {
                    raw("    imulq $" + elemSize + ", %rbx, %rbx");
                    raw("    addq %rbx, %rax");
                }
                pushReg("rax");
                return;
            }
            case "LOOKUP_ARRAY": {
                // The *read* side, by contrast, always gets its base via
                // a plain "PUSH <totalArraySize> $offset" -- the whole
                // array pushed by value (see PUSH's own note above) --
                // never an address, confirmed directly against the same
                // fixture: "PUSH 40 $-40 / PUSH idx / LOOKUP_ARRAY 8"
                // (a real bug this pass shipped with until caught by
                // actually running this exact test -- the old code
                // treated the popped array VALUE as if it were a
                // pointer, corrupting every later use of the real stack).
                // `pendingBlockSize` is the whole array's own total byte
                // count, tracked from that preceding PUSH; extracting
                // element `index` means locating word
                // index*elemSize/8 counting from the *front* of the
                // array (its lowest source offset, which -- per
                // pushBlockFromFrame's own convention -- ended up
                // *deepest* on the real stack, not on top).
                int elemSize = (int) Long.parseLong(line.get(1).text);
                // "ra": the element read is itself a fixed array narrower than 8 bytes, held by value -- tag it for the next LOOKUP_ARRAY (as PUSH does)
                boolean resultIsSmallArray = false;
                for (int q = 2; q < line.size(); q++) {
                    resultIsSmallArray |= line.get(q).text.equals("ra");
                }
                if (pendingBlockSize == 0 && line.size() > 2 && line.get(2).text.equals("t8")) {
                    // The lowering says the value is a whole 8-byte fixed array (not a pointer): its bytes are one natural-order word, exactly the shape
                    // of a smaller array pushed by value.
                    pendingBlockSize = -8;
                }
                if (pendingBlockSize < 0) {
                    // A small (<8-byte) fixed array pushed by value as a
                    // single, ordinary, natural-byte-order word -- see
                    // PUSH's own "size < 8" branch, which is the only
                    // place this negative encoding is ever produced. No
                    // memory access at all: the whole array's own bytes
                    // are already sitting directly in a register, so
                    // extracting element `index` is a plain shift-and-
                    // mask, never an address computation followed by a
                    // dereference (which is exactly what the fallback
                    // just below does, and exactly what corrupted this
                    // case before this branch existed -- a small array's
                    // own packed *value* is not a pointer).
                    popReg("rbx"); // index
                    popReg("rax"); // the whole small array's own packed value
                    // %rcx is both the shift-count register and an argument register (4th SysV, 1st win64): a finished earlier argument
                    // of the call being assembled may already sit in it (`printf(fmt, a, s[0], s[1], s[2])` popped s[0]/s[1] into their
                    // registers before this lookup runs), so save and restore it around the shift.
                    if (isWindows()) {
                        raw("    imul rbx, " + (elemSize * 8) + " ; index*elemSize, in bits");
                        raw("    push rcx");
                        raw("    mov rcx, rbx");
                        raw("    shr rax, cl");
                        raw("    pop rcx");
                    } else {
                        raw("    imulq $" + (elemSize * 8) + ", %rbx, %rbx"); // index*elemSize, in bits
                        raw("    pushq %rcx");
                        raw("    movq %rbx, %rcx");
                        raw("    shrq %cl, %rax");
                        raw("    popq %rcx");
                    }
                    if (isOddSize(elemSize)) {
                        // 3, 5, 6, 7 bytes (a row of a `u8[3][2]`): keep exactly those bytes. (The 1-byte `movzbl` used to be applied here too,
                        // cutting a row down to its first element.)
                        int sh = 64 - elemSize * 8;
                        raw(isWindows() ? ("    shl rax, " + sh) : ("    shlq $" + sh + ", %rax"));
                        raw(isWindows() ? ("    shr rax, " + sh) : ("    shrq $" + sh + ", %rax"));
                    } else if (elemSize < 8) {
                        // `andq` takes only a sign-extended 32-bit immediate, so a 4-byte
                        // mask (0xFFFFFFFF) was rejected by the assembler; a zero-extending
                        // move does the same job for 1, 2 and 4 bytes.
                        if (isWindows()) {
                            raw(elemSize == 4 ? "    mov eax, eax" : elemSize == 2 ? "    movzx eax, ax" : "    movzx eax, al");
                        } else {
                            raw(elemSize == 4 ? "    movl %eax, %eax" : elemSize == 2 ? "    movzwl %ax, %eax" : "    movzbl %al, %eax");
                        }
                    }
                    pushReg("rax");
                    if (resultIsSmallArray) {
                        lastValueBlockSize = -elemSize;
                    }
                    return;
                }
                if (pendingBlockSize == 0) {
                    // The same LOOKUP_ARRAY(_LHS) family is also used for
                    // an unsafe-dynarray's own direct (unchecked) index,
                    // whose "array" is really just a pointer *value* --
                    // an ordinary single 8-byte PUSH, not a multi-word
                    // block -- confirmed directly (an unsafe-dynarray
                    // fixture pushes "PUSH 8 $ptr / PUSH 8 idx /
                    // LOOKUP_ARRAY size" for both its read *and* write
                    // sides, unlike a true fixed array's own read/write
                    // split above). There, the popped value already *is*
                    // the base address to index from, exactly like the
                    // pre-existing single-word logic this pass shipped
                    // with originally -- so that's the fallback here,
                    // not a guess.
                    popReg("rbx"); // index
                    popReg("rax"); // base address (a plain pointer value)
                    if (isWindows()) {
                        raw("    imul rbx, " + elemSize);
                        raw("    add rax, rbx");
                    } else {
                        raw("    imulq $" + elemSize + ", %rbx, %rbx");
                        raw("    addq %rbx, %rax");
                    }
                    pushSizedOrBlockFromAddr("rax", elemSize);
                    if (resultIsSmallArray) {
                        lastValueBlockSize = -elemSize;
                    } else if (elemSize > 8) {
                        lastValueBlockSize = elemSize; // a struct element read through a pointer base: the DOT that follows needs the block size
                    }
                    return;
                }
                long totalWords = (pendingBlockSize + 7) / 8;
                popReg("rbx"); // index
                if (isWindows()) {
                    raw("    imul rbx, " + elemSize);
                } else {
                    raw("    imulq $" + elemSize + ", %rbx, %rbx");
                }
                // rbx now holds the target element's byte offset from the
                // array's own front (source-offset order). The pushed
                // block's own *words* are reversed (word i-from-front
                // sits at rsp + (totalWords-1-i)*8), but the *bytes
                // within* any one already-placed word are never
                // reordered by the push at all -- so a byte offset has to
                // be split into a word index (which gets the reversed
                // treatment) and a byte-within-word remainder (which
                // doesn't) before it can be turned into a real address:
                // rdx = rbx & 7 (byte-within-word), rbx = rbx >> 3
                // (word index, reusing rbx in place), then address =
                // rsp + (totalWords - 1 - wordIndex)*8 + byteWithinWord.
                //
                // 2026-09: two bugs fixed here together, both found via
                // real execution tests, neither previously caught because
                // every fixture ever checked against this branch before
                // now happened to have `elemSize` be an exact multiple of
                // 8 with `index*elemSize` therefore always landing on a
                // word boundary (byteWithinWord always 0) --
                //
                // 1. This used to subtract a bare `elemSize` here instead
                // of a fixed one-word `8` -- wrong for any element wider
                // than one word (a nested array, a struct: a 2D
                // `u64[3][3]`'s every row read back as row 0's own data,
                // regardless of the real index).
                //
                // 2. Even with that fixed, treating the *whole* byte
                // offset as if it were a whole-word offset is wrong the
                // moment more than one logical element shares a single
                // packed word -- this language's own tight sub-word
                // array/struct-literal construction packing means a
                // `char[10]` (say) really occupies just two real 8-byte
                // words, not ten (see `populateDirectMembers`'s own doc
                // comment) -- so a narrow (<8-byte) element's own real
                // address needs this word/byte split; without it, only
                // elements sharing word 0 (i.e. index 0 itself) ever
                // happened to read back correctly. See
                // caspien-codegen's own CLAUDE.md for the full writeup
                // of all three bugs this branch has now had fixed.
                //
                // The byte-within-word remainder needs a scratch
                // register -- `%rdx` was tried first and was wrong: this
                // whole LOOKUP_ARRAY sequence can sit in the middle of a
                // multi-argument call's own argument list (e.g. each of
                // `printf(fmt, arr[0], arr[1], arr[2])`'s three lookups
                // runs back to back, with each finished argument
                // immediately popped into its own real calling-convention
                // register -- `%rsi`, then `%rdx`, then `%rcx` for SysV --
                // before the *next* argument's own lookup even starts).
                // Using `%rdx` here clobbered exactly that: the second
                // argument's own already-computed, not-yet-called value
                // sitting in `%rdx` was silently overwritten by the
                // *third* argument's own lookup reusing `%rdx` as scratch,
                // before `printf` was ever called -- found via a real
                // execution test, a 2D array's middle column printing `0`
                // instead of its real value, and confirmed via gdb
                // (`%rdx` held the correct, second argument's value right
                // up until the third lookup's own `and $7, %rdx`
                // instruction). `%r14` is used instead -- already this
                // branch's own scratch register for the `elemSize > 8`
                // case just below, never a calling-convention argument
                // register in either ABI, and (unlike `%rdx`) never used
                // to carry a live, not-yet-consumed value across separate
                // LOOKUP_ARRAY invocations anywhere else in this file.
                if (isWindows()) {
                    raw("    mov r14, rbx");
                    raw("    and r14, 7");
                    raw("    shr rbx, 3");
                    raw("    imul rbx, 8");
                    raw("    mov rax, " + (totalWords * 8));
                    raw("    sub rax, 8");
                    raw("    sub rax, rbx");
                    raw("    add rax, r14");
                    raw("    add rax, rsp");
                } else {
                    raw("    movq %rbx, %r14");
                    raw("    andq $7, %r14");
                    raw("    shrq $3, %rbx");
                    raw("    imulq $8, %rbx, %rbx");
                    raw("    movq $" + (totalWords * 8) + ", %rax");
                    raw("    subq $8, %rax");
                    raw("    subq %rbx, %rax");
                    raw("    addq %r14, %rax");
                    raw("    addq %rsp, %rax");
                }
                if (isOddSize(elemSize)) {
                    // A 3, 5, 6 or 7 byte element (a row of a `u16[3][2]`) can start in one source word and end in the next, and the words of
                    // the pushed block are in reverse order (word k+1 sits 8 bytes BELOW word k), so its bytes are not contiguous in memory and a
                    // plain load at `rax` reads the bytes above the block instead of the second word. Same shrd extraction as the multi-word case
                    // below, for one result word: `rax` = address of the element's first byte, `r14` = its offset inside the word (0..7).
                    if (isWindows()) {
                        raw("    mov r11, rax");
                        raw("    sub r11, r14");
                        raw("    shl r14, 3");
                        raw("    mov r13, rcx");
                        raw("    mov rcx, r14");
                        raw("    mov rax, [r11]");
                        raw("    mov r14, [r11-8]");
                        raw("    shrd rax, r14, cl");
                        raw("    mov rcx, r13");
                        raw("    add rsp, " + (totalWords * 8));
                    } else {
                        raw("    movq %rax, %r11");
                        raw("    subq %r14, %r11");
                        raw("    shlq $3, %r14");
                        raw("    movq %rcx, %r13");
                        raw("    movq %r14, %rcx");
                        raw("    movq (%r11), %rax");
                        raw("    movq -8(%r11), %r14");
                        raw("    shrdq %cl, %r14, %rax");
                        raw("    movq %r13, %rcx");
                        raw("    addq $" + (totalWords * 8) + ", %rsp");
                    }
                    pushReg("rax");
                    if (resultIsSmallArray) {
                        lastValueBlockSize = -elemSize;
                    }
                } else if (elemSize <= 8) {
                    loadSizedFromAddr("rax", "rax", elemSize);
                    // discard the whole original block now that the one
                    // element we need is safely copied into rax.
                    raw(isWindows() ? ("    add rsp, " + (totalWords * 8)) : ("    addq $" + (totalWords * 8) + ", %rsp"));
                    pushReg("rax");
                    if (resultIsSmallArray) {
                        lastValueBlockSize = -elemSize;
                    }
                } else {
                    // A multi-word element (an array of structs, or a nested array, say). `rax` is the address of the element's first BYTE
                    // inside the pushed block, `r14` = that byte's offset inside its word (0..7).
                    // Block layout: word k (bytes 8k..8k+7) sits at rsp + (T-1-k)*8, bytes natural inside a word. So element bytes are NOT
                    // contiguous in memory unless the element starts on a word boundary (a 12-byte `u32[3]` row of a `u32[3][2]` starts at byte
                    // 12): result word j is the 64 bits starting at byte `within` of source word w+j, continued into word w+j+1 (which sits
                    // 8 bytes BELOW it) -- exactly `shrd`. A count of 0 (aligned element) leaves the word unchanged.
                    // Result word j goes to source word j's slot (rsp + (T-1-j)*8): that is where it must end up once rsp is raised by
                    // (T - E)*8 (result word j sits at rsp' + (E-1-j)*8), and writing in ascending j never overwrites a word still to be read
                    // (step j reads words w+j and w+j+1, both >= j+... greater than every slot already written).
                    // The word past the block end (read for the last, possibly partial, result word) lies just below rsp: valid stack, and
                    // only bytes beyond the element come from it.
                    // %rcx is the shift count register and may hold an already-loaded call argument: stash it in r13 (backend scratch).
                    extractWordsFromReversedBlock(totalWords, elemSize);
                    lastValueBlockSize = elemSize; // for a chained DOT right after this
                }
                return;
            }
            case "LOOKUP_DYN":
            case "LOOKUP_DYN_LHS": {
                // This backend's own dynarray layout: [len:8][cap:8]
                // [elements...] -- see class doc comment. The element
                // offset is 16 + index*elemSize.
                int elemSize = (int) Long.parseLong(line.get(1).text);
                popReg("rbx"); // index
                popReg("rax"); // dynarray pointer
                if (isWindows()) {
                    raw("    imul rbx, " + elemSize);
                    raw("    add rax, rbx");
                    raw("    add rax, 16");
                } else {
                    raw("    imulq $" + elemSize + ", %rbx, %rbx");
                    raw("    addq %rbx, %rax");
                    raw("    addq $16, %rax");
                }
                if (mnemonic.equals("LOOKUP_DYN_LHS")) {
                    pushReg("rax");
                } else {
                    pushSizedOrBlockFromAddr("rax", elemSize);
                    // An element wider than a word (a struct) is now a pushed value block: tell a DOT / LOOKUP that follows (`es[i].to`) how big it
                    // is, exactly as LOOKUP_ARRAY does for an array element. Without this the DOT found no pending block ("DOT with no preceding
                    // pushed value") and pushed 0, so any read of a field of a dynarray-of-struct element was wrong or crashed.
                    if (elemSize > 8) {
                        lastValueBlockSize = elemSize;
                    }
                }
                return;
            }
            case "LEN": {
                popReg("rax"); // dynarray pointer
                loadSizedFromAddr("rax", "rax", 8); // the header's own len field, offset 0
                pushReg("rax");
                return;
            }
            case "STACK_LOCK":
            case "STRUCT_PADDING": {
                // STACK_LOCK: reserve N raw, valueless bytes of stack
                // space mid-struct-construction so later-pushed members
                // land at their real, padded offsets (see
                // caspien-compiler's own emitInstantiate, "STACK_LOCK
                // reused for padding at struct construction"). Plain
                // 'sub rsp,N' does exactly this. STRUCT_PADDING, by
                // contrast, was never actually observed inside any real
                // function body across every fixture this pass was
                // checked against -- only as inert, stray top-level
                // metadata between two FUNC_END/FUNC_START lines (dead
                // leftovers that should have been fully stripped a stage
                // earlier) -- so it's treated as a pure no-op rather
                // than reserving stack space that would silently
                // misalign this backend's own push/pop accounting if
                // that assumption turns out wrong somewhere this pass
                // wasn't checked.
                if (mnemonic.equals("STACK_LOCK")) {
                    long n = Long.parseLong(line.get(1).text);
                    raw(isWindows() ? ("    sub rsp, " + n) : ("    subq $" + n + ", %rsp"));
                }
                return;
            }
            case "NEW": {
                // Every field push between the true start of this
                // construction and this exact line is now
                // construction-scoped (see `precomputeConstructionOffsets`/
                // `populateDirectMembers`'s own doc comments): each one reserved
                // and wrote only its own declared width, not a uniform
                // 8-byte word, and each "STACK_LOCK" padding gap did its
                // own exact `subq`. So `%rsp`, right here, already
                // points at the start of a real, byte-exact,
                // tightly-packed image of the value being constructed
                // -- `size` bytes, laid out exactly the way
                // `computeStructLayout` says, with zero further
                // reconciliation needed. NEW's whole job is now
                // genuinely trivial and completely struct-agnostic --
                // no field count, no offsets, nothing type-specific:
                // malloc(size), copy those `size` bytes straight off
                // the stack into the new buffer, deallocate the stack
                // region they came from now that they're safely copied,
                // and push the new pointer.
                //
                // This used to pop one 8-byte hardware word per
                // (size+7)/8, silently under-counting the real number
                // of pushed words the moment any field was narrower
                // than 8 bytes (a `bool` next to a `u64`, say) --
                // confirmed directly against real bytecode/assembly for
                // exactly that shape. Fixed at the true root instead
                // (the pushes themselves, above, weren't byte-exact),
                // so this case no longer needs to know or care how many
                // fields there were: one straight block copy, the exact
                // same `rep movsb` convention `case "CLONE"` already
                // uses for its own whole-buffer copy, just from the
                // stack instead of from another heap pointer.
                long size = Long.parseLong(line.get(1).text);
                if (!packedNewLines.contains(currentLineIndex) && line.size() > 2) {
                    emitNewRepack(size, line);
                    return;
                }
                raw(isWindows() ? "    mov r12, rsp" : "    movq %rsp, %r12"); // r12: source address -- the construction's own tightly-packed image already sitting on the stack, callee-saved, survives the malloc call below
                emitMallocCall(size);
                raw(isWindows() ? "    mov r14, rax" : "    movq %rax, %r14"); // r14: the fresh buffer, also callee-saved
                if (isWindows()) {
                    raw("    mov rdi, r14");
                    raw("    mov rsi, r12");
                    raw("    mov rcx, " + size);
                    raw("    rep movsb");
                } else {
                    raw("    movq %r14, %rdi");
                    raw("    movq %r12, %rsi");
                    raw("    movq $" + size + ", %rcx");
                    raw("    rep movsb");
                }
                raw(isWindows() ? ("    add rsp, " + size) : ("    addq $" + size + ", %rsp"));
                pushReg("r14");
                return;
            }
            case "CLONE": {
                long size = Long.parseLong(line.get(1).text);
                popReg("r12"); // source address (r12: callee-saved, survives the call below)
                emitMallocCall(size);
                raw(isWindows() ? "    mov r14, rax" : "    movq %rax, %r14"); // r14: new pointer, also callee-saved
                if (isWindows()) {
                    raw("    mov rdi, r14");
                    raw("    mov rsi, r12");
                    raw("    mov rcx, " + size);
                    raw("    rep movsb");
                } else {
                    raw("    movq %r14, %rdi");
                    raw("    movq %r12, %rsi");
                    raw("    movq $" + size + ", %rcx");
                    raw("    rep movsb");
                }
                pushReg("r14");
                return;
            }
            case "MEMCOPY": {
                // Pops (top to bottom) src, size, dest -- a plain byte
                // copy, no result pushed (the two "PUSH $addr" lines
                // immediately around every real MEMCOPY site this pass
                // was checked against re-read their own variables
                // separately afterward, confirmed directly).
                popReg("rsi"); // src
                popReg("rcx"); // size
                popReg("rdi"); // dest
                raw("    rep movsb");
                return;
            }
            case "DEREF": {
                int size = (int) Long.parseLong(line.get(1).text);
                popReg("rbx"); // pointer
                // The value this leaves is a whole aggregate by value when a LOOKUP_ARRAY/DOT follows (an array member read through an
                // owning pointer: "DOT_LHS / DEREF 24 / PUSH idx / LOOKUP_ARRAY 8"), so record its shape the way PUSH does for a value read
                // from the frame: > 8 bytes is a pushed word block, < 8 bytes a single natural-order word (negative tag). Without this
                // LOOKUP_ARRAY took its "bare pointer" fallback and dereferenced the array's own contents as an address.
                lastValueBlockSize = size > 8 ? size : (size < 8 ? -size : 0);
                if (size <= 8) {
                    loadSizedFromAddr("rax", "rbx", size);
                    pushReg("rax");
                } else {
                    // A multi-word (>8 byte) aggregate dereference --
                    // pushes ceil(size/8) whole words, lowest source
                    // address first, so the highest-address word ends up
                    // on top of the real stack; the matching "POP $addr
                    // size" block-store (see POP below) unwinds this in
                    // the mirror order. Only whole-8-byte chunks are
                    // handled -- a non-multiple-of-8 aggregate size was
                    // never observed and isn't specially handled.
                    long words = (size + 7) / 8;
                    for (long i = 0; i < words; i++) {
                        loadSizedFromAddr("rax", "rbx", 8);
                        if (i < words - 1) {
                            raw(isWindows() ? "    add rbx, 8" : "    addq $8, %rbx");
                        }
                        pushReg("rax");
                    }
                }
                return;
            }
            case "ADDR_OF": {
                // The "ADDR_OF kind $offset" shape (a plain addressable
                // local) was, at first, only ever observed here with
                // kind=RAW -- but AddressLoweringPass's own rewrite
                // (this pass's own doc comment: "opText (AUTO/RAW --
                // REF and a storage-bearing RAW never reach this
                // [rewrite]") already produces the identical "$offset"
                // shape for kind=AUTO too, confirmed directly while
                // testing a real auto-storage pointer end to end (see
                // BytecodeEmitter's "A pointer-typed dot-chain access
                // gets a real runtime dereference" fix): AUTO/RAW are
                // both just "take the address of an already-addressable
                // stack slot," with no runtime difference between them
                // -- the storage keyword only ever changes what the
                // *type checker* allows through it, never what the CPU
                // has to do to compute the address itself, so both kinds
                // get the identical leaMemToReg treatment below. Other
                // operand shapes (a global, a dotted field root, ...)
                // are still flagged rather than guessed at.
                //
                // AddressLoweringPass's own doc comment for this rewrite
                // says the plain "PUSH name type" line immediately
                // preceding this one (emitAddressOf's own
                // `emitExpr(op.left)`, run before ADDR_OF itself) is
                // superseded once the real address is known -- but that
                // pass only rewrites *this* line, it doesn't reach back
                // and delete the one before it, so that now-dead pushed
                // value genuinely survives into real low-order output
                // (confirmed directly by actually assembling and running
                // a real "raw <local>" fixture -- without this discard,
                // the stray value silently shifts everything below it on
                // the real stack, corrupting the very next real ASSIGN).
                // Popping it here, once, before pushing the real address
                // is exactly what the doc comment's own "dropped" already
                // says should happen to it.
                //
                // The dead value being discarded isn't always a single
                // 8-byte word: "auto obj" on a struct-typed `obj` pushes
                // `obj` by its own full declared size first (the ordinary
                // "PUSH size $offset, size>8 -> pushBlockFromFrame"
                // multi-word case, same as any other by-value struct
                // push), and *that's* the value ADDR_OF's own preceding
                // line pushed here -- confirmed directly while testing a
                // real "let p = auto structObj" end to end (a hardcoded
                // single-word discard left one whole stray word behind on
                // the stack for every struct-sized `auto`, corrupting
                // everything below it, the exact same class of bug this
                // case's own doc comment already describes for the
                // no-discard-at-all version). `pendingBlockSize` (already
                // computed above from the immediately preceding PUSH's
                // own `lastValueBlockSize`, the same state DOT/LOOKUP_ARRAY
                // already key off of) is 0 for an ordinary single-word
                // push and the real total byte size for a multi-word one.
                String kind = line.get(1).text;
                String operand = line.get(2).text;
                long discardBytes = pendingBlockSize > 8 ? ((pendingBlockSize + 7) / 8) * 8 : 8;
                raw(isWindows() ? ("    add rsp, " + discardBytes) : ("    addq $" + discardBytes + ", %rsp")); // discard the dead pushed value
                if ((kind.equals("RAW") || kind.equals("AUTO")) && operand.startsWith("$")) {
                    leaMemToReg("rax", Long.parseLong(operand.substring(1)));
                    pushReg("rax");
                } else {
                    comment("TODO(codegen): '" + String.join(" ", textsOf(line)) + "' not yet implemented -- pushing 0 as a placeholder");
                    movImmToReg("rax", 0);
                    pushReg("rax");
                }
                return;
            }
            case "NEW_DYN": {
                // "NEW_DYN totalPushedBytes count" -- `count` initializer
                // values, each currently assumed to be a plain 8-byte
                // word (this backend's own push/pop model treats every
                // value uniformly as one stack word regardless of its
                // declared type -- a narrower- or wider-than-8-byte
                // dynarray element isn't specially handled, matching
                // this pass's pre-existing "8-byte word" limitation
                // elsewhere). Allocates [len][cap][elements...], with
                // len == cap == count, and pops the `count` initializer
                // values (top of stack = last-pushed = last element)
                // into their slots.
                long count = Long.parseLong(line.get(2).text);
                long dataBytes = count * 8;
                emitMallocCall(16 + dataBytes);
                raw(isWindows() ? "    mov r12, rax" : "    movq %rax, %r12");
                storeSizedToAddr_withOffsetImm("r12", 0, count, 8);
                storeSizedToAddr_withOffsetImm("r12", 8, count, 8);
                for (long i = count - 1; i >= 0; i--) {
                    popReg("rax");
                    long off = 16 + i * 8;
                    if (isWindows()) {
                        raw("    mov [r12" + signed(off) + "], rax");
                    } else {
                        raw("    movq %rax, " + off + "(%r12)");
                    }
                }
                pushReg("r12");
                return;
            }
            case "NEW_UDYN": {
                // "NEW_UDYN totalPushedBytes" -- the unsafe/no-explicit-
                // count sibling of NEW_DYN; count is derived as
                // totalBytes/8 under the same "8-byte elements" working
                // assumption.
                //
                // Returns a pointer to the *data start* (element 0), not
                // the malloc'd block start -- unlike NEW_DYN/LOOKUP_DYN's
                // own header-first convention. This is a deliberate split
                // this pass had to introduce after a real, confirmed bug:
                // an unsafe dynarray's direct index always goes through
                // the plain LOOKUP_ARRAY(_LHS) family (see its own doc
                // comment), which computes element addresses as
                // "base + index*elemSize" with no way to also know to
                // skip a header (LOOKUP_ARRAY_LHS's base can just as
                // easily be a genuine ADDR-computed fixed-array address,
                // which has no header at all) -- so the only way to keep
                // that shared indexing code correct for both is to make
                // an unsafe dynarray's own "base" already point straight
                // at its data, with the len/cap header living at
                // *negative* offsets (-16/-8) from that same pointer
                // instead. RESIZE/URESIZE below were updated to match.
                long totalBytes = Long.parseLong(line.get(1).text);
                long count = totalBytes / 8;
                emitMallocCall(16 + totalBytes);
                raw(isWindows() ? "    mov r12, rax" : "    movq %rax, %r12");
                storeSizedToAddr_withOffsetImm("r12", 0, count, 8);
                storeSizedToAddr_withOffsetImm("r12", 8, count, 8);
                for (long i = count - 1; i >= 0; i--) {
                    popReg("rax");
                    long off = 16 + i * 8;
                    if (isWindows()) {
                        raw("    mov [r12" + signed(off) + "], rax");
                    } else {
                        raw("    movq %rax, " + off + "(%r12)");
                    }
                }
                raw(isWindows() ? "    add r12, 16" : "    addq $16, %r12"); // r12: now the data-start pointer
                pushReg("r12");
                return;
            }
            case "NEW_FROM_STRING": {
                // Builds a dynarray(u8-ish) from a string-pool id already
                // on the stack (its address, per the bare-name-PUSH
                // convention above) -- copies strlen(s)+ bytes... this
                // backend doesn't have strlen's result at codegen time,
                // so it calls the real, already-declared extern strlen
                // to get it, then allocates and copies via the same
                // dynarray header layout as NEW_DYN/NEW_UDYN.
                popReg("r12"); // address of the string literal
                // The argument register is an ABI question (win64: rcx,
                // SysV: rdi), not a syntax one -- argReg(0) resolves
                // that; hardcoding "rdi" here (as this used to) is only
                // right for `linux` and silently wrong for
                // `windows_gnu` (AT&T syntax, win64 ABI).
                if (isWindows()) {
                    raw("    mov " + argReg(0) + ", r12");
                } else {
                    raw("    movq %r12, %" + argReg(0));
                }
                emitAlignedCall(() -> emitCallByName("strlen"));
                // rax now holds the string length.
                raw(isWindows() ? "    mov r13, rax" : "    movq %rax, %r13"); // r13: length, callee-saved
                if (isWindows()) {
                    raw("    add rax, 16");
                } else {
                    raw("    addq $16, %rax");
                }
                if (isWindows()) {
                    raw("    mov " + argReg(0) + ", rax");
                } else {
                    raw("    movq %rax, %" + argReg(0));
                }
                emitAlignedCall(() -> emitCallByName("malloc"));
                raw(isWindows() ? "    mov r14, rax" : "    movq %rax, %r14"); // r14: new block
                if (isWindows()) {
                    raw("    mov [r14], r13");
                    raw("    mov [r14+8], r13");
                    raw("    lea rdi, [r14+16]");
                    raw("    mov rsi, r12");
                    raw("    mov rcx, r13");
                    raw("    rep movsb");
                } else {
                    raw("    movq %r13, (%r14)");
                    raw("    movq %r13, 8(%r14)");
                    raw("    leaq 16(%r14), %rdi");
                    raw("    movq %r12, %rsi");
                    raw("    movq %r13, %rcx");
                    raw("    rep movsb");
                }
                pushReg("r14");
                return;
            }
            case "RESIZE":
            case "URESIZE": {
                // RESIZE (safe, 3 popped values: fill, newCount, ptr) /
                // URESIZE (unsafe, 2 popped values: newCount, ptr, no
                // fill) -- both confirmed directly against
                // resize_uresize_lowering_test.caspien's own real
                // low-order output and BytecodeEmitter's own doc comment
                // for it. `elemSize` is the mnemonic's own trailing
                // operand. Reallocates to exactly newCount elements (no
                // growth slack, matching NEW_DYN/NEW_UDYN above), and
                // for the safe form, fills any newly-added slots
                // (oldLen..newCount) with the fill value.
                //
                // The safe (RESIZE) and unsafe (URESIZE) pointers use
                // *different* header conventions on purpose (see
                // NEW_UDYN's own doc comment): a safe dynarray's pointer
                // is the malloc'd block start (header at +0/+8, matching
                // NEW_DYN/LOOKUP_DYN), while an unsafe one's pointer is
                // the data start (header at -16/-8), because its direct
                // indexing goes through the header-agnostic
                // LOOKUP_ARRAY(_LHS) family. `dataStartPtr` tracks which
                // convention applies here.
                int elemSize = (int) Long.parseLong(line.get(1).text);
                boolean hasFill = mnemonic.equals("RESIZE");
                boolean dataStartPtr = !hasFill;
                // A fill value wider than one word (a struct element) is a
                // whole pushed block, nw words deep, ahead of newCount and
                // the old pointer; it stays on the real stack (rsp is
                // restored by emitAlignedCall) until the fill loop has
                // copied it. A fill of <= 8 bytes is one word, popped
                // into r14 and stored at exactly elemSize bytes below.
                final int fillWords = (elemSize + 7) / 8;
                final boolean wideFill = hasFill && elemSize > 8;
                if (wideFill) {
                    if (isWindows()) {
                        raw("    mov r12, [rsp+" + (fillWords * 8) + "]");
                        raw("    mov rax, [rsp+" + (fillWords * 8 + 8) + "]");
                    } else {
                        raw("    movq " + (fillWords * 8) + "(%rsp), %r12");
                        raw("    movq " + (fillWords * 8 + 8) + "(%rsp), %rax");
                    }
                } else {
                    if (hasFill) {
                        popReg("r14"); // fill value
                    }
                    popReg("r12"); // newCount
                    popReg("rax"); // old pointer (scratch; moved into the call's arg reg below)
                }
                if (dataStartPtr) {
                    raw(isWindows() ? "    sub rax, 16" : "    subq $16, %rax"); // rax: real block start
                }
                // oldLen must survive the emitAlignedCall below, which
                // internally saves/restores %rsp through %r13 -- so it
                // cannot be kept in r13 (that was the actual bug behind a
                // very confusing ghost-table-lock hang: r13 silently
                // turned into the saved pre-call %rsp value instead of
                // staying oldLen, and the fill loop below then computed
                // addresses from that garbage). rbx is free once the old
                // pointer's been moved into the call's argument register,
                // and emitAlignedCall never touches it, so oldLen lives
                // there instead across the call.
                loadSizedFromAddr("rbx", "rax", 8); // rbx = oldLen
                // The two argument registers are an ABI question (win64:
                // rcx/rdx, SysV: rdi/rsi), independent of syntax --
                // argReg(0)/argReg(1) resolve that; this used to hardcode
                // rdi/rsi in the AT&T branch, which is only right for
                // `linux` and silently wrong for `windows_gnu` (AT&T
                // syntax, win64 ABI).
                if (isWindows()) {
                    raw("    mov " + argReg(0) + ", rax"); // arg1 = old (block-start) pointer
                    raw("    mov " + argReg(1) + ", r12");
                    raw("    imul " + argReg(1) + ", " + elemSize);
                    raw("    add " + argReg(1) + ", 16");
                } else {
                    raw("    movq %rax, %" + argReg(0)); // arg1 = old (block-start) pointer
                    raw("    movq %r12, %" + argReg(1));
                    raw("    imulq $" + elemSize + ", %" + argReg(1) + ", %" + argReg(1));
                    raw("    addq $16, %" + argReg(1));
                }
                // realloc(oldBlockPtr, newTotalBytes) -- args already
                // staged into the ABI's own first two argument registers
                // just above (rdi/rsi for SysV, rcx/rdx for win64).
                emitAlignedCall(() -> emitCallByName("realloc"));
                raw(isWindows() ? "    mov r15, rax" : "    movq %rax, %r15"); // r15: new block-start pointer
                storeSizedToAddr_reg("r15", 0, "r12", 8);
                storeSizedToAddr_reg("r15", 8, "r12", 8);
                if (hasFill) {
                    // for (i = oldLen; i < newCount; i++) data[i] = fill;
                    String loop = newInternalLabel("resize_fill");
                    String end = newInternalLabel("resize_fill_end");
                    raw(loop + ":");
                    if (isWindows()) {
                        raw("    cmp rbx, r12");
                        raw("    jge " + end);
                        raw("    mov rax, rbx");
                        raw("    imul rax, " + elemSize);
                        raw("    lea rax, [r15+rax+16]");
                        if (wideFill) {
                            emitResizeWideFillCopy(elemSize, fillWords);
                        } else {
                            storeSizedToAddr("r14", "rax", elemSize);
                        }
                        raw("    inc rbx");
                    } else {
                        raw("    cmpq %r12, %rbx");
                        raw("    jge " + end);
                        raw("    movq %rbx, %rax");
                        raw("    imulq $" + elemSize + ", %rax, %rax");
                        raw("    leaq 16(%r15,%rax), %rax");
                        if (wideFill) {
                            emitResizeWideFillCopy(elemSize, fillWords);
                        } else {
                            storeSizedToAddr("r14", "rax", elemSize);
                        }
                        raw("    incq %rbx");
                    }
                    raw(isWindows() ? "    jmp " + loop : "    jmp " + loop);
                    raw(end + ":");
                    if (wideFill) {
                        long drop = fillWords * 8L + 16;
                        raw(isWindows() ? "    add rsp, " + drop : "    addq $" + drop + ", %rsp");
                    }
                }
                if (dataStartPtr) {
                    raw(isWindows() ? "    add r15, 16" : "    addq $16, %r15"); // r15: back to the data-start pointer this convention returns
                }
                pushReg("r15");
                return;
            }
            case "ATOMIC_PUSH": {
                // "ATOMIC_PUSH size globalName" -- an aligned load off a
                // global is already atomic on x86-64; no lock prefix
                // needed for a plain read.
                int size = (int) Long.parseLong(line.get(1).text);
                String name = line.get(2).text;
                leaGlobalToReg("rbx", name);
                loadSizedFromAddr("rax", "rbx", size);
                pushReg("rax");
                return;
            }
            case "ATOMIC_SWAP": {
                // Pops (top to bottom) newValue, address -- xchg with a
                // memory operand is *always* atomic on x86, no lock
                // prefix required -- and leaves the previous value in
                // the same register, which every real call site this
                // pass was checked against immediately compares against
                // something (confirmed directly).
                int size = (int) Long.parseLong(line.get(1).text);
                popReg("rax"); // new value
                popReg("rbx"); // address
                if (isWindows()) {
                    raw("    xchg [" + "rbx" + "], " + sizedRegWin("rax", size));
                } else {
                    raw("    xchg" + movSuffix(size) + " %" + sizedReg("rax", size) + ", (%rbx)");
                }
                pushReg("rax");
                return;
            }
            case "GT_INIT": {
                // The compiler's own ghost-table bootstrap opcode --
                // "gt_init" is itself a real, already-compiled function
                // sitting elsewhere in this exact same low-order file
                // (from the imported stdlib/gt_init.caspien), with no
                // ordinary CALL to it anywhere in the bytecode -- so
                // this opcode's job is to call it directly, confirmed
                // directly by the exact, unmistakable name match (and
                // the identical pattern for GT_ALIVE_CHECK/GT_DESTRUCT
                // below).
                emitAlignedCall(() -> emitCallByName("gt_init"));
                return;
            }
            case "GT_REGISTER": {
                // The fourth ghost-table opcode, added alongside this
                // case -- BytecodeEmitter's own "new" case now emits
                // this immediately after "NEW", confirmed directly:
                // "then I pass that malloced pointer thru the provided
                // gt_register function, before putting it on the
                // stack." Same shape as GT_ALIVE_CHECK just below (pop
                // the pointer into the first argument register, call
                // the real stdlib function by its own unmistakable
                // name), except gt_register's own return value is void
                // -- nothing useful ends up in rax after the call.
                //
                // The pointer surviving the call is kept on the real
                // stack, not in a register -- confirmed directly to be
                // necessary, not just cautious, by an actual segfault:
                // "callee-saved, survives the call" (the reasoning
                // "NEW"'s own case above relies on for r12/r14) only
                // holds when the callee is a real, externally-compiled C
                // function such as malloc, which genuinely honors the
                // calling convention's callee-saved registers. This
                // opcode's own callee, `gt_register`, is a Caspien
                // function compiled by this exact backend, and
                // `FUNC_START`/`FUNC_END` (above) never save or restore
                // r12-r15/rbx -- only rbp -- so nothing stops
                // `gt_register`'s own body (which itself calls
                // `gtSlotAt`/`gtReadSlot`/`gtWriteSlot`/`realloc`, each
                // freely reusing those same scratch registers) from
                // clobbering whatever this opcode left in one of them.
                // The real hardware stack has no such problem: a
                // properly-balanced callee (push/sub on the way in,
                // matching pop/add on the way out -- which is exactly
                // what every "FUNC_START"/"FUNC_END" pair here already
                // does) never disturbs anything sitting below its own
                // return address, so a second, throwaway copy of the
                // pointer pushed here for the call to consume leaves the
                // first copy sitting undisturbed underneath, already
                // exactly where the next real instruction expects "NEW"'s
                // own result to be.
                popReg("rax");
                pushReg("rax"); // the surviving copy -- left alone, underneath the call
                pushReg("rax"); // the throwaway copy -- this one becomes the argument
                popReg("rax");
                raw(isWindows() ? ("    mov " + argReg(0) + ", rax") : ("    movq %rax, %" + argReg(0)));
                emitAlignedCall(() -> emitCallByName("gt_register"));
                return;
            }
            case "GT_ALIVE_CHECK": {
                popReg("rax"); // the pointer being checked
                raw(isWindows() ? ("    mov " + argReg(0) + ", rax") : ("    movq %rax, %" + argReg(0)));
                emitAlignedCall(() -> emitCallByName("gt_alive_check"));
                pushReg("rax"); // its bool result
                return;
            }
            case "GT_DESTRUCT": {
                // Operand is an immediate stack offset, not a popped
                // value -- "GT_DESTRUCT $-240" -- read the pointer
                // *value* stored there (gt_destruct's own real parameter
                // is the pointer by value, confirmed directly from its
                // own body: "PUSH ARG0 / ASSIGN" storing its incoming
                // parameter to a local, ordinary by-value handling).
                String operand = line.get(1).text;
                if (operand.startsWith("$")) {
                    loadSizedFromFrame("rax", Long.parseLong(operand.substring(1)), 8);
                    raw(isWindows() ? ("    mov " + argReg(0) + ", rax") : ("    movq %rax, %" + argReg(0)));
                    emitAlignedCall(() -> emitCallByName("gt_destruct"));
                } else {
                    comment("TODO(codegen): 'GT_DESTRUCT " + operand + "' (non-stack-offset operand) not yet implemented");
                }
                return;
            }
            case "GT_DESTRUCT_ADDR": {
                // The pointer-crossing sibling of the plain, flat-offset
                // "GT_DESTRUCT $offset" case just above -- see
                // caspien-compiler's own CLAUDE.md, "GT_DESTRUCT
                // dotted-operand gap," for the front-end half of this
                // fix. Unlike that case, this operand is a type string
                // only (kept for this bytecode format's own "every
                // instruction states the type it operates on"
                // convention; not used for dispatch here -- an owns
                // pointer is always exactly 8 bytes regardless of what
                // it points to). The real work already happened on the
                // stack: `BytecodeEmitter.emitDestructOldOwnedValue`
                // computed this target's own real, possibly-pointer-
                // crossing ADDRESS via the same DOT_LHS/LOOKUP_LHS chain
                // an ordinary assignment target already uses, then
                // duplicated it with `DUP_TOP` -- one copy for this line
                // to consume, one left for the store that follows. So
                // where the plain `GT_DESTRUCT` case reads the pointer
                // value directly off a known, fixed frame offset
                // (`loadSizedFromFrame`), this one starts one level
                // further out: pop the ADDRESS itself, then dereference
                // it (`loadSizedFromAddr`) to get the same pointer value
                // `gt_destruct` always expects by value (confirmed
                // directly from its own body: "PUSH ARG0 / ASSIGN",
                // ordinary by-value handling -- identical to the plain
                // case above).
                popReg("rax"); // the computed address itself
                loadSizedFromAddr("rax", "rax", 8); // dereference: the owns pointer's own value
                raw(isWindows() ? ("    mov " + argReg(0) + ", rax") : ("    movq %rax, %" + argReg(0)));
                emitAlignedCall(() -> emitCallByName("gt_destruct"));
                return;
            }
            case "YIELD": {
                // "yield is intended to say 'I dont need my execution
                // time, im low priority, prioritize someone else'"
                // (caspien-compiler's own CLAUDE.md) -- a plain,
                // argument-less scheduling *hint*, not a delay: unlike
                // "sleep(...)" (a real "SLEEP" instruction just below,
                // naming the real "@sleep"-decorated function to call
                // directly), "yield" carries no duration at all and
                // never blocks the calling thread for any guaranteed
                // length of time -- it just gives up the rest of its
                // current scheduling turn, immediately eligible to run
                // again the instant nothing else wants the CPU.
                //
                // The real primitive for this is POSIX's own
                // "sched_yield(void)" -- confirmed empirically to link
                // and run correctly, with zero target-branching needed
                // at all, on *both* this backend's real targets: glibc
                // (linux) and mingw-w64's own libpthread/sched.h
                // compatibility layer (windows_gnu, already linked in
                // via "-pthread"/"-static" for pthread_create/join/exit)
                // both export it under this exact same name -- the
                // identical "one literal C symbol, no per-target
                // branching" shape "exit"/"pthread_exit" already
                // established just below. ("pthread_yield" -- the more
                // obvious-looking name, given "pthread_create"/"_join"/
                // "_exit" already being called this way -- was tried
                // first and rejected: it's a non-standard GNU extension
                // glibc happens to also provide, but mingw-w64's own
                // winpthreads does not export it at all, confirmed by a
                // real "undefined reference to 'pthread_yield'" link
                // failure under the windows_gnu target.) No arguments,
                // no return value read by anything -- "sched_yield"
                // itself returns an int (0 on success) that nothing
                // downstream of "yield" ever consumes.
                emitAlignedCall(() -> emitCallByName("sched_yield"));
                return;
            }
            case "SLEEP": {
                // "SLEEP sleep_call duration" -- confirmed directly: a
                // dedicated instruction, not an ordinary CALL, carrying
                // the real "@sleep"-decorated function's own name
                // directly as this line's second operand (resolved once,
                // by TypeChecker.checkSleepCall -- never a hardcoded
                // literal this backend has to already know, the exact
                // "resolved dynamically" property that keeps this immune
                // to the naming fragility the four original ghost-table
                // opcodes -- "GT_INIT" etc. -- once had; see
                // caspien-compiler's own CLAUDE.md, "a non-literal name
                // broke the linker"). Shaped like "GT_ALIVE_CHECK" just
                // above (pop the one pushed argument, move it into the
                // first argument register, call, push the real result
                // back) rather than the full CC_START/CC_END-wrapped
                // "CALL" machinery, since "@sleep" is required
                // (TypeChecker.requireSleepSignature) to take exactly
                // one plain "u64" and return exactly one plain "u64" --
                // always exactly 8 bytes each way, never anything this
                // fixed shape needs to branch on. The trailing operand
                // (the pushed duration argument's own resolved type) is
                // never actually read here at all -- carried purely for
                // the same "every instruction states the type of what it
                // operates on" documentation convention NEG/DEREF/CLONE/
                // LEN_SCAN already follow, "sleep is used like
                // sleep(duration) where duration is the duration type C
                // expects," confirmed directly.
                String realFuncName = line.get(1).text;
                popReg("rax"); // the pushed duration argument
                raw(isWindows() ? ("    mov " + argReg(0) + ", rax") : ("    movq %rax, %" + argReg(0)));
                emitAlignedCall(() -> emitCallByName(realFuncName));
                pushReg("rax"); // the real C "sleep"'s own u64 result (remaining, unslept seconds)
                return;
            }
            case "EXIT": {
                // Reached only when a THROW somewhere in this compilation
                // unit unwinds all the way up through every intervening
                // frame to the true root (main, or @event_loop) itself --
                // never reached by an ordinary, no-exception completion
                // of main, which always jumps clean over this whole label
                // (the "JMP end_of_gt_routine__main" emitted right after
                // it -- see emitGtRoutineBody) and never runs its body at
                // all. This can NOT simply do nothing and fall through:
                // the label immediately following this one in the
                // bytecode ("end_of_gt_routine__main:") is main's own
                // *ordinary body*, not a second copy of its epilogue --
                // falling through into it, as this case used to
                // (incorrectly) assume, would silently restart and re-run
                // main's entire body a second time the moment a real
                // throw ever actually unwound this far, rather than
                // ending the program. Confirmed against this project's
                // own gt_unwind_throw_multi_frame_cg_test.caspien fixture
                // doc comment: unwinding to the true root has its own
                // distinct, documented status -- "this codegen stage's
                // own chosen, distinct 'unwound to the true root
                // boundary' status," exit code 1 -- separate from a
                // normal, exception-free "exit 0" completion of main.
                // Calling the C library's own `exit` (already linked in
                // for malloc/printf/etc -- no separate EXTERN declaration
                // needed for codegen to call it by name) is the
                // straightforward way to actually end the process here,
                // with that exact distinct status.
                raw(isWindows() ? ("    mov " + argReg(0) + ", 1") : ("    movq $1, %" + argReg(0)));
                emitAlignedCall(() -> emitCallByName("exit"));
                return;
            }
            case "EXIT_THREAD": {
                // The real-OS-thread-backed "par"/"await" counterpart to
                // "EXIT" just above, emitted (unconditionally, see
                // caspien-compiler's own BytecodeEmitter.emitGtRoutineBody)
                // at the tail end of every "@async" function's own
                // gt_routine unwind label, in place of "EXIT" -- reached
                // the identical way "EXIT" is (an unwind that propagates
                // all the way up through every frame this thread's own
                // call stack has, to the very entry point pthread started
                // this thread at -- the compiler-synthesized
                // "__trampoline_<name>" function, never "main" itself,
                // since an "@async" function only ever runs on its own,
                // separate OS thread, started by "@par_call"/"@await_call"'s
                // own "pthread_create", never by the process's own
                // ordinary startup path). Ending the whole *process* here,
                // the way "EXIT" does via the C library's own "exit", would
                // be wrong: it would tear down every other thread
                // (including whichever one is "await"-ing or "par"-polling
                // this one) along with it, not just this one thread's own
                // execution. The real, ordinary way to end *one* thread
                // without ending the process is the C library's own
                // "pthread_exit" -- already linked in via "-pthread" (the
                // same "already linked in, no EXTERN declaration needed"
                // precedent "exit" itself already established just above;
                // "stdlib/libc.caspien"'s own comment on "pthread_exit"
                // confirms it's deliberately never declared there, called
                // directly by this exact case, by its own fixed, literal
                // C symbol name, for the identical reason). Passed a null
                // "void*" return value: nothing downstream (no real
                // "pthread_join" call site in "@await_call" ever reads
                // back a thread's own real exit-value pointer -- see
                // "stdlib/await_call.caspien"'s own "pthread_join(tid,
                // null)") ever consumes it.
                raw(isWindows() ? ("    mov " + argReg(0) + ", 0") : ("    movq $0, %" + argReg(0)));
                emitAlignedCall(() -> emitCallByName("pthread_exit"));
                return;
            }
            case "GT_UNWIND": {
                // "Make the assembly to return from a function as you
                // normally would, but then locate the first variable in
                // that stack frame (should be relative to base pointer?)
                // and set the instruction pointer to that," confirmed
                // directly (the Project's own notes on this, recovered
                // verbatim from compiler_master_code.md/emitGtRoutineBody
                // after this backend was found to have never implemented
                // it at all -- see this project's own CLAUDE.md).
                //
                // GT_UNWIND only ever appears inside this function's own
                // "gt_routine__<name>:" label, reached either by falling
                // through from a THROW originating somewhere in this same
                // function, or by a nested callee's own GT_UNWIND jumping
                // straight here (see below) -- in both cases every
                // GT_DESTRUCT this function's own gt_routine needed has
                // already run (emitGtRoutineBody emits them immediately
                // before this), so all that's left is to leave.
                //
                // "Return from a function as you normally would" is the
                // ordinary epilogue -- deallocate this frame (mov
                // rsp,rbp) and restore the *caller's* rbp (pop rbp) --
                // exactly what emitFunctionEpilogue does for a real RET.
                // The difference starts right after: a real RET would
                // now `ret` back into the caller's own body, to the
                // return address `call` pushed. That's precisely what
                // must NOT happen here -- this frame isn't returning
                // normally, it's unwinding, so the caller's own body must
                // never be resumed. The return address is discarded
                // unread (rsp bumped past it, exactly as if it had been
                // popped and thrown away) and control instead jumps
                // straight to the caller's own gt_routine label instead.
                //
                // "Locate the first variable in that stack frame" -- by
                // the time rbp has just been restored to the caller's
                // own value, "that stack frame" is the caller's, and its
                // first variable is always the caller's own
                // "gt_routine_address" slot (see emitGtRoutineAlloc's own
                // doc comment: reserved before any other local, in every
                // function that has one), which is confirmed, by
                // construction, to always sit at a fixed rbp-8 in every
                // such frame (verified directly against this project's
                // own real lowered bytecode: every function's first
                // "ALLOC ... code_addr" always lowers to exactly "$-8").
                // That slot already holds the address of the caller's own
                // gt_routine label -- written there by the caller's own
                // prologue (its own "ADDR gt_routine_address / PUSH_LABEL
                // .../ ASSIGN", emitted before this callee was ever
                // called) -- so reading it and jumping there is exactly
                // "set the instruction pointer to that."
                //
                // This chains correctly through an arbitrarily deep call
                // stack with no special-casing: the whole-program trigger
                // that gives a function its gt_routine machinery at all
                // (checker.usesOwnsRefDynNew() || checker.usesThrow(),
                // see emitGtRoutineAlloc) applies uniformly to *every*
                // function in a compilation unit that needs one, so the
                // caller reached here is always guaranteed to have its
                // own gt_routine_address slot at that same fixed offset
                // -- landing on the caller's own gt_routine label runs
                // *its* own GT_DESTRUCTs, then either unwinds one frame
                // further the same way, or (the true root -- main/
                // @event_loop/@async) terminates via EXIT/EXIT_THREAD
                // instead of a further GT_UNWIND.
                raw(CSR_MARK);
                // "GT_UNWIND MSG": the compiler reserved a second slot, gt_error_message, at rbp-16 in every frame (right behind
                // gt_routine_address; ArgToAllocLoweringPass keeps it ahead of the parameters). Carry this frame's message up to the
                // caller's slot, so a catch anywhere up the chain sees what was thrown. rax is free here (it is loaded with the jump
                // target below).
                boolean carryMessage = line.size() > 1 && "MSG".equals(line.get(1).text);
                if (carryMessage) {
                    movMemToReg("rax", -16);
                }
                if (isWindows()) {
                    raw("    mov rsp, rbp");
                } else {
                    raw("    movq %rbp, %rsp");
                }
                popReg("rbp");
                if (isWindows()) {
                    raw("    add rsp, 8"); // discard the return address -- never resumed
                } else {
                    raw("    addq $8, %rsp");
                }
                if (carryMessage) {
                    movRegToMem("rax", -16); // the caller's gt_error_message slot
                }
                movMemToReg("rax", -8); // the caller's own gt_routine_address slot
                if (isWindows()) {
                    raw("    jmp rax");
                } else {
                    raw("    jmp *%rax");
                }
                return;
            }
            case "THROW": {
                // UPDATE (per-throw-site/per-call-site unwinding): "THROW
                // string_id" used to carry a second operand (its own
                // enclosing function's whole-function "gt_routine" label)
                // and compiled to a plain unconditional "jmp" to it. That
                // label -- and the whole-function conservative destruct
                // list it guarded -- no longer exist at all (see the
                // sibling caspien-compiler project's own CLAUDE.md,
                // "Fixed: the 'GT_DESTRUCT dotted-operand gap'" era work
                // is unrelated; see instead "per-throw-site/per-call-site
                // unwinding" in that project's history for this specific
                // redesign). A throw's own destination is now resolved
                // entirely at compile time (always this exact lexical
                // point), so there is nothing left for THROW itself to
                // jump to -- the compiler now emits THROW's own precise
                // GT_DESTRUCT list, followed by GT_UNWIND/EXIT/EXIT_THREAD
                // as appropriate, as plain, ordinary lines immediately
                // following this one, using machinery this backend
                // already implements for every other destruct/unwind
                // site (return sites, call-site landing pads). THROW
                // itself is therefore a pure marker now -- a genuine
                // no-op here -- confirmed unchanged from before this
                // round: the thrown message was always inert at runtime
                // (never read, stored, or propagated -- there is still no
                // 'catch' construct anywhere in this language), so
                // dropping its own, now-obsolete control-transfer
                // responsibility loses nothing real.
                return;
            }
            case "RET": {
                // A void "RET 0" leaves %rax/%eax holding whatever the
                // last instruction happened to put there -- harmless
                // while every real test drove the compiled function
                // through a C harness that itself always returned 0,
                // but this program's own "main" is now linked (and its
                // real exit code inspected) directly, where the C
                // runtime reads %eax as the process exit code -- so a
                // void function now explicitly zeroes it, for a
                // predictable "exit 0" rather than whatever garbage a
                // caller wasn't meant to look at.
                long size = Long.parseLong(line.get(1).text);
                if (size > 0) {
                    popReg("rax");
                } else {
                    movImmToReg("rax", 0);
                }
                // RET can occur anywhere in a function's body -- nested
                // inside an if/match/loop, not just as the unconditional
                // final statement immediately before FUNC_END. It has to
                // actually leave the function *here*, at this exact
                // point, rather than falling through into whatever
                // merge-label/JMP scaffolding the surrounding control
                // flow emits next: falling through let later code
                // silently overwrite this return value (and, for a
                // conditional return, run the rest of the function body
                // it was meant to skip) before the real epilogue+ret
                // ever executed, a real bug found via a return nested
                // inside a chain of plain (no-else) ifs. The identical
                // epilogue FUNC_END emits at the function's true textual
                // end is emitted again here -- harmless dead code where
                // RET already was the last statement (FUNC_END's own
                // copy simply becomes unreachable), correct and required
                // everywhere else.
                emitFunctionEpilogue();
                return;
            }
            case "RET_FLOAT": {
                // The float return-value convention (`codegen.config`'s
                // own `return-register-float: XMM0`, shared by both win64
                // and SysV) puts the result in xmm0, not rax -- the one
                // real difference from plain RET. Never reached for a
                // void return (RET_FLOAT only exists for a real,
                // non-void, f32-returning function), so there's no
                // "leave it zeroed" case to mirror RET's own.
                popReg("rax");
                movRegToXmm("rax", "xmm0");
                // See RET's own identical comment just above -- the same
                // "must actually leave the function here" fix applies
                // equally to a float return.
                emitFunctionEpilogue();
                return;
            }
            case "VARARGS_XMM_COUNT": {
                // Pure compile-time metadata, emitted by the front end
                // immediately before a real variadic call's own
                // "CALL"/"INVOKE" line (never for an ordinary, wholly
                // fixed-arity call) -- see `pendingVarargsXmmCount`'s own
                // doc comment for why this needs to travel through the
                // bytecode at all (the SysV "%al = vector registers used"
                // varargs rule) rather than being computed here. Emits no
                // assembly of its own -- just records the value for
                // whichever call site's own preamble consumes it next.
                pendingVarargsXmmCount = Integer.parseInt(line.get(1).text);
                return;
            }
            case "CALL": {
                String name = line.get(1).text;
                if (name.startsWith("__drop_") && callBufferStack.isEmpty()) {
                    emitDropGlueCall(name);
                    return;
                }
                resolveStackArgBytesAndCall(() -> emitCallByName(name));
                return;
            }
            case "INVOKE": {
                // The function pointer value was pushed *before* the
                // preceding "POP ARGn" sequence, so it's what's left on
                // top of the stack right now -- pop it into a scratch
                // register not used by any argument slot and call
                // through it. The numeric operand (arg count) is purely
                // informational here.
                popReg("r10");
                resolveStackArgBytesAndCall(() -> emitCallIndirect("r10"));
                return;
            }
            case "POP": {
                String dest = line.get(1).text;
                int size = (int) Long.parseLong(line.get(2).text);
                if (dest.startsWith("ARG")) {
                    int idx = Integer.parseInt(dest.substring(3));
                    popReg(argReg(idx));
                    noteArgRegLoaded("i" + idx);
                } else if (dest.startsWith("FARG")) {
                    // The float-bank counterpart of "ARG" just above --
                    // there's no real "pop directly into an xmm register"
                    // instruction, so the value comes off the ordinary
                    // GPR-based value stack into rax first, then across
                    // into the real argument xmm register via the same
                    // bit-copying `movq` every other float op here uses.
                    int idx = Integer.parseInt(dest.substring(4));
                    popReg("rax");
                    movRegToXmm("rax", argFloatReg(idx));
                    noteArgRegLoaded("f" + idx);
                } else if (dest.startsWith("$")) {
                    long addr = Long.parseLong(dest.substring(1));
                    if (size <= 8) {
                        popReg("rax");
                        storeSizedToFrame("rax", addr, size);
                    } else {
                        // Mirror of DEREF's own multi-word push, above:
                        // the *first* pop is the highest-address word
                        // (last pushed), stored at addr+size-8, working
                        // back down to addr+0 for the final pop.
                        long words = (size + 7) / 8;
                        for (long i = 0; i < words; i++) {
                            popReg("rax");
                            storeSizedToFrame("rax", addr + size - 8 - i * 8, 8);
                        }
                    }
                } else {
                    comment("TODO(codegen): 'POP " + dest + " " + size + "' not yet implemented");
                }
                return;
            }
            case "PUSH_LABEL": {
                String name = line.get(1).text;
                leaGlobalToReg("rax", mangleLabel(name));
                pushReg("rax");
                return;
            }
            case "PUSH_RET_INT": {
                pushReg("rax"); // the call result already sitting in rax
                return;
            }
            case "PUSH_RET_FLOAT": {
                // The caller-side mirror of RET_FLOAT: a just-called
                // function's float result is sitting in xmm0 (the same
                // shared win64/SysV convention), not rax -- move it over
                // and push it exactly like PUSH_RET_INT does for rax.
                if (floatResultInR10) {
                    // xmm0 was already restored for the enclosing call's
                    // argument 0 (see "CC_END"); the real result is in r10.
                    raw(isWindows() ? "    mov rax, r10" : "    movq %r10, %rax");
                    floatResultInR10 = false;
                } else {
                    movXmmToReg("xmm0", "rax");
                }
                pushReg("rax");
                return;
            }
            case "ASM_START": {
                // Everything from here to the matching "ASM_END" is
                // opaque, verbatim assembly text (see AsmInfo/emitAsm in
                // the front end -- this compiler never inspects an ASM
                // block's content beyond that one reservation check, and
                // no lowering pass touches it either), so there is
                // nothing to resolve: copy each raw line through
                // unchanged, in order, and skip past them here so the
                // main dispatch above doesn't also see them as ordinary
                // bytecode lines.
                int end = currentLineIndex + 1;
                while (end < allLines.size()) {
                    List<BytecodeToken> candidate = allLines.get(end);
                    if (candidate.size() == 1 && candidate.get(0).text.equals("ASM_END")) {
                        break;
                    }
                    end++;
                }
                for (int i = currentLineIndex + 1; i < end; i++) {
                    raw(String.join(" ", textsOf(allLines.get(i))));
                }
                asmSkipUntilIndex = end; // skips the raw lines and ASM_END itself
                return;
            }
            case "ASM_END":
                // Reached only for a malformed/unpaired block (no matching
                // ASM_START skipped it first) -- nothing to emit either way.
                return;
            case "FLOAT_CHECK": {
                // "PUSH f / FLOAT_CHECK finite|nan|..." -- a float
                // classification test (never parsed from real source; see
                // BytecodeEmitter's own doc comment on this operator).
                // Only ever reached against a genuine 4-byte value:
                // "finite"/"infinite"/"nan" are only ever attached to a
                // bare 'f32' condition (TypeChecker's own
                // requireF32ForFloatState-style gate, checked directly at
                // FLOAT_STATE_NAMES/PRIMITIVE_SIZE), and 'f32' is the
                // *only* floating-point type this language has at all --
                // no 'f64' exists anywhere in caspien-compiler, confirmed
                // by grep -- so there is no second width this could ever
                // arrive as, unlike every genuinely dual-width _FLOAT op
                // above.
                //
                // IEEE-754 single precision: the top bit is sign (masked
                // off below), and a value is classified purely by
                // comparing what's left against the all-ones-exponent,
                // zero-mantissa bit pattern (0x7f800000, i.e.
                // 2139095040): strictly less is finite (every ordinary
                // value, subnormal, or zero), exactly equal is +-Infinity,
                // strictly greater is any NaN payload. Each requested
                // category (op.right.text is a "|"-joined, never-empty,
                // never-all-three subset -- see Token.MatchPattern's own
                // doc comment) contributes one setcc reading the *same*
                // comparison's flags -- untouched by an intervening setcc,
                // so one cmp legitimately serves all of them -- OR'd
                // together into a single 0/1 result for the CMP/JMP that
                // always follows a match branch condition.
                String[] categories = line.get(1).text.split("\\|");
                boolean f64Check = line.size() > 2 && line.get(2).text.equals("8");
                popReg("rax");
                if (f64Check) {
                    // double: strip the sign bit (bit 63) by shifting it out, compare against the +-Inf exponent pattern 0x7ff0000000000000
                    raw(isWindows() ? "    btr rax, 63" : "    btrq $63, %rax");
                    movImmToReg("rdx", 0x7ff0000000000000L);
                    raw(isWindows() ? "    cmp rax, rdx" : "    cmpq %rdx, %rax");
                } else {
                raw(isWindows() ? "    and eax, 2147483647" : "    andl $2147483647, %eax"); // strip the sign bit -> |bits|
                raw(isWindows() ? "    cmp eax, 2139095040" : "    cmpl $2139095040, %eax"); // vs. the shared +-Inf/NaN exponent pattern
                }
                boolean firstCategory = true;
                for (String category : categories) {
                    String setcc = category.equals("finite") ? "setb"
                            : category.equals("infinite") ? "sete"
                            : "seta"; // "nan"
                    String reg = firstCategory ? "rax" : "rdx";
                    if (isWindows()) {
                        raw("    " + setcc + " " + sizedRegWin(reg, 1));
                        raw("    movzx " + reg + ", " + sizedRegWin(reg, 1));
                    } else {
                        raw("    " + setcc + " %" + sizedReg(reg, 1));
                        raw("    movzbq %" + sizedReg(reg, 1) + ", %" + reg);
                    }
                    if (!firstCategory) {
                        raw(isWindows() ? "    or rax, rdx" : "    orq %rdx, %rax");
                    }
                    firstCategory = false;
                }
                pushReg("rax");
                return;
            }
            case "CC_START": {
                // Opens a fresh buffer/tally level for this call's own
                // argument marshalling -- nothing is emitted immediately
                // (unlike the old, pure-no-op version of this case): the
                // alignment reservation this call may or may not need
                // can't be sized correctly until its own real stack-
                // passed-argument byte count is known, which only happens
                // once every argument has been produced, at this same
                // call's own "CALL"/"INVOKE" line
                // (`resolveStackArgBytesAndCall`). See
                // `argTrackDeltaStack`'s own doc comment for the full
                // design and why a naive, immediate reservation here (the
                // shape this case used to have) is exactly the bug this
                // closes.
                // If this call is nested inside another call's argument
                // list, save the registers that outer call has already
                // loaded: this call is about to set up its own arguments in
                // the same physical registers. (These pushes land in the
                // enclosing call's buffer and tally; the matching pops at
                // this call's "CC_END" cancel them exactly, so the outer
                // call's stack-passed-byte accounting is unchanged.)
                java.util.List<String> saved = new java.util.ArrayList<>();
                if (!loadedArgRegsStack.isEmpty()) {
                    saved.addAll(loadedArgRegsStack.peek());
                }
                for (String r : saved) {
                    if (r.charAt(0) == 'i') {
                        pushReg(argReg(Integer.parseInt(r.substring(1))));
                    } else {
                        movXmmToReg(argFloatReg(Integer.parseInt(r.substring(1))), "r11");
                        pushReg("r11");
                    }
                }
                savedOuterArgRegsStack.push(saved);
                loadedArgRegsStack.push(new java.util.ArrayList<>());
                callBufferStack.push(new StringBuilder());
                argTrackDeltaStack.push(0L);
                return;
            }
            case "CC_END": {
                // Closes out this call's own buffer/tally level (opened
                // at the matching "CC_START") and flushes its now-
                // complete text (reservation, if any, plus every
                // argument-transfer/call/restore instruction emitted
                // since) into whatever was the active sink one level up
                // -- the enclosing call's own still-open buffer, if this
                // call was itself nested inside another call's own
                // argument expression, or `out` directly otherwise.
                StringBuilder finished = callBufferStack.pop();
                argTrackDeltaStack.pop();
                loadedArgRegsStack.pop();
                currentSink().append(finished);
                // Restore the enclosing call's already-loaded argument
                // registers (saved at this call's "CC_START"), in reverse
                // order. rax (an integer result) is never touched; r11 is
                // the scratch for float-bank restores; a possible float
                // result in xmm0 is stashed in r10 first, only when xmm0
                // itself is about to be overwritten.
                java.util.List<String> saved = savedOuterArgRegsStack.pop();
                if (saved.contains("f0")) {
                    movXmmToReg("xmm0", "r10");
                    floatResultInR10 = true;
                }
                for (int k = saved.size() - 1; k >= 0; k--) {
                    String r = saved.get(k);
                    if (r.charAt(0) == 'i') {
                        popReg(argReg(Integer.parseInt(r.substring(1))));
                    } else {
                        popReg("r11");
                        movRegToXmm("r11", argFloatReg(Integer.parseInt(r.substring(1))));
                    }
                }
                return;
            }
            case "EXPORT":
                // EXPORT's own target function already gets a real
                // .globl/PUBLIC from its own FUNC_START.
                return;
            case "GLOBAL":
            case "ALLOC_STATIC":
            case "STRING":
            case "EXTERN":
                // Already handled in the data-section pre-pass -- ELF
                // resolves an EXTERN'd symbol via the linker automatically,
                // with no explicit declaration needed in GAS.
                return;
            default:
                comment("TODO(codegen): '" + String.join(" ", textsOf(line)) + "' not yet implemented");
                return;
        }
    }

    private void storeSizedToAddr_withOffsetImm(String baseReg64, long offset, long value, int size) {
        if (isWindows()) {
            raw("    mov qword ptr [" + baseReg64 + signed(offset) + "], " + value);
        } else {
            raw("    movq $" + value + ", " + offset + "(%" + baseReg64 + ")");
        }
    }

    private void storeSizedToAddr_reg(String baseReg64, long offset, String srcReg64, int size) {
        if (isWindows()) {
            raw("    mov [" + baseReg64 + signed(offset) + "], " + srcReg64);
        } else {
            raw("    movq %" + srcReg64 + ", " + offset + "(%" + baseReg64 + ")");
        }
    }

    /**
     * A drop-glue call (`__drop_<T>`) is a deliberately minimal, ad hoc
     * shape: the LowerOrderGenerator emits a bare `PUSH pointer` /
     * `CALL __drop_T` with no `CC_START`/`CC_END`, and the routine reads
     * its one parameter as stack word 0 (`PUSH $16`), i.e. the word
     * immediately above the return address. The generic aligned-call
     * wrapper puts its alignment reservation between the pushed word and
     * the return address, so the routine read an uninitialised slot
     * instead of its pointer (it only worked when stale stack contents
     * happened to match -- a nested drop such as String -> DynamicArray
     * dereferenced null). Here the argument is popped, rsp is aligned
     * with the original rsp saved in a slot below the argument, the
     * argument is pushed back so it sits directly above the return
     * address, and rsp lands 16-aligned at the `call`.
     */
    private void emitDropGlueCall(String name) {
        popReg("rax");
        if (isWindows()) {
            raw("    mov rdx, rsp");
            raw("    and rsp, -16");
            raw("    sub rsp, 8");
            raw("    mov [rsp], rdx");
        } else {
            raw("    movq %rsp, %rdx");
            raw("    andq $-16, %rsp");
            raw("    subq $8, %rsp");
            raw("    movq %rdx, (%rsp)");
        }
        pushReg("rax");
        emitCallByName(name);
        if (isWindows()) {
            raw("    add rsp, 8");
            raw("    mov rsp, [rsp]");
        } else {
            raw("    addq $8, %rsp");
            raw("    movq (%rsp), %rsp");
        }
    }

    /**
     * RESIZE's fill loop for an element wider than one word: copies the
     * fill block still sitting on the stack (word k of the value is at
     * rsp + (words-1-k)*8, the same reversed-word convention
     * pushBlockFromFrame uses) into the element whose address is in rax,
     * in natural ascending byte order. Clobbers rdx.
     */
    private void emitResizeWideFillCopy(int elemSize, int words) {
        for (int k = 0; k < words; k++) {
            long srcOff = (long) (words - 1 - k) * 8;
            int n = Math.min(8, elemSize - 8 * k);
            if (isWindows()) {
                raw("    mov rdx, [rsp+" + srcOff + "]");
            } else {
                raw("    movq " + srcOff + "(%rsp), %rdx");
            }
            long dst = 8L * k;
            for (int b = 0; b < n;) {
                int chunk = n - b >= 8 ? 8 : n - b >= 4 ? 4 : n - b >= 2 ? 2 : 1;
                if (isWindows()) {
                    raw("    mov " + (chunk == 8 ? "qword" : winPtrSize(chunk)) + " ptr [rax+" + (dst + b) + "], " + sizedReg("rdx", chunk));
                } else {
                    raw("    mov" + movSuffix(chunk) + " %" + sizedReg("rdx", chunk) + ", " + (dst + b) + "(%rax)");
                }
                if (b + chunk < n) {
                    raw(isWindows() ? "    shr rdx, " + (chunk * 8) : "    shrq $" + (chunk * 8) + ", %rdx");
                }
                b += chunk;
            }
        }
    }

    private void emitMallocCall(long size) {
        // The argument register is an ABI question (win64: rcx, SysV:
        // rdi), independent of syntax -- argReg(0) already resolves
        // that correctly (see its own doc comment); previously this
        // hardcoded "rdi" in the AT&T branch, which is only right for
        // `linux` and silently wrong for `windows_gnu` (AT&T syntax,
        // but win64 ABI) -- confirmed directly by a real crash: malloc
        // read its size out of whatever garbage was sitting in rcx
        // instead of the real size sitting in rdi, and the resulting
        // wild/failed allocation surfaced downstream as a null-pointer
        // or wild-pointer fault (`NEW`'s own "rep movsb", most
        // visibly).
        if (isWindows()) {
            raw("    mov " + argReg(0) + ", " + size);
        } else {
            raw("    movq $" + size + ", %" + argReg(0));
        }
        emitAlignedCall(() -> emitCallByName("malloc"));
    }

    private boolean lastWasCmp = false;
    /** See the "value-block" handling threaded through PUSH/LOOKUP_ARRAY/DOT: the byte size of a multi-word value most recently pushed *by value* (as opposed to by address), still sitting on top of the real stack, for a following LOOKUP_ARRAY/DOT to extract a sub-range from -- 0 means "no pending value-block; use the ordinary address-based path." */
    private long lastValueBlockSize = 0;
    private String currentFuncName;
    private int internalLabelCounter = 0;

    private String newInternalLabel(String prefix) {
        return "L_cg_" + prefix + "_" + (internalLabelCounter++);
    }

    /** "@name" -> "L_name" (a real label can't contain '@'); a bare name with no '@' passes through unchanged -- the identical rule a label *definition* and every JMP/PUSH_LABEL *reference* to it must both apply, so they always agree. */
    // ---- Register-form instructions (R_MOV / R_BIN / R_UN / R_BRF / R_PUSH) ----
    //
    // Emitted by the LowerOrderGenerator's RegisterFormPass (deferred operands). Operands:
    //   #imm  an immediate      $off  an 8-byte frame slot      %tN  a temporary register
    // Temps are only ever live between register-form instructions -- the pass writes every one
    // back to the real stack (R_PUSH) before any other instruction -- so the backend's own
    // sequences (which use rax/rbx/r11-r15 internally) can never collide with a live temp. The
    // pool avoids every register that can be live ACROSS instructions: the argument registers of
    // an enclosing call (both ABIs), r10 (INVOKE target / float-result stash) and xmm registers.
    // r15 is the spare scratch, used only inside a single register-form instruction.

    private static final String[] RF_TEMP_REGS = { "rax", "rbx", "r11", "r12" };
    private static final String RF_SCRATCH = "r15";
    /** %v0..%v2: promoted variables (RegVarPromotionPass), callee-saved, so finishCalleeSaved saves/restores them like any other.
     *  %v3..%v5 (r8, r9, r10) are caller-saved: the promotion only puts a variable there that is never live across a call or any
     *  line that could clobber them. */
    private static final String[] RF_VAR_REGS = { "r13", "r14", "r12", "r8", "r9", "r10" };
    /** set when this function uses a %vN; checked against rfClobberSeen at FUNC_END */
    private boolean rfVarUsed = false;
    /** set when this function has an instruction whose code uses r13/r14 internally (see RegVarPromotionPass.R13_R14_USERS) */
    private boolean rfClobberSeen = false;
    private static final java.util.Set<String> RF_R13_R14_USERS = new java.util.HashSet<>(java.util.Arrays.asList(
            "NEW", "NEW_DYN", "NEW_UDYN", "NEW_FROM_STRING", "CLONE", "RESIZE", "URESIZE", "DOT", "LOOKUP_ARRAY", "ASM_START"));

    private String rfReg(String tok) {
        if (tok.equals("%s")) {
            return RF_SCRATCH; // the spare scratch register, usable as an operand only inside the backend
        }
        if (tok.startsWith("%v")) {
            int vi = Integer.parseInt(tok.substring(2));
            if (vi < 3) {
                rfVarUsed = true; // %v3..%v5 are r8/r9/r10: caller-saved, no save/restore, no clash with the r13/r14 scratch uses
            }
            return RF_VAR_REGS[vi];
        }
        return RF_TEMP_REGS[Integer.parseInt(tok.substring(2))];
    }

    /** a register operand: a temp, the scratch register or a promoted variable */
    private static boolean rfIsTemp(String tok) {
        return tok.startsWith("%t") || tok.startsWith("%v") || tok.equals("%s");
    }

    private static boolean rfIsVar(String tok) {
        return tok.startsWith("%v");
    }

    private static boolean rfIsImm(String tok) {
        return tok.startsWith("#");
    }

    private static boolean rfIsSlot(String tok) {
        return tok.startsWith("$");
    }

    private static long rfImm(String tok) {
        String t = tok.substring(1);
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return Long.parseUnsignedLong(t);
        }
    }

    private static long rfSlot(String tok) {
        return Long.parseLong(tok.substring(1));
    }

    private static boolean rfFitsImm32(long v) {
        return v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE;
    }

    private String rfRegText(String reg) {
        return isWindows() ? reg : ("%" + reg);
    }

    private String rfMemText(long off) {
        return isWindows() ? ("qword ptr [rbp" + signed(off) + "]") : (off + "(%rbp)");
    }

    /** Source-operand text for a two-operand instruction; a 64-bit immediate that will not fit imm32 goes through the scratch register. */
    private String rfSrc(String tok) {
        if (rfIsTemp(tok)) {
            return rfRegText(rfReg(tok));
        }
        if (rfIsSlot(tok)) {
            return rfMemText(rfSlot(tok));
        }
        long v = rfImm(tok);
        if (!rfFitsImm32(v)) {
            movImmToReg(RF_SCRATCH, v);
            return rfRegText(RF_SCRATCH);
        }
        return isWindows() ? String.valueOf(v) : ("$" + v);
    }

    /** "op src, dst" in the active syntax (AT&T gets the q suffix and reversed operand order). */
    private void rfOp2(String op, String dstText, String srcText) {
        raw(isWindows() ? ("    " + op + " " + dstText + ", " + srcText) : ("    " + op + "q " + srcText + ", " + dstText));
    }

    private void rfRegToReg(String dst, String src) {
        if (!dst.equals(src)) {
            raw(isWindows() ? ("    mov " + dst + ", " + src) : ("    movq %" + src + ", %" + dst));
        }
    }

    private void rfMov(String dst, String src) {
        if (rfIsTemp(dst)) {
            String d = rfReg(dst);
            if (rfIsImm(src)) {
                movImmToReg(d, rfImm(src));
            } else if (rfIsSlot(src)) {
                movMemToReg(d, rfSlot(src));
            } else {
                rfRegToReg(d, rfReg(src));
            }
            return;
        }
        long off = rfSlot(dst);
        if (rfIsTemp(src)) {
            movRegToMem(rfReg(src), off);
        } else if (rfIsImm(src)) {
            long v = rfImm(src);
            if (rfFitsImm32(v)) {
                raw(isWindows() ? ("    mov qword ptr [rbp" + signed(off) + "], " + v) : ("    movq $" + v + ", " + off + "(%rbp)"));
            } else {
                movImmToReg(RF_SCRATCH, v);
                movRegToMem(RF_SCRATCH, off);
            }
        } else {
            movMemToReg(RF_SCRATCH, rfSlot(src));
            movRegToMem(RF_SCRATCH, off);
        }
    }

    /**
     * "R_BIN SHL|SHR|SAR size %tD a b" -- %tD = a shifted by b, with the same single semantics as the stack form (see its SHL case):
     * the count is the unsigned value of the operand's own width, a count >= the width gives 0 (SHL/SHR) or the sign fill (SAR).
     * A constant count is resolved here at compile time (no %cl, no clamp unless it is >= 64); a variable count goes through %rcx,
     * which is an argument register that may be live, so it is saved and restored around the shift.
     */
    private void rfShift(String op, int size, String dstTok, String aTok, String bTok) {
        String d = rfReg(dstTok);
        boolean aIsD = rfIsTemp(aTok) && rfReg(aTok).equals(d);
        if (rfIsImm(bTok)) {
            long c = rfExtImm(rfImm(bTok), size, false);
            boolean wide = Long.compareUnsigned(c, 63) > 0;
            if (!aIsD) {
                rfMov(dstTok, aTok);
            }
            if (wide && !op.equals("SAR")) {
                // shifted out entirely
                raw(isWindows() ? ("    xor " + sizedReg(d, 4) + ", " + sizedReg(d, 4)) : ("    xorl %" + sizedReg(d, 4) + ", %" + sizedReg(d, 4)));
                return;
            }
            if (wide) {
                c = 63;
            }
            if (op.equals("SHR")) {
                zeroExtendReg(d, size);
            } else if (op.equals("SAR")) {
                signExtendReg(d, size);
            }
            if (c != 0) {
                String sop = op.equals("SHL") ? "shl" : op.equals("SHR") ? "shr" : "sar";
                raw(isWindows() ? ("    " + sop + " " + d + ", " + c) : ("    " + sop + "q $" + c + ", %" + d));
            }
            if (!op.equals("SHR")) {
                zeroExtendReg(d, size);
            }
            return;
        }
        // variable count: it goes into %rcx first (b may sit in d's own register, which the move of `a` below overwrites)
        pushReg("rcx");
        if (rfIsTemp(bTok)) {
            rfRegToReg("rcx", rfReg(bTok));
        } else {
            movMemToReg("rcx", rfSlot(bTok));
        }
        zeroExtendReg("rcx", size);
        if (!aIsD) {
            rfMov(dstTok, aTok);
        }
        emitShiftCore(op, size, d);
        popReg("rcx");
    }

    private void rfBin(String op, int size, String dstTok, String aTok, String bTok) {
        if (op.equals("SHL") || op.equals("SHR") || op.equals("SAR")) {
            rfShift(op, size, dstTok, aTok, bTok);
            return;
        }
        String d = rfReg(dstTok);
        boolean commutative = op.equals("ADD") || op.equals("MUL") || op.equals("AND") || op.equals("OR")
                || op.equals("BAND") || op.equals("BOR") || op.equals("BXOR") || op.equals("EQ") || op.equals("NEQ");
        String bText = null;
        if (rfIsTemp(bTok) && rfReg(bTok).equals(d) && !(rfIsTemp(aTok) && rfReg(aTok).equals(d))) {
            // The destination register is b's own register: a plain "d = a; d op= b" would overwrite b first.
            if (commutative) {
                String t = aTok;
                aTok = bTok;
                bTok = t;
            } else {
                rfRegToReg(RF_SCRATCH, d);
                bText = rfRegText(RF_SCRATCH);
            }
        }
        boolean narrowCmp = size < 8 && !(op.equals("ADD") || op.equals("SUB") || op.equals("MUL") || op.equals("AND") || op.equals("OR")
                || op.equals("BAND") || op.equals("BOR") || op.equals("BXOR"));
        boolean signedCmp = narrowCmp && op.charAt(0) == 'S';
        if (narrowCmp) {
            // A narrow compare looks at the low `size` bytes only: constants are cut down at compile time,
            // registers extended (the stack form's zeroExtendReg/signExtendReg on both operands).
            if (rfIsImm(aTok)) {
                aTok = "#" + rfExtImm(rfImm(aTok), size, signedCmp);
            }
            if (rfIsImm(bTok)) {
                bTok = "#" + rfExtImm(rfImm(bTok), size, signedCmp);
            }
        }
        if (!(rfIsTemp(aTok) && rfReg(aTok).equals(d))) {
            rfMov(dstTok, aTok);
        }
        if (bText == null) {
            bText = rfSrc(bTok);
        }
        if (narrowCmp) {
            rfExtend(d, size, signedCmp);
            if (bText.equals(rfRegText(RF_SCRATCH))) {
                rfExtend(RF_SCRATCH, size, signedCmp);
            } else if (rfIsTemp(bTok) && !rfReg(bTok).equals(d)) {
                rfExtend(rfReg(bTok), size, signedCmp);
            }
        }
        String dText = rfRegText(d);
        switch (op) {
            case "ADD":
                rfOp2("add", dText, bText);
                return;
            case "SUB":
                rfOp2("sub", dText, bText);
                return;
            case "MUL":
                rfOp2("imul", dText, bText);
                return;
            case "BAND":
            case "BOR":
            case "BXOR":
                // bitwise and / or / xor (BAND is also x % 2^n); the mask is a small immediate or a register. A narrow result is
                // cut back to the operand width, so the temp holds the zero-extended value (the entry is marked zx by the pass).
                rfOp2(op.equals("BAND") ? "and" : op.equals("BOR") ? "or" : "xor", dText, bText);
                zeroExtendReg(d, size);
                return;
            case "AND":
            case "OR": {
                String o = op.equals("AND") ? "and" : "or";
                if (size == 1) {
                    // Booleans: both operands are register-held 0/1 results; byte-wide, like the stack form.
                    String bReg = rfIsTemp(bTok) ? rfReg(bTok) : d;
                    if (isWindows()) {
                        raw("    " + o + " " + sizedReg(d, 1) + ", " + sizedReg(bReg, 1));
                    } else {
                        raw("    " + o + "b %" + sizedReg(bReg, 1) + ", %" + sizedReg(d, 1));
                    }
                } else {
                    rfOp2(o, dText, bText);
                }
                return;
            }
            default:
                break;
        }
        String setcc;
        switch (op) {
            case "EQ": setcc = "sete"; break;
            case "NEQ": setcc = "setne"; break;
            case "LT": setcc = "setb"; break;
            case "LT_EQ": setcc = "setbe"; break;
            case "GT": setcc = "seta"; break;
            case "GT_EQ": setcc = "setae"; break;
            case "SLT": setcc = "setl"; break;
            case "SLT_EQ": setcc = "setle"; break;
            case "SGT": setcc = "setg"; break;
            case "SGT_EQ": setcc = "setge"; break;
            default:
                throw new IllegalStateException("unknown R_BIN operator '" + op + "'");
        }
        rfOp2("cmp", dText, bText);
        String d8 = sizedReg(d, 1);
        if (isWindows()) {
            raw("    " + setcc + " " + d8);
            raw("    movzx " + d + ", " + d8);
        } else {
            raw("    " + setcc + " %" + d8);
            raw("    movzbq %" + d8 + ", %" + d);
        }
    }

    /** Jump to `label` when NOT (a op b), for an 8-byte compare; a and b are %t/%v registers, $off slots or #imm (never both #imm). */
    private void rfBrc(String op, String aTok, String bTok, String label) {
        String cc; // the jump taken when the comparison is false
        switch (op) {
            case "EQ": cc = "jne"; break;
            case "NEQ": cc = "je"; break;
            case "LT": cc = "jae"; break;
            case "LT_EQ": cc = "ja"; break;
            case "GT": cc = "jbe"; break;
            case "GT_EQ": cc = "jb"; break;
            case "SLT": cc = "jge"; break;
            case "SLT_EQ": cc = "jg"; break;
            case "SGT": cc = "jle"; break;
            case "SGT_EQ": cc = "jl"; break;
            default:
                throw new IllegalStateException("unknown R_BRC operator '" + op + "'");
        }
        if (rfIsImm(aTok)) {
            // "cmp imm, x" does not exist: swap the operands and mirror the condition.
            String t = aTok;
            aTok = bTok;
            bTok = t;
            switch (cc) {
                case "jae": cc = "jbe"; break;
                case "ja": cc = "jb"; break;
                case "jbe": cc = "jae"; break;
                case "jb": cc = "ja"; break;
                case "jge": cc = "jle"; break;
                case "jg": cc = "jl"; break;
                case "jle": cc = "jge"; break;
                case "jl": cc = "jg"; break;
                default: break; // je / jne are symmetric
            }
        }
        String aText;
        if (rfIsTemp(aTok)) {
            aText = rfRegText(rfReg(aTok));
        } else if (rfIsSlot(aTok) && !rfIsSlot(bTok)) {
            aText = rfMemText(rfSlot(aTok));
        } else {
            // both operands are slots (cmp takes at most one memory operand): a goes through the scratch register
            rfMov("%s", aTok);
            aText = rfRegText(RF_SCRATCH);
        }
        String bText = rfSrc(bTok); // a huge immediate goes through the scratch register (a is then never in it: a slot stays in memory)
        rfOp2("cmp", aText, bText);
        raw("    " + cc + " " + label);
    }

    private static long rfExtImm(long v, int size, boolean signed) {
        if (size >= 8) {
            return v;
        }
        long t = rfTrunc(v, size);
        return signed ? t : (t & ((1L << (8 * size)) - 1));
    }

    private void rfExtend(String reg, int size, boolean signed) {
        if (signed) {
            signExtendReg(reg, size);
        } else {
            zeroExtendReg(reg, size);
        }
    }

    private void rfUn(String op, int size, String dstTok, String aTok) {
        String d = rfReg(dstTok);
        if (!(rfIsTemp(aTok) && rfReg(aTok).equals(d))) {
            rfMov(dstTok, aTok);
        }
        switch (op) {
            case "INC":
                raw(isWindows() ? ("    inc " + d) : ("    incq %" + d));
                return;
            case "DEC":
                raw(isWindows() ? ("    dec " + d) : ("    decq %" + d));
                return;
            case "NEG":
                raw(isWindows() ? ("    neg " + d) : ("    negq %" + d));
                return;
            case "NOT":
                raw(isWindows() ? ("    xor " + sizedReg(d, 1) + ", 1") : ("    xorb $1, %" + sizedReg(d, 1)));
                return;
            case "BNOT":
                // bitwise complement cut back to the operand width (u8: ~0 = 255)
                raw(isWindows() ? ("    not " + d) : ("    notq %" + d));
                zeroExtendReg(d, size);
                return;
            default:
                throw new IllegalStateException("unknown R_UN operator '" + op + "'");
        }
    }

    // ---- register-form: memory, address, float, read-modify-write helpers ----------------------------------------

    private static boolean rfIsGlobal(String tok) {
        return tok.startsWith("&");
    }

    /** address of a frame slot ("$off") or a global ("&sym") into reg. */
    private void rfAddrToReg(String reg, String tok) {
        if (rfIsGlobal(tok)) {
            leaGlobalToReg(reg, tok.substring(1));
        } else {
            leaMemToReg(reg, rfSlot(tok));
        }
    }

    /** An integer argument register; an index beyond the register-passed ones would fall back to rax (a temp), which the pass never produces. */
    private String rfArgReg(int idx) {
        String reg = argReg(idx);
        if (reg.equals("rax")) {
            throw new CodegenException("codegen", "<register-form>", 0, "R_ARG index " + idx + " is beyond the register-passed arguments");
        }
        return reg;
    }

    private void rfLoad(int n, String dstTok, String srcTok) {
        String d = rfReg(dstTok);
        if (rfIsSlot(srcTok)) {
            loadSizedFromFrame(d, rfSlot(srcTok), n);
        } else if (rfIsGlobal(srcTok)) {
            leaGlobalToReg(d, srcTok.substring(1));
            loadSizedFromAddr(d, d, n);
        } else {
            loadSizedFromAddr(d, rfReg(srcTok), n);
        }
    }

    private static long rfTrunc(long v, int n) {
        if (n >= 8) {
            return v;
        }
        int sh = 64 - 8 * n;
        return (v << sh) >> sh;
    }

    private void rfStore(int n, String addrTok, String srcTok) {
        String areg = null; // register holding the address; null = a frame slot
        if (rfIsGlobal(addrTok)) {
            leaGlobalToReg(RF_SCRATCH, addrTok.substring(1));
            areg = RF_SCRATCH;
        } else if (!rfIsSlot(addrTok)) {
            areg = rfReg(addrTok);
        }
        if (rfIsImm(srcTok)) {
            long v = rfTrunc(rfImm(srcTok), n);
            String mem;
            if (isWindows()) {
                String pfx = n == 1 ? "byte" : n == 2 ? "word" : n == 4 ? "dword" : "qword";
                mem = areg == null ? (pfx + " ptr [rbp" + signed(rfSlot(addrTok)) + "]") : (pfx + " ptr [" + areg + "]");
                raw("    mov " + mem + ", " + v);
            } else {
                mem = areg == null ? (rfSlot(addrTok) + "(%rbp)") : ("(%" + areg + ")");
                raw("    mov" + movSuffix(n) + " $" + v + ", " + mem);
            }
            return;
        }
        String reg = rfReg(srcTok);
        if (areg == null) {
            storeSizedToFrame(reg, rfSlot(addrTok), n);
        } else {
            storeSizedToAddr(reg, areg, n);
        }
    }

    /** When set, loadSizedFromAddr / storeSizedToAddr address "(base + index*scale)" instead of "(base)" (R_LDI / R_STI only; sizes 1, 2, 4, 8). */
    private String rfIdxReg = null;
    private long rfIdxScale = 1;

    private String rfMemOperand(String baseReg) {
        if (rfIdxReg == null) {
            return isWindows() ? ("[" + baseReg + "]") : ("(%" + baseReg + ")");
        }
        return isWindows() ? ("[" + baseReg + "+" + rfIdxReg + "*" + rfIdxScale + "]")
                : ("(%" + baseReg + ",%" + rfIdxReg + "," + rfIdxScale + ")");
    }

    private void rfLoadIndexed(int n, String dstTok, String baseTok, String idxTok, long scale) {
        if (!(n == 1 || n == 2 || n == 4 || n == 8) || !rfIsGlobal(baseTok) || !rfIsVar(idxTok)
                || !(scale == 1 || scale == 2 || scale == 4 || scale == 8)) {
            throw new IllegalStateException("malformed R_LDI");
        }
        String d = rfReg(dstTok);
        String x = rfReg(idxTok);
        leaGlobalToReg(d, baseTok.substring(1));
        rfIdxReg = x;
        rfIdxScale = scale;
        try {
            loadSizedFromAddr(d, d, n);
        } finally {
            rfIdxReg = null;
        }
    }

    private void rfStoreIndexed(int n, String baseTok, String idxTok, long scale, String srcTok) {
        if (!(n == 1 || n == 2 || n == 4 || n == 8) || !rfIsGlobal(baseTok) || !rfIsVar(idxTok)
                || !(scale == 1 || scale == 2 || scale == 4 || scale == 8)) {
            throw new IllegalStateException("malformed R_STI");
        }
        leaGlobalToReg(RF_SCRATCH, baseTok.substring(1));
        rfIdxReg = rfReg(idxTok);
        rfIdxScale = scale;
        try {
            if (rfIsImm(srcTok)) {
                long v = rfTrunc(rfImm(srcTok), n);
                if (isWindows()) {
                    String pfx = n == 1 ? "byte" : n == 2 ? "word" : n == 4 ? "dword" : "qword";
                    raw("    mov " + pfx + " ptr " + rfMemOperand(RF_SCRATCH) + ", " + v);
                } else {
                    raw("    mov" + movSuffix(n) + " $" + v + ", " + rfMemOperand(RF_SCRATCH));
                }
            } else {
                storeSizedToAddr(rfReg(srcTok), RF_SCRATCH, n);
            }
        } finally {
            rfIdxReg = null;
        }
    }

    /** xmm register = the float at global + index*scale (R_LDXI): the base address goes through the scratch register. */
    private void rfLoadXIndexed(int n, String xTok, String baseTok, String idxTok, long scale) {
        if (!(n == 4 || n == 8) || scale != n || !rfIsGlobal(baseTok) || !rfIsVar(idxTok)) {
            throw new IllegalStateException("malformed R_LDXI");
        }
        leaGlobalToReg(RF_SCRATCH, baseTok.substring(1));
        rfIdxReg = rfReg(idxTok);
        rfIdxScale = scale;
        try {
            String mem = isWindows() ? ((n == 8 ? "qword ptr " : "dword ptr ") + rfMemOperand(RF_SCRATCH)) : rfMemOperand(RF_SCRATCH);
            String mn = n == 8 ? "movsd" : "movss";
            String xmm = xvReg(xTok);
            raw(isWindows() ? ("    " + mn + " " + xmm + ", " + mem) : ("    " + mn + " " + mem + ", %" + xmm));
        } finally {
            rfIdxReg = null;
        }
    }

    /** the float in an xmm register stored at global + index*scale (R_STXI). */
    private void rfStoreXIndexed(int n, String baseTok, String idxTok, long scale, String xTok) {
        if (!(n == 4 || n == 8) || scale != n || !rfIsGlobal(baseTok) || !rfIsVar(idxTok)) {
            throw new IllegalStateException("malformed R_STXI");
        }
        leaGlobalToReg(RF_SCRATCH, baseTok.substring(1));
        rfIdxReg = rfReg(idxTok);
        rfIdxScale = scale;
        try {
            String mem = isWindows() ? ((n == 8 ? "qword ptr " : "dword ptr ") + rfMemOperand(RF_SCRATCH)) : rfMemOperand(RF_SCRATCH);
            String mn = n == 8 ? "movsd" : "movss";
            String xmm = xvReg(xTok);
            raw(isWindows() ? ("    " + mn + " " + mem + ", " + xmm) : ("    " + mn + " %" + xmm + ", " + mem));
        } finally {
            rfIdxReg = null;
        }
    }

    private void rfAddImm(String reg, long v) {
        if (v == 0) {
            return;
        }
        if (rfFitsImm32(v)) {
            raw(isWindows() ? ("    add " + reg + ", " + v) : ("    addq $" + v + ", %" + reg));
        } else {
            movImmToReg(RF_SCRATCH, v);
            raw(isWindows() ? ("    add " + reg + ", " + RF_SCRATCH) : ("    addq %" + RF_SCRATCH + ", %" + reg));
        }
    }

    private void rfLea(String dstTok, String baseTok, String idxTok, long scale, long extra) {
        String d = rfReg(dstTok);
        boolean baseIsT = rfIsTemp(baseTok);
        String bReg = baseIsT ? rfReg(baseTok) : null;
        if (rfIsImm(idxTok)) {
            long disp = rfImm(idxTok) * scale + extra;
            if (rfIsSlot(baseTok)) {
                long total = rfSlot(baseTok) + disp;
                if (rfFitsImm32(total)) {
                    leaMemToReg(d, total);
                } else {
                    leaMemToReg(d, rfSlot(baseTok));
                    rfAddImm(d, disp);
                }
            } else if (rfIsGlobal(baseTok)) {
                leaGlobalToReg(d, baseTok.substring(1));
                rfAddImm(d, disp);
            } else {
                rfRegToReg(d, bReg);
                rfAddImm(d, disp);
            }
            return;
        }
        String x;
        if (rfIsTemp(idxTok)) {
            x = rfReg(idxTok);
            if (rfIsVar(idxTok) && !(scale == 1 || scale == 2 || scale == 4 || scale == 8)) {
                rfRegToReg(RF_SCRATCH, x); // the multiply below works in place; never on a variable
                x = RF_SCRATCH;
            }
            if (x.equals(d) && !(baseIsT && bReg.equals(d))) {
                rfRegToReg(RF_SCRATCH, x); // the index lives in the destination register: keep it before the base overwrites it
                x = RF_SCRATCH;
            }
        } else {
            movMemToReg(RF_SCRATCH, rfSlot(idxTok));
            x = RF_SCRATCH;
        }
        if (rfIsSlot(baseTok)) {
            leaMemToReg(d, rfSlot(baseTok));
        } else if (rfIsGlobal(baseTok)) {
            leaGlobalToReg(d, baseTok.substring(1));
        } else {
            rfRegToReg(d, bReg);
        }
        boolean fitsDisp = rfFitsImm32(extra);
        if (scale == 1 || scale == 2 || scale == 4 || scale == 8) {
            if (extra != 0 && fitsDisp) {
                raw(isWindows() ? ("    lea " + d + ", [" + d + "+" + x + "*" + scale + (extra < 0 ? "-" + (-extra) : "+" + extra) + "]")
                        : ("    leaq " + extra + "(%" + d + ",%" + x + "," + scale + "), %" + d));
            } else {
                raw(isWindows() ? ("    lea " + d + ", [" + d + "+" + x + "*" + scale + "]")
                        : ("    leaq (%" + d + ",%" + x + "," + scale + "), %" + d));
                if (extra != 0) {
                    rfAddImm(d, extra);
                }
            }
        } else {
            raw(isWindows() ? ("    imul " + x + ", " + x + ", " + scale) : ("    imulq $" + scale + ", %" + x + ", %" + x));
            raw(isWindows() ? ("    add " + d + ", " + x) : ("    addq %" + x + ", %" + d));
            if (extra != 0) {
                rfAddImm(d, extra);
            }
        }
    }

    /** Scratch xmm registers for register-form float ops: never an argument register (SysV passes floats in xmm0-7; win64 in xmm0-3) and never a win64 non-volatile one. */
    private String rfXmmA() {
        return isWinAbi() ? "xmm4" : "xmm14";
    }

    private String rfXmmB() {
        return isWinAbi() ? "xmm5" : "xmm15";
    }

    private void rfGprToXmm(String xmm, String gpr64, int n) {
        if (n == 4) {
            raw(isWindows() ? ("    movd " + xmm + ", " + sizedReg(gpr64, 4)) : ("    movd %" + sizedReg(gpr64, 4) + ", %" + xmm));
        } else {
            raw(isWindows() ? ("    movq " + xmm + ", " + gpr64) : ("    movq %" + gpr64 + ", %" + xmm));
        }
    }

    private void rfXmmToGpr(String xmm, String gpr64, int n) {
        if (n == 4) {
            raw(isWindows() ? ("    movd " + sizedReg(gpr64, 4) + ", " + xmm) : ("    movd %" + xmm + ", %" + sizedReg(gpr64, 4)));
        } else {
            raw(isWindows() ? ("    movq " + gpr64 + ", " + xmm) : ("    movq %" + xmm + ", %" + gpr64));
        }
    }

    /** a float operand (a temp, an immediate bit pattern or a frame slot of the op's own width) into an xmm register. */
    private void rfLoadXmm(String tok, String xmm, int n) {
        if (rfIsXvar(tok)) {
            rfMovaps(xmm, xvReg(tok));
        } else if (rfIsTemp(tok)) {
            rfGprToXmm(xmm, rfReg(tok), n);
        } else if (rfIsImm(tok)) {
            if (floatPoolOn()) {
                long v = n == 4 ? (rfImm(tok) & 0xFFFFFFFFL) : rfImm(tok);
                if (v == 0) {
                    raw("    xorps %" + xmm + ", %" + xmm);
                } else {
                    raw("    mov" + (n == 4 ? "ss" : "sd") + " " + floatPoolMem(v, n) + ", %" + xmm);
                }
            } else {
                movImmToReg(RF_SCRATCH, rfImm(tok));
                rfGprToXmm(xmm, RF_SCRATCH, n);
            }
        } else {
            long off = rfSlot(tok);
            String sfx = n == 4 ? "ss" : "sd";
            raw(isWindows() ? ("    mov" + sfx + " " + xmm + ", " + (n == 4 ? "dword" : "qword") + " ptr [rbp" + signed(off) + "]")
                    : ("    mov" + sfx + " " + off + "(%rbp), %" + xmm));
        }
    }

    // ---- float variables in xmm registers (RegVarPromotionPass, "float-variables-in-registers: on") ----
    //
    // Variable K lives in xmm(8+K) on SysV and xmm(6+K) on win64 (K < XV_COUNT). Both ranges avoid the argument registers
    // (SysV xmm0-7, win64 xmm0-3) and the register-form scratch pair (SysV xmm14/15, win64 xmm4/5). win64 keeps xmm6-15 across
    // calls, and finishCalleeSaved already saves/restores any of them a function touches (also on a throw). SysV keeps no xmm
    // register across a call, so every call spills all of the function's variables to their home frame slots (declared by
    // R_XVAR) right before it and reloads them right after; the catch entry reloads them too (control gets there from an unwind,
    // after the throwing call's spill). A variable's value is the f32 in the low 32 bits of the register.

    private static final int XV_COUNT = 6;
    private final java.util.Map<Integer, Long> xvHome = new java.util.TreeMap<>();
    private final java.util.Map<Integer, Integer> xvWidth = new java.util.TreeMap<>();

    private static boolean rfIsXvar(String tok) {
        return tok.startsWith("%x") || tok.startsWith("%y");
    }

    /** float temporaries %y0..%y3 (FloatTempPass): SysV xmm4-7, win64 xmm12-15 (win64 keeps them; a temp is never live across a call anyway). */
    private static final int YT_COUNT = 4;

    private String yPhys(int k) {
        return "xmm" + ((isWinAbi() ? 12 : 4) + k);
    }

    private String xvPhys(int k) {
        return "xmm" + ((isWinAbi() ? 6 : 8) + k);
    }

    private String xvReg(String tok) {
        int k = Integer.parseInt(tok.substring(2));
        if (tok.startsWith("%y")) {
            if (k < 0 || k >= YT_COUNT) {
                throw new IllegalStateException("float temporary register " + tok + " out of range");
            }
            return yPhys(k);
        }
        if (k < 0 || k >= XV_COUNT) {
            throw new IllegalStateException("float variable register " + tok + " out of range");
        }
        return xvPhys(k);
    }

    private void xvSpillAll() {
        if (isWinAbi() || xvHome.isEmpty()) {
            return;
        }
        for (java.util.Map.Entry<Integer, Long> e : xvHome.entrySet()) {
            raw("    mov" + (xvWidth.getOrDefault(e.getKey(), 4) == 8 ? "sd" : "ss") + " %" + xvPhys(e.getKey()) + ", " + e.getValue() + "(%rbp)");
        }
    }

    private void xvReloadAll() {
        if (isWinAbi() || xvHome.isEmpty()) {
            return;
        }
        for (java.util.Map.Entry<Integer, Long> e : xvHome.entrySet()) {
            raw("    mov" + (xvWidth.getOrDefault(e.getKey(), 4) == 8 ? "sd" : "ss") + " " + e.getValue() + "(%rbp), %" + xvPhys(e.getKey()));
        }
    }

    /** True when float immediates go through the constant pool (the AT&T targets; the MASM path is unchanged). */
    private boolean floatPoolOn() {
        return !isWindows();
    }

    /** The pool label for a float constant of width n (4 or 8 bytes), creating the entry on first use. */
    private String floatPoolLabel(long bits, int n) {
        long b = n == 4 ? (bits & 0xFFFFFFFFL) : bits;
        String key = n + ":" + b;
        String label = floatPool.get(key);
        if (label == null) {
            label = ".LFC" + floatPool.size();
            floatPool.put(key, label);
        }
        return label;
    }

    /** The memory operand text of a pooled float constant. */
    private String floatPoolMem(long bits, int n) {
        return floatPoolLabel(bits, n) + "(%rip)";
    }

    /** Emits the pool after the last function: read-only, each entry aligned to its own size. */
    private void emitFloatPool() {
        if (floatPool.isEmpty()) {
            return;
        }
        raw(target == CodegenConfig.Target.WINDOWS_GNU_X64 ? ".section .rdata,\"dr\"" : ".section .rodata");
        for (Map.Entry<String, String> e : floatPool.entrySet()) {
            int colon = e.getKey().indexOf(':');
            int n = Integer.parseInt(e.getKey().substring(0, colon));
            long bits = Long.parseLong(e.getKey().substring(colon + 1));
            raw(n == 4 ? "    .p2align 2" : "    .p2align 3");
            raw(e.getValue() + ":");
            raw(n == 4 ? ("    .long " + bits) : ("    .quad " + bits));
        }
    }

    private String xText(String xmm) {
        return isWindows() ? xmm : ("%" + xmm);
    }

    private void rfMovaps(String dst, String src) {
        if (!dst.equals(src)) {
            raw(isWindows() ? ("    movaps " + dst + ", " + src) : ("    movaps %" + src + ", %" + dst));
        }
    }

    /** memory operand text for a float at addr (a frame slot, a global or the pointer in a temp), n = 4 or 8 bytes wide. */
    private String rfFloatMem(String addrTok, int n) {
        String ptr = n == 8 ? "qword ptr " : "dword ptr ";
        if (rfIsSlot(addrTok)) {
            return isWindows() ? (ptr + "[rbp" + signed(rfSlot(addrTok)) + "]") : (rfSlot(addrTok) + "(%rbp)");
        } else if (rfIsGlobal(addrTok)) {
            leaGlobalToReg(RF_SCRATCH, addrTok.substring(1));
            return isWindows() ? ptr + "[" + RF_SCRATCH + "]" : ("(%" + RF_SCRATCH + ")");
        }
        return isWindows() ? (ptr + "[" + rfReg(addrTok) + "]") : ("(%" + rfReg(addrTok) + ")");
    }

    /** xmm register = the float (n = 4: f32, 8: f64) at addr. */
    private void rfLoadX(String xmm, String addrTok, int n) {
        String mem = rfFloatMem(addrTok, n);
        String mn = n == 8 ? "movsd" : "movss";
        raw(isWindows() ? ("    " + mn + " " + xmm + ", " + mem) : ("    " + mn + " " + mem + ", %" + xmm));
    }

    /** the float in an xmm register stored at addr. */
    private void rfStoreX(String addrTok, String xmm, int n) {
        String mem = rfFloatMem(addrTok, n);
        String mn = n == 8 ? "movsd" : "movss";
        raw(isWindows() ? ("    " + mn + " " + mem + ", " + xmm) : ("    " + mn + " %" + xmm + ", " + mem));
    }

    /** "R_FBINX OP n %xK a b": variable K = a OP b (n is always 4). */
    private void rfFloatX(String op, int n, String dstTok, String aTok, String bTok) {
        String xd = xvReg(dstTok);
        String mn;
        switch (op) {
            case "ADD": mn = "add"; break;
            case "SUB": mn = "sub"; break;
            case "MUL": mn = "mul"; break;
            case "DIV": mn = "div"; break;
            default: throw new IllegalStateException("unknown R_FBINX operator '" + op + "'");
        }
        String sfx = n == 4 ? "ss" : "sd";
        boolean commutative = op.equals("ADD") || op.equals("MUL");
        boolean bIsDst = rfIsXvar(bTok) && xvReg(bTok).equals(xd);
        boolean aIsDst = rfIsXvar(aTok) && xvReg(aTok).equals(xd);
        if (bIsDst && !aIsDst && commutative) {
            String t = aTok;
            aTok = bTok;
            bTok = t;
            aIsDst = true;
            bIsDst = false;
        }
        String work = bIsDst ? rfXmmA() : xd; // b lives in the destination register: compute elsewhere, then copy
        String bText;
        if (rfIsXvar(bTok)) {
            bText = xText(xvReg(bTok));
        } else if (rfIsSlot(bTok)) {
            long off = rfSlot(bTok);
            bText = isWindows() ? ((n == 4 ? "dword" : "qword") + " ptr [rbp" + signed(off) + "]") : (off + "(%rbp)");
        } else if (floatPoolOn() && rfIsImm(bTok)) {
            bText = floatPoolMem(rfImm(bTok), n);
        } else {
            rfLoadXmm(bTok, rfXmmB(), n);
            bText = xText(rfXmmB());
        }
        if (rfIsXvar(aTok)) {
            rfMovaps(work, xvReg(aTok));
        } else {
            rfLoadXmm(aTok, work, n);
        }
        raw(isWindows() ? ("    " + mn + sfx + " " + work + ", " + bText) : ("    " + mn + sfx + " " + bText + ", %" + work));
        rfMovaps(xd, work);
    }

    private void rfFloat(boolean compare, String op, int n, String dstTok, String aTok, String bTok) {
        String d = rfReg(dstTok);
        String xa = rfXmmA();
        String xb = rfXmmB();
        String sfx = n == 4 ? "ss" : "sd";
        boolean aDirect = compare && rfIsXvar(aTok); // a compare never modifies a: read the variable's register in place
        if (!aDirect) {
            rfLoadXmm(aTok, xa, n);
        } else {
            xa = xvReg(aTok);
        }
        String bText;
        if (rfIsXvar(bTok)) {
            bText = xText(xvReg(bTok));
        } else if (rfIsSlot(bTok)) {
            long off = rfSlot(bTok);
            bText = isWindows() ? ((n == 4 ? "dword" : "qword") + " ptr [rbp" + signed(off) + "]") : (off + "(%rbp)");
        } else if (floatPoolOn() && rfIsImm(bTok)) {
            bText = floatPoolMem(rfImm(bTok), n);
        } else {
            rfLoadXmm(bTok, xb, n);
            bText = isWindows() ? xb : ("%" + xb);
        }
        String aText = isWindows() ? xa : ("%" + xa);
        if (!compare) {
            String mn;
            switch (op) {
                case "ADD": mn = "add"; break;
                case "SUB": mn = "sub"; break;
                case "MUL": mn = "mul"; break;
                case "DIV": mn = "div"; break;
                default: throw new IllegalStateException("unknown R_FBIN operator '" + op + "'");
            }
            raw(isWindows() ? ("    " + mn + sfx + " " + aText + ", " + bText) : ("    " + mn + sfx + " " + bText + ", " + aText));
            rfXmmToGpr(xa, d, n);
            return;
        }
        raw(isWindows() ? ("    ucomi" + sfx + " " + aText + ", " + bText) : ("    ucomi" + sfx + " " + bText + ", " + aText));
        String setcc;
        switch (op) {
            case "EQ": setcc = "sete"; break;
            case "NEQ": setcc = "setne"; break;
            case "LT": setcc = "setb"; break;
            case "LT_EQ": setcc = "setbe"; break;
            case "GT_EQ": setcc = "setae"; break;
            case "GT": setcc = "seta"; break;
            default: throw new IllegalStateException("unknown R_FCMP operator '" + op + "'");
        }
        rfSetcc(setcc, d);
    }

    private void rfSetcc(String setcc, String d) {
        String d8 = sizedReg(d, 1);
        if (isWindows()) {
            raw("    " + setcc + " " + d8);
            raw("    movzx " + d + ", " + d8);
        } else {
            raw("    " + setcc + " %" + d8);
            raw("    movzbq %" + d8 + ", %" + d);
        }
    }

    private void rfRmw(String op, String slotTok, String srcTok) {
        if (rfIsVar(slotTok)) {
            String vr = rfReg(slotTok);
            switch (op) {
                case "INC":
                    raw(isWindows() ? ("    inc " + vr) : ("    incq %" + vr));
                    return;
                case "DEC":
                    raw(isWindows() ? ("    dec " + vr) : ("    decq %" + vr));
                    return;
                case "ADD":
                case "SUB": {
                    String mn = op.equals("ADD") ? "add" : "sub";
                    if (rfIsSlot(srcTok)) {
                        movMemToReg(RF_SCRATCH, rfSlot(srcTok));
                        rfOp2(mn, rfRegText(vr), rfRegText(RF_SCRATCH));
                    } else {
                        rfOp2(mn, rfRegText(vr), rfSrc(srcTok));
                    }
                    return;
                }
                default:
                    throw new IllegalStateException("unknown R_RMW operator '" + op + "'");
            }
        }
        String mem = rfMemText(rfSlot(slotTok));
        switch (op) {
            case "INC":
                raw(isWindows() ? ("    inc " + mem) : ("    incq " + mem));
                return;
            case "DEC":
                raw(isWindows() ? ("    dec " + mem) : ("    decq " + mem));
                return;
            case "ADD":
            case "SUB": {
                String mn = op.equals("ADD") ? "add" : "sub";
                if (rfIsSlot(srcTok)) {
                    movMemToReg(RF_SCRATCH, rfSlot(srcTok));
                    rfOp2(mn, mem, rfRegText(RF_SCRATCH));
                } else {
                    rfOp2(mn, mem, rfSrc(srcTok));
                }
                return;
            }
            default:
                throw new IllegalStateException("unknown R_RMW operator '" + op + "'");
        }
    }

    private String mangleLabel(String text) {
        return text.replace("@", "L_");
    }

    private void movRegToRegRbpFromRsp() {
        if (isWindows()) {
            raw("    mov rbp, rsp");
        } else {
            raw("    movq %rsp, %rbp");
        }
    }

    /**
     * The real function epilogue -- restore %rsp from %rbp (discarding
     * this frame's own ALLOC reservation and any leftover stack-machine
     * push/pop noise in one move, the same way FUNC_START's prologue
     * reservation is undone regardless of how it was used), restore the
     * caller's own %rbp, then the real hardware `ret`. Emitted once at
     * FUNC_END (a function's true textual end) and again at every RET/
     * RET_FLOAT site (a function's every real, control-flow-reachable
     * exit point) -- see those mnemonics' own doc comments for why a
     * single copy at FUNC_END alone isn't enough. A RET immediately
     * followed by FUNC_END (the common, non-nested case) simply makes
     * FUNC_END's own copy dead, unreachable code -- harmless.
     */

    /**
     * Called at FUNC_END. Finds which callee-saved registers this function's text touches, grows the function's single
     * ALLOC by their save area (kept a multiple of 16), stores them right after it, and turns every CSR_MARK (one per
     * exit path, including GT_UNWIND) into the matching restores. A function that touches none is left as it was.
     */
    private void finishCalleeSaved() {
        if (csrFuncStart < 0) {
            return;
        }
        String body = out.substring(csrFuncStart);
        java.util.List<String[]> regs = new java.util.ArrayList<>(); // {name, kind gpr|xmm}
        java.util.regex.Pattern nb = java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])(?:rbx|ebx|bx|bl|bh)(?![A-Za-z0-9_])");
        if (nb.matcher(body).find()) regs.add(new String[] {"rbx", "gpr"});
        for (int r = 12; r <= 15; r++) {
            if (java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])r" + r + "[dwb]?(?![A-Za-z0-9_])").matcher(body).find()) {
                regs.add(new String[] {"r" + r, "gpr"});
            }
        }
        if (isWinAbi()) {
            if (java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])(?:rdi|edi|di|dil)(?![A-Za-z0-9_])").matcher(body).find()) regs.add(new String[] {"rdi", "gpr"});
            if (java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])(?:rsi|esi|si|sil)(?![A-Za-z0-9_])").matcher(body).find()) regs.add(new String[] {"rsi", "gpr"});
            for (int x = 6; x <= 15; x++) {
                if (java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])xmm" + x + "(?![0-9A-Za-z_])").matcher(body).find()) {
                    regs.add(new String[] {"xmm" + x, "xmm"});
                }
            }
        }
        String restoreText = "";
        if (!regs.isEmpty()) {
            if (csrAllocPos < 0) {
                // no locals at all (no ALLOC line): make the frame ourselves, right after the prologue
                csrAllocPos = csrFuncStart;
                csrAllocBytes = 0;
                csrAllocLine = "";
            }
            String beforeAlloc = out.substring(csrFuncStart, csrAllocPos);
            for (String[] r : regs) {
                java.util.regex.Pattern any = java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])" + (r[1].equals("xmm") ? r[0] : (r[0].equals("rbx") ? "(?:rbx|ebx|bx|bl|bh)" : r[0].equals("rdi") ? "(?:rdi|edi|di|dil)" : r[0].equals("rsi") ? "(?:rsi|esi|si|sil)" : r[0] + "[dwb]?")) + "(?![0-9A-Za-z_])");
                if (any.matcher(beforeAlloc.replace(CSR_MARK, "")).find()) {
                    throw new RuntimeException("codegen internal error: function '" + currentFuncName + "' uses " + r[0] + " before its frame is allocated");
                }
            }
            long cur = csrAllocBytes;
            StringBuilder saves = new StringBuilder();
            StringBuilder restores = new StringBuilder();
            for (String[] r : regs) {
                boolean x = r[1].equals("xmm");
                cur += x ? 16 : 8;
                String st, ld;
                if (isWindows()) {
                    String mem = "[rbp-" + cur + "]";
                    st = x ? "    movups " + mem + ", " + r[0] : "    mov " + mem + ", " + r[0];
                    ld = x ? "    movups " + r[0] + ", " + mem : "    mov " + r[0] + ", " + mem;
                } else {
                    String mem = "-" + cur + "(%rbp)";
                    st = "    " + (x ? "movups" : "movq") + " %" + r[0] + ", " + mem;
                    ld = "    " + (x ? "movups" : "movq") + " " + mem + ", %" + r[0];
                }
                saves.append(st).append('\n');
                restores.append(ld).append('\n');
            }
            long total = (cur + 15) & ~15L;
            String newSub = isWindows() ? ("    sub rsp, " + total + "\n") : ("    subq $" + total + ", %rsp\n");
            if (!out.substring(csrAllocPos, csrAllocPos + csrAllocLine.length()).equals(csrAllocLine)) {
                throw new RuntimeException("codegen internal error: ALLOC line moved in '" + currentFuncName + "'");
            }
            out.replace(csrAllocPos, csrAllocPos + csrAllocLine.length(), newSub + saves);
            restoreText = restores.toString();
        }
        int from = csrFuncStart;
        String marked = CSR_MARK + "\n";
        int i;
        while ((i = out.indexOf(marked, from)) >= 0) {
            out.replace(i, i + marked.length(), restoreText);
            from = i + restoreText.length();
        }
        csrFuncStart = -1;
    }

    private void emitFunctionEpilogue() {
        raw(CSR_MARK);
        if (isWindows()) {
            raw("    mov rsp, rbp");
        } else {
            raw("    movq %rbp, %rsp");
        }
        popReg("rbp");
        raw("    ret");
    }

    private static boolean isInteger(String s) {
        if (s.isEmpty()) {
            return false;
        }
        int start = s.charAt(0) == '-' ? 1 : 0;
        if (start == s.length()) {
            return false;
        }
        for (int i = start; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> textsOf(List<BytecodeToken> line) {
        List<String> out = new ArrayList<>();
        for (BytecodeToken t : line) {
            out.add(t.text);
        }
        return out;
    }
}
```
