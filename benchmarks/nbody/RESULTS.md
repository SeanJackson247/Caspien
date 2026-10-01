# n-body benchmark results

## Latest: cross-language run with optimisations off and on (N = 5,000,000 steps)

Produced by `python3 benchmarks/nbody/bench.py` (numbers in `results.json`) and drawn by `python3 benchmarks/nbody/charts.py` (`charts.html`:
execution time, peak memory, compile time, executable size; green = Caspien, red = bare metal, purple = Java, blue = JavaScript).
2-core Linux VM. Time = fastest of 5 runs, memory = median peak RSS of 5 runs, compile time = median of 3 builds (whole toolchain incl. assembler and
linker for Caspien), size = the executable (Java, Node, Bun: the runtime plus the program). PHP, Ruby and Python were left out of this run.
Every program printed the energies of the C program of the same precision (f64: identical to 9 digits; f32: within 5e-4).
"Off" = every optimizer switch and register switch off. "Full" = `loop-unrolling: aggressive`, `function-inlining: aggressive`, constant folding,
variable elision/shifting, struct unpacking, dead control flow / function / declaration removal, register variables and float temporaries all on.

| Program | Time | Memory | Compile | Size |
|---|---|---|---|---|
| Rust -O | 0.23 s | 2.1 MB | 0.25 s | 4.4 MB |
| C -O2 | 0.41 s | 1.6 MB | 0.12 s | 16 KB |
| C++ -O2 | 0.41 s | 1.6 MB | 0.30 s | 16 KB |
| Go | 0.57 s | 1.8 MB | 0.10 s | 2.2 MB |
| Java | 0.67 s | 41.7 MB | 1.12 s | 203 MB (JDK) |
| Node | 0.87 s | 49.8 MB | none | 119 MB |
| Bun | 0.93 s | 39.9 MB | none | 97 MB |
| C -O0 | 1.87 s | 2.0 MB | 0.10 s | 16 KB |
| Caspien arr f64, full / off | 1.08 s / 5.03 s | 1.9 MB | 2.2 s / 1.8 s | 40 KB / 44 KB |
| Caspien natural f64, full / off | 1.71 s / 7.14 s | 2.0 MB | 2.0 s / 1.3 s | 37 KB / 21 KB |
| Caspien plain f64, full / off | 2.01 s / 6.59 s | 2.0 MB | 1.9 s / 1.2 s | 33 KB / 21 KB |
| Caspien scalars f64, full / off | 2.54 s / 3.58 s | 2.0 MB | 2.0 s / 1.8 s | 45 KB / 38 KB |
| Caspien loop f64, full / off | 3.91 s / 10.34 s | 2.0 MB | 2.3 s / 1.4 s | 67 KB / 26 KB |

(`natural f64` and `scalars f64` were re-measured after the strength-reduction / branch-fusion / third-register work; the `arr`, `plain` and `loop` rows and the f32 variants are from before it. The f32 variants behave the same; all 18 Caspien builds are in `results.json` and the chart.)

`caspien/nbody_natural_f64.caspien` is the n-body written the natural way: small one-job functions (`length`, `speedSquared`, `kinetic`,
`potential`, `attract`, `drift`), plain `for` loops, nothing unrolled or inlined by hand. With the optimizer off it takes 7.5 s; with unrolling and
inlining on it takes 2.0 s (3.8x), close to the hand-unrolled scalar version and behind the hand-unrolled array version (1.1 s).
Caspien is still about 2.6x (arr) to 4.8x (natural) slower than gcc -O2 and about 1.2-2x faster than gcc -O0.
What remains: no common-subexpression elimination or loop-invariant hoisting, no `sqrtsd` intrinsic (`sqrt` is a libm call), array indices and the hidden `for`
variables live in memory, two integer variable registers per function.

# Older results (N = 1,000,000, single run each, earlier compiler builds)

Program: n-body from the Computer Language Benchmarks Game (reference C program nbody-gcc-1). 5 bodies, 1,000,000 steps, dt = 0.01.
Expected output for the double-precision versions: `-0.169075164` then `-0.169086185`.
Machine: 2-core Linux VM (single run each, not best-of-three). Run `benchmarks/nbody/run.sh [N]` to repeat on your machine.

