#!/bin/bash
# Stage 3 runs the LowerOrderGenerator executable over files: LOB and asm equal the reference compiler's (shipped + everything-on configs), a missing or failing exe is reported, a rerun hits the cache.
# Needs LowerOrderGenerator/out/lowerorder (build.sh). Linux only; Windows/Wine not run.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_TOOL_OPTIONS= CASPIEN_STDLIB="$ROOT/../stdlib"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
fail() { echo "FAIL $1"; exit 1; }
[ -x "$ROOT/LowerOrderGenerator/out/lowerorder" ] || fail "LowerOrderGenerator/out/lowerorder is not built (run build.sh)"
ln -s "$ROOT/../stdlib" "$W/stdlib"
for t in CaspienImplementation:"$ROOT" ReferenceImplementation:"$ROOT/../ReferenceImplementation"; do
    mkdir "$W/${t%%:*}"; tar -C "${t#*:}" --exclude=.cache --exclude=output --exclude=benchmarks -cf - . | tar -C "$W/${t%%:*}" -xf -
    sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' "$W/${t%%:*}/toolchain.config"
done
for P in "$ROOT/../ReferenceImplementation/tests/cmov_select_test.caspien" "$ROOT/../ReferenceImplementation/tests/loop_hoist_test.caspien"; do
    n=$(basename "$P" .caspien)
    (cd "$W/ReferenceImplementation" && java Compiler --lob --no-cache -i "$P" "$W/ref_$n.lob" >/dev/null 2>&1 && java Compiler --asm --no-cache -i "$P" "$W/ref_$n.s" >/dev/null 2>&1)
    (cd "$W/CaspienImplementation" && java Compiler --lob --no-cache -i "$P" "$W/cas_$n.lob" >/dev/null 2>&1 && java Compiler --asm --no-cache -i "$P" "$W/cas_$n.s" >/dev/null 2>&1) || fail "build with the exe ($n)"
    cmp -s "$W/ref_$n.lob" "$W/cas_$n.lob" && echo "PASS $n: LOB from the exe == the reference compiler's" || fail "$n: LOB differs"
    cmp -s "$W/ref_$n.s" "$W/cas_$n.s" && echo "PASS $n: asm from the exe == the reference compiler's" || fail "$n: asm differs"
done
cd "$W/CaspienImplementation" || exit 1
P="$ROOT/../ReferenceImplementation/tests/cmov_select_test.caspien"
java Compiler --asm -i "$P" "$W/c1.s" >/dev/null 2>&1; java Compiler --asm --cache-report -i "$P" "$W/c2.s" 2>&1 | grep -qi "s3.*hit" && echo "PASS rerun hits the LowerOrderGenerator cache" || fail "no LowerOrderGenerator cache hit"
cmp -s "$W/ref_cmov_select_test.s" "$W/c2.s" || fail "cached asm differs"
mv LowerOrderGenerator/out/lowerorder LowerOrderGenerator/out/lowerorder.real
java Compiler --asm --no-cache -i "$P" "$W/m.s" >"$W/m.txt" 2>&1; [ $? -ne 0 ] && grep -q "not built" "$W/m.txt" && echo "PASS missing exe is reported" || fail "missing exe"
printf '#!/bin/sh\necho "[error] boom" >&2\nexit 1\n' > LowerOrderGenerator/out/lowerorder; chmod +x LowerOrderGenerator/out/lowerorder
java Compiler --asm --no-cache -i "$P" "$W/f.s" >"$W/f.txt" 2>&1; [ $? -ne 0 ] && grep -q "LowerOrderGenerator failed (exit 1)" "$W/f.txt" && echo "PASS a failing exe fails the build" || { tail -5 "$W/f.txt"; fail "failing exe"; }
echo "ALL LOWERORDER EXE STAGE CHECKS PASSED"
