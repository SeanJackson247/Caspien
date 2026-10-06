# Compiler reference (orchestrator, toolchain.config, optimisation passes)

Top-level orchestrator for the Caspien toolchain. It doesn't do any
compilation itself -- it drives four sibling component projects, each a
standalone prebuilt Java program, as subprocesses:

    ASTGenerator        .caspien source        -> higher-order bytecode
    Optimizer           higher-order bytecode   -> higher-order bytecode (optimized)
    LowerOrderGenerator higher-order bytecode   -> low-order bytecode
    Codegen             low-order bytecode      -> x86-64 assembly (.s)
    (then this orchestrator itself assembles + links the .s into a binary)

All four folders are expected to already exist as direct subfolders of
wherever `Compiler.java`/`Compiler.class` lives (this zip ships all four
already in place, prebuilt). Addressing is entirely relative to that
directory -- run the orchestrator from the folder it lives in.

`ASTGenerator` is the project formerly just called "the compiler" --
renamed so this orchestrator could take that name. Its own behavior is
unchanged; see `ASTGenerator/README.md` and `ASTGenerator/CLAUDE.md` for
its history.

## Build

    javac Compiler.java

(Plain `javac`, no build tool. The four component folders already contain
their own prebuilt `out/*.class` files -- you don't need to rebuild those
unless you change their source.)

## Run

    java Compiler -i path/to/file.caspien output/main.exe

- `-i <path>` -- the input `.caspien` file (ffmpeg-style flag).
- `<path>` (no flag) -- the output path. Its parent folder(s) are created
  automatically if they don't exist (e.g. `output/` in the example above).

On success, produces a real native binary at the output path (an ELF
executable for the `linux` target, a PE32+ `.exe` for `windows_gnu`).
Errors and warnings from every stage are printed to the console as they
happen; a one-line `[info] done -- wrote ...` summary prints at the end.

### Test files and `stdlib`

A `stdlib/` folder is included at this project's root. Caspien source
files resolve `import "stdlib/..."` relative to *the importing file's own
directory*, so keep your `.caspien` test files here at the root (next to
`Compiler.java`) alongside this `stdlib/` folder, the same way the
included `hello.caspien` sample does -- or copy `stdlib/` next to wherever
you keep your own test files instead.

### Hashing in the standard library: `insecure_hash.caspien`, `sha256.caspien`

`stdlib/insecure_hash.caspien` is real 64-bit FNV-1a (offset basis `0xcbf29ce484222325`, prime `0x100000001b3`; for every byte `h = bits_xor(h, byte)`, then `h = h * prime`, wrapping):

- `insecure_hashOf<T>(x: imut T) mut u64` hashes the raw bytes of a value of any non-struct type `T` (the signature and behaviour the stdlib `HashMap` has always used; `insecure_hashOf:<u32>(7)` is FNV-1a-64 of the bytes `07 00 00 00`). A struct `T` cannot be passed (a by-value struct parameter is illegal in this language).
- `insecure_fnv1a64Bytes(p: raw imut u8, byteCount: mut u64) mut u64` is the same hash over a byte buffer (a string's characters, a struct's bytes copied out with `memcopy`). Published vectors (checked in `tests/fnv1a_test.caspien`): `""` -> `0xcbf29ce484222325`, `"a"` -> `0xaf63dc4c8601ec8c`, `"foobar"` -> `0x85944171f73967e8`.

`stdlib/sha256.caspien` is a real SHA-256 (FIPS 180-4), pointer-based like `insecure_hash.caspien` (the pointers come from `unsafe` code):

```
import "stdlib/libc.caspien"
import "stdlib/sha256.caspien"
func main() void{
	unsafe extern memcopy raw{
		let msg = mut malloc(mut 3)
		memcopy(msg, mut 3, "abc")              // a string literal is not a byte pointer: copy it
		let digest = mut malloc(mut 32)
		sha256(msg, mut 3, digest)               // digest: 32 bytes, ba7816bf 8f01cfea ... 0015ad
	}
}
```

