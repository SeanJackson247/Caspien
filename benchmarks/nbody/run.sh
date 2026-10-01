#!/bin/bash
# For the full comparison (time, memory, compile time, size; Caspien with optimisations off and on) use bench.py and charts.py instead.
# Usage: benchmarks/nbody/run.sh [N]   (default 1000000). Run from anywhere; needs the toolchains you want to time.
# Builds and times every implementation present on this machine; Caspien is compiled with the shipped toolchain.config.
# Caspien programs read N from the environment variable NBODY_N; the others take it as argv[1].
N=${1:-1000000}
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"
W=$(mktemp -d); cp "$HERE"/reference/* "$W"/; cd "$W"
t(){ name=$1; shift; s=$(date +%s.%N); out=$("$@" 2>&1 | grep -v Picked | tr '\n' ' '); e=$(date +%s.%N); printf "%-26s %7.2fs  %s\n" "$name" "$(echo "$e - $s" | bc)" "$out"; }
have(){ command -v "$1" >/dev/null; }
have gcc  && { gcc -O2 -o c_O2 nbody.c -lm; gcc -O0 -o c_O0 nbody.c -lm; gcc -O2 -o cf32_O2 nbody_f32.c -lm; gcc -O0 -o cf32_O0 nbody_f32.c -lm
  t "C -O2 (double)" ./c_O2 $N; t "C -O0 (double)" ./c_O0 $N; t "C f32 -O2" ./cf32_O2 $N; t "C f32 -O0" ./cf32_O0 $N; }
have g++  && { g++ -O2 -x c++ -o cpp_O2 nbody.cpp -lm; t "C++ -O2" ./cpp_O2 $N; }
have go   && { go build -o go_nbody nbody.go && t Go ./go_nbody $N; }
have rustc&& { rustc -O -o rs_nbody nbody.rs 2>/dev/null && t Rust ./rs_nbody $N; }
have javac&& { javac -d . NBody.java 2>/dev/null && t Java java -cp . NBody $N; }
have node && t Node node nbody.js $N
have bun  && t Bun bun nbody.js $N
have php  && t PHP php nbody.php $N
have ruby && t Ruby ruby nbody.rb $N
have python3 && t Python python3 nbody.py $N
# Caspien: the shipped toolchain.config targets windows_gnu. On Linux, build in a scratch copy of the tree with `target linux` + `default: sysv_x64`
# (the shipped config is left untouched); elsewhere use the tree as it is.
export JAVA_TOOL_OPTIONS=
CT="$ROOT"
if [ "$(uname -s)" = Linux ] && grep -q '^target windows_gnu' "$ROOT/toolchain.config"; then
  CT=$(mktemp -d); cp -r "$ROOT"/ASTGenerator "$ROOT"/Optimizer "$ROOT"/LowerOrderGenerator "$ROOT"/Codegen "$ROOT"/stdlib "$ROOT"/Compiler*.class "$ROOT"/Compiler.java "$CT"/
  sed -e 's/^target windows_gnu/target linux/' -e 's/^    default: win64/    default: sysv_x64/' "$ROOT/toolchain.config" > "$CT/toolchain.config"
fi
cd "$CT"
export NBODY_N=$N
for f in nbody nbody_arr nbody_plain nbody_loop; do
  cp "$HERE/caspien/$f.caspien" "$CT/_nb_$f.caspien"
  java Compiler -i "_nb_$f.caspien" "$W/cas_$f" >/dev/null 2>&1 && t "Caspien $f" "$W/cas_$f"
  rm -f "$CT/_nb_$f.caspien"
done
rm -rf "$W" "$CT/output"; [ "$CT" != "$ROOT" ] && rm -rf "$CT"
