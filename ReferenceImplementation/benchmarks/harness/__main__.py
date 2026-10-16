"""Command line: python3 -m harness {run,check,index} ... (run from the benchmarks folder)."""
import argparse, os, shutil, subprocess, sys, tempfile, time

from . import core, languages, manifest, paths, results, runner

USAGE = """\
python3 -m harness run [PROGRAM ...] [--mode quick|full] [--with-off] [--caspien-only] [--runs N] [--builds N] [--n N]
                       [--only SUBSTRING ...] [--no-build-cache] [--clear-build-cache] [--no-leak-check] [--out FILE]
python3 -m harness check PROGRAM|all LANGUAGE [--n N]     build one language's port, run it, compare with the C -O2 output
python3 -m harness index                                  rewrite site/data/index.json from the manifest and the results present
"""

MODE_HELP = """\
quick (default)  Caspien: only the optimised ("full") builds, measured FIRST. Every other implementation then runs under a time limit equal to
                 the slowest optimised Caspien variant of that program (never below 0.1 s). One whose first run reaches the limit is killed and
                 listed as "would have taken longer than optimized Caspien". 3 runs, 1 build per implementation. The other languages' builds are
                 cached in .build_cache (keyed on the sources, the build command and the compiler binary).
full             Every Caspien variant with optimisations off and on, every language runs to completion, 5 runs, compile time = median of 3
                 builds. Use this for published numbers.
"""


def add_run_args(ap):
    ap.add_argument("programs", nargs="*", help="programs to measure, one after the other (default: all)")
    ap.add_argument("--mode", choices=sorted(core.MODES), default="quick", help="see below")
    ap.add_argument("--with-off", action="store_true", help="quick mode: also build the optimisations-off Caspien variants")
    ap.add_argument("--runs", type=int, help="runs per implementation (default 3 quick, 5 full)")
    ap.add_argument("--builds", type=int, help="builds per implementation for the compile time (default 1 quick, 3 full)")
    ap.add_argument("--n", type=int, help="problem size (default: the manifest's; only with a single program)")
    ap.add_argument("--out", help="result file (default site/data/<program>.json; with --caspien-only <program>.quick.json)")
    ap.add_argument("--only", nargs="*", default=[], help="measure only rows whose label contains one of these")
    ap.add_argument("--caspien-only", action="store_true", help="measure only Caspien and merge with the other languages' rows of the existing file (writes <program>.quick.json)")
    ap.add_argument("--no-build-cache", action="store_true")
    ap.add_argument("--clear-build-cache", action="store_true", help="delete .build_cache first")
    ap.add_argument("--no-leak-check", action="store_true", help="skip the untimed run of every Caspien program under tests/alloc_shim.c")


def cmd_run(a):
    programs = manifest.load()
    wanted = a.programs or [P["id"] for P in programs]
    chosen = [manifest.get(programs, p) for p in wanted]
    if a.n and len(chosen) != 1:
        raise SystemExit("--n needs exactly one program")
    S = core.settings(a)
    if os.path.exists(paths.LEAK_LOG):
        os.remove(paths.LEAK_LOG)
    t0 = time.time()
    for P in chosen:
        t1 = time.time()
        print("=== %s (%s) %s" % (P["id"], a.mode, time.strftime("%H:%M:%S")), flush=True)
        runner.run_program(P, a, S)
        print("--- %s %.0f s" % (P["id"], time.time() - t1), flush=True)
    results.write_index(programs)
    leaks = [l for l in (open(paths.LEAK_LOG).read().split("\n") if os.path.exists(paths.LEAK_LOG) else []) if l]
    print("LEAK CHECK: %s" % ("every Caspien program freed everything it allocated" if not leaks else "%d program(s) left blocks registered at exit:" % len(leaks)))
    for l in leaks:
        print("   " + l)
    print("ALLDONE %s %.0f s" % (a.mode, time.time() - t0))
    return 1 if leaks else 0


def cmd_check(a):
    programs = manifest.load()
    chosen = programs if a.program == "all" else [manifest.get(programs, a.program)]
    if a.language not in languages.NEW:
        raise SystemExit("unknown language %r (known: %s)" % (a.language, ", ".join(languages.NEW)))
    bad = 0
    for P in chosen:
        bad |= check_one(P, a.language, a.n or P.get("small", 1000))
    return 1 if bad else 0


def check_one(P, key, n):
    ref_dir = os.path.join(paths.PROGRAMS, P["id"], "reference")
    W = tempfile.mkdtemp(prefix="newlang_" + key + "_")
    for f in os.listdir(ref_dir):
        shutil.copy(os.path.join(ref_dir, f), W)
    ref_exe = os.path.join(W, "ref_c")
    rc = subprocess.run(["gcc", "-O2", "-o", ref_exe, os.path.join(W, P["stem"] + ".c"), "-lm"], capture_output=True, text=True)
    if rc.returncode:
        print("reference C build failed", rc.stderr[-500:])
        return 2
    want = subprocess.run([ref_exe, str(n)], capture_output=True, text=True).stdout.strip()
    rs = [r for r in languages.rows(P, W, str(n)) if r["key"] == key]
    if not rs:
        print("NO PORT or no toolchain for", P["id"], key)
        return 1
    bad = 0
    for r in rs:
        dt = 0.0
        for bc in r["build"]:
            d1, _, code, o = core.run(bc["cmd"], cwd=bc.get("cwd", W), env=bc.get("env"))
            dt += d1
            if code:
                print("BUILD FAILED", P["id"], r["label"], "\n", o[-1500:])
                bad += 1
                break
        else:
            dt2, kb, code, o = core.run(r["exe"], cwd=W, env=r.get("env"))
            got = o.strip()
            ok = code == 0 and got == want
            print("%-6s %-14s %-12s build %6.2fs run %7.3fs rss %7d KB  %s" % ("OK" if ok else "DIFF", P["id"], r["label"], dt, dt2, kb, "" if ok else "want=%r got=%r" % (want[:80], got[:80])))
            bad += 0 if ok else 1
    shutil.rmtree(W, ignore_errors=True)
    return 1 if bad else 0


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python3 -m harness", description=__doc__, epilog=USAGE + "\nModes:\n" + MODE_HELP, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    add_run_args(sub.add_parser("run", help="measure programs", epilog=MODE_HELP, formatter_class=argparse.RawDescriptionHelpFormatter))
    c = sub.add_parser("check", help="self-test one language's ports")
    c.add_argument("program")
    c.add_argument("language")
    c.add_argument("--n", type=int)
    sub.add_parser("index", help="rewrite site/data/index.json")
    a = ap.parse_args(argv)
    if a.cmd == "run":
        return cmd_run(a)
    if a.cmd == "check":
        return cmd_check(a)
    ids = results.write_index()
    print("index.json:", ", ".join(ids))
    return 0


if __name__ == "__main__":
    sys.exit(main())
