#!/usr/bin/env python3
"""
One page with the four benchmark metrics (execution time, compile time, peak memory, executable size) for every benchmark program, from
the results.json files that nbody/bench.py, bench_program.py and bench_suite.py write. The page is grouped METRIC first, then
benchmark: each metric section opens with one table (every language x every program, clickable headers sort it, the last column is the
geometric mean of the ratio to C -O2) followed by that metric's bar chart for each program.

    python3 benchmarks/charts_all.py [out.html]      (default benchmarks/charts.html)

Colours are by GROUP: Caspien green, bare metal (C, C++, Rust, Go, Fortran, Objective-C, Odin, Zig, Chapel) red, natively compiled with GC/refcounting/runtime
(D, Nim, Crystal, OCaml, Swift, Codon) amber, VMs (Java, C#, Kotlin) purple, JavaScript engines and LuaJIT blue, WebAssembly teal. Languages inside a group are different shades of the group
colour, and every row also names its language and group in words, so colour is never the only cue. Caspien is shown with every optimisation
off (pale, hatched) and with everything on (solid).
"""
import html, json, math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
_args = list(sys.argv[1:])
RESULTS_NAME = "results.json"
if "--results" in _args:   # e.g. --results results.quick.json (a --caspien-only run merged with the existing other-language rows)
    _i = _args.index("--results")
    RESULTS_NAME = _args[_i + 1]
    del _args[_i:_i + 2]
dst = _args[0] if _args else os.path.join(HERE, "charts.html")
PROGS = [  # (dir, title, count label, naive prefix, [(column label, prefix)] for optimized rows, description)
    ("nbody", "N-body", "%s steps", "Caspien natural f64", [("optimized", "Caspien scalars f64")], "naive = small functions and plain loops; optimized = hand-unrolled scalar statics (f64)"),
    ("fannkuchredux", "Fannkuch-redux", "n = %s", "Caspien naive", [("optimized", "Caspien optimized")], "naive = helper functions; optimized = everything hand-inlined into main"),
    ("spectralnorm", "Spectral-norm", "N = %s", "Caspien naive", [("optimized", "Caspien optimized")], "naive = A(i,j) through a function; optimized = incremental denominator, no calls"),
    ("sieve", "Sieve of Eratosthenes", "primes up to %s", "Caspien naive (DynamicArray)", [("optimized safe", "Caspien optimized safe"), ("optimized unsafe", "Caspien optimized unsafe")],
     "naive = stdlib DynamicArray<u8> (get/set under bounds proofs); optimized safe = safe dynarray with bounds proofs; optimized unsafe = unsafe dynarray, no checks. C, C++ and Rust: free = release memory at the end, leak = do not"),
    ("strings", "String manipulation", "%s characters", "Caspien naive (String class)", [("optimized safe", "Caspien optimized safe"), ("optimized unsafe", "Caspien optimized unsafe")],
     "naive = stdlib String (appendChar/charAt/setCharAt per character); optimized = raw byte buffers, safe (bounds proofs) or unsafe. C, C++ and Rust: free or leak"),
    ("graph", "Heap graph search", "%s nodes", "Caspien naive (DynamicArray)", [("optimized safe", "Caspien optimized safe"), ("optimized unsafe", "Caspien optimized unsafe"), ("struct nodes, safe", "Caspien struct nodes, safe"), ("struct nodes, unsafe", "Caspien struct nodes, unsafe")],
     "N nodes on the heap with four edges each, find a needle by breadth-first search. The other languages allocate every node separately and link them with pointers; Caspien cannot hold a typed node pointer inside a node (recursive structs are rejected on purpose), so its edges are INDEX edges: in parallel arrays (naive/optimized rows) or in one dynarray of Node structs (struct-node rows)"),
    ("sorting", "Sorting and searching", "%s elements", "Caspien naive (DynamicArray)", [("optimized safe", "Caspien optimized safe"), ("optimized unsafe", "Caspien optimized unsafe")],
     "quicksort, merge sort and heap sort of N random values, N binary searches and 20 linear searches. Caspien elements are u64 (the others use 32-bit ints)"),
    ('binarytrees', 'Binary trees', 'depth %s', 'Caspien naive (DynamicArray)', [('optimized safe', 'Caspien optimized safe'), ('optimized unsafe', 'Caspien optimized unsafe')], 'allocate, walk and free many complete binary trees (a stretch tree, one long-lived tree, then 2^(N-d+4) trees of each depth d = 4, 6, .., N). The other languages use pointer nodes; Caspien, which rejects recursive structs on purpose, uses an index-based tree (children are indices, 0 = null) in a dynarray of Node structs with a free list and an explicit work stack. Naive uses one stdlib DynamicArray<Node> (get plus the pointer-taking setPtr)'),
    ('mandelbrot', 'Mandelbrot', '%s per side grid', 'Caspien f64 scalars', [], 'escape-time count over an N x N grid, at most 100 iterations per point; f64 scalar maths, no arrays or allocation (a single Caspien variant)'),
    ('fasta', 'FASTA generation', '%s nucleotides', 'Caspien naive (DynamicArray)', [('optimized safe', 'Caspien optimized safe'), ('optimized unsafe', 'Caspien optimized unsafe')], 'LCG-driven repeat and weighted random sequences written into one heap buffer; prints length, checksum and letter counts (integer thresholds, no floating point)'),
    ('knucleotide', 'k-nucleotide (hash map)', '%s bases', 'Caspien naive (stdlib HashMap)', [('optimized safe', 'Caspien optimized safe'), ('optimized unsafe', 'Caspien optimized unsafe')], 'count k-mer frequencies for k = 1, 2, 3, 4, 6, 12 in a generated DNA sequence. C and the optimized Caspien variants use a hand-written open-addressing table; the other languages use their idiomatic maps; Caspien naive uses the stdlib HashMap. The LCG period is 139,968, so the tables stay small and cache-friendly'),
    ('lru', 'LRU cache', '%s operations', 'Caspien naive (DynamicArray + HashMap)', [('optimized safe', 'Caspien optimized safe'), ('optimized unsafe', 'Caspien optimized unsafe')], 'capacity 262,144 over 1,048,576 keys, 75% of operations on a hot subset, hash map plus recency list. Optimized Caspien: index-based doubly linked list and chained hash map in dynarrays; naive: stdlib DynamicArray + HashMap (no remove, evicted keys map to a sentinel)'),
    ('merkletrees', 'Merkle tree', '%s leaves', 'Caspien naive (DynamicArray)', [('optimized safe', 'Caspien optimized safe'), ('optimized unsafe', 'Caspien optimized unsafe')], 'build a Merkle tree over N leaves, then generate and verify N/2 proofs (every 4th tampered). The hash is real SHA-256 (FIPS 180-4, hand-written in every language, no crypto library): one compression per leaf, two per internal node'),
    ('helloworld', 'Hello World', 'N ignored (%s)', 'Caspien plain printf', [], 'print one line: measures start-up time, compile time and binary size'),
    ('json_serde', 'JSON serialise + parse', '%s records', 'Caspien naive (DynamicArray)', [('optimized safe', 'Caspien optimized safe'), ('optimized unsafe', 'Caspien optimized unsafe')], 'hand-written serialiser writes N records to one text buffer, a hand-written iterative parser reads them back; every language does the same work (no JSON library). Naive keeps the records in one stdlib DynamicArray<Rec>'),
]
GROUP_NAME = {"caspien": "Caspien", "bare": "Bare metal / native-compiled", "native": "Bare metal / native-compiled", "vm": "Compiled + interpreted (VM)",
              "js": "Interpreted", "luajit": "Interpreted", "wasm": "Compiled + interpreted (VM)"}
