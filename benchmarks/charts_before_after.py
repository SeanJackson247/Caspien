#!/usr/bin/env python3
"""Caspien rows before and after a step of the gap-to-C plan, from a before/after JSON (seconds, fastest run, same machine and
harness in one session) plus the C -O2 time from each results.json. Rows are the labels present in BOTH the "old" and "new" data.
    python3 benchmarks/charts_before_after.py [out.html] [before_after.json] [intro text] [page title]
Default: before_after.json (step 1). Step 2: before_after_step2.json."""
import json, os, sys, html
HERE = os.path.dirname(os.path.abspath(__file__))
dst = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "charts_before_after.html")
ba = json.load(open(sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "before_after.json")))
INTRO = sys.argv[3] if len(sys.argv) > 3 else 'Same machine, same harness, same session; fastest of 3 (n-body 5) runs. "off" = every optimisation off, "full" = everything on. Change measured: f64 hot locals can now live in xmm registers, and hot variables whose lifetimes do not overlap share a register (before, only the heaviest 3 integer / 6 float variables of a function were promoted).'
TITLE = sys.argv[4] if len(sys.argv) > 4 else "Caspien before and after"
def c_o2(d):
    r = json.load(open(os.path.join(HERE, d, "results.json")))
    r = r["results"] if isinstance(r, dict) else r
    return [x["time_s"] for x in r if x["label"] == "C -O2"][0]
def rows_of(k):
    keys = [l for l in ba["old"][k] if l in ba["new"][k]]
    return [(l.replace("Caspien ", ""), l) for l in keys]
P = [("N-body", "nb", "nbody", rows_of("nb"), "5,000,000 steps"),
     ("Fannkuch-redux", "fk", "fannkuchredux", rows_of("fk"), "n = 11"),
     ("Spectral-norm", "sp", "spectralnorm", rows_of("sp"), "N = 2000")]
W, LBL, RH = 640, 150, 15
out = []
for title, k, d, rows, size in P:
    c = c_o2(d)
    mx = max(max(ba["old"][k][l], ba["new"][k][l]) for _, l in rows)
    mx = max(mx, c) * 1.12
    sc = (W - LBL - 90) / mx
    h = 24 + len(rows) * (2 * RH + 14) + 22
    s = [f'<svg viewBox="0 0 {W} {h}" role="img" aria-label="{html.escape(title)} before and after">']
    y = 18
    cx = LBL + c * sc
    s.append(f'<line class="ref" x1="{cx:.1f}" x2="{cx:.1f}" y1="6" y2="{h-20}"/><text class="lab" x="{cx+4:.1f}" y="{h-6}">C -O2 {c:.2f} s</text>')
    for name, l in rows:
        o, n = ba["old"][k][l], ba["new"][k][l]
        s.append(f'<text class="lab" x="{LBL-8}" y="{y+RH+4}" text-anchor="end">{html.escape(name)}</text>')
        s.append(f'<rect class="old" x="{LBL}" y="{y}" width="{o*sc:.1f}" height="{RH-2}" rx="3"/><text class="val" x="{LBL+o*sc+5:.1f}" y="{y+RH-5}">{o:.2f} s before</text>')
        y += RH
        s.append(f'<rect class="new" x="{LBL}" y="{y}" width="{n*sc:.1f}" height="{RH-2}" rx="3"/><text class="val" x="{LBL+n*sc+5:.1f}" y="{y+RH-5}"><tspan class="b">{n:.2f} s after</tspan> ({(n-o)/o*100:+.0f}%)</text>')
        y += RH + 14
    s.append('</svg>')
    out.append(f'<section><h2>{html.escape(title)} <small>{size}, lower is better</small></h2>{"".join(s)}</section>')
page = f'''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{html.escape(TITLE)}</title><style>
:root{{--bg:#f6f7f4;--fg:#1d2a22;--mut:#5d6b62;--old:#c3d6c8;--new:#2e7d4f;--ref:#b03a2e;--line:#d9ded8}}
@media(prefers-color-scheme:dark){{:root:not([data-theme="light"]){{--bg:#141a16;--fg:#e4ebe6;--mut:#9aa89f;--old:#3b5243;--new:#58b97f;--ref:#e07a6c;--line:#2a332d;color-scheme:dark}}}}
:root[data-theme="dark"]{{--bg:#141a16;--fg:#e4ebe6;--mut:#9aa89f;--old:#3b5243;--new:#58b97f;--ref:#e07a6c;--line:#2a332d;color-scheme:dark}}
body{{background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,sans-serif;margin:0}}
main{{max-width:720px;margin:0 auto;padding:24px 16px}}
h1{{font-size:22px;margin:0 0 4px}}p{{color:var(--mut);margin:0 0 18px}}
section{{margin:0 0 26px}}h2{{font-size:17px;margin:0 0 6px;text-wrap:balance}}h2 small{{font-weight:400;color:var(--mut);font-size:13px}}
svg{{width:100%;height:auto;display:block}}.old{{fill:var(--old)}}.new{{fill:var(--new)}}.ref{{stroke:var(--ref);stroke-width:1.5;stroke-dasharray:4 3}}
.lab{{fill:var(--mut);font-size:12px}}.val{{fill:var(--fg);font-size:11.5px;font-variant-numeric:tabular-nums}}.b{{font-weight:600}}
.key{{display:flex;gap:16px;flex-wrap:wrap;font-size:13px;color:var(--mut);margin-bottom:18px}}.key i{{display:inline-block;width:12px;height:12px;border-radius:3px;margin-right:6px;vertical-align:-1px}}
</style></head><body><main>
<h1>{html.escape(TITLE)}</h1>
<p>{html.escape(INTRO)}</p>
<div class="key"><span><i style="background:var(--old)"></i>before</span><span><i style="background:var(--new)"></i>after</span><span><i style="background:var(--ref)"></i>C -O2 (dashed line)</span></div>
{"".join(out)}
</main></body></html>'''
open(dst, "w", encoding="utf-8").write(page)
print("wrote", dst)
