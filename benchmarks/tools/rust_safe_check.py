#!/usr/bin/env python3
"""Which Rust ports does rustc accept with `-F unsafe_code` (plain and the `leak` configuration)? Writes site/data/rust_safe.json ({program: bool});
the site's memory-safe views use it, so 'safe Rust' there means rustc confirmed it, not that anyone read the source.   python3 tools/rust_safe_check.py"""
import glob, json, os, subprocess, sys, tempfile
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from harness import paths, results

out = {}
for f in sorted(glob.glob(os.path.join(paths.PROGRAMS, "*", "reference", "*.rs"))):
    ok = True
    for flags in ([], ["--cfg", "leak"]):
        r = subprocess.run(["rustc", "--edition", "2021", "-F", "unsafe_code", "--emit=metadata", "-o", os.path.join(tempfile.mkdtemp(), "x.rmeta")] + flags + [f],
                           capture_output=True, text=True)
        ok = ok and r.returncode == 0
    out[f.split(os.sep)[-3]] = ok
results.write_json(os.path.join(paths.DATA, "rust_safe.json"), dict(sorted(out.items())))
print({k: v for k, v in out.items() if not v} or "all accepted", "(%d programs)" % len(out))