The benchmarks-game site was not reachable from the sandbox, so the reference programs in `reference/` are hand-written ports of the same algorithm
(not the site's own sources). "Slowest C" here means my own port built with `gcc -O0`.

## Caspien files (`caspien/`)
Caspien has no f64, so all four use f32 (`sqrtf`) and end at slightly different energies from the double versions. N comes from env var `NBODY_N`.
- `nbody.caspien`       35 scalar `let static` f32 variables, everything written out.
- `nbody_arr.caspien`   seven `f32[5]` static arrays, literal indices, everything written out.
- `nbody_plain.caspien` seven static arrays, loops with `assume match i in/into <array>` proofs.
- `nbody_loop.caspien`  same with nested `match` proof blocks.

## Results, phase 1 (deferred operands: 8-byte integer temporaries in registers)
| Program | Time | Final energy |
|---|---|---|
| Rust | 0.05 s | -0.169086185 |
| C f32 -O2 | 0.06 s | -0.169054747 |
| C -O2 | 0.07 s | -0.169086185 |
| C -O3 native fast-math | 0.07 s | -0.169086185 |
| C++ -O2 | 0.07 s | -0.169086185 |
| Go | 0.10 s | -0.169086185 |
| Java | 0.15 s | -0.169086185 |
| Bun | 0.16 s | -0.169086185 |
| Node | 0.18 s | -0.169086185 |
| C f32 -O0 | 0.29 s | -0.169054747 |
| C -O0 | 0.31 s | -0.169086185 |
| Caspien nbody, pass off / on | 0.63 / 0.60 s | -0.169092044 |
| Caspien nbody_arr, pass off / on | 0.91 / 0.94 s | -0.169092044 |
| Caspien nbody_plain, pass off / on | 1.37 / 1.21 s | -0.169092014 |
| Caspien nbody_loop, pass off / on | 1.81 / 1.53 s | -0.169092014 |
| PHP | 3.82 s | -0.169086185 |
| Python | 6.47 s | -0.169086185 |
| Ruby | 9.05 s | -0.169086185 |

Note (found, not investigated): the variable-index Caspien programs end at `-0.169092014`, the literal-index ones at `-0.169092044`, with the pass on or off.

## Results, stage 2 (adds f32, narrow-integer and pointer operands, computed-address stores, ARG/RET fast paths, `x += c`)
Best of 3, N = 1,000,000, same machine. Pass off / phase 1 on / stage 2 on. Final energies unchanged by the pass (identical with it off).
| Program | off | phase 1 | stage 2 |
|---|---|---|---|
| Caspien nbody | 0.56 s | 0.60 s | 0.44 s |
| Caspien nbody_arr | 0.87 s | 0.94 s | 0.26 s |
| Caspien nbody_plain | 1.21 s | 1.21 s | 0.50 s |
| Caspien nbody_loop | 1.70 s | 1.53 s | 0.72 s |

For scale: C -O0 0.28-0.31 s, C -O2 0.06-0.07 s. Stage 2 leaves Caspien 1.5-2.5x slower than gcc -O0 on these. What remains is stack-based calls (`sqrtf`, `advance`), every variable living in memory, and no instruction scheduling. Variables in registers was built afterwards (next section).
`run.sh` (tested) builds Caspien in a scratch copy with `target linux` when run on Linux, so the shipped `windows_gnu` config is not touched.

## Results, register variables (`variables-in-registers: on`, on top of stage 2)
Best of 3, N = 1,000,000, same machine, stage 2 on in both columns. Final energies identical on and off.
| Program | variables off | variables on |
|---|---|---|
| Caspien nbody | 0.336 s | 0.324 s |
| Caspien nbody_arr | 0.237 s | 0.231 s |
| Caspien nbody_plain | 0.524 s | 0.530 s |
| Caspien nbody_loop | 0.775 s | 0.689 s |

Small gain: these programs are dominated by f32 work and calls, and only integer loop counters/accumulators (two per function at most) are promoted. Absolute times differ from the stage 2 table (different load on the shared VM); compare within this table only.

## Results, float register variables (`float-variables-in-registers`, on top of register variables)
Linux, 2-core VM, N = 1e6, best of 5, float switch off -> on. Outputs identical.

| Program | off | on |
|---|---|---|
| Caspien nbody | 0.331 s | 0.334 s |
| Caspien nbody_arr | 0.225 s | 0.230 s |
| Caspien nbody_plain | 0.500 s | 0.449 s |
| Caspien nbody_loop | 0.667 s | 0.612 s |

Small: float temporaries still round-trip through general registers, array indices and hidden `for` variables stay in memory, and SysV
spills the variables around every call. An xmm temporary allocator would be the next step. Not measured under Wine.

## Results, float temporaries (`float-temporaries-in-registers`) and float variables
Linux, 2-core VM, N = 1e6, best of 5. Columns: float variables / float temporaries. Final energies identical in every run.

| Program | off / off | off / on | on / off | on / on |
|---|---|---|---|---|
| Caspien nbody | 0.325 s | 0.278 s | 0.334 s | 0.286 s |
| Caspien nbody_arr | 0.230 s | 0.131 s | 0.232 s | 0.141 s |
| Caspien nbody_plain | 0.528 s | 0.378 s | 0.447 s | 0.337 s |
| Caspien nbody_loop | 0.647 s | 0.622 s | 0.648 s | 0.569 s |

Float temporaries are the bigger win: a float operation no longer round-trips through general registers. Float variables on their own add
little (the variable is in a register but each use still copied it out) and help mainly together with temporaries in the loop programs.



## Results, N = 50,000,000 steps (execution time, peak memory, compile time)
Linux, 2-core VM, all programs run one at a time. Execution time = best of 3 (PHP, Ruby, Python: a single run, they take minutes). Memory = peak
resident set (VmHWM) of the running process. Compile time = best of 3 of the full compile command: `gcc`/`g++`/`rustc`/`go build`/`javac` for
the others (`go build` was cache-hot); for Caspien it is the whole pipeline through `java Compiler` (four JVM stages plus the `gcc` link),
which is mostly JVM start-up. Node, Bun, PHP, Ruby and Python have no compile step. "float regs on" = `float-variables-in-registers: on` plus
`float-temporaries-in-registers: on`; "off" = the shipped defaults. The last column is the final energy each program printed (the Caspien and
C f32 programs use 32-bit floats, everything else 64-bit doubles, so the two groups agree only in the leading digits).

| Program | Time | Peak memory | Compile | Final energy |
|---|---|---|---|---|
| Rust -O | 2.28 s | 2.1 MB | 0.18 s | -0.169059907 |
| C f32 -O2 | 2.57 s | 1.7 MB | 0.08 s | -0.168870047 |
| C -O2 (double) | 2.84 s | 1.7 MB | 0.11 s | -0.169059907 |
| C++ -O2 | 2.89 s | 1.7 MB | 0.20 s | -0.169059907 |
| Java | 3.58 s | 41.9 MB | 0.67 s | -0.169059907 |
| Go | 4.91 s | 1.9 MB | 0.04 s | -0.169059907 |
| Node | 5.28 s | 53.2 MB | - | -0.169059907 |
| Bun | 6.60 s | 43.3 MB | - | -0.169059907 |
| Caspien nbody_arr (float regs on) | 6.85 s | 2.0 MB | 1.16 s | -0.168877661 |
| Caspien nbody_arr (float regs off) | 11.63 s | 2.1 MB | 1.24 s | -0.168877661 |
| C f32 -O0 | 13.89 s | 2.0 MB | 0.07 s | -0.168870047 |
| Caspien nbody (float regs on) | 14.58 s | 2.0 MB | 1.10 s | -0.168877661 |
| C -O0 (double) | 14.64 s | 2.0 MB | 0.06 s | -0.169059907 |
| Caspien nbody_plain (float regs on) | 16.92 s | 2.0 MB | 0.85 s | -0.168877676 |
| Caspien nbody (float regs off) | 17.45 s | 2.1 MB | 1.13 s | -0.168877661 |
| Caspien nbody_plain (float regs off) | 25.94 s | 2.1 MB | 0.83 s | -0.168877676 |
| Caspien nbody_loop (float regs on) | 29.72 s | 2.1 MB | 0.97 s | -0.168877676 |
| Caspien nbody_loop (float regs off) | 34.87 s | 2.0 MB | 0.87 s | -0.168877676 |
| PHP | 155.12 s | 36.3 MB | - | -0.169059907 |
| Python | 306.21 s | 8.6 MB | - | -0.169059907 |
| Ruby | 445.38 s | 19.3 MB | - | -0.169059907 |

Notes. At 1e6 steps Java measured 0.15 s and Caspien nbody_arr 0.13 s, but Java's figure is mostly JVM start-up and warm-up: at 5e7 steps Java
(3.6 s) is about twice as fast as Caspien nbody_arr with float registers on (6.9 s). Caspien binaries use about 2 MB, like the C ones.

## f64 (double precision) variants

Caspien now has `f64`. The four programs were ported to it (`benchmarks/nbody/caspien/*_f64.caspien`, same layouts as the f32 ones). At 5e7 steps
(best of 3, Linux, 2-core VM) all four print `-0.169075164` then `-0.169059907`, identical to the C double reference (the f32 programs print `-0.1688777`).

| Program | f64, float regs on | f64, float regs off | f32, regs on (above) |
|---|---|---|---|
| nbody_arr_f64 | 8.20 s | 11.63 s | 6.85 s |
| nbody_f64 | 15.58 s | 17.51 s | 14.58 s |
| nbody_plain_f64 | 20.50 s | 26.10 s | 16.92 s |
| nbody_loop_f64 | 30.40 s | 35.29 s | 29.72 s |
| C -O2 (double) | 2.74 s | | |

f64 does not make anything faster here: it costs 2-20% more than f32 (divide and sqrt are slower in double, and doubles move 8 bytes per access). What it buys is
precision and agreement with the reference output. The gap to C is code quality (no array-index hoisting, only two integer variable registers, calls stay on the stack), not the float type.
