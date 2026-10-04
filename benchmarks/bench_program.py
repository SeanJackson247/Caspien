#!/usr/bin/env python3
"""
Cross-language benchmark for fannkuch-redux and spectral-norm (the same four metrics as nbody/bench.py: execution time, peak
memory, compile time, executable size).

    python3 benchmarks/bench_program.py fannkuchredux [--n 11] [--runs 3] [--builds 3] [--only substring ...]
    python3 benchmarks/bench_program.py spectralnorm  [--n 2000]

Builds and runs every implementation this machine has a toolchain for and writes benchmarks/<program>/results.json.
Bare-metal, JVM and JS programs take N as argv[1]; Caspien programs read it from an environment variable (FANNKUCH_N / SPECTRAL_N).
Caspien is measured twice per source file: a naive version and a hand-optimised one, each compiled with every optimisation off and
with everything on (all passes at their most aggressive setting). Output of every implementation is compared, as a whole, with the
output of the C -O0 build at the same N.
The harness itself (launcher, config patching, scratch Caspien tree) is shared with nbody/bench.py.
"""
import argparse, json, os, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, ".."))
sys.path.insert(0, os.path.join(HERE, "nbody"))
import bench as B   # noqa: E402  (nbody/bench.py: run, have, size_of, java_runtime_size, patch_config, make_caspien_tree, OFF, FULL)

