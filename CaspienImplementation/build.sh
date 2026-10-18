#!/bin/bash
# Builds this implementation: 1) the LowerOrderGenerator and Codegen components as native executables (compiled by the reference compiler), 2) the Java orchestrator.
# Output: LowerOrderGenerator/out/lowerorder and Codegen/out/codegen (.exe off Linux). Linux builds use the SysV target; elsewhere the shipped toolchain.config target.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
REF="$HERE/../ReferenceImplementation"
export CASPIEN_STDLIB="$HERE/../stdlib"
export JAVA_TOOL_OPTIONS=
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT

# the reference compiler runs from a scratch copy so building never rewrites its config files
(cd "$REF" && javac -nowarn -sourcepath Orchestrator -d . Compiler.java 2>&1 | grep -v '^Note:' || true)
tar -C "$REF" --exclude=.cache --exclude=output --exclude=benchmarks --exclude=tests -cf - . | tar -C "$W" -xf -
EXE=codegen
LOEXE=lowerorder
if [ "$(uname -s)" = Linux ]; then
    sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' "$W/toolchain.config"
else
    EXE=codegen.exe
    LOEXE=lowerorder.exe
fi
mkdir -p "$HERE/Codegen/out" "$HERE/LowerOrderGenerator/out"
(cd "$W" && java Compiler -i "$HERE/LowerOrderGenerator/src/main/java/caspien/lowerorder/Main.caspien" "$W/$LOEXE" > "$W/build_lo.log" 2>&1) \
    || { echo "build.sh: building the LowerOrderGenerator executable failed:"; tail -20 "$W/build_lo.log"; exit 1; }
cp "$W/$LOEXE" "$HERE/LowerOrderGenerator/out/$LOEXE"
echo "built LowerOrderGenerator/out/$LOEXE"
(cd "$W" && java Compiler -i "$HERE/Codegen/src/main/java/caspien/codegen/Main.caspien" "$W/$EXE" > "$W/build.log" 2>&1) \
    || { echo "build.sh: building the Codegen executable failed:"; tail -20 "$W/build.log"; exit 1; }
cp "$W/$EXE" "$HERE/Codegen/out/$EXE"
echo "built Codegen/out/$EXE"

(cd "$HERE" && javac -nowarn -sourcepath Orchestrator -d . Compiler.java 2>&1 | grep -v '^Note:' || true)
echo "built the orchestrator (java Compiler -i prog.caspien output/prog)"
