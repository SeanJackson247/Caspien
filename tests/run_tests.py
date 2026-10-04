#!/usr/bin/env python3
"""
Runs every tests/*.caspien and docs/examples/*.caspien program under one or more optimisation presets, skipping what has not changed.

    python3 tests/run_tests.py                         # presets shipped + allon
    python3 tests/run_tests.py --configs shipped,allon,aggressive,alloff
    python3 tests/run_tests.py --only stdlib 13_       # programs whose name contains one of these
    python3 tests/run_tests.py --rerun                 # ignore the cached verdicts (compilation still uses the stage cache)
    python3 tests/run_tests.py --checks                # also run the tests/*_check.sh and *_check.py scripts (never cached)

How it skips: programs are compiled in a persistent scratch copy of the tree (.cache/scratch, Linux-configured) through the compiler's own
stage cache (.cache/stages), so an unchanged program with an unchanged compiler and config is not recompiled. The verdict of a run is stored
under the SHA-256 of the produced binary (.cache/tests): a binary byte-identical to one already tested is not run again, whatever changed
around it. Change the compiler and every binary that changes is re-run; one that comes out identical is not.

Verdicts: a program passes when it compiles, exits 0 and prints no line starting with FAIL (and, when its header has `// Expected output:`,
prints exactly that). `*_error_test` / `*_error` programs pass when they do NOT compile and the compiler reports an error rather than crashing.
Skipped (they need a harness of their own, see the matching *_check.sh): fs_test, fs_policy_*, 09_event_loop, non_exhaustive_*.
Programs are compiled one after the other: a compiler tree has shared scratch files. Linux, java, gcc.
"""
import argparse, glob, hashlib, json, os, re, shutil, subprocess, sys, time

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
sys.path.insert(0, os.path.join(ROOT, "benchmarks", "nbody"))
import bench as B   # noqa: E402  (OFF / FULL switch sets, patch_config)

CACHE = os.path.join(ROOT, ".cache")
SCRATCH = os.path.join(CACHE, "scratch")
VERDICTS = os.path.join(CACHE, "tests")
COPY = ["ASTGenerator", "Optimizer", "LowerOrderGenerator", "Codegen", "stdlib", "tests", "docs/examples", "docs/c_interop"]
SKIP = re.compile(r"^(fs_test|fs_policy_.*|09_event_loop|non_exhaustive_.*)$")
ENV = dict(os.environ, JAVA_TOOL_OPTIONS="", CASPIEN_CACHE=os.path.join(CACHE, "stages"))
ENV.pop("JAVA_TOOL_OPTIONS")


def sync():
    """Refreshes the scratch tree from the repo (content only; stable paths keep the stage cache valid)."""
    os.makedirs(SCRATCH, exist_ok=True)
    for d in COPY:
        src = os.path.join(ROOT, d)
        if os.path.isdir(src):
            shutil.copytree(src, os.path.join(SCRATCH, d), dirs_exist_ok=True, ignore=shutil.ignore_patterns("output", "*.log"))
    for f in os.listdir(ROOT):
        if f.startswith("Compiler") or f in ("toolchain.config",):
            shutil.copy(os.path.join(ROOT, f), SCRATCH)
    os.makedirs(os.path.join(SCRATCH, "output"), exist_ok=True)


def presets():
    base = open(os.path.join(ROOT, "toolchain.config")).read()
    base = re.sub(r"^target windows_gnu", "target linux", base, flags=re.M)
    base = re.sub(r"^(\s*)default: win64", r"\1default: sysv_x64", base, flags=re.M)
    def make(kv):
        path = os.path.join(SCRATCH, "_preset.config")
        open(path, "w").write(base)
        if kv:
            B.patch_config(path, kv)
        return open(path).read()
    allon = dict(B.FULL, **{"function-inlining": "balanced"})
    return {"shipped": base, "allon": make(allon), "aggressive": make(B.FULL), "alloff": make(B.OFF)}


def expected_output(path):
    exp, on = [], False
    for l in open(path).read().split("\n"):
        if l.startswith("// Expected output:"):
            on = True; continue
        if on:
            m = re.match(r"//   ?(.*)$", l)
            if not m:
                break
            exp.append(m.group(1).rstrip())
    return exp or None


