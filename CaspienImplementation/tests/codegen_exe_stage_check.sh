#!/bin/bash
# Stage 4 runs the Codegen executable over files: asm equals the reference compiler's, a missing or failing exe is reported, a rerun hits the cache.
# Needs Codegen/out/codegen (build.sh). Linux only; Windows/Wine not run.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_TOOL_OPTIONS= CASPIEN_STDLIB="$ROOT/../stdlib"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
fail() { echo "FAIL $1"; exit 1; }
[ -x "$ROOT/Codegen/out/codegen" ] || fail "Codegen/out/codegen is not built (run build.sh)"
ln -s "$ROOT/../stdlib" "$W/stdlib"
for t in CaspienImplementation:"$ROOT" ReferenceImplementation:"$ROOT/../ReferenceImplementation"; do
    mkdir "$W/${t%%:*}"; tar -C "${t#*:}" --exclude=.cache --exclude=output --exclude=benchmarks -cf - . | tar -C "$W/${t%%:*}" -xf -
    sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' "$W/${t%%:*}/toolchain.config"
done
P="$ROOT/../ReferenceImplementation/tests/cmov_select_test.caspien"
(cd "$W/ReferenceImplementation" && java Compiler --asm --no-cache -i "$P" "$W/ref.s" >/dev/null 2>&1)
cd "$W/CaspienImplementation" || exit 1
java Compiler --asm --no-cache -i "$P" "$W/cas.s" >/dev/null 2>&1 || fail "build with the exe"
cmp -s "$W/ref.s" "$W/cas.s" && echo "PASS asm from the exe == the reference compiler's" || fail "asm differs"
java Compiler --asm -i "$P" "$W/c1.s" >/dev/null 2>&1; java Compiler --asm --cache-report -i "$P" "$W/c2.s" 2>&1 | grep -qi "s4.*hit" && echo "PASS rerun hits the Codegen cache" || fail "no Codegen cache hit"
cmp -s "$W/ref.s" "$W/c2.s" || fail "cached asm differs"
mv Codegen/out/codegen Codegen/out/codegen.real
java Compiler --asm --no-cache -i "$P" "$W/m.s" >"$W/m.txt" 2>&1; [ $? -ne 0 ] && grep -q "not built" "$W/m.txt" && echo "PASS missing exe is reported" || fail "missing exe"
printf '#!/bin/sh\necho "[error] boom" >&2\nexit 1\n' > Codegen/out/codegen; chmod +x Codegen/out/codegen
java Compiler --asm --no-cache -i "$P" "$W/f.s" >"$W/f.txt" 2>&1; [ $? -ne 0 ] && grep -q "Codegen failed (exit 1)" "$W/f.txt" && echo "PASS a failing exe fails the build" || { cat "$W/f.txt" | tail -5; fail "failing exe"; }
echo "ALL CODEGEN EXE STAGE CHECKS PASSED"
