#!/usr/bin/env python3
"""usage: gen_bytecode_text_test.py GROUP > tests/bytecode_text_GROUP_test.caspien   (GROUP 1..4)
Differential test of the Caspien port of BytecodeParser/ against the Java original in ReferenceImplementation/BytecodeParser: the same inputs go to the
Java `Text`/parser/serializer (a generated harness, compiled here) and to the Caspien port; both render results the same way and the Caspien test
compares with what Java printed. Inputs are seeded random strings built from fragments (ASCII only: the port is byte based)."""
import os, random, subprocess, sys, tempfile, shutil
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REF = os.path.join(os.path.dirname(ROOT), "ReferenceImplementation", "BytecodeParser", "src", "main", "java")
FRAG = ["unsafe", "unaudited", " ", "  ", "\t", "\n", "\r\n", "\r", "{", "}", "//", "// c", "u8", "s16", "u32", "u64", "f32", "bool", "char", "mut_", "imut_", "indeterminate_",
        "range", "(", ")", "[", "]", "12", "007", "0", "00", ".", "-", ":", "\"", ";", "#", "start", "end", "r8", "r15", "x", "_", "e", "E", "+", "a.b", "%t", ".caspien",
        "\x0b", "\x0c", "ab", "Z9", "rbx", "bl", "$"]
ALPHA = "abAZ09_ .-:\"#;{}/\\\t\n\r[]()+%$ux"

def rs(rng, kind=None):
    n = rng.choice([0, 1, 2, 3, 4, 5, 6, 8])
    out = []
    for _ in range(n):
        out.append(rng.choice(FRAG) if rng.random() < 0.8 else rng.choice(ALPHA))
    return "".join(out)

def jlit(s):
    o = ['"']
    for ch in s:
        c = ord(ch)
        if ch == '"': o.append('\\"')
        elif ch == '\\': o.append('\\\\')
        elif ch == '\n': o.append('\\n')
        elif ch == '\r': o.append('\\r')
        elif ch == '\t': o.append('\\t')
        elif 32 <= c < 127: o.append(ch)
        else: o.append('\\u%04x' % c)
    o.append('"')
    return "".join(o)

def clit(s):
    o = ['"']
    for ch in s:
        c = ord(ch)
        if ch in '"\\$`': o.append('\\' + ch)
        elif ch == '\n': o.append('\\n')
        elif ch == '\r': o.append('\\r')
        elif ch == '\t': o.append('\\t')
        elif 32 <= c < 127: o.append(ch)
        else: o.append('\\x%02x' % c)
    o.append('"')
    return "".join(o)

