#!/bin/bash
# (needs Linux, java, gcc, valgrind) 1. tests/hashmap_growth_test.caspien and tests/string_bulk_test.caspien in a scratch copy with a Linux target:
# every line PASS, valgrind clean (no definite leak, no invalid access). 2. HashMap growth running out of memory (tests/alloc_shim.c
# FAILREALLOC=<size> FAILREALLOC_STICKY=1 fails the realloc of the new slot array, or of the new control array): `set` must carry on in the
# old table (the first keys stay readable, a full table ignores the rest), nothing may crash, and no allocation may be left over compared with the clean run.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
bad=0
for pair in hashmap_growth_test:10 string_bulk_test:13; do
  t=${pair%%:*}; want=${pair##*:}
  java Compiler -i tests/$t.caspien $t >compile_$t.log 2>&1 || { echo "FAIL $t: compile"; tail -5 compile_$t.log; exit 1; }
  valgrind -q --leak-check=full --errors-for-leak-kinds=definite --error-exitcode=9 ./$t >out_$t.txt 2>vg_$t.txt; rc=$?
  grep -q FAIL out_$t.txt && { echo "FAIL $t: $(grep FAIL out_$t.txt)"; bad=1; }
  [ "$(grep -c PASS out_$t.txt)" = "$want" ] || { echo "FAIL $t: expected $want PASS lines"; bad=1; }
  [ $rc -eq 0 ] || { echo "FAIL $t: valgrind rc=$rc"; head -15 vg_$t.txt; bad=1; }
done
cp tests/alloc_shim.c shim.c && gcc -shared -fPIC -o shim.so shim.c -ldl || { echo "FAIL: cannot build shim"; exit 1; }
cat > tests/hm_oom.caspien <<'EOF'
import "../stdlib/libc.caspien"
import "../stdlib/hash_map.caspien"
import "../stdlib/gt_init.caspien"
import "../stdlib/gt_register.caspien"
import "../stdlib/gt_alive_check.caspien"
import "../stdlib/gt_destruct.caspien"
import "../stdlib/gt_moved.caspien"
func main() void{
	?catch(e){
		unsafe extern{ printf("CAUGHT %s\n", e.msg) }
		return
	}
	let m = mut ? new HashMap:<u64>(mut 0, mut 0, mut 4)
	for i in 1..7{
		m.set(m, i, i * 10)
	}
	let sum = mut 0
	for i in 1..5{
		sum = sum + m.get(m, i, mut 0)
	}
	unsafe extern{ printf("count=%llu cap=%llu sum=%llu\n", m.count, m.capacity, sum) }
}
EOF
java Compiler -i tests/hm_oom.caspien hm_oom >compile_oom.log 2>&1 || { echo "FAIL hm_oom: compile"; tail -5 compile_oom.log; exit 1; }
clean=$(LD_PRELOAD=./shim.so ./hm_oom 2>err.txt); live=$(sed -n 's/.*LIVE=\(-\?[0-9]*\).*/\1/p' err.txt)
L0=$live
[ "$clean" = "count=6 cap=16 sum=100" ] || { echo "FAIL hm_oom clean run: '$clean' LIVE=$live"; bad=1; }
for size in 208 24; do   # the 8-slot entry array (16 + 8*24), then the 8-byte control array (16 + 8)
  out=$(FAILREALLOC=$size FAILREALLOC_STICKY=1 LD_PRELOAD=./shim.so ./hm_oom 2>err.txt); rc=$?
  live=$(sed -n 's/.*LIVE=\(-\?[0-9]*\).*/\1/p' err.txt)
  [ $rc -eq 0 ] && [ "$out" = "count=4 cap=4 sum=100" ] && [ "$live" = "$L0" ] || { echo "FAIL hm_oom (realloc $size fails): rc=$rc out='$out' LIVE=$live"; bad=1; }
done
[ $bad -eq 0 ] && echo "PASS hashmap_growth_check: growth + string tests valgrind clean, 2 failed-growth cases carried on without leaks"
exit $bad
