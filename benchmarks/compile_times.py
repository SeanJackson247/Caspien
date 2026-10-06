#!/usr/bin/env python3
"""
Compile-time benchmark only (nothing is run): every Caspien benchmark program is compiled with the FULL optimiser configuration, `--no-cache`, in a Linux
scratch copy of the tree, one build at a time; the figure is the median of --builds builds (default 3). The same programs' compile times stored in the
benchmarks' results.json files (the last full run, Caspien "· full" rows) and the other languages' stored compile times are shown beside it.

    python3 benchmarks/compile_times.py [--builds 3] [--only sieve lru] [--out benchmarks/compile_times.json] [--html benchmarks/charts.compile.html]
"""
import argparse, glob, html, json, os, re, statistics, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(HERE, "nbody"))
import bench as B   # noqa: E402


def stored(prog):
    """label -> compile_s of the last full run, from results.json."""
    try:
        d = json.load(open(os.path.join(HERE, prog, "results.json")))
    except OSError:
        return {}
    return {r["label"]: r.get("compile_s") for r in d["results"] if r.get("compile_s") is not None}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--builds", type=int, default=3)
    ap.add_argument("--only", nargs="*", default=[])
    ap.add_argument("--out", default=os.path.join(HERE, "compile_times.json"))
    ap.add_argument("--html", default=os.path.join(HERE, "charts.compile.html"))
    a = ap.parse_args()
    ct = B.make_caspien_tree(ROOT)
    base = open(os.path.join(ct, "toolchain.config")).read()
    open(os.path.join(ct, "toolchain.config"), "w").write(base)
    B.patch_config(os.path.join(ct, "toolchain.config"), B.FULL)
    env = dict(os.environ, JAVA_TOOL_OPTIONS="")
    rows = []
    progs = sorted(d for d in os.listdir(HERE) if os.path.isdir(os.path.join(HERE, d, "caspien")))
    for prog in progs:
        if a.only and prog not in a.only:
            continue
        old = stored(prog)
        for f in sorted(glob.glob(os.path.join(HERE, prog, "caspien", "*.caspien"))):
            name = os.path.basename(f)[:-8]
            text = re.sub(r'"(\.\./)+stdlib/', '"stdlib/', open(f).read())
            open(os.path.join(ct, "_bp.caspien"), "w").write(text)
            ts = []
            for k in range(a.builds):
                exe = os.path.join(ct, "cas_out")
                t0 = time.time()
                r = subprocess.run(["java", "Compiler", "-i", "_bp.caspien", exe, "--no-cache"], cwd=ct, env=env, capture_output=True, text=True)
                dt = time.time() - t0
                if r.returncode != 0:
                    print("FAIL %s/%s:" % (prog, name), r.stdout[-300:], r.stderr[-300:])
                    ts = []
                    break
                ts.append(dt)
            if not ts:
                continue
            row = {"program": prog, "variant": name, "compile_s": statistics.median(ts), "runs": ts}
            rows.append(row)
            print("%-14s %-28s %6.2fs  (%s)" % (prog, name, row["compile_s"], " ".join("%.2f" % t for t in ts)), flush=True)
    others = {}
    for prog in progs:
        for l, v in stored(prog).items():
            if l.startswith(("C -O2", "Rust", "Go", "Zig", "Odin", "C++ -O2")):
                others.setdefault(prog, {})[l] = v
    json.dump({"builds": a.builds, "caspien_full": rows, "others_stored": others}, open(a.out, "w"), indent=1)
    page(rows, others, a.html, a.builds)
    import shutil
    shutil.rmtree(ct, ignore_errors=True)


def page(rows, others, dst, builds):
    byprog = {}
    for r in rows:
        byprog.setdefault(r["program"], []).append(r)
    mx = max(r["compile_s"] for r in rows) if rows else 1
    mx = max([mx] + [v for d in others.values() for v in d.values()])
    out = ["<!doctype html><meta charset=utf-8><title>Compile times</title><style>"
           ":root{--bg:#fff;--fg:#1d2430;--mut:#6b7686;--cas:#2e9e5b;--oth:#c0504d;--line:#e3e7ee}"
           "@media(prefers-color-scheme:dark){:root{--bg:#14181f;--fg:#e6e9ef;--mut:#9aa5b4;--cas:#4cc47f;--oth:#e07a76;--line:#2a313c}}"
           "body{background:var(--bg);color:var(--fg);font:14px system-ui,sans-serif;margin:0;padding:16px;max-width:980px}"
           "h1{font-size:20px}h2{font-size:15px;margin:22px 0 6px}.r{display:grid;grid-template-columns:230px 1fr 64px;gap:8px;align-items:center;margin:2px 0}"
           ".b{height:14px;border-radius:2px}.c{background:var(--cas)}.o{background:var(--oth);opacity:.8}.n{color:var(--mut);font-size:12px;text-align:right}"
           ".l{font-size:12.5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}p{color:var(--mut);max-width:70ch}</style>",
           "<h1>Compile times</h1><p>Caspien: every benchmark program compiled now with all optimisations on, no stage cache, median of %d builds (seconds, whole "
           "compiler run: front end, optimiser, lowering, codegen, gcc). Red bars: the other languages' compile times as stored from the last full run "
           "(not re-measured). Nothing was run.</p>" % builds]
    for prog, rs in byprog.items():
        out.append("<h2>%s</h2>" % html.escape(prog))
        for r in rs:
            out.append('<div class=r><div class=l>Caspien %s</div><div><div class="b c" style="width:%.1f%%"></div></div><div class=n>%.2f s</div></div>' % (html.escape(r["variant"]), 100 * r["compile_s"] / mx, r["compile_s"]))
        for l, v in others.get(prog, {}).items():
            out.append('<div class=r><div class=l>%s (stored)</div><div><div class="b o" style="width:%.1f%%"></div></div><div class=n>%.2f s</div></div>' % (html.escape(l), 100 * v / mx, v))
    open(dst, "w").write("\n".join(out))


if __name__ == "__main__":
    main()
