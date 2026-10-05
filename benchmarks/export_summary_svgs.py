#!/usr/bin/env python3
"""Summary bar charts like img/overview_time.svg for all four metrics (time, peak memory, compile time, executable size):
  summary_<metric>_vs_c        every language, geometric mean of metric / C -O2 on the same program
  summary_<metric>_safe_vs_rust  memory-safe implementations only (charts_all.py's safe filter), geometric mean of metric / Rust -O (free build)
Usage: CHART_RESULTS=results.quick.json python3 export_summary_svgs.py [outdir]   (default results.json, outdir benchmarks/img)
Per row the mean runs over the programs where the row and the reference both have a figure (a time cut off in a quick run is a lower bound, marked >=)."""
import os, sys, math, html, json
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
ARGV_OUT = sys.argv[1] if len(sys.argv) > 1 else None   # export_svgs rewrites sys.argv on import
import export_svgs as E
G = E.G
DATA, PROGS, find, c_ref, known = E.DATA, E.PROGS, E.find, E.c_ref, G["known"]
safe_row, SAFE_LANGS, OVERVIEW_ROWS, CAS_VARIANTS = G["safe_row"], G["SAFE_LANGS"], E.OVERVIEW_ROWS, E.CAS_VARIANTS
REF_ROWS = [("Caspien ref naive", "Caspien ref naive"), ("Caspien ref safe", "Caspien ref safe"), ("Caspien ref unsafe", "Caspien ref unsafe"),
            ("Caspien naive, ref HashMap", "Caspien naive, ref in stdlib")]
METRICS = [("time_s", "Execution time"), ("rss_kb", "Peak memory"), ("compile_s", "Compile time"), ("size_bytes", "Executable size")]


def rust_ref(R):
    return next((r for c in ("Rust -O (free)", "Rust -O") for r in R if r["label"] == c), None)


def rows_for(metric, safe, refget):
    progs = [p for p in PROGS if p[0] in DATA and p[0] != "helloworld"]
    cands = []
    for lab, key, cs in OVERVIEW_ROWS:
        if safe and key not in SAFE_LANGS: continue
        cands.append((lab, key, "", lambda R, naive, opts, cs=cs: next((r for c in cs for r in R if r["label"] == c), None)))
    for lab, pick in CAS_VARIANTS:
        if safe and "unsafe" in lab: continue
        for mode in ("off", "full"):
            cands.append(("%s, %s" % (lab, "everything on" if mode == "full" else "optimisations off"), "caspien", mode,
                          lambda R, naive, opts, pick=pick, mode=mode: (lambda p: find(R, p, mode) if p else None)(pick(naive, opts))))
    # the `ref` variants exist on a few programs only (graph, binarytrees, lru; the ref-in-stdlib one on lru and knucleotide): quick runs measure just "full"
    for lab, prefix in REF_ROWS:
        if safe and "unsafe" in lab: continue
        cands.append((lab, "caspien", "full", lambda R, naive, opts, prefix=prefix: find(R, prefix, "full")))
    out = []
    for lab, key, mode, getr in cands:
        ratios, low = [], False
        for d, t, cnt, naive, opts, what in progs:
            R = DATA[d]["results"]; ref = refget(R); r = getr(R, naive, opts)
            if r and safe and not safe_row(d, r): r = None
            if not (r and ref and known(r, metric) and known(ref, metric) and r[metric] > 0 and ref[metric] > 0): continue
            ratios.append(r[metric] / ref[metric])
            low = low or bool(r.get("cutoff") and metric == "time_s")
        if len(ratios) >= (2 if lab in dict(REF_ROWS) else 3):
            if lab in dict(REF_ROWS): lab = "%s [%d programs]" % (lab, len(ratios))
            out.append((lab, key, mode, math.exp(sum(map(math.log, ratios)) / len(ratios)), len(ratios), low))
    return sorted(out, key=lambda r: r[3])


