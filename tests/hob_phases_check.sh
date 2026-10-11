#!/bin/bash
# The Optimizer is four separate phases: HOB text -> HOBProgram (hob/HOBParser) -> passes on the HOBProgram -> HOB text (hob/HOBSerializer).
#   1. layering (static), for the Optimizer and the LowerOrderGenerator: both use the one shared HOB/ package; only Main touches the parser/serializer; no pass reads or writes files; HOB/ imports no stage
#   2. the parser/serializer round trip is exact (strings with spaces and escaped quotes, char literals, blank lines)
#   tests/hob_phases_check.sh        (needs Optimizer/out, which holds the compiled HOB classes)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOB="$ROOT/HOB/src/main/java/caspien/hob"
unset JAVA_TOOL_OPTIONS
bad=0
fail(){ echo "FAIL $1"; bad=1; }
# the representation package knows nothing about any stage
x=$(grep -rE "^import caspien\.(optimizer|lowerorder|codegen)" "$HOB"); [ -z "$x" ] && echo "PASS HOB imports no stage" || fail "HOB imports a stage: $x"
for stage in "Optimizer optimizer" "LowerOrderGenerator lowerorder"; do
  set -- $stage; SRC="$ROOT/$1/src/main/java/caspien/$2"
  # own copies of the representation are gone
  x=$(ls "$SRC"/Bytecode{Parser,Serializer,Token}.java 2>/dev/null); [ -z "$x" ] && echo "PASS $1 has no private parser/serializer/token" || fail "$1 still has: $x"
  # only Main may mention the program parser or serializer
  x=$(grep -lE "HOBParser|HOBSerializer" "$SRC"/*.java | grep -v "/Main.java$"); [ -z "$x" ] && echo "PASS $1: only Main uses HOBParser/HOBSerializer" || fail "$1 passes mention the parser/serializer: $x"
  # passes never touch files or stdout (stderr notes are diagnostics, not HOB); the config loaders and Main may
  x=$(grep -lE "java\.io\.|java\.nio\.file|System\.out" "$SRC"/*.java | grep -vE "/(Main|[A-Za-z]*Config)\.java$"); [ -z "$x" ] && echo "PASS $1: passes do no file/stdout I/O" || fail "$1 I/O outside Main/configs: $x"
  # the pass interface speaks HOBProgram only (no raw text lists)
  x=$(grep -nE "List<String>|String\[\] *lines" "$SRC/OptimizationPass.java" "$SRC/PassResult.java"); [ -z "$x" ] && echo "PASS $1: pass signatures carry HOBProgram" || fail "$1 text lists in the pass interface: $x"
done
# the lower-order passes get their config from Main; none loads a file
x=$(grep -lE "CompilerConfig\.load" "$ROOT"/LowerOrderGenerator/src/main/java/caspien/lowerorder/*.java | grep -v "/Main.java$"); [ -z "$x" ] && echo "PASS LowerOrderGenerator: config loaded only in Main" || fail "config loaded outside Main: $x"
# 2 round trip
W=$(mktemp -d)
printf 'FUNC_START main\nSTRING @s0 "a b  \\" c"\nPUSH %s imut_char\n\nRET imut_void\nFUNC_END\n' "' '" > "$W/t.hob"
javac -cp "$ROOT/Optimizer/out" -d "$W" "$ROOT/tests/HobRoundTrip.java" 2>&1 | grep -v JAVA_TOOL
java -cp "$ROOT/Optimizer/out:$W" HobRoundTrip "$W/t.hob" || bad=1
rm -rf "$W"; exit $bad
