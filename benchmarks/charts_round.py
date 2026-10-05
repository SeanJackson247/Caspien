#!/usr/bin/env python3
"""
Before/after charts for one optimisation round (standalone HTML, no libraries): one bar chart per program (every optimised Caspien variant,
before and after, seconds) plus an aggregate (per-program geometric mean of after/before, and the overall geometric mean).

    python3 benchmarks/charts_round.py --collect BEFORE_TREE AFTER_TREE [data.json]   # read results.quick.json of two trees
    python3 benchmarks/charts_round.py [out.html] [data.json]                          # render (default charts_round.html, before_after_round.json)

Both trees are measured with `run_all.py --caspien-only --only P --runs 5`, one program at a time, before then after, back to back.
"""
import html, json, math, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
PROGS = ["nbody", "fannkuchredux", "spectralnorm", "sieve", "strings", "graph", "sorting", "binarytrees", "mandelbrot", "fasta", "knucleotide",
         "lru", "merkletrees", "helloworld", "json_serde"]
FIELDS = ("time_s", "times", "size_bytes", "ok")

def collect(before, after, dst):
    out = {}
    for p in PROGS:
        rows = {}
        for side, tree in (("before", before), ("after", after)):
            j = json.load(open(os.path.join(tree, "benchmarks", p, "results.quick.json")))
            for r in j["results"]:
                if r["group"] == "caspien":
                    rows.setdefault(r["label"], {})[side] = {k: r.get(k) for k in FIELDS}
        out[p] = rows
    json.dump(out, open(dst, "w"), indent=1)
    print("wrote", dst)

def short(l):
    return l.replace("Caspien ", "").replace(" · full", "")

def gmean(xs):
    return math.exp(sum(math.log(x) for x in xs) / len(xs))

def render(dst, src):
    d = json.load(open(src))
    per = {}
    for p in PROGS:
        rows = [(short(l), s["before"]["time_s"], s["after"]["time_s"]) for l, s in d[p].items() if "before" in s and "after" in s]
        if rows:
            per[p] = rows
    sections = []
    W, LBL = 900, 230
    for p, rows in per.items():
        mx = max(max(b, a) for _, b, a in rows) * 1.18
        sc = (W - LBL - 150) / mx
        h = 12 + len(rows) * 36 + 6
        s = ['<svg viewBox="0 0 %d %d" role="img" aria-label="%s before and after">' % (W, h, p)]
        for i, (l, b, a) in enumerate(rows):
            y = 12 + i * 36
            s.append('<text x="%d" y="%d" class="lbl" text-anchor="end">%s</text>' % (LBL - 8, y + 20, html.escape(l[:34])))
            s.append('<rect x="%d" y="%d" width="%.1f" height="13" class="before"><title>before %.3f s</title></rect>' % (LBL, y + 2, max(b * sc, 1), b))
            s.append('<text x="%.1f" y="%d" class="val">%.3f s</text>' % (LBL + b * sc + 5, y + 13, b))
            cls = "fast" if a < b * 0.96 else "slow" if a > b * 1.04 else "flat"
            s.append('<rect x="%d" y="%d" width="%.1f" height="13" class="%s"><title>after %.3f s</title></rect>' % (LBL, y + 17, max(a * sc, 1), cls, a))
            s.append('<text x="%.1f" y="%d" class="val">%.3f s (%+.1f%%)</text>' % (LBL + a * sc + 5, y + 28, a, (a / b - 1) * 100))
        s.append("</svg>")
        g = gmean([a / b for _, b, a in rows])
        sections.append('<h3>%s <span class="mute">geomean after/before %.3f (%+.1f%%)</span></h3><div class="card">%s</div>' % (p, g, (g - 1) * 100, "\n".join(s)))
    # aggregate
    gm = {p: gmean([a / b for _, b, a in rows]) for p, rows in per.items()}
    allr = [a / b for rows in per.values() for _, b, a in rows]
    total = gmean(allr)
    AW, AL = 900, 170
    names = list(gm)
    h = 50 + (len(names) + 1) * 22 + 20
    x0, sc = AL + 330, 330 / 0.3
    s = ['<svg viewBox="0 0 %d %d" role="img" aria-label="aggregate change per program">' % (AW, h)]
    for pc in (-0.2, -0.1, 0, 0.1, 0.2):
        x = x0 + pc * sc
        s.append('<line x1="%.1f" y1="30" x2="%.1f" y2="%d" class="grid"/><text x="%.1f" y="22" class="axis" text-anchor="middle">%s</text>' % (x, x, h - 10, x, "%+d%%" % round(pc * 100)))
    for i, k in enumerate(names + ["ALL (%d variants)" % len(allr)]):
        v = gm[k] if k in gm else total
        y = 40 + i * 22
        pct = (v - 1) * 100
        w = min(abs(pct / 100) * sc, 160)
        cls = "fast" if pct < -4 else "slow" if pct > 4 else "flat"
        if i == len(names):
            cls += " all"
        s.append('<text x="%d" y="%d" class="lbl" text-anchor="end">%s</text>' % (AL - 8, y + 13, html.escape(k)))
        s.append('<rect x="%.1f" y="%d" width="%.1f" height="14" class="%s"/>' % (x0 - w if pct < 0 else x0, y + 2, max(w, 1.5), cls))
        tx, anchor = (x0 - w - 4, "end") if pct < 0 else (x0 + w + 4, "start")
        s.append('<text x="%.1f" y="%d" class="val" text-anchor="%s">%+.1f%%</text>' % (tx, y + 14, anchor, pct))
    s.append("</svg>")
    page = PAGE % (len(allr), (total - 1) * 100, "\n".join(s), "\n".join(sections))
    open(dst, "w").write(page)
    print("wrote", dst, "overall geomean %.3f over %d variants" % (total, len(allr)))
    for k in names:
        print("%-14s %.3f" % (k, gm[k]))

