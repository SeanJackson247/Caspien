#!/usr/bin/env python3
"""
Quick leak scan of the Caspien benchmark programs (seconds per program instead of a full benchmark run).

    python3 benchmarks/leak_scan.py                    # every variant of every bench_suite program, N = 2000, everything-on config
    python3 benchmarks/leak_scan.py --n 20000 --only lru knucleotide
    python3 benchmarks/leak_scan.py --config off       # the optimisations-off build

Builds each Caspien variant (benchmarks/<name>/caspien/*.caspien) in a scratch compiler tree, runs it once at a small N under
tests/alloc_shim.c and prints the number of blocks still registered in its ghost table at exit (0 = it freed everything it allocated;
valgrind cannot see these leaks: the table keeps every registered block reachable). Exit code 1 when any variant leaks or fails to
run. The full benchmark runs (run_all.py / bench_suite.py) do the same check once per Caspien program after the timed runs, at their
real N (--no-leak-check skips it). Programs without a ghost table (no owns/new/dyn) show as "no table". nbody, fannkuchredux and
spectralnorm allocate nothing and are not scanned.
"""
import argparse, os, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, ".."))
sys.path.insert(0, os.path.join(HERE, "nbody"))
sys.path.insert(0, HERE)
import bench as B   # noqa: E402
import bench_suite as BS   # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--n", type=int, default=2000)
    ap.add_argument("--config", choices=("full", "off"), default="full")
    ap.add_argument("--only", nargs="*", default=[])
    a = ap.parse_args()
    ct = B.make_caspien_tree(ROOT)
    base = open(os.path.join(ct, "toolchain.config")).read()
    kv = B.FULL if a.config == "full" else B.OFF
    work = tempfile.mkdtemp(prefix="leak_scan_")
    env = {k: v for k, v in os.environ.items() if k != "JAVA_TOOL_OPTIONS"}
    bad = 0; n = 0
    for name, P in sorted(BS.PROGRAMS.items()):
        if a.only and name not in a.only:
            continue
        for src, kind, desc in P["caspien"]:
            n += 1
            text = open(os.path.join(HERE, name, "caspien", src + ".caspien")).read().replace("../../../stdlib/", "stdlib/")
            open(os.path.join(ct, "toolchain.config"), "w").write(base)
            B.patch_config(os.path.join(ct, "toolchain.config"), kv)
            open(os.path.join(ct, "_bp.caspien"), "w").write(text)
            exe = os.path.join(work, src)
            c = subprocess.run(["java", "Compiler", "-i", "_bp.caspien", exe, "--no-cache"], cwd=ct, env=env, capture_output=True, text=True)
            if c.returncode != 0:
                print("%-28s DOES NOT COMPILE: %s" % (src, c.stderr.strip().split("\n")[-1][:100])); bad += 1; continue
            run_env = dict(env); run_env[P["env"]] = str(min(P["n"], a.n))   # N is a depth for binarytrees: never above the program's own default
            leaked = B.gt_leak([exe], work, run_env)
            r = subprocess.run([exe], cwd=work, env=run_env, capture_output=True, text=True)
            status = "no table" if leaked is None else ("clean" if leaked == 0 else "LEAK %d block(s)" % leaked)
            if r.returncode != 0:
                status += "  (exit code %d)" % r.returncode
            print("%-28s %s" % (src, status), flush=True)
            bad += (leaked or 0) > 0 or r.returncode != 0
    shutil.rmtree(ct, ignore_errors=True); shutil.rmtree(work, ignore_errors=True)
    print("%d variants scanned, %s" % (n, "all clean" if not bad else "%d with a leak or a failure" % bad))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
