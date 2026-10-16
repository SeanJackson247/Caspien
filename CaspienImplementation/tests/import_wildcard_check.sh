#!/bin/bash
# import "dir/*": every .caspien file directly in the folder, sorted, no subfolders; missing folder / no files = error; adding a file to the
# folder invalidates the stage cache; combining the wildcard with an explicit import of one of its files is fine (diamond import).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache --exclude=benchmarks -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
fail() { echo "FAIL: $1"; exit 1; }
java Compiler -i tests/import_wildcard_test.caspien prog >c.log 2>&1 || { tail -3 c.log; fail "compile import_wildcard_test"; }
[ "$(./prog)" = "1" ] || fail "wrong output"
mkdir -p wc/sub
printf 'import "../stdlib/print.caspien"\nimport "../stdlib/gt/*"\nimport "wc/*"\nfunc main() void{\n\tprintln(one())\n\treturn\n}\n' >p1.caspien
printf '@pub\nfunc one() u64{\n\treturn 1\n}\n' >wc/a.caspien
printf '@pub\nfunc ignored() u64{\n\treturn 9\n}\n@pub\nfunc one() u64{\n\treturn 2\n}\n' >wc/sub/b.caspien   # a subfolder is NOT imported
printf 'not a source file\n' >wc/notes.txt                                                          # other extensions are NOT imported
sed -i 's#"../stdlib#"stdlib#; s#"wc/\*"#"wc/*"#' p1.caspien
java Compiler -i p1.caspien prog1 >c.log 2>&1 || { tail -3 c.log; fail "wildcard compile"; }
[ "$(./prog1)" = "1" ] || fail "subfolder or non-.caspien file was imported"
# cache: a new file in the folder must be seen (here it redefines one(), so the build must now fail)
printf '@pub\nfunc one() u64{\n\treturn 3\n}\n' >wc/b.caspien
if java Compiler -i p1.caspien prog2 >c.log 2>&1; then fail "stale cache: new file in the folder was ignored"; fi
rm wc/b.caspien
java Compiler -i p1.caspien prog3 >c.log 2>&1 || { tail -3 c.log; fail "recompile after removing the file"; }
# errors
printf 'import "nosuchdir/*"\nfunc main() void{\n\treturn\n}\n' >e1.caspien
if java Compiler -i e1.caspien p >c.log 2>&1; then fail "missing folder accepted"; fi
grep -q "is not a folder" c.log || { tail -3 c.log; fail "wrong message for missing folder"; }
mkdir empty
printf 'import "empty/*"\nfunc main() void{\n\treturn\n}\n' >e2.caspien
if java Compiler -i e2.caspien p >c.log 2>&1; then fail "empty folder accepted"; fi
grep -q "no .caspien files" c.log || { tail -3 c.log; fail "wrong message for empty folder"; }
echo "PASS import_wildcard_check: wildcard imports, ignores subfolders/other files, cache sees new files, errors reported"
