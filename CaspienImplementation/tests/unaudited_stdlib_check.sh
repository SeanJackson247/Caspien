#!/bin/bash
# `unsafe unaudited` is the catch-all unsafe tag and the standard library may never use it: (1) no stdlib file mentions it in code,
# (2) the compiler refuses it inside the stdlib folder (a scratch copy gets a stdlib file that uses it). Needs Linux, java.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if grep -rn "unsafe[^/]*unaudited" "$ROOT"/stdlib --include=*.caspien | grep -v "^[^:]*:[0-9]*: *//"; then echo "FAIL: the stdlib uses 'unsafe unaudited'"; exit 1; fi
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
cat > stdlib/zz_unaudited.caspien <<'P'
import "libc.caspien"
@pub
func zz() void{
	unsafe unaudited{
		printf("x\n")
	}
}
P
cat > t_use.caspien <<'P'
import "stdlib/zz_unaudited.caspien"
func main() void{
	zz()
}
P
out=$(java Compiler -i t_use.caspien prog --no-cache 2>&1); rc=$?
[ $rc != 0 ] && echo "$out" | grep -q "not allowed in the standard library" || { echo "FAIL: stdlib 'unsafe unaudited' was not refused"; echo "$out" | tail -4; exit 1; }
echo "PASS unaudited_stdlib_check: not in the stdlib, refused there"
