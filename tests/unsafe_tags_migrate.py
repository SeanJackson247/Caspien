#!/usr/bin/env python3
"""One-off migration helper: rewrite every bare `unsafe{` statement block to `unsafe <tags>{`.

usage: unsafe_tags_migrate.py <compiler tree> <program.caspien>...
Compiles each program with --hob and CASPIEN_UNSAFE_REPORT set (the type checker then appends `file TAB line TAB tags` for
every unsafe block it checked instead of reporting), unions the tags per block over all programs, and rewrites the source
files in place. Blocks that need nothing are listed, not changed.
"""
import os, re, subprocess, sys, collections
tree, progs = sys.argv[1], sys.argv[2:]
report = os.path.join(tree, "unsafe_report.txt")
if os.path.exists(report):
    os.remove(report)
env = dict(os.environ, CASPIEN_UNSAFE_REPORT=report, JAVA_TOOL_OPTIONS="")
for p in progs:
    r = subprocess.run(["java", "-cp", ".", "Compiler", "--hob", "-i", p, "output/mig"], cwd=tree, env=env,
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("COMPILE FAILED", p, (r.stdout + r.stderr).strip().splitlines()[-1][:200])
tags = collections.defaultdict(set)
seen = set()
for line in open(report):
    f, ln, t = (line.rstrip("\n").split("\t") + [""])[:3]
    seen.add((f, int(ln)))
    tags[(f, int(ln))].update(t.split())
byfile = collections.defaultdict(dict)
for (f, ln), t in tags.items():
    byfile[f][ln] = t
changed = 0
for f, lines in byfile.items():
    src = open(f).read().split("\n")
    for ln, t in sorted(lines.items()):
        if not t:
            print("NEEDS NOTHING (remove by hand):", f, ln)
            continue
        text = src[ln - 1]
        new, n = re.subn(r"\bunsafe((?: [a-z]+)*)\s*\{", "unsafe " + " ".join(sorted(t)) + "{", text, count=1)
        if n == 0:
            print("NO unsafe{ ON LINE:", f, ln, text.strip()[:80])
            continue
        src[ln - 1] = new
        changed += 1
    open(f, "w").write("\n".join(src))
print("rewrote", changed, "blocks")