| call | meaning |
|---|---|
| `sha256(data: raw imut u8, byteCount: mut u64, digest: raw mut u8)` | one-shot hash, 32 digest bytes written to `digest` |
| `sha256Begin(ctx)`, `sha256Update(ctx, data, byteCount)`, `sha256Finish(ctx, digest)`, `sha256Release(ctx)` | streaming, any split of the message; `ctx` is a `Sha256Ctx` (`auto mut`) |
| `sha256Init(state)`, `sha256Compress(state, block)`, `sha256CompressWords(state, words)`, `sha256Digest(state, digest)` | block level: `state` is 8 `u32` words (`raw mut u32`), a block is 64 bytes or 16 words, no padding is applied (so a caller can supply a precomputed padding block); `sha256NewState`/`sha256FreeState`, `sha256WordsAlloc`/`sha256WordsFree` allocate the `raw mut u32` buffers (malloc's `raw mut u8` cannot be converted) |

`tests/sha256_test.caspien` (168 checks, expected digests from Python `hashlib`) covers the FIPS vectors, one million `a`, lengths around every padding boundary, streaming with many chunk sizes against one-shot, and the block primitives in the shapes the Merkle benchmark uses. With `function-inlining: aggressive` every call site of `sha256` is a private copy of the hash, so a program should hash from a few places (the test is table-driven for that reason).

## Build cache

Every stage's result is cached in `.cache/` next to `Compiler.class` (override with the `CASPIEN_CACHE` environment variable; the folder is git-ignored and safe to delete). A stage's key is the SHA-256 of everything its output depends on, chained like a Merkle tree: the stage's own class files and Java version, the keys of the config it reads, and the hash of the previous stage's output.

| Stage | Key |
|---|---|
| 1 front end | its classes, `compiler.config` without optimiser and register keys, `fs.config`, target, the input path; on lookup also the hash of every file the front end read (input, imports, ASM files; reported through `CASPIEN_DEPS_FILE`) |
| 2 Optimizer | its classes, `compiler.config` without register keys, hash of stage 1's output |
| 3 LowerOrderGenerator | its classes, `compiler.config` without optimiser keys, hash of stage 2's output |
| 4 Codegen | its classes, `codegen.config`, hash of stage 3's output |
| 5 as + gcc | target, `as`/`gcc` versions, hash of stage 4's output |

So a comment edit re-runs only the front end (the later stages see an identical input), an optimiser switch re-runs stages 2-5, a register-form switch stages 3-5, and an unchanged program with unchanged compiler and config is served entirely from the cache. Only successful stages are stored; warnings are stored with the entry and printed again on a hit (progress lines are not). A compiler config key the cache does not know is kept in every stage's view, so a new key can cost a miss but never produce a stale hit. `--fs-report` builds bypass stage 1. Benchmarks build with `--no-cache` so compile times stay honest. `tests/cache_check.sh` covers hits, misses and invalidation, `tests/cache_views_check.py` the claim that each stage ignores the config keys left out of its view.

## Flags

    --no-cache      Do not read or write the build cache (see "Build cache").
    --cache-report  Print `[cache] <stage>: hit|miss` for every stage.
    --audit [--audit-no-stdlib]
                    Type-check only (nothing is built) and print every `unsafe` in what would be compiled: `file:line  unsafe <tags> {`, the
                    numbered contents of the braces, `unsafe unaudited` blocks with the tags they actually need, other uses (`unsafe dyn(..)`),
                    and a summary by tag. Sections: your code / standard library (`--audit-no-stdlib` hides the second).
    --clear-cache   Delete the build cache (alone, or together with a build).

    --no-warnings   Suppress warning output (they simply aren't printed;
                    this does not affect errors, which always print).

    --asm           Stop after Codegen. Do not assemble/link with gcc.
                    The output path receives the generated .s assembly
                    text directly.

    --lob           Stop after LowerOrderGenerator. Output path receives
                    the low-order bytecode text.

    --hob           Stop after ASTGenerator + Optimizer. Output path
                    receives the (optimized) higher-order bytecode text.

At most one of `--asm` / `--lob` / `--hob` may be given at a time.

> **Note on `--lob` vs `--hob`:** in the original request these two flags
> were described with identical wording ("doesn't compile the assembly,
> stops short at higher order bytecode"), which is almost certainly a
> copy-paste slip rather than intentional, since as written it would make
> the two flags redundant. I've implemented the naming literally instead:
> `--hob` stops at **h**igher-**o**rder **b**ytecode (after
> ASTGenerator+Optimizer, before LowerOrderGenerator runs), and `--lob`
> stops at **l**ower-**o**rder **b**ytecode (after LowerOrderGenerator,
> before Codegen runs). If that's not what you meant, the flag parsing
> and the four-branch pipeline in `Compiler.java`'s `run()` method are
> both short and easy to re-point.

## `toolchain.config`

This orchestrator has exactly **one** config file of its own,
`toolchain.config`, at the project root. It bundles the config files each
component expects, under `===sectionname===` headers:

    ===compiler.config===
    ...content copied verbatim into ASTGenerator/compiler.config
    ...and LowerOrderGenerator/compiler.config (both need an identical copy)

    ===codegen.config===
    ...content copied verbatim into Codegen/codegen.config

Every run, before invoking any component, the orchestrator re-splits
`toolchain.config` and overwrites each component's own config file with
the matching section. **Edit `toolchain.config`, not the per-component
copies** -- direct edits to `ASTGenerator/compiler.config`,
`LowerOrderGenerator/compiler.config`, or `Codegen/codegen.config` will be
silently overwritten on the next run.

The `codegen.config` section's `target` key selects the backend:

    target linux         # GAS AT&T syntax, SysV ABI (as + gcc, native gcc/Linux)
    target windows_gnu    # GAS AT&T syntax, win64 ABI (gcc via mingw-w64 -- this
                           # is the one to use for "gcc on Windows")

Ships set to `target windows_gnu` (a fresh copy of this project builds a
Windows `.exe` with gcc/mingw-w64 out of the box, no config edits needed,
since that's the toolchain in actual use). Change it to `target linux` if
you move this project to a Linux machine building with native gcc/as
instead.

### `f64`

`f64` (double precision) is supported alongside `f32`, with the same register treatment (register variables, xmm temporaries) and literal adaptation. There is no implicit
mixing: widen with `x as f64`, narrow with `wrap:<f32>(d)`. Extern libm calls use the real names (`sqrt`, not `sqrtf`). See `tests/f64_test.caspien` and `benchmarks/nbody/RESULTS.md`
(f64 costs a little more time than f32; it buys precision). Untested: the win64/Wine target for f64 varargs, MASM output.

### `deferred-operands`

The `===compiler.config===` section also has a top-level switch,
`deferred-operands: on|off` (ships `on`). With `on`, the LowerOrderGenerator
keeps the temporaries of integer (1/2/4/8-byte), `f32` and pointer
expressions in registers instead of pushing and popping every operand
(variables live in their frame slots unless `variables-in-registers` promotes them, below; calls stay stack-based); with `off` the low-order bytecode is exactly what it was before the
pass existed. Programs give identical output either way -- `tests/regform_test.caspien`
and `tests/regform2_test.caspien` check that. Measured on a 1e9-iteration `x += 8` loop:
6.6 s off, 2.1 s on; on the n-body benchmarks (`benchmarks/nbody/`) 1.3-3.5x faster.
Only the AT&T (`linux`, `windows_gnu`) forms of the new instructions have been
run; the Intel/MASM (`windows`) forms mirror them but are unexecuted.

### Constant-bounds `match` test (always on)

A plain `match i in arr` / `match i into arr` on an array (or any literal-bounds range) used to rebuild the range on the stack and copy the index on every pass through the block before comparing; the LowerOrderGenerator's `RangeCheckFusionPass` now compares the index with the literals directly (`i < N`; the `>= 0` half is dropped for an unsigned index). No switch, nothing to configure; `assume match` was never affected. Measured (Linux, n-body, N = 5M, everything-on config): the `loop` variants 1.64 -> 0.68 s (f64) and 1.59 -> 0.70 s (f32), every other program unchanged within noise. Tests: `tests/rangecheck_test.caspien` (21 checks), `tests/rangecheck_check.sh` (25 LOB cases). Linux only: windows_gnu output assembles and links but was not run, Intel/MASM is unverified.

### Global scalar reads in register form (always on with `deferred-operands`)

`RegisterFormPass` also fuses a read of a global scalar (`let static` x) into a register load (`R_LD n %tD &sym`) instead of a stack push, and, when a call or an unfused integer/float stack operation follows operands that are still pending, keeps the fusion done so far (flush) instead of throwing the whole region back to stack form. No switch; it only runs with `deferred-operands: on`. Measured (Linux, n-body, N = 5M, everything-on config): the scalar-statics programs 1.27 -> 0.66 s (f64) and 1.20 -> 0.64 s (f32); programs that keep their state in locals or arrays are unchanged within noise. Tests: `tests/regform_globals_test.caspien` (26 checks, identical with the pass on and off), `tests/regform_globals_check.sh` (14 LOB cases). Linux only: windows_gnu output assembles and links but was not run, Intel/MASM is unverified.

### Float constant pool and indexed float arrays (always on with `deferred-operands`; AT&T targets)

A float constant used in register-form code is no longer built in a general register on every use: each distinct value (width + bit pattern) gets one read-only entry (`.LFC<k>`) and is used as a memory operand or loaded with one `movss`/`movsd` (zero is `xorps`). This is a pool, not hoisting into registers. A global float array element read or written through a variable index is one indexed instruction (`R_LDXI`/`R_STXI`, scale = 4 for f32, 8 for f64) behind a single `lea sym(%rip)`, instead of two `lea`s, and a float result headed for a store (`a[i] -= a[i]*c`) stays in xmm. No switch. Measured (Linux, n-body, N = 5M, everything-on config, previous tree -> this tree): the float programs 4-20% faster (scalars f64 0.70 -> 0.56 s), spectral-norm naive 0.48 -> 0.32 s, fannkuch unchanged. The MASM/Intel path is unchanged and unexecuted for the new forms.

### `float-temporaries-in-registers`

Top-level switch in `===compiler.config===`, `float-temporaries-in-registers: on|off` (needs `deferred-operands: on`; independent of
`float-variables-in-registers`; shipped `off`). Intermediate results of f32 expressions stay in xmm registers (four: SysV xmm4-7, win64
xmm12-15) instead of being copied to a general register and back around each operation. A temporary is only moved when every use of it is a
float operation, argument, return, compare or store; anything else keeps the general-register form. Measured on the n-body programs
(Linux, N = 1e6): nbody_arr 0.23 -> 0.13 s, nbody_plain 0.53 -> 0.38 s, nbody 0.33 -> 0.28 s, nbody_loop 0.65 -> 0.62 s; with float
variables also on, nbody_plain reaches 0.34 s and nbody_loop 0.57 s. Verified on Linux and `windows_gnu` (Wine) with
`tests/floattemps_test.caspien` and the snapshot programs; the Intel/MASM target and float fuzzing are not covered.

### `loop-unrolling`

Top-level keys in `===compiler.config===`, read by the Optimizer's `LoopUnrollingPass` (shipped `off`; a missing key is off):
`loop-unrolling: off|conservative|balanced|aggressive`, plus optional `loop-unroll-factor`, `loop-unroll-full-max-trips`,
`loop-unroll-max-body-lines`, `loop-unroll-max-growth` (whole numbers; they override one number of the preset and do nothing while the
preset is off; a malformed value stops the compile).

| preset | factor | full unroll if trips <= | body lines <= | added lines per function <= |
|---|---|---|---|---|
| conservative | 2 | 4 | 40 | 400 |
| balanced | 4 | 16 | 100 | 2000 |
| aggressive | 8 | 4096 | 2000 | 200000 |

Only a `for` loop whose trip count is written in the loop's own bounds is unrolled: `for i in 0..8`, or `for e in a` over a fixed array.
The pass does no constant propagation, folding or other analysis to find a bound, so `for i in 0..n`, `for j in j0..5` (a variable
bound) and every `loop{}` are left exactly as they are. When a fully unrolled body only reads the loop variable, copy k gets the
literal `lo + k` in its place and the increments go (so `i * 8` or `bits >> i` fold); otherwise the variable stays real and its increment is
kept between copies. Not done for a body that holds another loop (its bounds would turn literal and the code multiplies); range proofs on the
literal are folded away by constant folding (`IN`). A loop of at most `full` trips is unrolled completely. A longer one with at least
2 x `factor` trips is unrolled `factor` times: the hidden range's high bound becomes `lo + floor(N/factor) * factor`, the body is
repeated `factor` times, and the remaining `N mod factor` trips are copied after it. Inner loops go first; the outer loop is
considered on a later round. A loop is skipped when its body declares storage or a function/struct/global/register hint, refers to its
own hidden range variable, or defines a label that code outside the body jumps to; a `break` inside an unrolled loop still leaves the
whole loop. Verified on Linux and `windows_gnu` (Wine) with `tests/unroll_test.caspien` (16 checks: full, partial with and without a
remainder, one trip, `break`, nested loops, a variable bound, `loop{}`, a call in the body, a fixed array, a try/catch in the body, two
loops with the same bounds) at off and all three presets, and under 12 numeric-override combinations (Linux): identical output every
time, no duplicate labels. Of the other programs in `tests/`, only `array_match_test` and `regform2_test` contain a literal-bound `for`
loop; their output is identical too. Unrolling by itself did not make the looping n-body programs measurably faster (N = 1e6, within
timing noise): their hot inner loop has a variable bound, and turning the outer loop into straight-line code needs the constant
propagation this pass deliberately does not do. Not covered: the Intel/MASM target, a fuzzer for this pass.

#### Per-loop control: `@unroll`, `@unroll(N)`, `@dont(unroll)`

A decorator on the `for` statement overrides the preset for that one loop and is honoured even with `loop-unrolling: off`:

| written | effect |
|---|---|
| `@unroll` | unroll fully (any trip count), substitute the loop variable, also into a body that holds another loop |
| `@unroll(N)` | N from 2 to 64: unroll by N (fully if the loop has at most N trips) |
| `@dont(unroll)` | never unroll this loop, whatever the preset says |

A forced unroll ignores the preset's trip, body-size and growth limits; its only limit is 20000 added lines per loop. It still needs a loop the
pass can read (literal bounds after folding, no label of the body used from outside it). The optimizer says what it did, on stderr: `[note]
file:line - @unroll: unrolled fully (8 iterations, +37 lines)`, and `[note] ... @dont(unroll): this loop is kept as a loop (the heuristic would
have unrolled it)` when the preset would have unrolled it. A request that cannot be met is a `[warning] file:line - @unroll not honoured:
<reason>` (variable bounds, a label used from outside, over the limit), printed once after the optimizer has settled, because a bound that is a
variable early on can become a literal in a later round. Notes and warnings are replayed when the stage comes from the build cache. Only on a
`for` statement (not function level); `@unroll` on `loop{}` is accepted and ignored. Tests: `tests/unroll_decorator_test.caspien` (9 values from a
Python model) and `tests/unroll_decorator_check.sh` (messages, preset off and aggressive, cache replay, bad factors).

### `function-inlining`

`function-inlining: off|conservative|balanced|aggressive` (shipped `off`) makes the Optimizer replace a direct call by a copy of the callee's body, so constant folding,
variable elision and register hints see the merged code. Presets (callee size in bytecode lines / rounds of inlining / lines added per function): conservative 12 / 1 / 200,
balanced 40 / 3 / 2000, aggressive unlimited / 32 / no limit (inlines every function that can be inlined, except that a callee longer than `inline-max-multi-callee-lines`, default 1000 lines, is inlined only if the program calls it from exactly one place; 0 switches that rule off). `inline-max-callee-lines`, `inline-max-depth` and `inline-max-growth`
override the three numbers. Never inlined: `@async` functions, calls through a function pointer, C (`extern`) functions, `main`, the runtime glue functions, inline assembly or a local `static`, and callees that take a struct, array or dynarray by value.
Functions that throw, catch or call throwing functions ARE inlined (`@throws` is fine). In a program that uses throw/`new`/owns every function carries two slots, `gt_routine_address` and `gt_error_message`, stages a label into its own slot before each call and unwinds through the caller's slot; the inlined code simply uses the caller's own slots (the callee's are dropped), each `GT_UNWIND` of the callee becomes a jump to the label the caller staged for that call (its landing pad, or its `catch`), and the callee's own landing pads and `catch` blocks are copied along with renamed labels. A few shapes still stay calls: an unwinding callee at a `catch` when the caller or callee has a float variable, a callee with a `catch` of its own or one that ignores a call result when it would sit in the middle of an expression with operands pending (see `Optimizer/CLAUDE.md`), and a function that only ever throws where a value is expected. A recursive function is already a loop when it
reaches the Optimizer (the front end converts it), so it is inlined like any other; scalar and `range` parameters are supported. `@inline` is still an accepted decorator (it is in the front end's decorator list, nothing was removed); the pass does not treat it as a command: a function carrying it is inlined or not by the same rules as any other, under the configured limits.
A call inside another call's argument list (`check("name", f(x))`) is not inlined once an earlier argument has been loaded into an argument register: inlined code has no register protection (a real nested call saves and restores those registers, but the backend's block copies, shifts and divides use rdi, rsi, rdx and rcx as scratch), so the site stays a call. If the outer call is itself inlined, the inner one is inlined on the next round.
Tests: `tests/inline_test.caspien` (45 checks, output identical off and on at every preset, on Linux and under Wine), `tests/inline_argreg_test.caspien` (7 checks: range copy, divide and recursion-lowered callees in argument lists), `tests/inline_throwprog_test.caspien` and `tests/inline_throw_test.caspien` (10 + 17 checks: throwing, catching, re-throwing and message propagation through inlined frames) and `tests/inline_owns_test.caspien` (10 checks: calls that move an owns variable into the callee) and `tests/inline_hob_check.sh` (bytecode-level cases, including unwinding callees and owns-move null-outs).
Measured on the n-body written with small helper functions (`benchmarks/nbody/caspien/nbody_natural_f64.caspien`, 5M steps): 7.5 s with everything off, 2.0 s with unrolling and inlining on.
See `benchmarks/nbody/RESULTS.md` and `charts.html` (from `bench.py` and `charts.py`) for the full comparison against C, C++, Rust, Go, Java, Node and Bun.

#### Per-function control: `@inline`, `@dont(inline)`

| written | effect |
|---|---|
| `@inline` | inline every call to this function that is safe to inline, even with `function-inlining: off`, past the preset's callee-size limit, depth and growth budgets |
| `@dont(inline)` | never inline this function, whatever the preset says |

`@inline` ignores the preset's size and growth limits only; the safety rules above still decide (no `@async`, no call cycle, scalar/pointer/`range` parameters, a call inside another call's argument list stays a call, ...). A chain of `@inline` functions needs one round per level (up to 8 rounds when no preset is on). The optimizer prints once, after it has settled, `[note] file:line - @inline: f inlined at N call sites`, and `[warning] file:line - @inline not honoured at N call sites of f (K inlined): <reason>` for a call that stayed a call. The standard library uses `@inline` on `DynamicArray.get/set/setPtr` and `String.charAt/setCharAt`. Measured (best of 3, naive programs that call them in inner loops): `function-inlining: off` or `conservative` 1.1x to 1.9x faster (sorting 4.7 s to 2.7 s, json_serde 4.3 s to 2.4 s, strings 3.8 s to 3.4 s), `aggressive` unchanged (it inlines them anyway). In the aggressive benchmark builds `@inline` helped only where the heuristic left hot calls: the helper functions of `nbody_natural_f64` (-9%) and `advance` in `nbody_arr` / `nbody_arr_f64` (-8% / -17%); in `merkletrees` it changed nothing measurable. Tests: `tests/inline_decorator_test.caspien` and `tests/inline_decorator_check.sh`; before/after chart `benchmarks/charts_decorators.html`.

### `dead-function-removal`

Top-level switch in `===compiler.config===`, `dead-function-removal: on|off`, read by the Optimizer's `DeadFunctionRemovalPass` (shipped `off`; a missing key is off; any other
value stops the compile). It runs after dead control flow removal in the outer loop, so a function that was only called from a deleted branch is removed on the next round.

The program is left completely alone when it contains an `INVOKE` (a call through a function pointer: `call(fp, ...)`, unsafe code only, the target is not known statically) or has no
`main` (a library). Otherwise a function is kept when it is reachable from a root through `CALL`/`RECURSIVE_CALL` or through any other line that names it as an operand (so a function
whose address is taken, `PUSH f static_imut_func(..)`, counts as used, as do the trampolines the compiler builds for `par`). Roots: `main`; `gt_init`, `gt_register`, `gt_alive_check`,
`gt_destruct` (the backend calls these by fixed name with no `CALL` in the bytecode); any function carrying a decorator other than `@pub`, `@throws`, `@recursive`, `@pure`, `@pure(rt)`, `@non(deterministic)`; and any function named
on a line outside every function. Note this is reachability from the roots, slightly stronger than "is there a call site": two functions that only call each other, or one that only calls
itself, are removed too. Only whole `FUNC_START`..`FUNC_END` blocks are deleted. A Hello World drops from 9 functions to 6 (the plain `gtSlotAt`, `gtReadSlot`, `gtWriteSlot` are only ever
called through their `__` duplicates); `stdlib_test` from 62 to 54.

Verified on Linux with `tests/deadfunc_test.caspien` (4 checks; unused functions, a dead chain, and a function called only from a branch that the constant condition removes, plus live
chains and a catch) at on and off (identical output), then with every optimizer switch on (22 -> 16 -> 15 functions off/on/all); `tests/deadfunc_hob_check.sh` (hand-written bytecode:
recursion cycle and self-recursion removed, address-taken function kept, INVOKE and no-main leave everything, roots and decorators) passes 4/4; all other `tests/` programs, Hello World and the
eight n-body ports with every switch on vs all off (33 programs, identical output; `stdlib_test`, which uses `par`/async/process, shrinks from 62 to 54 functions and still passes). The pass was run against 8
deliberately broken versions (no `main` root, no decorator roots, no hook roots, no main guard, no INVOKE guard, references only through CALL, an inert `@sleep`/`@async`, a probe that
counts only CALL sites); 7 change a result, the 8th (no hook roots) is equivalent because the hooks also carry decorators. Under Wine (`windows_gnu`) with every switch on, `deadfunc_test` passes 4/4 and `stdlib_test` gives the same result as with every switch off (58 pass; its one failing check, `process: write mode + close`,
fails identically with everything off, so it is not caused by this pass). Not covered: the Intel/MASM target, a fuzzer. Removal of the declarations this leaves behind is the next pass, `unused-declaration-removal`.

### `unused-declaration-removal`

Top-level switch in `===compiler.config===`, `unused-declaration-removal: on|off`, read by the Optimizer's `UnusedDeclarationRemovalPass` (shipped `off`; a missing key is off; any other
value stops the compile). It runs right after dead function removal in the outer loop, so what a removed function or branch leaves behind goes in the same round. It deletes declaration lines only:
`EXTERN` lines, `GLOBAL` declarations (a name plus its `name.member` lines), function-local `ALLOC_STATIC` declarations (same), and `STRING` literals.

A declaration counts as used when any code token on any other line is its name (for a global also `name.member`); text inside a quoted string is not a reference. Always kept: the externs the
backend calls by fixed name without any reference in the bytecode (`malloc`, `realloc`, `free`, `strlen`, `exit`, `pthread_exit`, `sched_yield`) and the global `ghost_table`. An `ALLOC_STATIC` name that
is declared in more than one function is left alone. The whole pass does nothing when the program has no `main` (a library exports its declarations) or contains inline assembly (`ASM_START`, whose text
may name symbols). Effect: Hello World with the other passes on goes from 16 `EXTERN` lines to 5 (374 -> 363 lines); `stdlib_test` from 16 to 14. It is a separate switch, not part of dead function removal,
because it is a different kind of change (declarations, not code) and can be turned off on its own.

Verified on Linux with `tests/unusedecl_test.caspien` (4 checks; unused externs, statics and array, strings that only occur in a dead function or a constant-false branch are gone from the bytecode,
used ones survive) at on and off (identical output); `tests/unusedecl_hob_check.sh` (hand-written bytecode: no `main`, inline assembly, fixed-name externs, `ghost_table`, same-named local statics)
passes 4/4; all other `tests/` programs, Hello World and the n-body ports with every switch on (including this one) vs all off: 34 programs, identical output. Under Wine (`windows_gnu`) with every switch on,
`unusedecl_test`, `deadfunc_test`, `deadflow_test`, `structunpack_test` and `varelide_test` pass in full and `stdlib_test` gives the same one failure as with everything off (`process: write mode + close`).
Mutation testing: 7 deliberately broken versions (self-references count as use, no `name.member` base matching, no fixed-name externs, no `ghost_table`, no `main` guard, no assembly guard, no duplicate-static
guard) are all caught by the test or the bytecode check; an 8th (quoted-string text counted as a reference) cannot be told apart by any test because a quoted token never equals a bare name. Not covered: the
Intel/MASM target, a fuzzer. Known conservative case: an unused `ALLOC_STATIC` whose name is declared in several functions stays.

### `sizeof-resolution`

No switch: `SizeofResolutionPass` is always on, because the `SIZEOF` mnemonic must never reach the LowerOrderGenerator. The front end folds `sizeof` for primitives, pointers and ranges, but leaves a struct, an array of
structs and the scale of `raw Struct` pointer arithmetic symbolic as `SIZEOF TypeName <type>`. The pass (first real pass of the outer loop after `StructMemberReorderingPass`) rewrites each to
`PUSH n` from the STRUCT declarations as they stand: size = sum of STRUCT_MEMBER sizes + STRUCT_PADDING (the hidden `___type` is the first member; nested struct = its own size; fixed array = element size times length;
any storage-keyword type 8; a mangled generic name is looked up whole). An unknown struct name is an `IllegalStateException`. Because it runs inside the loop, `constant-folding` (when on) then folds `3 * sizeof(S)`; a
`let static n = sizeof(S)` is a data line and is folded by the front end (`foldStaticConstant`) instead. Operands after the type (`SIZEOF S t extra...`) are kept.

Verified on Linux: `tests/sizeof_stride_test.caspien` (8 checks: padded struct sizes 16/32/88, nested, array of structs, generic `Box_u64` 24, `raw P ++` step equals `sizeof(P)`, `p + n`, pointer difference, a static
initialiser) and `tests/sizeof_hob_check.sh` (6 cases on hand-written bytecode: resolution with padding, nested struct, array and generic name; folding on gives `PUSH 48` for `3 * sizeof(S)`; folding off keeps the multiply;
extra operands kept; unknown struct rejected; no `SIZEOF` leaves the program untouched). Not covered: the Intel/MASM target.

### `struct-member-reordering`

`struct-member-reordering: on|off` in `toolchain.config` (shipped `off`). The Optimizer's `StructMemberReorderingPass` moves each eligible struct's members so the largest alignment comes first (stable inside a class; `___type` stays first) and the padding gathers at the end, e.g. `{u8, u64, u8, u32}` 32 -> 24 bytes, then rewrites every positional construction in the same run: the declaration, stack literals, `new`, array literals of structs, RVO returns. Named member access, static `GLOBAL g.member` lines and all later offsets follow the declaration, and `sizeof` (resolved right after) follows too.
A struct is left alone if it uses `extends` (either side), carries a decorator such as `@lock`, has a member that is not a plain scalar (struct, array, pointer, dynarray, range), has a `raw` pointer to it anywhere (punning, `memcopy`, C interop), is the operand of a `let static n = sizeof(S)` (the front end folded the number; it emits `STRUCT_PIN S`, which the pass always strips), or has a construction site the pass cannot parse with certainty. Member initialisers run in the new order (they are pure expressions). Verified on Linux: `tests/structreorder_test.caspien` (35 checks, identical off and on except the printed `sizeof`), `tests/structreorder_hob_check.sh` (9 cases), all 44 programs of `tests/` identical off and on. Not verified: Intel/MASM, Wine. No benchmark program uses a struct, so benchmark numbers do not change.

### `variable-allocation-reordering`

Top-level switch in `===compiler.config===`, `variable-allocation-reordering: on|off`, read by the Optimizer's `VariableAllocationReorderingPass` (shipped `off`; a missing key is off; any other value stops the
compile; the ASTGenerator's and LowerOrderGenerator's config parsers skip it). It runs once, after the outer optimisation loop and before the register-variable hints. Frame offsets are assigned in address lowering from the
order of a function's ALLOC lines alone: each slot goes right below the previous one, rounded down to its own alignment, and the gap that leaves is wasted, so locals declared `u8, u64, u8, u64` cost 7 bytes twice. The pass sorts
the function's leading ALLOC run by (1) alignment, largest first (8, 4, 2, 1; every size is a multiple of its alignment, so after the sort only the final round-up to 16 is left), (2) use weight, larger first (the weight
`RegVarHintPass` uses: each mention counts 8^loopDepth, depth capped at 4), so the hottest slots sit nearest rbp where a displacement fits in one byte, (3) original order. Alignments mirror the lowering's table (storage
pointers 8, u8/s8/bool/char 1, u16/s16 2, u32/s32/f32 4, u64/s64/f64/code_addr/string/range/dynarray 8, a fixed array its element's, a struct the max of its members', anything unknown 8).

