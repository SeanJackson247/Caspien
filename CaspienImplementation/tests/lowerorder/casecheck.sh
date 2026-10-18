#!/bin/bash
# casecheck.sh EXE CORPUS HOBFILE_BASENAME : dumps the Java per-pass gold of ONE case (hob/<name>.hob), checks every pass of the Caspien exe against it
# (input = Java output of the step before), prints "BAD <case> <pass>" lines and "case <name> ok=N bad=M exercised=K", then deletes the gold (disk is scarce).
# env SKIP="pass1 pass2" leaves passes out; env KEEP=1 keeps the gold.
EXE=$1; C=$2; b=$3; v=${b%%__*}
R=/home/claude/caspien/ReferenceImplementation
export JAVA_TOOL_OPTIONS=
d=$C/gold/$b
if [ ! -f $d/steps.txt ]; then
  rm -rf $d
  java -Xss512m -cp /tmp/pdout:$R/LowerOrderGenerator/out:$R/BytecodeParser/out caspien.lowerorder.PassDump $C/hob/$b.hob $C/cfg/$v.config $d > /dev/null 2>&1
fi
[ -f $d/steps.txt ] || { echo "NOGOLD $b"; exit 1; }
W=$(mktemp -d); cp $C/cfg/$v.config $W/compiler.config
ok=0; bad=0; ex=0
while read nn p; do
  case " $SKIP " in *" $p "*) continue;; esac
  prev=$(printf "%02d" $((10#$nn-1)))
  inf=$(ls $d/${prev}_*.txt 2>/dev/null | head -1); exp=$d/${nn}_$p.txt
  [ -f "$inf" ] && [ -f "$exp" ] || continue
  cmp -s $inf $exp || ex=$((ex+1))
  (cd $W && $EXE -i $inf out.txt --pass $p >/dev/null 2>err.txt)
  if cmp -s $W/out.txt $exp; then ok=$((ok+1)); else bad=$((bad+1)); echo "BAD $b $p"; fi
  rm -f $W/out.txt
done < $d/steps.txt
echo "case $b ok=$ok bad=$bad exercised=$ex"
rm -rf $W
[ -n "$KEEP" ] || rm -rf $d
