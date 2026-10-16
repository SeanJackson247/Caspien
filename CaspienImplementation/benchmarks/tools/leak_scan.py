#!/usr/bin/env python3
"""Quick leak scan: builds every Caspien variant, runs it once at a small N under tests/alloc_shim.c and prints the blocks still registered in its
ghost table at exit (0 = freed everything; valgrind cannot see these leaks). Exit 1 when any variant leaks or fails.
    python3 tools/leak_scan.py [--n 2000] [--config full|off] [--only lru knucleotide]"""
import argparse, os, shutil, subprocess, sys, tempfile
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from harness import caspien, core, manifest, paths

ap = argparse.ArgumentParser()
ap.add_argument("--n", type=int, default=2000)
ap.add_argument("--config", choices=("full", "off"), default="full")
ap.add_argument("--only", nargs="*", default=[])
a = ap.parse_args()
tree, work, bad, n = caspien.Tree(), tempfile.mkdtemp(prefix="leak_scan_"), 0, 0
env = {k: v for k, v in os.environ.items() if k != "JAVA_TOOL_OPTIONS"}
try:
    for P in manifest.load():
        if (a.only and P["id"] not in a.only) or not P.get("env"):
            continue
        for src in sorted({v["src"] for v in P["caspien"]}):
            n += 1
            rel = tree.prepare(os.path.join(paths.PROGRAMS, P["id"], "caspien", src + ".caspien"), caspien.FULL if a.config == "full" else caspien.OFF)
            exe = os.path.join(work, src)
            c = subprocess.run(tree.compile_command(rel, exe)["cmd"], cwd=tree.dir, env=env, capture_output=True, text=True)
            if c.returncode:
                print("%-28s DOES NOT COMPILE: %s" % (src, c.stderr.strip().split("\n")[-1][:100]))
                bad += 1
                continue
            run_env = dict(env, **{P["env"]: str(min(P["n"], a.n))})   # N is a depth for binarytrees: never above the program's own default
            leaked = core.ghost_table_leaks([exe], work, run_env)
            r = subprocess.run([exe], cwd=work, env=run_env, capture_output=True, text=True)
            status = "no table" if leaked is None else ("clean" if leaked == 0 else "LEAK %d block(s)" % leaked)
            if r.returncode:
                status += "  (exit code %d)" % r.returncode
            print("%-28s %s" % (src, status), flush=True)
            bad += (leaked or 0) > 0 or r.returncode != 0
finally:
    tree.close()
    shutil.rmtree(work, ignore_errors=True)
print("%d variants scanned, %s" % (n, "all clean" if not bad else "%d with a leak or a failure" % bad))
sys.exit(1 if bad else 0)
