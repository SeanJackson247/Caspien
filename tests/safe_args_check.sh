#!/bin/bash
# Runs docs/examples/08_main_safe_args.caspien with two arguments under valgrind (needs Linux, java, gcc, valgrind): it must print
# "args=3 first=3" and valgrind must report no invalid read/write/free and no definitely-lost block. (The sweep runs it without
# arguments, which never reached the inner argument arrays; `outer[oi] = mut inner` in make_safe_args used to free each inner
# array at the end of its loop trip because the `mut` wrapper hid the move.)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
java Compiler -i docs/examples/08_main_safe_args.caspien prog >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
out=$(valgrind -q --leak-check=full --errors-for-leak-kinds=definite --error-exitcode=9 ./prog one two 2>vg.txt); rc=$?
[ "$out" = "args=3 first=3" ] || { echo "FAIL: output '$out'"; head -10 vg.txt; exit 1; }
[ $rc -eq 0 ] || { echo "FAIL: valgrind rc=$rc"; head -20 vg.txt; exit 1; }
echo "PASS safe_args_check: args=3 first=3, valgrind clean"