def svg(rows, title, sub, foot, refname):
    W, LBL, BH, GAP, top = 900, 300, 17, 6, 8
    plot = W - LBL - 150
    vmax, vmin = max(r[3] for r in rows), min(r[3] for r in rows)
    logax = vmax > 12 or vmin < 0.2   # a wide spread (compile time, executable size) gets a log axis, bars grow from the left edge
    o = ['<svg viewBox="0 0 %d %d">' % (W, 0)]
    if logax:
        lo, hi = math.floor(math.log10(min(vmin, 1))), math.ceil(math.log10(max(vmax, 1.01)))
        sx = lambda v: plot * (math.log10(v) - lo) / (hi - lo)
        ticks = [(10.0 ** e, "%gx" % 10.0 ** e) for e in range(lo, hi + 1)]
    else:
        step = 1 if vmax <= 12 else 2
        top_t = math.ceil(vmax / step) * step
        sx = lambda v: plot * v / top_t
        ticks = [(float(t), "%gx" % t) for t in range(0, top_t + 1, step)]
    H = top + len(rows) * (BH + GAP) + 26
    o = ['<svg viewBox="0 0 %d %d">' % (W, H)]
    for v, txt in ticks:
        x = LBL + (sx(v) if v > 0 else 0)
        o.append('<line class="grid" x1="%.1f" x2="%.1f" y1="%d" y2="%d"/><text class="tick" x="%.1f" y="%d" text-anchor="middle">%s</text>' % (x, x, top - 2, H - 22, x, H - 8, txt))
    if logax:
        o.append('<text class="tick" x="%d" y="%d" text-anchor="end">log scale</text>' % (W - 8, H - 8))
    for i, (lab, key, mode, gm, n, low) in enumerate(rows):
        y = top + i * (BH + GAP); w = max(sx(gm), 1.5)
        cls = "k-%s%s" % (key, " off" if mode == "off" else "")
        o.append('<g class="row"><text class="lbl" x="%d" y="%.1f" text-anchor="end">%s</text><rect class="bar %s" x="%d" y="%d" width="%.1f" height="%d" rx="3"/>'
                 '<text class="val" x="%.1f" y="%.1f">%s%.2fx</text></g>' % (LBL - 8, y + BH * 0.72, html.escape(lab), cls, LBL, y, w, BH, LBL + w + 6, y + BH * 0.72, "&gt;=" if low else "", gm))
    o.append("</svg>")
    return E.wrap("".join(o), title, sub, foot)


if __name__ == "__main__":
    out = ARGV_OUT or E.OUT
    os.makedirs(out, exist_ok=True)
    made = []
    for E.THEME in ("light", "dark"):
        suf = "" if E.THEME == "light" else "-dark"
        for m, mname in METRICS:
            for safe in (False, True):
                refget = rust_ref if safe else c_ref
                refname = "Rust -O (free build)" if safe else "C -O2"
                rows = rows_for(m, safe, refget)
                title = "%s: %s, relative to %s" % (mname, "memory-safe implementations" if safe else "every language", refname)
                sub = "Geometric mean of %s / %s on the same program, over the programs where both exist. Lower is better; 1x = %s." % (mname.lower(), refname, refname)
                foot = ["Green = Caspien (hatched = optimisations off, solid = everything on), red = native, purple = JVM/.NET/Wasm, blue = interpreted.",
                        ("Safe filter: Caspien without unsafe-dynarray variants, GC/runtime-safe languages, Rust where rustc accepts the port under -F unsafe_code." if safe else
                         "C, C++ and Rust: free build. Caspien variants that exist only on some programs are averaged over those programs.") + ("  >= : time cut off in a quick run (lower bound)." if m == "time_s" else "")]
                name = "summary_%s_%s%s.svg" % (m, "safe_vs_rust" if safe else "vs_c", suf)
                open(os.path.join(out, name), "w").write(svg(rows, title, sub, foot, refname)); made.append(name)
    print("\n".join(made))
