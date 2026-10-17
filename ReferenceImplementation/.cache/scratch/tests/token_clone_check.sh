#!/bin/bash
# Reflection check: TokenCloner copies (or deliberately resets) every field of Token and TokenChecked.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export CASPIEN_STDLIB="$ROOT/../stdlib"
W=$(mktemp -d)
javac -nowarn -cp "$ROOT/ASTGenerator/out" -d "$W" "$ROOT/tests/TokenCloneCheck.java" 2>&1 | grep -v JAVA_TOOL
java -Xss512k -cp "$ROOT/ASTGenerator/out:$W" TokenCloneCheck 2>&1 | grep -v JAVA_TOOL
rc=${PIPESTATUS[0]}; rm -rf "$W"; exit $rc