def _mix(h, t):
    r, g, b = (int(h[i:i + 2], 16) for i in (1, 3, 5))
    return "#%02x%02x%02x" % tuple(round(c + (255 - c) * t) for c in (r, g, b))
# language -> (display name, light colour); the colour FAMILY is the group's, shades tell the languages inside it apart
_L = [("caspien", "Caspien", "#2e9e5b"),
      ("c", "C", "#d9453d"), ("cpp", "C++", "#ef7a6e"), ("rust", "Rust", "#a82a24"), ("go", "Go", "#f2a39a"), ("fortran", "Fortran", "#7d1f1a"),
      ("objc", "Objective-C", "#bd5e55"), ("odin", "Odin", "#e8745d"), ("zig", "Zig", "#c94a6a"), ("chapel", "Chapel", "#f5b8ae"),
      ("ldc", "D (LDC)", "#e0523f"), ("gdc", "D (GDC)", "#f08a7a"), ("nim", "Nim", "#b8322c"), ("crystal", "Crystal", "#d86a5a"),
      ("ocaml", "OCaml", "#f7c4bb"), ("swift", "Swift", "#ca3a52"), ("codon", "Codon", "#96302a"),
      ("java", "Java", "#7a3fb8"), ("csharp", "C#", "#5f45c8"), ("kotlin", "Kotlin", "#b06ad8"), ("wasm", "WebAssembly", "#9a7ee0"),
      ("node", "Node", "#2f6fd6"), ("bun", "Bun", "#7fb0f2"), ("luajit", "LuaJIT", "#1d4aa8")]
LANGS = [(k, n, c, _mix(c, 0.22)) for k, n, c in _L]
LEGEND_GROUPS = [("caspien", "Caspien (hatched = optimisations off / naive, solid = everything on)"),
                 ("c", "Bare metal / native-compiled: C, C++, Rust, Go, Fortran, Objective-C, Odin, Zig, Chapel, D, Nim, Crystal, OCaml, Swift, Codon (reds)"),
                 ("java", "Compiled and interpreted (VM): Java, C#, Kotlin, WebAssembly (purples)"), ("node", "Interpreted: Node, Bun, LuaJIT (blues)")]
_LABEL_KEYS = [("C++", "cpp"), ("C#", "csharp"), ("C ", "c"), ("Chapel", "chapel"), ("Codon", "codon"), ("Crystal", "crystal"), ("D (LDC)", "ldc"),
               ("D (GDC)", "gdc"), ("Fortran", "fortran"), ("Kotlin", "kotlin"), ("Nim", "nim"), ("Objective-C", "objc"), ("OCaml", "ocaml"),
               ("Odin", "odin"), ("Rust", "rust"), ("Swift", "swift"), ("WebAssembly", "wasm"), ("Zig", "zig"), ("Go", "go"), ("Java", "java"),
               ("Node", "node"), ("Bun", "bun"), ("LuaJIT", "luajit")]


def lang_key(r):
    l = r["label"]
    if l.startswith("Caspien"): return "caspien"
    for pre, k in _LABEL_KEYS:
        if l == pre or l.startswith(pre + " ") or (pre.endswith(" ") and l.startswith(pre)):
            return k
    raise SystemExit("no colour for " + l)
METRICS = [
    ("time_s", "Execution time", "lower is better"),
    ("rss_kb", "Peak memory (RSS)", "lower is better"),
    ("compile_s", "Compile time", "lower is better; Node and Bun have no compile step"),
    ("size_bytes", "Executable size", "lower is better; log scale. Java, Node and Bun: the runtime plus the program"),
]
LOG = {"size_bytes"}


def fmt(metric, v):
    if metric in ("time_s", "compile_s"):
        return "%.2f s" % v if v >= 0.005 else ("0 s" if v == 0 else "%.3f s" % v)
    if metric == "rss_kb":
        return "%.1f MB" % (v / 1024.0)
    if v >= 1 << 20:
        return "%.1f MB" % (v / (1 << 20))
    if v >= 1 << 10:
        return "%.1f KB" % (v / (1 << 10))
    return "%d B" % v


