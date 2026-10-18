#!/bin/bash
# gen_corpus.sh OUTDIR [N-programs-file]: HOB inputs for the LowerOrderGenerator port oracle.
# For each program and each variant ({lin,win} x {alloff,allon,shipped,feat}) runs the reference compiler up to the LOB and keeps the OPTIMIZER's output (stage 2, the real input of the LowerOrderGenerator -- `--hob` stops
# BEFORE the Optimizer, so it lacks the REGVAR hints and every optimization) as hob/<variant>__<name>.hob, plus the variant's compiler.config.
# Needs a scratch copy of the reference compiler tree in $SCRATCH_TREE (default: a fresh copy made here); never touches the repo trees.
set -e
OUT=$(readlink -f "${1:?outdir}"); LIST=${2:-}
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
export JAVA_TOOL_OPTIONS= CASPIEN_STDLIB="$REPO/stdlib"
TREE=${SCRATCH_TREE:-$OUT/tree}
if [ ! -d "$TREE" ]; then
  mkdir -p "$TREE"; (cd "$REPO/ReferenceImplementation" && javac -nowarn -sourcepath Orchestrator -d . Compiler.java 2>&1 | grep -v '^Note:' || true)
  tar -C "$REPO/ReferenceImplementation" --exclude=.cache --exclude=output --exclude=benchmarks --exclude=tests -cf - . | tar -C "$TREE" -xf -
fi
mkdir -p "$OUT/hob" "$OUT/cfg"
if [ -z "$LIST" ]; then
  LIST=$OUT/programs.txt; : > $LIST
  for f in "$REPO"/CaspienImplementation/docs/examples/[0-9]*.caspien; do echo "$f" >> $LIST; done
fi
BASE="$REPO/ReferenceImplementation/toolchain.config"
mk(){ # variant -> $TREE/toolchain.config
  v=$1; tgt=${v%%-*}; mode=${v#*-}
  cp "$BASE" "$TREE/toolchain.config"
  if [ "$tgt" = lin ]; then sed -i 's/^target .*/target linux/; s/^\( *\)default: win64/\1default: sysv_x64/' "$TREE/toolchain.config"; fi
  if [ "$mode" = feat ]; then
    # only the LowerOrder features on (deferred operands, register variables, cmov, ...) with every optimizer option off, so small test programs are not folded away
    for k in deferred-operands variables-in-registers float-variables-in-registers float-temporaries-in-registers hoist-array-bases variables-in-alloc-functions fuse-length-compare variables-in-arg-registers conditional-move copy-forward loop-rotation; do
      sed -i "s/^$k: .*/$k: on/" "$TREE/toolchain.config"
    done
    for k in loop-unrolling loop-unroll-nested function-inlining constant-folding variable-elision variable-shifting struct-unpacking dead-control-flow-removal dead-function-removal unused-declaration-removal variable-allocation-reordering struct-member-reordering; do
      sed -i "s/^$k: .*/$k: off/" "$TREE/toolchain.config"
    done
  elif [ "$mode" != shipped ]; then
    val=off; [ "$mode" = allon ] && val=on
    for k in deferred-operands variables-in-registers float-variables-in-registers float-temporaries-in-registers hoist-array-bases variables-in-alloc-functions fuse-length-compare variables-in-arg-registers conditional-move copy-forward loop-rotation loop-unrolling loop-unroll-nested function-inlining constant-folding variable-elision variable-shifting struct-unpacking dead-control-flow-removal dead-function-removal unused-declaration-removal variable-allocation-reordering struct-member-reordering; do
      sed -i "s/^$k: .*/$k: $val/" "$TREE/toolchain.config"
    done
    [ "$mode" = allon ] && sed -i -e 's/^loop-unrolling: .*/loop-unrolling: aggressive/' -e 's/^function-inlining: .*/function-inlining: aggressive/' "$TREE/toolchain.config"
  fi
  awk '/^===compiler.config===/{f=1;next} /^===/{f=0} f' "$TREE/toolchain.config" > "$OUT/cfg/$v.config"
}
for v in ${VARIANTS:-lin-alloff lin-allon lin-shipped win-alloff win-allon win-shipped}; do
  mk $v
  while read -r src; do
    n=$(basename "$src" .caspien)
    [ -s "$OUT/hob/${v}__$n.hob" ] && continue
    W="$OUT/work/${v}__$n"; rm -rf "$W"; mkdir -p "$W"
    (cd "$TREE" && java Compiler --no-cache -i "$src" "$W/x.lob" --lob >/dev/null 2>"$OUT/hob/${v}__$n.err") && cp "$W/.build/2_optimizer.hob.txt" "$OUT/hob/${v}__$n.hob" || { echo "FAIL $v $n"; rm -f "$OUT/hob/${v}__$n.hob"; }
    rm -rf "$W"
  done < "$LIST"
done
echo corpus done: $(ls "$OUT/hob"/*.hob | wc -l) hobs