Stays put: `gt_routine_address` (rbp-8) and `gt_error_message` (rbp-16), pinned as the first two slots (GT_UNWIND and its message copy use those offsets); parameters (still `ARG` lines here; ArgToAllocLoweringPass adds their
slots right behind the pinned ones later, so they stay ahead of every local); ALLOC lines that are not part of the leading run (the inline temporary of an async `par` call); functions containing inline assembly, or with a
gt slot anywhere but the very front of the run. The pass never adds, removes or renames a line, and locals are only ever named, so it needs no analysis; a wrong alignment estimate could only cost padding.

Effect: `tests/allocreorder_test.caspien`'s `mixed` function goes from a 192-byte to a 160-byte frame. Expect small gains in general: a few bytes per frame, and shorter displacements only in functions with more than 16 8-byte locals.

Verified on Linux with `tests/allocreorder_test.caspien` (6 checks: eight mixed-width locals with an array and a struct, owns/throw functions whose gt slots must stay first, a throw through two frames caught with a catch of its own) at
off and on (identical output; frame 192 -> 160); `tests/allocreorder_hob_check.sh` (hand-written bytecode, 10 cases: sort by alignment, gt slots pinned, weights and loop depth, struct alignment, inline assembly, switch off,
ALLOC after a body line, unknown/pointer/array/range types, a misplaced gt slot, nothing to do); all 50 programs of the sweep (every `tests/*.caspien`, the n-body ports, Hello World) off vs inlining-only vs everything on
including this switch, at aggressive and conservative: identical. Under Wine (`windows_gnu`) with everything on: `allocreorder_test`, `regvars_test`, `inline_throw_test`, `struct_literal_nested_test`, `array_row_odd_test`
identical to off. Mutation testing: 11 deliberately broken versions (gt slots or `gt_error_message` not pinned, alignment ascending, weights ignored, loop depth ignored, struct alignment ignored, assembly guard removed,
misplaced-gt guard removed, reversed tie-break, pointers not 8-aligned, arrays 8-aligned whatever the element) are each caught by the bytecode check; the two pin mutants are masked at program level by the misplaced-gt guard
(they need both removed to break a program, and that combination is caught by `allocreorder_test`). Not covered: the Intel/MASM target, a fuzzer.

