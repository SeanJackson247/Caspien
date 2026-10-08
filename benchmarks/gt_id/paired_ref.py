#!/usr/bin/env python3
"""
Paired old-vs-new timing of the Caspien benchmark variants that use a nullable `ref` (binarytrees/graph/lru `*_ref_*`, the `*_refstd` stdlib
HashMap variants): the same source is built with the compiler at git HEAD~0 of a pristine checkout (OLD: `ref` = address, id-less ghost table)
and with this working tree (NEW: `ref` = 64-bit id), both with the benchmarks' FULL optimiser config, then run alternately OLD NEW OLD NEW ...
on one machine in one go (VM noise is 10-30%, so only same-run ratios mean anything). Prints best-of-N seconds and the NEW/OLD ratio.

    python3 benchmarks/gt_id/paired_ref.py OLD_TREE [--reps 5] [--scale 1.0] [--only graph_ref lru_ref_naive]

OLD_TREE: a directory holding `git archive <rev>` output with the four stage classes built (javac -d out ...) and a Linux toolchain.config.
"""
import argparse, os, shutil, subprocess, sys, tempfile, time

HERE = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.abspath(os.path.join(HERE, ".."))
ROOT = os.path.abspath(os.path.join(BENCH, ".."))
sys.path.insert(0, os.path.join(BENCH, "nbody"))
sys.path.insert(0, BENCH)
import bench as B          # noqa: E402
import bench_suite as BS   # noqa: E402

VARIANTS = [
    ("binarytrees", "binarytrees_ref_naive"), ("binarytrees", "binarytrees_ref_safe"), ("binarytrees", "binarytrees_ref_unsafe"),
    ("graph", "graph_ref_naive"), ("graph", "graph_ref_safe"), ("graph", "graph_ref_unsafe"),
    ("lru", "lru_ref_naive"), ("lru", "lru_ref_safe"), ("lru", "lru_ref_unsafe"), ("lru", "lru_naive_refstd"),
    ("knucleotide", "knucleotide_naive_refstd"),
    # programs that never take a nullable `ref`: what the id table costs them (should be nothing)
    ("knucleotide", "knucleotide_naive"), ("knucleotide", "knucleotide_safe"), ("lru", "lru_naive"), ("lru", "lru_safe"),
    ("binarytrees", "binarytrees_naive"), ("binarytrees", "binarytrees_safe"), ("fasta", "fasta_naive"), ("merkletrees", "merkletrees_naive"),
]


def build(tree, prog, src, out):
    cfg = open(os.path.join(tree, "toolchain.config")).read()
    path = os.path.join(tree, "toolchain.config")
    B.patch_config(path, B.FULL)
    text = open(os.path.join(BENCH, prog, "caspien", src + ".caspien")).read().replace("../../../stdlib/", "stdlib/")
    open(os.path.join(tree, "_bp.caspien"), "w").write(text)
    env = dict(os.environ, JAVA_TOOL_OPTIONS="")
    r = subprocess.run(["java", "-cp", ".", "Compiler", "-i", "_bp.caspien", out, "--no-cache"], cwd=tree, env=env, capture_output=True, text=True)
    open(path, "w").write(cfg)
    if r.returncode != 0:
        raise SystemExit("build failed: %s\n%s" % (src, r.stdout[-400:] + r.stderr[-400:]))


def timed(exe, env):
    t = time.perf_counter()
    r = subprocess.run([exe], env=env, capture_output=True, text=True)
    dt = time.perf_counter() - t
    if r.returncode != 0:
        raise SystemExit("run failed: %s rc=%d" % (exe, r.returncode))
    return dt, r.stdout


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("old_tree")
    ap.add_argument("--reps", type=int, default=5)
    ap.add_argument("--only", nargs="*", default=[])
    ap.add_argument("--json")
    a = ap.parse_args()
    new_tree = B.make_caspien_tree(ROOT)
    work = tempfile.mkdtemp(prefix="paired_ref_")
    rows = []
    for prog, src in VARIANTS:
        if a.only and not any(s in src for s in a.only):
            continue
        P = BS.PROGRAMS[prog]
        env = dict(os.environ, JAVA_TOOL_OPTIONS="")
        env[P["env"]] = str(P["n"])
        exes = {}
        for tag, tree in (("old", a.old_tree), ("new", new_tree)):
            exes[tag] = os.path.join(work, "%s_%s" % (src, tag))
            build(tree, prog, src, exes[tag])
        times = {"old": [], "new": []}
        outs = {}
        for i in range(a.reps):
            for tag in (("old", "new") if i % 2 == 0 else ("new", "old")):
                dt, out = timed(exes[tag], env)
                times[tag].append(dt)
                outs[tag] = out
        same = outs["old"] == outs["new"]
        o, n = min(times["old"]), min(times["new"])
        rows.append({"variant": src, "old_s": o, "new_s": n, "ratio": n / o, "old_runs": times["old"], "new_runs": times["new"], "same_output": same})
        print("%-28s old %7.3fs  new %7.3fs  new/old %5.2fx  output %s" % (src, o, n, n / o, "identical" if same else "DIFFERENT"), flush=True)
    shutil.rmtree(work, ignore_errors=True)
    shutil.rmtree(new_tree, ignore_errors=True)
    if a.json:
        import json
        json.dump(rows, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    main()
