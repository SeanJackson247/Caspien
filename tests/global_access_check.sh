#!/bin/bash
# (needs Linux, java, gcc, python3) tests/global_access_test.caspien with everything on (benchmarks/nbody/bench.py FULL switches), Linux target:
# every integer line PASS, the two float lines equal the Python model, and the assembly has no `leaq sym(%rip), %r15` left for a
# global load or store (they are single `mov sym(%rip)` instructions now).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
python3 - <<'PY'
import sys
sys.path.insert(0, "benchmarks/nbody")
import bench as B
B.patch_config("toolchain.config", B.FULL)
PY
java Compiler -i tests/global_access_test.caspien prog --no-cache >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
./prog >out.txt
grep -q FAIL out.txt && { echo "FAIL: $(grep FAIL out.txt)"; exit 1; }
[ "$(grep -c PASS out.txt)" = 6 ] || { echo "FAIL: expected 6 PASS lines"; cat out.txt; exit 1; }
python3 - <<'PY' || exit 1
import struct
r32 = lambda x: struct.unpack('f', struct.pack('f', x))[0]
f32, f64 = 1.5, 2.25
for _ in range(100):
    f32 = r32(r32(f32 * 1.5) + 0.25); f64 = f64 * 1.25 + 0.5
got = dict(l.strip().split("=") for l in open("out.txt") if l.startswith(("F32=", "F64=")))
if "%.9e" % f32 != got["F32"] or "%.17e" % f64 != got["F64"]:
    print("FAIL: float globals", got, "%.9e" % f32, "%.17e" % f64); sys.exit(1)
PY
n=$(grep -c "leaq [A-Za-z_.0-9+]*(%rip), %r15" .build/4_codegen.s)
[ "$n" = 0 ] || { echo "FAIL: $n 'leaq sym(%rip), %r15' left in the assembly"; grep -n "leaq [A-Za-z_.0-9+]*(%rip), %r15" .build/4_codegen.s | head -5; exit 1; }
echo "PASS global_access_check: 6 integer checks, 2 float values equal the model, no leaq-through-r15 left"
