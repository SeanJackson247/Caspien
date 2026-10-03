#!/bin/bash
# Compiles tests/fs_test.caspien (Linux, java, gcc) in a scratch copy, prepares /tmp/caspien_fs_test (with the symlinks `link` -> w.txt and
# `dlink` -> other), runs it, requires 24 PASS and no FAIL lines, then checks the files left on disk against a Python model.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
java Compiler -i tests/fs_test.caspien prog >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
D=/tmp/caspien_fs_test
rm -rf "$D"; mkdir -p "$D/other"
ln -s w.txt "$D/link"; ln -s other "$D/dlink"
OUT=$(./prog)
echo "$OUT" | grep -c '^FAIL' | grep -qx 0 || { echo "$OUT"; echo "FAIL fs_test reported failures"; exit 1; }
echo "$OUT" | grep -c '^PASS' | grep -qx 24 || { echo "$OUT"; echo "FAIL expected 24 PASS lines"; exit 1; }
python3 - "$D" <<'PY' || exit 1
import os, sys
d = sys.argv[1]
model = {"w.txt": b"alpha\nbeta\n", "c.txt": b"newer", "sub/in.txt": b"inner"}   # t.txt is created, truncated, then removed; temp files of uncommitted writes leave nothing
got = {}
for root, dirs, files in os.walk(d):
    dirs[:] = [x for x in dirs if not os.path.islink(os.path.join(root, x))]
    for f in files:
        p = os.path.join(root, f)
        if not os.path.islink(p):
            got[os.path.relpath(p, d)] = open(p, "rb").read()
if got != model:
    print("FAIL disk state", got, model); sys.exit(1)
PY
rm -rf "$D"; mkdir -p "$D/other"; ln -s w.txt "$D/link"; ln -s other "$D/dlink"
valgrind -q --leak-check=full --errors-for-leak-kinds=definite --error-exitcode=9 ./prog >/dev/null 2>vg.txt || { echo "FAIL valgrind"; head vg.txt; exit 1; }
rm -rf "$D"
echo "PASS fs_check: 24 checks, disk state matches the model, valgrind clean"
