#!/usr/bin/env python3
"""Writes standalone SVG charts for the README into benchmarks/img/ (GitHub cannot run charts.html).

  img/time_<program>.svg and time_<program>-dark.svg   execution time of every language on that program (all 15 programs, no selection)
  img/overview_time.svg and overview_time-dark.svg   geometric mean of (time / C -O2 time) over every program where both exist, every language and every Caspien variant

The programs the README features are chosen by a fixed rule, not by hand: for each program take the ratio of Caspien's fastest
"everything on" variant to C -O2; the featured programs are the one with the lowest ratio, the median and the highest ratio.
`python3 export_svgs.py` prints the ratios and the selection. Needs the same results.json files as charts_all.py."""
import os, sys, math, html, re, runpy, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "img")
os.makedirs(OUT, exist_ok=True)
sys.argv = [sys.argv[0], os.path.join(tempfile.mkdtemp(), "unused.html")]  # charts_all.py writes its page to argv[1]; we only want its functions
G = runpy.run_path(os.path.join(HERE, "charts_all.py"))
chart, DATA, PROGS, LANGS, find, c_ref, fmt = (G[k] for k in ("chart", "DATA", "PROGS", "LANGS", "find", "c_ref", "fmt"))
OVERVIEW_ROWS, CAS_VARIANTS, GROUP_NAME = G["OVERVIEW_ROWS"], G["CAS_VARIANTS"], G["GROUP_NAME"]

FONT = 'font-family="-apple-system,Segoe UI,Helvetica,Arial,sans-serif"'
THEMES = {  # same values as the light and dark themes of charts.html
    "light": dict(card="#ffffff", line="#d2d8cf", fg="#1d2320", muted="#5d665f", grid="#dfe3dc", off="#cfe9d9", lang=1, cas="#2e9e5b"),
    "dark": dict(card="#1f2220", line="#3a413b", fg="#e8ece6", muted="#a1aaa2", grid="#343a35", off="#28402f", lang=3, cas=None),
}
THEME = "light"


def pal():
    t = dict(THEMES[THEME])
    if t["cas"] is None: t["cas"] = next(L[3] for L in LANGS if L[0] == "caspien")
    return t


def style():
    t = pal()
    lang = "".join(".k-%s{--c:%s}" % (L[0], L[t["lang"] if t["lang"] == 3 else 2]) for L in LANGS)
    return ("<style>.grid{stroke:%(grid)s;stroke-width:1}.noise{stroke:#e0a800;stroke-width:1.6}.tick{fill:%(muted)s;font-size:11px}"
            ".lbl{fill:%(fg)s;font-size:12.5px}.val{fill:%(fg)s;font-size:12px}.grp{fill:%(muted)s;font-size:10.5px}.hit{fill:transparent}"
            ".bar{fill:var(--c)}.bar.off{fill:url(#hatch);stroke:var(--c);stroke-width:1.2}.ttl{fill:%(fg)s;font-size:15px;font-weight:600}"
            ".sub{fill:%(muted)s;font-size:11.5px}" % t) + lang + "</style>"


def wrap(inner_svg, title, sub, foot):
    m = re.match(r'<svg viewBox="0 0 (\d+) (\d+)"[^>]*>(.*)</svg>\s*$', inner_svg, re.S)
    w, h, body = int(m.group(1)), int(m.group(2)), m.group(3)
    body = re.sub(r'<rect class="hit"[^>]*/>', "", body)
    body = re.sub(r' data-tip="[^"]*"', "", body)
    top, bot = 46, 44
    hatch = ('<defs><pattern id="hatch" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">'
             '<rect width="6" height="6" fill="%s"/><line x1="0" y1="0" x2="0" y2="6" stroke="%s" stroke-width="2.4"/></pattern></defs>' % (pal()["off"], pal()["cas"]))
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" height="%d" %s>%s%s'
            '<rect width="%d" height="%d" rx="8" fill="' + pal()["card"] + '" stroke="' + pal()["line"] + '"/>'
            '<text class="ttl" x="16" y="24">%s</text><text class="sub" x="16" y="40">%s</text><g transform="translate(0,%d)">%s</g>'
            '%s</svg>\n') % (
        w, h + top + bot, w, h + top + bot, FONT, style(), hatch, w, h + top + bot, html.escape(title), html.escape(sub), top, body,
        "".join('<text class="sub" x="16" y="%d">%s</text>' % (h + top + 18 + 14 * i, html.escape(l)) for i, l in enumerate(foot if isinstance(foot, list) else [foot])))


def time_svg(d, title, cnt):
    R = DATA[d]["results"]
    sub = "%s  |  fastest of %d runs, Linux 2-core VM; every output equals the C reference" % (cnt % "{:,}".format(DATA[d]["n"]), DATA[d]["runs"])
    foot = ["Green = Caspien (hatched = optimisations off), red = native, purple = JVM/.NET/Wasm, blue = interpreted.",
            "Gold lines: best time -/+ typical run-to-run noise (median repeat minus best)."]
    return wrap(chart(R, "time_s", "Execution time, " + title), "Execution time: " + title, sub, foot)