### `dead-control-flow-removal`

Top-level switch in `===compiler.config===`, `dead-control-flow-removal: on|off`, read by the Optimizer's `DeadControlFlowRemovalPass` (shipped `off`; a missing key is
off; any other value stops the compile). It runs after the inner loop (folding, elision, shifting) in the outer loop, so a condition that elision and folding just turned into a
literal is caught on the next outer round.

It looks for `PUSH true|false T ; CMP ; JMP L` (`CMP ; JMP L` jumps to L when the condition is false). `true`: the three lines are deleted and execution falls into the branch body.
`false`: the three lines become the unconditional `JMP L`. Then flow reachability over the function (fallthrough, `JMP`, both ways of `CMP ; JMP`; `RET`, `THROW` and an unconditional
`JMP` end a path; roots are the function start, every label that is not one of the compiler's structured if/loop/for/match labels, and every label referenced by anything other than a
`JMP`, i.e. catch entries and unwinding callsites) decides what the rewrite made dead, and exactly those lines are deleted (declarations such as `ALLOC` are never deleted; code that was
already unreachable before the rewrite is left alone). A `JMP L` left directly before `L:` is dropped. Functions with inline assembly are skipped. Nested dead branches, dead branches
containing loops, nested ifs and calls, constant conditions inside loops, a constant guarding a `break`, and functions with a catch body all work. Not done: any other unreachable-code
removal (for example the `JMP` the compiler emits after a `RET`), removal of the now-unreferenced `GT_UNWIND` callsite entries of a deleted call, removal of unreferenced empty labels, and
conditions that are constant for a reason this pass cannot see (a comparison of two literals must first be folded, which `constant-folding: on` does).

