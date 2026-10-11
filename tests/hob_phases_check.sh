#!/bin/bash
# (the front end is covered too: see the ASTGenerator rules below)
# The Optimizer is four separate phases: HOB text -> HOBProgram (hob/HOBParser) -> passes on the HOBProgram -> HOB text (hob/HOBSerializer).
#   1. layering (static), for the Optimizer and the LowerOrderGenerator: both use the one shared HOB/ package; only the library entry class (Optimizer / LowerOrder) touches the parser/serializer, and only Main touches files; no pass reads or writes files; HOB/ imports no stage
#   2. the parser/serializer round trip is exact (strings with spaces and escaped quotes, char literals, blank lines)
#   tests/hob_phases_check.sh        (needs Optimizer/out, which holds the compiled HOB classes)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOB="$ROOT/HOB/src/main/java/caspien/hob"
unset JAVA_TOOL_OPTIONS
bad=0
fail(){ echo "FAIL $1"; bad=1; }
# the representation package knows nothing about any stage
x=$(grep -rE "^import caspien\.(optimizer|lowerorder|codegen)" "$HOB"); [ -z "$x" ] && echo "PASS HOB imports no stage" || fail "HOB imports a stage: $x"
for stage in "Optimizer optimizer Optimizer" "LowerOrderGenerator lowerorder LowerOrder"; do
  set -- $stage; SRC="$ROOT/$1/src/main/java/caspien/$2"; LIB="$3"
  # own copies of the representation are gone
  x=$(ls "$SRC"/Bytecode{Parser,Serializer,Token}.java 2>/dev/null); [ -z "$x" ] && echo "PASS $1 has no private parser/serializer/token" || fail "$1 still has: $x"
  # only the library entry class ($LIB) may use the program parser or serializer (Main calls the library)
  x=$(grep -lE "HOBParser|HOBSerializer" "$SRC"/*.java | grep -v "/$LIB.java$"); [ -z "$x" ] && echo "PASS $1: only $LIB uses HOBParser/HOBSerializer" || fail "$1 passes mention the parser/serializer: $x"
  # nothing but Main touches files or stdout (the library classes and config parsers take and return text)
  x=$(grep -lE "java\.io\.|java\.nio\.file|System\.out" "$SRC"/*.java | grep -vE "/Main\.java$"); [ -z "$x" ] && echo "PASS $1: passes do no file/stdout I/O" || fail "$1 I/O outside Main/configs: $x"
  # the pass interface speaks HOBProgram only (no raw text lists)
  x=$(grep -nE "List<String>|String\[\] *lines" "$SRC/OptimizationPass.java" "$SRC/PassResult.java"); [ -z "$x" ] && echo "PASS $1: pass signatures carry HOBProgram" || fail "$1 text lists in the pass interface: $x"
done
# ASTGenerator (stage 1): the emitter builds a HOBProgram; only Main turns it into text, after emit() returned
AST="$ROOT/ASTGenerator/src/main/java/caspien"
x=$(grep -rlE "HOBSerializer|HOBParser" "$AST" | grep -v "/Main.java$"); [ -z "$x" ] && echo "PASS ASTGenerator: only Main serializes the HOB" || fail "ASTGenerator mentions the serializer/parser outside Main: $x"
x=$(grep -nE "StringBuilder +out\b|String +emit\(" "$AST"/bytecodeemitter/*.java); [ -z "$x" ] && echo "PASS ASTGenerator: the emitter has no text buffer" || fail "emitter builds text: $x"
x=$(grep -rE "^import caspien\.(optimizer|lowerorder|codegen)" "$AST"); [ -z "$x" ] && echo "PASS ASTGenerator imports no other stage" || fail "ASTGenerator imports a stage: $x"
# the lower-order config is parsed once, in the library entry class; no pass reads or parses it
x=$(grep -lE "CompilerConfig\.parse" "$ROOT"/LowerOrderGenerator/src/main/java/caspien/lowerorder/*.java | grep -v "/LowerOrder.java$"); [ -z "$x" ] && echo "PASS LowerOrderGenerator: config parsed only in LowerOrder" || fail "config parsed outside LowerOrder: $x"
# 2 round trip
W=$(mktemp -d)
printf 'FUNC_START main\nSTRING @s0 "a b  \\" c"\nPUSH %s imut_char\n\nRET imut_void\nFUNC_END\n' "' '" > "$W/t.hob"
javac -cp "$ROOT/Optimizer/out" -d "$W" "$ROOT/tests/HobRoundTrip.java" 2>&1 | grep -v JAVA_TOOL
java -cp "$ROOT/Optimizer/out:$W" HobRoundTrip "$W/t.hob" || bad=1
rm -rf "$W"; exit $bad
