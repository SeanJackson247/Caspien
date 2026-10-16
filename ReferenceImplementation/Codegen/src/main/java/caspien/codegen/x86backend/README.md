# x86backend

The x86-64 backend of stage 4: low-order bytecode (LOB) in, GAS AT&T assembly text out. The overall picture (targets, register partition, mnemonic groups, known gaps) is in `Codegen/CLAUDE.md`; this README holds the detailed rules and design notes that used to be long comments in the Java files, so the code keeps only short comments.

## Why this is a folder of classes

The backend is one logical class split over several files by **composition, not inheritance** (the same pattern as `ASTGenerator/.../typechecker`): `X86Backend` is the public hub and owns the shared state `X86Core` plus one instance of each part, each part holding a reference back to the hub/core.

| File | Lines | Holds |
|---|---:|---|
| `X86Backend.java` | 37 | public hub: wiring, `generate`, `avx` switch |
| `X86Core.java` | 479 | shared state (fields, constants) and static helpers |
| `X86Emit.java` | 1055 | text emission helpers, global declarations, construction runs, shifts, block copies |
| `X86Calls.java` | 517 | argument registers, aligned calls, `emitLine` dispatch, first mnemonic group |
| `X86CasesA.java` | 727 | mnemonic cases: register-form declarations, arithmetic, compares, shifts, conversions, stack ops |
| `X86CasesB.java` | 679 | mnemonic cases: `DOT`, `LOOKUP_*`, `NEW*`, `MEMCOPY`, `DEREF`, `ADDR_OF`, `RESIZE` |
| `X86RegForm.java` | 752 | mnemonic cases: ghost table, unwinding, `THROW`, `RET`, call brackets; register-form `R_*` emission |
| `X86Generator.java` | 1438 | register-form helpers, callee-saved handling, `generate`; backend overview |
| `X86RunEntries.java` | 10 | run-entry record |

## How to read this file

- One section per Java file, then topics named after the methods, mnemonics or mechanisms they describe; names are exact so you can search the code for them.
- Statements marked **(comment said)** record what an old code comment claimed where it no longer matches the code (it was stale or the code moved on). They are kept so nothing is lost; treat the code and `Codegen/CLAUDE.md` as authoritative.
- Code comments are 1-2 lines on purpose; a line such as `// See README.md, "Calls and stack alignment".` points at the matching section here.

## X86Backend.java

The public face and wiring of the x86-64 backend. It owns the shared state (`X86Core`) and the six parts that use it
(`X86Emit`, `X86Calls`, `X86CasesA`, `X86CasesB`, `X86RegForm`, `X86Generator`), each a separate class holding a reference
back to the hub. The overview of the whole backend (strategy, targets, layout) is written under `X86Generator.java` below.
`avx` is the "AVX three-operand float forms" switch, set before `generate`, which copies it into `core.avx`.

## X86Calls.java

Argument registers, aligned calls, the dispatch `emitLine`/`emitLineInner` and the first mnemonic group (`emitCases1`:
`FUNC_START`, `FUNC_END`, `ALLOC`, `ADDR`, `PUSH`, `ASSIGN`/`ATOMIC_ASSIGN`, and the `R_MOV`/`R_BIN`/`R_DIVC`/`R_UN`/`R_BRF`/`R_BRC`/`R_CMOV` forwarders).

### `memcopyConstantSize`
Returns the byte count of the `MEMCOPY` on the current line when it is a literal of at most 64 bytes, else -1. The line shape is
`[dest expr] PUSH 8 <size> [src expr] MEMCOPY`, where the source expression is one value (`PUSH 8 $x`, optionally followed by
`ADDR_OF ...`). It walks back over the lines after the size push and requires that they leave exactly one value.

### Float (SSE) helpers (`movRegToXmm`, `movXmmToReg`, `sseSuffix`, ...)
- Every value on the backend's value stack, float bits included, already travels as opaque bytes through the ordinary
  push/pop-a-64-bit-GPR mechanism (see `PUSH`'s `isFloatLiteral` branch, and `ASSIGN`/frame-load; neither interprets a
  float's bits any differently from an int's).
- What needs the SSE register file is arithmetic. `movq` between a GPR and an xmm register moves the raw 64 bits with no
  reinterpretation (the same "opaque bytes" property the whole-word stack model relies on). So every float op pops its
  operand(s) through the GPR path, shuttles them into xmm0/xmm1 with that one instruction, does the floating-point op there,
  and shuttles the result back the same way before pushing it.
- There is no persistent xmm allocation in this path; this matches the first pass's "simplest correct mapping" philosophy
  (CLAUDE.md, "What's implemented"). (The register-form code in `X86RegForm`/`X86Generator` is the one that keeps float variables in xmm registers.)

### `pushSizedOrBlockFromAddr`
The first word of a multi-word value is loaded into `r11`, never `rax`: callers such as `LOOKUP_DYN` pass `"rax"` as the
address register, and loading the first word into it destroyed the address before the second word was read. (Every dynarray
element wider than one word, i.e. any struct, read back garbage.)

### `argReg` / `argFloatReg`
The register table is an ABI question, not a syntax one: `WINDOWS_GNU_X64` uses the win64 registers, just referenced in AT&T syntax.
Beyond the register-passed arguments the functions fall back (`argFloatReg` returns `"xmm0"`); the comment said "this first
version doesn't marshal stack-passed arguments -- no fixture this pass was verified against needs a 5th+ (Linux) / 5th+
(Windows) arg". That is old: stack-passed arguments are now handled by the call-site machinery below (see "Stack-passed arguments").

### Calls and stack alignment (`emitAlignedCall`, `emitAlignedCallByName`)
- Why the exact rsp is saved: the stack-machine-to-real-stack translation pushes and pops arbitrarily many words between
  calls, so rsp's alignment at a `call` site is not otherwise known. An unaligned rsp across a real `call` is undefined
  behaviour per the x86-64 ABI (some libc routines fault on it). Restoring the saved rsp afterwards also keeps the backend's
  "abstract stack depth" bookkeeping exactly correct regardless of the alignment adjustment.
- The emitted sequence (AT&T):
  ```
  movq %rsp, %rax
  andq $-16, %rsp
  subq $16, %rsp          # reserved slot for the pre-align rsp; keeps 16-alignment
  movq %rax, 8(%rsp)
  [subq $32, %rsp]        # win64 only: shadow space
  [movl $<xmmCount>, %eax]# SysV only: see "varargs %al" below
  call target
  [addq $32, %rsp]        # win64 only
  movq 8(%rsp), %rsp      # load the saved rsp straight into rsp; also discards the slot
  ```
