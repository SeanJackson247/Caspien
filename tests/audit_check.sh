#!/bin/bash
# `--audit` lists every use of `unsafe` (file, line, tags, the text in the braces) of the input and everything it imports, without building.
# Checked on docs/examples/19_unsafe_tags.caspien (one block per tag plus one `unsafe unaudited`). Needs Linux, java.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
fail(){ echo "FAIL: $*"; exit 1; }
java Compiler -i docs/examples/19_unsafe_tags.caspien --audit >a.txt 2>/dev/null || fail "--audit did not succeed"
[ -e output/audit ] && fail "an audit must not leave a binary"
grep -q '^docs/examples/19_unsafe_tags.caspien:[0-9]*  unsafe extern {' a.txt || fail "no extern block listed with file and line"
grep -q '| *printf("extern: %llu\\n", mut 5)' a.txt || fail "block contents missing"
grep -q 'unsafe unaudited {   UNAUDITED; it actually needs: assume deref extern global raw' a.txt || fail "unaudited block not reported with the tags it needs"
grep -q '^stdlib/gt_init.caspien:[0-9]*  unsafe extern {' a.txt || fail "stdlib blocks not listed"
grep -q 'not a block): let a = mut unsafe dyn' a.txt || fail "unsafe dyn expression not listed"
grep -q '^# unaudited blocks: 1' a.txt || fail "summary: unaudited count"
grep -q '^## your code: 25 blocks, 2 other uses' a.txt || fail "summary: your code count"
java Compiler -i docs/examples/19_unsafe_tags.caspien --audit-no-stdlib >b.txt 2>/dev/null || fail "--audit-no-stdlib"
grep -q '^## standard library: 13 blocks, 0 other uses (not listed' b.txt || fail "stdlib section should be counted but not listed"
grep -q '^stdlib/' b.txt && fail "--audit-no-stdlib listed stdlib blocks"
echo "PASS audit_check: blocks, tags, contents, unaudited needs, stdlib split, nothing built"
