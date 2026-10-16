"""The program manifest (programs/programs.json): one entry per benchmark program, read by the harness and, through data/index.json, by the site."""
import json

from . import paths


def load():
    with open(paths.MANIFEST) as f:
        programs = json.load(f)
    for P in programs:
        P.setdefault("stem", P["id"])
        P.setdefault("prec", "")
    return programs


def get(programs, pid):
    for P in programs:
        if P["id"] == pid:
            return P
    raise SystemExit("unknown program %r (known: %s)" % (pid, ", ".join(P["id"] for P in programs)))


def catalogue_entry(P):
    """What the site needs to know about a program (no measurement settings)."""
    cas = P["caspien"]
    naive = next(v["label"] for v in cas if v.get("role") == "naive")
    return {"id": P["id"], "title": P["title"], "count": P.get("count"), "what": P.get("what"), "overview": P.get("overview", True),
            "naive": naive, "columns": [[v["column"], v["label"]] for v in cas if "column" in v]}
