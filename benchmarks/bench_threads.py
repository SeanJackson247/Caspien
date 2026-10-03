#!/usr/bin/env python3
"""
Cross-language benchmarks for OS threads (execution time, peak memory, compile time, executable size):

    python3 benchmarks/bench_threads.py threads  [--n 1024]   [--runs 3] [--builds 3] [--only substring ...]
    python3 benchmarks/bench_threads.py swaplock [--n 500000] [--runs 3] [--builds 3]

threads:  N tasks (a multiple of 64) run in waves of 64 OS threads, each thread runs the same integer kernel on its own state and the
          results are added up after the join. No data is shared between threads.
swaplock: 32 OS threads each update one shared (count, sum) struct N times under one lock (Caspien: a `swap` lock; the others: their
          standard mutex). The result does not depend on the order of the updates.
Only languages with OS-level threads are included (C, C++, Rust, Java, and Go with every task pinned to its own OS thread by
runtime.LockOSThread). Node, Bun and Lua are left out: their workers/coroutines are not comparable thread-per-task units.
Caspien is measured as a naive and a hand-optimised program, each compiled with every optimisation off and with everything on.
The output of every implementation is compared with the output of the C -O0 build at the same N.
The harness itself (launcher, config patching, scratch Caspien tree) is shared with nbody/bench.py.
"""
import argparse, json, os, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, ".."))
sys.path.insert(0, os.path.join(HERE, "nbody"))
import bench as B   # noqa: E402

PROGRAMS = {
    "threads": dict(
        title="Thread spawn", env="THREADS_N", n=1024, stem="threads", java_class="Threads",
        caspien=[("threads_naive", "naive", "the kernel step is a function call"),
                 ("threads_opt", "optimized", "kernel inlined by hand and unrolled four times")]),
    "swaplock": dict(
        title="Swap lock contention", env="SWAPLOCK_K", n=500000, stem="swaplock", java_class="Swaplock",
        caspien=[("swaplock_naive", "naive", "a CLOSED lock is retried at once (pure spinning)"),
                 ("swaplock_opt", "optimized", "a backoff policy yields the time slice every 16th failed attempt")]),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("program", choices=sorted(PROGRAMS))
    ap.add_argument("--n", type=int)
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--builds", type=int, default=3)
    ap.add_argument("--out")
    ap.add_argument("--only", nargs="*", default=[])
    a = ap.parse_args()
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
    results = []
    envn = dict(os.environ, JAVA_TOOL_OPTIONS="")
    envn[P["env"]] = N
    noj = dict(os.environ, JAVA_TOOL_OPTIONS="")

    def wanted(label):
        return not a.only or any(s.lower() in label.lower() for s in a.only)

    def measure(label, group, lang, build_cmds, exe_cmd, size_fn, note=""):
        if not wanted(label):
            return
        ctimes = []
        for _ in range(a.builds if build_cmds else 0):
            t = 0.0
            for bc in build_cmds:
                dt, _, rc, out = B.run(bc["cmd"], cwd=bc.get("cwd", W), env=bc.get("env"))
                t += dt
                if rc != 0:
                    print("BUILD FAILED", label, out[-600:], file=sys.stderr)
                    return
            ctimes.append(t)
        times, rss, outs = [], [], []
        for _ in range(a.runs):
            dt, kb, rc, out = B.run(exe_cmd, cwd=W, env=envn)
            if rc != 0:
                print("RUN FAILED", label, out[-300:], file=sys.stderr)
                return
            times.append(dt); rss.append(kb); outs.append(out.strip().split())
        r = {"label": label, "group": group, "lang": lang, "note": note, "compile_s": B.median(ctimes),
             "size_bytes": size_fn(), "time_s": min(times), "times": times, "rss_kb": B.median(rss), "output": outs[-1]}
        results.append(r)
        print("%-30s compile %6.2fs  size %10d  time %8.3fs  rss %8d KB  %s" % (
            label, r["compile_s"], r["size_bytes"], r["time_s"], r["rss_kb"], " ".join(r["output"])[:60]), flush=True)

    if B.have("gcc"):
        for opt in ("-O0", "-O2"):
            exe = os.path.join(W, "c" + opt)
            measure("C " + opt, "bare", "C", [{"cmd": ["gcc", opt, "-o", exe, stem + ".c", "-lpthread"]}], [exe, N], lambda e=exe: B.size_of(e))
    if B.have("g++"):
        exe = os.path.join(W, "cpp_O2")
        measure("C++ -O2", "bare", "C++", [{"cmd": ["g++", "-O2", "-o", exe, stem + ".cpp", "-lpthread"]}], [exe, N], lambda: B.size_of(exe))
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
    if B.have("java") and B.have("gcc"):
        ct = B.make_caspien_tree(ROOT)
        base = open(os.path.join(ct, "toolchain.config")).read()
        srcdir = os.path.join(ct, "bench", "t", "c")      # the programs import "../../../stdlib/..."
        os.makedirs(srcdir, exist_ok=True)
        for src, kind, desc in P["caspien"]:
            for mode, kv in (("off", B.OFF), ("full", B.FULL)):
                label = "Caspien %s · %s" % (kind, mode)
                if not wanted(label):
                    continue
                open(os.path.join(ct, "toolchain.config"), "w").write(base)
                B.patch_config(os.path.join(ct, "toolchain.config"), kv)
                shutil.copy(os.path.join(CAS, src + ".caspien"), os.path.join(srcdir, "_bp.caspien"))
                exe = os.path.join(W, "cas_%s_%s" % (src, mode))
                measure(label, "caspien", "Caspien", [{"cmd": ["java", "Compiler", "-i", "bench/t/c/_bp.caspien", exe], "cwd": ct, "env": noj}],
                        [exe], lambda e=exe: B.size_of(e), desc + "; optimisations " + mode)
        shutil.rmtree(ct, ignore_errors=True)
    ref = next((r["output"] for r in results if r["label"] == "C -O0"), None)
    for r in results:
        r["ok"] = ref is not None and r["output"] == ref
    json.dump({"program": a.program, "title": P["title"], "n": n, "runs": a.runs, "builds": a.builds, "results": results},
              open(out_json, "w"), indent=1)
    shutil.rmtree(W, ignore_errors=True)
    print("wrote", out_json, "| differs from C -O0:", [r["label"] for r in results if not r["ok"]] or "none")


if __name__ == "__main__":
    main()