# name: (arg kinds, result kind, throws in Caspien, extra targeted inputs)
# arg kinds: s string, S second string, c chars string, b bool, m mode 0..2, l limit, p separator char, n names list
SPEC = {
 "isDigits": ("s", "B", False), "isSignedDigits": ("s", "B", False), "isDecimalNumber": ("s", "B", False), "isWord": ("s", "B", False),
 "isDottedName": ("s", "B", False), "isHighGpr": ("s", "B", False), "isDigitsDotStartOrEnd": ("s", "B", True), "isRangeType": ("s", "B", False),
 "isByValueRangeType": ("s", "B", False), "endsWithSmallIntType": ("s", "B", False), "isSizedIntName": ("s", "B", False), "isCloseLine": ("s", "B", False),
 "isPrefixedDigits": ("sS", "B", True), "isPrefixedChar": ("sSc", "B", False), "isQualifiedScalar": ("sb", "B", False),
 "stripMutPrefix": ("s", "S", True), "stripQualifierPrefixes": ("s", "S", True), "stripTrailingSpace": ("s", "S", True), "collapseSpace": ("s", "S", True),
 "stripCommentLead": ("s", "S", True), "stripLeadingZeros": ("s", "S", True), "stripLineNumberBeforeQuote": ("s", "S", True),
 "replaceNonWordChars": ("sS", "S", True), "stripSuffixAtEnd": ("sS", "S", True),
 "splitArrayType": ("sm", "L", True), "unsafeOpenParts": ("s", "L", True), "findUnsafeUnaudited": ("s", "P", True),
 "containsWord": ("sSSb", "B", False), "containsAnyWord": ("snS", "B", False),
 "split": ("spl", "L", True), "splitSpace": ("sl", "L", True), "splitNonWord": ("s", "L", True), "splitLineBreaks": ("s", "L", True), "splitNewlines": ("sl", "L", True),
}
GROUPS = {
 "1": ["isDigits", "isSignedDigits", "isDecimalNumber", "isWord", "isDottedName", "isHighGpr", "isDigitsDotStartOrEnd", "isRangeType", "isByValueRangeType",
       "endsWithSmallIntType", "isSizedIntName", "isCloseLine", "isPrefixedDigits", "isPrefixedChar", "isQualifiedScalar"],
 "2": ["stripMutPrefix", "stripQualifierPrefixes", "stripTrailingSpace", "collapseSpace", "stripCommentLead", "stripLeadingZeros", "stripLineNumberBeforeQuote",
       "replaceNonWordChars", "stripSuffixAtEnd"],
 "3": ["splitArrayType", "unsafeOpenParts", "findUnsafeUnaudited", "containsWord", "containsAnyWord", "split", "splitSpace", "splitNonWord", "splitLineBreaks", "splitNewlines"],
}
TARGET = {
 "isDigits": ["0", "12", "", "1a", "-1"], "isSignedDigits": ["-12", "-", "5", "--1", ""], "isDecimalNumber": ["1.5", "-1.5e+3", "1.", ".5", "1.5E-2", "1.5e", "-0.0", "12.34.5", "1.5e+"],
 "isHighGpr": ["r8", "r9", "r10", "r15", "r16", "r1", "r", "rax", "r08", "r5"], "isDigitsDotStartOrEnd": ["12.start", "3.end", ".start", "1.2.end", "5.begin", "7.start\n"],
 "isRangeType": ["range", "imut_range", "mut_range", "xrange"], "isByValueRangeType": ["imut_range", "mut_range(1)", "indeterminate_range(a,b)", "mut_range()", "mut_range(", "imut_range(x\ny)", "range", "mut_rangex"],
 "endsWithSmallIntType": ["mut_u8", "s16", "u64", "x\ns32", "u32\n", "u"], "isSizedIntName": ["u8", "s64", "u", "x8", "u08", "s1x"], "isCloseLine": ["}", "  } // c", "}x", "} //", "\t}\t", "} //a\nb", ""],
 "isQualifiedScalar": ["mut_u8", "imut_f64", "indeterminate_bool", "mut_char", "mut_u128", "x_u8", "mut_", "mut"],
 "stripMutPrefix": ["mut_x", "imut_x", "mut_", "xmut_", "imut_mut_x"], "stripQualifierPrefixes": ["mut_imut_indeterminate_x", "indeterminate_", "mut_mut_u8", "x"],
 "stripTrailingSpace": ["a  ", "a \n", "a\n\n", "a \r\n", " ", "", "a b", "a  b  ", "a\r"], "collapseSpace": ["a  b", " a ", "\t\n", "", "a\x0bb"],
 "stripCommentLead": ["# x", ";x", "#", "x#", "#  \t y", ""], "stripLeadingZeros": ["000", "0012", "0", "0\n", "00\n1", "x00", ""],
 "stripLineNumberBeforeQuote": ['a:12"', 'a:12" ', 'x:"', ':1"\n', 'a:1"b:22"', 'a:1:2"'], "replaceNonWordChars": ["a.b-c", "", "abc", "a b"],
 "stripSuffixAtEnd": ["x.caspien", "x.caspien\n", "x.caspien.caspien", "x", ".caspien", "a.caspienb"],
 "splitArrayType": ["a[3]", "[3]", "a[]", "ab[12]", "a b[3]", "a\n[3]", "a[3]x", "a[b[3]", "x[0]"], "unsafeOpenParts": ["unsafe {", "  unsafe memcopy raw{ // why", "unsafex {", "unsafe", "unsafe { x", "\tunsafe unaudited{  ", "unsafe{//a\nb"],
 "findUnsafeUnaudited": ["unsafe unaudited{", "xunsafe unaudited {", "unsafe  unaudited", "unsafe unauditedx{", "a unsafe unaudited { b", "unsafeunaudited{"],
}

