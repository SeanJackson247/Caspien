# Stack traces for throw/catch: design (research document)

Status: **research / design only. Nothing in this file is implemented.** Written 8 Oct 2026 from a read of the repository at
remote commit `1710514` (extracted to a scratch directory, not edited), plus the design discussion recorded here. No compiler,
stdlib or test file was changed.

Goal: when an error is handled, the handler can see **where it came from** (the chain of functions from the throw site up
to the root), at a cost that is **zero unless the feature is used**, with **no heap use on the error path** (an
out-of-memory failure may be the error being reported), and correct for `par` threads.

## 1. How throw and catch work today (verified in the source/docs)

- Every function starts with `push rbp` (Codegen notes: "`FUNC_START` pushes rbp"); the epilogue is `movq %rbp,%rsp; pop rbp`.
  `rbp` is not in the variable-register set (`RF_VAR_REGS = {r13,r14,r12,r8,r9,r10,rsi,rdi,rdx,rcx}`), so the chain is intact.
- Fixed frame slots: `rbp-8` = `gt_routine_address` (landing-pad address), `rbp-16` = `gt_error_message` (present when the
  program uses throw), then parameters. `ArgToAllocLoweringPass` and `VariableAllocationReordering` keep these first.
- Before **every** call, the caller writes that call site's own landing pad into its `rbp-8` (`ADDR gt_routine_address /
  PUSH_LABEL <pad|@catch_N> / ASSIGN`). A try-guarded call stages the catch label directly (no pad).
- Landing pads are cold code behind a `JMP` before `FUNC_END`: that site's `GT_DESTRUCT`s, lock releases, then `GT_UNWIND MSG`
  (non-root), `EXIT` (main) or `EXIT_THREAD` (async). `GT_UNWIND MSG` tears down the frame, discards the return address, copies the
  message into the caller's `rbp-16` and jumps to `*(caller rbp-8)`. A catch landing is followed by `RSP_MARK`.
- The catch parameter `e` is an untyped name of type `static imut string`.
- `throw` takes any `static imut string` that is a string literal or a bare variable (`TypeChecker.checkThrow`): a literal, `e`
  (re-throw), or a parameter such as `msg: static imut string`. Calls and member chains are rejected. So a library function
  `Assert(b: mut bool, msg: static imut string)` with `throw msg` is ordinary Caspien and needs no compiler support.
- Allocation failures (`new`, `dyn`, `clone`, `resize`, `par`, `await`) jump to catch labels directly with a fixed message
  ("out of memory", "cannot start thread").
- HOB carries source positions only on decorators (`FUNC_DECORATE @x "file:line"`); tokens carry file/line in the front end only.
- There is **no thread-local storage** anywhere in the compiler output or stdlib (searched: `tbss`, `.tdata`, `%fs:`, `__thread`,
  pthread keys). Shared state is a global behind a lock, or lives in frames. Throw/unwind state is in frames, so it is
  thread-safe without TLS.
- The call graph of safe code is acyclic (recursion only as bounded tail loops). `--audit` already computes the longest call path
  (summed over frame sizes) and flags function pointers, `call()` and `unsafe` as unbounded; it follows the source call graph and
  does not model optimiser inlining.
- The inliner turns an inlined callee's `GT_UNWIND` into a `JMP` to the label the caller staged, so an inlined frame leaves no
  frame on the stack. `@gt_*` functions are never inlined.

## 2. Options considered

| Option | Normal-path cost | Throw-path cost | Result |
|---|---|---|---|
| A. Throw-site text in the literal (`"file:line: msg"`) | 0 | 0 | Where thrown only |
| B. Breadcrumbs appended by landing pads | 0 | one store per unwound frame | Path from throw to handler only. Needs a buffer location |
| C. Frame-pointer walk at the throw | 0 | walk of depth N | Full stack to root. Needs per-function identity |
| D. Shadow stack pushed/popped per call | 4-6 instructions per call | free | Needs per-thread storage and a pop at every exit |
| E. DWARF/CFI plus external tools | 0 | n/a | Post-mortem only, not in-program, not portable to `windows_gnu` |

**Chosen: C, with the function's identity stored in its own frame.** One immediate store per call, nothing else on the normal
path, per-thread by construction (each thread walks its own stack), no TLS, no global, no allocation.

The capture happens **at the throw site**, not in the handler: by the time `catch(e){..}` runs, the frames that threw are
already unwound, so a walk there would return the handler's stack. The result is written into the root frame at the throw, and
a pointer to it travels with the message to the handler.

## 3. Design

### 3.1 The `usesTrace` gate

A whole-program flag, like `usesThrow()` today. Set when the source mentions `e.stack_trace` or `funcname`. When unset,
**output must be byte-identical to today** (test: binary hash).

### 3.2 Per-function identity slot

With `usesTrace`, every function gets a hidden 8-byte slot `gt_trace_id` at `rbp-24`, written once at entry by a single
`movq $imm32, -24(%rbp)`, value `(id << 1) | isRoot`.

- Ids are `u64`, assigned by the front end **per generic instantiation** (each expansion is its own function), starting at **1**.
  **Id 0 is reserved** as the end-of-trace marker, so no function has it.
- `usesTrace` forces the throw-style frame layout, so `rbp-8`, `rbp-16`, `rbp-24` (and the trace pointer slot, 3.5) always
  exist. The ordering passes keep the slots fixed.
- The root bit is static: the user `main` (or `__caspien_main`), each synthesised `__trampoline_<f>` (async roots), the event-loop
  roots, and `export`ed functions entered from C. An exported function that is also called from Caspien gets a thin root wrapper.

### 3.3 The staging array

Each root frame reserves `u64[DEPTH + 1]`.

- `DEPTH` comes from a compiler flag, `--trace-depth N`, default 16. In practice a trace rarely needs more than a dozen
  frames, and the real worst-case depth is usually deeper than anyone reads, so there is no attempt to size the array from the
  audit's call-graph walk.
- `+1` is room for the terminator when the stack is exactly `DEPTH` deep.
- When the stack is deeper than `DEPTH`, **keep the innermost frames** (the throw site and its callers); the outermost are
  dropped.
- Inlining is disabled under `usesTrace` (an inlined function leaves no frame to record), and `@inline` warns.

### 3.4 Capture at the throw

Only when `usesTrace`, at every fresh `throw` of a literal or variable that is not the catch parameter, and at every
allocation-failure branch, **before** unwinding:

1. Walk the `rbp` chain from the current frame: read `-24(%rbp)`, stop at the first frame with the root bit, never following
   the saved `rbp` of a root. Count the frames.
2. Second pass: write the innermost `DEPTH` ids into the root frame's array.
3. Write the terminator `0` after the last entry. A stale tail from an earlier, deeper capture is harmless: readers stop at the
   first `0`.

Both walks are on the throw path only, so they are slow only when something is actually thrown. `throw e` (re-throw) does **not**
capture; it keeps the existing one.

### 3.5 The catch parameter

`e` becomes a built-in value with two members:

- `e.msg`: `static imut string` (what `e` is today).
- `e.stack_trace`: a **pointer** to an array of `u64` ids, terminated by `0`. It is not the array itself.

The throw site writes the trace into the root's array and stores the array's address next to the message. Unwinding carries both:
`GT_UNWIND MSG` copies the message slot as now, plus the trace-pointer slot (`rbp-32`) into the caller's. That is one extra `mov` per
unwound frame, on the throw path only, and only with `usesTrace`. `throw e` re-throws both members unchanged. A fresh throw starts
a new message and a new capture.

### 3.6 Handler-local copy (aliasing)

The array in the root frame is shared by everything on that thread, so the next throw overwrites it. If a handler's body mentions
`e.stack_trace`, the compiler gives that function a frame-local `u64[DEPTH + 1]` and copies the root's array into it (through the
terminator) when the catch lands, and `e.stack_trace` points at the local copy. Handlers that never mention it pay nothing.

Known imperfection: a re-throwing handler that does **not** mention `e.stack_trace` re-throws the pointer, and the array may have
been overwritten if that handler threw and caught something else in between. Making every re-throw copy would cost O(depth
squared) along a chain of `?catch(e){ throw e }` handlers, so this is documented rather than fixed.

### 3.7 `funcname`

`funcname(id: u64) static imut string`: a **generated stdlib function** (a lookup into a table of string literals in read-only
data), not a compiler builtin.

- Total and non-throwing: id `0` or past the table returns a fixed `"?"`. It carries its own bounds check.
- No heap, no ghost table, cannot fail, so it is safe to call while reporting an out-of-memory error.
- The table is emitted only if `funcname` is referenced. A program that stores ids but never names them carries no name strings.
- Names are source-level, e.g. `String.concat (string.caspien:88)`, not the mangled `__sig_` names; generic instantiations are
  distinguished by their type arguments.

### 3.8 Printing on the error path

Print to **unbuffered `stderr`** (`fputs`/`write`), not `stdout`. As far as I know glibc allocates the `stdout` buffer with `malloc`
on first use, so a `printf` could fail under the same out-of-memory condition being reported.

### 3.9 Example

```rust
@throws
func Assert(b: mut bool, msg: static imut string) void{
	if !b{ throw msg }
}