Verified on Linux and (Wine) `windows_gnu` with `tests/deadflow_test.caspien` (17 checks, hand-computed, includes a check that no removed branch ran) at on and off, with and without
folding/elision/shifting (identical output; the bytecode of that test shrinks from 1766 to 1505 lines with everything on), all other `tests/`, Hello World and the eight n-body ports with
every optimizer switch on against all off (32 programs, identical output). The pass was run against 10 deliberately broken versions: 4 change the test's result (a swapped true/false, a
conditional jump treated as unconditional, jump targets not followed, a wrong label dropped; three of these break the link step, which also counts as caught) and 6 do not, because the
pass has two independent safeguards (only lines that became unreachable because of the rewrite are removed; and the reachability roots) that mask each other, or because the
mutated case cannot be written in the language (code after `return` is already a compile error). Not covered: the Intel/MASM target, a fuzzer.

### `struct-unpacking`

Top-level switch in `===compiler.config===`, `struct-unpacking: on|off`, read by the Optimizer's `StructUnpackingPass` (shipped `off`; a missing key is off; any
other value stops the compile). It runs first in the Optimizer's outer loop, so elision, shifting and folding see the new variables in the same run.

A local `ALLOC v mut_S` / `imut_S`, where `S` has only scalar members (integers, f32/f64, bool), is replaced by one variable per member (`v__x`, `v__y`, ...; the
type is the member's declared type). It qualifies only when every mention of `v` is one of: its own declaration; a construction (`ADDR v T`, the hidden `___type` slot, one
expression per member with padding lines in between, `ASSIGN`), where each member expression may use pushes, arithmetic, compares and casts but no call and no mention of `v`;
a member read `PUSH v.m` (not followed by `ADDR_OF`); or a member write / compound write (`ADDR v; PUSH_FIELDNAME m; DOT_LHS`). Then the construction becomes one
assignment per member, reads and writes are redirected, and the hidden slot and the padding disappear. Left alone: a struct that is copied whole, passed by address (`auto p`),
has a member address taken (`raw p.x`), is reassigned from its own members (`v = V{x= v.y, y= v.x}`), holds a non-scalar member, or lives in a function with inline assembly.

Verified on Linux with `tests/structunpack_test.caspien` (11 checks, hand-computed) at on and off (identical), all other `tests/` programs, Hello World and the eight n-body ports
with every optimizer switch on (unrolling aggressive) against everything off (31 programs, identical output). The test was run against 8 deliberately broken versions of the pass;
6 turn it red. The two survivors are equivalent: one drops a member-address guard (the resulting `PUSH v__x; ADDR_OF` is still a correct scalar address, elision has its own guard),
and one drops a stack-count sanity check that valid bytecode can never violate. Also passes (11/11) on the `windows_gnu` target under Wine with all switches on. Not covered: the Intel/MASM target, a fuzzer.
Only structs with scalar members are unpacked; a struct that contains another struct, an array, a pointer or an owned value is left as it is.

**Pre-existing Codegen bug found while building this -- now fixed (see `### struct literal stores`):** a struct literal that had a member narrower than 8 bytes AND a computed member value segfaulted with every optimizer switch off. It no longer does.

### struct literal stores

Fix for the bug found during struct unpacking: a stack struct literal such as `P{x= k + 1, b= 3}` with a member narrower than 8 bytes (`u8`, `u16`, `u32`, `f32`, `bool`) and a computed member (an expression, a call) crashed. Cause: Codegen's byte-exact fast path only applies when every member is a plain push; otherwise every member is pushed as a whole 8-byte word, and the fallback block-store assumed the words filled the struct's declared size, so it read the wrong slots (heap `new` already had a repack path for exactly this, stack literals did not). Fix, mirroring `new`: `AddressLoweringPass` (LowerOrderGenerator) recognizes a literal's own top-level `ASSIGN` (an `ADDR v T` followed by the struct's `PUSH <digits> imut_u64` type id, ending in `ASSIGN T T T`; a whole-value copy has no such push) and appends the struct's layout tokens (`m<bytes>` member, `p<bytes>` padding) to the line; `X86Backend.emitAssignRepack` copies each field word to its true offset in the destination and drops the words and address. The packed fast path is unchanged and ignores the extra tokens. Only scratch registers `%r10`/`%r11`/`%rax` are used.
Verified: `tests/struct_literal_test.caspien` (started as 26 checks; now 74, see array members below: narrow + computed members in several combinations, a call as a member value, all-constant literal, whole-value copy, pass by address, and 3,000,000 literals in a loop to check the stack stays balanced) crashes with the previous build (exit 139) and passes now with every switch off and on, on Linux and under Wine; all other tests, Hello World and the n-body ports with every switch on vs all off (36 programs, Wine included for all of `tests/`) identical. 7 broken versions of the fix (address not dropped, wrong field advance, wrong destination load, fast path bypassed, marker never applied, marker applied to copies) are all caught. **Array members.** A fixed array written element by element (`small= [20, 21, 22]`) is pushed as one whole 8-byte word per element, so a `u32[3]` member takes 3 words, not the 2 that its 12 bytes suggest; the first version of the fix described it as one 12-byte block and crashed. The layout description (compile-time tokens on the `ASSIGN`/`NEW` line, nothing at run time) therefore has a third token, `a<elemBytes>x<n>` (e.g. `a4x3`), for such a member; Codegen copies element *i* from word *n-1-i* to `dst + i*elemBytes`. An array taken from an existing variable (`small= src`) is pushed as one block (2 words for 12 bytes) and keeps `m12`. LowerOrderGenerator decides per member by splitting the literal's values with an operand-stack simulation (`splitValueUnits`: pushes, binary/unary operators, calls, the out-of-memory check after a nested `new`, a nested literal collapsing at its `NEW`): a member whose value-producing line has an array type is a block, otherwise it is `n` element values. This handles several members of the same array type written differently, and nested literals. If the lines cannot be split (an instruction it does not know) it falls back to counting array-typed value lines, which is only decisive when no array type is shared by two members; in the shared case it stops the compile with a message instead of guessing. Only arrays of scalars narrower than 8 bytes get the token (8-byte elements lay out the same either way). For heap `new` the literal's start is found by walking back to its type-id push, skipping nested literals (`findNewRunStart`).

