#!/bin/bash
# The user's `main` (safe-args form, and the `@event_loop` form) is emitted under a renamed symbol. It must NOT be called `__main`: the
# mingw-w64 C startup code calls a function named `__main` before `main` (it runs gcc's constructors), so on windows_gnu the user's
# main used to run early with garbage arguments (safe-args programs crashed under Wine). Compiles examples 08 and 09 to assembly and
# checks no `__main` symbol is defined or called.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
bad=0
for ex in 08_main_safe_args 09_event_loop; do
  java Compiler --asm -i docs/examples/$ex.caspien out_$ex.s >compile.log 2>&1 || { echo "FAIL: compile $ex"; tail -3 compile.log; exit 1; }
  if grep -qE '(^|[^A-Za-z0-9_])__main([^A-Za-z0-9_]|$)' out_$ex.s; then echo "FAIL: $ex defines or calls __main"; bad=1; else echo "PASS $ex has no __main symbol"; fi
  grep -q '__caspien_main' out_$ex.s || { echo "FAIL: $ex has no __caspien_main"; bad=1; }
done
exit $bad
