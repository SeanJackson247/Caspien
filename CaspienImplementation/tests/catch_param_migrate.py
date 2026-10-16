#!/usr/bin/env python3
"""Rewrites bare uses of a catch parameter to `<name>.msg` (the catch parameter is now the caught error: message + stack trace).

    python3 tests/catch_param_migrate.py [--check] [paths...]      (default: every .caspien and .md file under the repo)

Inside the body of `catch(NAME){ ... }` / `?catch(NAME){ ... }` every use of NAME that is not already `NAME.msg` / `NAME.stack_trace`, not a
member name (`x.NAME`), not inside a string/char literal or comment, and not the operand of `throw` (a re-throw stays `throw NAME`) becomes
`NAME.msg`. Idempotent. A use that looks like a struct-literal label or declaration (`NAME=`, `NAME:`) is reported, not rewritten.
With --check nothing is written; the exit status is 1 if some file would change. Text is scanned the same way in Markdown (code blocks and prose).
"""
import os, re, sys

IDENT = re.compile(r"[A-Za-z_][A-Za-z_0-9]*")
CATCH = re.compile(r"catch\s*\(\s*([A-Za-z_][A-Za-z_0-9]*)\s*\)\s*\{")


def skip_literal(s, i):
    """If s[i] starts a string/char literal or comment, returns the index just past it, else i."""
    c = s[i]
    if c == '"' or c == "'":
        j = i + 1
        while j < len(s) and s[j] != c and s[j] != "\n":
            j += 2 if s[j] == "\\" else 1
        return j + 1
    if s.startswith("//", i):
        j = s.find("\n", i)
        return len(s) if j < 0 else j
    if s.startswith("/*", i):
        j = s.find("*/", i + 2)
        return len(s) if j < 0 else j + 2
    return i


def body_end(s, i):
    """s[i] is just past the '{' of a catch body; returns the index of the matching '}'."""
    depth = 1
    while i < len(s):
        j = skip_literal(s, i)
        if j != i:
            i = j
            continue
        if s[i] == "{":
            depth += 1
        elif s[i] == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def rewrite_body(body, name, notes):
    out, i = [], 0
    prev_word = None
    while i < len(body):
        j = skip_literal(body, i)
        if j != i:
            out.append(body[i:j]); i = j
            continue
        m = IDENT.match(body, i)
        if m and (i == 0 or not (body[i - 1].isalnum() or body[i - 1] == "_")):
            w = m.group(0)
            end = m.end()
            if w == name:
                before = body[:i].rstrip()
                after = body[end:]
                if before.endswith("."):
                    pass
                elif after.startswith(".msg") or after.startswith(".stack_trace"):
                    pass
                elif re.search(r"\bthrow\s*$", before):
                    pass
                elif re.match(r"\s*(=(?!=)|:(?!:))", after):
                    notes.append(body[max(0, i - 20):end + 12].replace("\n", " "))
                else:
                    out.append(w + ".msg"); i = end
                    continue
            out.append(w); i = end
            continue
        out.append(body[i]); i += 1
    return "".join(out)


def migrate(text, notes):
    out, pos = [], 0
    while True:
        m = CATCH.search(text, pos)
        if not m:
            out.append(text[pos:]); break
        # skip a match that sits inside a comment or string on its line
        line_start = text.rfind("\n", 0, m.start()) + 1
        prefix = text[line_start:m.start()]
        start = m.end()
        end = body_end(text, start)
        if end < 0 or "//" in prefix:
            out.append(text[pos:start]); pos = start
            continue
        out.append(text[pos:start])
        # nested catch bodies are rewritten by their own match: here only the part outside them is touched, so recurse on the body
        out.append(migrate_nested(text[start:end], m.group(1), notes))
        pos = end
    return "".join(out)


def migrate_nested(body, name, notes):
    """Rewrite uses of `name` in body; nested catch(...) bodies with their own parameter are migrated by their own rule."""
    res, pos = [], 0
    while True:
        m = CATCH.search(body, pos)
        if not m:
            res.append(rewrite_body(body[pos:], name, notes)); break
        start = m.end()
        end = body_end(body, start)
        if end < 0:
            res.append(rewrite_body(body[pos:], name, notes)); break
        res.append(rewrite_body(body[pos:start], name, notes))
        res.append(migrate_nested(body[start:end], m.group(1), notes) if m.group(1) != name
                   else migrate_nested(body[start:end], name, notes))
        pos = end
    return "".join(res)


def main(argv):
    check = "--check" in argv
    paths = [a for a in argv if not a.startswith("--")]
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    if not paths:
        for d, dirs, files in os.walk(root):
            dirs[:] = [x for x in dirs if x not in (".git", ".cache", "output", ".build", "node_modules")]
            for f in files:
                if f.endswith(".caspien") or (f.endswith(".md") and f not in ("CLAUDE.md", "stack_trace.md")):
                    paths.append(os.path.join(d, f))
    changed = 0
    for p in sorted(paths):
        try:
            text = open(p, encoding="utf-8").read()
        except (OSError, UnicodeDecodeError):
            continue
        if "catch" not in text:
            continue
        notes = []
        new = migrate(text, notes)
        for n in notes:
            print("REVIEW %s: %s" % (os.path.relpath(p, root), n))
        if new != text:
            changed += 1
            if not check:
                open(p, "w", encoding="utf-8").write(new)
            print("%s %s" % ("would change" if check else "rewrote", os.path.relpath(p, root)))
    print("%d file(s) %s" % (changed, "would change" if check else "changed"))
    return 1 if (check and changed) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
