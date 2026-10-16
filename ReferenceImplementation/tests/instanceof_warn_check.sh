#!/bin/bash
# Compiles tests/instanceof_warn_test.caspien and requires exactly the two "can never be true" warnings for `instanceof Other` and
# `Other implements Shape` (and none for the legitimate tests), then runs it (needs Linux, java, gcc): every line must be PASS.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
java Compiler -i tests/instanceof_warn_test.caspien prog >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
n=$(grep -c "can never be true" compile.log)
[ "$n" = 2 ] || { echo "FAIL: expected 2 warnings, got $n"; grep -i warn compile.log; exit 1; }
grep -q "instanceof Other' can never be true" compile.log || { echo "FAIL: no instanceof warning"; exit 1; }
grep -q "implements Shape' can never be true -- 'Other' does not implement" compile.log || { echo "FAIL: no implements warning"; exit 1; }
./prog >out.txt; grep -q FAIL out.txt && { echo "FAIL: $(grep FAIL out.txt)"; exit 1; }
[ "$(grep -c PASS out.txt)" = 4 ] || { echo "FAIL: expected 4 PASS lines"; cat out.txt; exit 1; }
echo "PASS instanceof_warn_check: 2 warnings, 4 run-time checks"
