"""Measure one program: Caspien first (its slowest optimised time is the quick-mode limit), then every other language."""
import os, shutil, sys, tempfile

from . import caspien, core, languages, paths, results


def _rowtag(label):
    """'Caspien graph · full' -> ('Caspien graph', 'full')"""
    head, _, mode = label.rpartition(" · ")
    return head, mode


def run_program(P, a, S):
    """Measures program P with options a (parsed arguments) and settings S; returns the path written."""
    pid = P["id"]
    n = a.n or P["n"]
    N = str(n)
    pdir = os.path.join(paths.PROGRAMS, pid)
    ref_dir, cas_dir = os.path.join(pdir, "reference"), os.path.join(pdir, "caspien")
    out_path = a.out or results.result_path(pid, quick=S["caspien_only"])
    old_rows = results.existing_rows(S, pid, n)
    W = tempfile.mkdtemp(prefix=pid + "_bench_")
    for f in os.listdir(ref_dir):
        shutil.copy(os.path.join(ref_dir, f), W)
    cache = core.BuildCache(W, S["build_cache"])
    rows, limits = [], {}
    envn = dict(os.environ, JAVA_TOOL_OPTIONS="")
    envn[P["env"]] = N

    def wanted(label):
        return not a.only or any(s.lower() in label.lower() for s in a.only)

    def measure(label, key, group, lang, prec, build, exe_cmd, size_fn, note="", extra=None):
        if not wanted(label) or (S["caspien_only"] and group != "caspien"):
            return
        built = cache.compile(label, group, build, S["builds"])
        if built is None:
            return
        ctimes, reused = built
        if reused:
            note = (note + "; " if note else "") + "compile time reused from an earlier build (build cache)"
        status, times, rss, outs, text = core.timed_runs(exe_cmd, W, envn, S, limits, prec, group == "caspien")
        if status == "fail":
            print("RUN FAILED", label, text[-300:], file=sys.stderr)
            return
        if status == "cutoff":
            r = core.cutoff_row(label, group, lang, prec, note, core.median(ctimes), size_fn(), times[0])
            print("%-40s compile %6.2fs  CUT OFF at %.3fs (%s)" % (label, r["compile_s"], times[0], core.CUTOFF_NOTE), flush=True)
        else:
            r = {"label": label, "group": group, "lang": lang, "prec": prec, "note": note, "compile_s": core.median(ctimes), "size_bytes": size_fn(),
                 "time_s": min(times), "times": times, "rss_kb": core.median(rss), "output": outs[-1]}
            core.note_caspien(limits, S, prec, label, r["time_s"])
            print("%-40s compile %6.2fs  size %10d  time %8.3fs  rss %8d KB  %s" % (
                label, r["compile_s"], r["size_bytes"], r["time_s"], r["rss_kb"], " ".join(r["output"])[:50]), flush=True)
        r["compile_cached"] = reused
        r["key"] = key
        r.update(extra or {})
        rows.append(r)

    # ---- Caspien, measured first ----
    if core.have("java") and core.have("gcc"):
        tree = caspien.Tree()
        try:
            for v in P["caspien"]:
                for mode, kv in caspien.modes(S, pid):
                    label = "%s · %s" % (v["label"], mode)
                    if not wanted(label):
                        continue
                    rel = tree.prepare(os.path.join(cas_dir, v["src"] + ".caspien"), kv)
                    exe = os.path.join(W, "cas_%s_%s" % (v["src"], mode))
                    measure(label, "caspien", "caspien", "Caspien", v.get("prec", P["prec"]), [tree.compile_command(rel, exe)], [exe],
                            lambda e=exe: core.size_of(e), v["desc"] + "; optimisations " + mode, {"mode": mode, "variant": v["label"]})
        finally:
            tree.close()
    # ---- every other language ----
    for r in languages.rows(P, W, N):
        measure(r["label"], r["key"], r["group"], r["lang"], r["prec"], r["build"], r["exe"], r["size"], r["note"])

    rows = old_rows + rows
    rows.sort(key=lambda r: r["group"] == "caspien")      # Caspien rows last, as on the site
    check_outputs(P, rows)
    header = {"program": pid, "title": P["title"], "n": n, "mode": "quick, Caspien only (other languages' rows from the existing results file)" if S["caspien_only"] else S["mode"],
              "runs": S["runs"], "builds": S["builds"], "measured": core.provenance(), "cutoff_s": limits if S["cutoff"] else None}
    results.write(out_path, header, rows)
    shutil.rmtree(W, ignore_errors=True)
    bad = [r["label"] for r in rows if not r["ok"]]
    print("wrote", out_path, "| output differs from the C reference:", bad or "none", "| cut off:", sum(1 for r in rows if r.get("cutoff")))
    return out_path


# ---- correctness: every output against the C program of the same precision --------------------------------------------------------------------

STD_REF = ["C -O0", "C -O2", "C -O2 (free)"]


def pick_reference(rows, labels):
    """Output of the first label that finished (a cut-off row has no output)."""
    for lab in labels:
        r = next((r for r in rows if r["label"] == lab and not r.get("cutoff")), None)
        if r:
            return r["output"]
    r = next((r for r in rows if not r.get("cutoff") and r["group"] != "caspien"), None)
    return r["output"] if r else None


def check_outputs(P, rows):
    """Sets r["ok"]. Reference labels and tolerances per precision come from the manifest ("references", "tolerance"); default: the C -O0 build,
    exact match of the whole output. A cut-off row never produced output, so there is nothing to differ."""
    refs_cfg = P.get("references", {})
    tol = P.get("tolerance", {})
    refs = {}
    for r in rows:
        refs.setdefault(r["prec"], pick_reference(rows, refs_cfg.get(r["prec"], STD_REF)))
    for r in rows:
        want = refs[r["prec"]]
        if r.get("cutoff"):
            r["ok"] = True
        elif want is None:
            r["ok"] = False
        elif r["prec"] in tol:                                # e.g. f32 ports round differently (operation order): equal to a few digits
            r["ok"] = len(r["output"]) == len(want) and all(abs(float(x) - float(y)) < tol[r["prec"]] for x, y in zip(r["output"], want))
        else:
            r["ok"] = r["output"] == want
