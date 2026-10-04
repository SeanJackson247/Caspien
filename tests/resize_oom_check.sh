#!/bin/bash
# `resize` realloc failure (needs Linux, java, gcc). A failed realloc leaves the old block allocated and registered; the moved-in
# dynarray must then be freed (like a failed `new` frees what was moved into it), the failure caught as "out of memory", and no
# allocation left over compared with the clean run. Cases: growing and shrinking arrays of plain u64, of inline owning structs
# and of owns pointers, and a temporary (`resize(mut dyn([..]), ..)`) as the source. tests/alloc_shim.c: FAILREALLOC=<size> FAILREALLOC_STICKY=1 fails the realloc of that size.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
cp "$ROOT/tests/alloc_shim.c" shim.c
gcc -shared -fPIC -o shim.so shim.c -ldl || { echo "FAIL: cannot build shim"; exit 1; }
HEAD='import "../stdlib/libc.caspien"
import "../stdlib/gt_init.caspien"
import "../stdlib/gt_register.caspien"
import "../stdlib/gt_alive_check.caspien"
import "../stdlib/gt_destruct.caspien"
import "../stdlib/gt_moved.caspien"
struct World{@pub{
	ticks: mut u64
}}
struct Holder{@pub{
	w: owns some mut World
}}
func main() void{
	try{
		?catch(e){
			unsafe extern{ printf("CAUGHT %s\n", e) }
			return
		}'
TAIL='
		unsafe extern{ printf("ok\n") }
	}
}'
bad=0; n=0
# name | realloc size to fail | body
run_case(){
  name=$1; size=$2; body=$3
  printf '%s\n%s\n%s\n' "$HEAD" "$body" "$TAIL" > tests/rz_$name.caspien
  java Compiler -i tests/rz_$name.caspien rz_$name >compile_$name.log 2>&1 || { echo "FAIL $name: compile"; tail -3 compile_$name.log; bad=1; return; }
  n=$((n+1))
  clean=$(LD_PRELOAD=./shim.so ./rz_$name 2>err.txt); L0=$(sed -n 's/.*LIVE=\(-\?[0-9]*\).*/\1/p' err.txt)
  echo "$clean" | grep -q "^ok" || { echo "FAIL $name: clean run: $clean"; bad=1; return; }
  out=$(FAILREALLOC=$size FAILREALLOC_STICKY=1 LD_PRELOAD=./shim.so ./rz_$name 2>err.txt); rc=$?
  live=$(sed -n 's/.*LIVE=\(-\?[0-9]*\).*/\1/p' err.txt)
  [ $rc -eq 0 ] && echo "$out" | grep -q "CAUGHT out of memory" || { echo "FAIL $name: rc=$rc out=$out"; bad=1; return; }
  [ "$live" = "$L0" ] || { echo "FAIL $name: live allocations $live after the caught failure (clean run: $L0)"; bad=1; }
}
run_case grow_u64 336 '		let a = mut ?dyn([1, 2, 3, 4])
		a = ?resize(a, 40, 0)'
run_case shrink_u64 32 '		let a = mut ?dyn([1, 2, 3, 4])
		a = ?resize(a, 40, 0)
		a = ?resize(a, 2, 0)'
run_case grow_inline 96 '		let w1 = mut ?new World{ticks= 10}
		let w2 = mut ?new World{ticks= 20}
		let h1 = mut Holder{w= w1}
		let h2 = mut Holder{w= w2}
		let v = mut ?dyn([h1, h2])
		let wf = mut ?new World{ticks= 7}
		let hf = mut Holder{w= wf}
		v = ?resize(v, 5, hf)'
run_case shrink_inline 32 '		let w1 = mut ?new World{ticks= 10}
		let w2 = mut ?new World{ticks= 20}
		let h1 = mut Holder{w= w1}
		let h2 = mut Holder{w= w2}
		let v = mut ?dyn([h1, h2])
		let wf = mut ?new World{ticks= 7}
		let hf = mut Holder{w= wf}
		v = ?resize(v, 5, hf)
		v = ?resize(v, 1, hf)'
run_case grow_ptrs 48 '		let p1 = mut ?new World{ticks= 1}
		let p2 = mut ?new World{ticks= 2}
		let pv = mut ?dyn([p1, p2])
		let pf = mut ?new World{ticks= 9}
		pv = ?resize(pv, 4, pf)'
run_case grow_temp 336 '		let a = mut ?resize(mut dyn([1, 2, 3, 4]), 40, 0)'
run_case grow_temp_inline 96 '		let w1 = mut ?new World{ticks= 10}
		let w2 = mut ?new World{ticks= 20}
		let h1 = mut Holder{w= w1}
		let h2 = mut Holder{w= w2}
		let wf = mut ?new World{ticks= 7}
		let hf = mut Holder{w= wf}
		let v = mut ?resize(mut dyn([h1, h2]), 5, hf)'
if [ $bad -eq 0 ]; then echo "PASS resize_oom_check: $n resize failures caught, none crashed or leaked"; else exit 1; fi
