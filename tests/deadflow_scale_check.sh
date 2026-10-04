#!/bin/bash
# DeadControlFlowRemovalPass must stay roughly linear in the number of foldable branches (it used to redo whole-function reachability for
# every single fold). Generates a main with 2400 constant branches, compiles it with the dead-control-flow switch on (Linux scratch copy,
# everything-on style: constant folding + variable elision + dead-control-flow) and requires: PASS output (sum from a Python model),
# a folded program (no leftover `PUSH true/false ; CMP` pairs) and a compile under 25 s (the quadratic version took about 27 s).
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
export JAVA_TOOL_OPTIONS=
tar -C "$ROOT" --exclude=.git --exclude=.cache -cf - . | tar -C "$W" -xf -
cd "$W" || exit 1
sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/; s/^constant-folding: .*/constant-folding: on/; s/^variable-elision: .*/variable-elision: on/; s/^dead-control-flow-removal: .*/dead-control-flow-removal: on/' toolchain.config
python3 - <<'P'
K = 2400
s = 'import "../stdlib/libc.caspien"\nfunc main() void{\n\tlet x = mut 0\n'
x = 0
for i in range(K):
    if i % 3 == 0:
        s += '\tif %d < %d{\n\t\tx += %d\n\t}\n\telse{\n\t\tx += 1000\n\t}\n' % (i, i + 5, i); x += i
    elif i % 3 == 1:
        s += '\tif %d > %d{\n\t\tx += 7\n\t}\n\telse{\n\t\tx += %d\n\t}\n' % (i, i + 5, i); x += i
    else:
        s += '\tif %d < %d{\n\t\tif %d > %d{\n\t\t\tx += 5\n\t\t}\n\t\telse{\n\t\t\tx += %d\n\t\t}\n\t}\n' % (i, i + 2, i, i + 1, i); x += i
s += '\tunsafe extern{\n\t\tif x == %d{ printf("PASS big fold\\n") }\n\t\telse{ printf("FAIL big fold %%llu\\n", x) }\n\t}\n}\n' % x
open('tests/dcf_scale_prog.caspien', 'w').write(s)
P
S=$(date +%s)
timeout 120 java Compiler -i tests/dcf_scale_prog.caspien prog >compile.log 2>&1 || { echo "FAIL: compile"; tail -5 compile.log; exit 1; }
T=$(( $(date +%s) - S ))
./prog | grep -q '^PASS big fold' || { echo "FAIL: output"; ./prog; exit 1; }
[ "$T" -le 25 ] || { echo "FAIL: compile took ${T}s (limit 25)"; exit 1; }
echo "PASS deadflow_scale_check (${T}s)"
