#!/usr/bin/env python3
"""Soundness sweep of the `--audit` peak live heap figure: every tests/*.caspien and docs/examples program that compiles and exits cleanly is run under
tests/alloc_shim.c (the real peak of live heap bytes) and its audited `main` figure must not be below it. Programs whose figure is unbounded, that only
end through an event loop, or that start other processes are skipped. Prints one summary line; lists the programs where the audit was exact.
Uses the persistent scratch tree of tests/run_tests.py (.cache/scratch, stage cache). Takes about 10 minutes."""
import glob, os, re, subprocess, sys
ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
sys.path.insert(0, os.path.join(ROOT, "tests"))
import run_tests as R   # noqa: E402

SKIP_EXTRA = re.compile(r"^(stdlib_test|20_stdlib_tour|22_processes_threads_sleep|par_.*|async_.*|08_main_safe_args|07_main_c_args)$")
R.sync()
P = R.presets()
open(os.path.join(R.SCRATCH, "toolchain.config"), "w").write(P["shipped"])
shim = R.build_shim()
files = sorted(glob.glob(os.path.join(R.SCRATCH, "tests", "*.caspien")) + glob.glob(os.path.join(R.SCRATCH, "docs", "examples", "*.caspien")))
bad, checked, exact, skipped = [], 0, 0, 0
for f in files:
    name = os.path.basename(f)[:-len(".caspien")]
    if R.SKIP.match(name) or SKIP_EXTRA.match(name) or re.search(r"_error(_test)?$", name):
        continue
    if re.search(r"\b(malloc|calloc|realloc)\(", open(f, errors="replace").read()):
        skipped += 1      # calls libc's allocator directly through an extern: the audit models `new`/`dyn`/`resize`/`clone` only
        continue
    rel = os.path.relpath(f, R.SCRATCH)
    exe = os.path.join(R.SCRATCH, "output", "t_" + name)
    if os.path.exists(exe):
        os.remove(exe)
    c = subprocess.run(["java", "-cp", ".", "Compiler", "-i", rel, exe], cwd=R.SCRATCH, env=R.ENV, capture_output=True, text=True, timeout=600)
    if c.returncode != 0:
        continue
    a = subprocess.run(["java", "-cp", ".", "Compiler", "-i", rel, "--audit"], cwd=R.SCRATCH, env=R.ENV, capture_output=True, text=True, timeout=600)
    m = re.search(r"# summary: \S+ has at most (\d+) bytes of heap live at once\n", a.stdout)
    if not m:
        skipped += 1
        continue
    bound = int(m.group(1))
    env = dict(R.ENV, LD_PRELOAD=shim)
    g = R.gt_address(exe)
    if g:
        env["GT_ADDR"] = g; env["GT_EXE"] = exe
    try:
        r = subprocess.run([exe], cwd=R.SCRATCH, env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=60, errors="replace")
    except subprocess.TimeoutExpired:
        skipped += 1
        continue
    pk = re.search(r"PEAK=(\d+)", r.stderr)
    if r.returncode != 0 or not pk:
        skipped += 1
        continue
    peak = int(pk.group(1))
    checked += 1
    if peak > bound:
        bad.append((name, peak, bound))
    elif peak == bound:
        exact += 1
for n, peak, bound in bad:
    print("FAIL %s: measured peak %d > audited bound %d" % (n, peak, bound))
print("%s heap_live_sweep_check: %d programs run, %d skipped (unbounded or not runnable), %d with the audit exact, %d violations" % ("PASS" if not bad else "FAIL", checked, skipped, exact, len(bad)))
sys.exit(1 if bad else 0)