@throws
func readToken(x: mut u64) mut u64{
	?Assert(x <= 100, "too big")
	return x
}

@throws
func parseLine(x: mut u64) mut u64{
	?catch(e){ throw e }
	return ? readToken(x)
}

func load(x: mut u64) void{
	?catch(e){
		unsafe extern{ fputs(e.msg, stderr) }
		let t = mut e.stack_trace
		for match i in 0..16{
			let id = mut t[i]
			if id == 0{ break }
			unsafe extern{ fputs(funcname(id), stderr) }
		}
		return
	}
	let v = mut ? parseLine(x)
}
```

Captured at the throw in `Assert`: `Assert, readToken, parseLine, load, main, 0`. (The loop shape is illustrative: how a handler
indexes a pointer to an array under the `for match` proof rules is open, section 7.)

## 4. Threads

Each thread's stack is walked on its own, rooted at its own trampoline frame, with its own array in that frame. No TLS, no
global, no collection and no registration. Nothing here is shared between threads.

## 5. Costs (estimates, not measured)

- `usesTrace` off: zero. Identical output.
- `usesTrace` on, normal path: one `movq $imm32, -24(%rbp)` per function entry; frames grow by 16 bytes (id slot and trace
  pointer slot); each root frame grows by `(DEPTH + 1) * 8` bytes (136 bytes at the default).
- Throw path: two walks of depth N; unwinding carries one more value per frame; a handler that uses the trace pays one copy of
  at most `(DEPTH + 1) * 8` bytes and the same amount of frame space, only in that function.
- Read-only data: the optional name table, about one string per function.
- Measure with the existing executed-instruction counter before shipping.

## 6. Edge cases and rules

- **Roots.** Every entry from foreign code must be a root, or the walker would read a C frame's garbage. Roots never follow the
  saved `rbp`.
- **Inlining** drops the frame and its entry; disabled under `usesTrace`.
- **`call(fp)`** from Caspien code is fine (the parent is a Caspien frame).
- **`@recursive`** lowers to a loop and adds no frames.
- **`unsafe asm`** functions that do not maintain the frame chain would break the walk; not handled.
- **Breaking change.** `e` stops being a plain string. The repo has about 450 `catch(e)` handlers (tests 325, examples 24, stdlib
  41, benchmarks 48, README 16). Most never read `e`; those that print it (a few dozen by a rough grep) need `.msg`.
- **The trace is not a destroy event** and does not interact with the proof/kill analysis in `fatrefplan_prooffix.md`.

## 7. Open decisions

1. The exact type exposed for `e.stack_trace` (a pointer to a `u64` array; which pointer kind, and whether its length is known
   to the checker) and how indexing it fits the `for match` proof rules.
2. Whether the handler-local copy (3.6) should be unconditional for any handler that mentions `e.stack_trace`, as written, or
   whether the extra frame space is worth avoiding by reading the root's array directly.

## 8. Order of work

1. `usesTrace` flag plumbing and `--trace-depth`, with the byte-identical-output test.
2. Frame slots, per-instantiation ids, roots.
3. Capture at throw sites and failure branches; the root array.
4. The `e` type change (breaking), the trace pointer through `GT_UNWIND`, the handler-local copy, and `funcname`.
5. Later: call-site line numbers. The walk already reads each return address (`[rbp+8]`); mapping them to lines needs a table and
   source positions carried through the later stages, which do not exist today.

## 9. Tests

- Output is byte-identical (binary hash) with `usesTrace` off.
- Known call chains give the expected named traces, including `Assert, readToken, parseLine, load, main`.
- A throw in a `par` thread gives a trace rooted at its trampoline.
- Allocation-failure traces, using the existing alloc shim (`FAILN`, `FAILREALLOC`, `FAILPTHREAD`).
- A handler that internally throws and catches before reading `e.stack_trace`: the handler-local copy is still correct.
- Re-throw chains keep the original trace.
- Depth exactly `DEPTH` still has its terminator; a deeper stack keeps the innermost frames; `--trace-depth` changes the cap.
- Two instantiations of one generic function have different ids and names.
- `export` entry gives a root.
- Sweep under the four configs of `tests/run_tests.py`; valgrind clean; leak check clean.

## 10. Unverified

Not read: the optimiser and low-order passes, so it is unknown whether they tolerate a new frame slot or pad operation. Not
checked: that every return and exit shape keeps the frame chain; whether pads with nothing to destruct are merged; that a
pointer to an array works with `for match`; and everything on `windows_gnu` (nothing in the design needs TLS and the frame chain
exists there too, but neither has been checked).

## 11. Non-goals

Per-frame line numbers, DWARF/CFI support, and tracing by default in release builds.