def common_programs():
    """Programs on which every row of the overview exists: C -O2, and Caspien naive and optimized safe in both configurations. Every row uses exactly this set."""
    out = []
    for d, t, cnt, naive, opts, what in PROGS:
        if d not in DATA or d == "helloworld" or not opts: continue
        R = DATA[d]["results"]
        if c_ref(R) and all(find(R, pre, m) for pre in (naive, opts[0][1]) for m in ("off", "full")): out.append(d)
    return out


def geomeans():
    rows, common = [], common_programs()
    def gm(getr):
        rs = []
        for d, t, cnt, naive, opts, what in PROGS:
            if d not in common: continue
            R = DATA[d]["results"]; c2 = c_ref(R); r = getr(R, naive, opts)
            if not (r and c2 and r["time_s"] > 0 and c2["time_s"] > 0): return None
            rs.append(r["time_s"] / c2["time_s"])
        return math.exp(sum(map(math.log, rs)) / len(rs))
    for lab, key, cands in OVERVIEW_ROWS:
        g = gm(lambda R, naive, opts, cands=cands: next((r for c in cands for r in R if r["label"] == c), None))
        if g: rows.append((lab, key, "", g, len(common)))
    for lab, pick in CAS_VARIANTS[:2]:  # naive and optimized safe; the unsafe variants exist on only some programs, see the per-program charts
        for mode in ("off", "full"):
            g = gm(lambda R, naive, opts, pick=pick, mode=mode: find(R, pick(naive, opts), mode))
            if g: rows.append(("%s, %s" % (lab, "everything on" if mode == "full" else "optimisations off"), "caspien", mode, g, len(common)))
    return sorted(rows, key=lambda r: r[3])


def overview_svg():
    rows = geomeans()
    W, LBL, BH, GAP, top = 900, 300, 17, 6, 8
    plot = W - LBL - 150
    vmax = max(r[3] for r in rows)
    sx = lambda v: plot * v / (math.ceil(vmax))
    H = top + len(rows) * (BH + GAP) + 26
    o = ['<svg viewBox="0 0 %d %d">' % (W, H)]
    t = 0
    while t <= math.ceil(vmax):
        x = LBL + sx(t)
        o.append('<line class="grid" x1="%.1f" x2="%.1f" y1="%d" y2="%d"/><text class="tick" x="%.1f" y="%d" text-anchor="middle">%gx</text>' % (x, x, top - 2, H - 22, x, H - 8, t))
        t += 1
    x1 = LBL + sx(1)
    for i, (lab, key, mode, gm, n) in enumerate(rows):
        y = top + i * (BH + GAP)
        cls = "k-%s%s" % (key, " off" if mode == "off" else "")
        w = max(sx(gm), 1.5)
        o.append('<g class="row"><text class="lbl" x="%d" y="%.1f" text-anchor="end">%s</text><rect class="bar %s" x="%d" y="%d" width="%.1f" height="%d" rx="3"/>'
                 '<text class="val" x="%.1f" y="%.1f">%.2fx</text></g>' % (LBL - 8, y + BH * 0.72, html.escape(lab), cls, LBL, y, w, BH, LBL + w + 6, y + BH * 0.72, gm))
    o.append("</svg>")
    sub = "Geometric mean of time / C -O2 time over the same %d programs for every row. Lower is faster; 1x = C -O2." % len(common_programs())
    foot = ["Green = Caspien (hatched = optimisations off, solid = everything on), red = native, purple = JVM/.NET/Wasm, blue = interpreted.",
            "Fastest of 3 runs, Linux 2-core VM. Hello World and Mandelbrot (one Caspien variant) are excluded; unsafe variants are in the per-program charts."]
    return wrap("".join(o), "Caspien against 23 other languages: execution time over %d programs" % len(common_programs()), sub, foot)


def selection():
    out = []
    for d, t, cnt, naive, opts, what in PROGS:
        if d not in DATA or d == "helloworld": continue
        R = DATA[d]["results"]; c2 = c_ref(R)
        c = [r for r in R if r["label"].startswith("Caspien") and r["label"].endswith("· full") and r.get("ok", True)]
        if not c or not c2: continue
        b = min(c, key=lambda r: r["time_s"])
        out.append((b["time_s"] / c2["time_s"], d, t, b["label"]))
    out.sort()
    return out


if __name__ == "__main__":
    for THEME in ("light", "dark"):
        suf = "" if THEME == "light" else "-dark"
        for d, title, cnt, *_ in PROGS:
            if d in DATA: open(os.path.join(OUT, "time_%s%s.svg" % (d, suf)), "w").write(time_svg(d, title, cnt))
        open(os.path.join(OUT, "overview_time%s.svg" % suf), "w").write(overview_svg())
    sel = selection()
    print("Caspien fastest 'everything on' variant / C -O2, per program (low = faster than C):")
    for r, d, t, lab in sel: print("  %5.2fx  %-14s %s" % (r, d, lab))
    print("README selection (rule: lowest ratio, median, highest ratio): %s, %s, %s" % (sel[0][1], sel[len(sel) // 2][1], sel[-1][1]))
