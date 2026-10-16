#!/usr/bin/env python3
"""
What does the recursion-to-loop conversion cost? Two micro-benchmarks (see README text in RESULTS.md):
  chain: linear tail recursion, depth D repeated R times. C -O0 / -O1 -fno-inline (a real call per step), C -O2 (gcc turns the tail call into a
         loop), C hand loop, Caspien @recursive (the compiler lowers it to a bounded `for`), Caspien hand loop.
  tree : visit every node of a complete binary tree (non-tail recursion, which @recursive does not allow): C recursive -O0/-O2, C explicit stack,
         Caspien explicit stack (the only way to write it today).
    python3 experiments/recursion/run.py [--runs 3] [--depth 1000] [--reps 200000] [--tree 27]
"""
import argparse, json, os, shutil, subprocess, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
from harness import caspien, core as B, paths, results as R
ap = argparse.ArgumentParser(); ap.add_argument("--runs", type=int, default=3); ap.add_argument("--depth", type=int, default=1000)
ap.add_argument("--reps", type=int, default=200000); ap.add_argument("--tree", type=int, default=27); a = ap.parse_args()
W = tempfile.mkdtemp(prefix="rec_"); res = []
env = dict(os.environ, REC_DEPTH=str(a.depth), REC_REPS=str(a.reps), REC_TREE_DEPTH=str(a.tree))
def measure(group, label, build, exe, args):
    for bc in build:
        dt, _, rc, out = B.run(bc["cmd"], cwd=bc.get("cwd", W), env=bc.get("env"))
        if rc: print("BUILD FAILED", label, out[-400:]); return
    ts, outs = [], []
    for _ in range(a.runs):
        dt, kb, rc, out = B.run(exe + args, cwd=W, env=env)
        if rc: print("RUN FAILED", label, out[-300:]); return
        ts.append(dt); outs.append(out.strip())
    res.append(dict(group=group, label=label, time_s=min(ts), times=ts, output=outs[-1]))
    print("%-6s %-44s %8.3f s  %s" % (group, label, min(ts), outs[-1]), flush=True)
cargs = [str(a.depth), str(a.reps)]; targs = [str(a.tree)]
for label, flags, src, extra in (("C -O0, recursive (a call per step)", ["-O0"], "chain.c", []), ("C -O1 -fno-inline, recursive (a call per step)", ["-O1", "-fno-inline"], "chain.c", []),
                                 ("C -O2, recursive (gcc makes the tail call a loop)", ["-O2"], "chain.c", []), ("C -O2, hand loop", ["-O2", "-DLOOP"], "chain.c", []),
                                 ("C -O0, hand loop", ["-O0", "-DLOOP"], "chain.c", [])):
    exe = os.path.join(W, "c" + str(len(res)))
    measure("chain", label, [{"cmd": ["gcc"] + flags + ["-o", exe, os.path.join(HERE, src)]}], [exe], cargs)
for label, flags, src in (("C -O0, recursive", ["-O0"], "tree.c"), ("C -O2, recursive", ["-O2"], "tree.c"), ("C -O0, explicit stack", ["-O0", "-DSTACK"], "tree.c"), ("C -O2, explicit stack", ["-O2", "-DSTACK"], "tree.c")):
    exe = os.path.join(W, "t" + str(len(res)))
    measure("tree", label, [{"cmd": ["gcc"] + flags + ["-o", exe, os.path.join(HERE, src)]}], [exe], targs)
P = os.path.join(HERE, "ports"); NOENV = dict(os.environ)
def have(*tools): return all(shutil.which(t) for t in tools)
# C++ (the C sources compiled as C++), same flags as the C rows
if have("g++"):
    for label, flags, src, grp, ar in (("C++ -O2, recursive (g++)", ["-O2"], "chain.c", "chain", cargs), ("C++ -O2, hand loop (g++)", ["-O2", "-DLOOP"], "chain.c", "chain", cargs),
                                       ("C++ -O2, recursive (g++)", ["-O2"], "tree.c", "tree", targs)):
        exe = os.path.join(W, "cpp%d" % len(res))
        measure(grp, label, [{"cmd": ["g++", "-x", "c++"] + flags + ["-o", exe, os.path.join(HERE, src)]}], [exe], ar)
if have("rustc"):
    for grp, f, ar, extra in (("chain", "chain.rs", cargs, []), ("chain", "chain.rs", cargs + ["loop"], []), ("tree", "tree.rs", targs, [])):
        exe = os.path.join(W, "rs_%s_%d" % (f, len(res)))
        if not os.path.exists(exe + ".built"):
            measure(grp, "Rust -O, %s" % ("hand loop" if "loop" in ar else "recursive"), [{"cmd": ["rustc", "-O", "-o", exe, os.path.join(P, f)]}], [exe], ar)
if have("go"):
    env_go = dict(os.environ, GOCACHE=os.path.join(W, "gocache"), GOFLAGS="-mod=mod", GO111MODULE="off")
    for grp, f, ar in (("chain", "chain.go", cargs), ("chain", "chain.go", cargs + ["loop"]), ("tree", "tree.go", targs)):
        exe = os.path.join(W, "go_%s_%d" % (f, len(res)))
        measure(grp, "Go, %s" % ("hand loop" if "loop" in ar else "recursive"), [{"cmd": ["go", "build", "-o", exe, os.path.join(P, f)], "env": env_go}], [exe], ar)
if have("javac", "java"):
    jd = os.path.join(W, "j"); os.makedirs(jd, exist_ok=True)
    B.run(["javac", "-d", jd, os.path.join(P, "Chain.java"), os.path.join(P, "Tree.java")], cwd=W)
    for grp, cls, ar in (("chain", "Chain", cargs), ("chain", "Chain", cargs + ["loop"]), ("tree", "Tree", targs)):
        measure(grp, "Java (HotSpot), %s" % ("hand loop" if "loop" in ar else "recursive"), [], ["java", "-Xss512m", "-cp", jd, cls], ar)
for tool, name, ext in (("node", "Node.js", "js"), ("bun", "Bun", "js"), ("luajit", "LuaJIT", "lua")):
    if have(tool):
        for grp, f, ar in (("chain", "chain." + ext, cargs), ("chain", "chain." + ext, cargs + ["loop"]), ("tree", "tree." + ext, targs)):
            extra = ["--stack-size=60000"] if tool == "node" else []
            measure(grp, "%s, %s" % (name, "hand loop" if "loop" in ar else "recursive"), [], [tool] + extra + [os.path.join(P, f)], ar)
tree = caspien.Tree()
for group, src, kind in (("chain", "chain_recursive", "@recursive (lowered to a loop by the compiler)"), ("chain", "chain_loop", "hand-written for loop"), ("tree", "tree_stack", "explicit stack (unsafe dynarray)")):
    for mode, kv in (("off", caspien.OFF), ("full", caspien.FULL)):
        rel = tree.prepare(os.path.join(HERE, "caspien", src + ".caspien"), kv)
        exe = os.path.join(W, "cas_%s_%s" % (src, mode))
        measure(group, "Caspien %s, optimisations %s" % (kind, "off" if mode == "off" else "everything on"), [tree.compile_command(rel, exe)], [exe], [])
tree.close()
for g in ("chain", "tree"):
    outs = {r["output"] for r in res if r["group"] == g}
    print(g, "outputs", "all identical" if len(outs) == 1 else "DIFFER: %s" % outs)
R.write_json(os.path.join(paths.DATA, "recursion.json"), dict(depth=a.depth, reps=a.reps, tree=a.tree, runs=a.runs, results=res))   # the site reads it from there
shutil.rmtree(W, ignore_errors=True)
