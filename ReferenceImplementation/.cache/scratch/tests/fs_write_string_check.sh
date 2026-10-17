#!/bin/bash
# Compiles tests/fs_write_string_test.caspien (Linux, java, gcc) in a scratch copy, runs it, requires 6 PASS and no FAIL, then checks the
# bytes on disk against a Python model (file n.txt = n chars, char i = 'a' + i % 26). Linux only; Windows/Wine not run.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export CASPIEN_STDLIB="$ROOT/../stdlib"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
java Compiler -i tests/fs_write_string_test.caspien prog >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
D=/tmp/caspien_fs_ws
rm -rf "$D"; mkdir -p "$D"
OUT=$(./prog)
echo "$OUT" | grep -c '^FAIL' | grep -qx 0 || { echo "$OUT"; echo "FAIL reported failures"; exit 1; }
echo "$OUT" | grep -c '^PASS' | grep -qx 6 || { echo "$OUT"; echo "FAIL expected 6 PASS lines"; exit 1; }
python3 - "$D" <<'PY' || exit 1
import sys
d = sys.argv[1]
for n in (0, 1, 4095, 4096, 4097, 10000):
    want = bytes(97 + i % 26 for i in range(n))
    got = open("%s/%d.txt" % (d, n), "rb").read()
    if got != want:
        print("FAIL bytes differ for", n); sys.exit(1)
print("fs_write_string_check: ok")
PY
