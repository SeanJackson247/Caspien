#!/usr/bin/env python3
"""Which Rust benchmark ports does the COMPILER accept with `-F unsafe_code` (forbid, free and leak configurations)? Writes rust_safe.json
({program: true|false}); charts_all.py uses it for the memory-safe views, so 'safe Rust' there means rustc confirmed it, not that anyone read the source."""
import glob, json, os, subprocess, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
out = {}
for f in sorted(glob.glob(os.path.join(HERE, "*", "reference", "*.rs"))):
    d = f.split(os.sep)[-3]
    ok = True
    for flags in ([], ["--cfg", "leak"]):
        r = subprocess.run(["rustc", "--edition", "2021", "-F", "unsafe_code", "--emit=metadata", "-o", os.path.join(tempfile.mkdtemp(), "x.rmeta")] + flags + [f],
                           capture_output=True, text=True)
        ok = ok and r.returncode == 0
    out[d] = ok
json.dump(out, open(os.path.join(HERE, "rust_safe.json"), "w"), indent=1, sort_keys=True)
print({k: v for k, v in out.items() if not v} or "all accepted", "(%d programs)" % len(out))
