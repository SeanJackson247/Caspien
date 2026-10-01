#!/usr/bin/env python3
"""
Builds the n-body benchmark charts from results.json (written by bench.py) as one self-contained HTML page.

    python3 benchmarks/nbody/charts.py [results.json] [out.html]

Four horizontal bar charts (execution time, peak memory, compile time, executable size), one bar per implementation, sorted best
first. Colours by kind of language, as asked: green = Caspien, red = bare-metal compiled (C, C++, Rust, Go), purple = compiled to
bytecode and run by a VM (Java), blue = interpreted / JIT'd scripts (Node, Bun). Caspien is shown twice per program: with every
optimisation off (pale, hatched) and with everything on (solid). Every row also carries its group in words, because red and green
alone are not safe for red-green colour blindness. A table view of all numbers sits under the charts.
"""
import html, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
src = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "results.json")
dst = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "charts.html")
data = json.load(open(src))
R = data["results"]

GROUP_NAME = {"caspien": "Caspien", "bare": "Bare metal", "vm": "VM (bytecode)", "js": "JavaScript", "lua": "Interpreter", "luajit": "Tracing JIT"}
# one colour per language: key -> (display name, light colour, dark colour)
LANGS = [("caspien", "Caspien", "#2e9e5b", "#3fb872"), ("c", "C", "#d9453d", "#e8635b"), ("cpp", "C++", "#e08a1e", "#f0a040"),
         ("rust", "Rust", "#8c5a34", "#c08a5e"), ("go", "Go", "#1b9aaa", "#3cc0d0"), ("java", "Java", "#8a3fb8", "#a860d6"),
         ("node", "Node", "#2f6fd6", "#5b95ee"), ("bun", "Bun", "#b89a00", "#e0c030"), ("lua", "Lua 5.4", "#2b3a8f", "#7b8cf0"),
         ("luajit", "LuaJIT", "#d8508e", "#f07aae")]


def lang_key(r):
    l = r["label"]
    if l.startswith("Caspien"): return "caspien"
    for pre, k in (("C++", "cpp"), ("C ", "c"), ("Rust", "rust"), ("Go", "go"), ("Java", "java"), ("Node", "node"), ("Bun", "bun"), ("LuaJIT", "luajit"), ("Lua", "lua")):
        if l.startswith(pre): return k
    raise SystemExit("no colour for " + l)
for r in R:
    r["kind"] = "caspien" if r["label"].startswith("Caspien") else r["group"]
    r["key"] = lang_key(r)
    r["mode"] = "off" if r["label"].endswith("· off") else ("full" if r["label"].endswith("· full") else "")

METRICS = [
    ("time_s", "Execution time", "s", "lower is better", False),
    ("rss_kb", "Peak memory (RSS)", "MB", "lower is better", False),
    ("compile_s", "Compile time", "s", "lower is better; Node and Bun have no compile step", False),
    ("size_bytes", "Executable size", "bytes", "lower is better; log scale. Java, Node and Bun: the runtime plus the program", True),
]


def fmt(metric, v):
    if metric == "time_s" or metric == "compile_s":
        return "%.2f s" % v if v >= 0.005 else ("0 s" if v == 0 else "%.3f s" % v)
    if metric == "rss_kb":
        return "%.1f MB" % (v / 1024.0)
    if v >= 1 << 20:
        return "%.1f MB" % (v / (1 << 20))
    if v >= 1 << 10:
        return "%.1f KB" % (v / (1 << 10))
    return "%d B" % v


def label_of(r):
    return r["label"]


