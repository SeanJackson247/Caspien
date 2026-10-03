#!/bin/bash
# Ghost-table growth failure (needs Linux, java, gcc). gt_register returns false when the table can not grow; the allocation site must then
# free the new block and take the ordinary out-of-memory path. Each case below fills the table to its load limit (4 of 8 buckets) and makes the next
# registration the one that must grow it; tests/alloc_shim.c (FAILREALLOC=128, the doubled bucket array) fails that first growth. Every case must exit 0, print
# "CAUGHT out of memory" and end with the same number of live allocations as its clean run (no leak, no crash).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git -cf - . | tar -C "$W" -xf -
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
struct Pair{
	@pub{ a: mut u64
	b: mut u64 }
}
struct Outer{
	@pub{ x: mut u64
	inner: owns some mut Pair }
}
struct Holder{
	@pub{ w: owns some mut Pair }
}
func main() void{
	try{
		?catch(e){
			unsafe extern{ printf("CAUGHT %s\n", e) }
			return
		}'
TAIL='		unsafe extern{ printf("ok\n") }
	}
}'
fill() { for i in $(seq 1 "$1"); do echo "		let f$i = mut ?new Pair{a= $i, b= 1}"; done; }
declare -A BODY
BODY[new]="$(fill 4)
		let x = mut ?new Pair{a= 5, b= 5}"
BODY[new_moved]="$(fill 3)
		let p = mut ?new Pair{a= 7, b= 9}
		let o = mut ?new Outer{x= 5, inner= p}"
BODY[dyn_literal]="$(fill 2)
		let q1 = mut ?new Pair{a= 5, b= 6}
		let q2 = mut ?new Pair{a= 7, b= 8}
		let arr = mut ?dyn([q1, q2])"
BODY[dyn_text]="$(fill 4)
		let s = mut ?dyn(\"hello\")"
BODY[clone_top]="$(fill 1)
		let p = mut ?new Pair{a= 1, b= 2}
		let o = mut ?new Outer{x= 5, inner= p}
		match Some(o){
			let c = mut ?clone(o)
		}"
BODY[clone_leaf]="$(fill 2)
		let p = mut ?new Pair{a= 1, b= 2}
		let o = mut ?new Outer{x= 5, inner= p}
		match Some(o){
			let c = mut ?clone(o)
		}"
BODY[clone_dyn_block]="$(fill 1)
		let q1 = mut ?new Pair{a= 5, b= 6}
		let q2 = mut ?new Pair{a= 7, b= 8}
		let arr = mut ?dyn([q1, q2])
		match Some(arr){
			let c = mut ?clone(arr)
		}"
BODY[clone_dyn_elem]="$(fill 0)
		let q1 = mut ?new Pair{a= 5, b= 6}
		let q2 = mut ?new Pair{a= 7, b= 8}
		let arr = mut ?dyn([q1, q2])
		match Some(arr){
			let c = mut ?clone(arr)
		}"
BODY[clone_dynval_block]="$(fill 1)
		let q1 = mut ?new Pair{a= 5, b= 6}
		let q2 = mut ?new Pair{a= 7, b= 8}
		let arr = mut ?dyn([Holder{w= q1}, Holder{w= q2}])
		match Some(arr){
			let c = mut ?clone(arr)
		}"
BODY[clone_dynval_elem]="$(fill 0)
		let q1 = mut ?new Pair{a= 5, b= 6}
		let q2 = mut ?new Pair{a= 7, b= 8}
		let arr = mut ?dyn([Holder{w= q1}, Holder{w= q2}])
		match Some(arr){
			let c = mut ?clone(arr)
		}"
bad=0; checked=0
for name in new new_moved dyn_literal dyn_text clone_top clone_leaf clone_dyn_block clone_dyn_elem clone_dynval_block clone_dynval_elem; do
  printf '%s\n%s\n%s\n' "$HEAD" "${BODY[$name]}" "$TAIL" > tests/grow_$name.caspien
  java Compiler -i tests/grow_$name.caspien prog_$name >compile_$name.log 2>&1 || { echo "FAIL $name: compile"; tail -3 compile_$name.log; bad=1; continue; }
  clean=$(LD_PRELOAD=./shim.so ./prog_$name 2>err.txt); L0=$(sed -n 's/.*LIVE=\(-\?[0-9]*\).*/\1/p' err.txt)
  [ "$clean" = "ok" ] || { echo "FAIL $name: clean run printed: $clean"; bad=1; continue; }
  out=$(FAILREALLOC=128 FAILREALLOC_STICKY=1 LD_PRELOAD=./shim.so ./prog_$name 2>err.txt); rc=$?; live=$(sed -n 's/.*LIVE=\(-\?[0-9]*\).*/\1/p' err.txt)
  checked=$((checked+1))
  if [ $rc -ne 0 ] || [ "$out" != "CAUGHT out of memory" ] || [ "$live" != "$L0" ]; then echo "FAIL $name: rc=$rc live=$live (clean $L0) out=$out"; bad=1; fi
done
# Shrinking the table (gt_destruct halves it when under an eighth full): a failed shrink realloc keeps the old buffer and nothing else happens.
cat > tests/grow_shrink.caspien <<EOF
$HEAD
		unsafe extern{ printf("start\n") }
		let p1 = mut ?new Pair{a= 1, b= 1}
		let p2 = mut ?new Pair{a= 2, b= 1}
		let p3 = mut ?new Pair{a= 3, b= 1}
		let p4 = mut ?new Pair{a= 4, b= 1}
		let p5 = mut ?new Pair{a= 5, b= 1}
		let p6 = mut ?new Pair{a= 6, b= 1}
	}
	let q = mut 0
	unsafe extern{ printf("ok\n") }
}
EOF
java Compiler -i tests/grow_shrink.caspien prog_shrink >compile_shrink.log 2>&1 || { echo "FAIL shrink: compile"; tail -3 compile_shrink.log; bad=1; }
if [ -x prog_shrink ]; then
  out=$(FAILREALLOC=64 LD_PRELOAD=./shim.so ./prog_shrink 2>err.txt); rc=$?; live=$(sed -n 's/.*LIVE=\(-\?[0-9]*\).*/\1/p' err.txt)
  clean=$(LD_PRELOAD=./shim.so ./prog_shrink 2>err.txt); L0=$(sed -n 's/.*LIVE=\(-\?[0-9]*\).*/\1/p' err.txt)
  checked=$((checked+1))
  if [ $rc -ne 0 ] || [ "$out" != "$clean" ] || [ "$live" != "$L0" ]; then echo "FAIL shrink: rc=$rc live=$live (clean $L0) out=$out"; bad=1; fi
fi
# gt_init: the very first malloc is the ghost table; if it fails the program says so and exits 1
out=$(FAILN=1 LD_PRELOAD=./shim.so ./prog_new 2>/dev/null); rc=$?; checked=$((checked+1))
if [ $rc -ne 1 ] || ! echo "$out" | grep -q "out of memory: cannot create the ghost table"; then echo "FAIL gt_init: rc=$rc out=$out"; bad=1; fi
if [ $bad -eq 0 ]; then echo "PASS gt_grow_oom_check: $checked table-growth failures caught, none crashed or leaked"; else exit 1; fi
