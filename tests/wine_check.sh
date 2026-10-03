#!/bin/bash
# Windows target smoke test: builds a few programs for `windows_gnu` with the mingw-w64 cross-compiler and runs them under Wine, comparing
# the output with the expected text. Skips (exit 0, says so) when x86_64-w64-mingw32-gcc or wine64 is not installed.
# (Linux sandbox setup used so far: apt install gcc-mingw-w64-x86-64 wine64; the binary is /usr/lib/wine/wine64; WINEPREFIX=/tmp/wpfx.)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WINE=$(command -v wine64 || ls /usr/lib/wine/wine64 2>/dev/null)
command -v x86_64-w64-mingw32-gcc >/dev/null && [ -n "$WINE" ] || { echo "SKIP wine_check: needs x86_64-w64-mingw32-gcc and wine64"; exit 0; }
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS= WINEPREFIX="${WINEPREFIX:-/tmp/wpfx}" WINEDEBUG=-all DISPLAY=
tar -C "$ROOT" --exclude=.git -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target windows_gnu/; s/^\( *\)default: sysv_x64/\1default: win64/' toolchain.config
grep -q '^target windows_gnu' toolchain.config || { echo "FAIL: could not select windows_gnu"; exit 1; }
bad=0
run() { # name source args expected-first-line
  java Compiler -i "$2" "prog_$1" >"c_$1.log" 2>&1 || { echo "FAIL $1: compile"; tail -3 "c_$1.log"; bad=1; return; }
  got=$(timeout 8 "$WINE" "prog_$1.exe" $3 2>&1 < /dev/null | tr -d '\r' | head -1)
  [ "$got" = "$4" ] && echo "PASS $1" || { echo "FAIL $1: got '$got' want '$4'"; bad=1; }
}
run safe_args docs/examples/08_main_safe_args.caspien "one two" "args=3 first=3"
run c_args docs/examples/07_main_c_args.caspien "one two" "argc=3"
run event_loop docs/examples/09_event_loop.caspien "" "tick 1"
exit $bad
