#!/bin/bash
# The Optimizer is four separate phases: HOB text -> HOBProgram (hob/HOBParser) -> passes on the HOBProgram -> HOB text (hob/HOBSerializer).
#   1. layering (static): only Main touches the parser/serializer; no pass reads or writes files or builds HOB text; the hob package imports nothing from the passes
#   2. the parser/serializer round trip is exact (strings with spaces and escaped quotes, char literals, blank lines)
#   tests/hob_phases_check.sh        (needs Optimizer/out)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/Optimizer/src/main/java/caspien/optimizer"
unset JAVA_TOOL_OPTIONS
bad=0
fail(){ echo "FAIL $1"; bad=1; }
# 1a only Main (and the hob package itself) may mention the parser or serializer
x=$(grep -lE "HOBParser|HOBSerializer" "$SRC"/*.java | grep -v "/Main.java$"); [ -z "$x" ] && echo "PASS only Main uses HOBParser/HOBSerializer" || fail "passes mention the parser/serializer: $x"
# 1b passes never touch files or stdout (stderr notes are diagnostics, not HOB), except the config loaders and Main
x=$(grep -lE "java\.io\.|java\.nio\.file|System\.out" "$SRC"/*.java | grep -vE "/(Main|[A-Za-z]*Config)\.java$"); [ -z "$x" ] && echo "PASS passes do no file/stdout I/O" || fail "I/O outside Main/configs: $x"
# 1c the representation package knows nothing about the passes
x=$(grep -rE "^import caspien\.optimizer\.[A-Z]" "$SRC/hob"); [ -z "$x" ] && echo "PASS hob package imports no pass" || fail "hob imports passes: $x"
# 1d the pass interface speaks HOBProgram only (no raw text lists)
x=$(grep -nE "List<String>|String\[\] *lines" "$SRC/OptimizationPass.java" "$SRC/PassResult.java" "$SRC/BytecodeOptimizer.java"); [ -z "$x" ] && echo "PASS pass pipeline signatures carry HOBProgram" || fail "text lists in the pipeline: $x"
# 2 round trip
W=$(mktemp -d)
printf 'FUNC_START main\nSTRING @s0 "a b  \\" c"\nPUSH %s imut_char\n\nRET imut_void\nFUNC_END\n' "' '" > "$W/t.hob"
javac -cp "$ROOT/Optimizer/out" -d "$W" "$ROOT/tests/HobRoundTrip.java" 2>&1 | grep -v JAVA_TOOL
java -cp "$ROOT/Optimizer/out:$W" HobRoundTrip "$W/t.hob" || bad=1
rm -rf "$W"; exit $bad