def gen_cases(fn, rng):
    kinds = SPEC[fn][0]
    cases = []
    for t in TARGET.get(fn, []):
        cases.append(t)
    seen = set()
    n = 0
    while n < (60 if fn.startswith('split') else 34):
        n += 1
        cases.append(None)
    out = []
    for c in cases:
        args = []
        for k in kinds:
            if k == "s": args.append(c if c is not None else rs(rng))
            elif k == "S": args.append(rng.choice(["", "x", "ab", ".caspien", "start", "r", "_", "e", "-", "u8"]) if rng.random() < 0.7 else rs(rng))
            elif k == "c": args.append(rng.choice(["", "0123", "abc", "01", "x"]))
            elif k == "b": args.append(rng.random() < 0.5)
            elif k == "m": args.append(rng.choice([0, 1, 2]))
            elif k == "l": args.append(rng.choice([0, 0, 1, 2, 3, -1]))
            elif k == "p": args.append(rng.choice([",", "a", " ", "\n", "_", "x"]))
            elif k == "n": args.append(rng.choice([["rbx", "bl"], ["r12", "r13", "x"], ["a"], []]))
        if fn in ("containsWord", "containsAnyWord"):
            # make the haystack likely to contain the needle
            hay = args[0]
            if kinds[0] == "s" and rng.random() < 0.7:
                nm = args[1] if kinds[1] == "S" else (args[1][0] if args[1] else "x")
                hay = rs(rng) + rng.choice(["", " ", ".", "_"]) + (nm if isinstance(nm, str) else "x") + rng.choice(["", " ", "d", "_", "w"]) + rs(rng)
                args[0] = hay
        if fn in ("split", "splitSpace", "splitNonWord", "splitLineBreaks", "splitNewlines") and c is None and rng.random() < 0.85:
            seps = [" ", "  ", "\t", "\n", "\r\n", "\r", "\x0b", "\x0c", ",", "a", ".", "_", "-", "\n\n", " \n"]
            words = ["", "x", "ab", "u8", "12", "Q_"]
            parts = [rng.choice(seps) if rng.random() < 0.5 else rng.choice(words) for _ in range(rng.randint(0, 9))]
            if kinds[1:2] == "p":
                parts = [args[1] if p in seps and rng.random() < 0.6 else p for p in parts]
            args[0] = "".join(parts)
        if fn in ("splitArrayType",) and c is None:
            args[0] = rng.choice(["", "ab", "n"]) + "[" + rng.choice(["", "7", "12", "x"]) + "]" + rng.choice(["", "", "z"])
        if fn in ("isHighGpr",) and c is None:
            args[0] = "r" + str(rng.randint(0, 20))
        if fn in ("isDecimalNumber",) and c is None:
            args[0] = rng.choice(["", "-"]) + rng.choice(["", "1", "42"]) + rng.choice([".", ""]) + rng.choice(["", "5", "75"]) + rng.choice(["", "e", "E+3", "e-1", "e+"])
        out.append(tuple(args))
    return out

def java_call(fn, a, kinds):
    parts = []
    for k, v in zip(kinds, a):
        if k in "sSc": parts.append(jlit(v))
        elif k == "b": parts.append("true" if v else "false")
        elif k in "ml": parts.append(str(v))
        elif k == "p": parts.append("'" + (v if v not in ("\n",) else "\\n") + "'")
        elif k == "n": parts.append("new String[] {" + ", ".join(jlit(x) for x in v) + "}")
    return "Text.%s(%s)" % (fn, ", ".join(parts))