PAGE = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Caspien round before and after</title><style>
:root{--bg:#fbfaf7;--fg:#1f2328;--mute:#6b7280;--grid:#d9d6cf;--fast:#2f7d4f;--slow:#c0562b;--flat:#9aa3ad;--before:#c9c4ba;--card:#fff;--line:#e4e1da}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#16181b;--fg:#e8e6e1;--mute:#9aa0a8;--grid:#34383d;--fast:#58b57e;--slow:#e0805a;--flat:#6c7580;--before:#4a4f55;--card:#1d2024;--line:#2c3035}}
body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.55 system-ui,sans-serif}main{max-width:960px;margin:0 auto;padding:24px 16px 60px}
h1{font-size:24px;margin:0 0 4px}h3{font-size:16px;margin:26px 0 6px}.mute{color:var(--mute);font-weight:400;font-size:13px}
.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px 14px}svg{width:100%%;height:auto;display:block}
.grid{stroke:var(--grid)}.axis,.val{fill:var(--mute);font-size:11px}.lbl{fill:var(--fg);font-size:11.5px}
.before{fill:var(--before)}.fast{fill:var(--fast)}.slow{fill:var(--slow)}.flat{fill:var(--flat)}.all{opacity:.75}
</style></head><body><main>
<h1>Caspien: this round, before and after</h1>
<div class="mute">Optimised Caspien, same machine, old tree vs new tree measured back to back per program (best of 5). Overall geometric mean of after/before over %d variants: <b>%+.1f%%</b>. Grey = before, coloured = after (green faster, red slower, grey within &plusmn;4%% noise). Linux only.</div>
<h3>Aggregate: geometric mean of after/before per program</h3><div class="card">%s</div>
%s
</main></body></html>
"""
if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--collect":
        collect(sys.argv[2], sys.argv[3], sys.argv[4] if len(sys.argv) > 4 else os.path.join(HERE, "before_after_round.json"))
    else:
        render(sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "charts_round.html"), sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "before_after_round.json"))