**Two more Codegen bugs found and fixed while doing this.** (1) Reading an array element through an owning heap pointer (`p.small[1]`) crashed: `DEREF` of an aggregate did not record that a whole array value was pushed, so the following element lookup took its "bare pointer" path and dereferenced the array's own bytes. `DEREF` now sets the same pending-value-size state `PUSH` does. (2) Extracting an element of a small array (under 8 bytes: `u8[5]`, `u16[3]`) and every variable shift (`SHL`/`SHR`/`SAR`) used `%rcx` as the shift-count register without saving it, clobbering a finished earlier argument of the call being assembled (`%rcx` is the 4th SysV argument and the 1st win64 one, i.e. the format string of a printf on `windows_gnu`). They now save and restore it. This was also the real cause of the crash I had first described as "reading a `u8`/`u16` array member of a struct crashes on Windows"; it was never specific to structs.
Tests: `tests/struct_literal_test.caspien` (74 checks: narrow and computed members, calls as values, all-constant literal, whole-value copy, pass by address, 3,000,000 literals in a loop, `u64[3]`/`u32[3]`/`u8[5]`/`u16[3]` members with constant, computed and variable-sourced elements, two same-typed arrays in every list/variable combination, heap `new` incl. reading the elements back through the pointer and as five call arguments, and a nested heap literal) and `tests/call_arg_regs_test.caspien` (4 checks: a variable shift and small-array element extraction as later arguments of a call). Both pass with every switch off and on on Linux and under Wine. 18 broken versions of the fixes (repack, array token, block detection, unit splitting, `DEREF` tag, `%rcx` saves) were each caught. **Third round -- arrays of structs, multi-dimensional arrays and inline structs as members of a literal that also has a computed member (asked for: "test this, best to know if there are gaps").** Testing this found five real bugs, all fixed: (1) an array of structs member crashed (stack and heap): the layout tokens only knew scalar arrays; `AddressLoweringPass.layoutFromUnits`/`valueFromUnits` now walk the literal's value units recursively (struct -> its own layout, array of scalars -> `m<size>` or `a<e>x<n>`, array of structs/arrays -> per element). (2) A nested heap `new` started its layout at an inline element's type-id push; `splitValueUnits`/`findNewRunStart` now match the struct's class id. (3) `arr[i].a` on a narrow field returned the wrong bytes (the `DOT` read address was right only for whole words): fixed in `X86Backend` (`size <= 8` path). (4) Reading an 8-byte array member (`u32[2]`, `u8[8]`) crashed because `PUSH 8` looks like a pointer: `LOOKUP_ARRAY` now carries a `t8` token. (5) 3-D narrow arrays / an intermediate lookup that yields a small array crashed: `LOOKUP_ARRAY` carries `ra`. Also verified: the n-body ports on Wine (all 8 identical with switches on and off, and equal to Linux).
Tests: `tests/struct_literal_nested_test.caspien` (60 checks: array of structs with constant and computed elements, inline struct members, `u32[3][2]`/`u64[2][2]` multi-dimensional members built from row variables and as literals, a 3-D array from plane variables, an 8-byte array member, a nested heap `new` holding an array of structs plus two same-typed `u32[3]` members). Passes on Linux and under Wine with every switch off and on. 10 broken versions of the third-round fixes were tried. The first run caught 9; the survivor (`nested_new_any`: dropping the class-id match in `splitValueUnits`' `NEW` case) turned out NOT to be equivalent: with it the literal falls back to the counting path, which happens to give the same tokens unless a nested `new` is followed by two same-typed array members written differently (one as a block, one element by element). `heapNested2` in the test now covers that shape, and the broken version fails to compile it, so all 10 are caught.
**Fixed afterwards (front end):** a nested integer/float array literal (`let:<mut u32[3][2]> g = mut [[1,2,3],[4,5,6]]`, also 3-D, `u8`/`u16`/`s32`/`f32`/`f64`, and as a struct member or in heap `new`) was rejected as `indeterminate_u64[3][2]`, because only a flat literal carried its element values up for adaptation. `TypeChecker.checkArrayLiteral` now flattens the rows' values (and float-ness) onto the outer literal, `arrayLiteralAdapts`/`floatArrayLiteralAdapts` compare the whole dimension suffix (`[3][2]`) and check every value fits, and `adaptLiteral` retags one dimension at a time. An out-of-range element or a shape mismatch is still a type error. Tests: 10 more checks in `tests/struct_literal_nested_test.caspien` (now 59; Linux and Wine, switches off and on); 2 broken versions of the change caught.
**Fourth round -- the two shapes listed as untested, now tested (`tests/array_member_shapes_test.caspien`, 48 checks; Linux and Wine, switches off and on; 7 of 7 broken versions caught).** Findings, all fixed: (1) `h.cells[i].vals[j]` (an array member of a struct that is an array element) crashed: a `DOT` that yields a fixed array of at most 8 bytes did not tag the pushed word as a small array, so the next lookup treated it as a pointer. LowerOrderGenerator now appends `ra` to such a `DOT`; Codegen tags the result. (2) A narrow array member that straddles an 8-byte word of its struct (`u8[5]` at offset 4, `u8[3]` at offset 6) read wrong bytes through a pushed block, because the block's words are reversed and the member is not contiguous: the `ra` read now loads the word it starts in and the next struct word and joins them with `shrd`. (Scalars cannot straddle -- they are aligned to their own size; only arrays can.) (3) A plain local narrow array literal with a computed element (`let:<mut u16[3]> a = mut [c, c + 1, c + 7]`) segfaulted, with every switch off: the pushed words were stored as if packed. This predates all of the above (it was the "array literal with computed elements segfaults" item in older notes). `AddressLoweringPass.findArrayLiteralAssigns` marks such an `ASSIGN` with `a<e>x<n>` and Codegen's existing repack handles it (now also for results of at most 8 bytes). (4) Front end: an array literal mixing a typed variable with plain literals (`[c, 2, c + 7]`, `c: u16`) was rejected (`u64` and `u16`); the literals now adopt the typed element's type when their value fits (out-of-range still errors).
**Known gaps, not fixed:** (a) The Intel/MASM target (`target windows`): the orchestrator produces the `.s` but cannot assemble it, and no ml64/nasm exists here. Inspecting the generated file found the Intel output is not valid MASM as it stands: it mixes NASM-style operands (`dword [rax]`, no `ptr`) with MASM directives (`PROC`, `.code`), and emits `movzx rax, dword [rax]` and `movzx rax, rax`, which no assembler accepts. This is the pre-existing Intel backend, not this change. The new `DOT`/`shrd` sequence is written to mirror the AT&T one, but is unexecuted. (The small-array `DOT` read used to load the word below the pushed block even when the field does not straddle; it now reads that second word only when the field really straddles -- a `cmov` picks the same word again otherwise -- so nothing outside the block is read. Checked by inspection of the asm and a broken version (flipped condition) is caught.)

### `variable-elision` and `variable-shifting`

Two top-level switches in `===compiler.config===`, `variable-elision: on|off` and `variable-shifting: on|off`, read by the Optimizer's
`VariableElisionPass` and `VariableShiftingPass` (both shipped `off`; a missing key is off; any other value stops the compile). Independent
switches; shifting only pays off with elision on.

Elision: a local scalar (u8..u64, s8..s64, f32, f64, bool) with exactly one assignment, and that assignment is `x = <literal>`, whose every read
comes after the assignment (no forward jump before it lands between it and the read) and whose address is never taken, is removed: the three
assignment lines and the declaration go, and each read `PUSH x T` becomes `PUSH <literal> T`. Parameters, struct fields, address-taken
variables (`raw x`), variables with more than one assignment or a non-literal assignment (for elision), negative literals in narrow types, and functions with inline assembly are
left alone. With `constant-folding: on` the substituted literals fold in the same inner loop (`let a = 5; let b = 7; a * b` becomes `35`).

Shifting: a variable with two or more constant assignments, no read before the first, is split at each reassignment that is straight-line
code (not inside a loop, and every later mention is reached whichever way the branches go): the reassignment declares a fresh variable
`x__sN` (same type and mutability, declaration placed with the original) and the assignment and every later reference use it. A reassignment
inside an `if` or a loop, or one that a branch could skip, does not split, so post-merge references keep the original variable. Functions
with inline assembly or a catch body are skipped. Side effects on register allocation: `RegVarHintPass` runs afterwards and sees the new
variables, and the ones elision removed are no longer hinted.

Verified on Linux with `tests/varelide_test.caspien` (20 checks, hand-computed expectations, covering propagation into expressions and calls,
constant `for` bound, assignment inside an `if`, variable assigned in a loop, address taken through `raw` and read through a pointer, a dead
store, narrow and negative narrow types, bool, straight-line and branch and loop reassignments, and try/catch) at each switch alone and both on
and off (identical output), all 22 programs in `tests/`, Hello World and all eight n-body ports (identical output and energies, f64 ones match the
reference). The test was run against 8 deliberately broken versions of the passes (no dominance check, no loop check, multiple assignment
elided, read before assignment, address-of ignored, catch-body skip removed, shifting inside loops, wrong literal); 6 turn it red. Two do not:
"read before assignment" (the language makes it unreachable, as `let` needs an initialiser) and "catch-body skip removed" (the read-before-first-assignment
rule already covers it, so that skip is a second line of defence that nothing exercises). Not covered: the Intel/MASM target,
a fuzzer (not wanted for the HOB optimizer). A `for` bound that was a constant variable (`let n = imut 4` then `0..n`) has its name in the hidden range's type text; when the variable is elided, elision rewrites that text
to the literal (`imut_range(0,4)`), so `LoopUnrollingPass` (which reads trip counts only from that text) now unrolls it. Checked by the loop count in the HOB (11 loops in `varelide_test` without
propagation, 9 with it and unrolling on) and by identical output; the test itself cannot fail if the rewrite is dropped, since the results are the same either way.

### `constant-folding`

Top-level switch in `===compiler.config===`, `constant-folding: on|off`, read by the Optimizer's `ConstantFoldingPass` (shipped `off`; a missing
key is off; any other value stops the compile). It collapses an operator whose operands are literals written in the bytecode into one
pushed literal: `2 + 3` becomes `5`, `(10 - 3) * 2` becomes `14`, `3 < 4` becomes `true`, `1.5 + 2.25` becomes `3.75`. Nested cases fold in
one run. Covered: integer `+ - * / %`, and the bitwise builtins `bits_and`, `bits_or`, `bits_xor`, `bits_not`, `bits_left`, `bits_right` at every width, signed or unsigned (see the section below for the shift rule; 64-bit arithmetic wraps exactly as at runtime), the six
comparisons at every integer width (signed or unsigned as the type says), `&&`, `||`, `!`, f32/f64 `+ - * /`, the six float comparisons and float
negation, and the `TRUNC` (to an unsigned type), `SEXT`/`ZEXT` (to 64 bits) casts. Not folded: anything with a variable operand (that is constant
propagation, a different pass), a float result that is infinity, NaN or negative zero (the bytecode has no literal for them; the runtime computes
it as before), narrow-integer `+ - * / %` arithmetic, and the cases the language already rejects (a literal `/ 0` or `% 0`, a signed divisor that
is not proven positive). Verified on Linux and `windows_gnu` (Wine) with `tests/constfold_test.caspien` (11 checks, each comparing a folded literal
expression with the same operation done at runtime through variables) at on and off, the other programs in `tests/` (folding fired in seven of
them; identical output), and all eight n-body ports (identical energies). The test was checked against 17 deliberately broken versions of the
pass (wrong operator, swapped operands, wrong compare, missing wrap, ...); all 17 turn it red. Not covered: the Intel/MASM target, a fuzzer, and compares/casts on narrow integer types (the pass accepts them, but literal-only source expressions are always 64-bit, so no test program produces one).