JAVA_HEAD = """package caspien.bytecode;
import java.util.*;
import java.nio.file.*;
public class H {
  static StringBuilder out = new StringBuilder();
  static void end() { out.append('\\0'); }
  static void b(boolean x) { out.append(x ? "T" : "F"); end(); }
  static String rs(String s) { return s == null ? "N" : s.length() + ":" + s; }
  static void s(String x) { out.append(rs(x)); end(); }
  static void l(String[] x) { if (x == null) { out.append("N"); } else { out.append("["); for (int i = 0; i < x.length; i++) { if (i > 0) out.append(","); out.append(rs(x[i])); } out.append("]"); } end(); }
  static void p(int[] x) { if (x == null) out.append("N"); else out.append("S" + x[0] + "," + x[1]); end(); }
  static String stm(BytecodeStatement st) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < st.size(); i++) { BytecodeToken t = st.get(i); if (i > 0) sb.append(' '); sb.append(t.kind.ordinal() + ":" + t.line + ":" + t.file + ":" + rs(t.text)); } return sb.toString(); }
  static String prog(BytecodeProgram p) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < p.size(); i++) { if (i > 0) sb.append('|'); sb.append(stm(p.get(i))); } return sb.toString(); }
  public static void main(String[] args) throws Exception {
"""
JAVA_TAIL = """    System.out.write(out.toString().getBytes("ISO-8859-1")); System.out.flush();
  }
}
"""

def put(fn, kind):
    return {"B": "b", "S": "s", "L": "l", "P": "p"}[kind]

def run_java(body, tmp):
    src = os.path.join(tmp, "caspien", "bytecode"); os.makedirs(src, exist_ok=True)
    open(os.path.join(src, "H.java"), "w", encoding="latin-1").write(JAVA_HEAD + body + JAVA_TAIL)
    cls = os.path.join(tmp, "cls"); os.makedirs(cls, exist_ok=True)
    files = [os.path.join(REF, "caspien", "bytecode", f) for f in os.listdir(os.path.join(REF, "caspien", "bytecode")) if f.endswith(".java")] + [os.path.join(src, "H.java")]
    subprocess.check_call(["javac", "-nowarn", "-encoding", "ISO-8859-1", "-d", cls] + files, stderr=subprocess.DEVNULL)
    res = subprocess.run(["java", "-cp", cls, "caspien.bytecode.H"], capture_output=True, check=True).stdout.decode("latin-1")
    return res.split("\0")[:-1]

CHEAD = """// Differential test of the Caspien port of BytecodeParser/ against the Java original (generated by gen_bytecode_text_test.py GROUP).
import "stdlib/libc.caspien"
import "stdlib/gt/*"
import "stdlib/string.caspien"
import "stdlib/print.caspien"
import "stdlib/print_string.caspien"
import "../Codegen/src/main/java/caspien/codegen/collections.caspien"
import "../BytecodeParser/src/main/java/caspien/bytecode/Text.caspien"
import "../BytecodeParser/src/main/java/caspien/bytecode/BytecodeParser.caspien"
import "../BytecodeParser/src/main/java/caspien/bytecode/BytecodeSerializer.caspien"

@throws
func putB(o: ref some mut String, b: imut bool) void{
	?catch(e){ throw e }
	if b{ ? o.appendChar(o, 'T') } else { ? o.appendChar(o, 'F') }
}

@throws
func putS(o: ref some mut String, s: ref imut String) void{
	?catch(e){ throw e }
	let n = mut 0
	match Some(s){ n = s.length(s) }
	let isNone = mut true
	match Some(s){ isNone = false }
	if isNone{
		? o.appendChar(o, 'N')
		return
	}
	? o.appendU64(o, n)
	? o.appendChar(o, ':')
	match Some(s){ ? o.append(o, s) }
}

@throws
func putL(o: ref some mut String, l: ref imut ObjectList<String>) void{
	?catch(e){ throw e }
	let isNone = mut true
	match Some(l){ isNone = false }
	if isNone{
		? o.appendChar(o, 'N')
		return
	}
	let n = mut 0
	match Some(l){ n = l.size(l) }
	? o.appendChar(o, '[')
	for i in 0..n{
		if i > 0{ ? o.appendChar(o, ',') }
		match Some(l){
			let x = mut l.get(l, i)
			? putS(o, x)
		}
	}
	? o.appendChar(o, ']')
}

@throws
func putP(o: ref some mut String, p: ref imut Span) void{
	?catch(e){ throw e }
	let isNone = mut true
	match Some(p){ isNone = false }
	if isNone{
		? o.appendChar(o, 'N')
		return
	}
	let:<mut s64> a = mut 0
	let:<mut s64> b = mut 0
	match Some(p){
		a = p.start + 0
		b = p.end + 0
	}
	? o.appendChar(o, 'S')
	? o.appendS64(o, a)
	? o.appendChar(o, ',')
	? o.appendS64(o, b)
}

func chk(label: static imut string, got: ref imut String, want: static imut string) mut u64{
	let same = mut false
	match Some(got){ same = got.equals(got, want) }
	if same{
		return 0
	}
	print("FAIL ")
	print(label)
	print(" got ")
	println(got)
	print("   want ")
	println(want)
	return 1
}

"""

