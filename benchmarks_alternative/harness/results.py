"""Reading and writing the results files (site/data/<program>.json) and the catalogue (site/data/index.json)."""
import json, os

from . import manifest, paths


def result_path(pid, quick=False):
    return os.path.join(paths.DATA, "%s%s.json" % (pid, ".quick" if quick else ""))


OPTIONAL = ("note", "prec", "compile_cached")     # left out of a row when empty / false; readers treat a missing field as that default


def _tidy(o):
    """Floats to microseconds (far below timing noise); optional fields dropped when empty."""
    if isinstance(o, float):
        return round(o, 6)
    if isinstance(o, list):
        return [_tidy(x) for x in o]
    if isinstance(o, dict):
        return {k: _tidy(v) for k, v in o.items() if not (k in OPTIONAL and not v)}
    return o


def write(path, header, rows):
    """Header on a few lines, one row per line: small files that diff well."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    header, rows = _tidy(header), [_tidy(r) for r in rows]
    dump = lambda o: json.dumps(o, separators=(",", ":"), ensure_ascii=False)
    with open(path, "w", encoding="utf-8") as f:
        f.write("{" + ",\n ".join('"%s":%s' % (k, dump(v)) for k, v in header.items()) + ',\n "results":[\n')
        f.write(",\n".join("  " + dump(r) for r in rows))
        f.write("\n ]}\n")


def read(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def existing_rows(S, pid, n):
    """--caspien-only: the other implementations' rows from the existing results file (refused when it was made with a different N)."""
    if not S["caspien_only"]:
        return []
    path = result_path(pid)
    if not os.path.exists(path):
        raise SystemExit("--caspien-only needs the existing results file %s" % path)
    old = read(path)
    if old.get("n") != n:
        raise SystemExit("%s was measured with N=%s, this run uses N=%s: the other languages' rows would not be comparable (pass --n %s)" % (path, old.get("n"), n, old.get("n")))
    return [r for r in old["results"] if r["group"] != "caspien"]


def write_index(programs=None):
    """data/index.json: the catalogue of programs that have results, in manifest order."""
    programs = programs or manifest.load()
    have = [P for P in programs if os.path.exists(result_path(P["id"]))]
    write_json(os.path.join(paths.DATA, "index.json"), {"programs": [manifest.catalogue_entry(P) for P in have]})
    return [P["id"] for P in have]


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(json.dumps(_tidy(obj), separators=(",", ":"), ensure_ascii=False) + "\n")
