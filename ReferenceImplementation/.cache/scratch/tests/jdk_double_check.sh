#!/bin/sh
# Builds tests/jdk_double_test.caspien, runs it and the Java oracle (JdkDoubleOracle.java), diffs the two outputs line by line. Run from ReferenceImplementation/.
set -e
T=${TMPDIR:-/tmp}/jdk_double_check.$$
mkdir -p $T
java Compiler -i tests/jdk_double_test.caspien $T/jt >/dev/null
javac -d $T tests/JdkDoubleOracle.java
$T/jt > $T/caspien.txt
java -cp $T JdkDoubleOracle > $T/java.txt
wc -l $T/caspien.txt $T/java.txt
if cmp -s $T/caspien.txt $T/java.txt; then echo PASS jdk_double; else diff $T/caspien.txt $T/java.txt | head -20; echo FAIL jdk_double; exit 1; fi
rm -rf $T
