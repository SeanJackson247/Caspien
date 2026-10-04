#!/usr/bin/env python3
"""
Run every cross-language benchmark program, one after the other (never two at once: they share a machine and a scratch Caspien tree).

    python3 benchmarks/run_all.py                    # quick (default)
    python3 benchmarks/run_all.py --mode full        # the full thing
    python3 benchmarks/run_all.py --only sieve lru   # a subset
    python3 benchmarks/run_all.py --with-off         # quick, plus the optimisations-off Caspien builds
    python3 benchmarks/run_all.py --charts           # regenerate benchmarks/charts.html afterwards

quick (default)  Caspien: only the optimised ("full") builds, measured FIRST. Every other implementation then runs under a time limit
                 equal to the slowest optimised Caspien variant of that program (same precision, never below 0.1 s). One whose first run
                 reaches the limit is killed and listed as "would have taken longer than optimized Caspien" (a full-colour bar at the limit).
                 3 runs, 1 build per implementation. The other languages' builds are cached in benchmarks/.build_cache (keyed on the program's
                 sources, the build command and the compiler binary) and reused; the stored compile time is reported again and marked in the
                 row's note. --no-build-cache rebuilds them, --clear-build-cache empties the cache first.
full             Every Caspien variant with optimisations off and on, every language runs to completion, 5 runs, compile time = median of
                 3 builds. Results are comparable to the committed ones. Use this for published numbers and the README.
Full mode never uses the build cache. Other flags are passed to every program (--runs N, --builds N).
"""
import argparse, os, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
SUITE = ["sieve", "strings", "graph", "sorting", "binarytrees", "mandelbrot", "fasta", "knucleotide", "lru", "merkletrees", "helloworld", "json_serde"]
ALL = ["nbody", "fannkuchredux", "spectralnorm"] + SUITE


def command(name, extra):
    if name == "nbody":
        return [sys.executable, os.path.join(HERE, "nbody", "bench.py")] + extra
    script = "bench_program.py" if name in ("fannkuchredux", "spectralnorm") else "bench_suite.py"
    return [sys.executable, os.path.join(HERE, script), name] + extra


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mode", choices=("quick", "full"), default="quick")
    ap.add_argument("--with-off", action="store_true")
    ap.add_argument("--no-build-cache", action="store_true")
    ap.add_argument("--clear-build-cache", action="store_true", help="delete the cached builds once, before the first program")
    ap.add_argument("--runs", type=int)
    ap.add_argument("--builds", type=int)
    ap.add_argument("--only", nargs="*", default=[])
    ap.add_argument("--charts", action="store_true")
    a = ap.parse_args()
    extra = ["--mode", a.mode] + (["--with-off"] if a.with_off else []) + (["--no-build-cache"] if a.no_build_cache else []) + (["--runs", str(a.runs)] if a.runs else []) + (["--builds", str(a.builds)] if a.builds else [])
    progs = [p for p in ALL if not a.only or p in a.only]
    bad = [p for p in a.only if p not in ALL]
    if bad:
        sys.exit("unknown program(s): %s (known: %s)" % (", ".join(bad), ", ".join(ALL)))
    if a.clear_build_cache:
        import shutil
        shutil.rmtree(os.path.join(HERE, ".build_cache"), ignore_errors=True)
    t0 = time.time()
    for p in progs:
        t1 = time.time()
        print("=== %s (%s) %s" % (p, a.mode, time.strftime("%H:%M:%S")), flush=True)
        rc = subprocess.call(command(p, extra))
        print("--- %s rc=%d %.0f s" % (p, rc, time.time() - t1), flush=True)
    if a.charts:
        subprocess.call([sys.executable, os.path.join(HERE, "charts_all.py")])
    print("ALLDONE %s %.0f s" % (a.mode, time.time() - t0))


if __name__ == "__main__":
    main()