def caspien_case(idx, fn, a, kinds, rkind, throws, want):
    pre = []
    names = []
    for j, (k, v) in enumerate(zip(kinds, a)):
        if k in "sSc":
            pre.append("\tlet a%d = mut ? new String(%s)" % (j, clit(v))); names.append("a%d" % j)
        elif k == "b": names.append("true" if v else "false")
        elif k in "ml": names.append(str(v) if k == "m" else "%d" % v)
        elif k == "p": names.append("'" + {"\n": "\\n", "'": "\\'"}.get(v, v) + "'")
        elif k == "n":
            pre.append("\tlet nl = mut ? new ObjectList:<String>()")
            for x in v:
                pre.append("\tlet nx%d = mut ? new String(%s)" % (len(pre), clit(x)))
                pre.append("\t? nl.add(nl, nx%d)" % (len(pre) - 1))
            names.append("nl")
    call = "Text.%s(%s)" % (fn, ", ".join(names))
    if fn == "isQualifiedScalar" or fn == "splitArrayType":
        pass
    body = ["func t%d() mut u64{" % idx, "\t?catch(e){ throw e }"] if False else []
    lines = ["@throws", "func t%d() mut u64{" % idx, "\t?catch(e){ throw e }"] + pre + ["\tlet o = mut ? new String()"]
    if rkind == "B":
        if throws:
            lines.append("\tlet r = mut ? %s" % call); lines.append("\t? putB(o, r)")
        else:
            lines.append("\t? putB(o, %s)" % call)
    else:
        lines.append("\tlet r = mut ? %s" % call)
        lines.append("\t? put%s(o, r)" % {"S": "S", "L": "L", "P": "P"}[rkind])
    lines.append("\treturn chk(%s, o, %s)" % (clit("%s#%d" % (fn, idx)), clit(want)))
    lines.append("}")
    return "\n".join(lines) + "\n"

def build_group(group):
    rng = random.Random(1000 + int(group))
    fns = GROUPS[group]
    cases = []
    jb = []
    for fn in fns:
        kinds, rkind, throws = SPEC[fn]
        for a in gen_cases(fn, rng):
            cases.append((fn, a, kinds, rkind, throws))
            jb.append("    %s(%s);\n" % (put(fn, rkind), java_call(fn, a, kinds)))
    tmp = tempfile.mkdtemp(prefix="bpt", dir="/tmp/bpt")
    try:
        wants = run_java("".join(jb), tmp)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    assert len(wants) == len(cases), (len(wants), len(cases))
    out = [CHEAD]
    for i, (c, w) in enumerate(zip(cases, wants)):
        out.append(caspien_case(i, c[0], c[1], c[2], c[3], c[4], w))
    out.append("@throws\nfunc main() void{\n\t?catch(e){ throw e }\n\tlet bad = mut 0\n")
    for i in range(len(cases)):
        out.append("\tlet r%d = mut ? t%d()\n\tbad = bad + r%d\n" % (i, i, i))
    out.append('\tlet total = mut %d\n\tif bad == 0{\n\t\tprint("PASS bytecode_text ")\n\t} else {\n\t\tprint("FAIL bytecode_text ")\n\t}\n\tprint(total)\n\tprint(" cases, failed ")\n\tprintln(bad)\n}\n' % len(cases))
    return "".join(out)

if __name__ == "__main__":
    print(build_group(sys.argv[1]))
