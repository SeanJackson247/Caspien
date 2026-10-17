#!/bin/bash
# (the front end is covered too: see the ASTGenerator rules below)
# The Optimizer is four separate phases: HOB text -> BytecodeProgram (hob/BytecodeParser) -> passes on the BytecodeProgram -> HOB text (hob/BytecodeSerializer).
#   1. layering (static), for the Optimizer and the LowerOrderGenerator: both use the one shared BytecodeParser/ package; only the library entry class (Optimizer / LowerOrder) touches the parser/serializer, and only Main touches files; no pass reads or writes files; BytecodeParser/ imports no stage
#   2. the parser/serializer round trip is exact (strings with spaces and escaped quotes, char literals, blank lines)
#   tests/bytecode_phases_check.sh        (needs Optimizer/out, which holds the compiled HOB classes)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export CASPIEN_STDLIB="$ROOT/../stdlib"
HOB="$ROOT/BytecodeParser/src/main/java/caspien/bytecode"
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
  x=$(grep -lE "BytecodeParser|BytecodeSerializer" "$SRC"/*.java | grep -v "/$LIB.java$"); [ -z "$x" ] && echo "PASS $1: only $LIB uses BytecodeParser/BytecodeSerializer" || fail "$1 passes mention the parser/serializer: $x"
  # nothing but Main touches files or stdout (the library classes and config parsers take and return text)
  x=$(grep -lE "java\.io\.|java\.nio\.file|System\.out" "$SRC"/*.java | grep -vE "/Main\.java$"); [ -z "$x" ] && echo "PASS $1: passes do no file/stdout I/O" || fail "$1 I/O outside Main/configs: $x"
  # the pass interface speaks BytecodeProgram only (no raw text lists)
  x=$(grep -nE "List<String>|String\[\] *lines" "$SRC/OptimizationPass.java" "$SRC/PassResult.java"); [ -z "$x" ] && echo "PASS $1: pass signatures carry BytecodeProgram" || fail "$1 text lists in the pass interface: $x"
done
# ASTGenerator (stage 1): the emitter builds a BytecodeProgram; only the library entry Frontend turns it into text, after emit() returned
AST="$ROOT/ASTGenerator/src/main/java/caspien"
x=$(grep -rlE "BytecodeSerializer|BytecodeParser" "$AST" | grep -v "/Frontend.java$"); [ -z "$x" ] && echo "PASS ASTGenerator: only the library entry Frontend serializes the HOB" || fail "ASTGenerator mentions the serializer/parser outside Frontend: $x"
x=$(grep -nE "System\.(out|err)" "$AST/Frontend.java" "$ROOT/Codegen/src/main/java/caspien/codegen/Codegen.java"; grep -n "java\.nio\.file" "$ROOT/Codegen/src/main/java/caspien/codegen/Codegen.java"); [ -z "$x" ] && echo "PASS Frontend/Codegen entries print nothing (Codegen reads no files)" || fail "entry class does stdout/file I/O: $x"
x=$(grep -nE "StringBuilder +out\b|String +emit\(" "$AST"/bytecodeemitter/*.java); [ -z "$x" ] && echo "PASS ASTGenerator: the emitter has no text buffer" || fail "emitter builds text: $x"
x=$(grep -rE "^import caspien\.(optimizer|lowerorder|codegen)" "$AST"); [ -z "$x" ] && echo "PASS ASTGenerator imports no other stage" || fail "ASTGenerator imports a stage: $x"
# Codegen (stage 4): the LOB is read by the shared parser, once, in the Codegen entry; the backend only walks the statements
CG="$ROOT/Codegen/src/main/java/caspien/codegen"
x=$(ls "$CG"/Bytecode{Parser,Serializer,Token}.java 2>/dev/null); [ -z "$x" ] && echo "PASS Codegen has no private parser/token" || fail "Codegen still has: $x"
x=$(grep -rlE "BytecodeParser|BytecodeSerializer" "$CG" | grep -v "/Codegen.java$"); [ -z "$x" ] && echo "PASS Codegen: only the library entry Codegen uses BytecodeParser" || fail "Codegen parser use outside Codegen.java: $x"
# the lower-order config is parsed once, in the library entry class; no pass reads or parses it
x=$(grep -lE "CompilerConfig\.parse" "$ROOT"/LowerOrderGenerator/src/main/java/caspien/lowerorder/*.java | grep -v "/LowerOrder.java$"); [ -z "$x" ] && echo "PASS LowerOrderGenerator: config parsed only in LowerOrder" || fail "config parsed outside LowerOrder: $x"
# 2 round trip
W=$(mktemp -d)
printf 'FUNC_START main\nSTRING @s0 "a b  \\" c"\nPUSH %s imut_char\n\nRET imut_void\nFUNC_END\n' "' '" > "$W/t.hob"
javac -cp "$ROOT/Optimizer/out" -d "$W" "$ROOT/tests/BytecodeRoundTrip.java" 2>&1 | grep -v JAVA_TOOL
java -cp "$ROOT/Optimizer/out:$W" BytecodeRoundTrip "$W/t.hob" || bad=1
rm -rf "$W"; exit $bad
