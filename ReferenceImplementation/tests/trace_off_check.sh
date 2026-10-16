#!/bin/bash
# Stack traces are off unless a program mentions `stack_trace` or `funcname`. With tracing off, the generated code must not depend on the
# trace settings: every program in a sample builds to the identical HOB and the identical binary with the default `--trace-depth` and with
# `--trace-depth 3` / `1024`. (Needs Linux, java, gcc.) Also checks the flag's range errors.
# Optional: BASE_TREE=<a checkout of the compiler from before the feature> additionally compares every sample's HOB and binary with that tree.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config
mkdir -p out_a out_b out_c
n=0; bad=0
for name in throw_message_test throw_safe_test ctor_throw_test async_fail_test dyn_owning_elems_test hashmap_growth_test string_bulk_test lock_owns_member_test event_loop_tick_throw_test print_test cond_move_test par_lock_test inline_throw_test perf_codegen_test; do
  f=tests/$name.caspien; [ -f $f ] || continue
  java Compiler -i $f out_a/$name --no-cache >/dev/null 2>&1 || { echo "FAIL: $name does not compile"; bad=1; continue; }
  java Compiler -i $f out_b/$name --no-cache --trace-depth 3 >/dev/null 2>&1 || { echo "FAIL: $name --trace-depth 3"; bad=1; continue; }
  java Compiler -i $f out_c/$name --no-cache --trace-depth 1024 >/dev/null 2>&1 || { echo "FAIL: $name --trace-depth 1024"; bad=1; continue; }
  java Compiler -i $f out_a/$name.hob --no-cache --hob >/dev/null 2>&1
  java Compiler -i $f out_b/$name.hob --no-cache --hob --trace-depth 3 >/dev/null 2>&1
  cmp -s out_a/$name out_b/$name && cmp -s out_a/$name out_c/$name || { echo "FAIL: $name binary depends on --trace-depth"; bad=1; }
  cmp -s out_a/$name.hob out_b/$name.hob || { echo "FAIL: $name HOB depends on --trace-depth"; bad=1; }
  n=$((n+1))
done
for v in 0 1025 abc; do
  java Compiler -i tests/print_test.caspien out_a/x --trace-depth $v >err.txt 2>&1 && { echo "FAIL: --trace-depth $v accepted"; bad=1; }
done
if [ -n "$BASE_TREE" ]; then
  ( cd "$BASE_TREE" && sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' toolchain.config )
  for name in throw_message_test dyn_owning_elems_test cond_move_test par_lock_test; do
    ( cd "$BASE_TREE" && java Compiler -i "$ROOT/tests/$name.caspien" $W/base_$name --no-cache >/dev/null 2>&1 )
    cmp -s $W/base_$name out_a/$name || { echo "FAIL: $name differs from the pre-feature compiler"; bad=1; }
  done
fi
[ $bad -eq 0 ] && echo "PASS trace_off_check: $n programs build identically for every --trace-depth, bad values refused"
exit $bad
