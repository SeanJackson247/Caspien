#!/bin/bash
# Windows file system layer (stdlib/fs_windows_gnu.caspien, NT native API) built for windows_gnu with mingw-w64 and run under Wine:
# fs_test.caspien and fs_policy_run_test.caspien with "Z:/tmp/..." paths (Z: is Wine's view of /), the files left on disk checked
# against the same model as fs_check.sh, plus a probe that the reparse-point query works (Wine reports its Z: drive root as a mount point).
# NOT covered, because Wine here neither creates nor reports real symlinks/junctions: the symlink-refusal checks (the two lines are
# removed from the Windows variants). Real Windows has never run this code. Skips when mingw-w64 or wine64 is missing.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WINE=$(command -v wine64 || ls /usr/lib/wine/wine64 2>/dev/null)
command -v x86_64-w64-mingw32-gcc >/dev/null && [ -n "$WINE" ] || { echo "SKIP fs_wine_check: needs x86_64-w64-mingw32-gcc and wine64"; exit 0; }
W=$(mktemp -d); trap 'rm -rf "$W" /tmp/caspien_fs_test /tmp/caspien_fs_pol' EXIT
export JAVA_TOOL_OPTIONS= WINEPREFIX="${WINEPREFIX:-/tmp/wpfx}" WINEDEBUG=-all DISPLAY=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target windows_gnu/; s/^\( *\)default: sysv_x64/\1default: win64/' toolchain.config
cat >> toolchain.config <<'CFG'

===fs.config===
fs-roots: Z:/tmp/caspien_fs_pol/data:rw, Z:/tmp/caspien_fs_pol/ro:r
CFG
fail=0
run() { "$WINE" "$1" < /dev/null 2>&1 | tr -d '\r'; }
# 1. fs_test
sed 's|"/tmp/caspien_fs_test"|"Z:/tmp/caspien_fs_test"|; /symlink file refused/d; /symlink dir refused/d' tests/fs_test.caspien > tests/fs_test_w.caspien
# the root of fs_test is outside the policy roots, so give this one a permissive build
cp toolchain.config toolchain.policy
python3 - <<'PY'
s = open('toolchain.config').read()
i = s.index('\n===fs.config===')
open('toolchain.nopolicy', 'w').write(s[:i])
PY
cp toolchain.nopolicy toolchain.config
java Compiler -i tests/fs_test_w.caspien fsw >c1.log 2>&1 || { echo "FAIL fs_test compile"; tail -4 c1.log; exit 1; }
D=/tmp/caspien_fs_test; rm -rf $D; mkdir -p $D/other
OUT=$(run fsw.exe)
echo "$OUT" | grep -c '^FAIL' | grep -qx 0 || { echo "$OUT" | grep FAIL; fail=1; }
N=$(echo "$OUT" | grep -c '^PASS'); [ "$N" = 22 ] || { echo "FAIL fs_test: expected 22 PASS, got $N"; echo "$OUT" | head; fail=1; }
python3 - "$D" <<'PY' || fail=1
import os, sys
d = sys.argv[1]
model = {"w.txt": b"alpha\nbeta\n", "c.txt": b"newer", "sub/in.txt": b"inner"}
got = {}
for root, dirs, files in os.walk(d):
    for f in files:
        p = os.path.join(root, f)
        got[os.path.relpath(p, d)] = open(p, "rb").read()
if got != model:
    print("FAIL disk state", got, model); sys.exit(1)
