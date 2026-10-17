#!/bin/bash
# Main.run/main of the Codegen port: builds the port in a scratch copy (Linux target), then checks usage error, missing config,
# nested output path and the info line. Output text is empty while the parser shim is in place. Linux only; Windows/Wine not run.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export CASPIEN_STDLIB="$ROOT/../stdlib"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
java Compiler -i Codegen/src/main/java/caspien/codegen/Main.caspien cg >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
R=$(mktemp -d); cp cg "$R/"; cd "$R" || exit 1
printf 'target linux\n' > codegen.config; echo "PUSH 8 1" > in.lob; mkdir sub
./cg >/dev/null 2>e.txt; [ $? -eq 1 ] && grep -q Usage e.txt || { echo "FAIL usage"; exit 1; }
./cg -i in.lob sub/o.s >/dev/null 2>e.txt || { echo "FAIL run"; cat e.txt; exit 1; }
[ -f sub/o.s ] && grep -q "wrote assembly to sub/o.s" e.txt || { echo "FAIL output/info"; exit 1; }
rm codegen.config; ./cg -i in.lob o.s >/dev/null 2>&1; [ $? -eq 1 ] || { echo "FAIL missing config"; exit 1; }
echo "PASS codegen_main_run_check: usage, run with nested output, missing config"
