#!/usr/bin/env python3
"""Compares the method names of each Codegen Java class with its codegenCaspien twin (Java names that have no func there, and the reverse)."""
import os, re, sys
J = "Codegen/src/main/java/caspien/codegen"
P = J + "/codegenCaspien"
KEYS = {"if", "for", "while", "switch", "catch", "return", "else", "new", "synchronized", "try"}
SIG = re.compile(r"^\s{4}(?:(?:public|private|protected|static|final|abstract|synchronized)\s+)*[\w<>\[\],.? ]+?\s+(\w+)\s*\([^;]*$")
def java_methods(path):
    names = set()
    for line in open(path, encoding="utf-8"):
        if line.startswith("    ") and not line.startswith("     ") and "(" in line and not line.rstrip().endswith(";"):
            m = SIG.match(line)
            if m and m.group(1) not in KEYS and line.strip().split("(")[0].strip().count(" ") >= 1:
                names.add(m.group(1))
    return names
def port_methods(path):
    return set(re.findall(r"\bfunc\s+(\w+)\s*\(", open(path, encoding="utf-8").read()))
# port-only helpers (documented in the port README) and Java files that have no twin on purpose
PORT_ONLY = {"readFile", "wire", "rfTempRegName", "rfVarRegName", "sliceEq", "tokenIs", "tokenIsDigits", "tokenS64", "partCopy", "isStrideKeepOp", "xmmName", "sizedRegLiteral", "textIs", "rawLit", "rawLitE", "rawNum", "divLoadDividend", "op2S", "pct", "rawS", "regStr", "tempIsReg", "xmmAStr", "xmmBStr", "xvStr", "repackInfo", "repackTokenText", "repackTotal", "repackLoop", "lineTokenText"}
NO_TWIN = {"CodegenException.java"}
bad = 0
for root, _, files in os.walk(J):
    if root.startswith(P):
        continue
    for f in sorted(files):
        if not f.endswith(".java"):
            continue
        rel = os.path.relpath(os.path.join(root, f), J)
        if f in NO_TWIN:
            continue
        twin = os.path.join(P, rel[:-5] + ".caspien")
        if not os.path.exists(twin):
            print("MISSING twin", rel); bad += 1; continue
        a, b = java_methods(os.path.join(root, f)), port_methods(twin)
        # Java raw() is raw_ in the port
        a = {n for n in a if n != f[:-5]}  # constructors
        b = b - PORT_ONLY
        a = {("raw_" if n == "raw" else n) for n in a}
        only_j, only_p = sorted(a - b), sorted(b - a)
        if only_j or only_p:
            bad += 1
            print(rel, "| only in Java:", only_j, "| only in port:", only_p)
print("parity OK" if not bad else "%d file(s) differ" % bad)
sys.exit(1 if bad else 0)