- History / why a stack slot and not a register: the pre-alignment rsp used to be stashed in `%r13` on the theory that r13 is
  callee-saved. That holds for a real external function (malloc/printf/...), but NOT for a call into a function this backend
  generated: `FUNC_START`/`FUNC_END` only saved/restored `%rbp` (never r12-r15/rbx; at that time), so a Caspien-compiled callee
  that itself makes calls reused r13 for its own save/align/restore dance and overwrote the outer value. Root-caused against
  `gt_register` (which calls `gtSlotAt`/`gtReadSlot`): gdb showed r13 correct immediately before `call gt_register` but different
  (off by over a hundred bytes, gt_register's own last internal save) right after it returned, which was then copied into rsp and
  corrupted the caller's whole abstract stack depth from there. The fix trusts no register to survive the call: the pre-alignment
  rsp lives in a dedicated stack slot reserved at the call site. A nested call just gets its own slot further down. This relies
  only on the invariant every function here upholds: a well-behaved callee (real or Caspien-compiled) returns with rsp exactly
  where it was when the `call` executed, so the slot is still at a fixed offset from the unchanged rsp.
- Tracking suspended: `core.argTrackSuspendDepth` is incremented for the whole call (preamble, call, postamble; see
  `argTrackSuspendDepth`'s own doc). Every instruction nets to zero real stack effect by construction. Without the suspension the
  preamble's `sub rsp, 16` / `sub rsp, 32` would be tracked by `trackStackDelta` as a real surviving push (its matching undo is an
  ABSOLUTE `mov rsp, ...` restore, a shape `trackStackDelta` deliberately never recognises as a relative move) and would be
  wrongly counted toward an enclosing `CC_START`/`CALL` bracket's stack-passed-argument tally.
- **SysV varargs `%al`**: `%al` must hold the number of vector (xmm) registers used for a varargs call (printf most visibly).
  It used to be unconditionally zeroed on the claim that the backend never passes float arguments in xmm registers; that was
  stale even when written (register-passed floats already went through `POP FARGn` into a real xmm register) and was the root cause of
  `printf("%f", floatValue)` always printing `0.000000` (glibc's printf trusts `%al` and never looks at `%xmm0`). Fixed by
  emitting the compile-time count the front end computed for the call (`consumeVarargsXmmCount`). Win64 has no such
  convention, so this stays SysV-only.

### Stack-passed arguments (`alignPadFor`, `emitStackArgRestore`, `buildStackArgReservation`, `reverseStackArgWords`, `resolveStackArgBytesAndCall`)
Arguments beyond the register-passed ones travel on the stack. The call site is bracketed by `CC_START` ... `CALL`/`INVOKE` ... `CC_END`;
the number of stack-passed bytes (`stackArgBytes`) is only known at the `CALL` line, hence the retroactive insertion below
(the design is described at `argTrackDeltaStack`).
- `alignPadFor(stackArgBytes)`: the number of bytes `case "CC_START"` reserves below its own aligned rsp, BEFORE `stackArgBytes` of
  argument words are pushed on top and BEFORE the `call` pushes the 8-byte return address, chosen so that after both, rsp is on a
  16-byte boundary. `stackArgBytes` is always a multiple of 8 (every `pushq/popq/add rsp,N/sub rsp,N` that `trackStackDelta`
  recognises moves rsp by whole words; see `argTrackDeltaStack`). From a 16-aligned start (after `and rsp, -16`) the ABI wants
  `%rsp` = 0 (mod 16) at the instant the `call` executes (ABI text: "the value (%rsp + 8) is always a multiple of 16 when control is
  transferred to the function entry point", i.e. just after the return-address push). This was also confirmed against the old
  hard-coded zero-stack-argument case (`emitAlignedCall`: `subq $16` with nothing pushed). Result: 16 when `stackArgBytes` is a
  multiple of 16, else 8.
- **Fixed bug**: that ternary used to be inverted. The two overflow fixtures that first verified the mechanism
  (`register_overflow_args_even_cg_test` / `_odd_cg_test`) call an all-integer function that does no alignment-sensitive SSE stack
  access, so they tolerated a call site misaligned by 8 bytes. It showed up with `printf(...)` taking one float pushed on the stack (a true
  C varargs call; glibc spills xmm registers with the alignment-requiring `movaps`): segfault inside `__printf`, confirmed by gdb as an
  alignment fault, not a value error.
- `buildStackArgReservation(stackArgBytes)`: builds the same four-instruction alignment reservation as `emitAlignedCall` but as plain
  text, to `insert` at the very front of the call's own buffer (`callBufferStack`), instead of `raw()`-emitting it immediately (it is
  only known at the `CALL` line what size it needs; the ordinary zero-stack-argument case gets away with emitting up front at
  `CC_START`). It is a plain string, not `raw()`, so `trackStackDelta` never mistakes it for a real stack move; its net effect, paired
  with `emitStackArgRestore`, is zero by construction.
- `emitStackArgRestore(stackArgBytes)`: the `CALL`/`INVOKE` half of the fix: restores rsp to its pre-reservation value by reading the
  value the reservation saved. That slot's address never moved but rsp has, by `stackArgBytes` (the argument words pushed since), so it
  is read from `[rsp+stackArgBytes]`, not `[rsp]`. Called only when `stackArgBytes > 0`; the all-register case still cleans up through
  `emitAlignedCall`'s unchanged restore. It is a single memory-to-register `mov` straight into rsp with NO scratch register,
  deliberately: `%rax` holds the call's just-returned value here (non-void callee), so staging through it (an earlier version did)
  clobbered the return value before `PUSH_RET_INT` read it. Found as: with only the alignment fix in place, `sum8(1..8)` returned 36 in
  `%rax` (gdb) and the very next instruction overwrote it with a stray stack address. `emitAlignedCall`'s `movq 8(%rsp), %rsp` already
  avoids the trap the same way; this mirrors it at a variable offset.
- `reverseStackArgWords(stackArgBytes)`: puts the stack-passed words in the order the calling conventions require, the FIRST
  stack-passed argument at the LOWEST address (just above the return address), as if pushed right-to-left. The front end evaluates and
  pushes left to right, so at the call the first stack word is deepest. With two or more stack words the later ones were read as the
  earlier ones (`eight(1, ..., 8)` saw its 7th and 8th arguments swapped; `printf` with eight integer varargs printed `7 8 6` for the last
  three). Every stack-passed word is one whole 8-byte slot (`stackArgBytes` multiple of 8, see `alignPadFor`) and evaluation order stays
  strictly left to right, so the fix is an in-place reversal of those slots at the call, after every argument has been produced.
  Uses `rax` and `r11` as scratch (nothing live there; `r10` may hold an INVOKE target and is left alone).
- `resolveStackArgBytesAndCall(indirect, target)`: the shared `case "CALL"` / `case "INVOKE"` dispatch. It reads the call's real
  stack-passed byte count and either takes the ordinary `emitAlignedCall` path (zero stack bytes, the overwhelmingly common case) or,
  with at least one stack-passed argument, retroactively prepends the correctly-sized reservation to the front of the call's buffer
  (`callBufferStack`, opened at the call's `CC_START`), emits the bare call, and restores rsp afterwards.
  - With no open `CC_START` bracket (should not happen: every bytecode-level `CALL`/`INVOKE` is wrapped in `CC_START`/`CC_END`) it
    falls back to the always-safe zero-stack-argument path rather than guessing.
  - win64 always reserves a fixed 32-byte shadow space just below the return address IN ADDITION to real stack-passed arguments
    (`AddressLoweringPass.resolveArgWordSlots` bakes this into the callee-side offset formula as `convention.shadowStack`).
    `emitAlignedCall`'s zero-stack path reserves/undoes this around its call; this path needs the same reservation, placed AFTER the
    stack-passed arguments are pushed (not right after the alignment slot) so it sits adjacent to the return address where the callee's
    formula expects it. 32 is a multiple of 16, so it never changes the alignment parity `alignPadFor` solved. It is suspended (not
    tracked) for the same reason as in `emitAlignedCall`: it is fully undone before the call's bracket closes.
  - SysV `%al` rule: identical to `emitAlignedCall`'s non-win64 branch (see "SysV varargs `%al`" above). This path previously set `%al`
    to nothing, leaving whatever was in `%eax`; fixed alongside the zero-stack-argument site as the same class of gap. Only reachable
    for the AT&T-syntax `linux` target (the win64 path is unconditionally `winAbi`, so it never reaches that `else`).

### `emitLineInner`: labels, pending value tag, mnemonic entry
- A bare label line (e.g. `@loop_1:` or `end_of_gt_routine__main:`) is carried over as a real assembly label. `@` is not valid in a
  real label so it is mangled to `L_`; a label with no `@` is used unchanged, which is what a `JMP` to the same text must also do (see `mangleLabel`).
- **Value-block tag** (`core.lastValueBlockSize`, see "value-block" handling in `PUSH`/`LOOKUP_ARRAY`/`DOT`): on every instruction the
  pending tag is captured into `pendingBlockSize` and reset, and only restored where it must survive (an intervening small `PUSH`,
  e.g. an index or a field offset).
- A pending tag survives ONE intervening `PUSH` of a word (the index of `PUSH arr; PUSH idx; LOOKUP_ARRAY`), not two. In `z + d[i].a` the small
  `PUSH 4 z` before `PUSH 8 d; PUSH 8 i` used to tag the pointer lookup as an in-register small array (a float sum silently kept its old
  value with the register-form passes off). A word `PUSH` that passed a tag on is remembered (`core.pushedWordWithTag`); a second `PUSH`
  right after it drops the tag.

### `emitCases1` mnemonics
- **`FUNC_END`**: a void function with no explicit `return` never emits a `RET` line; control falls through into this copy of the
  epilogue (see `RET`'s own doc for the case where a real `RET` runs first). `RET`'s "size 0" branch zeroes `%rax/%eax` before
  handing off to `emitFunctionEpilogue` so a void function's exit code is predictable, but that zeroing lives on the `RET` line, so a
  function that falls off the end skipped it. Found as a live bug: a bare `func main() void { printf(...) }` exited with printf's own
  return value (6 for a 6-character format) instead of 0. A non-void function can never reach this point live (the type checker
  requires every path to return a value), so zeroing `%rax` here unconditionally is safe: it only ever executes for a void fall-through
  and is otherwise the same harmless dead code after an already-emitted `RET` that the method's own doc describes.
- **`PUSH` with 2 tokens (`PUSH <bareName>`)**: a class/type tag operand (`INSTANCEOF`'s right-hand side, e.g. `PUSH Circle`), not an
  ordinary `PUSH size operand`. `INSTANCEOF` was one of the first pass's documented gaps (see the class doc and CLAUDE.md): the lowering
  pipeline did not resolve this name to a runtime class tag, so there was nothing safe to push; it is flagged, not guessed (this used to
  crash outright; "fix that bug", confirmed directly).
- **`PUSH size $offset` / `%x` (narrow value, small fixed array)**: when nothing was pending and the value is narrower than a full 8-byte
  pointer, it can only be a whole small fixed array (`letters: mut char[3]`) pushed by value as one natural-byte-order word, about to
  feed a `LOOKUP_ARRAY`. It is encoded as a NEGATIVE size in `lastValueBlockSize` so `LOOKUP_ARRAY` can tell this shape from the multi-word block
  case below (whose reversed-word layout would extract the wrong byte). An unrelated narrow scalar read harmlessly carries the tag for one instruction too,
  but the bytecode never follows one with `LOOKUP_ARRAY`/`DOT` except in a real access chain, so nothing misreads it. Bug found this way:
  this shape used to leave `lastValueBlockSize` at 0, so the following `LOOKUP_ARRAY` fell into its "no block pending" fallback (built
  for an unsafe dynarray's bare pointer value) and treated the packed array as a memory address: a live segfault, confirmed with
  `struct_array_member_narrow_elem_cg_test`'s `letters: mut char[3]` member.
- A small push right after a pushed value-block is always the index / field offset that a following
  `LOOKUP_ARRAY` / `PUSH_FIELDNAME`+`DOT` consumes alongside that block, so the pending block size is preserved across it.
- **`PUSH size $offset` with size > 8 (multi-word value)**: a small struct passed/copied whole (`PUSH 24 $-64`, `PUSH 48 $-200`), or an
  array pushed by its full declared size for a following `LOOKUP_ARRAY` read. Confirmed against `array_lookup_cg_test.caspien`'s
  low-order output: a read (`PUSH 40 $-40 / PUSH idx / LOOKUP_ARRAY 8`) pushes the whole array by value, while a write
  (`ADDR 40 $-40 / PUSH idx / LOOKUP_ARRAY_LHS 8`) pushes only its address: two different conventions for the same `LOOKUP_ARRAY` family,
  not a codegen choice. It pushes ceil(size/8) whole words, lowest source offset first, so the highest-offset word ends on top (the same
  convention as `DEREF`'s multi-word case and the `POP $addr size` block store), and remembers the total size for `LOOKUP_ARRAY`/`DOT`.
  - EXCEPTION: when the line is itself a member of a construction run already resolved by `precomputeConstructionOffsets`
    (`pushStoreOffsetByLine`), i.e. a whole, already-built value (e.g. a hidden RVO temp local) fed as ONE >8-byte entry into a following
    `NEW` / construction-scoped `ASSIGN`. That terminator's straight `rep movsb` copy (see `NEW`'s doc) assumes the stack bytes are in
    natural ascending-offset order. This is true for an ordinary multi-field literal (each field reserves and writes only its own width
    via `storeConstructionOrPlainPush`, at its own position) but false for the reversed "highest-offset word on top" order of the ordinary path, which
    silently swapped a struct's front and back words once copied. Found as a live bug with `new` constructing a value already built by a separate
    call (a constructor called via its stack-RVO destination pointer, then handed to `new` as one complete value): `new MyClass(11,13)` read back
    `y` as `1` (the hidden `classId`, the front-most word) instead of `13`. `pushBlockFromFrameConstructionAware` keeps the bytes in natural order and lands them at the
    packed offset `populateDirectMembers` computed (0, unconditionally, when this is the run's only entry; a whole-value feed always is,
    since `collectRunEntries` only splits a value into several entries at real separate `PUSH`/`ATOMIC_PUSH`/`STACK_LOCK` lines and a
    single built value is one line), so the terminator's straight copy sees the bytes it assumes.
- **`PUSH` float literal**: a float literal's IEEE-754 bit pattern is pushable through the same integer-immediate/`pushq` mechanism: this
  instruction and the `ASSIGN`/frame-load machinery treat every value as opaque bytes of the declared `size`, so nothing SSE-specific
  is needed to put a literal's bits in memory. Float arithmetic (`ADD_FLOAT`/`SUB_FLOAT`/`MUL_FLOAT`/`DIV_FLOAT`), comparison
  (`EQ_FLOAT`/`NEQ_FLOAT`/`LT_FLOAT`/...), `RET_FLOAT` and `FLOAT_CHECK` (the finite/infinite/nan classification `match` lowers to) are implemented elsewhere in the
  backend and no longer fall to the generic TODO (verified with a program using all three `FLOAT_CHECK` categories (finite, 1.0/0.0, 0.0/0.0):
  zero TODOs in the assembly and it printed `finite`/`infinite`/`nan`).
- **`PUSH N.start` / `N.end`** (regex `\d+\.(start|end)`): a synthesized pseudo-member push against an INCOMPLETE range value. Part of the same
  WITHIN/incomplete-range gap documented elsewhere (the lowering pipeline leaves this operand symbolic even at low order); flagged rather than mangled into a bogus symbol.
- **`PUSH ARGn`**: a calling-convention register-transfer slot read directly as a value, the callee-side counterpart of `POP ARGn`. Every real
  site is the first instruction touching registers right after `FUNC_START`/`ALLOC` (storing an incoming parameter to its local), so the
  argument register still holds its true incoming value.
- **`PUSH FARGn`**: the float-bank counterpart: the incoming parameter's bits are already in a real xmm register at function entry (never a GPR),
  so the raw 64 bits are moved out with the bit-copying `movq` used by every other float op, rather than reading a GPR that was never written.
- **`PUSH <string-pool id>`**: always the string's address (a string literal decays to a pointer to its `.rodata` bytes, like a real array/struct
  global; there is no separate value to push). Confirmed against every string-literal `PUSH` site (printf's format-string argument,
  `NEW_FROM_STRING`'s source operand).
- **`PUSH <bare global / ALLOC_STATIC name>`** (possibly dotted): ordinarily its VALUE (same "read what is stored" semantics as a local's
  `PUSH size $offset`, and what `ATOMIC_PUSH` does explicitly and atomically for the same case). History: the pass originally pushed the
  ADDRESS of every bare global; that happened to work for the ghost-table lock but silently broke `gt_destruct`'s ghost-table search the first
  time a test exercised it (`ghost_table.len` is stored as 0 but its address is a large nonzero number, so an always-skipped search loop ran with
  garbage bounds and dereferenced garbage: a confusing segfault, bisected to this convention split). The one confirmed exception is a global
  immediately feeding an `ATOMIC_SWAP` two lines ahead (`lineAtOffsetIsAtomicSwap(2)`; the lock-acquire shape `PUSH lockGlobal / PUSH newValue / ATOMIC_SWAP`,
  an in-place memory swap that needs the address). Everywhere else (`ASSIGN`, arithmetic, comparisons, `ARG` passing) it is the value.
  - A multi-word global (a static array, most often, about to feed `LOOKUP_ARRAY`) is pushed as a whole value block, the same shape `PUSH size $offset`
    makes for a local of that size (`pushSizedOrBlockFromAddr`). Before this it fell to the single-word load, so only the array's first 8 bytes
    were pushed and `LOOKUP_ARRAY` indexed into (and dereferenced) that word as if it were the array: reading any element of a static array segfaulted.
  - A narrow global with nothing pending gets the same negative-size tag as the local narrow case (a narrow value may be a whole small static array
    about to feed `LOOKUP_ARRAY`).
- **`PUSH <function name>`** (`declaredFunctionNames`): a real function's address. Never dereferenced afterwards, unlike a global: the value IS the address.
- **`PUSH <char literal>`** (`X86Core.isCharLiteral`/`decodeCharLiteral`): `'a'`, `'\n'`, `'\0'`, ... in `BytecodeEmitter.escapeForBytecode`'s serialised form: a
  single quote, one character's content (one printable byte, or a two-character `\x`-style escape for any escape the lexer
  recognises: `\\ \" \n \t \r \0 \b \f \a \v`), then a closing single quote. It is decoded like an integer/float literal to its numeric byte value and
  immediate-loaded into `%rax` (a char is one compile-time-known byte). History: this operand shape once had no branch, so every char literal in the language fell
  to the generic "not a declared global" placeholder and pushed 0 instead of the character, in every context. 22 example fixtures hit it, including three that
  had been tracked as unrelated "pre-existing baseline failures" (`array_literal_narrow_elem_cg_test`, `struct_array_member_narrow_elem_cg_test`,
  `struct_mixed_width_cg_test`): one bug the whole time.
- After any of the non-`$offset` forms (a literal index, `null`, an `ARG` read, a global address): they can legitimately be the index/offset half of a
  `LOOKUP_ARRAY`/`DOT` chain, so a pending value block from just before is preserved through them (`core.lastValueBlockSize = pendingBlockSize`), as in the small `$offset` branch.
- **`ASSIGN` / `ATOMIC_ASSIGN`**: stack top to bottom is value, address. The three `size` operands (always equal) give the real store width.
  They were once ignored (always a full 8-byte store), which is wrong for anything narrower, e.g. a struct's `bool`/`u8`/`f32` field between other fields.
  A plain aligned store is already atomic on x86-64, so `ATOMIC_ASSIGN` needs no different instruction, only the same width-aware store.
  - Construction-scoped store (`constructionScopedAssign`): a plain stack-resident struct/array/primitive literal's top-level store. `%rsp` already
    holds a byte-exact, tightly packed image of the value (every push in the run reserved only its own declared width), exactly the trivial,
    struct-agnostic copy `case "NEW"` does for the same shape, but to an address already on the stack (below the value block) rather than a fresh malloc, so no allocation call
    is needed. Checked BEFORE the `size <= 8` case: a small (say 4-byte, four `char`) construction is as real a construction as a large one, its value block was reserved
    byte-exact, not as one 8-byte word, so the plain scalar pop/pop path would desynchronise (popping 8 bytes of a 4-byte-reserved block spills into the address word above it).
  - A stack struct literal whose field values were not all plain pushes (an expression, a call, ...): every field was pushed as a whole 8-byte
    word, so the block-copy paths would misread the stack. The layout descriptor `LowerOrderGenerator` appended says where each field goes.
  - Otherwise, not a recognised construction: an ordinary whole-value block copy (`let b = a`, passing a struct/array by value, ...), whose image came from `pushBlockFromFrame`'s
    uniform whole-8-byte-word convention. Handled the old way, popping one word at a time.

## X86Generator.java

The register-form (`R_*`) helpers that do not live in `X86RegForm`, callee-saved register handling and `generate`. Its former class comment is the
overview of the whole x86-64 backend and is recorded here.

### Overview of the backend (former class comment)
The one backend so far: x86-64, either target. It began as a substantial second pass, not a finished code generator ("begin building the fourth
program... this will generate assembly instructions from the lower order bytecode", and a later "fix that [PUSH] bug and then work thru the remaining 70+
[mnemonics]", both confirmed directly). It now translates the great majority of the real low-order instruction set (see the mnemonic list in `emitLine`'s own switch).
Anything unresolved is emitted as a visible `; TODO(codegen): <line> not yet implemented` comment rather than dropped or guessed; the project's CLAUDE.md "Known gaps"
holds the list and the reasons. Some needed a runtime-protocol decision that did not exist (GT_UNWIND / async), or a fix one layer up in the lower-order generator
(WITHIN's incomplete-range case and INSTANCEOF's class tag, both still carrying unresolved symbolic operands even at low order, so codegen alone cannot translate them safely).
(These gap statements are historical and may be stale; GT_UNWIND and async are handled by the backend today, see Codegen/CLAUDE.md.)

**Translation strategy.** The bytecode is a stack machine (`PUSH`/`POP`/`ADDR` each push or consume one 8-byte cell) and the host has a real stack, so in the
first version every bytecode-level push/pop is translated 1:1 to a real `push`/`pop` of the hardware stack. This is deliberately not an optimising translation (a real backend would keep
short-lived values in registers; the later register-form passes do exactly that for `R_*` lines), it is a simple, easy-to-verify mapping, chosen so the output could be checked by assembling and running it.

**Memory layout decisions the pass made on its own** (nothing else in the pipeline reads a dynarray's or ghost-registered heap pointer's bytes except the mnemonics this backend implements, confirmed by
grepping every sibling project's source for DOT/PUSH_FIELDNAME-style structural access to one; so the backend is free to pick any self-consistent representation):
- A dynarray value is a pointer to a malloc'd block laid out `[8-byte len][8-byte capacity][elements...]`. (comment said) `len` and `capacity` are always kept equal, no growth slack, "for this first version's own simplicity"; see
  `LEN`/`RESIZE`/`URESIZE`/`LOOKUP_DYN(_LHS)`. NOTE: this is stale; project CLAUDE.md says `resize` is now amortised (growth past `cap` reallocs to max(newCount, 2*cap), a shrink below cap/4 reallocs down, a fit only rewrites `len`; `clone` gives cap = len).
- Every other heap value (`NEW`/`CLONE`) is a bare malloc'd block of exactly the given size, no header.

**Targets.** Two concrete targets share the class, distinguished by `CodegenTarget` (from `codegen.config`); both emit GNU assembler (GAS) AT&T syntax:
| Target | Convention |
|---|---|
| `LINUX_X64` | SysV calling convention (rdi/rsi/rdx/rcx/r8/r9) |
| `WINDOWS_GNU_X64` | win64 convention (rcx/rdx/r8/r9 + 32-byte shadow space), assembled and linked with mingw-w64 gcc |

Both are run in the project's test sandbox (Windows under Wine). There is no MASM/Intel-syntax target: it was removed on purpose.

**Source layout** (composition, not inheritance; Java has no partial classes and one file would be 7000+ lines): `X86Backend` is the public hub that builds the shared state
`X86Core` (fields, constants, static helpers) and the parts: `X86Emit` (emission helpers, construction runs, sized moves, block copies), `X86Calls` (argument registers, aligned calls,
`emitLine`/`emitLineInner`, the first mnemonic group), `X86CasesA` and `X86CasesB` (more mnemonic groups, `emitCasesN`, each a `switch` that returns false for a mnemonic it does not own),
`X86RegForm` (register-form `R_*` helpers) and `X86Generator` (the rest of the register form, callee-saved handling, `generate`). A part reaches the state as `core.x` and another part as
`hub.<part>.x`. (comment said) "No file is over 1500 lines": X86Generator.java is now 1536 lines, so this is stale.

### Constant multiplies
- `rfMulByBigConst(d, aTok, k)`: `d = a * k` for k = 2^n + 1 or 2^n - 1 above 2^31 (too big for an imm32 `imul`, which would need a 10-byte `movabs` plus a 3-cycle `imul`), as
  `mov a, d; shl n, d; add|sub a, d`. `a` is read again, so `a == d` goes through the scratch register. The low 64 bits equal the imul's. Returns false (nothing emitted) for any other k.
- `rfMulByConst(d, k)`: `d *= k` for a constant k as shifts and adds instead of an `imul` (3 cycles latency; the shifts are 1 + 1): a power of two is one `shl`; 3, 5 and 9 are one `lea`;
  2^n + 1 and 2^n - 1 are `mov scratch; shl; add|sub scratch` (so `x * 31` is `(x << 5) - x`). The low 64 bits equal the imul's, which is all a register holds (narrow products are never masked there anyway). Returns false for any other k.

### Fused compare-and-branch forms
- `rfBrcMem(op, aTok, baseTok, label)` (`R_BRCM`): jump to `label` when NOT (`a op [base]`), where `[base]` is the 8-byte word at the address held in the register `base`
  (the safe dynarray length word: `cmpq (%base), %a`). `a` is a `%t`/`%v` register or a frame slot.
- `rfBrcNarrow(op, size, aTok, bTok, label)` (`R_BRC` of a 1, 2 or 4 byte compare): one sized cmp (`cmpb`/`cmpw`/`cmpl`) of the low bytes of two registers, or a register and an immediate, then
  the jump taken when the comparison is false. Unsigned operators use the unsigned conditions and `S*` the signed ones, which on a sized cmp look at exactly the low `size` bytes (what the unfused
  `R_BIN` did by extending both operands first). Frame-slot operands are never fused (`BranchFusionPass`).
- `rfCmov(line)` (`R_CMOV OP n a b %vK X [Y]`): the select made by the LowerOrderGenerator's `ConditionalMovePass`: `%vK = (a OP b) ? X : Y` (Y absent: `%vK` keeps its value). `X` and `Y` are a register or an immediate
  (already cut to the variable's width). One compare, then `mov Y` (flags untouched) and `cmovCC X`.

### Indexed accesses (`R_LDI`/`R_STI`/`R_LDXI`/`R_STXI`)
- `rfIndexedBase(baseTok, scratch)`: base of an indexed access. A global is loaded into `scratch`, a frame slot is addressed off `rbp` with a displacement, a temp or variable register is used as it is.
  Sets `rfIdxDisp`; the caller resets it.
- `rfSetIndex(baseTok, idxTok, scale)`: index register + scale. A hardware scale (1/2/4/8) is used as it is; any other stride (a struct element: 24, 56 ...) is multiplied into the scratch register first
  (`imulq $scale, %idx, %r15`, the variable is not touched) and the operand becomes `disp(%base,%r15,1)`. The base of such an access must not be a global (a global base address already needs the scratch register).

### Variable-register marks
`varMarksNeeded(start)`: expands every V1314 mark pair (`@@V1314S@@` ...): a variable register (r12-r14) is saved and restored around an instruction only when the instruction's own code mentions that register
(most scratch users touch none of them); pairs that need nothing disappear. (Feature: `variables-in-alloc-functions`, see CLAUDE.md.)

## X86Core.java

State and static helpers shared by every part of the x86-64 backend (overview: `X86Backend`, `X86Generator`). It is a plain state holder with no behaviour beyond a few static helpers. The six parts (`X86Emit` .. `X86Generator`) are separate classes that reach this state, and each other, through the `X86Backend` hub.

### Callee-saved register preservation (per function)
- Any generated function that touches a callee-saved register of the target ABI (SysV: rbx, r12-r15; win64 also rdi, rsi, xmm6-15) saves it in its frame and restores it on every way out (`RET`, the `FUNC_END` fall-through and `GT_UNWIND`).
- The uses are only known once the whole body is emitted, so every exit emits `CSR_MARK` (`"@@CSR@@"`). At `FUNC_END`, `finishCalleeSaved` finds which callee-saved registers the function's text touches, grows the function's single `ALLOC` by their save area (kept a multiple of 16), stores them right after it, and replaces every `CSR_MARK` (one per exit path, including `GT_UNWIND`) with the matching restores, or with nothing. A function that touches none is left as it was.
- `vSlots` (3 entries): frame slot of the saved copy of r13, r14, r12 (null: the function keeps no variable there).
- `V1314_SAVE` marks: instructions whose code uses r12/r13/r14 as scratch (`NEW*`, `CLONE*`, `RESIZE*`, `DOT`, `LOOKUP_ARRAY`) are bracketed by two marks. `finishCalleeSaved` turns them into saves/restores of the variable registers (`%v0..%v2` = r13, r14, r12) of the functions that actually keep a variable there, and deletes them from every other function (whose text is then exactly what it was without marks).

### Function epilogue (stale-position comment, no code attached)
The real function epilogue restores `%rsp` from `%rbp` (discarding this frame's `ALLOC` reservation and any leftover stack-machine push/pop noise in one move, the same way the `FUNC_START` prologue reservation is undone regardless of how it was used), restores the caller's `%rbp`, then executes the hardware `ret`. It is emitted once at `FUNC_END` (a function's true textual end) and again at every `RET`/`RET_FLOAT` site (every control-flow-reachable exit point); a single copy at `FUNC_END` alone is not enough. A `RET` immediately followed by `FUNC_END` (the common, non-nested case) simply makes `FUNC_END`'s copy dead, unreachable code, which is harmless.

### Declaration tables (first pass)
- `globalsSizeInit`, `stringLiterals` and related maps are collected on a first pass so the declarations can be emitted into proper `.data`/`.rodata`/`.bss` sections ahead of `.text`, instead of wherever they sit in the linear bytecode stream. `GLOBAL`/`STRING`/`ALLOC_STATIC`/`EXTERN` lines are legal anywhere (interleaved with function bodies), but real assembly sections cannot be interleaved the same way.
- `floatPool` (AT&T targets only): float-constant pool. Every float immediate a register-form float op needs is stored once in a read-only section and used as a memory operand (`mulsd .LFC3(%rip), %xmm0`) or loaded with one `movsd`, instead of `movabs $bits, %r15 ; movq %r15, %xmm` on every use. Key `"n:bits"` -> label; n = 4 or 8.
- `declaredFunctionNames`: every real function this compilation unit defines (`FUNC_START name` lines, collected on the same declarations pass as globals/strings). It lets a bare `PUSH size funcName` (a real function's own address pushed as a value, needed for the compiler-synthesised trampoline function pointer of `par`/`await`; see the compiler's CLAUDE.md, the `par`/`await` real-thread desugaring) be told apart from an unresolved name the pass does not understand, which would otherwise fall through to the generic "not a declared global" TODO placeholder. A function's value is its address (there is no separate stored value to load as for an ordinary global), so the push uses the label's address directly via `leaGlobalToReg`, the same helper a string literal's `PUSH` uses for the identical "decays to its own address" reason; it is never followed by `loadSizedFromAddr` the way an ordinary global `PUSH` is.
- `globalAliasOffset` / `globalAliasParent` / `aliasInits`: dotted `parent.field` `GLOBAL` declarations where `parent` is also separately declared as a whole-block global (e.g. `GLOBAL ghost_table 40` alongside `GLOBAL ghost_table.lockState 8 0`) are not independent storage. Found through a real, confusing bug: `gt_init`/`gt_destruct`'s lock acquire/release read and wrote `ghost_table.lockState` as a bare global (its own separate `.data` slot) in some places but computed the same field's address via `ADDR 40 ghost_table` + `PUSH_FIELDNAME`/`DOT_LHS` (an offset into the `ghost_table` block) in others, so the unlock write and the lock-check read landed in different places and the lock never appeared to clear. The fix: a dotted `GLOBAL` whose prefix (up to the last `.`) names another declared whole-block global is a pure alias with no storage of its own, resolving to the parent's address plus the cumulative size of any same-parent dotted fields declared before it (matching the `PUSH_FIELDNAME` offsets used elsewhere for the same struct). `aliasInits` is parent -> `[offset, size, bits]` of each initialised element (see `X86Emit.java`, "Global declaration pass").
- `allLines`: set only while `generate()` is walking the line list, so a bare global-name `PUSH` can peek a couple of lines ahead to tell whether it feeds an `ATOMIC_SWAP` (needs the global's address) or anything else (needs its value); see the `PUSH` case for why both are needed.
- `asmSkipUntilIndex`: set by the `ASM_START` case to the index of the `ASM_END` line that closes the block it just copied verbatim, so every raw line in between (already emitted by that one case) is skipped by the main per-line dispatch instead of falling through to the generic TODO comment a second time. -1 when no block is open.

### Construction-scoped push detection (`pushStoreOffsetByLine`, `constructionScopedAssign`, `packedNewLines`)
Algorithms are in `X86Emit.java` (`findRunStart`, `collectRunEntries`, `populateDirectMembers`, `precomputeConstructionOffsets`).

- A struct/array/primitive construction (`X{...}` / `new X{...}`) emits a run of `PUSH`/`ATOMIC_PUSH`/`STACK_LOCK` lines immediately followed by a `NEW <size>` line whose declared size equals the sum of that run's own sizes/gaps. Within such a run each push can safely write fewer than a full 8-byte word (its own declared width) instead of the usual always-8-byte `pushq`, because `NEW` (see its case) does one straight block copy starting at `%rsp`, which is byte-exact, matching `computeStructLayout`'s tightly-packed layout, only when every push and `STACK_LOCK` gap in the run reserved exactly its declared width. An ordinary push anywhere else (comparison operand, call argument, `if` condition, ...) is never part of such a run and keeps doing a full `pushq`.
- Nesting subtlety (found by running a nested-construction repro: a struct with an `owns` member itself built via a nested `new NestedStruct{...}`): a purely linear backward/forward scan over raw `PUSH` text cannot tell an outer run's field pushes from an inner, completed construction's field pushes sitting textually in the middle of it; both are just `PUSH size operand` lines and only the `NEW` lines mark where a run ends. From the outer run's perspective a nested `new NestedStruct{...}` field contributes exactly one 8-byte pointer word (its `NEW`'s final always-8-byte `pushReg`), never the inner raw field bytes, which are consumed by the time that inner `NEW` returns. So the computation is a real recursive (any-level) parse, not a flat scan: a `NEW` met while walking a run backward is treated as one complete, opaque, resolved 8-byte unit (after recursively confirming its own run resolves to its own declared size).
- Back-to-back constructions: two independent constructions can touch with zero instructions between them (a field whose value is itself `new Nested{...}`, the last field of the outer literal: the inner and outer `NEW` are on consecutive lines). Processing every `NEW` line in the function independently (each validating and resolving only its own direct, non-nested members) handles this without either run's bookkeeping leaking into the other's.
- `pushStoreOffsetByLine` is precomputed once per `generate()` call (`allLines` does not change during it), keyed by line index; `case "PUSH"` does a plain map lookup rather than re-scanning per instruction.
- `constructionScopedAssign`: line indices of an `ASSIGN size size size` / `ATOMIC_ASSIGN size size size` whose preceding run of pushes was confirmed (by the same `findRunStart`/`populateDirectMembers` used for `NEW`) to be a freshly built construction, a plain non-heap literal (`X{...}`, not `new X{...}`) left stack-resident rather than copied into a heap buffer. Populated by `precomputeConstructionOffsets` alongside `pushStoreOffsetByLine`, from the same scan.
  - Bug it fixed: `precomputeConstructionOffsets` used to look only for runs ending in a `NEW <size>` line, so the "each push reserves only its declared width" treatment (described as construction-agnostic: "NEW's whole job is now trivial and struct-agnostic") was in practice coupled to heap allocation. A stack literal with any field narrower than 8 bytes (`let p = mut Mixed{flag=true, a='X', b='Y', id=123456789}`, no `new`) never went through the scan, so its narrow fields fell back to the old blind always-8-byte `pushReg`/`assignBlock` word-popping path, correct only when every field happened to be 8 bytes wide. Confirmed by two fixtures already in the corpus: `struct_mixed_width_cg_test` (a segfault from writing to the wrong stack slot) and `struct_array_member_narrow_elem_cg_test` (an invalid `movzqq` instruction that fails to assemble).
  - The fix: the same run detection is now also triggered by an `ASSIGN`/`ATOMIC_ASSIGN size size size` line (three equal sizes, > 8: the shape of a literal's top-level store), not only `NEW`. No new detection logic: `findRunStart`/`populateDirectMembers` are reused unchanged; the set only remembers which `ASSIGN` lines qualified so `case "ASSIGN"` can pick the same "trivial byte-exact copy" treatment `case "NEW"` uses, instead of the per-word `assignBlock`. An ordinary whole-value block copy (`let b = a`, an existing struct/array variable re-pushed via a single `PUSH size $offset`) still needs the old path (see `precomputeConstructionOffsets`).
- `packedNewLines`: `NEW` lines whose run of field pushes `precomputeConstructionOffsets` resolved into one clean packed image (every direct field a plain `PUSH`/`STACK_LOCK`, sizes summing exactly to the declared size). Any other `NEW` (a field value that is an expression, a call, a moved `owns` variable, a nested `new`/`dyn(...)` with its out-of-memory check code) is NOT packed: every field was pushed as a blind 8-byte word (a wider field as a word-reversed block), so the stack holds those words in REVERSE field order and `NEW`'s straight block copy would put the fields backwards (the type id where the first field belongs, and so on). Those go through `emitNewRepack` instead, using the struct layout descriptor `LowerOrderGenerator` appends to the `NEW` line.

### Call-argument stack tracking and `CC_START` buffering
Fields: `argTrackDeltaStack`, `argTrackSuspendDepth`, `callBufferStack`, `loadedArgRegsStack`, `savedOuterArgRegsStack`, `floatResultInR10`, `pendingVarargsXmmCount`. Implemented in `X86Emit.raw()`/`trackStackDelta`, `case "CC_START"`, `"CALL"`/`"INVOKE"`, `"CC_END"`, `emitAlignedCall`, `resolveStackArgBytesAndCall`.

**The bug.** The real fix for a real segfault (`register_overflow_args_even_cg_test.caspien` / `_odd_cg_test`, a plain function with 8 or 9 `u64` parameters, more than sysv_x64's 6 integer argument registers). A stack-passed argument word is already emitted as a bare, un-popped `PUSH size operand` (see `BytecodeEmitter.emitArgTransferTail` on the compiler side: a register-passed argument gets a `POP ARGn`/`POP FARGn` right after its value is computed, a stack-passed one gets nothing). This backend's `PUSH`/`POP` are real one-to-one `pushq`/`popq` on the actual x86 stack, so every such deliberately un-popped word stays in real memory until the `call`. A second, independent bug (found only after the first was fixed and the program printed a wrong value instead of segfaulting): `emitAlignedCall`'s 16-byte alignment reservation used to land between those pushed words and the return address, so the callee's correct `%rbp+16+n*8` read formula read the alignment machinery's saved-rsp slot instead of the argument. Both are caller-side stack accounting/ordering bugs, not specific to integers or to one calling convention: they fire identically for a stack-passed float, a narrow type, or a call under win64, since `emitAlignedCall` / `CALL` / `INVOKE` are the one shared convention-agnostic choke point every call goes through.

**The fix.**
1. The 16-byte alignment reservation happens at `CC_START`, before any argument is pushed, sized to account for the number of bytes the stack-passed arguments will add before the call.
2. That number cannot be known from the bytecode text alone: an argument's `PUSH ... ; POP ARGn`-or-not shape can have any number of intermediate lines. Confirmed regression: `raw arr` compiles to `PUSH 24 $-24 / ADDR_OF RAW $-24 / POP ARG0 8`; a whole 24-byte array block is pushed, collapsed to an 8-byte pointer by `ADDR_OF`'s own codegen, then popped into a register, and a naive "is the very next line a POP ARGn" check misclassifies it as 24 stack-passed bytes and corrupts an otherwise correct all-register call. Special-casing every mnemonic whose codegen can shrink or discard a pushed value (`ADDR_OF`, `DOT`, `LOOKUP_ARRAY`, `CLONE`, ...) is the whack-a-mole this class of bug warns against.
3. So the backend measures the real thing: it tracks every actual `pushq`/`popq`/`add rsp,N`/`sub rsp,N` it emits between a call's `CC_START` and its `CALL`/`INVOKE` (`argTrackDelta`, updated from `raw()` itself). Bytes still unaccounted for at the `CALL` line are, by construction, exactly the stack-passed argument bytes: every register-passed argument's production sequence, however long, nets to zero when its `POP ARGn`/`POP FARGn` runs.
4. Emission is therefore deferred. The alignment reservation must be the first thing emitted for the call, but its byte count is known only at `CALL`. `case "CC_START"` emits nothing immediately; it opens a fresh buffer (`callBufferStack`) that every `raw()` call until the matching `CC_END` writes into instead of `out`. `case "CALL"`/`"INVOKE"`, now knowing the byte count, prepend the correctly sized reservation to the front of that buffer, then append the bare call and its restore. `case "CC_END"` closes the buffer, flushing its contents into whatever buffer (or `out`) is active one level up, so a call nested in another call's argument expression (`foo(bar(), x)`) buffers and resolves independently.
5. The common all-register case (byte count 0 at `CALL`) is unchanged in its assembly: it still goes through the original `emitAlignedCall` wrapping just the `call`; buffering/flushing is a no-op change in output.

**Fields.**
- `argTrackDeltaStack`: one running byte tally per open `CC_START`/`CC_END` bracket.
- `argTrackSuspendDepth`: suppresses `argTrackDeltaStack` tracking for the duration of a call wrapped by `emitAlignedCall` (a counter, not a boolean, because one such call's argument can itself trigger another). Everything `emitAlignedCall` emits (alignment reservation, win64 shadow-space adjustment, final restore) nets to exactly zero stack effect by contract, so none of it may be attributed to an enclosing bracket's total: it would double-count bytes that were only transient scratch.
- `callBufferStack`: one level of buffered, not-yet-flushed assembly text per open bracket, paired one-to-one with `argTrackDeltaStack` (both pushed at `CC_START`, both popped at `CC_END`). `raw()` writes into `callBufferStack.peek()` when non-empty, `out` otherwise. A nested call gets its own level; `bar()`'s buffer/tally resolve and flush at its own `CC_END` before `foo`'s `CALL` looks at its tally, so they never interfere.
- `loadedArgRegsStack`: per open bracket (paired with `callBufferStack`), the argument registers this call's `POP ARGn`/`POP FARGn` lines already loaded, as `"i<n>"` (integer bank) or `"f<n>"` (float bank), in load order. A call nested in the remaining argument expressions (`foo(a, bar(b), c)`) sets up its own arguments in the same physical registers and would silently overwrite what `foo` loaded (and `foo`'s later arguments too, when `bar`'s result is popped into a register `bar`'s argument setup clobbered). So `CC_START` pushes every register in the enclosing call's list before opening the nested bracket and `CC_END` pops them back.
- `savedOuterArgRegsStack`: the registers each open nested call saved at its own `CC_START` (the enclosing call's loaded argument registers, in push order), one list per open bracket, popped at the matching `CC_END`.
- `floatResultInR10`: true when the last `CC_END` had to restore xmm0 (float-bank argument 0) that a just-finished nested call may also have used to return a float: the possible float result was stashed in r10 first, and the next `PUSH_RET_FLOAT` reads it from there instead of xmm0.
- `pendingVarargsXmmCount`: the number of xmm argument registers the upcoming call's arguments used, for the one SysV varargs call this value reaches (the "`%al` must hold the number of vector registers used" rule; read in `emitAlignedCall`'s non-win64 branch and `resolveStackArgBytesAndCall`'s overflow branch). -1 = no pending value: the upcoming call is not variadic (the front end emits `VARARGS_XMM_COUNT` only immediately before a real variadic call's `CALL`/`INVOKE`; see `BytecodeEmitter.emitCallSequence`'s `varargStartIndex`), or the target is win64 (no such convention; consumed and ignored). Set by `case "VARARGS_XMM_COUNT"`, always consumed (read, then reset to -1) by whichever of those two `%al`-zeroing sites runs next, for exactly the call it was computed for. It is never left stale: every variadic call emits a fresh `VARARGS_XMM_COUNT`, and a non-variadic call in between would otherwise see a leftover count from an earlier call.

### Stride CSE (`strideCseHits`)
Common subexpression for the stride multiply `imulq $stride, %idx, %r15` of an indexed access (`rfSetIndex`). After the multiply r15 holds idx*stride. A following access with the same index register and stride reuses it when every line emitted in between is a plain data move / float arithmetic whose destination is neither r15 nor (any alias of) the index register, in the same sink. Anything else (label, jump, call, lea, integer arithmetic, a buffer append) forgets it. Flags: only the skipped `imul` would have set them.

### Static helpers
- `roundUpToLoadableWidth(int size)`: rounds a byte count up to a real loadable width, 1, 2, 4 or 8 (x86 has no 3, 5, 6 or 7-byte load). Asking `movSuffix` for one produced `movzqq`, which is not an instruction and used to be emitted verbatim (an assembler-rejected gap). Trigger: a struct with a packed sub-word array member, e.g. `letters: mut char[3]`, yields `PUSH 3 $offset` when the member is read back by value (a `LOOKUP_ARRAY` on it). Over-reading a few bytes is harmless: `ALLOC` always rounds a frame's size up to 16 bytes, so there is slack past any field, and every consumer of a sub-8-byte loaded value only reads back its declared low `size` bytes (array element extraction, narrow comparison, ...), never the whole register as an opaque scalar, so garbage in the unused high bytes is never observed.
- `decodeCharLiteral(BytecodeToken)`: decodes a char-literal `PUSH` operand to its byte value, the exact inverse of `BytecodeEmitter.escapeForBytecode`; it must stay in lockstep with it. Every escape that method outputs (`\\`, `\"`, `\n`, `\t`, `\r`, `\0`, `\b`, `\f`, `\a`, `\v`) is decoded, plus the plain unescaped single byte for every other character. A char is always one byte in this language (`PRIMITIVE_SIZE.put("char", 1L)` in the front end), so the result is a single 0-255 value.
- `divMagicSimple(long k)`: single-multiply unsigned division by k (not a power of two, k >= 3): returns `{pre, magic, post}` with q = mulhi(a >> pre, magic) >> post for every 64-bit a, or null when no 64-bit magic number exists (the add-fix-up form is used then). Granlund-Montgomery Theorem 4.2: for x < 2^N and m = ceil(2^(N+l) / k'), m*k' - 2^(N+l) <= 2^l gives floor(x*m / 2^(N+l)) = floor(x / k'). An even k first drops its trailing zero bits from the dividend (a >> pre, N = 64 - pre, k' = k >> pre), which makes a 64-bit magic number likely; pre = 0 is tried as well. The smallest l >= pre with m < 2^64 that meets the condition is taken; post = N + l - 64 = l - pre.

### Register-form instructions (`R_MOV` / `R_BIN` / `R_UN` / `R_BRF` / `R_PUSH`)
Emitted by `LowerOrderGenerator`'s `RegisterFormPass` (deferred operands). Operands:

| Operand | Meaning |
|---|---|
| `#imm` | an immediate |
| `$off` | an 8-byte frame slot |
| `%tN` | a temporary register |

Temps are only live between register-form instructions: the pass writes every one back to the real stack (`R_PUSH`) before any other instruction, so the backend's own sequences (which use rax/rbx/r11-r15 internally) can never collide with a live temp. The temp pool avoids every register that can be live across instructions: the argument registers of an enclosing call (both ABIs), r10 (`INVOKE` target / float-result stash) and xmm registers. r15 is the spare scratch, used only inside a single register-form instruction.

### Variable registers (`RF_VAR_REGS`)
Order: `r13, r14, r12, r8, r9, r10, rsi, rdi, rdx, rcx` (= `%v0..%v9`).
- `%v0..%v2`: promoted variables (`RegVarPromotionPass`), callee-saved, so `finishCalleeSaved` saves/restores them like any other.
- `%v3..%v5` (r8, r9, r10): caller-saved; the promotion only puts a variable there that is never live across a call or any line that could clobber them.
- `%v6`, `%v7` (rsi, rdi: SysV argument registers 1 and 0; callee-saved on win64, where `finishCalleeSaved` saves them when the text mentions them): same rule, and never live inside a call's argument bracket.
- `%v8`, `%v9` (rdx, rcx: SysV argument registers 2 and 3, SysV only): likewise, and never live at a constant divide (rdx) / variable-count shift (rcx).

### Float variables in xmm registers (`float-variables-in-registers: on`, `RegVarPromotionPass`)
Variable K lives in xmm(8+K) on SysV and xmm(6+K) on win64 (K < `XV_COUNT`). Both ranges avoid the argument registers (SysV xmm0-7, win64 xmm0-3) and the register-form scratch pair (SysV xmm14/15, win64 xmm4/5). win64 keeps xmm6-15 across calls, and `finishCalleeSaved` already saves/restores any of them a function touches (also on a throw). SysV keeps no xmm register across a call, so every call spills all of the function's variables to their home frame slots (declared by `R_XVAR`) right before it and reloads them right after; the catch entry reloads them too (control reaches it from an unwind, after the throwing call's spill). A variable's value is the f32 in the low 32 bits of the register. (Float temporaries `%y0..%y3`, `YT_COUNT`, have a one-line doc in the code.)

## X86Emit.java

Text-emission helpers and declaration-pass code of the backend: construction/run detection, global declarations, the `raw()` sink, shifts, block copies and repacking.

### Construction run detection (companion to "Construction-scoped push detection" above)
- `findRunStart(endExclusive, targetSize)`: returns the start index of the run of field productions ending exactly at `endExclusive` (walking backward from `endExclusive-1`) whose sizes sum to EXACTLY `targetSize`, or -1. A production is an ordinary `PUSH`/`ATOMIC_PUSH`/`STACK_LOCK` line (contributing its declared size/gap) or a complete nested `NEW <size>` (contributing exactly 8 bytes, the pointer it leaves, once its own run is confirmed, with an explicit stack, to sum to the same declared size). `targetSize` is load-bearing, not a sanity check: a nested construction's trailing field pushes are textually indistinguishable from the outer run's earlier field pushes (both are `PUSH size operand` lines with no separating marker); the only thing that tells where the nested run started is that its declared size (the nested `NEW`'s operand) is reached exactly. A scan without a target, greedily consuming every syntactically valid production, would walk past the nested run's true start into the outer run's remaining fields and silently miscount both. So it stops the instant the running total reaches `targetSize` (success) or would exceed it (failure), never "as much as looks valid".
- `populateDirectMembers(start, endExclusive, runTotal)`: fills `pushStoreOffsetByLine` for every direct (non-nested) `PUSH`/`ATOMIC_PUSH` member of the run `[start, endExclusive)`. Each entry's value is `trueOffset - remainingBytes`, the signed byte offset from `%rsp` after that push's own `subq` at which to store its value. Plain `(%rsp)` (offset 0) is wrong the moment a construction has more than one field: each field's `subq` grows the stack downward, which by itself lays fields out in the reverse of their struct order; `trueOffset - remainingBytes` corrects exactly that. A nested `NEW` met along the way is skipped as one opaque unit (it never needs a store offset: its own `pushReg` in `case "NEW"` is a plain always-8-byte push); its own direct members are populated separately when that `NEW` is processed by `precomputeConstructionOffsets`.
- `collectRunEntries(start, endExclusive)` (returns `X86RunEntries`): the shared forward scan used both to count a run's direct members and to lay out their store offsets (`populateDirectMembers`). Counting is how `precomputeConstructionOffsets` tells a genuine multi-field construction from an ordinary single-value `ASSIGN`: scalar assigns and whole-block copies alike are always exactly one `PUSH`, whatever their declared size, while any real struct/array literal has more than one, so the entry count, not the byte size, distinguishes them. Walking forward, a nested construction's field pushes are indistinguishable from the outer run's direct pushes until its terminating `NEW` is reached, so they are recorded provisionally like any direct member and collapsed retroactively when that `NEW` is seen: entries are popped off the back of what was recorded until their sizes sum to exactly this `NEW`'s declared size (an already-collapsed deeper `NEW` pops as a single 8-byte entry, so arbitrary nesting depth works), then replaced by one opaque 8-byte entry (the nested `NEW`'s pointer, pushed by its plain always-8-byte `pushReg`, never eligible for a store offset).
- `precomputeConstructionOffsets()`: runs once per `generate()` call. Finds every construction terminator: a `NEW <size>` line (heap construction), or an `ASSIGN`/`ATOMIC_ASSIGN size size size` line whose three sizes agree and exceed 8 (a plain stack-resident literal's top-level store). For each whose immediately preceding run of pushes resolves to that exact declared size (`findRunStart`) it records every direct member's store offset into `pushStoreOffsetByLine`, as it already did for `NEW` alone.
  - This one generalisation is the whole fix for the bug described under `constructionScopedAssign` (before, the "each push reserves only its declared width" treatment fired only ahead of `NEW`; a plain non-heap literal fell back to the blind 8-byte word path). Detection did not change: `findRunStart`/`collectRunEntries` are the identical methods `NEW` used; only which lines may trigger them grew a second case.
  - Three equal size operands alone do NOT distinguish a literal construction from an ordinary scalar assign or a whole-block copy (`let b = a`): the bytecode emits all three the same way, and a copy's declared size can be large (a big struct) just as a literal's can be small (a two-element `char` array, total 2 bytes). A size threshold was tried first and was wrong on both ends: it misses small literals such as `char[4]` and would misfire on a large whole-struct copy given a low enough threshold. What distinguishes them is the number of direct pushes feeding the `ASSIGN`: a scalar assign and a block copy are fed by exactly one `PUSH` (one value, or one `$offset` reference to the source block); a literal, however small, by more than one (one per member). The run is found the same way regardless (`findRunStart` does not care about intent), and only afterward, once `collectRunEntries` has counted the direct entries, a single-entry run is recognised as "not a construction" and left on the old, correct blind path.

### Stores of construction members
- `storeConstructionOrPlainPush(regName, size)`: after a value's bits are in `regName` (always `rax` today, kept general), either stores it at its precomputed tightly packed construction offset (see `populateDirectMembers`) when this `PUSH` line is a direct member of a struct/array/range/primitive construction, or else does the plain full-word `pushq` (`pushReg`), the identical fallback every `PUSH` had before construction packing existed. It is shared between every `PUSH` operand shape (`case "PUSH"`'s `$offset` branch and its generic literal/ARG/global fallback) so newly added operand shapes automatically participate rather than duplicating the decision. Bug it fixes: the `$offset` branch used to call `pushReg` unconditionally and never consulted `pushStoreOffsetByLine`, so a construction with a variable-sourced field (e.g. `0..index`, a range literal whose end is a parameter's value rather than a literal) got that entry pushed as an ordinary unrelated push, corrupting the packed image the terminating `ASSIGN`/`assignConstructionBlock`/`NEW` expects, with no symptom until the entry was consumed. Found via a real segfault in the ghost table's `gtSlotAt`/`gtWriteSlot` (`stdlib/gt/ghost_table.caspien`, `for i in 0..index`: a range literal with one immediate `0` and one frame-loaded `index` entry).
- `pushBlockFromFrameConstructionAware(baseOffset, size)`: the construction-aware sibling of `pushBlockFromFrame`, used only when this exact `PUSH size $offset` line is a direct member of a construction run already resolved by `precomputeConstructionOffsets`. Unlike `pushBlockFromFrame` (which reverses word order, matching DEREF's "highest-offset word on top" pushed-value convention), it reserves exactly this entry's declared width and copies its bytes straight across, front to back, in natural ascending-offset order, to the packed position (`constructionOffset`, possibly negative; see `storeConstructionOrPlainPush`) the run's layout computed. `%rcx` is saved/restored around the copy for the same reason `assignConstructionBlock` does: this can run inside a still-in-progress call's argument marshalling, where `%rcx` may carry a live, unconsumed incoming parameter. Inside, the copy goes through `rax` in 8/4/2/1-byte pieces. It used to be `rep movsb` with rdi/rsi/rcx and a `push rcx` around it; the push writes 8 bytes just below rsp, which is where the fields of this construction stored EARLIER already sit (each field is stored at its final place below the current rsp before rsp reaches it), so an earlier narrow field (`tag: u8` before an array member) was overwritten; and rdi/rsi are argument registers an enclosing call may already have loaded.
- `assignConstructionBlock(size)`: the construction-scoped counterpart of `assignBlock`, used only when `constructionScopedAssign` confirms the `ASSIGN` is a plain stack-resident literal's top-level store. Stack, top to bottom: a byte-exact `size`-byte tightly packed image of the value (every push in the run reserved only its declared width), then the single-word destination address pushed ahead of it. It mirrors `case "NEW"`'s trivial, struct-agnostic copy: one straight `rep movsb` block copy with no field count, offsets or type knowledge, except the destination is the already-pushed address rather than a fresh `malloc` buffer, so there is no allocation call. 2026-09 fix of a silent register-clobber bug (the project CLAUDE.md, "assignConstructionBlock clobbered %rcx" was cited): `pushq`/`popq %rcx` now bracket the `rep movsb` counter setup so a still-unread incoming argument register (SysV ARG3 / win64 ARG3, both `%rcx`) survives. Within it: the value block is fully popped, and the destination address word pushed ahead of it is still on top; it is dropped (`addq $8, %rsp`) only after it has been read.

### Repacking of unpacked constructions
- `emitNewRepack(size, line)`: for `NEW size m8 m8 p4 m1 ...` where the run of field pushes could NOT be packed (see `packedNewLines`). Every field production was pushed the ordinary way: a scalar as one full 8-byte word, a wider field as a word-reversed block (`pushBlockFromFrame`'s convention, highest-offset word nearest `%rsp`), each padding gap as an exact `subq`; so the stack region, read from `%rsp` upward, is the word-for-word REVERSE of the "padded image" (fields in declared order, each rounded up to whole words, gaps exact). It rebuilds the real tightly packed struct in a fresh heap buffer from that description: for each layout entry, copy its bytes from where that chunk sits on the stack to the entry's true offset in the buffer. Unlike the packed path it needs the layout, which `LowerOrderGenerator` appends to the line.
- `emitAssignRepack(size, line)`: `ASSIGN size size size m8 m4 p4 a4x3 ...` for a stack struct literal whose field pushes could not be packed (the stack twin of `emitNewRepack`): every scalar field was pushed as one whole 8-byte word, a wider field as a word-reversed block, each padding gap as an exact `subq`, so the stack region from `%rsp` upward is the reverse of the padded image, with the destination address in the word just above it. It copies each field's bytes to its true offset from that address, then drops the field words and the address. Scratch registers used: `%r10` (source base), `%r11` (destination) and `%rax` only.

### Global declaration pass
- Pass 1: collects every plain (non-dotted) `GLOBAL`/`ALLOC_STATIC` name, so pass 2 can tell a dotted `parent.field` declaration apart from one whose `parent` is not a separately declared whole-block global (that keeps its own independent storage, the pre-existing behaviour).
- Shapes: `GLOBAL name size` (bss, zero-init) or `GLOBAL name size initVal` (data, non-zero init). `ALLOC_STATIC name size initVal` is the identical shape for a function-local `static`, declared at its first-use point inside a function body instead of at top level.
- Nested members: a nested member (`b.0.x` of a static array of structs) has an already-aliased direct parent (`b.0`); it lives in the ROOT global at (parent's offset + running offset inside the parent). Before, only a plain parent was aliased, so the members of every struct element kept their own stray storage and the array itself was emitted zeroed.
- Initial values: an element's own initial value (a static array literal's elements arrive as `GLOBAL a.0 4 1.5` ...) is kept (`aliasInits`) so the parent's storage can be emitted with them; before, they were dropped and every static array started zeroed.
- Initializer parsing: a float initializer, or anything else this first version does not resolve to a plain integer, is zero-initialised rather than guessed at as a bit pattern (the helper returns 0).
- Global reads of an alias (in the global-load code): an alias into a parent block's storage (see `globalAliasOffset`) loads the parent's address and adds the field's fixed offset, rather than referencing a separate (nonexistent) label of its own.

### The `raw()` sink and stack-delta tracking
- `raw(String text)`: the single low-level text-emission sink every other emission helper funnels through. That makes it the one place that can both (a) redirect output into the call buffer currently open (`currentSink()`, `callBufferStack`) and (b) track the real running stack-pointer delta of a call's argument marshalling (`argTrackDelta`) by recognising the instruction shapes that move `%rsp`, directly off the emitted text (this must measure real emitted instructions rather than infer intent from bytecode; see "Call-argument stack tracking" under `X86Core.java`). Tracking is skipped only when `argTrackSuspendDepth > 0` (inside an `emitAlignedCall`-wrapped nested call, net effect zero by construction) or when no `CC_START` bracket is open (ordinary code outside argument marshalling).
- `trackStackDelta(rawText)`: recognises the exact instruction shapes this backend emits that move the stack pointer by a literal fixed amount: `pushq %reg`/`push reg` (+8), `popq %reg`/`pop reg` (-8), `subq $N, %rsp`/`sub rsp, N` (+N), `addq $N, %rsp`/`add rsp, N` (-N); each is folded into `argTrackDelta`. It deliberately does NOT recognise an absolute assignment to rsp (`movq ..., %rsp` / `mov rsp, ...`, the shape of `emitAlignedCall`'s restore and of the `CC_START`/`CALL` reservation-and-restore): that is not a relative move by a byte count the tracker could add or subtract, and every real caller of one already guarantees the net effect across it is zero.

### Variable-count shifts
- `emitShiftCore(op, size, v)`: shared core of every variable-count shift (stack form `SHL`/`SHR`/`SAR`, register-form `rfShift`). On entry `%rcx` holds the shift count already zero-extended from the operand width (the caller saved `%rcx` and loaded it); `v` is a 64-bit register holding the value (stale bits above `size` bytes allowed). On exit `v` holds the result, zero-extended from `size` bytes, and `%rcx` (and `%r15` for SAR) are clobbered. Rule (see the `SHL` case in `X86CasesA`): count >= 64 gives 0 (SHL/SHR) / sign fill (SAR); a count in [size*8, 63] needs no special case, because SHL's extra bits fall off the truncation, SHR's zero-extended operand has no bits left, and SAR's sign-extended operand already is all sign bits.
- `emitShiftBmi2(op, size, v, cnt)`: BMI2 form of a variable SHL/SHR (same meaning as `emitShiftCore`; a count >= 64 gives 0): `shlx`/`shrx` take the count from any register (no `%rcx` save/restore), then the same compare-and-mask removes a count >= 64. `cnt` holds the zero-extended count and is overwritten (the mask).

### Block and odd-size helpers
- `extractWordsFromReversedBlock(totalWords, size)`: cuts a field/element of `size` (> 8) bytes out of the pushed value block of `totalWords` words and leaves it as a block of its own on the stack (rsp raised past the words no longer needed). On entry `rax` = address of the field's first byte inside the block and `r14` = that byte's offset inside its word (0..7). Block layout: word k (bytes 8k..8k+7) sits at `rsp + (T-1-k)*8`, bytes natural inside a word, so the field's bytes are NOT contiguous in memory unless it starts on a word boundary. Result word j is the 64 bits starting at byte `within` of source word w+j, continued into word w+j+1 (8 bytes BELOW it), which is exactly `shrd` (count 0 for an aligned field leaves the word unchanged). Result word j goes to the slot of source word j (`rsp + (T-1-j)*8`): once rsp is raised by (T - E)*8 that is where it must be (`rsp' + (E-1-j)*8`), and writing in ascending j never overwrites a word still to be read. The word past the block end (read for the last, possibly partial, result word) lies just below rsp: valid stack, and only bytes beyond the field come from it. `%rcx` is the shift count register and may hold an already-loaded call argument, so it is stashed in r13 (backend scratch). Used by `LOOKUP_ARRAY` for an element wider than a word and by `DOT` for a field wider than a word. (A field that is not a whole number of words used to be cut out with a shrink by `total*8 - size` bytes, which is only right when the field's offset and size are whole words.)
- `storeOddSize(srcReg64, baseReg64, off, size)`: stores exactly the low `size` bytes (3, 5, 6 or 7) of `srcReg64` at `off(base)` as a 4/2/1-byte sequence. A single 8-byte store (what `movSuffix`/`sizedReg` used to fall back to) writes 1 to 5 bytes too many: past the end of a packed construction image that is the pointer just above it (a `u16[3][2]` built from two row variables lost the low 2 bytes of its destination address), in a frame it is the neighbouring variable. The source register is rotated between the pieces and rotated back, so it is unchanged afterwards.

## X86CasesA.java

Mnemonic cases (the per-opcode `if`/`else` branches) for the first group of bytecode instructions: register-form variable declarations, integer/float arithmetic and comparison, shifts and rotates, conversions, stack manipulation and field pushes. The comments below sat inside the individual `case` bodies.

### `R_XVAR %xK $off [n]`
Declaration only (emits no code): float variable K lives in an xmm register; `$off` is its home frame slot, used to spill/reload around calls where the ABI does not preserve xmm registers; n is the variable's width (4 = f32, the default; 8 = f64). See "Float variables in xmm registers" under `X86Core.java`.

### Integer division and comparisons (unsigned only)
- `DIV_INT` / `MOD_INT`: the bytecode carries only a byte size per operator, never a signedness flag, and the language's numeric literals default to unsigned (`indeterminate_u64`), so, like `LT_INT`/`LT_EQ_INT`/`GT_EQ_INT`, this pass treats every int comparison/division as unsigned. A real signed s8/s16/s32/s64 division would need `idiv`/`cqto`; documented as a known gap rather than silently wrong for the (more common) unsigned case.
- `GT_INT`: found missing while building float support. The sibling `LowerOrderGenerator`'s `AddressLoweringPass` rewrites all six comparison operators (GT/LT/GT_EQ/LT_EQ/EQ/NEQ) through one identical rule, so each was always as reachable as `GT_EQ_INT`, which was handled; this one case was just never added. Unrelated to anything float-specific; fixed alongside it with the same unsigned-only scope as its five siblings (see `DIV_INT`'s note).

### Shifts and rotates
- `SHL`/`SHR`/`SAR`: shifts have ONE well-defined meaning at every width, on both targets and in both forms (the register-form twin is `rfShift`). The count is read as an UNSIGNED number of the operand's own width (a negative signed count is therefore huge). A count >= the operand width gives 0 for SHL and SHR and the sign fill (0 or -1) for SAR, not x86's "count modulo 64". SAR is an arithmetic shift right for signed operands (the value is sign-extended from its width first); SHR zero-extends first. A narrow result is re-truncated, so the stack word keeps holding the zero-extended value.
- `ROTL`/`ROTR` (`bits_rotl` / `bits_rotr`): rotate the low `size` bytes, count modulo the width (what `rol`/`ror` do: the hardware masks the count to 5 or 6 bits, and a 1/2-byte rotate by (count mod 32) equals a rotate by (count mod width)). The value is zero-extended first so the untouched upper bits of a 1/2-byte sub-register stay zero.

### Float (SSE) cases
- Binary float ops (`ADD_FLOAT`/`SUB_FLOAT`/`MUL_FLOAT`/`DIV_FLOAT`): AT&T `op src, dest` computes dest = dest OP src. xmm0 holds the left operand (pushed first, popped last, as in `ADD_INT`/`SUB_INT`/`MUL_INT` just above), xmm1 the right, so `opss %xmm1, %xmm0` gives left-OP-right, in the correct order for the non-commutative `SUB_FLOAT`/`DIV_FLOAT`.
- Float comparisons (`ucomiss`/`ucomisd`): same unordered-compare caveat as the unsigned-only integer division gap. `ucomiss` sets CF=ZF=PF=1 whenever either operand is NaN ("unordered"), so a NaN compared with anything reads as both "less than" and "equal to" here, rather than IEEE-754's rule that every ordered comparison against NaN is false. A scoped, documented gap: correct for every ordinary (non-NaN) comparison, not yet NaN-aware.
- Float increment/decrement: no SSE "increment by one" instruction exists (unlike `inc`/`dec` on a GPR), so 1.0's IEEE-754 bit pattern is loaded as a plain integer immediate (the way a float literal already reaches a register in `PUSH`'s `isFloatLiteral` branch) and added/subtracted as a real SSE operand.
- `PROMOTE_F32_TO_F64`:
  - Purpose: C's default argument promotion. A `float` (this language's `f32`) argument reaching the `...` part of a variadic call (printf's `%f` etc.) is promoted to `double` before the call, unconditionally, never left as a raw 4-byte value. The fixed non-varargs prefix of a variadic function's parameters (e.g. printf's format string) is NOT promoted, only the true varargs; so the front end (`emitArgWord`'s promote flag) emits this mnemonic only for an argument index at or past the extern's declared fixed parameter count, on an `f32`-typed argument. No `f64`/`double` type exists in this language (see `FLOAT_CHECK`'s doc), so this is purely a value-level ABI conversion in place on the stack; it introduces no new type to the bytecode format.
  - Mechanism: every value on this backend's stack travels as an opaque 8-byte word (see the "Float (SSE) helpers" doc) and a pushed `f32` sits in the low 4 bytes with the upper 4 zero (confirmed against generated assembly: `movq %xmm0, %rax` zero-extends because the source xmm's upper 32 bits are zero for a real `f32`). So: pop the word, reinterpret its low 32 bits as a `float` in a scratch xmm register (`movq`, the same opaque-bit-move `movRegToXmm` uses; exact since the upper bits are known zero), widen with `cvtss2sd`, move the resulting 8-byte `double` bit pattern back to a GPR and push it. It occupies the same one stack word the `f32` did, so nothing downstream (register-vs-stack argument counting, `POP FARGn`, an overflowing stack-passed word) changes; it still consumes exactly one float-bank argument slot.
  - Scratch register: deliberately `xmm8`, never `xmm0` (a confirmed register-clobber bug otherwise; same class of hazard as `LOOKUP_ARRAY`'s scratch-register choice, see the project CLAUDE.md, "a second, real bug found ... this time a register-clobber"). A multi-float variadic call (`printf("%f %f", a, b)`) already has `a`'s promoted value live in `xmm0` (its `POP FARG0` destination) when `b`'s `PUSH b / PROMOTE_F32_TO_F64 / POP FARG1` runs; using xmm0 as scratch for `b` would overwrite `a` before the call (confirmed: both floats printed as `b`'s value before the fix). `xmm8` is never an argument register in either convention (SysV caps at xmm7, win64 at xmm3), so it cannot collide with a live, not-yet-consumed `FARGn` transfer.

### Control and stack cases
- `CMP`: pops the boolean condition and sets the real flags from it, "if true, fall through; if false, jump away" (see `emitForLoop`'s doc). `CMP` is always immediately followed by exactly one `JMP` (verified against every low-order output this pass was checked against), so the next `JMP` becomes a conditional "jump if false" rather than an unconditional jump; see the `JMP` case.
- `TRUNC srcSize destSize`: integer narrowing, only emitted where a `match x fits T` proof shows the value is representable in T. Keeps the low destSize bytes and zero-fills the rest of the stack word.
- Top-word duplicate (pop, then push back twice): used only by the front end's win64 varargs float duplication rule (see `BytecodeEmitter.emitArgTransferTail`): for variadic functions, floating-point arguments must be passed in both the XMM register and the corresponding general-purpose register, a win64-only ABI requirement with no SysV equivalent. The one already-promoted 8-byte double bit pattern needs to reach both a `POP FARGn` and a `POP ARGn` line, and this makes a second independent copy available. It is a generic duplicate-top-of-stack primitive, not float-specific; nothing about it cares what the 8 bytes mean.
- `PUSH_FIELDNAME offset size`: already address-lowered; pushes the compile-time-constant field offset as an immediate, for the `DOT`/`DOT_LHS` that always immediately follows to add to the base address already on the stack. It preserves a pending value block across itself (see `PUSH`'s note), e.g. a `DOT` chained right after a `LOOKUP_ARRAY` read needs to know the element value `LOOKUP_ARRAY` left on the stack is still there (`core.lastValueBlockSize = pendingBlockSize`).

## X86CasesB.java

Mnemonic groups 4 and 5 of the stack-form instruction set (`emitCases4`, `emitCases5`): `DOT`/`DOT_LHS`, `LOOKUP_ARRAY[_LHS]`, `LOOKUP_DYN[_LHS]`, `STACK_LOCK`/`STRUCT_PADDING`, `NEW`, `NEW_DYN`/`NEW_UDYN`/`NEW_FROM_[U]STRING`, `MEMCOPY`, `DEREF`, `ADDR_OF`, `RESIZE`/`URESIZE`, `ATOMIC_PUSH`, `ATOMIC_SWAP`. Most of the notes below record bugs that were found by real execution tests; they explain why the code has the shape it has.

### Value-block layout used by DOT / LOOKUP_ARRAY / DEREF

A value larger than 8 bytes that is pushed BY VALUE is a "block" of words in reverse order: word k (bytes 8k..8k+7) of a block of T words sits at `rsp + (T-1-k)*8`, and the bytes inside one word keep their natural order. So the front (lowest source offset) word is the DEEPEST on the real stack. `pendingBlockSize` (set by the preceding `PUSH`/`DEREF`/`LOOKUP_*`, kept in `core.lastValueBlockSize`) is the total byte size of such a block; a negative value tags a value of at most 8 bytes held in ONE natural-order word (small struct or small array, see below). A byte offset therefore has to be split into a word index (reversed treatment) and a byte-within-word remainder (not reversed): `address = rsp + (totalWords - 1 - wordIndex)*8 + byteWithinWord`.

### DOT and DOT_LHS

- Write vs read side (same split as `LOOKUP_ARRAY` vs `LOOKUP_ARRAY_LHS`): `DOT_LHS` gets a plain address as its base, but a read `DOT` gets the whole containing value pushed by value. Evidence at the time: a bare struct's own `PUSH structSize $offset` before `PUSH_FIELDNAME`+`DOT`, e.g. reading a field off an array-of-structs element that `LOOKUP_ARRAY` (non-LHS) just extracted: `PUSH 48 $-200 / PUSH idx / LOOKUP_ARRAY 24 / PUSH_FIELDNAME off size / DOT size`. `pendingBlockSize` is that containing value's total byte size.
- Containing value of at most 8 bytes held by value in one natural-order word (tagged negative by `PUSH`/`DEREF`, or untagged: an element read through a pointer base is just loaded): the field is a shift and a mask, no address. This used to fall into the placeholder path below it, which silently read 0 (`d[i].a0` on an unsafe dynarray of a 3-byte struct with the register-form passes off). Test `small_struct_dot_test`.
- Field inside one word of a larger block: the block's words are reversed (front word deepest), so the byte at struct offset X lives at `rsp + (totalWords-1-X/8)*8 + X%8`. The older formula was right only for a whole aligned word or a wider field; for a narrower field it pointed into the wrong half of a word (`arr[i].a` for a u32 at offset 8 read the byte at offset 12).
- Field that is a fixed array of at most 8 bytes (`ra` token): its bytes may straddle two of the block's (reversed) words, so they are not contiguous in memory. `rax` = address of the field's first byte, `rbx` = its offset within its word. The code reads the word it starts in and the next struct word (the next-lower address) - only when the field really straddles, otherwise the same word again, so nothing below the pushed block is read - and shifts the pair together (`shrd`). Bytes past the field are junk, as for any small array.
- Multi-word nested field (a struct- or array-typed field read off a base that arrived as a whole pushed value, e.g. `arr[i].point`: the element came from `LOOKUP_ARRAY` as a still-pending multi-word block and this `DOT` reads a field wider than 8 bytes out of it):
  - `rax` (computed earlier, shared with the size<=8 branch) is NOT the field's front-most word the way `LOOKUP_ARRAY`'s own `elemSize > 8` fix's `rax` is (that formula subtracts a fixed one-word `8`; this one subtracts the field's full `size`). For a field at byte offset `off` covering word indices `off/8 .. off/8+fieldWords-1`, `rax` lands on word index `off/8 + fieldWords - 1`, the field's BACK-most word (nearest rsp). That is the same role plain `%rsp` plays for a whole pushed block (`address_of_wordIndex(totalWords-1) == rsp+0`, the same formula with `size == totalWords*8`, `off == 0`).
  - Because `rax` is already a back-word anchor, moving the field's words into a fresh rsp-anchored block needs no reversal: the word at relative offset `k*8` from `rax` goes to relative offset `k*8` from the new (post-shrink) `rsp`, k ascending, which keeps the "back-most word nearest rsp" convention automatically.
  - A first attempt copied `LOOKUP_ARRAY`'s reversed-copy shape verbatim (assuming `rax` was a front-word anchor); it was wrong, confirmed by a real execution test (`arr[i].point` printed `x=8 y=0` instead of the field values).
  - `rsp` is shrunk to the field's final size FIRST (mirroring the reason, not the shape, of `LOOKUP_ARRAY`'s fix): `rax`, captured into `r14` beforehand, is an absolute address unaffected by moving rsp, so this order is always safe and the copy's destination is the address range that stays live. Afterwards `extractWordsFromReversedBlock(totalWords, size)` and `core.lastValueBlockSize = size` (for a chained `DOT`).

### LOOKUP_ARRAY and LOOKUP_ARRAY_LHS

- Write side (`LOOKUP_ARRAY_LHS`): the base always arrives via a plain `ADDR` (a single address word) - ordinary address arithmetic (checked against `array_lookup_cg_test.caspien`'s low-order output).
- Read side (`LOOKUP_ARRAY`): the base always arrives via `PUSH <totalArraySize> $offset` - the whole array pushed by value, never an address (`PUSH 40 $-40 / PUSH idx / LOOKUP_ARRAY 8`). The old code treated the popped array VALUE as a pointer and corrupted the real stack; found by running that test. `pendingBlockSize` is the whole array's byte count, tracked from the preceding `PUSH`; extracting element `index` means locating word `index*elemSize/8` counted from the FRONT of the array (lowest source offset, which `pushBlockFromFrame` leaves DEEPEST on the real stack).
- Small (<8-byte) fixed array pushed by value as one ordinary natural-order word (the negative encoding is only ever produced by `PUSH`'s "size < 8" branch): no memory access at all. The array's bytes already sit in a register, so extracting element `index` is a plain shift-and-mask. The fallback path (address computation then dereference) corrupted this case before the branch existed, because a small array's packed value is not a pointer.
  - `%rcx` is both the shift-count register and an argument register (4th SysV, 1st win64): a finished earlier argument of the call being assembled may already sit in it (`printf(fmt, a, s[0], s[1], s[2])` popped s[0]/s[1] into their registers before this lookup runs), so it is saved and restored around the shift.
  - The mask: `andq` takes only a sign-extended 32-bit immediate, so a 4-byte mask (0xFFFFFFFF) was rejected by the assembler; a zero-extending move does the same job for 1, 2 and 4 bytes.
- Unsafe dynarray direct (unchecked) index also goes through `LOOKUP_ARRAY[_LHS]`, but there the "array" is just a pointer VALUE - an ordinary single 8-byte `PUSH`, not a multi-word block (`PUSH 8 $ptr / PUSH 8 idx / LOOKUP_ARRAY size` for both the read and write side, unlike a true fixed array's read/write split). The popped value already is the base address, as in the single-word logic this pass originally shipped with; that is the fallback (`pendingBlockSize == 0`).
- Reversed-word block, element wider than 8 bytes or narrow in a packed word. After the index multiply, `rbx` = target element's byte offset from the array's front (source-offset order). Split it: `r14 = rbx & 7` (byte within word), `rbx = rbx >> 3` (word index), then address = `rsp + (totalWords - 1 - wordIndex)*8 + byteWithinWord`. Two bugs were fixed together (2026-09), both found by real execution tests and previously invisible because every fixture had `elemSize` an exact multiple of 8 (byteWithinWord always 0):
  1. The code used to subtract a bare `elemSize` instead of a fixed one-word `8`: wrong for any element wider than one word (a 2D `u64[3][3]` read every row back as row 0).
  2. Treating the whole byte offset as a whole-word offset is wrong once several logical elements share one packed word. Tight sub-word array/struct-literal packing means a `char[10]` occupies two 8-byte words, not ten (see `populateDirectMembers`), so a narrow element needs the word/byte split; before the fix only index 0 read back correctly. (Comment said: the full write-up of all three bugs of this branch is in "caspien-codegen's own CLAUDE.md".)
  - Scratch register for the byte remainder: `%rdx` was tried first and was wrong. This whole sequence can sit in the middle of a multi-argument call's argument list (`printf(fmt, arr[0], arr[1], arr[2])`: each finished argument is popped at once into its ABI register - `%rsi`, `%rdx`, `%rcx` on SysV - before the next lookup starts). Using `%rdx` overwrote the second argument's value with the third lookup's scratch before `printf` was called (found with a 2D array's middle column printing `0`, confirmed with gdb: `%rdx` was right until the third lookup's `and $7, %rdx`). `%r14` is used instead: it is already this branch's scratch for the `elemSize > 8` case, is never an argument register in either ABI, and never carries a live value across separate `LOOKUP_ARRAY` invocations.
- Odd-sized element (3, 5, 6, 7 bytes; e.g. a row of a `u16[3][2]`): may start in one source word and end in the next, and the pushed block's words are in reverse order (word k+1 sits 8 bytes BELOW word k), so its bytes are not contiguous and a plain load at `rax` would read the bytes above the block. Same `shrd` extraction as the multi-word case, for one result word; `rax` = address of the element's first byte, `r14` = its offset inside the word (0..7).
- Multi-word element (array of structs, nested array): `rax` is the address of the element's first BYTE inside the pushed block, `r14` = that byte's offset inside its word (0..7). Element bytes are NOT contiguous unless the element starts on a word boundary (a 12-byte `u32[3]` row of a `u32[3][2]` starts at byte 12). Result word j is the 64 bits starting at byte `within` of source word w+j, continued into word w+j+1 (8 bytes BELOW it) - exactly `shrd`; a count of 0 (aligned element) leaves the word unchanged.
  - Result word j goes to source word j's slot (`rsp + (T-1-j)*8`): that is where it must end up once rsp is raised by `(T - E)*8` (result word j sits at `rsp' + (E-1-j)*8`). Writing in ascending j never overwrites a word still to be read (step j reads words w+j and w+j+1, both greater than every slot already written).
  - The word past the block end (read for the last, possibly partial, result word) lies just below rsp: valid stack, and only bytes beyond the element come from it.
  - `%rcx` is the shift-count register and may hold an already-loaded call argument: it is stashed in `r13` (backend scratch). Afterwards `extractWordsFromReversedBlock(totalWords, elemSize)` and `core.lastValueBlockSize = elemSize` (for a chained `DOT`).

### LOOKUP_DYN / LOOKUP_DYN_LHS

- Dynarray layout used here: `[len:8][cap:8][elements...]`, element offset = `16 + index*elemSize` (comment said "see class doc comment"; no such class doc remains in the file).
- An element wider than a word (a struct) is a pushed value block: `core.lastValueBlockSize = elemSize` tells a following `DOT`/`LOOKUP` (`es[i].to`) how big it is, as `LOOKUP_ARRAY` does for an array element. Without it the `DOT` found no pending block ("DOT with no preceding pushed value") and pushed 0, so any read of a field of a dynarray-of-struct element was wrong or crashed.

### STACK_LOCK and STRUCT_PADDING

`STACK_LOCK n` reserves N raw, valueless bytes of stack mid-struct-construction so later-pushed members land at their real, padded offsets (the front end's `emitInstantiate` reuses `STACK_LOCK` for padding at struct construction); plain `sub rsp, N` does exactly this. `STRUCT_PADDING` was never observed inside a real function body in any fixture - only as inert top-level metadata between two `FUNC_END`/`FUNC_START` lines (dead leftovers that should have been stripped earlier) - so it is a pure no-op, rather than reserving stack space that would silently misalign the push/pop accounting if the assumption were wrong somewhere unchecked.

### NEW

Every field push between the true start of the construction and the `NEW` line is construction-scoped (see `precomputeConstructionOffsets` / `populateDirectMembers`): each reserved and wrote only its own declared width, not a uniform 8-byte word, and each `STACK_LOCK` padding gap did its own exact `subq`. So `%rsp` at `NEW` already points at the start of a byte-exact, tightly packed image of the value, `size` bytes laid out as `computeStructLayout` says, needing no reconciliation. `NEW` is therefore struct-agnostic: `malloc(size)`, copy the `size` bytes off the stack into the new buffer (the same `rep movsb` convention as `CLONE`, but from the stack), deallocate the stack region, push the new pointer. It used to pop one 8-byte word per `(size+7)/8`, silently under-counting once any field was narrower than 8 bytes (a `bool` next to a `u64`); the fix was made at the root (the pushes are now byte-exact), so `NEW` needs no field count or offsets.

### MEMCOPY

Pops (top to bottom) src, size, dest - a plain byte copy, nothing pushed. The two `PUSH $addr` lines around every real `MEMCOPY` site re-read their variables separately afterwards (checked directly).

### DEREF

- The result can be a whole aggregate by value when a `LOOKUP_ARRAY`/`DOT` follows (an array member read through an owning pointer: `DOT_LHS / DEREF 24 / PUSH idx / LOOKUP_ARRAY 8`), so its shape is recorded the way `PUSH` does for a value read from the frame: more than 8 bytes = a pushed word block, less than 8 bytes = a single natural-order word (negative tag). Without this `LOOKUP_ARRAY` took its "bare pointer" fallback and dereferenced the array's own contents as an address.
- Multi-word (>8 byte) aggregate: pushes `ceil(size/8)` whole words, lowest source address first, so the highest-address word ends up on top of the real stack; the matching `POP $addr size` block store unwinds in the mirror order. Only whole-8-byte chunks are handled (a non-multiple-of-8 aggregate size was never observed and is not specially handled).

### ADDR_OF

- Operand shape `ADDR_OF kind $offset` (plain addressable local) was first seen only with kind `RAW`; `AddressLoweringPass` produces the identical `$offset` shape for `AUTO` too (found testing a real auto-storage pointer; see BytecodeEmitter's "A pointer-typed dot-chain access gets a real runtime dereference" fix). Both are "take the address of an already-addressable stack slot" with no runtime difference (the storage keyword only changes what the type checker allows), so both get the same `leaMemToReg`. Other operand shapes (a global, a dotted field root, ...) are flagged rather than guessed at.
- Discarded value: `AddressLoweringPass` rewrites only the `ADDR_OF` line; the plain `PUSH name type` line before it (`emitAddressOf`'s `emitExpr(op.left)`), which that pass's doc says is superseded, is NOT deleted and survives into the low-order output. Found by assembling and running a real `raw <local>` fixture: without the discard the stray value shifts everything below it on the stack and corrupts the next `ASSIGN`. `ADDR_OF` pops it once before pushing the real address.
- The dead value is not always one word: `auto obj` on a struct-typed `obj` pushes `obj` at its full declared size first (the `PUSH size $offset`, size>8 `pushBlockFromFrame` case), found with `let p = auto structObj` (a hardcoded single-word discard left a whole stray word per struct-sized `auto`). `pendingBlockSize` (from the preceding `PUSH`'s `lastValueBlockSize`) is 0 for a single-word push and the real total byte size for a multi-word one.

### NEW_DYN, NEW_UDYN, NEW_FROM_STRING, NEW_FROM_USTRING

- `NEW_DYN totalPushedBytes count`: `count` initializer values, each originally assumed to be a plain 8-byte word (the backend's push/pop model treats every value as one stack word; a narrower or wider dynarray element was not specially handled - the code has since gained wide-element handling, see the short comment at the top of the branch). Allocates `[len][cap][elements...]` with `len == cap == count` and pops the `count` values (top of stack = last pushed = last element) into their slots.
- `NEW_UDYN totalPushedBytes`: the unsafe sibling; count = `totalBytes/8` under the same 8-byte-element assumption. An unsafe dynarray is HEADERLESS: just the elements; the returned pointer (also the variable's value) is exactly what `malloc` returned, hence the address registered with the ghost table and the one `free()` needs. Direct indexing is plain `base + index*elemSize` (`LOOKUP_ARRAY`). No length is stored, so there is no `len()`; it never allocates 0 bytes (an empty one gets 1) so a live array is never a NULL or zero-size block.
- `NEW_FROM_STRING`: builds a dynarray(u8-ish) from a string-pool id already on the stack (its address, per the bare-name `PUSH` convention). The backend has no `strlen` result at codegen time, so it calls the already-declared extern `strlen`, then allocates and copies using the same header layout as `NEW_DYN`/`NEW_UDYN`. `NEW_FROM_USTRING` is the unsafe, headerless form: malloc'd block = the bytes plus a NUL terminator (so `len(arr, '\0')` works), pointer = block, registered with the ghost table.
- The argument register is an ABI question (win64 `rcx`, SysV `rdi`), not a syntax one: `argReg(0)` resolves it. Hardcoding `rdi` (as the code used to) is right only for `linux` and silently wrong for `windows_gnu` (AT&T syntax, win64 ABI).

### RESIZE and URESIZE

- Operands: `RESIZE` (safe, 3 popped values: fill, newCount, ptr) / `URESIZE` (unsafe, 2 popped values: newCount, ptr, no fill); `elemSize` is the mnemonic's trailing operand (checked against `resize_uresize_lowering_test.caspien` and BytecodeEmitter's doc). Comment said: it "reallocates to exactly newCount elements (no growth slack, matching NEW_DYN/NEW_UDYN)" and, for the safe form, fills the new slots (oldLen..newCount) with the fill value. The safe-form capacity policy below supersedes the "exactly newCount" claim.
- Safe pointer = malloc'd block start with the 16-byte `[len][cap]` header before the elements (as `NEW_DYN`/`LOOKUP_DYN`). Unsafe pointer = the malloc'd block itself, just the elements, no header, nothing to read or write: `realloc(ptr, newCount*elemSize)` rounded up to at least 1 byte (`realloc(p, 0)` would free the block and return NULL). The unsafe dynarray is headerless: its pointer IS the malloc'd block (nothing to subtract, no length to read).
- Ghost table: the old block start is kept on the stack across the realloc; if the block moves, its ghost table entry is dropped (`gt_moved`: no free, realloc already released it) and the new address registered (`emitResizeGtUpdate`).
- Wide fill: a fill value wider than one word (a struct element) is a whole pushed block, `nw` words deep, ahead of newCount and the old pointer; it stays on the real stack (rsp is restored by `emitAlignedCall`) until the fill loop has copied it (`emitResizeWideFillCopy`). A fill of at most 8 bytes is one word, popped into `r14` and stored at exactly `elemSize` bytes below.
- `oldLen` register: it must survive the `emitAlignedCall`, which internally saves/restores `%rsp` through `%r13`, so it cannot live in `r13` (that was the actual bug behind a confusing ghost-table-lock hang: r13 turned into the saved pre-call rsp, and the fill loop computed addresses from garbage). `rbx` is free once the old pointer is moved into the call's argument register and `emitAlignedCall` never touches it, so `oldLen` lives there.
- ABI registers: the two argument registers are an ABI question (win64 `rcx`/`rdx`, SysV `rdi`/`rsi`): `argReg(0)`/`argReg(1)`; the AT&T branch used to hardcode `rdi`/`rsi`, wrong for `windows_gnu`.
- Safe-form capacity policy: the header's capacity word is honoured. A resize that fits the capacity rewrites only the length (no realloc, the block does not move, the ghost table is untouched). One that does not fit grows to `max(newCount, 2 * capacity)`, so a run of one-element growths costs O(1) amortised reallocs. One that leaves fewer than a quarter of the capacity in use reallocs down to exactly `newCount`. `r15` carries the new capacity across the realloc call (callee-saved); the old block's header is never touched before the call.
- The actual call is `realloc(oldBlockPtr, newTotalBytes)` with arguments staged in the ABI's first two argument registers (rdi/rsi SysV, rcx/rdx win64).

### ATOMIC_PUSH and ATOMIC_SWAP

- `ATOMIC_PUSH size globalName`: an aligned load from a global is already atomic on x86-64, so no `lock` prefix for a plain read.
- `ATOMIC_SWAP`: pops (top to bottom) newValue, address. `xchg` with a memory operand is always atomic on x86 (no `lock` prefix) and leaves the previous value in the same register; every real call site checked compares it against something immediately.

## X86RegForm.java

Last stack-form mnemonic groups (`emitCases6`, `emitCases7`: ghost-table opcodes, `YIELD`/`SLEEP`, `EXIT`/`EXIT_THREAD`, `TRACE_CAPTURE`, `GT_UNWIND`, `THROW`, `RET`/`RET_FLOAT`, call-bracket and argument opcodes, `ASM_START`, `FLOAT_CHECK`), allocation/resize helpers (`emitMallocCall`, `emitResizeGtUpdate`, `emitResizeWideFillCopy`, `emitDropGlueCall`) and the first register-form (`R_*`) helpers (`rfShift`, `rfRotate`, `rfDivConst`).

### GT_INIT and the ghost-table opcodes

- `GT_INIT` is the compiler's ghost-table bootstrap opcode: `gt_init` is an ordinary compiled function elsewhere in the same low-order file (from the imported `stdlib/gt/gt_init.caspien`) with no `CALL` to it anywhere in the bytecode, so this opcode calls it directly (`emitAlignedCallByName("gt_init")`), found by the exact name match; `GT_ALIVE_CHECK`/`GT_DESTRUCT` follow the same pattern.
- `GT_REGISTER` (fourth ghost-table opcode; BytecodeEmitter's `new` case emits it right after `NEW`: "then I pass that malloced pointer thru the provided gt_register function, before putting it on the stack"). Same shape as `GT_ALIVE_CHECK` (pop the pointer into the first argument register, call the stdlib function by its literal name). Comment said `gt_register`'s return value is void and nothing useful ends up in `rax`; that is stale, the code now tests `%al` (see below).
  - The pointer that must survive the call is kept on the real stack, not in a register. This was necessary, not merely cautious (an actual segfault). Comment said: "callee-saved, survives the call" (what `NEW`'s case relies on for r12/r14) holds only for a real externally compiled C function such as `malloc`; `gt_register` is a Caspien function compiled by this backend, and `FUNC_START`/`FUNC_END` never save/restore r12-r15/rbx (only rbp), so its body (which calls `gtSlotAt`/`gtReadSlot`/`gtWriteSlot`/`realloc`, reusing those registers freely) could clobber them. (Stale: `finishCalleeSaved` now saves callee-saved registers per function; the stack copy is still what the code does.) The hardware stack has no such problem: a balanced callee never disturbs what sits below its return address, so a throwaway second copy of the pointer pushed for the call to consume leaves the first copy undisturbed underneath, exactly where the next instruction expects `NEW`'s result.
  - `gt_register` returns false when the table could not grow (the pointer is then NOT registered): the block is freed and null is left in its place, which is what a failed allocation leaves, so the allocation site's own null check takes the out-of-memory path. A null pointer registers as success (and stays null).
- `GT_DESTRUCT $offset`: the operand is an immediate stack offset, not a popped value (`GT_DESTRUCT $-240`); the pointer VALUE stored there is read (`gt_destruct`'s own parameter is the pointer by value: `PUSH ARG0 / ASSIGN` in its body).
- `GT_DESTRUCT_ADDR type`: the pointer-crossing sibling of the flat-offset case (front-end half: caspien-compiler's CLAUDE.md, "GT_DESTRUCT dotted-operand gap"). The operand is a type string only (kept for the format's "every instruction states its type" convention; not used for dispatch - an owns pointer is always 8 bytes). The work already happened on the stack: `BytecodeEmitter.emitDestructOldOwnedValue` computed the target's real, possibly pointer-crossing ADDRESS via the `DOT_LHS`/`LOOKUP_LHS` chain an assignment target uses, then `DUP_TOP`-duplicated it (one copy for this line, one for the following store). So where the plain case reads the pointer off a fixed frame offset (`loadSizedFromFrame`), this one pops the ADDRESS and dereferences it (`loadSizedFromAddr`) to get the pointer value `gt_destruct` expects.

### YIELD and SLEEP

- `YIELD`: per the language notes "yield is intended to say 'I dont need my execution time, im low priority, prioritize someone else'" - an argument-less scheduling hint, not a delay (unlike `SLEEP`, which names an `@sleep` function). It gives up the rest of the current scheduling turn and can run again as soon as nothing else wants the CPU. Implemented as POSIX `sched_yield(void)`, which links and runs on both targets with no target branching: glibc (linux) and mingw-w64's pthread/sched.h layer (windows_gnu, already linked with `-pthread`/`-static`) export it under the same name, the same "one literal C symbol" shape as `exit`/`pthread_exit`. `pthread_yield` was tried first and rejected: a non-standard GNU extension that winpthreads does not export (`undefined reference to 'pthread_yield'` under windows_gnu). No arguments; `sched_yield`'s int result (0 on success) is never consumed.
- `SLEEP sleep_call duration`: a dedicated instruction, not an ordinary `CALL`; the second operand is the `@sleep`-decorated function's own name, resolved once by `TypeChecker.checkSleepCall` (never a hardcoded literal the backend must know - that "resolved dynamically" property avoids the naming fragility the original ghost-table opcodes once had; caspien-compiler's CLAUDE.md, "a non-literal name broke the linker"). Shaped like `GT_ALIVE_CHECK` (pop the one pushed argument, move it into the first argument register, call, push the result) instead of the full `CC_START`/`CC_END`-wrapped `CALL`, because `TypeChecker.requireSleepSignature` forces `@sleep` to take exactly one plain `u64` and return exactly one plain `u64` (8 bytes each way). The trailing operand (the duration argument's resolved type) is never read; it only follows the convention that every instruction states the type it operates on (as `NEG`/`DEREF`/`CLONE`/`LEN_SCAN`). "sleep is used like sleep(duration) where duration is the duration type C expects."

### EXIT and EXIT_THREAD

- `EXIT` is reached only when a `THROW` unwinds through every intervening frame to the true root (`main`, or `@event_loop`); never on an ordinary completion of `main`, which always jumps over the whole label (`JMP end_of_gt_routine__main` emitted right after it, see `emitGtRoutineBody`). It cannot do nothing and fall through: the label after it (`end_of_gt_routine__main:`) is `main`'s own ordinary BODY, not a copy of its epilogue, so falling through (as this case once wrongly assumed) would re-run all of `main` after a real throw. Unwinding to the true root has its own documented status (fixture `gt_unwind_throw_multi_frame_cg_test.caspien`): "unwound to the true root boundary", exit code 1, distinct from a normal exit 0. It calls the C library's `exit(1)` (already linked; no EXTERN needed).
- `EXIT_THREAD` is the real-OS-thread counterpart for `par`/`await`, emitted unconditionally by `emitGtRoutineBody` at the tail of every `@async` function's gt_routine unwind label, in place of `EXIT`. It is reached the same way, but at the thread's entry point, the compiler-synthesized `__trampoline_<name>` (an `@async` function only runs on its own OS thread started by `@par_call`/`@await_call`'s `pthread_create`, never from `main`'s startup path). Ending the whole process with `exit` would tear down every other thread (including the one `await`-ing or polling this one); `pthread_exit(NULL)` ends just this thread. `pthread_exit` is linked via `-pthread` and deliberately never declared in `stdlib/libc.caspien` (called here by its literal name, like `exit`). The null return value is never read: `@await_call` calls `pthread_join(tid, null)` (`stdlib/await_call.caspien`).

### TRACE_CAPTURE

Stack traces (`stack_trace.md`, 3.4). Walks the rbp chain from this frame: each traced frame keeps `(id << 1) | isRoot` at `rbp-24`; stops at the first root (never following a root's saved rbp), counting frames. The root's `rbp-32` holds the address of its staging array. A second pass stores the innermost `min(count, DEPTH)` ids, then a 0 terminator. Nothing else is touched: the scratch registers are saved and restored, because variable registers may be live across this instruction.

### GT_UNWIND

Original design note ("make the assembly to return from a function as you normally would, but then locate the first variable in that stack frame and set the instruction pointer to that"), recovered from `emitGtRoutineBody` after the backend was found never to have implemented it.

- `GT_UNWIND` appears only inside a function's own `gt_routine__<name>:` label, reached by falling through from a `THROW` in the same function or by a nested callee's `GT_UNWIND` jumping straight there. Every `GT_DESTRUCT` the routine needed has already run (`emitGtRoutineBody` emits them just before), so all that is left is to leave.
- "Return as you normally would" = the ordinary epilogue: deallocate the frame (`mov rsp, rbp`) and restore the CALLER's rbp (`pop rbp`), as `emitFunctionEpilogue` does for a real `RET`. Then, instead of `ret` (which would resume the caller's body), the return address is discarded unread (rsp bumped past it) and control jumps straight to the caller's own `gt_routine` label.
- "Locate the first variable": with rbp restored, the frame is the caller's and its first variable is always its `gt_routine_address` slot (`emitGtRoutineAlloc`: reserved before any other local in every function that has one), always at rbp-8 (checked: every function's first `ALLOC ... code_addr` lowers to `$-8`). The caller's prologue stored its own gt_routine label there (`ADDR gt_routine_address / PUSH_LABEL ... / ASSIGN`) before the callee was ever called, so reading it and jumping there is "set the instruction pointer to that".
- It chains through any call depth with no special casing: the whole-program trigger that gives a function its gt_routine machinery (`checker.usesOwnsRefDynNew() || checker.usesThrow()`) applies to every function in the unit, so the caller always has that slot at the same offset. Landing on the caller's label runs its own `GT_DESTRUCT`s, then either unwinds one more frame the same way or (the true root: `main`/`@event_loop`/`@async`) terminates via `EXIT`/`EXIT_THREAD` instead of a further `GT_UNWIND`.
- `GT_UNWIND MSG`: the compiler reserved a second slot, `gt_error_message`, at `rbp-16` in every frame (right behind `gt_routine_address`; `ArgToAllocLoweringPass` keeps it ahead of the parameters). This frame's message is carried up to the caller's slot so a catch anywhere up the chain sees what was thrown. `rax` is free at that point (it is loaded with the jump target afterwards).

### THROW

`THROW` is a pure marker and a no-op here. Originally `THROW string_id` carried a second operand (the enclosing function's whole-function `gt_routine` label) and compiled to an unconditional `jmp`. That label and its conservative whole-function destruct list no longer exist (per-throw-site / per-call-site unwinding redesign, see the compiler project's history): a throw's destination is resolved at compile time, so the compiler emits the throw's precise `GT_DESTRUCT` list followed by `GT_UNWIND`/`EXIT`/`EXIT_THREAD` as ordinary lines right after it, using machinery the backend has for every other destruct/unwind site. (Comment said: the thrown message was always inert at runtime and "there is still no 'catch' construct anywhere in this language"; stale - the message now travels via `gt_error_message` and `catch` exists.)

### RET and RET_FLOAT

- A void `RET 0` explicitly zeroes `%rax`/`%eax`. It used to leave whatever the last instruction put there, harmless while tests used a C harness that always returned 0, but `main` is linked directly and the C runtime reads `%eax` as the process exit code, so a void function now gives a predictable exit 0 (this is the `FUNC_END` zeroing rule mentioned in the Codegen CLAUDE.md).
- `RET` can occur anywhere (nested in an if/match/loop), so it must actually leave the function HERE, not fall through into the merge-label/JMP scaffolding of the surrounding control flow. Falling through let later code overwrite the return value (and for a conditional return, run the rest of the body it was meant to skip); found via a return nested in a chain of plain (no-else) ifs. The same epilogue `FUNC_END` emits at the function's textual end is emitted again here: harmless dead code where `RET` was the last statement, required everywhere else.
- `RET_FLOAT`: the float return convention (`codegen.config` `return-register-float: XMM0`, shared by win64 and SysV) puts the result in `xmm0`, not `rax`; otherwise identical to `RET`, including the "must leave the function here" fix. Never reached for a void return (it exists only for a non-void float-returning function), so there is no "leave it zeroed" case.

### Calls, arguments, and call brackets

- `VARARGS_XMM_COUNT n`: pure compile-time metadata emitted by the front end immediately before a real variadic call's `CALL`/`INVOKE` (never for a fixed-arity call). It emits no assembly; it records the value in `core.pendingVarargsXmmCount` for the next call site's preamble (the SysV `%al` = vector-registers-used rule; see `pendingVarargsXmmCount`'s doc for why it must travel through the bytecode).
- `INVOKE n`: the function pointer was pushed BEFORE the preceding `POP ARGn` sequence, so it is what is left on top of the stack: it is popped into `r10` (not used by any argument slot) and called through. The numeric operand (arg count) is informational.
- `FARGn` destination (float-bank counterpart of `ARG`): there is no pop directly into an xmm register, so the value comes off the GPR-based value stack into `rax` and then into the argument xmm register with the bit-copying `movq` used by every other float op.
- Multi-word pop-to-address store: mirror of `DEREF`'s multi-word push; the FIRST pop is the highest-address word (last pushed), stored at `addr+size-8`, working down to `addr+0` for the last pop.
- `PUSH_RET_FLOAT`: caller-side mirror of `RET_FLOAT`: the called function's float result is in `xmm0`, not `rax`; it is moved over and pushed as `PUSH_RET_INT` does for `rax`.
- `CC_START`/`CC_END` bracket one call (`resolveStackArgBytesAndCall` is the matching `CALL`/`INVOKE` side):
  - `CC_START` opens a fresh buffer/tally level for this call's argument marshalling. Nothing is emitted immediately (it used to be a pure no-op): the alignment reservation cannot be sized until the real stack-passed-argument byte count is known, which happens once every argument has been produced, at the call's own `CALL`/`INVOKE` line (`resolveStackArgBytesAndCall`). A naive immediate reservation here is exactly the bug this design closes; see `argTrackDeltaStack`'s doc.
  - If the call is nested inside another call's argument list, `CC_START` saves the registers the outer call has already loaded (this call is about to set up its own arguments in the same physical registers). Those pushes land in the enclosing call's buffer and tally; the matching pops at this call's `CC_END` cancel them exactly, so the outer call's stack-passed-byte accounting is unchanged.
  - `CC_END` closes this call's buffer/tally level and flushes the complete text (reservation, if any, plus every argument-transfer/call/restore instruction emitted since) into whatever sink was active one level up: the enclosing call's still-open buffer, or `out` directly.
  - `CC_END` then restores the enclosing call's already-loaded argument registers (saved at `CC_START`) in reverse order. `rax` (an integer result) is never touched; `r11` is the scratch for float-bank restores; a possible float result in `xmm0` is first stashed in `r10`, only when `xmm0` itself is about to be overwritten.
- `GLOBAL`/`ALLOC_STATIC`/`STRING`/`EXTERN` are handled in the data-section pre-pass; ELF resolves an EXTERN'd symbol via the linker automatically, with no explicit declaration needed in GAS.

### ASM_START

Everything from `ASM_START` to the matching `ASM_END` is opaque, verbatim assembly text (see `AsmInfo`/`emitAsm` in the front end; the compiler never inspects an ASM block beyond one reservation check and no lowering pass touches it). Nothing to resolve: each raw line is copied through unchanged, in order, and skipped here so the main dispatch does not also see it as bytecode.

### FLOAT_CHECK

`PUSH f / FLOAT_CHECK finite|nan|...` is a float classification test (never parsed from real source; see BytecodeEmitter's doc on this operator). Comment said it is only reached against a 4-byte value because `f32` is the only floating-point type in the language ("no 'f64' exists anywhere in caspien-compiler") - stale: the code reads an `f64Check` flag (operand 2 equal to `8`) and handles both widths. Classification (shown for IEEE-754 single precision): the top bit is the sign (masked off), and the rest is compared against the all-ones-exponent, zero-mantissa pattern `0x7f800000` (2139095040): strictly less = finite (ordinary, subnormal or zero), equal = +-Infinity, strictly greater = NaN of any payload. `op.right.text` is a `|`-joined, never-empty, never-all-three subset of the categories (see `Token.MatchPattern`); each requested category contributes one `setcc` reading the SAME comparison's flags (unchanged by an intervening `setcc`, so one `cmp` serves all), OR'd into one 0/1 result for the `CMP`/`JMP` that always follows a match branch condition.

### Drop-glue and clone-glue calls (`emitDropGlueCall`)

A drop-glue call (`__drop_<T>`, and likewise a clone-glue call `__clone_<T>`, whose pointer result comes back in `rax`) has a deliberately minimal, ad hoc shape: LowerOrderGenerator emits a bare `PUSH pointer` / `CALL __drop_T` with no `CC_START`/`CC_END`, and the routine reads its one parameter as stack word 0 (`PUSH $16`), the word immediately above the return address. The generic aligned-call wrapper puts its alignment reservation between the pushed word and the return address, so the routine read an uninitialised slot instead of its pointer (it only worked when stale stack contents happened to match; a nested drop such as String -> DynamicArray dereferenced null). Here the argument is popped, rsp is aligned with the original rsp saved in a slot below the argument, the argument is pushed back so it sits directly above the return address, and rsp lands 16-aligned at the `call`. (The source's next line, kept as a short comment, covers `__clone_value_T(dst, src)`: two words, dst pushed first, src on top, same procedure with both words kept.)

### Resize helpers

- `emitResizeGtUpdate` (end of `RESIZE`/`URESIZE`): the old block start is on top of the stack, the realloc result (block start or null) in `r15`. A moved block (new address, non-null) is unregistered at the old address WITHOUT freeing (`gt_moved`) and registered at the new one; an unmoved block or a failed realloc (old block still valid and registered) changes nothing. Clobbers `rbx`.
- `emitResizeWideFillCopy(elemSize, words)`: `RESIZE`'s fill loop for an element wider than one word: copies the fill block still on the stack (word k at `rsp + (words-1-k)*8`, the reversed-word convention of `pushBlockFromFrame`) into the element whose address is in `rax`, in natural ascending byte order. Clobbers `rdx`.

### emitMallocCall

The argument register is an ABI question (win64 `rcx`, SysV `rdi`), independent of syntax: `argReg(0)` resolves it. The code used to hardcode `rdi` in the AT&T branch, right only for `linux` and silently wrong for `windows_gnu` (AT&T syntax but win64 ABI) - confirmed by a real crash: `malloc` read its size from garbage in `rcx`, and the wild or failed allocation surfaced downstream as a null- or wild-pointer fault (most visibly `NEW`'s `rep movsb`).

### Register-form arithmetic helpers

- `rfShift` (`R_BIN SHL|SHR|SAR size %tD a b`): `%tD` = `a` shifted by `b`, with the same single semantics as the stack form (see its `SHL` case): the count is the unsigned value of the operand's own width, a count >= the width gives 0 (SHL/SHR) or the sign fill (SAR). A constant count is resolved at compile time (no `%cl`, no clamp unless it is >= 64); a variable count goes through `%rcx`, an argument register that may be live, so it is saved and restored around the shift. (In the source this comment sits directly above `rfRotate`'s, not above `rfShift` at its own definition: misplaced.)
- `rfRotate` (`bits_rotl` / `bits_rotr`): rotate of the low `size` bytes of `a` by `b`: `rol`/`ror` on the sub-register of the destination, the count taken modulo the width (a constant count is reduced here; a variable count goes through `%rcx`, saved and restored like `rfShift`). The value is zero-extended first for 1/2-byte widths (the rotate leaves the upper bits of the sub-register alone); a 4-byte rotate zero-extends by itself.
- `rfDivConst`: `%tD = a / k` or `a % k` for an unsigned 64-bit `a` and a constant `k >= 3` that is not a power of two, without a divide instruction (Granlund-Montgomery): `t = mulhi(m, a); q = (t + ((a - t) >> 1)) >> (l - 1)` with `l = ceil(log2 k)` and `m = floor(2^64 (2^l - k) / k) + 1`; the remainder is `a - q*k`. The dividend goes to the scratch register `r15`; `rax` (a live temp when `%tD` is another register) is parked in `%tD` for the duration and swapped back; `rdx` is clobbered, as by the stack form's `divq`.
