# Benchmarks: n-body, fannkuch-redux, spectral-norm

Linux target, 2-core VM, runs one at a time. Time = fastest of N runs; compile time and peak RSS = medians.
Charts: `charts.html` (built by `charts_all.py`). Raw data: `<program>/results.json`. Harness: `nbody/bench.py`, `bench_program.py`.
Every implementation's full output equals the C -O0 output at the same size.

## Re-run after the speed work, 4 Oct 2026

Every program and language re-run on the same VM (fastest of 3 runs, compile time the median of 2 builds, every output equal to the C reference), after: an allocation-free `insecure_hashOf` and a leaner stdlib `HashMap`, a scalar sqrt instruction, division and modulo by a literal as a multiply, small constant `memcopy` inlined, struct-field reads on dynarray elements fused, integer call results kept in registers, and loop-index substitution in fully unrolled innermost loops. `charts.html` now draws two gold lines on every execution-time bar at the best time minus and plus the typical run-to-run noise (the median of the three repeats minus the best one).

Caspien rows that moved by more than 12% (all with the aggressive "full" configuration unless the row says "off"):

| Program, variant | before | after |
|---|---|---|
| k-nucleotide naive (stdlib HashMap) | 16.00 s | 4.99 s (0.31x) |
| LRU naive (DynamicArray + HashMap) | 5.15 s | 2.07 s (0.40x) |
| Heap graph, struct nodes, safe | 0.58 s | 0.28 s (0.47x) |
| Strings naive (String class) | 4.46 s | 2.74 s (0.61x); off 7.68 s -> 5.61 s |
| Binary trees optimized safe | 0.62 s | 0.44 s (0.72x) |
| JSON optimized safe / unsafe | 2.16 s / 1.89 s | 1.88 s / 1.65 s (0.87x) |

Slower by 12-13%, all within the 10-30% noise of this VM and none repeated in a same-run comparison: fasta optimized safe (off), JSON naive and optimized safe (off), spectral-norm naive (full), strings optimized unsafe (off). Everything else is within noise. Executables are the same size or smaller (`knucleotide` optimized safe 56 KB -> 53 KB).

One regression was found and fixed during this re-run (not in the numbers above): the first pass made the n-body "loop" variants 1.7x slower. Two causes: `sqrtsd` has a false dependency on its destination register (now cleared with `xorps` first), and substituting the loop index into bodies with range proofs left `match 2 in 0..5` as run-time checks on constants that the constant folder does not remove (`IN` is not folded). Substitution is now limited to innermost loops without range proofs. Making `IN` fold is the proper fix and is on the to-do list.

Not verified: Windows (the new instructions have only been run on Linux and, for the check scripts, under Wine); the weak spots are unchanged (peak memory of LRU naive, size of the Merkle-tree programs, sorting).

## Re-run of everything, 3 Oct 2026

