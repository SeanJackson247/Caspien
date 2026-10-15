#!/bin/bash
# caspien.bytecode.Text (the hand-written replacements for regex) must agree with java.util.regex / String.split on random input.
cd "$(dirname "$0")/.." || exit 1
D=$(mktemp -d)
javac -nowarn -d "$D" BytecodeParser/src/main/java/caspien/bytecode/Text.java tests/TextFuzz.java 2>&1 | grep -v "^Picked up\|^Note:"
OUT=$(java -cp "$D" TextFuzz 2>&1 | grep -v "^Picked up")
rm -rf "$D"
echo "$OUT" | tail -5
echo "$OUT" | grep -q "^ALL OK$" && echo "PASS text_helpers_check" || { echo "FAIL text_helpers_check"; exit 1; }
