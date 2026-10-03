#!/bin/bash
# File-system policy (fs.config in toolchain.config): compile-time root checks, the `file` unsafe tag, fs-deny-externs, --fs-report,
# and (run) the RootPath run-time check, read-only directories and symlink refusal. Needs Linux, java, gcc.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W" /tmp/caspien_fs_pol' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
cat >> toolchain.config <<'CFG'

===fs.config===
fs-roots: /tmp/caspien_fs_pol/data:rw, /tmp/caspien_fs_pol/ro:r
fs-deny-externs: open, openat, creat, fopen, unlink, unlinkat, rename, renameat, mkdir, mkdirat, rmdir
CFG
fail=0
mustfail() { # name, expected message fragment
  out=$(java Compiler -i tests/fs_policy_$1.caspien prog 2>&1)
  if [ $? -eq 0 ] || ! echo "$out" | grep -q -- "$2"; then echo "FAIL $1 (wanted compile error containing: $2)"; echo "$out" | tail -3; fail=1; else echo "PASS $1 refused"; fi
}
mustfail outside "not inside any fs-roots entry"
mustfail dotdot "contains '..'"
mustfail readonly_root "is read-only"
mustfail nonliteral "needs a string literal path"
mustfail any_safe "needs 'unsafe file"
mustfail any_extra_tag "needs exactly 'unsafe file{'"
mustfail deny_extern "forbidden by fs-deny-externs"
mustfail deny_alias "forbidden by fs-deny-externs"
# run test
D=/tmp/caspien_fs_pol
rm -rf $D; mkdir -p $D/data/sub $D/ro/rsub; echo hi > $D/ro/r.txt; ln -s ../ro $D/data/link; ln -s data $D/via
java Compiler -i tests/fs_policy_run_test.caspien prog --fs-report >compile.log 2>&1 || { echo "FAIL run test compile"; tail -5 compile.log; exit 1; }
for want in 'roots: /tmp/caspien_fs_pol/data?rw|/tmp/caspien_fs_pol/ro?r' 'opens "/tmp/caspien_fs_pol/ro" read-only' 'opens "/tmp/caspien_fs_pol/data" read-write' 'UNSAFE unchecked path via rootAny'; do
  grep -qF -- "$want" compile.log || { echo "FAIL --fs-report lacks: $want"; fail=1; }
done
OUT=$(./prog); echo "$OUT" | grep -c '^FAIL' | grep -qx 0 || { echo "$OUT" | grep '^FAIL'; fail=1; }
N=$(echo "$OUT" | grep -c '^PASS'); [ "$N" = 21 ] || { echo "FAIL expected 21 PASS lines, got $N"; echo "$OUT"; fail=1; }
[ -e $D/data/x.txt ] && [ -e $D/data/sub/x.txt ] && [ ! -e $D/ro/x.txt ] && [ ! -e $D/ro/nd ] && [ ! -e $D/data/nd ] || { echo "FAIL disk state"; fail=1; }
valgrind -q --leak-check=full --errors-for-leak-kinds=definite --error-exitcode=9 ./prog >/dev/null 2>vg.txt
rm -f $D/data/x.txt $D/data/sub/x.txt
valgrind -q --leak-check=full --errors-for-leak-kinds=definite --error-exitcode=9 ./prog >/dev/null 2>vg.txt || { echo "FAIL valgrind"; head vg.txt; fail=1; }
[ $fail = 0 ] && echo "PASS fs_policy_check: 8 compile refusals, 21 run checks, --fs-report, valgrind clean" || exit 1
