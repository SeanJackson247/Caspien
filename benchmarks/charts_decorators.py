#!/usr/bin/env python3
"""
Before/after chart for the `@inline` work on the benchmark programs and the stdlib (standalone HTML, no libraries).

    python3 benchmarks/charts_decorators.py --collect BEFORE_TREE AFTER_TREE   # read results.quick.json of two trees -> before_after_decorators.json
    python3 benchmarks/charts_decorators.py [out.html]                         # render the chart (default benchmarks/charts_decorators.html)

BEFORE_TREE / AFTER_TREE are two checkouts, each measured with `benchmarks/run_all.py --caspien-only` one program at a time, back to back
(before, then after), so machine drift between the two measurements is as small as it can be.
"""
import html, json, os, statistics, sys

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "before_after_decorators.json")
PROGS = ["nbody", "fannkuchredux", "spectralnorm", "sieve", "strings", "graph", "sorting", "binarytrees", "mandelbrot", "fasta", "knucleotide",
         "lru", "merkletrees", "helloworld", "json_serde"]
FIELDS = ("time_s", "times", "compile_s", "size_bytes", "rss_kb", "ok")


def collect(before, after):
    out = {"programs": {}}
    for p in PROGS:
        rows = {}
        for side, tree in (("before", before), ("after", after)):
            j = json.load(open(os.path.join(tree, "benchmarks", p, "results.quick.json")))
            for r in j["results"]:
                if r["group"] == "caspien":
                    rows.setdefault(r["label"], {})[side] = {k: r.get(k) for k in FIELDS}
        out["programs"][p] = rows
    old = {}
    if os.path.exists(DATA):
        old = json.load(open(DATA))
    out["stdlib"] = old.get("stdlib", {})
    out["meta"] = old.get("meta", {})
    json.dump(out, open(DATA, "w"), indent=1)
    print("wrote", DATA, sum(len(v) for v in out["programs"].values()), "rows")


# rows whose program source was edited (marked in the chart)
EDITED = {("nbody", "natural f64"), ("nbody", "arr f64"), ("nbody", "arr f32")}


def short(label):
    return label.replace("Caspien ", "").replace(" · full", "")


def esc(s):
    return html.escape(str(s))


