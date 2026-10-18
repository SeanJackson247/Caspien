#!/bin/bash
# passcheck.sh EXE CORPUS PASSNAME [filter] : runs one pass of the Caspien LowerOrder exe on the Java dump of the step before it and compares with the Java dump of that step
# CORPUS = folder with cfg/ and gold/<variant>__<name>/{steps.txt,NN_pass.txt} (gen_corpus.sh + PassDump). Prints BAD lines and a summary.
EXE=$1; C=$2; P=$3; F=${4:-}
# optional env FROM=<pass>: feed the gold input of that pass (the program before it) and run CHAIN (comma list, default P)
FROM=${FROM:-$P}; CHAIN=${CHAIN:-$P}
W=$(mktemp -d); ok=0; bad=0; skip=0; ex=0
for d in $C/gold/*$F*/; do
  b=$(basename $d); v=${b%%__*}
  [ -f $d/steps.txt ] || continue
  nn=$(awk -v p=$P '$2==p{print $1}' $d/steps.txt)
  if [ -z "$nn" ]; then skip=$((skip+1)); continue; fi
  fn=$(awk -v p=$FROM '$2==p{print $1}' $d/steps.txt)
  prev=$(printf "%02d" $((10#$fn-1)))
  inf=$(ls $d/${prev}_*.txt 2>/dev/null | head -1); exp=$d/${nn}_$P.txt
  cmp -s $inf $exp || ex=$((ex+1))
  cp $C/cfg/$v.config $W/compiler.config
  (cd $W && $EXE -i $inf out.txt --pass $CHAIN >/dev/null 2>err.txt)
  if cmp -s $W/out.txt $exp; then ok=$((ok+1)); else bad=$((bad+1)); echo "BAD $b"; fi
  rm -f $W/out.txt
done
echo "$P: ok=$ok bad=$bad skipped(no such step)=$skip exercised(pass changes the program)=$ex"
rm -rf $W