def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--configs", default="shipped,allon")
    ap.add_argument("--only", nargs="*", default=[])
    ap.add_argument("--rerun", action="store_true")
    ap.add_argument("--checks", action="store_true")
    ap.add_argument("--clear", action="store_true", help="delete .cache (stages, verdicts, scratch tree) first")
    a = ap.parse_args()
    if a.clear:
        shutil.rmtree(CACHE, ignore_errors=True)
    # the repo's own compiler classes must be current
    sync()
    P = presets()
    os.makedirs(VERDICTS, exist_ok=True)
    files = sorted(glob.glob(os.path.join(SCRATCH, "tests", "*.caspien")) + glob.glob(os.path.join(SCRATCH, "docs", "examples", "*.caspien")))
    t0 = time.time(); total_fail = 0
    for cfg in a.configs.split(","):
        if cfg not in P:
            sys.exit("unknown preset %s (known: %s)" % (cfg, ", ".join(P)))
        open(os.path.join(SCRATCH, "toolchain.config"), "w").write(P[cfg])
        n = ok = reused = skipped = 0; failed = []; stage_hits = 0
        for f in files:
            name = os.path.basename(f)[:-len(".caspien")]
            if SKIP.match(name) or (a.only and not any(s in name for s in a.only)):
                skipped += 1; continue
            n += 1
            rel = os.path.relpath(f, SCRATCH)
            exe = os.path.join(SCRATCH, "output", "t_" + name)
            if os.path.exists(exe):
                os.remove(exe)
            c = subprocess.run(["java", "-cp", ".", "Compiler", "-i", rel, exe, "--cache-report"], cwd=SCRATCH, env=ENV, capture_output=True, text=True, timeout=600)
            if re.search(r"_error(_test)?$", name):
                if c.returncode != 0 and "[error]" in c.stderr and "Exception in thread" not in c.stderr:
                    ok += 1
                else:
                    failed.append((name, "an error test compiled" if c.returncode == 0 else "the compiler crashed: " + c.stderr.strip().split("\n")[-1][:120]))
                continue
            if c.returncode != 0:
                failed.append((name, "does not compile: " + [l for l in c.stderr.split("\n") if "error" in l][-1:][0].strip()[:140] if "error" in c.stderr else "does not compile"))
                continue
            stage_hits += "s5: hit" in c.stderr
            key = sha(exe) + ("-x" if expected_output(f) else "")
            if expected_output(f):
                key = hashlib.sha256((key + "|" + "\n".join(expected_output(f))).encode()).hexdigest()
            vp = os.path.join(VERDICTS, key + ".json")
            if not a.rerun and os.path.exists(vp):
                v = json.load(open(vp)); reused += 1
            else:
                try:
                    r = subprocess.run([exe], cwd=SCRATCH, env=ENV, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=60, errors="replace")
                    lines = r.stdout.rstrip("\n").split("\n")
                    why = ""
                    if r.returncode != 0:
                        why = "exit code %d" % r.returncode
                    elif any(l.startswith("FAIL") for l in lines):
                        why = [l for l in lines if l.startswith("FAIL")][0][:140]
                    elif expected_output(f) and [l.rstrip() for l in lines] != expected_output(f):
                        why = "output differs from the `Expected output:` header"
                    v = {"pass": not why, "why": why}
                except subprocess.TimeoutExpired:
                    v = {"pass": False, "why": "timeout (60 s)"}
                json.dump(v, open(vp, "w"))
            if v["pass"]:
                ok += 1
            else:
                failed.append((name, v["why"]))
        print("%-10s %3d programs: %3d pass, %d fail | %d verdicts reused (binary unchanged), %d compiles from the stage cache | %d skipped" % (cfg, n, ok, len(failed), reused, stage_hits, skipped), flush=True)
        for name, why in failed:
            print("   FAIL %s: %s" % (name, why))
        total_fail += len(failed)
    if a.checks:
        for s in sorted(glob.glob(os.path.join(ROOT, "tests", "*_check.sh")) + glob.glob(os.path.join(ROOT, "tests", "*_check.py"))):
            cmd = ["bash", s] if s.endswith(".sh") else [sys.executable, s]
            r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
            line = (r.stdout.strip().split("\n") or [""])[-1]
            print("%s %s: %s" % ("ok  " if r.returncode == 0 else "FAIL", os.path.basename(s), line[:120]))
            total_fail += r.returncode != 0
    print("done in %.0f s, %s" % (time.time() - t0, "all passed" if not total_fail else "%d FAILED" % total_fail))
    sys.exit(1 if total_fail else 0)


if __name__ == "__main__":
    main()
