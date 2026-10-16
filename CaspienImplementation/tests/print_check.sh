#!/bin/bash
# stdlib/print.caspien: compiles tests/print_test.caspien in a Linux-configured scratch copy, runs it and compares stdout with the
# independent Python model tests/print_model.py; also checks the file contains no `unsafe` (the program calls print/println from safe code)
# and that the leak shim sees no registered blocks at exit.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
cp "$ROOT/tests/alloc_shim.c" shim.c
gcc -shared -fPIC -o shim.so shim.c -ldl || { echo "FAIL: cannot build shim"; exit 1; }
grep -v "^//" tests/print_test.caspien | grep -q "unsafe" && { echo "FAIL: print_test.caspien must not contain unsafe"; exit 1; }
java Compiler -i tests/print_test.caspien pt >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
addr=$(nm pt | awk '$3=="ghost_table"{print $1}')
GT_ADDR=$addr GT_EXE=$W/pt LD_PRELOAD=./shim.so ./pt >out.txt 2>err.txt; rc=$?
[ $rc -eq 0 ] || { echo "FAIL: exit status $rc"; exit 1; }
python3 tests/print_model.py >want.txt
diff want.txt out.txt >diff.txt || { echo "FAIL: output differs from the model"; cat diff.txt; exit 1; }
grep -q "GT_LEN=0" err.txt || { echo "FAIL: blocks still registered at exit"; cat err.txt; exit 1; }
java Compiler -i tests/print_readme_test.caspien pr >compile.log 2>&1 || { echo "FAIL: compile print_readme_test"; tail -5 compile.log; exit 1; }
./pr >out2.txt 2>err2.txt; rc=$?
[ $rc -eq 0 ] || { echo "FAIL: print_readme_test exit status $rc"; exit 1; }
python3 tests/print_readme_model.py >want2.txt
diff want2.txt out2.txt >diff2.txt || { echo "FAIL: print_readme_test output differs from the model"; cat diff2.txt; exit 1; }
echo "PASS print_check: every print/println overload and the README's print examples match their models, no unsafe in the callers, no leak"
