#!/bin/bash
# Usage: tests/dynarray_struct_generic_check.sh   (Linux, from anywhere; scratch copy of the tree, target linux)
# Compile-time checks for DynamicArray<S> (S a struct): the generic impl is no longer rejected as a whole, each method whose signature is
# illegal for a struct T (a by-value T parameter) is only an error WHEN CALLED and the message names the method and the reason, the pointer
# twins and `get` compile, and the by-value-struct-parameter rule is untouched for ordinary code (also a user-written generic impl).
# The runtime behaviour is covered by tests/dynarray_struct_generic_test.caspien.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; export JAVA_TOOL_OPTIONS=
T=$(mktemp -d); cp -r "$ROOT"/ASTGenerator "$ROOT"/Optimizer "$ROOT"/LowerOrderGenerator "$ROOT"/Codegen "$ROOT"/stdlib "$ROOT"/Compiler*.class "$ROOT"/Compiler.java "$T"/
sed -e 's/^target windows_gnu/target linux/' -e 's/^    default: win64/    default: sysv_x64/' "$ROOT/toolchain.config" > "$T/toolchain.config"
mkdir -p "$T/tests"; cd "$T"
HDR='import "../stdlib/libc.caspien"
import "../stdlib/dynamic_array.caspien"
import "../stdlib/gt_init.caspien"
import "../stdlib/gt_register.caspien"
import "../stdlib/gt_alive_check.caspien"
import "../stdlib/gt_destruct.caspien"
struct Edge{
	@pub{ to: mut u64 }
	@pub{ w: mut u64 }
}
'
pass=0; fail=0
# usage: case name expect(ok|MESSAGE-SUBSTRING) body
case_() {
  printf '%s\nfunc main() void{\n\t?catch(e){ return }\n%s\n\treturn\n}\n' "$HDR" "$3" > tests/c_$1.caspien
  out=$(java Compiler -i tests/c_$1.caspien out_$1 2>&1); rc=$?
  if [ "$2" = ok ]; then
    if [ $rc -eq 0 ]; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1 (expected to compile)"; echo "$out" | grep "error" | head -2; fail=$((fail+1)); fi
  else
    if [ $rc -ne 0 ] && echo "$out" | grep -qF -- "$2"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1 (expected error containing: $2)"; echo "$out" | grep "error" | head -2; fail=$((fail+1)); fi
  fi
}
Z='	let z = mut Edge{to= 1, w= 2}'
case_ construct_empty ok '	let d = mut ? new DynamicArray:<Edge>()'
case_ adopt ok '	let r = mut ? dyn:<Edge>([])
	let d = mut ? new DynamicArray:<Edge>(r)'
case_ ptr_methods ok "$Z"'
	let d = mut ? new DynamicArray:<Edge>()
	? d.pushBackPtr(d, auto z)
	? d.pushFrontPtr(d, auto z)
	let k = mut 0
	match k into d.backing{
		d.setPtr(d, k, auto z)
	}
	let p = mut ? d.popBackPtr(d, auto z)
	let q = mut ? d.popFrontPtr(d, auto z)
	match k in d.backing{
		let g = mut d.get(d, k)
	}'
case_ pushBack_unavailable "method 'pushBack' of 'DynamicArray_Edge' is unavailable" "$Z"'
	let d = mut ? new DynamicArray:<Edge>()
	? d.pushBack(d, z)'
case_ pushFront_unavailable "method 'pushFront' of 'DynamicArray_Edge' is unavailable" "$Z"'
	let d = mut ? new DynamicArray:<Edge>()
	? d.pushFront(d, z)'
case_ set_unavailable "method 'set' of 'DynamicArray_Edge' is unavailable" "$Z"'
	let d = mut ? new DynamicArray:<Edge>()
	let k = mut 0
	match k into d.backing{
		d.set(d, k, z)
	}'
case_ popBack_unavailable "method 'popBack' of 'DynamicArray_Edge' is unavailable" "$Z"'
	let d = mut ? new DynamicArray:<Edge>()
	let y = mut ? d.popBack(d, z)'
case_ popFront_unavailable "method 'popFront' of 'DynamicArray_Edge' is unavailable" "$Z"'
	let d = mut ? new DynamicArray:<Edge>()
	let y = mut ? d.popFront(d, z)'
case_ seed_ctor_unavailable "constructor of 'DynamicArray_Edge' is unavailable" "$Z"'
	let d = mut ? new DynamicArray:<Edge>(z)'
case_ message_says_plain_struct "is a plain (by-value) 'Edge'" "$Z"'
	let d = mut ? new DynamicArray:<Edge>(z)'
case_ get_in_expression_rejected "bind it to a 'let' first" '	let r = mut ? dyn:<Edge>([])
	let d = mut ? new DynamicArray:<Edge>(r)
	let k = mut 0
	match k in d.backing{
		let s = mut (d.get(d, k)).to
	}'
# the rule itself is not relaxed
printf '%s\nfunc bad(e: mut Edge) void{\n\treturn\n}\nfunc main() void{\n\treturn\n}\n' "$HDR" > tests/c_plain.caspien
out=$(java Compiler -i tests/c_plain.caspien out_plain 2>&1); rc=$?
if [ $rc -ne 0 ] && echo "$out" | grep -qF "is a plain (by-value) 'Edge'"; then echo "PASS plain_function_still_rejected"; pass=$((pass+1)); else echo "FAIL plain_function_still_rejected"; fail=$((fail+1)); fi
# a user-written generic impl: the same lazy rule (not called -> fine; called -> error), and a non-generic impl method is still a hard error
printf '%s\nstruct Box<T>{@pub{ v: mut u64 }}\nimpl<T> Box<T>{\n\t@pub\n\tfunc put(_self: ref some mut self, x: mut T) void{\n\t\treturn\n\t}\n}\nfunc main() void{\n\t?catch(e){ return }\n\tlet b = mut ? new Box:<Edge>{v= 1}\n\treturn\n}\n' "$HDR" > tests/c_ubox.caspien
out=$(java Compiler -i tests/c_ubox.caspien out_ubox 2>&1); rc=$?
if [ $rc -eq 0 ]; then echo "PASS user_generic_impl_lazy"; pass=$((pass+1)); else echo "FAIL user_generic_impl_lazy"; echo "$out" | grep error | head -2; fail=$((fail+1)); fi
printf '%s\nstruct Box{@pub{ v: mut u64 }}\nimpl Box{\n\t@pub\n\tfunc put(_self: ref some mut self, x: mut Edge) void{\n\t\treturn\n\t}\n}\nfunc main() void{\n\treturn\n}\n' "$HDR" > tests/c_nbox.caspien
out=$(java Compiler -i tests/c_nbox.caspien out_nbox 2>&1); rc=$?
if [ $rc -ne 0 ] && echo "$out" | grep -qF "is a plain (by-value) 'Edge'"; then echo "PASS non_generic_method_still_rejected"; pass=$((pass+1)); else echo "FAIL non_generic_method_still_rejected"; echo "$out" | grep error | head -2; fail=$((fail+1)); fi
echo "dynarray_struct_generic_check: $pass passed, $fail failed"
rm -rf "$T"
[ $fail -eq 0 ]