PROGRAMS = {
    "fannkuchredux": dict(
        title="Fannkuch-redux", env="FANNKUCH_N", n=11, stem="fannkuchredux", java_class="FannkuchRedux",
        caspien=[("fannkuch_naive", "naive", "plain helper functions"), ("fannkuch_opt", "optimized", "everything hand-inlined into main")]),
    "spectralnorm": dict(
        title="Spectral-norm", env="SPECTRAL_N", n=2000, stem="spectralnorm", java_class="SpectralNorm",
        caspien=[("spectralnorm_naive", "naive", "A(i,j) computed by a function call"),
                 ("spectralnorm_opt", "optimized", "incremental denominator, no calls in the inner loop")]),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("program", choices=sorted(PROGRAMS))
    ap.add_argument("--n", type=int)
    B.add_mode_args(ap)
    ap.add_argument("--out")
    ap.add_argument("--only", nargs="*", default=[])
    a = ap.parse_args()
    S = B.settings(a)
    limits = {}
    P = PROGRAMS[a.program]
    n = a.n or P["n"]
    N = str(n)
    pdir = os.path.join(HERE, a.program)
    REF, CAS = os.path.join(pdir, "reference"), os.path.join(pdir, "caspien")
    out_json = a.out or os.path.join(pdir, "results.json")
    stem, jc = P["stem"], P["java_class"]
    W = tempfile.mkdtemp(prefix=a.program + "_bench_")
    for f in os.listdir(REF):
        shutil.copy(os.path.join(REF, f), W)
    BC = B.BuildCache(W, S["build_cache"])
    results = []
    envn = dict(os.environ, JAVA_TOOL_OPTIONS="")
    envn[P["env"]] = N
    noj = dict(os.environ, JAVA_TOOL_OPTIONS="")

    def wanted(label):
        return not a.only or any(s.lower() in label.lower() for s in a.only)

    def measure(label, group, lang, build_cmds, exe_cmd, size_fn, note=""):
        if not wanted(label):
            return
        built = BC.compile(label, group, build_cmds, S["builds"])
        if built is None:
            return
        ctimes, reused = built
        if reused:
            note = (note + "; " if note else "") + "compile time reused from an earlier build (build cache)"
        status, times, rss, outs, text = B.timed_runs(exe_cmd, W, envn, S, limits, "f64", group == "caspien")
        if status == "fail":
            print("RUN FAILED", label, text[-300:], file=sys.stderr)
            return
        if status == "cutoff":
            r = B.cutoff_row(label, group, lang, "f64", note, B.median(ctimes), size_fn(), times[0])
            r["compile_cached"] = reused
            results.append(r)
            print("%-40s compile %6.2fs  CUT OFF at %.3fs (%s)" % (label, r["compile_s"], times[0], B.CUTOFF_NOTE), flush=True)
            return
        r = {"label": label, "group": group, "lang": lang, "prec": "f64", "note": note, "compile_s": B.median(ctimes),
             "size_bytes": size_fn(), "time_s": min(times), "times": times, "rss_kb": B.median(rss), "output": outs[-1]}
        B.note_caspien(limits, S, "f64", label, r["time_s"])
        r["compile_cached"] = reused
        results.append(r)
        print("%-40s compile %6.2fs  size %10d  time %8.3fs  rss %8d KB  %s" % (
            label, r["compile_s"], r["size_bytes"], r["time_s"], r["rss_kb"], " ".join(r["output"])[:50]), flush=True)

    # Caspien first: its slowest optimised time is the limit for every other implementation in quick mode
    if B.have("java") and B.have("gcc"):
        ct = B.make_caspien_tree(ROOT)
        base = open(os.path.join(ct, "toolchain.config")).read()
        for src, kind, desc in P["caspien"]:
            for mode, kv in B.caspien_modes(S):
                label = "Caspien %s · %s" % (kind, mode)
                if not wanted(label):
                    continue
                open(os.path.join(ct, "toolchain.config"), "w").write(base)
                B.patch_config(os.path.join(ct, "toolchain.config"), kv)
                shutil.copy(os.path.join(CAS, src + ".caspien"), os.path.join(ct, "_bp.caspien"))
                exe = os.path.join(W, "cas_%s_%s" % (src, mode))
                measure(label, "caspien", "Caspien", [{"cmd": ["java", "Compiler", "-i", "_bp.caspien", exe, "--no-cache"], "cwd": ct, "env": noj}],
                        [exe], lambda e=exe: B.size_of(e), desc + "; optimisations " + mode)
        shutil.rmtree(ct, ignore_errors=True)
    if B.have("gcc"):
        for opt in ("-O0", "-O2"):
            exe = os.path.join(W, "c" + opt)
            measure("C " + opt, "bare", "C", [{"cmd": ["gcc", opt, "-o", exe, stem + ".c", "-lm"]}], [exe, N], lambda e=exe: B.size_of(e))
    if B.have("g++"):
        exe = os.path.join(W, "cpp_O2")
        measure("C++ -O2", "bare", "C++", [{"cmd": ["g++", "-O2", "-x", "c++", "-o", exe, stem + ".cpp", "-lm"]}], [exe, N], lambda: B.size_of(exe))
    if B.have("rustc"):
        exe = os.path.join(W, "rs_bin")
        measure("Rust -O", "bare", "Rust", [{"cmd": ["rustc", "-O", "-o", exe, stem + ".rs"]}], [exe, N], lambda: B.size_of(exe))
    if B.have("go"):
        exe = os.path.join(W, "go_bin")
        genv = dict(os.environ, GOCACHE=os.path.join(W, "gocache"), GOFLAGS="-buildvcs=false")
        measure("Go", "bare", "Go", [{"cmd": ["go", "build", "-o", exe, stem + ".go"], "env": genv}], [exe, N], lambda: B.size_of(exe))
    if B.have("javac") and B.have("java"):
        cls = os.path.join(W, jc + ".class")
        measure("Java", "vm", "Java", [{"cmd": ["javac", "-d", W, jc + ".java"], "env": noj}], ["java", "-cp", W, jc, N],
                lambda: B.java_runtime_size() + B.size_of(cls), "size = bin/java + lib/ + conf/ of the JRE plus the class file")
    if B.have("node"):
        measure("Node", "js", "JavaScript", [], ["node", stem + ".js", N],
                lambda: B.size_of(os.path.realpath(shutil.which("node"))) + B.size_of(os.path.join(W, stem + ".js")), "size = node binary + script")
    if B.have("bun"):
        measure("Bun", "js", "JavaScript", [], ["bun", stem + ".js", N],
                lambda: B.size_of(os.path.realpath(shutil.which("bun"))) + B.size_of(os.path.join(W, stem + ".js")), "size = bun binary + script")
    for exe_name, lab, grp, what in (("luajit", "LuaJIT", "luajit", "tracing JIT"),):
        if B.have(exe_name):
            path = os.path.realpath(shutil.which(exe_name))
            measure(lab, grp, lab, [], [exe_name, stem + ".lua", N],
                    lambda p=path: B.size_of(p) + B.size_of(os.path.join(W, stem + ".lua")), "size = %s binary + script (%s)" % (exe_name, what))
    import newlangs as NL   # the fifteen languages added on 1 Oct 2026
    for row in NL.rows(stem, W, W, N):
        measure(row["label"], row["group"], row["lang"], row["build"], row["exe"], row["size"], row["note"])
    results.sort(key=lambda r: r["group"] == "caspien")   # Caspien rows last, as in the charts
    ref = B.pick_reference(results, ["C -O0", "C -O2", "C -O2 (free)"])
    for r in results:
        r["ok"] = bool(r.get("cutoff")) or (ref is not None and r["output"] == ref)   # a cut-off row never produced output
    json.dump({"program": a.program, "title": P["title"], "n": n, "mode": S["mode"], "runs": S["runs"], "builds": S["builds"], "cutoff_s": limits.get("f64") if S["cutoff"] else None, "results": results},
              open(out_json, "w"), indent=1)
    shutil.rmtree(W, ignore_errors=True)
    print("wrote", out_json, "| differs from the C reference:", [r["label"] for r in results if not r["ok"]] or "none", "| cut off:", sum(1 for r in results if r.get("cutoff")))


if __name__ == "__main__":
    main()