All 15 time-measured programs (n-body, fannkuch-redux, spectral-norm, sieve, strings, graph, sorting, binarytrees, mandelbrot, fasta, k-nucleotide, LRU, Merkle tree, hello world, JSON) across every language (C, C++, Rust, Go, Fortran, Objective-C, Odin, Zig, Chapel, D LDC/GDC, Nim, Crystal, OCaml, Swift, Codon, Java, C#, Kotlin, WebAssembly, Node, Bun, LuaJIT) with the current compiler (after `@drop`, the file-system stdlib and the compiler fixes). Fresh sandbox, toolchains reinstalled (versions as in `newlangs.py`; Bun is now included). Fastest of 3 runs, compile time = median of 2 builds, 2-core VM, one program at a time. Every output equals the C reference. Charts: `charts.html` (green = Caspien, hatched = optimisations off / naive; red = bare metal and natively compiled; purple = compiled + interpreted (JVM, .NET, WebAssembly); blue = interpreted (Node, Bun, LuaJIT)).

Best Caspien variant with everything on, time against the best C -O2 build of the same run (old = 1 Oct run):

| Program | old | new |
|---|---|---|
| n-body | 2.06x | 2.12x |
| fannkuch-redux | 1.05x | 0.98x |
| spectral-norm | 0.98x | 1.02x |
| sieve | 1.19x | 1.66x |
| strings | 2.62x | 2.74x |
| graph | 1.25x | 1.37x |
| binarytrees | 1.19x | 1.33x |
| mandelbrot | 1.18x | 1.21x |
| fasta | 1.66x | 1.78x |
| k-nucleotide | 2.08x | 2.21x |
| LRU | 1.76x | 1.84x |
| Merkle tree | 1.99x | 2.03x |
| sorting | 1.47x | 1.58x |
| hello world | 0.69x | 0.82x |
| JSON | 2.90x | 2.64x |
| geometric mean | 1.49x | 1.59x |

The whole machine ran faster this time (C and Caspien absolute times both dropped, for example Caspien sieve 1.12 s to 0.88 s, k-nucleotide naive 18.8 s to 16.0 s), but C gained a little more, so the ratios rose slightly. That is inside the 10-30% VM noise; no run showed a Caspien slowdown in absolute terms that is not explained by the machine. Not run: Windows targets, Dart (not installable), the threads/swaplock/recursion benchmarks (data from earlier runs kept).


| Program | Size | Runs | C -O2 | Caspien naive off | naive full | optimized off | optimized full | Lua 5.4 | LuaJIT |
|---|---|---|---|---|---|---|---|---|---|
| n-body | 5,000,000 steps | 5 | 0.29 s | 3.98 s | 0.68 s | 1.93 s | 0.63 s | 8.63 s | 1.21 s |
| fannkuch-redux | n = 11 | 3 | 3.55 s | 15.12 s | 3.62 s | 12.78 s | 3.55 s | 53.41 s | 7.18 s |
| spectral-norm | N = 2000 | 3 | 0.23 s | 2.92 s | 0.31 s | 1.20 s | 0.23 s | 9.05 s | 0.29 s |

(Everything re-measured in one session on 1 Oct 2026, after step 5, all languages; all outputs equal the C reference. The VM drifts by about 10% between
sessions and even within one (this session's n-body "full" rows read 0.57-0.68 s, the same code measured 0.56-0.63 s an hour earlier), so absolute figures in the older sections below are not comparable.)

Whole field, fastest first (execution time, seconds): n-body: Rust 0.22, C f32 -O2 0.24, C++ 0.27, C -O2 0.29, Java 0.40, Go 0.45, **Caspien 0.57-0.68 (full)**, Node 0.58, Bun 0.67, LuaJIT 1.21, C -O0 1.52, Caspien off 1.9-4.1, Lua 5.4 8.63.
Fannkuch n = 11: Rust 2.59, Go 3.38, Java 3.38, C++ 3.54, **Caspien optimized 3.55 (full)**, C -O2 3.55, **Caspien naive 3.62 (full)**, Bun 3.95, Node 4.98, C -O0 5.62, LuaJIT 7.18, Caspien off 12.8-15.1, Lua 5.4 53.4.
Spectral-norm N = 2000: Rust 0.22, C++ 0.23, C -O2 0.23, **Caspien optimized 0.23**, Go 0.28, LuaJIT 0.29, **Caspien naive 0.31**, Bun 0.34, Node 0.56, C -O0 0.68, Java 0.69, Caspien off 1.2-2.9, Lua 5.4 9.05.
Lua rows: the same `nbody.lua` / `fannkuchredux.lua` / `spectralnorm.lua` (in each program's `reference/` folder) run on both interpreters; the "size" column is the interpreter binary plus the script.

n-body "naive" = `nbody_natural_f64` (small functions, plain loops); "optimized" = `nbody_scalars_f64` (hand-unrolled scalar statics).
Fannkuch "naive/optimized" = `fannkuch_naive/opt`; spectral "naive" = A(i,j) through a function, "optimized" = incremental denominator, no calls.

## Threads: spawning and lock contention (3 Oct 2026)

Two programs for OS-level threads, in `threads/` and `swaplock/` (harness `bench_threads.py`; raw data in each folder's `results.json`). Only languages with OS threads
are included: C (pthreads), C++ (`std::thread`), Rust (`std::thread`), Java (platform threads), Go (every task pinned to its own OS thread with
`runtime.LockOSThread()`, since goroutines are otherwise multiplexed). Node, Bun and Lua are left out (workers and coroutines are not comparable thread-per-task
units). Linux, 2-core VM, fastest of 3 runs; every output equals the C -O0 output (`tasks=1024 checksum=130561855306`, `count=16000000 sum=295999808`;
the same sums come from a Python model at smaller sizes).

* **threads**: 1024 tasks in waves of 64 OS threads (spawn 64, join 64, repeat). Each thread runs 1,000,000 xorshift64 steps on its own state and returns a
  checksum; nothing is shared between threads. Caspien starts each thread with `par` and polls the handle's `state` (with `yield`) before `resolve()`; a `par`
  handle has no type name, so it cannot sit in an array and `gen.py` writes the 64 handles of a wave as 64 locals. Naive = the kernel step is a function,
  optimized = inlined by hand and unrolled four times.
* **swaplock**: 32 OS threads, each does 500,000 times {lock; `count += 1`; `sum += id + i % 7`; unlock} on one shared struct. Caspien's lock is a `swap` field
  (`match @lock`); the others use `pthread_mutex_t`, `std::mutex`, `Mutex`, `ReentrantLock` and `sync.Mutex`. Naive = a CLOSED lock is retried at once (pure spinning),
  optimized = a backoff policy (`impl default match @lock`) that calls `yield` on every 16th failed attempt.

| Program | C -O2 | C++ -O2 | Rust -O | Go | Java | Caspien naive off | Caspien naive full | Caspien optimized off | Caspien optimized full |
|---|---|---|---|---|---|---|---|---|---|
| threads (1024 tasks) | 1.23 s | 1.22 s | 1.23 s | 1.23 s | 1.39 s | 6.34 s | 1.49 s | 4.15 s | 1.48 s |
| swaplock (16M lock operations) | 1.13 s | 1.15 s | 1.54 s | 0.57 s | 0.45 s | 20.33 s | 19.46 s | 0.69 s | 0.53 s |

Reading: the thread-spawn work is compute bound, so Caspien with the optimizer on is within about 20% of C (naive 1.49 s, optimized 1.48 s against 1.23 s; the hand
unrolling buys nothing once the optimizer inlines the step function, and costs 50% with every pass off). The lock benchmark is where the design shows: a pure
spin lock with 32 threads on 2 cores wastes its time slices on lock holders that were preempted (19-20 s), and yielding on the 16th failed attempt brings it to
0.5 s, faster than the C/C++ mutexes (1.1 s here) and level with Java's lock (0.45 s), while the Rust `Mutex` row (1.5 s) is slower than C's. Why Go and Java are fastest here was not investigated. Not verified: Windows, and any machine with more than 2 cores.

## Step 5: the `for` range end in a register, and call-free r8/r9/r10 variable registers (1 Oct 2026)

Changes: `RangeEndHintPass` makes the hidden range end of a `for` loop promotable; `RegVarPromotionPass`/`X86Backend` add r8, r9, r10 as variable registers `%v3..%v5` for variables that are never live across a call (or any mnemonic outside a whitelist), only in functions with more hot variables than callee-saved registers. Part 1 alone changes nothing (the callee-saved registers are full); part 2 is what pays, and only on code of fannkuch's shape (many hot integer variables, call-free loops).
Same harness and method as before (previous tree vs this tree alternating, two rounds, best of both, "full" rows, seconds; outputs identical on every row):

| Caspien row (full) | natural f64 | plain f64 | loop f64 | arr f64 | scalars f64 | plain f32 | loop f32 | arr f32 | scalars f32 | fannkuch naive | opt | spectral naive | opt |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| before | 0.62 | 0.57 | 0.57 | 0.58 | 0.58 | 0.60 | 0.61 | 0.63 | 0.61 | 4.27 | 4.23 | 0.35 | 0.23 |
| after | 0.63 | 0.58 | 0.58 | 0.56 | 0.59 | 0.58 | 0.58 | 0.60 | 0.58 | 3.69 | 3.50 | 0.34 | 0.24 |

Fannkuch -14% and -17%; everything else is inside the ~5-10% noise (n-body mean change about -2%, spectral +7% on the optimized row is the noise of a 0.23 s run). With this step the fannkuch "optimized" full row equals C -O2 on this machine (3.55 s vs 3.55 s in the full rerun; the earlier same-day C -O2 figures were 3.38-3.44 s, so call it "within about 5%"). Not verified: windows_gnu was assembled and linked, never run; MASM untested.
Charts: `charts_before_after_step5.html`, all languages: `charts.html`. Raw data: `before_after_step5.json`, `<program>/results.json`.

## Steps 3 and 4: float constant pool, indexed float arrays (1 Oct 2026)

Step 3: each float constant in register form comes from a read-only pool entry (`.LFC<k>`; one `movss`/`movsd` or a memory operand, `xorps` for zero) instead of `movabs`+`movq` through a general register on every use (AT&T targets only; this is pooling, not hoisting into registers).
Step 4: `R_LEA &sym %vK scale ; R_LDX/R_STX` becomes `R_LDXI`/`R_STXI` (scale = 4 for f32, 8 for f64; one `lea sym(%rip)` is still needed because RIP-relative addressing cannot carry an index), and an `R_FBIN` result headed for a store stays in xmm (`FloatTempPass`).
Same harness and method as step 2b (previous tree vs this tree alternating, two rounds, best of both, "full" rows, seconds; outputs identical on every row):

| Caspien row (full) | natural f64 | plain f64 | loop f64 | arr f64 | scalars f64 | plain f32 | loop f32 | arr f32 | scalars f32 | fannkuch naive | opt | spectral naive | opt |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| before -> after | 0.76 -> 0.64 | 0.70 -> 0.62 | 0.68 -> 0.65 | 0.71 -> 0.62 | 0.70 -> 0.56 | 0.69 -> 0.65 | 0.77 -> 0.63 | 0.66 -> 0.60 | 0.69 -> 0.56 | 4.45 -> 4.21 | 4.51 -> 4.53 | 0.48 -> 0.32 | 0.26 -> 0.24 |

Float programs gain 4-20%, spectral-norm naive 32%; fannkuch is integer code and does not change (inside the ~10% noise). Where the indexed forms fire: the n-body `loop` programs (345 `R_LDXI`/`R_STXI` lines); the `arr` programs index with literals and have none. The pool: 48 entries in the pool test, none of them hoisted.
Caveats: Linux only; the Intel/MASM forms and Wine were not run; windows_gnu builds of the two new tests and two n-body programs assemble and link only. Charts: `charts_before_after_step3_4.html`, all languages: `charts.html`. Raw data: `before_after_step3_4.json`, `<program>/results.json`.

## Step 2b: the per-iteration `match` bounds test no longer builds the range on the stack (1 Oct 2026)

The `loop` n-body variants (`nbody_loop*.caspien`, 37 plain `match i in/into <array>` lines, seven per loop level; the `assume match` variants `plain` and `natural` skip the test) were 2.4x slower than every other variant. Their generated code for each `match` was
`PUSH 8 $i / PUSH 8 0 / PUSH 8 5 / POP $r 16 / POP $l 8 / PUSH $l / PUSH $r / GT_EQ_INT / PUSH $l / PUSH $r+8 / LT_INT / AND 1 / CMP / JMP`: the literal range 0..5 was
rebuilt on the stack, stored to a 16-byte temp and the index copied to another temp, every iteration, seven times per inner iteration (my earlier "four per inner iteration" was wrong: the inner loop of that file nests seven matches),
and the `PUSH 8 $i` also kept the loop counters out of variable registers. New always-on `RangeCheckFusionPass` (LowerOrderGenerator, stack-form text, right before `RegisterFormPass`) rewrites that exact 14-line shape into
`PUSH i / PUSH K1 / LT_INT / CMP / JMP` (lower bound 0 on an unsigned index is always true) or, for a non-zero lower bound, `PUSH i / PUSH K0 / GT_EQ_INT / PUSH i / PUSH K1 / LT_INT / AND 1 / CMP / JMP`; `RegisterFormPass` and
`BranchFusionPass` then turn it into `R_BRC LT 8 %v0 #5 @L`. Conditions: unsigned compares only, decimal literal bounds 0..2^31-1, and the three temp slots mentioned nowhere else in the same function (the stores are dropped).
Same harness and method as step 2 (previous tree vs this tree alternating, two rounds, best of both, "full" rows, seconds; outputs identical on every row, 0 differences):

| Caspien row (full) | natural f64 | plain f64 | loop f64 | arr f64 | scalars f64 | plain f32 | loop f32 | arr f32 | scalars f32 | fannkuch naive | opt | spectral naive | opt |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| before -> after | 0.73 -> 0.72 | 0.66 -> 0.70 | **1.64 -> 0.68** | 0.64 -> 0.66 | 0.64 -> 0.66 | 0.63 -> 0.67 | **1.59 -> 0.70** | 0.71 -> 0.67 | 0.64 -> 0.66 | 4.23 -> 4.16 | 4.56 -> 4.12 | 0.49 -> 0.51 | 0.24 -> 0.25 |

Only the two `loop` rows change (-58% and -56%); they now run as fast as the `assume match` and unrolled variants. Every other row is inside the VM's ~10% noise (programs without a constant-bounds `match` get the same code). Charts: `charts_before_after_step2b.html`.

## Step 2 of the gap-to-C plan: register-form coverage for global scalar reads and for calls in the middle of a statement (1 Oct 2026)

Not the planned "inline sqrtsd" (hand-patching it into the assembly gave no gain, see step 1) but what the n-body's generated code showed instead:
stack-form float code (118 asm instructions between two `call sqrt`, 42 push/pop, 37 `movq`). Two changes in `RegisterFormPass`:
(a) `PUSH n <global>` (a read of a global scalar) is now loaded into a temp (`R_LD n %tD &sym`, which `FloatTempPass` turns into `R_LDX` for floats);
(b) a RISKY region (floats, globals, computed addresses ...) that hits a call bracket (`CC_START`) or a plain stack arithmetic op (`*_FLOAT`, `*_INT`)
while entries are still remembered is now FLUSHED to stack form instead of rolled back, provided every remembered entry is one plain 8-byte word.
(The planned part (b), "array element read-modify-write", turned out not to be the cause: it already fuses in the hot loops. The real cause in the natural
n-body was that an inlined `sqrt` call sits underneath a pending `e = e - ...` (an address and a deferred read), the call was unfusable, and the whole region,
including every load already fused, was discarded: 42-line stack runs per loop iteration. The natural variant's LOB went from 1982 to 1702 lines.)
Same harness, same machine, previous tree and this tree alternating in one session (two rounds, best of both), only the "full" rows (the register-form pass does
not run with everything off); fastest of 5 (n-body) or 3 runs, seconds; outputs identical between the trees on every row:

| Caspien row (full) | natural f64 | plain f64 | loop f64 | arr f64 | scalars f64 | plain f32 | loop f32 | arr f32 | scalars f32 | fannkuch naive | opt | spectral naive | opt |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| before -> after | 0.70 -> 0.68 | 0.68 -> 0.64 | 1.60 -> 1.61 | 0.66 -> 0.66 | 1.27 -> 0.66 | 0.67 -> 0.65 | 1.51 -> 1.52 | 0.65 -> 0.62 | 1.20 -> 0.64 | 4.13 -> 4.03 | 4.18 -> 4.29 | 0.48 -> 0.48 | 0.25 -> 0.25 |

Only the scalar-statics programs gain (about -47%, 1.27 -> 0.66 s, C -O2 is 0.26 s); every other row is inside the VM's noise (about 10%). The natural
variant shrank 14% in generated code but not in time: its loop is bound by `sqrt` latency and the dependency chain, not by the pushes. What is still stack form:
the once-per-run `energy` code of the hand-unrolled programs (it needs more than four live temporaries, an expression-depth limit of the model, cold code), and in
the `loop` variants the per-iteration `match` bounds proofs (`PUSH i / PUSH 0 / PUSH 5 / POP 16 / POP 8` before each `R_BRC`; fixed by step 2b above), which is
probably why `loop` is still 2.4x the others. Charts: `charts_before_after_step2.html` (data `before_after_step2.json`).
Verified (Linux only): see the root `CLAUDE.md`. Not verified: the Intel/MASM target and Wine (`windows_gnu` builds assemble and link with mingw, they were not run).

## Step 1 of the gap-to-C plan: f64 register promotion fix + register sharing (1 Oct 2026)

What changed (`RegVarPromotionPass`): (a) f64 locals were never promoted, because RegisterFormPass spells an 8-byte float slot access as `R_MOV 8`
and the float renamer only knew `R_LD`/`R_ST`; (b) when a function has more hot variables than registers (3 integer, 6 float; typical after
inlining, where every inlined body adds its own loop counters and accumulators) the heaviest N won and every other variable stayed in memory.
Variables whose lifetimes do not overlap now share a register (liveness over the final register-form text).
Same harness, same machine, previous tree and this tree run one after the other in the same session; fastest of 3 (n-body 5) runs, seconds:

| Caspien row | n-body natural off | natural full | scalars off | scalars full | fannkuch naive off | naive full | opt off | opt full | spectral naive off | naive full | opt off | opt full |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| before -> after | 3.43 -> 3.49 | 0.77 -> 0.67 | 1.73 -> 1.74 | 1.47 -> 1.22 | 13.71 -> 12.83 | 4.70 -> 4.01 | 11.94 -> 11.96 | 4.13 -> 4.15 | 2.50 -> 2.26 | 0.67 -> 0.47 | 1.17 -> 1.20 | 0.67 -> 0.25 |

How to read it: the "off" columns cannot be affected (the pass is off there), and they moved by -10% to +3% (fannkuch naive off -6%, spectral naive off -10%),
so differences of about 10% are noise on this machine. Real gains: spectral-norm optimized full -63% (0.67 -> 0.25 s, C -O2 is 0.25 s in the same
session), spectral naive full -30%, n-body scalars full -17%, n-body natural full -12%, fannkuch naive full -15% (the only one near the noise
level); fannkuch optimized full and every "off" row are unchanged. Output of every row equals the C output; the f32 n-body rows were not re-measured
for this step (no f32 code changed). The shipped `toolchain.config` has `float-variables-in-registers: off` and function inlining off, so the shipped
default sees little of this; the figures are the everything-on configuration.
Why n-body gained little: a hand-patch of the generated assembly (`call sqrt` replaced by an inline `sqrtsd`, no spills, no alignment sequence)
changed natural 0.657 -> 0.640 s and scalars 1.22 -> 1.24 s, i.e. nothing. The remaining cost is float arithmetic on global scalars
(`PUSH 8 sym` is never fused into register form) and on array elements read-modified-written through a computed address, which stay in stack form:
about 118 instructions per pair interaction in the scalars program, 42 of them push/pop and 37 `movq`.
Charts: `charts_before_after.html` now shows this comparison (the earlier for-loop/jump-cleanup comparison remains in the table of the next section).


## Earlier: after the `for` lower-bound drop, jump cleanup and indexed global access

Full re-run of all three programs (every language, 1 Oct 2026, after the `for` lower-bound drop, jump cleanup and indexed global access). The machine was
clearly faster than on the earlier runs (every absolute figure is lower than the figures this file used to carry, including the plain Caspien rows
that those changes cannot touch much), so older numbers are NOT comparable with these. For an honest before/after the previous tree (the one before
those three changes) was run through the same harness in the same session: fastest of N, seconds, before -> after:

| Caspien row | n-body natural off | natural full | scalars off | scalars full | fannkuch naive off | naive full | opt off | opt full | spectral naive off | naive full | opt off | opt full |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| before -> after | 3.73 -> 3.24 | 0.85 -> 0.77 | 1.78 -> 1.66 | 1.48 -> 1.41 | 14.33 -> 12.08 | 5.04 -> 4.48 | 13.47 -> 11.59 | 4.28 -> 3.96 | 2.45 -> 2.28 | 0.65 -> 0.67 | 1.28 -> 1.26 | 0.60 -> 0.64 |

Spectral-norm "full" did not change beyond noise (0.65 -> 0.67, 0.60 -> 0.64 are within the run-to-run spread seen on this machine). Outputs of every
row equal the C output (n-body: the same-precision C program). A first run of this set showed `RUN FAILED` for the two fannkuch everything-off rows: a
real bug in the new `JumpCleanupPass` (in stack form a `JMP` right after `CMP` is the conditional jump and was treated as unconditional); fixed
and re-run, the figures above are from after the fix. Only Linux was measured. Charts: `charts.html` (all metrics, all implementations) and
`charts_before_after.html` (the Caspien rows before and after).

## Caveats
- Single-threaded reference ports, not the site's multithreaded versions. Smaller sizes than the site (spectral 2000 not 5500, fannkuch 11 not 12) and fewer runs.
- PHP, Python, Ruby not included. Linux target only.
- Caspien has no int-to-float cast, so the spectral port keeps f64 mirror counters. Arrays have fixed capacity 2000 with a runtime `n`.
- Optimizer scaling problem found: aggressive full unroll of literal-bound nested 2000-trip loops blew up the optimizer (OutOfMemoryError, ~74 s). The port uses a runtime bound to avoid it.
- Fannkuch sums use u64 with `wrap` for the signed conversion.

## Four new benchmarks: sieve, strings, heap graph, sorting/searching (and Lua 5.4 dropped)

Asked for ("some string manipulation", "a large graph on the heap and walking it", "sorting and searching algorithms", the sieve of Eratosthenes; naive and optimized, safe and unsafe Caspien; free and leak variants for the non-memory-safe languages; Lua 5.4 removed, LuaJIT kept; colours: green Caspien, red bare metal, purple Java, blue JavaScript/LuaJIT). Run with `python3 benchmarks/bench_suite.py <sieve|strings|graph|sorting>`; sources in `benchmarks/<name>/reference` (C, C++, Rust, Go, Java, JS, LuaJIT) and `benchmarks/<name>/caspien` (naive = stdlib `DynamicArray`/`String`, optimized safe = bounds proofs, optimized unsafe = no checks). Sorting variants are generated by `sorting/caspien/generate.py`. Linux, 2-core VM, fastest of 3 runs, seconds, everything-on Caspien; every output matches C.

| program | C -O2 | Rust | Go | Java | Caspien naive | opt safe | opt unsafe |
|---|---|---|---|---|---|---|---|
| sieve (1e8) | 0.85 | 0.99 | 1.00 | 0.85 | 7.24 | 3.24 | 2.77 |
| strings (2e7) | 0.25 | 0.23 | 0.33 | 0.43 | 6.54 | 1.54 | 1.15 |
| heap graph (2e6 nodes) | 0.27 | 0.26 | 0.37 | 0.63 | 1.55 | 0.68 | 0.52 |
| sorting (2e6) | 1.47 | 1.43 | 1.54 | 1.74 | 8.23 | 3.81 | 2.80 |

Reading: unlike fannkuch and spectral-norm (about 1x C), these four show Caspien 2-5x behind C for the best variant (unsafe: sieve 3.3x, strings 4.6x, graph 1.9x, sorting 1.9x), and 6-26x for the naive stdlib versions (String class 26x on strings). Safe costs about 20-40% over unsafe. Not yet investigated why (byte-array loops, memory-bound access); candidates are the missing memset-style fill, index addressing for integer dynarrays, and per-element call overhead in the stdlib classes. Free vs leak made no consistent difference at these sizes.
Caveats: Caspien rejects recursive structs on purpose, so edges are node indices, not pointers (the array-of-structs member-read bug found earlier is fixed, see the struct-node section below; the main graph rows here are parallel arrays); Caspien sorting elements are u64, others 32-bit; Linux only. Skipped website problems: coro-prime-sieve, pidigits, edigits, secp256k1, regex-redux, http-server (need language features/libraries Caspien lacks); json-serde, binarytrees, mandelbrot, fasta, knucleotide, lru, merkletrees and helloworld were added afterwards (section at the end).


## Closing the sieve/graph/sorting gap: dynarray register forms, volatile registers in allocating functions, `@lock` inlining (1 Oct 2026)

Asked for ("yes, please do" after the four new benchmarks showed Caspien 2-5x behind C). Profiling the safe/unsafe sieve showed the hot loops were still in stack form: `LEN`, `LOOKUP_DYN`/`LOOKUP_DYN_LHS`, `ZEXT` and `TRUNC` had no register-form handler, so every `f[i]` pushed and popped, and the loop variables could not live in registers. Three changes (details in the root `CLAUDE.md`, top section):
1. `RegisterFormPass` fuses `LEN`, `LOOKUP_DYN` (value and LHS), `ZEXT`, `TRUNC`; `R_LEA` gained an optional displacement (the 16-byte dynarray header); `IndexedAccessPass`/`RegVarPromotionPass` accept the 6-token form; the backend emits `disp(%base,%idx,scale)`.
2. `RegVarPromotionPass`: functions that allocate (`new`, `resize`) may now use the caller-saved variable registers r8-r10 for variables that are not live across the allocating line.
3. `FunctionInliningPass`: `@lock` is an accepted decorator (it is a compile-time proof contract only), so the stdlib `DynamicArray.get/set` under `match i into self.backing` are inlined at `aggressive`.

Same-session A/B (previous tree vs this tree, fastest of runs, seconds, everything-on): safe sieve 3.24 -> 1.85 (C -O2 0.85 that session), unsafe sieve 2.77 -> 1.87, naive sieve 7.24 -> about 3.4.
Full re-run at the end (1 Oct 2026, 3 runs, every language; the VM was slower and noisier than in the earlier sessions: C -O2 sieve measured 0.85, 1.14 and 1.27 s on different runs, so compare ratios inside one run, never absolute figures across runs):

| program | C -O2 | Rust | Go | Java | Caspien naive | opt safe | opt unsafe | naive / safe / unsafe vs C |
|---|---|---|---|---|---|---|---|---|
| sieve (1e8) | 1.27 | 1.36 | 1.40 | 1.41 | 3.72 | 1.58 | 1.58 | 2.9x / 1.3x / 1.3x |
| strings (2e7) | 0.25 | 0.24 | 0.34 | 0.47 | 6.07 | 1.12 | 1.28 | 24x / 4.5x / 5.1x |
| heap graph (2e6) | 0.29 | 0.28 | 0.49 | 0.66 | 0.80 | 0.41 | 0.46 | 2.7x / 1.4x / 1.6x |
| sorting (2e6) | 1.48 | 1.42 | 1.55 | 1.80 | 4.22 | 2.37 | 3.28 | 2.9x / 1.6x / 2.2x |

Previous table (earlier, faster session): naive 7.24 / 1.55 / 8.23 s on sieve / graph / sorting, safe 3.24 / 0.68 / 3.81, unsafe 2.77 / 0.52 / 2.80. Roughly: the safe variants are now 1.3-1.6x C on sieve, graph and sorting (they were 2.5-3.8x), naive stdlib versions about halved, and the String class is NOT improved (6.5 -> 6.1 s, 24x C): its per-character `appendChar`/`charAt`/`setCharAt` go through calls with struct receivers and `memcopy`, which none of these changes touch. Unsafe is not faster than safe here (sorting unsafe even looks slower, 3.28 vs 2.37; the difference is inside the noise of this VM but is not understood, and the unsafe sorting variant should be looked at next).
Regression check (same run, "full" rows, seconds, outputs identical to before): fannkuch n=11 naive 3.99, optimized 3.89 (C -O2 3.65 in the same run; previous session 3.69 / 3.50 with C 3.38); spectral naive 0.329, optimized 0.247 (C 0.248); n-body f64 natural 0.70, plain 0.65, loop 0.65, arr 0.62, scalars 0.69, f32 plain 0.69, loop 0.64, arr 0.73, scalars 0.67 (previous 0.56-0.63; the machine was about 8% slower, C also). No regression beyond the noise.
Not verified: Windows/Wine/MASM (Linux only, as in earlier steps).


## Dynarray-of-struct field reads fixed; struct-node graph benchmark (1 Oct 2026)

`es[i].field` on a dynarray of structs was a Codegen gap (field reads gave 0 or crashed), now fixed (root `CLAUDE.md`). New Caspien graph variants keep the graph in ONE dynarray of `Node` structs (edges are still indices; recursive structs stay banned). Same run as the table above, fastest of 3, seconds, 2M nodes, output equal to C: C -O2 0.275, Rust 0.308, Go 0.389, Java 0.648, Caspien naive (DynamicArray) 0.651, parallel-array safe 0.430 / unsafe 0.472, **struct nodes safe 0.752 / unsafe 0.688** (about 2.5-2.7x C, 1.6x slower than the parallel arrays: each field read pushes the whole 56-byte element before extracting the field; fusing `LOOKUP_DYN`+`PUSH_FIELDNAME`+`DOT` into one displaced load is the obvious next step). Linux only.


## The remaining benchmark programs (1 Oct 2026)

Asked for: every benchmark program not assessed as "skip" (the six skipped website problems stay skipped). Added binarytrees, mandelbrot, fasta, knucleotide, lru, merkletrees, helloworld, json_serde: C, C++, Rust, Go, Java, Node, LuaJIT (Bun not installed) plus Caspien naive / optimized safe / optimized unsafe (mandelbrot and helloworld have one Caspien variant). Run with `python3 benchmarks/bench_suite.py <name>`; sources in `benchmarks/<name>/reference` and `benchmarks/<name>/caspien`. Every output equals C, under both Caspien configs. Linux, 2-core VM, fastest of 3, seconds, Caspien = everything on, free builds for C/Rust.

| program | C -O2 | Rust | Go | Java | Caspien naive | opt safe | opt unsafe |
|---|---|---|---|---|---|---|---|
| binary trees (depth 17) | 0.52 | 0.77 | 1.42 | 0.44 | 1.89 | 0.80 | 0.85 |
| mandelbrot (3000) | 1.35 | 1.33 | 1.35 | 1.57 | 1.55 | - | - |
| fasta (8e7) | 1.04 | 1.04 | 1.30 | 1.45 | 3.03 | 2.23 | 2.40 |
| k-nucleotide (2e7) | 0.64 | 1.82 | 1.80 | 2.66 | 19.15 | 1.24 | 1.64 |
| LRU (2e7 ops) | 0.66 | 0.53 | 3.32 | 4.09 | 8.15 | 1.88 | 1.91 |
| Merkle tree, real SHA-256 (140000 leaves) | 1.01 | 0.84 | 1.14 | 1.20 | 2.19 | 2.10 | 1.83 |
| JSON serde (4e6) | 0.91 | 0.92 | 1.04 | 1.41 | 3.99 | 2.86 | 2.73 |
| hello world | 0.002 | 0.003 | 0.003 | 0.047 | 0.004 | - | - |

Reading: mandelbrot (1.15x C) is close to C (the old merkletrees figures, 1.4-1.7x C, were for the 64-bit mix64 hash, which no longer exists, so they are removed; see the note below); binarytrees, fasta, lru and json_serde sit at 1.5-3.4x for the best variant, and the real SHA-256 Merkle tree at 1.8x; the naive variants are far behind where the stdlib is on the hot path (k-nucleotide naive uses the fixed-capacity stdlib HashMap: 30x C, down from 62x since `insecure_hashOf` became real FNV-1a (1 Oct 2026 re-run; fewer collisions); lru naive 12x). Caspien's optimized k-nucleotide (hand-written open-addressing table) and lru are faster than Go, Java and Node, and the k-nucleotide one beats Rust and Go with their default maps. hello world measures start-up, compile time and size only.

Deviations to keep in mind: binarytrees, lru and json_serde are index-based in Caspien (recursive structs rejected on purpose); merkletrees was a 64-bit mixing hash (mix64) in the measurements above and is now REAL SHA-256 (FIPS 180-4, hand-written in every language; see `benchmarks/merkletrees/SPEC.md`), possible since `bits_xor` / `bits_and` / `bits_not` / shifts exist; the old Merkle row was removed from the table (a different program: 2e6 leaves of a cheap hash) and its timing is now in the table above (140000 leaves, re-run 1 Oct 2026: C -O2 1.01 s, Caspien unsafe 1.83 s = 1.8x, safe 2.10 s, naive 2.19 s; Caspien optimized safe/unsafe/naive have the same output as C at every N checked, see root CLAUDE.md); Caspien Merkle tree arrays are now u32 (8 words per digest), and lru elements are u64; knucleotide's LCG has period 139,968 so its tables are small; `DynamicArray<S>` for a struct `S` compiles since the fix of 1 Oct 2026 (see the root CLAUDE.md); the naive binarytrees (one `DynamicArray<Node>`) and json_serde (one `DynamicArray<Rec>`) variants were converted to it afterwards, so the naive rows measured above are of the older parallel-`DynamicArray<u64>` programs and have NOT been re-measured; the stdlib HashMap has no remove and a fixed capacity. Linux only.

Re-timed after `DynamicArray<struct>` was fixed: the naive binarytrees and json_serde rows above now use one `DynamicArray<Node>` / `DynamicArray<Rec>` (binarytrees naive 1.12 -> 1.89 s with the struct element, json_serde naive 4.61 -> 3.99 s; safe/unsafe re-measured the same run, differences are run-to-run noise on the shared VM).

## 1 Oct 2026 re-run: 15 more languages, bitwise builtins in every Caspien benchmark, report grouped by metric

Added to every harness (`bench_suite.py`, `bench_program.py`, `nbody/bench.py`) through `benchmarks/newlangs.py`: Fortran (gfortran), C# (.NET 8), Odin, Zig 0.16, Nim, WebAssembly (the C program built with `zig cc -target wasm32-wasi`, run by wasmtime 25), Crystal, Kotlin, Chapel, Codon, D (LDC and GDC), Objective-C (GNU runtime), OCaml, Swift. **Dart is NOT measured**: the sandbox network allowlist blocks every Dart distribution host, so it could not be installed; no row exists for it. 195 reference ports live next to the C sources (`reference/<stem>.<ext>`), each checked equal to C at small N. Caspien benchmark sources now use `bits_and(x, 0xFFFFFFFF)` instead of `% 4294967296`. Every output in the run equals C's; no row failed.
`charts.html` is now metric first (execution time, compile time, peak memory, executable size), each opening with one table of every implementation on every program plus the geometric mean of the ratio to C -O2; every header sorts (click again to reverse, button to reset).
Geomean of execution time vs C -O2 across the programs: Zig 0.95, Fortran 1.03, D (LDC) 1.04, Chapel 1.05, Rust 1.05, Odin 1.10, C++ 1.16, Wasm 1.21, Crystal 1.26, Go 1.38, Swift 1.59, Nim 1.59, **Caspien optimized safe, everything on 1.61**, C# 1.69, Caspien unsafe 1.89, Java 1.90, Kotlin 2.03, Bun 2.34, LuaJIT 2.50, Node 2.76, Caspien naive (stdlib) 3.19; optimisations off: unsafe 4.14, safe 4.93, naive 8.61.
Recursion micro-benchmark (`benchmarks/recursion/`, fastest of 3, same output within each group), chain of 1000 steps x 200000: C -O0 recursive 2.92, C -O1 no-inline recursive 2.21, C -O2 recursive 0.218 (gcc turns the tail call into a loop), C -O2 hand loop 0.214; Caspien `@recursive` (lowered to a bounded loop by the compiler) 2.52 off / 1.12 full before `RangeWordSplitPass`, 0.67 full after it (best of 5); Caspien hand-written loop 0.67 off / 0.28 full. Tree (depth 27): C -O0 recursive 0.79, C -O2 recursive 0.075, C explicit stack 1.04 / 0.33 (-O0 / -O2), Caspien explicit stack 2.53 off / 1.02 full. Reading: lowering recursion to a loop costs nothing in principle (C does the same at -O2); in Caspien the lowered loop is about 4x slower than a hand loop because it rebuilds a range and tests `within` every iteration, which is a compiler optimisation gap and not a cost of the idea.

After `RangeWordSplitPass` (see CLAUDE.md): the lowered `@recursive` chain with everything on takes 0.67 s (was 1.12 s); the hand-written loop is 0.28 s and C -O2 0.22 s. The language still rejects non-tail and mutual recursion, so the tree benchmark above is an explicit-stack program written by hand, not recursion.

## 1 Oct 2026: recursion against loops, nine languages (`benchmarks/recursion/run.py`, results in `recursion/results.json`, section "Recursion" in `charts.html`)

Chain = linear tail recursion, depth 1000 x 200000 repetitions; tree = visit every node of a complete binary tree of depth 27 (non-tail). Fastest of 3, outputs identical within each group (seconds).

| language | chain recursive | chain hand loop | tree recursive |
|---|---|---|---|
| C -O0 | 2.85 | 0.235 | 0.824 (explicit stack 1.061) |
| C -O1 -fno-inline | 2.20 | - | - |
| C -O2 | 0.225 | 0.213 | 0.076 (explicit stack 0.304) |
| C++ -O2 | 0.221 | 0.217 | 0.076 |
| Rust -O | 0.216 | 0.214 | 0.003 (constant-folded, ignore) |
| Go | 1.209 | 0.214 | 0.499 |
| Java (HotSpot) | 1.349 | 0.279 | 0.424 |
| Node.js | 11.617 | 6.617 | 1.223 |
| Bun | 15.243 | 14.973 | 0.887 |
| LuaJIT | 0.327 | 0.323 | 0.780 |
| Caspien `@recursive` (lowered to a loop), off / everything on | 2.665 / 0.706 | 0.628 / 0.285 | explicit stack 2.803 / 1.045 |

Reading: a recursive form never beat a loop. gcc, g++ and rustc turn the tail call into the same loop (tie), LuaJIT has proper tail calls (tie), Go and Java do not eliminate tail calls (about 5x slower than their loops). For non-tail recursion the native call stack beats an emulated explicit stack (4x at C -O2). Caveats: the Node and Bun chain times (6.6-15 s, even for the hand loop) are probably 64-bit BigInt emulation, not recursion cost; the Rust tree row was folded to a constant. Caspien's lowered `@recursive` loop is still about 2.5x a hand loop (range rebuilt and `within` tested every step); that is an optimiser gap, not a cost of the idea. VM noise is about 30%.

## 5 Oct 2026: self-referential `ref` structs, the `ref` benchmark variants and the `ref` HashMap

A struct may now hold plain nullable `ref` members of its own type (`next: ref mut Node`; never `ref some`, `owns`, `raw`, `auto`). Nodes are real heap objects, owned by a dynarray of `Holder{n: owns mut Node}` and linked with `ref v[i].n`; every access goes through a `match Some` alive proof (a ghost-table hash lookup), which is why these variants are slower than index arithmetic. The existing index-based programs are kept; the new ones sit next to them (`graph_ref_{naive,safe,unsafe}`, `binarytrees_ref_*` (generated by `binarytrees/caspien/gen_ref.py`), `lru_ref_*` (`lru/caspien/gen_ref.py`)). `stdlib/ref/hash_map.caspien` is a `ref` version of the stdlib HashMap (separate chaining over `HashMapNode` with a `ref next`, owner slots, 70% load growth, same API); `lru_naive_refstd` and `knucleotide_naive_refstd` are the naive programs with only that import changed ("ref in stdlib"). Unaffected: json_serde and merkletrees (flat data, no node links), tree_stack (implicit tree), the sieve/strings/sorting/fasta/numeric programs. In the `ref unsafe` rows only the work stack / bucket array can be unsafe: an unsafe dynarray of `owns` slots would destruct uninitialised slots on assignment, and `raw` self-links stay rejected. The `ref naive` rows use the stdlib `DynamicArray<QRef>` for the queue / stacks / buckets; the owner pool is always a safe dynarray (an owning struct cannot be a `DynamicArray` element).

Quick run (`run_all.py --caspien-only --runs 5`, best of 5, everything on, same tree; C -O2 from the existing rows):

| program | index naive | index safe | index unsafe | ref naive | ref safe | ref unsafe | naive, ref in stdlib |
|---|---|---|---|---|---|---|---|
| graph 2M nodes (C 0.224 s) | 0.317 | 0.194 | 0.194 | 1.549 | 1.478 | 1.469 | - |
| binarytrees depth 17 (C 0.491 s) | 1.257 | 0.316 | 0.437 | 4.088 | 3.610 | 3.963 | - |
| lru 20M ops (C 0.653 s) | 1.950 | 0.906 | 0.867 | 5.226 | 4.693 | 5.285 | 4.106 |
| knucleotide 20M (C 0.623 s) | 2.469 | 0.847 | 0.791 | - | - | - | 7.391 |

Reading: the `ref` versions cost 5-12x over their index versions (7.6x graph, 11x binarytrees, 5x lru safe) and 7-8x C -O2; peak memory is 1.3-3.7x because every node is a separate registered allocation. The ref-based stdlib HashMap is 1.5x (lru) to 3.0x (knucleotide) slower than the open-addressing one. Outputs are identical to the index versions (`tests/ref_variants_check.sh`, valgrind clean). The paired old-tree/new-tree check of the 14 existing graph/binarytrees/lru/knucleotide variants shows no change beyond noise (0.966-1.037). Not verified: Windows/Wine. Pre-existing and unchanged: `graph_struct_unsafe` (0.5 s) is slower than `graph_struct_safe` (0.26 s) and `binarytrees_unsafe` slower than `binarytrees_safe`; not investigated here.

## 5 Oct register/peephole round (paired quick run, best of 5, FULL config, new/old time; Linux VM, noise ±4%)
nbody_f64 0.96, mandelbrot 0.86, spectralnorm_opt 0.99, fannkuch_opt 0.95, strings 0.94/0.97 (safe/unsafe), graph 0.96/0.98, sorting 0.98/0.92, binarytrees 0.99/1.01, fasta 0.94/0.94, knucleotide 0.93/0.92, lru 1.02/0.95, merkletrees 0.81/0.80, json_serde 0.91/0.92. sieve_safe 1.03 and sieve_unsafe 1.04 are inside its code-placement noise (bimodal). Not run on Windows/Wine.

## 8 Oct 2026: nullable `ref` = 64-bit id (ghost table design E), paired old-vs-new (`benchmarks/gt_id/paired_ref.py`, FULL config, best of 3, alternating runs, Linux VM)
Outputs identical in every case. Programs that never take a nullable `ref` are unchanged: knucleotide naive/safe 1.02/1.01x, lru naive/safe 0.97/0.99x, binarytrees naive/safe 1.00/0.98x, fasta naive 0.99x, merkletrees naive 0.98x (new/old time).
Programs with nullable refs are slower, the price of closing address reuse (ABA): binarytrees ref 2.8-3.3x, graph ref 3.2-3.3x, lru ref 1.7-2.0x, lru naive ref-in-stdlib 1.6x, knucleotide naive ref-in-stdlib 1.25x. Cause: the first `ref x` of each object inserts into two extra hash tables, every `match Some` resolves an id, freeing an object with an id costs 4-5x. A full `run_all.py --mode full` is in the charts; comparing it with the committed numbers is noisy (nbody, which never allocates, shows +10-20% from VM drift), so only the paired ratios count.