PY
# 2. policy run test (roots checked at run time, read-only dirs, case-insensitive prefix), with the policy in force
cp toolchain.policy toolchain.config
sed 's|/tmp/caspien_fs_pol|Z:/tmp/caspien_fs_pol|g; /symlink last component refused/d; /symlink in the middle refused/d; s|anyOpens("/tmp")|anyOpens("Z:/tmp")|' tests/fs_policy_run_test.caspien > tests/fs_policy_run_w.caspien
python3 - <<'PY'
p = 'tests/fs_policy_run_w.caspien'
s = open(p).read()
s = s.replace('\treport("empty refused"', '\treport("drive letter and path compare without case", checks("z:/TMP/Caspien_FS_Pol/DATA/sub") == 1)\n\treport("empty refused"')
s = s.replace('checks("data") == 0', 'checks("data") == 0)\n\treport("windows reserved name refused", opens(d0(), "NUL", Open.READ) == 0')
open(p, 'w').write(s)
PY
# (the reserved-name line needs a Dir; keep the variant simple: drop it again)
python3 - <<'PY'
p = 'tests/fs_policy_run_w.caspien'
s = open(p).read()
i = s.index('\treport("windows reserved name refused"')
j = s.index('\n', i)
s = s[:i-1] + s[j:]
open(p, 'w').write(s)
PY
java Compiler -i tests/fs_policy_run_w.caspien fsp >c2.log 2>&1 || { echo "FAIL policy run compile"; tail -4 c2.log; exit 1; }
P=/tmp/caspien_fs_pol; rm -rf $P; mkdir -p $P/data/sub $P/ro/rsub; echo hi > $P/ro/r.txt
OUT=$(run fsp.exe)
echo "$OUT" | grep -c '^FAIL' | grep -qx 0 || { echo "$OUT" | grep FAIL; fail=1; }
N=$(echo "$OUT" | grep -c '^PASS'); [ "$N" = 20 ] || { echo "FAIL policy run: expected 20 PASS, got $N"; echo "$OUT" | head -30; fail=1; }
[ -e $P/data/x.txt ] && [ -e $P/data/sub/x.txt ] && [ ! -e $P/ro/x.txt ] && [ ! -e $P/ro/nd ] || { echo "FAIL policy disk state"; fail=1; }
# 3. names that Windows forbids (device names, trailing dot/space, stream syntax) are refused by the name check
cat > tests/fs_names_w.caspien <<'SRC'
import "../stdlib/libc.caspien"
import "../stdlib/gt_init.caspien"
import "../stdlib/gt_register.caspien"
import "../stdlib/gt_alive_check.caspien"
import "../stdlib/gt_destruct.caspien"
import "../stdlib/gt_moved.caspien"
import "../stdlib/fs.caspien"
func bad(name: static imut string) mut u64{
	?catch(e){ return 1 }
	? fsCheckName(name)
	return 0
}
func report(name: static imut string, ok: imut bool) void{
	unsafe extern{
		if ok{ printf("PASS %s\n", name) } else { printf("FAIL %s\n", name) }
	}
}
func main() imut s32{
	report("plain ok", bad("report.txt") == 0)
	report("dots inside ok", bad("a.b.c") == 0)
	report("NUL", bad("NUL") == 1)
	report("nul.txt", bad("nul.txt") == 1)
	report("Com1", bad("Com1") == 1)
	report("LPT9.log", bad("LPT9.log") == 1)
	report("COM10 ok", bad("COM10") == 0)
	report("trailing dot", bad("a.") == 1)
	report("trailing space", bad("a ") == 1)
	report("stream", bad("a.txt:s") == 1)
	report("star", bad("a*") == 1)
	report("backslash", bad("a\\b") == 1)
	return 0
}
SRC
java Compiler -i tests/fs_names_w.caspien fsn >c3.log 2>&1 || { echo "FAIL names compile"; tail -4 c3.log; exit 1; }
OUT=$(run fsn.exe)
echo "$OUT" | grep -c '^FAIL' | grep -qx 0 || { echo "$OUT" | grep FAIL; fail=1; }
N=$(echo "$OUT" | grep -c '^PASS'); [ "$N" = 12 ] || { echo "FAIL names: expected 12 PASS, got $N"; fail=1; }
# 4. the reparse query: Wine reports its Z: drive root as a mount point
cat > tests/fs_reparse_w.caspien <<'SRC'
import "../stdlib/libc.caspien"
import "../stdlib/fs_windows_gnu.caspien"
func main() imut s32{
	let:<mut s64> h = mut fsOsStart("Z:/tmp")
	let:<mut u64> r = mut 0
	unsafe extern{
		if h >= 0{ r = fsWIsReparse(h) }
		if r == 1{ printf("PASS reparse seen\n") } else { printf("FAIL reparse seen\n") }
	}
	return 0
}
SRC
java Compiler -i tests/fs_reparse_w.caspien fsr >c4.log 2>&1 || { echo "FAIL reparse compile"; tail -4 c4.log; exit 1; }
run fsr.exe | grep -q '^PASS reparse seen' || { echo "FAIL reparse probe"; fail=1; }
[ $fail = 0 ] && echo "PASS fs_wine_check: 22 + 20 + 12 checks under Wine, disk state matches, reparse probe (symlink refusal itself not testable here)" || exit 1