def render(dst):
    d = json.load(open(DATA))
    rows = []
    for p in PROGS:
        for label, sides in d["programs"][p].items():
            if "before" in sides and "after" in sides:
                b, a = sides["before"], sides["after"]
                spread = max((statistics.median(x["times"]) / min(x["times"]) - 1) if x.get("times") else 0 for x in (b, a))
                rows.append(dict(p=p, label=short(label), b=b["time_s"], a=a["time_s"], pct=(a["time_s"] / b["time_s"] - 1) * 100,
                                 spread=spread * 100, bs=b["size_bytes"], as_=a["size_bytes"], ok=bool(a.get("ok"))))
    # ---- chart A: change per row, diverging bars
    W, LBL, RW, ROWH, TOP = 980, 330, 330, 19, 54
    x0 = LBL + RW / 2.0          # zero line
    scale = (RW / 2.0 - 14) / 25.0   # px per percent, +-25% fills the half width
    H = TOP + len(rows) * ROWH + 40
    s = ['<svg viewBox="0 0 %d %d" role="img" aria-label="Change in run time per Caspien program, after versus before" xmlns="http://www.w3.org/2000/svg">' % (W, H)]
    for pc in (-20, -10, 0, 10, 20):
        x = x0 + pc * scale
        s.append('<line x1="%.1f" y1="%d" x2="%.1f" y2="%d" class="grid"/>' % (x, TOP - 8, x, H - 30))
        s.append('<text x="%.1f" y="%d" class="axis" text-anchor="middle">%s</text>' % (x, TOP - 14, ("%+d%%" % pc) if pc else "0"))
    s.append('<rect x="%.1f" y="%d" width="%.1f" height="%d" class="band"/>' % (x0 - 5 * scale, TOP - 8, 10 * scale, H - 30 - TOP + 8))
    s.append('<text x="%.1f" y="%d" class="axis" text-anchor="middle">&#8592; faster</text>' % (x0 - 110, 14))
    s.append('<text x="%.1f" y="%d" class="axis" text-anchor="middle">slower &#8594;</text>' % (x0 + 110, 14))
    last = None
    for i, r in enumerate(rows):
        y = TOP + i * ROWH
        if r["p"] != last:
            s.append('<text x="6" y="%d" class="prog">%s</text>' % (y + 13 - (0 if i == 0 else 0), esc(r["p"])))
            if i:
                s.append('<line x1="0" y1="%d" x2="%d" y2="%d" class="sep"/>' % (y - 2, W, y - 2))
            last = r["p"]
        s.append('<text x="%d" y="%d" class="lbl">%s</text>' % (LBL - 8 + 150 - 150, y + 13, ""))
        mark = "\u270e " if (r["p"], r["label"]) in EDITED else ""
        s.append('<text x="%d" y="%d" class="lbl" text-anchor="end">%s%s</text>' % (LBL - 6, y + 13, mark, esc(r["label"][:44])))
        w = abs(r["pct"]) * scale
        w = min(w, RW / 2.0 - 2)
        sig = abs(r["pct"]) > max(5.0, r["spread"] * 1.5) and r["b"] >= 0.02   # a few milliseconds is startup noise
        cls = "bar-fast" if r["pct"] < 0 and sig else "bar-slow" if r["pct"] > 0 and sig else "bar-flat"
        bx = x0 - w if r["pct"] < 0 else x0
        s.append('<rect x="%.1f" y="%d" width="%.1f" height="%d" class="%s"><title>%s: %.3f s before, %.3f s after (%+.1f%%)</title></rect>' % (
            bx, y + 3, max(w, 1.5), ROWH - 7, cls, esc(r["p"] + " " + r["label"]), r["b"], r["a"], r["pct"]))
        tx = (bx - 4, "end") if r["pct"] < 0 else (bx + w + 4, "start")
        s.append('<text x="%.1f" y="%d" class="val" text-anchor="%s">%+.1f%%</text>' % (tx[0], y + 14, tx[1], r["pct"]))
    s.append("</svg>")
    chart_a = "\n".join(s)
    # ---- table
    t = ['<table><thead><tr><th>program</th><th>variant</th><th class="n">before s</th><th class="n">after s</th><th class="n">change</th><th class="n">size before</th><th class="n">size after</th></tr></thead><tbody>']
    for r in rows:
        t.append('<tr><td>%s</td><td>%s</td><td class="n">%.3f</td><td class="n">%.3f</td><td class="n">%+.1f%%</td><td class="n">%s</td><td class="n">%s</td></tr>' % (
            esc(r["p"]), esc(r["label"]), r["b"], r["a"], r["pct"], format(r["bs"], ","), format(r["as_"], ",")))
    t.append("</tbody></table>")
    # ---- chart B: stdlib @inline under the three inlining presets
    sl = d.get("stdlib", {})
    bars = []
    for prog, byp in sl.items():
        for preset in ("off", "conservative", "aggressive"):
            if preset in byp:
                bars.append((prog, preset, byp[preset]))
    HB = 60 + len(bars) * 19 + 20
    sb = ['<svg viewBox="0 0 980 %d" role="img" aria-label="Run time with the stdlib @inline, relative to without, per inlining preset" xmlns="http://www.w3.org/2000/svg">' % HB]
    bx0, bw = 330, 560
    for v in (0.0, 0.25, 0.5, 0.75, 1.0, 1.25):
        x = bx0 + v / 1.25 * bw
        sb.append('<line x1="%.1f" y1="46" x2="%.1f" y2="%d" class="grid"/><text x="%.1f" y="38" class="axis" text-anchor="middle">%s</text>' % (x, x, HB - 14, x, ("%.2f&#215;" % v) if v else "0"))
    sb.append('<text x="%d" y="14" class="axis">run time with the stdlib @inline, as a fraction of the run time without (1.00 = no change; lower is faster)</text>' % 6)
    for i, (prog, preset, v) in enumerate(bars):
        y = 52 + i * 19
        r = v["with"] / v["without"]
        sb.append('<text x="%d" y="%d" class="lbl" text-anchor="end">%s, inlining %s</text>' % (bx0 - 6, y + 13, esc(prog), esc(preset)))
        cls = "bar-fast" if r < 0.95 else "bar-flat"
        sb.append('<rect x="%d" y="%d" width="%.1f" height="12" class="%s"><title>%.3f s without, %.3f s with</title></rect>' % (bx0, y + 3, r / 1.25 * bw, cls, v["without"], v["with"]))
        sb.append('<text x="%.1f" y="%d" class="val">%.2f&#215;  (%.2f s &#8594; %.2f s)</text>' % (bx0 + r / 1.25 * bw + 5, y + 14, r, v["without"], v["with"]))
    sb.append("</svg>")
    chart_b = "\n".join(sb)
    meta = d.get("meta", {})
    changed = [r for r in rows if abs(r["pct"]) > max(5.0, r["spread"] * 1.5) and r["b"] >= 0.02]
    page = PAGE.format(chart_a=chart_a, table="\n".join(t), chart_b=chart_b, n=len(rows), nchanged=len(changed),
                       when=esc(meta.get("when", "")), edits=meta.get("edits", ""), method=meta.get("method", ""), caveats=meta.get("caveats", ""))
    open(dst, "w").write(page)
    print("wrote", dst, len(rows), "rows,", len(changed), "outside the noise band")


PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Caspien before and after @inline</title>
<style>
:root {{ --bg:#fbfaf7; --fg:#1f2328; --mute:#6b7280; --grid:#d9d6cf; --band:#ebe8e0; --fast:#2f7d4f; --slow:#c0562b; --flat:#9aa3ad; --card:#fff; --line:#e4e1da; }}
@media (prefers-color-scheme: dark) {{ :root:not([data-theme="light"]) {{ --bg:#16181b; --fg:#e8e6e1; --mute:#9aa0a8; --grid:#34383d; --band:#23262a; --fast:#58b57e; --slow:#e0805a; --flat:#6c7580; --card:#1d2024; --line:#2c3035; }} }}
:root[data-theme="dark"] {{ --bg:#16181b; --fg:#e8e6e1; --mute:#9aa0a8; --grid:#34383d; --band:#23262a; --fast:#58b57e; --slow:#e0805a; --flat:#6c7580; --card:#1d2024; --line:#2c3035; }}
body {{ margin:0; background:var(--bg); color:var(--fg); font:15px/1.55 system-ui,-apple-system,Segoe UI,Roboto,sans-serif; }}
main {{ max-width:1020px; margin:0 auto; padding:24px 16px 60px; }}
h1 {{ font-size:24px; margin:0 0 4px; }} h2 {{ font-size:18px; margin:34px 0 8px; }}
p, li {{ max-width:78ch; }} .mute {{ color:var(--mute); }}
.card {{ background:var(--card); border:1px solid var(--line); border-radius:10px; padding:14px 16px; margin:12px 0; }}
svg {{ width:100%; height:auto; display:block; }}
.grid {{ stroke:var(--grid); stroke-width:1; }} .sep {{ stroke:var(--line); stroke-width:1; }} .band {{ fill:var(--band); }}
.axis {{ fill:var(--mute); font-size:11px; }} .lbl {{ fill:var(--fg); font-size:11.5px; }} .prog {{ fill:var(--fg); font-size:12px; font-weight:600; }} .val {{ fill:var(--mute); font-size:11px; }}
.bar-fast {{ fill:var(--fast); }} .bar-slow {{ fill:var(--slow); }} .bar-flat {{ fill:var(--flat); }}
table {{ border-collapse:collapse; width:100%; font-size:13px; }} th,td {{ padding:4px 8px; border-bottom:1px solid var(--line); text-align:left; }} .n {{ text-align:right; font-variant-numeric:tabular-nums; }}
.key span {{ display:inline-block; width:12px; height:12px; border-radius:2px; vertical-align:-1px; margin:0 4px 0 12px; }}
</style></head><body><main>
<h1>Caspien before and after <code>@inline</code></h1>
<div class="mute">Optimised Caspien programs, quick benchmark, {n} programs/variants. {when}</div>

<h2>What changed</h2>
<div class="card">{edits}</div>

<h2>How it was measured</h2>
<div class="card">{method}</div>

<h2>Run time per Caspien program, after versus before</h2>
<p>Each bar is (after / before &minus; 1). The shaded band is &plusmn;5%, about the run-to-run noise. A bar is coloured only when it is outside the band
and outside 1.5&times; that row's own run-to-run spread; grey bars are within noise. <span class="key"><span style="background:var(--fast)"></span>faster
<span style="background:var(--slow)"></span>slower <span style="background:var(--flat)"></span>no measurable change
&nbsp; &#9998; = the program source was edited (<code>@inline</code>); every other row is the same source and the same assembly, so its movement is noise.</span></p>
<div class="card">{chart_a}</div>
<p><b>{nchanged}</b> of {n} rows moved outside the noise band.</p>

<h2>The stdlib <code>@inline</code>, under the other inlining presets</h2>
<p>The benchmark config uses <code>function-inlining: aggressive</code>, which already inlines everything the stdlib marks, so the benchmark rows above cannot show the stdlib
change. Compiled with <code>off</code> or <code>conservative</code> (the shipped config is <code>off</code>) the naive programs, which call
<code>DynamicArray.get/set</code> and <code>String.charAt/setCharAt</code> in their inner loops, get much faster.</p>
<div class="card">{chart_b}</div>

<h2>Caveats</h2>
<div class="card">{caveats}</div>

<h2>All rows</h2>
{table}
</main></body></html>
"""

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--collect":
        collect(sys.argv[2], sys.argv[3])
    else:
        render(sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "charts_decorators.html"))
