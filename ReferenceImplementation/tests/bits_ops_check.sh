#!/bin/bash
# Usage: tests/bits_ops_check.sh   (Linux, from anywhere; scratch copy of the tree, target linux)
# Compile-time checks for the bitwise builtins (bits_and / bits_xor / bits_or / bits_not / bits_left / bits_right) and for hex / binary / underscore
# integer literals: wrong arity, non-integer arguments, mismatched widths, literals that do not fit, malformed literals (lex errors), and a few
# forms that must still COMPILE. Each negative case asserts a non-zero exit AND a substring of the message. The runtime behaviour is covered by
# tests/bits_ops_test.caspien.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; export JAVA_TOOL_OPTIONS=
T=$(mktemp -d); cp -r "$ROOT"/ASTGenerator "$ROOT"/Optimizer "$ROOT"/LowerOrderGenerator "$ROOT"/Codegen "$ROOT"/stdlib "$ROOT"/Compiler*.class "$ROOT"/Compiler.java "$T"/
sed -e 's/^target windows_gnu/target linux/' -e 's/^    default: win64/    default: sysv_x64/' "$ROOT/toolchain.config" > "$T/toolchain.config"
mkdir -p "$T/tests"; cd "$T"
pass=0; fail=0
# usage: case_ name expect(ok|MESSAGE-SUBSTRING) body-of-main
case_() {
  printf 'func main() void{\n%s\n\treturn\n}\n' "$3" > tests/c_$1.caspien
  out=$(java Compiler -i tests/c_$1.caspien out_$1 2>&1); rc=$?
  if [ "$2" = ok ]; then
    if [ $rc -eq 0 ]; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1 (expected to compile)"; echo "$out" | grep "error" | head -2; fail=$((fail+1)); fi
  else
    if [ $rc -ne 0 ] && echo "$out" | grep -qF -- "$2"; then echo "PASS $1"; pass=$((pass+1)); else echo "FAIL $1 (expected error containing: $2)"; echo "$out" | grep "error" | head -2; fail=$((fail+1)); fi
  fi
}
# ---- builtins: arity
case_ and_one_arg        "'bits_and' takes exactly two arguments, got 1" '	let x = mut bits_and(1)'
case_ xor_three_args     "'bits_xor' takes exactly two arguments, got 3" '	let x = mut bits_xor(1, 2, 3)'
case_ or_zero_args       "'bits_or' takes exactly two arguments, got 0"  '	let x = mut bits_or()'
case_ not_two_args       "'bits_not' takes exactly one argument, got 2"  '	let x = mut bits_not(1, 2)'
case_ not_zero_args      "'bits_not' takes exactly one argument, got 0"  '	let x = mut bits_not()'
case_ left_one_arg       "'bits_left' takes exactly two arguments, got 1" '	let x = mut bits_left(1)'
# ---- builtins: operand types
case_ and_float          "'bits_and' requires an integer type, got 'mut_f32'" '	let f = mut 1.5
	let x = mut bits_and(f, f)'
case_ not_float          "'bits_not' requires an integer type, got 'mut_f32'" '	let f = mut 1.5
	let x = mut bits_not(f)'
case_ not_bool           "'bits_not' requires an integer type, got 'mut_bool'" '	let b = mut true
	let x = mut bits_not(b)'
case_ not_string         "'bits_not' requires an integer type" '	let x = mut bits_not("a")'
case_ shift_float        "'bits_left' requires an integer type, got 'mut_f32'" '	let f = mut 1.5
	let x = mut bits_left(f, 1)'
case_ xor_mismatched     "'bits_xor' requires both sides to be the same integer type, got 'mut_u64' and 'mut_u8'" '	let a = mut 1
	let:<mut u8> c = mut 3
	let x = mut bits_xor(a, c)'
case_ and_mismatched_s   "requires both sides to be the same integer type" '	let:<mut s8> a = mut 1
	let:<mut s16> c = mut 3
	let x = mut bits_and(a, c)'
