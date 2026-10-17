#!/bin/bash
# Builds this implementation: 1) the Codegen component as a native executable (compiled by the reference compiler), 2) the Java orchestrator.
# Output: Codegen/out/codegen (codegen.exe off Linux). Linux builds use the SysV target; elsewhere the shipped toolchain.config target.
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
if [ "$(uname -s)" = Linux ]; then
    sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' "$W/toolchain.config"
else
    EXE=codegen.exe
fi
mkdir -p "$HERE/Codegen/out"
(cd "$W" && java Compiler -i "$HERE/Codegen/src/main/java/caspien/codegen/Main.caspien" "$W/$EXE" > "$W/build.log" 2>&1) \
    || { echo "build.sh: building the Codegen executable failed:"; tail -20 "$W/build.log"; exit 1; }
cp "$W/$EXE" "$HERE/Codegen/out/$EXE"
echo "built Codegen/out/$EXE"

(cd "$HERE" && javac -nowarn -sourcepath Orchestrator -d . Compiler.java 2>&1 | grep -v '^Note:' || true)
echo "built the orchestrator (java Compiler -i prog.caspien output/prog)"