def chart(metric, title, unit, note, log):
    rows = sorted(R, key=lambda r: (r[metric], r["label"]))
    vmax = max(r[metric] for r in rows) or 1
    vmin = min([r[metric] for r in rows if r[metric] > 0] or [1])
    import math
    W, LBL, RIGHT, BH, GAP = 900, 270, 190, 17, 6
    top = 8
    H = top + len(rows) * (BH + GAP) + 26
    plot = W - LBL - RIGHT
    ticks = []
    if log:
        lo = 10 ** math.floor(math.log10(vmin))
        hi = 10 ** math.ceil(math.log10(vmax))
        lo_l, hi_l = math.log10(lo), math.log10(hi)
        def sx(v):
            return plot * (math.log10(max(v, lo)) - lo_l) / (hi_l - lo_l)
        e = lo
        while e <= hi * 1.001:
            ticks.append((e, ('%g KB' % (e / 1e3)) if e < 1e6 else ('%g MB' % (e / 1e6))))
            e *= 10
    else:
        def nice(x):
            p = 10 ** math.floor(math.log10(x))
            for m in (1, 2, 2.5, 5, 10):
                if x <= m * p:
                    return m * p
        step_guess = vmax / 5
        st = nice(step_guess)
        top_v = st * math.ceil(vmax / st)
        def sx(v):
            return plot * v / top_v
        k = 0
        while k * st <= top_v * 1.001:
            ticks.append((k * st, None))
            k += 1
    out = ['<svg viewBox="0 0 %d %d" role="img" aria-label="%s, one bar per implementation">' % (W, H, html.escape(title))]
    for v, t in ticks:
        x = LBL + sx(v)
        out.append('<line class="grid" x1="%.1f" x2="%.1f" y1="%d" y2="%d"/>' % (x, x, top - 2, H - 22))
        if t is None:
            t = ("%g" % (v / 1024.0 if metric == "rss_kb" else v))
        out.append('<text class="tick" x="%.1f" y="%d" text-anchor="middle">%s</text>' % (x, H - 8, html.escape(t)))
    for i, r in enumerate(rows):
        y = top + i * (BH + GAP)
        v = r[metric]
        w = max(sx(v), 1.5) if v > 0 else 1.5
        cls = "k-" + r["key"] + (" off" if r["mode"] == "off" else "")
        tip = "%s\\n%s: %s\\ngroup: %s%s" % (
            r["label"], title, fmt(metric, v), GROUP_NAME[r["kind"]], "" if r.get("ok", True) else "\\nOUTPUT DIFFERS from the C reference")
        out.append('<g class="row" data-tip="%s">' % html.escape(tip.replace("\\n", "\n"), quote=True))
        out.append('<rect class="hit" x="0" y="%d" width="%d" height="%d"/>' % (y - GAP // 2, W, BH + GAP))
        out.append('<text class="lbl" x="%d" y="%.1f" text-anchor="end">%s</text>' % (LBL - 8, y + BH * 0.72, html.escape(label_of(r))))
        out.append('<rect class="bar %s" x="%d" y="%d" width="%.1f" height="%d" rx="3"/>' % (cls, LBL, y, w, BH))
        vt = fmt(metric, v)
        out.append('<text class="val" x="%.1f" y="%.1f">%s <tspan class="grp">%s</tspan></text>' % (
            LBL + w + 6, y + BH * 0.72, html.escape(vt), html.escape(GROUP_NAME[r["kind"]])))
        out.append('</g>')
    out.append('</svg>')
    return "\n".join(out)


def table():
    rows = sorted(R, key=lambda r: (r["kind"], r["label"]))
    t = ['<table><thead><tr><th>Implementation</th><th>Group</th><th class="n">Time</th><th class="n">Memory</th><th class="n">Compile</th><th class="n">Size</th><th>Energies (start, end)</th><th>Matches C</th></tr></thead><tbody>']
    for r in rows:
        t.append('<tr><td><span class="sw k-%s%s"></span>%s</td><td>%s</td><td class="n">%s</td><td class="n">%s</td><td class="n">%s</td><td class="n">%s</td><td class="mono">%s</td><td>%s</td></tr>' % (
            r["key"], " off" if r["mode"] == "off" else "", html.escape(r["label"]), GROUP_NAME[r["kind"]],
            fmt("time_s", r["time_s"]), fmt("rss_kb", r["rss_kb"]), fmt("compile_s", r["compile_s"]), fmt("size_bytes", r["size_bytes"]),
            html.escape(" ".join(r["output"])), "yes" if r.get("ok") else "<b>no</b>"))
    t.append("</tbody></table>")
    return "\n".join(t)


def caspien_summary():
    by = {}
    for r in R:
        if r["kind"] == "caspien":
            by.setdefault(r["label"].replace(" · off", "").replace(" · full", ""), {})[r["mode"]] = r
    rows = []
    for k, d in by.items():
        if "off" in d and "full" in d:
            rows.append((k, d["off"]["time_s"], d["full"]["time_s"], d["off"]["time_s"] / d["full"]["time_s"]))
    rows.sort(key=lambda x: -x[3])
    s = ['<table><thead><tr><th>Caspien program</th><th class="n">Optimisations off</th><th class="n">Everything on</th><th class="n">Speed-up</th></tr></thead><tbody>']
    for k, a, b, x in rows:
        s.append('<tr><td>%s</td><td class="n">%.2f s</td><td class="n">%.2f s</td><td class="n">%.2f×</td></tr>' % (html.escape(k.replace("Caspien ", "")), a, b, x))
    s.append("</tbody></table>")
    return "\n".join(s)


sections = []
for m in METRICS:
    sections.append('<section><h2>%s</h2><p class="note">%s</p>%s</section>' % (m[1], html.escape(m[3]), chart(*m)))

n = data["n"]
page = """<title>N-body Benchmark</title>
<style>
:root{--bg:#fbfbf9;--fg:#1d2320;--muted:#5d665f;--grid:#dfe3dc;--card:#ffffff;--line:#d2d8cf;
@@LIGHTVARS@@--off-fill:#cfe9d9}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#181a18;--fg:#e8ece6;--muted:#a1aaa2;--grid:#343a35;--card:#1f2220;--line:#3a413b;
@@DARKVARS@@--off-fill:#28402f;color-scheme:dark}}
:root[data-theme="dark"]{--bg:#181a18;--fg:#e8ece6;--muted:#a1aaa2;--grid:#343a35;--card:#1f2220;--line:#3a413b;
@@DARKVARS@@--off-fill:#28402f;color-scheme:dark}
body{background:var(--bg);color:var(--fg);font:15px/1.5 "IBM Plex Sans",system-ui,sans-serif;padding-inline:16px;padding-block:24px}
main{max-width:980px;margin:0 auto}
h1{font-size:26px;margin:0 0 4px;letter-spacing:-.01em}
h2{font-size:18px;margin:0}
.sub{color:var(--muted);margin:0 0 16px}
.legend{display:flex;flex-wrap:wrap;gap:8px 20px;margin:12px 0 4px;font-size:14px}
.legend span{display:inline-flex;align-items:center;gap:7px}
.sw{display:inline-block;width:14px;height:14px;border-radius:3px;margin-right:7px;vertical-align:-2px}
section{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:16px;margin:16px 0;min-width:0}
.note{color:var(--muted);margin:2px 0 8px;font-size:13px}
.scroll{overflow-x:auto}
svg{width:100%;min-width:640px;height:auto;display:block}
.grid{stroke:var(--grid);stroke-width:1}
.tick{fill:var(--muted);font-size:11px}
.lbl{fill:var(--fg);font-size:12.5px}
.val{fill:var(--fg);font-size:12px;font-variant-numeric:tabular-nums}
.grp{fill:var(--muted);font-size:10.5px}
.hit{fill:transparent}
.row:hover .hit{fill:color-mix(in srgb,var(--fg) 6%,transparent)}
.bar,.sw{fill:var(--c)}
@@KCLASSES@@
.sw{background:var(--c)}
.bar.off,.sw.off{fill:url(#hatch);background:repeating-linear-gradient(45deg,var(--c) 0 2px,var(--off-fill) 2px 5px);stroke:var(--c);stroke-width:1.2}
.n{text-align:right;font-variant-numeric:tabular-nums}
table{border-collapse:collapse;width:100%;font-size:13px}
th,td{padding:6px 10px;border-bottom:1px solid var(--line);text-align:left;white-space:nowrap}
th{color:var(--muted);font-weight:600;font-size:12px;text-transform:uppercase;letter-spacing:.04em}
.mono{font-family:"IBM Plex Mono",ui-monospace,monospace;font-size:12px}
details{margin-top:8px}summary{cursor:pointer;color:var(--muted)}
#tip{position:fixed;pointer-events:none;background:var(--fg);color:var(--bg);padding:6px 9px;border-radius:6px;font-size:12px;white-space:pre;display:none;z-index:5}
.foot{color:var(--muted);font-size:13px}
</style>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono&family=IBM+Plex+Sans:wght@400;600&display=swap">
<main>
<h1>N-body, @@n@@ steps</h1>
<p class="sub">Caspien against C, C++, Rust, Go, Java, Node, Bun, Lua 5.4 and LuaJIT, with every Caspien optimisation off and everything on. Same program, same data; each row shows its group in words as well as colour.</p>
<div class="legend">
@@legend@@
</div>
<svg width="0" height="0" style="position:absolute"><defs><pattern id="hatch" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="6" height="6" fill="var(--off-fill)"/><line x1="0" y1="0" x2="0" y2="6" stroke="currentColor" style="stroke:var(--lang-caspien)" stroke-width="2.4"/></pattern></defs></svg>
@@sections@@
<section><h2>Caspien: optimisations off against everything on</h2><p class="note">Execution time per program. &ldquo;natural&rdquo; is written with small functions and plain loops, nothing unrolled or inlined by hand.</p><div class="scroll">@@summary@@</div></section>
<section><h2>All numbers</h2><div class="scroll">@@table@@</div></section>
<p class="foot">Time is the fastest of @@runs@@ runs; memory is the median peak resident size; compile time is the median of @@builds@@ builds (wall clock, including the whole Caspien toolchain and the assembler/linker). f32 programs are compared with a single-precision C program, f64 programs with a double-precision one.</p>
</main>
<div id="tip"></div>
<script>
(function(){var t=document.getElementById('tip');
document.querySelectorAll('.row').forEach(function(r){
r.addEventListener('mousemove',function(e){t.textContent=r.getAttribute('data-tip');t.style.display='block';t.style.left=Math.min(e.clientX+14,window.innerWidth-220)+'px';t.style.top=(e.clientY+14)+'px'});
r.addEventListener('mouseleave',function(){t.style.display='none'});});
document.querySelectorAll('svg').forEach(function(s){if(s.getAttribute('viewBox')){var d=document.createElement('div');d.className='scroll';s.parentNode.insertBefore(d,s);d.appendChild(s);}});
})();
</script>
"""
legend = "\n".join('<span><i class="sw k-%s"></i>%s</span>' % (k, nm + (" (pale hatched = optimisations off, solid = everything on)" if k == "caspien" else ""))
                   for k, nm, lc, dc in LANGS)
page = page.replace("@@LIGHTVARS@@", "".join("--lang-%s:%s;" % (k, lc) for k, nm, lc, dc in LANGS))
page = page.replace("@@DARKVARS@@", "".join("--lang-%s:%s;" % (k, dc) for k, nm, lc, dc in LANGS))
page = page.replace("@@KCLASSES@@", "".join(".k-%s{--c:var(--lang-%s)}" % (k, k) for k, nm, lc, dc in LANGS))
for k, v in {"legend": legend, "n": "{:,}".format(n), "sections": "\n".join(sections), "summary": caspien_summary(), "table": table(), "runs": str(data["runs"]), "builds": str(data["builds"])}.items():
    page = page.replace("@@" + k + "@@", v)
open(dst, "w").write(page)
print("wrote", dst, len(page), "bytes")