### `float-variables-in-registers`

Top-level switch in `===compiler.config===`, `float-variables-in-registers: on|off` (needs `variables-in-registers: on`; shipped `off`).
Hot f32 locals (the Optimizer's `REGVAR ... f` hints) live in xmm registers instead of frame slots, up to six per function: xmm8-13 on
SysV (Linux), xmm6-11 on win64. SysV has no callee-saved xmm registers, so every call spills them to their frame slots and reloads
them afterwards (a throw resumes at a catch label that reloads); win64 keeps them across calls. Same all-or-nothing rule as integer
variables. With `off`, float hints go to the integer registers as before. Measured gain on the first version was small (0-15% on the n-body programs).
Later changes (see CLAUDE.md, step 1): f64 slots (`R_MOV 8`) are promoted too, and when a function has more candidates than registers,
variables whose lifetimes never overlap share a register (liveness over the register-form text; also applies to the integer registers).
Measured on Linux with everything on, previous tree -> this tree: spectral-norm naive 0.67 -> 0.47 s, optimized 0.67 -> 0.25 s, n-body
natural f64 0.77 -> 0.67 s, scalars f64 1.47 -> 1.22 s; fannkuch optimized unchanged. Tests: `tests/regvars_share_test.caspien`,
`tests/regvars_share_check.sh`. The shipped `toolchain.config` keeps this switch `off`.
Verified on Linux and `windows_gnu` (Wine) with `tests/regvars_float_test.caspien` and the snapshot programs; the Intel/MASM target and
float fuzzing are not covered.

### Jump cleanup, one `lea` fewer for global arrays, and `for` without a lower-bound test (always on)

Three more code-generation changes with no switch (see the root `CLAUDE.md`): the per-iteration test of a `for` loop is `i < end` only (the counter starts at `start` and is immutable in the body); `if c { break }` no longer produces `jne; jmp; jmp` chains (`JumpCleanupPass`); and a global array element addressed by a variable held in a register is one `lea` plus an indexed `mov` (`IndexedAccessPass`, 8-byte elements only). Programs give identical output (`tests/loopjump_index_test.caspien`, `tests/lob_passes_check.sh`, the whole `tests/` sweep against a build without them). More than three variable registers was first left undone (only caller-saved r8-r10 were left); step 5 added them for call-free regions only (see the next paragraph). Only the AT&T forms have been run.

Benchmarks: `benchmarks/bench_suite.py <sieve|strings|graph|sorting>` runs the four newer programs (naive/safe/unsafe Caspien, free/leak C/C++/Rust, Go, Java, Node, Bun, LuaJIT) and `benchmarks/charts_all.py` draws every program into `charts.html`; results and caveats are at the end of `benchmarks/RESULTS.md`. Lua 5.4 is no longer measured.


Step 5 (no switch, needs `variables-in-registers` and `deferred-operands`): the hidden `for` range end is promotable (`RangeEndHintPass`), and r8/r9/r10 are extra variable registers (`%v3..%v5`) that `RegVarPromotionPass` gives only to variables that are never live across a call or any mnemonic outside a conservative whitelist; r8/r9 are withheld from functions that use argument 3 or 4, r10 from functions with a float-result stash. They are used only in functions with more hot variables than callee-saved registers, so other output is unchanged. Fannkuch-redux gains about 14-17% (same-session, alternating old/new), the other benchmarks are unchanged within noise; outputs identical (`tests/regvars_volatile_test.caspien`, `tests/regvars_volatile_check.sh`, a 63-program old-vs-new sweep). Linux-run only; windows_gnu builds were assembled and linked, not run; MASM untested. See the root `CLAUDE.md`.

### Dynarray of structs: field reads (always on)

`es[i].field` on a dynarray (safe or unsafe) whose element is a struct used to read 0 or crash (writes and whole-element copies always worked): Codegen's `LOOKUP_DYN` / pointer-base `LOOKUP_ARRAY` did not tell the following `DOT` the size of the element block. Fixed; `tests/dynarray_struct_test.caspien` (15 checks). Recursive structs stay rejected (edges in the graph benchmark are indices). See the root `CLAUDE.md`.

### Dynarray access in register form, r8-r10 in allocating functions, `@lock` inlining (always on)

No switch. `RegisterFormPass` now keeps `LEN`, `LOOKUP_DYN` (value and address form), `ZEXT` and `TRUNC` in registers (the dynarray header is 16 bytes, so an element address is `base + 16 + idx*size`, written as one `R_LEA` with a displacement), a function that allocates (`new`, `resize`) may use r8-r10 for variables that are not live across the allocating line, and `FunctionInliningPass` accepts `@lock`-decorated functions (a compile-time proof contract; the lock code is emitted at the caller), so the stdlib `DynamicArray.get/set` are inlined at `aggressive`. Safe sieve (1e8) 3.24 -> 1.85 s against C -O2 0.85 s in one session, naive 7.24 -> about 3.4 s; across the four new benchmarks the safe variants are now 1.3-1.6x C (strings 4.5x), the naive stdlib versions 2.7-2.9x (the `String` class 24x, not improved). Tests: `tests/regform_dyn_test.caspien`, `tests/regform_dyn_check.sh` (47), `tests/regvars_blocked_test.caspien`, `tests/inline_hob_check.sh` (57), old-vs-new sweeps of 65 programs under 4 configs, 0 differences. Linux-run only. See the root `CLAUDE.md` and `benchmarks/RESULTS.md`.

### Dynarray capacity (always on)

A safe dynarray block is `[len][cap][elements...]`; `cap` used to be written equal to `len` and never read. `RESIZE` now honours it: a resize with `newCount <= cap` only rewrites `len` (no `realloc`, the block does not move, so the ghost table is not touched); a growth past `cap` reallocs to `max(newCount, 2 * cap)`; a resize leaving fewer than `cap / 4` elements reallocs down to exactly `newCount`. A first resize from an empty array is therefore exact (`resize(a, n)` on `dyn([])` allocates n elements), and a run of one-element growths (`pushBack`) costs O(1) amortised reallocs (100000 pushes: 38 reallocs in `tests/dyn_growth_check.sh`). `CLONE_DYN` copies the block and then sets the copy's `cap` to its `len`. `len()` still returns the length. A failed growth `realloc` leaves the old block intact, as before (no retry with a smaller size). Memory held after shrinking is at most 4x the live elements. The unsafe (headerless) form is unchanged.

### Strength reduction, branch fusion and three register variables (always on)

Three code-generation changes with no switch (found by the fannkuch-redux benchmark, see the root `CLAUDE.md`): unsigned `/` and `%`
by a constant power of two become a shift and a mask; a compare that only feeds a branch becomes `cmp` plus one conditional jump
(`R_BRC`), also for two compares joined by `&&`; a fixed-size range/struct construction is stored word by word instead of `rep movsb`.
`variables-in-registers` now also keeps up to three hot variables per function (r12 joins r13/r14 in functions that never need a
fourth temporary), and a loop counter declared in several loops under one name is eligible. Programs give identical output
(`tests/strength_test.caspien`, `tests/regvars3_test.caspien`, the whole `tests/` sweep against a build without them). Only the AT&T
forms have been run; the Intel/MASM forms of the new instructions are unexecuted.

### `variables-in-registers`

Another top-level switch in `===compiler.config===`, `variables-in-registers: on|off` (needs `deferred-operands: on`; a lone `on` is
ignored). The Optimizer marks hot scalar locals (`REGVAR name weight`: loop counters, accumulators; weight counts each mention
8^loop-depth times); the LowerOrderGenerator's `RegVarPromotionPass` then keeps up to three of them per function in callee-saved
registers (`r13`, `r14`, and `r12` when the function never needs a fourth temporary) instead of their frame slots. Which register is decided there, not by the hint. A local is promoted only
if every mention of its slot in the final code is an operand the pass can rename; anything else (address taken, a stack-form
instruction the register-form pass could not fuse, a size mismatch) leaves it in memory. A function containing `new`, `clone`, a
struct field read wider than a word, an array read from a value block, `resize` or inline assembly keeps everything in memory,
because the backend uses `r13`/`r14` as scratch there. With `off` the assembly is byte-identical to a build without the feature.
Programs give identical output either way (`tests/regvars_test.caspien`, `tests/callee_saved_probe.sh`). Gain on the n-body
benchmarks is small (see `benchmarks/nbody/RESULTS.md`). Only the AT&T forms have been run; the Intel/MASM forms are unexecuted.

### Bitwise builtins and integer literals (always on)

Language reference. All eight builtins take integers and return the operand type; they need no `unsafe`.

| call | meaning |
|---|---|
| `bits_and(a, b)`, `bits_or(a, b)`, `bits_xor(a, b)` | `a & b`, `a \| b`, `a ^ b` |
| `bits_not(a)` | complement truncated to the operand's width: `bits_not(0)` is 255 for a `u8`, -1 for an `s8`, 18446744073709551615 for a `u64` |
| `bits_left(a, n)` | `a << n`, bits shifted past the top are discarded |
| `bits_right(a, n)` | `a >> n`: logical (zero fill) for unsigned types, arithmetic (sign fill) for signed types |
| `bits_rotl(a, n)`, `bits_rotr(a, n)` | rotate the bit pattern of `a` left / right within its own width (`rol` / `ror`). Unlike a shift, the count is taken MODULO the width: `bits_rotl(x_u32, 32)` is `x`, `bits_rotl(x_u8, 9)` is `bits_rotl(x_u8, 1)`, a negative signed count rotates the other way. Same typing rules as the shifts (both operands the same integer type, a literal adapts); HOB `ROTL`/`ROTR`, register form `R_BIN ROTL\|ROTR`, constant-folded at every width |

Types: the two operands of a binary builtin (and the count of a shift) must have the SAME integer type, except that a literal adapts exactly as in arithmetic: `bits_and(x_u8, 0xF0)` and `bits_left(x_u8, 3)` compile (the literal must fit the type of the other operand, otherwise "literal 300 does not fit in 'u8'"); two typed operands of different types are an error, as are floats, bools and strings. A call made only of literals is a `u64` (an `s64` when an operand is negative); typed limit constants (`u8Max`, `s16Min`, ...) give narrow typed operands in a constant expression.

Shift counts (identical constant-folded, on the stack path and on the register path, Linux and Windows): the count is read as an UNSIGNED number of its own width (a signed count of -1 is a huge count). A count at or above the operand's width gives 0 for `bits_left` and for unsigned `bits_right`, and the sign fill (0 or -1) for signed `bits_right`; a count below the width is an ordinary shift. There is no hardware masking: `bits_left(x_u64, 64)` is 0, not `x`.

Integer literals: decimal, hexadecimal (`0xFF`, `0XFF`), binary (`0b1010`, `0B1010`), each with `_` between digits (`1_000_000`, `0xFF_FF`, `0b1111_0000`). They are normalised to decimal in the lexer and typed and range-checked exactly like decimal literals: `0xFFFFFFFFFFFFFFFF` is 18446744073709551615 (a `u64`), `-0x8000000000000000` is the `s64` minimum, a literal above `u64` max is an error. They work anywhere a decimal literal does (`match` arms, `const`, enum values (63-bit), array sizes, range bounds). Malformed forms (`0x`, `0b`, `0b2`, `0xG`, `1__0`, `0x_`) are lex errors with a message naming the problem.

Passes: front end (`Lexer`, `TypeChecker.checkBitsBuiltin`, `BytecodeEmitter`: `BITS_AND`/`BITS_XOR`/`BITS_OR`/`BITS_NOT`/`SHL`/`SHR`), `ConstantFoldingPass`, the three Optimizer passes with operator lists, `AddressLoweringPass`, `RegisterFormPass` (`R_BIN BAND|BOR|BXOR|SHL|SHR|SAR`, `R_UN BNOT`), `X86Backend` (stack and register form; AT&T for Linux and `windows_gnu`, Intel for `windows`). Tests: `tests/bits_ops_test.caspien` and `tests/bits_ops_matrix{8,16,32,64}_test.caspien` (generated by `tests/bits_ops_gen.py`, expected values from an independent Python model, byte-identical output with everything on, shipped Linux config, register-form only and everything off), `tests/bits_ops_check.sh` (34 compile-time checks). Status and what was not verified: `BITWISE_STATUS.md`.

## Tested

This exact orchestrator, wired up exactly as shipped, was used to build
and run a real "Hello World!" end-to-end in the sandbox:

- `target linux`: full default pipeline (no stop flags) -- produced a
  working ELF binary, ran it, exit code 0.
- `target windows_gnu`: full default pipeline -- produced a working PE32+
  `.exe`, ran it under Wine, exit code 0.
- `--hob`, `--lob`, `--asm`: each verified individually to stop at the
  right stage and write the right intermediate text to the requested
  output path.
- `--no-warnings`: verified it suppresses a real emitted warning, and that
  the warning shows by default without the flag.
- A genuine compile error: verified it's reported to the console with a
  nonzero exit code (1), and a missing/malformed CLI invocation exits 2
  with the usage message.

`target windows` (MASM) itself remains unverified -- there's no MASM
toolchain in this sandbox to test against.
