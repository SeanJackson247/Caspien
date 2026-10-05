#!/usr/bin/env python3
"""Build cache config views (CompilerCache.OPT_KEYS / REG_KEYS): stage 1 must ignore the optimiser and register keys, stage 2 the register keys,
stage 3 the optimiser keys. Each comparison runs the stage on identical input under two configs and requires byte-identical output.
Runs in a scratch copy of the tree (Linux, java, gcc); about 4 minutes. Prints one summary line."""
import glob, os, re, shutil, subprocess, sys, tempfile
ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
sys.path.insert(0, os.path.join(ROOT, "benchmarks", "nbody"))
import bench as B   # noqa: E402  (FULL = every optimiser and register switch on; patch_config)
W = tempfile.mkdtemp(prefix="cache_views_")
try:
    subprocess.check_call("tar -C %s --exclude=.git --exclude=.cache -cf - . | tar -C %s -xf -" % (ROOT, W), shell=True)
    os.chdir(W)
    os.makedirs("output", exist_ok=True)
    cfg = open("toolchain.config").read()
    cfg = re.sub(r"^target windows_gnu", "target linux", cfg, flags=re.M)
    cfg = re.sub(r"^(\s*)default: win64", r"\1default: sysv_x64", cfg, flags=re.M)
    open("toolchain.config", "w").write(cfg)
    B.patch_config("toolchain.config", B.FULL)
    open("tc.allon", "w").write(open("toolchain.config").read())
    OPT = ["loop-unrolling:", "loop-unroll-", "function-inlining:", "inline-max-", "constant-folding:", "variable-elision:", "variable-shifting:", "struct-unpacking:",
           "dead-control-flow-removal:", "dead-function-removal:", "unused-declaration-removal:", "variable-allocation-reordering:", "struct-member-reordering:"]
    REG = ["deferred-operands:", "variables-in-registers:", "float-variables-in-registers:", "float-temporaries-in-registers:", "hoist-array-bases:", "variables-in-alloc-functions:", "fuse-length-compare:", "variables-in-arg-registers:"]
    base = open("tc.allon").read()
    def flip(text, prefixes, extra=""):
        out = []
        for l in text.split("\n"):
            if l and not l[0].isspace() and any(l.startswith(p) for p in prefixes) and ":" in l:
                k = l.split(":")[0]
                l = k + ": off"
            out.append(l)
        return "\n".join(out) + extra
    cfgs = {"allon": base,
            "noOPT": flip(base, OPT, ""),
            "noREG": flip(base, REG),
            "none": flip(flip(base, OPT), REG)}
    # a numeric-key variant as well
    cfgs["unroll3"] = re.sub(r"^loop-unrolling:.*$", "loop-unrolling: aggressive\nloop-unroll-factor: 3\nloop-unroll-max-growth: 100\ninline-max-callee-lines: 7", base, flags=re.M)
    progs = [p for p in sorted(glob.glob("tests/*.caspien")) if "_error" not in p and "non_exhaustive_" not in p and "sc.caspien" not in p]
    progs = progs[::5] + sorted(glob.glob("docs/examples/*.caspien"))[:4]
    def comp(cfg, prog, stop, out):
        open("toolchain.config", "w").write(cfgs[cfg])
        r = subprocess.run(["java", "-cp", ".", "Compiler", "-i", prog, out, "--no-cache"] + stop, capture_output=True, text=True)
        return r.returncode == 0
    def rd(p): return open(p, "rb").read()
    bad = 0; n = 0
    for prog in progs:
        # stage 1: HOB identical under every config
        hobs = {}
        for c in cfgs:
            if not comp(c, prog, ["--hob"], "output/h_%s" % c): hobs = None; break
            hobs[c] = rd("output/h_%s" % c)
        if hobs is None: continue
        n += 1
        if len(set(hobs.values())) != 1:
            bad += 1; print("STAGE1 DIFFERS", prog, [c for c in cfgs if hobs[c] != hobs["allon"]])
        # stage 2: HOB2 identical under allon vs noREG (same OPT keys)
        comp("allon", prog, ["--lob"], "output/l_a"); h2a = rd("output/.build/2_optimizer.hob.txt")
        comp("noREG", prog, ["--lob"], "output/l_b"); h2b = rd("output/.build/2_optimizer.hob.txt")
        if h2a != h2b: bad += 1; print("STAGE2 DIFFERS under REG keys", prog)
        # stage 3: standalone LOB generation on h2a under allon vs noOPT vs unroll3 configs
        open(os.path.join(W, "h2a.txt"), "wb").write(h2a)
        outs = {}
        for c in ("allon", "noOPT", "unroll3"):
            comp(c, "tests/in_fold_test.caspien", ["--hob"], "output/dummy")   # makes the Compiler write this config into the component folders
            subprocess.run(["java", "-cp", "out", "caspien.lowerorder.Main", "-i", os.path.join(W, "h2a.txt"), os.path.join(W, "l3_%s.txt" % c)], cwd="LowerOrderGenerator", capture_output=True)
            outs[c] = rd(os.path.join(W, "l3_%s.txt" % c))
        if len(set(outs.values())) != 1: bad += 1; print("STAGE3 DIFFERS under OPT keys", prog)
    print("%s cache_views_check: %d programs, %d violations" % ("PASS" if bad == 0 and n else "FAIL", n, bad))
    sys.exit(1 if bad or not n else 0)

finally:
    os.chdir(ROOT)
    shutil.rmtree(W, ignore_errors=True)