case_ and_signedness     "requires both sides to be the same integer type" '	let:<mut s32> a = mut 1
	let:<mut u32> c = mut 3
	let x = mut bits_and(a, c)'
# ---- builtins: literal operands must fit the other operand's type
case_ lit_right_too_big  "'bits_xor': literal 300 does not fit in 'u8'" '	let:<mut u8> c = mut 3
	let x = mut bits_xor(c, 300)'
case_ lit_left_too_big   "'bits_xor': literal 300 does not fit in 'u8'" '	let:<mut u8> c = mut 3
	let x = mut bits_xor(300, c)'
case_ lit_negative_u8    "'bits_and': literal -1 does not fit in 'u8'" '	let:<mut u8> c = mut 3
	let x = mut bits_and(c, -1)'
case_ lit_s8_too_big     "'bits_or': literal 128 does not fit in 's8'" '	let:<mut s8> c = mut 3
	let x = mut bits_or(c, 128)'
case_ lit_hex_too_big    "'bits_or': literal 256 does not fit in 'u8'" '	let:<mut u8> c = mut 3
	let x = mut bits_or(c, 0x100)'
# ---- literals: lexing
case_ hex_no_digits      "malformed hexadecimal literal '0x': no digits after the '0x' prefix" '	let x = mut 0x'
case_ hex_bad_digit      "malformed hexadecimal literal '0xG': 'G' is not a valid hexadecimal digit" '	let x = mut 0xG'
case_ bin_no_digits      "malformed binary literal '0b': no digits after the '0b' prefix" '	let x = mut 0b'
case_ bin_bad_digit      "malformed binary literal '0b2': '2' is not a valid binary digit" '	let x = mut 0b2'
case_ underscore_double  "malformed numeric literal '1__0': '_' must sit between two digits" '	let x = mut 1__0'
case_ underscore_after_prefix "malformed numeric literal '0x_': '_' must sit between two digits" '	let x = mut 0x_'
case_ hex_over_u64       "does not fit in any integer type (the largest, u64, ends at 18446744073709551615)" '	let x = mut 0x1FFFFFFFFFFFFFFFF'
case_ dec_over_u64       "does not fit in any integer type (the largest, u64, ends at 18446744073709551615)" '	let x = mut 18446744073709551616'
case_ hex_literal_into_u8 "doesn't satisfy the declared bound 'mut_u8'" '	let:<mut u8> x = mut 0x1FF'
case_ bin_literal_into_u8 "doesn't satisfy the declared bound 'mut_u8'" '	let:<mut u8> x = mut 0b100000000'
# ---- forms that must compile
case_ ok_all_builtins    ok '	let:<mut u8> a = mut 0xF0
	let:<mut u8> b = mut bits_and(a, 0x3C)
	let:<mut u8> c = mut bits_xor(b, 0b1010)
	let:<mut u8> d = mut bits_or(c, 1)
	let:<mut u8> e = mut bits_not(d)
	let:<mut u8> f = mut bits_left(e, 3)
	let:<mut u8> g = mut bits_right(f, 1)'
case_ ok_signed          ok '	let:<mut s16> a = mut -0x7FFF
	let:<mut s16> b = mut bits_not(a)
	let:<mut s16> c = mut bits_right(b, 2)'
case_ ok_literal_both_sides ok '	let:<mut u16> a = mut 7
	let:<mut u16> b = mut bits_and(0xFFFF, a)'
case_ ok_u64_max_hex     ok '	let x = mut 0xFFFFFFFFFFFFFFFF
	let y = mut bits_not(x)'
case_ ok_underscores     ok '	let x = mut 1_000_000
	let y = mut 0xFF_FF
	let z = mut 0b1111_0000'
echo "bits_ops_check: $pass passed, $fail failed"
rm -rf "$T"
[ $fail -eq 0 ]
