#!/usr/bin/env python3
"""Compiles every docs/examples/*.caspien that has an `// Expected output:` header (lines `//   text`) and compares the program's output with it.
Runs in a scratch copy of the tree (Linux, java, gcc), once per optimisation preset (shipped, everything on). Skips 07/08/09 (arguments, endless loop)."""
import glob, os, re, subprocess, sys, tempfile
ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
sys.path.insert(0, os.path.join(ROOT, "benchmarks", "nbody"))
import bench as B   # noqa: E402
SKIP = ("07_", "08_", "09_")
W = tempfile.mkdtemp(prefix="examples_check_")
subprocess.check_call("tar -C %s --exclude=.git --exclude=.cache -cf - . | tar -C %s -xf -" % (ROOT, W), shell=True)
os.chdir(W); os.makedirs("output", exist_ok=True)
cfg = open("toolchain.config").read()
cfg = re.sub(r"^target windows_gnu", "target linux", cfg, flags=re.M)
cfg = re.sub(r"^(\s*)default: win64", r"\1default: sysv_x64", cfg, flags=re.M)
open("tc.shipped", "w").write(cfg)
open("tc.allon", "w").write(cfg); B.patch_config("tc.allon", B.FULL)
bad = 0; n = 0
for f in sorted(glob.glob("docs/examples/*.caspien")):
    if os.path.basename(f).startswith(SKIP):
        continue
    lines = open(f).read().split("\n")
    exp = []; on = False
    for l in lines:
        if l.startswith("// Expected output:"): on = True; continue
        if on:
            m = re.match(r"//   ?(.*)$", l)
            if not m: break
            exp.append(m.group(1))
    if not exp:
        continue
    for preset in ("shipped", "allon"):
        open("toolchain.config", "w").write(open("tc." + preset).read())
        r = subprocess.run(["java", "-cp", ".", "Compiler", "-i", f, "output/ex", "--no-cache"], capture_output=True, text=True)
        n += 1
        if r.returncode != 0:
            bad += 1; print("FAIL (compile)", f, preset, r.stderr.strip().split("\n")[-1][:150]); continue
        out = subprocess.run(["./output/ex"], capture_output=True, text=True, timeout=60).stdout.rstrip("\n").split("\n")
        if [x.rstrip() for x in out] != [x.rstrip() for x in exp]:
            bad += 1; print("FAIL (output)", f, preset); print("  expected:", exp); print("  got:     ", out)
print("%s examples_expected_check: %d runs, %d failures" % ("PASS" if bad == 0 and n else "FAIL", n, bad))
sys.exit(1 if bad or not n else 0)
