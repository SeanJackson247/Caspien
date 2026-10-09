#!/bin/bash
# audit_resize_fill_test: the heap figures never fall below the real use (100 Holder slots + 100 nodes = at least 1616 + 100*32 bytes... measured with
# the allocator shim), whether the audit calls the figure bounded or only a lower bound (">=" / finite).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
java Compiler -i tests/audit_resize_fill_test.caspien --audit >a.txt 2>&1 || { echo "FAIL: --audit"; tail -3 a.txt; exit 1; }
line=$(grep -m1 "^# summary: main has .* of heap live at once" a.txt)
[ -n "$line" ] || { echo "FAIL: no peak line"; exit 1; }
if echo "$line" | grep -q "(bounded)"; then
  n=$(echo "$line" | sed -E 's/.* at most ([0-9]+) bytes.*/\1/')
  [ "$n" -ge 4040 ] || { echo "FAIL: bounded peak $n is below the real 4040"; exit 1; }
else
  echo "$line" | grep -q "at least" || { echo "FAIL: neither bounded nor a lower bound: $line"; exit 1; }
fi
echo "PASS audit_resize_fill_check: struct-fill resize is not under-priced ($line)"
