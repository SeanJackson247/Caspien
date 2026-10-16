#!/bin/bash
# Main.writeFile/readFile of the Codegen port: compiles tests/codegen_main_io_test.caspien in a scratch copy (port's `func main` renamed,
# /tmp/caspien_cg_io whitelisted :rw in fs-roots), needs 4 PASS and no FAIL, checks bytes on disk vs a Python model, then valgrind.
# Linux only; Windows/Wine not run.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); D=/tmp/caspien_cg_io; trap 'rm -rf "$W" "$D"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
sed -i 's/^func main(/func port_main(/' Codegen/src/main/java/caspien/codegen/Main.caspien
printf '\n===fs.config===\nfs-roots: %s:rw\n' "$D" >> toolchain.config
java Compiler -i tests/codegen_main_io_test.caspien prog >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
rm -rf "$D"; mkdir -p "$D"
OUT=$(./prog)
echo "$OUT" | grep -c '^FAIL' | grep -qx 0 || { echo "$OUT"; echo "FAIL reported failures"; exit 1; }
echo "$OUT" | grep -c '^PASS' | grep -qx 4 || { echo "$OUT"; echo "FAIL expected 4 PASS lines"; exit 1; }
python3 - "$D" <<'PY' || exit 1
import sys
d = sys.argv[1]
for k, n in ((0, 0), (1, 1), (2, 3)):
    want = bytes(97 + i % 26 for i in range(n))
    got = open("%s/f%s.t" % (d, chr(97 + k)), "rb").read()
    if got != want:
        print("FAIL bytes differ for file", k); sys.exit(1)
PY
rm -rf "$D"; mkdir -p "$D"
valgrind -q --leak-check=full --errors-for-leak-kinds=definite --error-exitcode=9 ./prog >/dev/null 2>vg.txt || { echo "FAIL valgrind"; head vg.txt; exit 1; }
echo "PASS codegen_main_io_check: 4 run checks, bytes on disk match, valgrind clean"