def known(r, metric):
    """A cut-off row (killed at the slowest optimised Caspien time) has a time, a compile time and a size, but no peak memory."""
    return not (r.get("cutoff") and metric == "rss_kb")


def vtext(r, metric):
    return ("> " if r.get("cutoff") and metric == "time_s" else "") + fmt(metric, r[metric])


def chart(R, metric, title):
    rows = sorted([r for r in R if known(r, metric)], key=lambda r: (r[metric], r["label"]))
    vmax = max(r[metric] for r in rows) or 1
    vmin = min([r[metric] for r in rows if r[metric] > 0] or [1])
    W, LBL, RIGHT, BH, GAP, top = 900, 270, 190, 17, 6, 8
    H = top + len(rows) * (BH + GAP) + 26
    plot = W - LBL - RIGHT
    ticks = []
    if metric in LOG:
        lo = 10 ** math.floor(math.log10(vmin)); hi = 10 ** math.ceil(math.log10(vmax))
        lo_l, hi_l = math.log10(lo), math.log10(hi)
        sx = lambda v: plot * (math.log10(max(v, lo)) - lo_l) / (hi_l - lo_l)
        e = lo
        while e <= hi * 1.001:
            ticks.append((e, ('%g KB' % (e / 1e3)) if e < 1e6 else ('%g MB' % (e / 1e6)))); e *= 10
    else:
        def nice(x):
            p = 10 ** math.floor(math.log10(x))
            for m in (1, 2, 2.5, 5, 10):
                if x <= m * p:
                    return m * p
        unit = 1024.0 if metric == "rss_kb" else 1.0
        st = nice(vmax / unit / 5) * unit; top_v = st * math.ceil(vmax / st)
        sx = lambda v: plot * v / top_v
        k = 0
        while k * st <= top_v * 1.001:
            ticks.append((k * st, None)); k += 1
    out = ['<svg viewBox="0 0 %d %d" role="img" aria-label="%s, one bar per implementation">' % (W, H, html.escape(title))]
    for v, t in ticks:
        x = LBL + sx(v)
        out.append('<line class="grid" x1="%.1f" x2="%.1f" y1="%d" y2="%d"/>' % (x, x, top - 2, H - 22))
        if t is None:
            t = "%g" % round(v / 1024.0 if metric == "rss_kb" else v, 4)
        out.append('<text class="tick" x="%.1f" y="%d" text-anchor="middle">%s</text>' % (x, H - 8, html.escape(t)))
    for i, r in enumerate(rows):
        y = top + i * (BH + GAP); v = r[metric]
        w = max(sx(v), 1.5) if v > 0 else 1.5
        ts = r.get("times") if metric == "time_s" else None
        # typical noise = how much slower the median repeat is than the best one (the bar is the best of the repeats); one stray slow repeat does not move it
        noise = (sorted(ts)[len(ts) // 2] - min(ts)) if ts and len(ts) > 2 else ((sum(ts) / len(ts) - min(ts)) if ts and len(ts) == 2 else 0)
        spread = (max(ts) - min(ts)) if ts and len(ts) > 1 else 0
        cls = "k-" + r["key"] + (" off" if r["mode"] == "off" else "")
        tip = "%s\n%s: %s%s\ngroup: %s%s" % (r["label"], title, vtext(r, metric) + (" -- killed there: would have taken longer than optimized Caspien" if r.get("cutoff") and metric == "time_s" else ""), (" (gold lines: ±%s = median repeat minus best; slowest repeat +%s)" % (fmt(metric, noise), fmt(metric, spread))) if spread else "", GROUP_NAME[r["kind"]],
                                           "" if r.get("ok", True) else "\nOUTPUT DIFFERS from the C reference")
        out.append('<g class="row" data-tip="%s">' % html.escape(tip, quote=True))
        out.append('<rect class="hit" x="0" y="%d" width="%d" height="%d"/>' % (y - GAP // 2, W, BH + GAP))
        out.append('<text class="lbl" x="%d" y="%.1f" text-anchor="end">%s</text>' % (LBL - 8, y + BH * 0.72, html.escape(r["label"])))
        out.append('<rect class="bar %s" x="%d" y="%d" width="%.1f" height="%d" rx="3"/>' % (cls, LBL, y, w, BH))
        tx = w
        if noise and noise > 0 and v > 0:  # two gold lines at the best time -/+ the typical noise (median repeat minus best)
            for xv in (max(v - noise, 0), v + noise):
                gx = LBL + min(max(sx(xv), 0), plot * 1.04)
                out.append('<line class="noise" x1="%.1f" x2="%.1f" y1="%d" y2="%d"/>' % (gx, gx, y, y + BH))
                tx = max(tx, gx - LBL)
        out.append('<text class="val" x="%.1f" y="%.1f">%s <tspan class="grp">%s</tspan></text>' % (
            LBL + tx + 6, y + BH * 0.72, html.escape(vtext(r, metric) + (" (cut off)" if r.get("cutoff") and metric == "time_s" else "")), html.escape(GROUP_NAME[r["kind"]])))
        out.append('</g>')
    out.append('</svg>')
    return "\n".join(out)



METRIC_ID = {"time_s": "time", "compile_s": "compile", "rss_kb": "memory", "size_bytes": "size"}
METRIC_ORDER = ["time_s", "compile_s", "rss_kb", "size_bytes"]
MET = {m[0]: m for m in METRICS}


def find(R, prefix, mode):
    for r in R:
        if r["label"] == "%s · %s" % (prefix, mode):
            return r


def c_ref(R):
    for lab in ("C -O2", "C -O2 (free)"):
        r = next((r for r in R if r["label"] == lab), None)
        if r:
            return r


def summary(R, naive, opts):
    """Caspien naive / optimized variants x off/full, all four metrics, against the best C build."""
    c2 = c_ref(R)
    t = ['<table class="sortable"><thead><tr>' + "".join(th(h, ty, n) for h, ty, n in (("Caspien version", "text", False), ("Optimisations", "text", False), ("Time", "num", True), ("Memory", "num", True), ("Compile", "num", True), ("Size", "num", True), ("Time vs C -O2", "num", True))) + '</tr></thead><tbody>']
    i = 0
    for kind, prefix in [("naive", naive)] + opts:
        for mode, mname in (("off", "off"), ("full", "everything on")):
            r = find(R, prefix, mode)
            if not r:
                continue
            ratio = r["time_s"] / c2["time_s"] if c2 and c2["time_s"] > 0 else None
            t.append('<tr data-i="%d"><td data-v="%s">%s</td><td data-v="%s">%s</td>%s%s%s%s<td class="n" data-v="%s">%s</td></tr>' % (
                i, kind, kind, mname, mname,
                num(r["time_s"], fmt("time_s", r["time_s"])), num(r["rss_kb"], fmt("rss_kb", r["rss_kb"])), num(r["compile_s"], fmt("compile_s", r["compile_s"])),
                num(r["size_bytes"], fmt("size_bytes", r["size_bytes"])), "" if ratio is None else "%.4f" % ratio, "-" if ratio is None else "%.1f×" % ratio))
            i += 1
    t.append("</tbody></table>")
    return "\n".join(t)


def th(label, ty, numeric):
    return '<th data-type="%s"%s><button type="button">%s</button></th>' % (ty, ' class="n"' if numeric else "", html.escape(label))


def num(v, text, extra="", bad=False):
    return '<td class="n%s" data-v="%s">%s%s</td>' % (" bad" if bad else "", "" if v is None else repr(float(v)), text, extra)


def table(R):
    cols = (("Implementation", "text", False), ("Group", "text", False), ("Time", "num", True), ("Memory", "num", True), ("Compile", "num", True), ("Size", "num", True), ("Output", "text", False), ("Matches C", "text", False))
    t = ['<table class="sortable"><thead><tr>' + "".join(th(*c) for c in cols) + '</tr></thead><tbody>']
    for i, r in enumerate(sorted(R, key=lambda r: (r["kind"], r["label"]))):
        t.append('<tr data-i="%d"><td data-v="%s"><span class="sw k-%s%s"></span>%s</td><td data-v="%s">%s</td>%s%s%s%s<td class="mono" data-v="%s">%s</td><td data-v="%s">%s</td></tr>' % (
            i, html.escape(r["label"]), r["key"], " off" if r["mode"] == "off" else "", html.escape(r["label"]), GROUP_NAME[r["kind"]], GROUP_NAME[r["kind"]],
            num(r["time_s"], vtext(r, "time_s") + (" (cut off)" if r.get("cutoff") else "")), num(r["rss_kb"] if known(r, "rss_kb") else None, fmt("rss_kb", r["rss_kb"]) if known(r, "rss_kb") else "-"), num(r["compile_s"], fmt("compile_s", r["compile_s"])), num(r["size_bytes"], fmt("size_bytes", r["size_bytes"])),
            html.escape(" ".join(r["output"])), html.escape(" ".join(r["output"])), "yes" if r.get("ok") else "no", ("n/a (cut off)" if r.get("cutoff") else "yes") if r.get("ok") else "<b>no</b>"))
    t.append("</tbody></table>")
    return "\n".join(t)


# (row label, swatch key, [candidate labels, first that exists wins]); free builds for C, C++ and Rust
OVERVIEW_ROWS = [
    ("C -O2", "c", ["C -O2", "C -O2 (free)"]), ("C++ -O2", "cpp", ["C++ -O2", "C++ -O2 (free)"]), ("Rust -O", "rust", ["Rust -O", "Rust -O (free)"]), ("Go", "go", ["Go"]),
    ("Fortran", "fortran", ["Fortran"]), ("Objective-C", "objc", ["Objective-C"]), ("Odin", "odin", ["Odin"]), ("Zig", "zig", ["Zig"]), ("Chapel", "chapel", ["Chapel"]),
    ("D (LDC)", "ldc", ["D (LDC)"]), ("D (GDC)", "gdc", ["D (GDC)"]), ("Nim", "nim", ["Nim"]), ("Crystal", "crystal", ["Crystal"]), ("OCaml", "ocaml", ["OCaml"]),
    ("Swift", "swift", ["Swift"]), ("Codon", "codon", ["Codon"]),
    ("Java", "java", ["Java"]), ("C#", "csharp", ["C#"]), ("Kotlin", "kotlin", ["Kotlin"]),
    ("Node", "node", ["Node"]), ("Bun", "bun", ["Bun"]), ("LuaJIT", "luajit", ["LuaJIT"]), ("WebAssembly", "wasm", ["WebAssembly"]),
]
CAS_VARIANTS = [("Caspien naive", lambda naive, opts: naive), ("Caspien optimized (safe)", lambda naive, opts: opts[0][1] if opts else None),
                ("Caspien optimized (unsafe)", lambda naive, opts: opts[1][1] if len(opts) > 1 else None)]


def overview(DATA, metric):
    """One metric of every language on every program, plus the geometric mean of the ratio to C -O2 on the same program. Headers sort."""
    progs = [p for p in PROGS if p[0] in DATA]
    cols = [("Implementation", "text", False), ("Group", "text", False)] + [(p[1], "num", True) for p in progs] + [("Geomean vs C -O2", "num", True)]
    out = []
    def row(i, label, key, group, off, getr):
        cs, ratios, nlow = [], [], 0
        for d, t, cnt, naive, opts, what in progs:
            R = DATA[d]["results"]; c2 = c_ref(R); r = getr(R, naive, opts)
            if not r or not known(r, metric):
                cs.append('<td class="n" data-v="">-</td>'); continue
            ratio = r[metric] / c2[metric] if c2 and c2[metric] > 0 and r[metric] > 0 else None
            if ratio: ratios.append(ratio)
            lower = r.get("cutoff") and metric == "time_s"   # killed at the limit: the true time is longer, so the ratio is a lower bound
            nlow += 1 if lower and ratio else 0
            cs.append(num(r[metric], vtext(r, metric), ("  <span class=\"grp\">%s%.1f×</span>" % ("&ge;" if lower else "", ratio)) if ratio else "", not r.get("ok", True)))
        gm = math.exp(sum(math.log(x) for x in ratios) / len(ratios)) if ratios else None
        cs.append('<td class="n" data-v="%s"><b>%s</b></td>' % ("" if gm is None else "%.4f" % gm, "-" if gm is None else "%s%.2f×" % ("&ge;" if nlow else "", gm)))
        if all('data-v=""' in c for c in cs[:-1]):
            return
        out.append('<tr data-i="%d"><td data-v="%s"><span class="sw k-%s%s"></span>%s</td><td data-v="%s">%s</td>%s</tr>' % (
            i, html.escape(label), key, " off" if off else "", html.escape(label), group, group, "".join(cs)))
    i = 0
    for lab, key, cands in OVERVIEW_ROWS:
        gname = GROUP_NAME[{"c": "bare", "cpp": "bare", "rust": "bare", "go": "bare", "fortran": "bare", "objc": "bare", "odin": "bare", "zig": "bare", "chapel": "bare",
                            "ldc": "native", "gdc": "native", "nim": "native", "crystal": "native", "ocaml": "native", "swift": "native", "codon": "native",
                            "java": "vm", "csharp": "vm", "kotlin": "vm", "node": "js", "bun": "js", "luajit": "luajit", "wasm": "wasm"}[key]]
        row(i, lab, key, gname, False, lambda R, naive, opts, cands=cands: next((r for c in cands for r in R if r["label"] == c), None)); i += 1
    for lab, pick in CAS_VARIANTS:
        for mode, mname in (("off", "optimisations off"), ("full", "everything on")):
            row(i, "%s, %s" % (lab, mname), "caspien", "Caspien", mode == "off",
                lambda R, naive, opts, pick=pick, mode=mode: (lambda pre: find(R, pre, mode) if pre else None)(pick(naive, opts))); i += 1
    return ('<table class="sortable ov"><thead><tr>%s</tr></thead><tbody>%s</tbody></table><p class="note"><button type="button" class="reset">Reset order</button> '
            'Click a column header to sort by it (click again to reverse). The grey figure is the ratio to C -O2 on the same program; the last column is the geometric mean of those ratios over the programs where both exist. '
            'C, C++ and Rust: the free build. A red value means the output differs from the C reference.</p>' % ("".join(th(*c) for c in cols), "".join(out)))


DATA = {d: json.load(open(os.path.join(HERE, d, RESULTS_NAME))) for d, *_ in PROGS if os.path.exists(os.path.join(HERE, d, RESULTS_NAME))}
for d, title, cnt, naive, opts, what in PROGS:
    if d not in DATA:
        continue
    data = DATA[d]
    data["results"] = [r for r in data["results"] if not r["label"].startswith("Lua 5.4")]
    for r in data["results"]:
        r["kind"] = "caspien" if r["label"].startswith("Caspien") else r["group"]
        r["key"] = lang_key(r)
        r["mode"] = "off" if r["label"].endswith("· off") else ("full" if r["label"].endswith("· full") else "")
progs = [p for p in PROGS if p[0] in DATA]

# ---- programs table
prog_rows = "".join('<tr><td><a href="#p-%s">%s</a></td><td class="n">%s</td><td class="wrap">%s</td></tr>' % (d, html.escape(t), html.escape(cnt % "{:,}".format(DATA[d]["n"])), html.escape(what))
                    for d, t, cnt, naive, opts, what in progs)
programs_html = ('<section id="programs"><h2>The programs</h2><div class="scroll"><table class="progs"><thead><tr><th>Program</th><th class="n">Size</th><th>What it does, and how Caspien versions differ</th></tr></thead><tbody>%s</tbody></table></div></section>' % prog_rows)

# ---- metric sections: metric first, then benchmark
msecs, nav = [], []
for m in METRIC_ORDER:
    _, mtitle, mnote = MET[m]
    mid = METRIC_ID[m]
    nav.append('<a href="#%s">%s</a>' % (mid, mtitle))
    charts = []
    for d, title, cnt, naive, opts, what in progs:
        R = DATA[d]["results"]
        charts.append('<section class="chart" id="%s-%s"><h3>%s <span class="nn">%s</span></h3><div class="scroll">%s</div></section>' % (
            mid, d, html.escape(title), html.escape(cnt % "{:,}".format(DATA[d]["n"])), chart(R, m, mtitle + " – " + title)))
    msecs.append('<div class="metric" id="%s"><h2>%s <span class="nn">%s</span></h2><section><h3>Every language on every program</h3><div class="scroll">%s</div></section>'
                 '<details class="charts" open><summary>Bar charts, one per program</summary><nav class="sub2">%s</nav>%s</details></div>' % (
                     mid, mtitle, html.escape(mnote), overview(DATA, m),
                     "".join('<a href="#%s-%s">%s</a>' % (mid, d, html.escape(t)) for d, t, *_ in progs), "".join(charts)))
nav += ['<a href="#caspien">Caspien variants</a>', '<a href="#recursion">Recursion</a>', '<a href="#programs">Programs</a>', '<a href="#allnumbers">All numbers</a>', '<a href="#notes">Notes</a>']
cas_html = ('<div class="metric" id="caspien"><h2>Caspien variants <span class="nn">naive against optimized, optimisations off against everything on</span></h2>%s</div>' % "".join(
    '<section id="p-%s"><h3>%s <span class="nn">%s</span></h3><p class="note">%s. Time is the fastest of %d runs.</p><div class="scroll">%s</div></section>' % (
        d, html.escape(t), html.escape(cnt % "{:,}".format(DATA[d]["n"])), html.escape(what), DATA[d]["runs"], summary(DATA[d]["results"], naive, opts)) for d, t, cnt, naive, opts, what in progs))
all_html = ('<div class="metric" id="allnumbers"><h2>All numbers <span class="nn">every implementation, every metric, per program</span></h2>%s</div>' % "".join(
    '<details><summary>%s</summary><div class="scroll">%s</div></details>' % (html.escape(t), table(DATA[d]["results"])) for d, t, *_ in progs))
# ---- recursion section (recursion/results.json)
def recursion_section():
    import os, json as _j
    f = os.path.join(os.path.dirname(os.path.abspath(__file__)), "recursion", "results.json")
    if not os.path.exists(f): return ""
    D = _j.load(open(f)); out = []
    refs = {"chain": "C -O2, hand loop", "tree": "C -O2, recursive"}
    notes = {"chain": "Linear tail recursion, depth %d repeated %s times. A true loop is the baseline: a recursive form only ever ties it (when the compiler or runtime turns the tail call into a jump) or loses to it." % (D["depth"], "{:,}".format(D["reps"])),
             "tree": "Visit every node of a complete binary tree of depth %d (non-tail recursion). Here the native call stack beats an explicit stack; the Rust row is constant-folded by LLVM and means nothing." % D["tree"]}
    titles = {"chain": "Chain: tail recursion against a loop", "tree": "Tree: non-tail recursion against an explicit stack"}
    for g in ("chain", "tree"):
        rows = [r for r in D["results"] if r["group"] == g]
        base = next(r["time_s"] for r in rows if r["label"] == refs[g])
        body = "".join('<tr><td>%s</td><td class="n">%.3f</td><td class="n%s">%.1f&times;</td></tr>' % (
            html.escape(r["label"]), r["time_s"], " bad" if r["time_s"] / base > 3 else "", r["time_s"] / base) for r in sorted(rows, key=lambda r: r["time_s"]))
        out.append('<section id="rec-%s"><h3>%s</h3><p class="note">%s Baseline: %s (%.3f s). Fastest of %d runs.</p><div class="scroll"><table><thead><tr><th>Implementation</th><th class="n">Seconds</th><th class="n">vs baseline</th></tr></thead><tbody>%s</tbody></table></div></section>' % (
            g, titles[g], html.escape(notes[g]), html.escape(refs[g]), base, 3, body))
    out.append('<section><ul><li><b>Result.</b> Recursion never beat a loop. gcc, g++ and rustc -O2 turn the tail call into the same loop (tie); Go and Java do not eliminate tail calls (about 5&times; slower); LuaJIT has proper tail calls (tie). For non-tail recursion the native stack beats a hand-written explicit stack (about 4&times; at C -O2).</li>'
               '<li><b>Caspien.</b> <code>@recursive</code> is lowered to a bounded <code>for</code> by the compiler, so there is no call stack; the lowered loop still costs about 2.5&times; a hand-written loop (range rebuilt and tested per step).</li>'
               '<li><b>Caveats.</b> Node and Bun chain times (6&ndash;15 s, even for the hand loop) are probably 64-bit BigInt emulation, not recursion cost. The Rust tree row is constant-folded.</li></ul></section>')
    return '<div class="metric" id="recursion"><h2>Recursion against loops <span class="nn">what does the recursion-to-loop conversion cost, and could real recursion ever win?</span></h2>%s</div>' % "".join(out)
rec_html = recursion_section()

NOTES = [
    "<b>Toolchains added on 1 Oct 2026.</b> Fortran (gfortran), Objective-C (gcc + GNU libobjc, no Foundation), D (LDC and GDC), Nim 1.6 (refc), Crystal, OCaml (no flambda), Swift 6.0.3 (-O, static stdlib, Glibc only), Codon 0.17, Zig 0.16 (ReleaseFast: no safety checks), Odin (-o:speed, bounds checks on), Chapel 2.3 (--fast), C# (.NET 8, tiered JIT, PGO), Kotlin 2.0.21 (JVM), WebAssembly (the C program built with zig cc for wasm32-wasi, ahead-of-time compiled by wasmtime 25). All of them single-threaded, compiled with the normal release flags; every port reproduces the C program's output exactly (checked at every size that was run).",
    "<b>Dart is missing.</b> Its toolchain could not be downloaded: the sandbox network allowlist blocks the Dart SDK hosts. Nothing was substituted.",
    "<b>Ports are idiomatic, not identical.</b> Each port does the same work with the same algorithm and the same data-structure kind as the C, Rust, Go or Java program it follows, but uses its own language's containers where those exist (Dictionary, HashMap, associative arrays, Table); garbage-collected and reference-counted languages allocate nodes the way a programmer in that language would. Differences worth knowing are noted in the port files and in benchmarks/RESULTS.md.",
    "<b>Size.</b> Java, C# and Kotlin count the runtime (JRE or the .NET shared runtime) plus the program; Node and Bun the interpreter binary plus the script; WebAssembly the wasmtime binary plus the .wasm; native binaries only themselves. Zig and Swift link a static runtime. This makes the size column about <i>deployment size</i>, not code size.",
    "<b>Compile time</b> is wall clock of the whole build (Caspien: its four stages plus assembler and linker; Zig: a cold cache build; C#: dotnet build; WebAssembly: zig cc plus wasmtime compile). Node, Bun and LuaJIT have no compile step.",
    "<b>Memory</b> is the peak resident size of the process (wait4). Garbage-collected runtimes size their heaps from the machine, so their peaks reflect policy as well as need.",
    "<b>Heap graph, binary trees, LRU:</b> Caspien rejects recursive structs on purpose, so its trees and lists are index-based; the other languages use pointers or references. Same work, not the same memory layout.",
    "<b>Sorting:</b> Caspien elements are u64, the others use 32-bit integers. <b>Linux only:</b> nothing was run on the Windows targets. <b>Noise:</b> a 2-core VM, timings vary by roughly 5&ndash;10% between runs; time is the fastest run.",
    "<b>Re-run on 4 Oct 2026</b> after the allocation-free insecure_hashOf, leaner HashMap, constant-division, sqrt and loop-unrolling changes: every program, every language, fastest of 3 runs, compile time the median of 2 builds, Linux 2-core VM, every output equal to the C reference.",
    "<b>Quick and full runs.</b> A <i>full</i> run builds every Caspien variant with optimisations off and on, repeats everything 5 times (compile time: median of 3 builds) and runs every language to completion. A <i>quick</i> run builds only the optimised Caspien variants, measures them first, and kills any other implementation whose first run reaches the slowest optimised Caspien time of that program (at least 0.1 s). Such a row is a full-colour bar at that limit, labelled &ldquo;&gt; limit (cut off)&rdquo;, meaning <i>would have taken longer than optimized Caspien</i>; it has no peak-memory figure, its ratios and the geometric mean are lower bounds (&ge;), and it has no output to compare. Quick files carry <code>mode</code> and <code>cutoff_s</code>.",
    "<b>Gold lines</b> (execution-time charts only; the other metrics are measured once per build): two lines at the best time minus and plus the typical run-to-run noise, defined as the median of the three repeats minus the best one. A single stray slow repeat (a JIT pause, a noisy neighbour) does not stretch them; the hover text also gives the slowest repeat. The value text sits to the right of the rightmost line.",
    "<b>Colours</b>: green = Caspien (hatched = optimisations off / naive, solid = everything on), red = bare metal and natively compiled languages, purple = compiled and interpreted (JVM, .NET, WebAssembly), blue = interpreted (Node, Bun, LuaJIT); shades tell the languages apart; every row is also labelled with its language, so colour is never the only cue (a palette of 24 languages cannot be colour-blind-safe by colour alone).",
]
notes_html = '<div class="metric" id="notes"><h2>Notes</h2><section><ul>%s</ul></section></div>' % "".join("<li>%s</li>" % n for n in NOTES)

page = """<title>Caspien Benchmarks</title>
<style>
:root{--bg:#fbfbf9;--fg:#1d2320;--muted:#5d665f;--grid:#dfe3dc;--card:#ffffff;--line:#d2d8cf;
@@LIGHTVARS@@--off-fill:#cfe9d9}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#181a18;--fg:#e8ece6;--muted:#a1aaa2;--grid:#343a35;--card:#1f2220;--line:#3a413b;
@@DARKVARS@@--off-fill:#28402f;color-scheme:dark}}
:root[data-theme="dark"]{--bg:#181a18;--fg:#e8ece6;--muted:#a1aaa2;--grid:#343a35;--card:#1f2220;--line:#3a413b;
@@DARKVARS@@--off-fill:#28402f;color-scheme:dark}
body{background:var(--bg);color:var(--fg);font:15px/1.5 "IBM Plex Sans",system-ui,sans-serif;padding-inline:16px;padding-block:24px}
main{max-width:1100px;margin:0 auto}
h1{font-size:26px;margin:0 0 4px;letter-spacing:-.01em}
h2{font-size:22px;margin:36px 0 0;padding-top:8px;border-top:2px solid var(--line)}
h2 .nn,h3 .nn{color:var(--muted);font-weight:400;font-size:15px;margin-left:8px}
h3{font-size:16px;margin:0}
.sub{color:var(--muted);margin:0 0 12px}
nav{display:flex;flex-wrap:wrap;gap:6px 16px;margin:8px 0 12px;font-size:14px}
nav a{color:var(--fg)}
nav.top{position:sticky;top:env(safe-area-inset-top,0px);background:var(--bg);padding:8px 0;margin:0;border-bottom:1px solid var(--line);z-index:4}
nav.sub2{margin:4px 0 0;font-size:13px}
.legend{display:flex;flex-wrap:wrap;gap:8px 20px;margin:12px 0 4px;font-size:14px}
.legend span{display:inline-flex;align-items:center;gap:7px}
.sw{display:inline-block;width:14px;height:14px;border-radius:3px;margin-right:7px;vertical-align:-2px}
section{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:16px;margin:16px 0;min-width:0}
.note{color:var(--muted);margin:6px 0 0;font-size:13px}
.scroll{overflow-x:auto}
svg{width:100%;min-width:640px;height:auto;display:block}
.grid{stroke:var(--grid);stroke-width:1}
.noise{stroke:#e0a800;stroke-width:1.6;pointer-events:none}
.tick{fill:var(--muted);font-size:11px}
.lbl{fill:var(--fg);font-size:12.5px}
.val{fill:var(--fg);font-size:12px;font-variant-numeric:tabular-nums}
.grp{fill:var(--muted);color:var(--muted);font-size:10.5px}
.hit{fill:transparent}
.row:hover .hit{fill:color-mix(in srgb,var(--fg) 6%,transparent)}
.bar{fill:var(--c)}
@@KCLASSES@@
.sw{background:var(--c)}
.bar.off{fill:url(#hatch);stroke:var(--c);stroke-width:1.2}
.sw.off{background:repeating-linear-gradient(45deg,var(--c) 0 2px,var(--off-fill) 2px 5px);outline:1px solid var(--c)}
.n{text-align:right;font-variant-numeric:tabular-nums}
.bad{color:#d9453d}
table{border-collapse:collapse;width:100%;font-size:13px}
th,td{padding:6px 10px;border-bottom:1px solid var(--line);text-align:left;white-space:nowrap}
th{color:var(--muted);font-weight:600;font-size:12px;text-transform:uppercase;letter-spacing:.04em;padding:0}
th button{all:unset;cursor:pointer;display:block;padding:6px 10px;width:100%;box-sizing:border-box;text-transform:uppercase;letter-spacing:.04em}
th.n button{text-align:right}
th button:hover,th button:focus-visible{color:var(--fg);background:color-mix(in srgb,var(--fg) 6%,transparent)}
th[aria-sort=ascending] button::after{content:" \\25B2"}
th[aria-sort=descending] button::after{content:" \\25BC"}
th[aria-sort]{color:var(--fg)}
table.ov th:first-child,table.ov td:first-child{position:sticky;left:0;background:var(--card);z-index:1}
td.wrap{white-space:normal;min-width:360px}
.reset{all:unset;cursor:pointer;color:var(--fg);text-decoration:underline;margin-right:8px}
.mono{font-family:"IBM Plex Mono",ui-monospace,monospace;font-size:12px}
details{margin-top:8px}summary{cursor:pointer;color:var(--muted)}
details.charts>summary{margin:8px 0}
#tip{position:fixed;pointer-events:none;background:var(--fg);color:var(--bg);padding:6px 9px;border-radius:6px;font-size:12px;white-space:pre;display:none;z-index:5}
.foot{color:var(--muted);font-size:13px;margin-top:24px}
ul{margin:0;padding-left:20px}li{margin:6px 0}
</style>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono&family=IBM+Plex+Sans:wght@400;600&display=swap">
<main>
<h1>Caspien against other languages</h1>
<p class="sub">Fifteen programs (n-body, fannkuch-redux, spectral-norm, binary-trees, mandelbrot, fasta and k-nucleotide in the style of the Computer Language Benchmarks Game; sieve of Eratosthenes, string manipulation, heap graph search, sorting and searching, LRU cache, Merkle tree, JSON serialise and parse, and hello world written for this suite) in @@NLANG@@ languages, single-threaded, on a 2-core Linux VM. The page is grouped by metric (execution time, compile time, peak memory, executable size) and then by program; each metric opens with one table of every language on every program, and its column headers sort it.</p>
<nav class="top">@@nav@@</nav>
<div class="legend">
@@legend@@
</div>
<svg width="0" height="0" style="position:absolute"><defs><pattern id="hatch" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="6" height="6" fill="var(--off-fill)"/><line x1="0" y1="0" x2="0" y2="6" style="stroke:var(--lang-caspien)" stroke-width="2.4"/></pattern></defs></svg>
@@sections@@
<p class="foot">Time is the fastest run; memory is the median peak resident size (wait4 on a small launcher); compile time is the median of the builds, wall clock. Output of every implementation is compared with the C -O0 build at the same size. &ldquo;Everything on&rdquo; = every optimizer pass at its most aggressive setting plus register variables and temporaries; &ldquo;off&rdquo; = every optimisation switch off.</p>
</main>
<div id="tip"></div>
<script>
(function(){var t=document.getElementById('tip');
document.querySelectorAll('.row').forEach(function(r){
r.addEventListener('mousemove',function(e){t.textContent=r.getAttribute('data-tip');t.style.display='block';t.style.left=Math.min(e.clientX+14,window.innerWidth-220)+'px';t.style.top=(e.clientY+14)+'px'});
r.addEventListener('mouseleave',function(){t.style.display='none'});});
document.querySelectorAll('table.sortable').forEach(function(tb){
var heads=tb.tHead.rows[0].cells,body=tb.tBodies[0];
function sortBy(ci,asc,text){var rows=Array.prototype.slice.call(body.rows);
rows.sort(function(a,b){var x=a.cells[ci].getAttribute('data-v')||'',y=b.cells[ci].getAttribute('data-v')||'';
if(text)return asc?x.localeCompare(y):y.localeCompare(x);
var nx=x===''?NaN:parseFloat(x),ny=y===''?NaN:parseFloat(y);
if(isNaN(nx)&&isNaN(ny))return 0;if(isNaN(nx))return 1;if(isNaN(ny))return -1;return asc?nx-ny:ny-nx;});
rows.forEach(function(r){body.appendChild(r)});}
Array.prototype.forEach.call(heads,function(th,ci){var b=th.querySelector('button');if(!b)return;
b.addEventListener('click',function(){var asc=th.getAttribute('aria-sort')!=='ascending';
Array.prototype.forEach.call(heads,function(h){h.removeAttribute('aria-sort')});
th.setAttribute('aria-sort',asc?'ascending':'descending');sortBy(ci,asc,th.getAttribute('data-type')==='text');});});
var sec=tb.closest('section')||tb.parentNode;var rs=sec&&sec.querySelector('.reset');
if(rs)rs.addEventListener('click',function(){Array.prototype.forEach.call(heads,function(h){h.removeAttribute('aria-sort')});
var rows=Array.prototype.slice.call(body.rows);rows.sort(function(a,b){return a.getAttribute('data-i')-b.getAttribute('data-i')});rows.forEach(function(r){body.appendChild(r)});});
});})();
</script>
"""
nlang = len({r["key"] for d in DATA for r in DATA[d]["results"]})
legend = "\n".join('<span><i class="sw k-%s"></i>%s</span>' % (k, n) for k, n in LEGEND_GROUPS)
page = page.replace("@@LIGHTVARS@@", "".join("--lang-%s:%s;" % (k, lc) for k, n, lc, dc in LANGS))
page = page.replace("@@DARKVARS@@", "".join("--lang-%s:%s;" % (k, dc) for k, n, lc, dc in LANGS))
page = page.replace("@@KCLASSES@@", "".join(".k-%s{--c:var(--lang-%s)}" % (k, k) for k, n, lc, dc in LANGS))
page = page.replace("@@NLANG@@", str(nlang))
for k, v in {"nav": "".join(nav), "sections": "\n".join(msecs) + cas_html + rec_html + programs_html + all_html + notes_html, "legend": legend}.items():
    page = page.replace("@@" + k + "@@", v)
open(dst, "w").write(page)
print("wrote", dst, len(page), "bytes")
