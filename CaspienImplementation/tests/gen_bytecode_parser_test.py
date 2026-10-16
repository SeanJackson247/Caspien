#!/usr/bin/env python3
"""usage: gen_bytecode_parser_test.py > tests/bytecode_text_4_test.caspien
Group 4 of the BytecodeParser port test (see gen_bytecode_text_test.py): parseLine, parse + serialize, parseText vs Files.readAllLines, and scripted
BytecodeStatement / BytecodeProgram operation sequences, each compared with what the Java classes produce."""
import os, random, sys, tempfile, shutil
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_bytecode_text_test as G

HEAD_SPLIT = G.JAVA_HEAD.index("  public static void main")
EXTRA = r'''
  static BytecodeToken tk(String t, int line, boolean str) { return new BytecodeToken(t, "f", line, str ? BytecodeTokenKind.STRING : BytecodeTokenKind.CODE); }
  static BytecodeStatement mkStm(int n) { BytecodeStatement st = new BytecodeStatement(); if (n >= 1) st.add(tk("A", 1, false)); if (n >= 2) st.add(tk("\"b c\"", 1, true)); if (n >= 3) st.add(tk("7", 1, false)); return st; }
  static BytecodeProgram mkProg(int n) { BytecodeProgram p = new BytecodeProgram(); for (int i = 0; i < n; i++) p.add(mkStm(i % 4)); return p; }
  static String ser(BytecodeProgram p) { return new BytecodeSerializer().serialize(p); }
  static String readLines(String text) throws Exception { Path f = Files.createTempFile("bpt", ".hob"); Files.write(f, text.getBytes("ISO-8859-1")); List<String> ls = Files.readAllLines(f, java.nio.charset.StandardCharsets.ISO_8859_1); Files.delete(f); return prog(new BytecodeParser().parse(ls, "f.hob")); }
'''
JHEAD = G.JAVA_HEAD[:HEAD_SPLIT] + EXTRA + G.JAVA_HEAD[HEAD_SPLIT:]

C_HELP = r'''
@throws
func putStm(o: ref some mut String, st: ref imut BytecodeStatement) void{
	?catch(e){ throw e }
	let n = mut 0
	match Some(st){ n = st.size(st) }
	for i in 0..n{
		if i > 0{ ? o.appendChar(o, ' ') }
		let k = mut 0
		let:<mut s32> ln = mut 0
		let file = mut ? new String()
		let tx = mut ? new String()
		match Some(st){
			let t = mut st.get(st, i)
			match Some(t){
				if t.kind == BytecodeTokenKind.STRING{ k = 1 }
				if t.kind == BytecodeTokenKind.COMMENT{ k = 2 }
				ln = t.line + 0
			}
		}
		match Some(st){
			let t = mut st.get(st, i)
			match Some(t){ ? file.append(file, t.file) }
		}
		match Some(st){
			let t = mut st.get(st, i)
			match Some(t){ ? tx.append(tx, t.text) }
		}
		? o.appendU64(o, k)
		? o.appendChar(o, ':')
		? o.appendS64(o, wrap:<s64>(ln))
		? o.appendChar(o, ':')
		? o.append(o, file)
		? o.appendChar(o, ':')
		? o.appendU64(o, tx.length(tx))
		? o.appendChar(o, ':')
		? o.append(o, tx)
	}
}

@throws
func putProg(o: ref some mut String, p: ref imut BytecodeProgram) void{
	?catch(e){ throw e }
	let n = mut 0
	match Some(p){ n = p.size(p) }
	for i in 0..n{
		if i > 0{ ? o.appendChar(o, '|') }
		match Some(p){
			let st = mut p.get(p, i)
			? putStm(o, st)
		}
	}
}

@throws
func mkTok(text: static imut string, line: imut s32, kind: imut BytecodeTokenKind) owns some mut BytecodeToken{
	?catch(e){ throw e }
	let a = mut ? new String(text)
	let b = mut ? new String("f")
	let t = mut ? new BytecodeToken(a, b, line, kind)
	return t
}

@throws
func mkStm(n: imut s32) owns some mut BytecodeStatement{
	?catch(e){ throw e }
	let st = mut ? new BytecodeStatement()
	if n >= 1{
		let t1 = mut ? mkTok("A", 1, BytecodeTokenKind.CODE)
		? st.add(st, t1)
	}
	if n >= 2{
		let t2 = mut ? mkTok("\"b c\"", 1, BytecodeTokenKind.STRING)
		? st.add(st, t2)
	}
	if n >= 3{
		let t3 = mut ? mkTok("7", 1, BytecodeTokenKind.CODE)
		? st.add(st, t3)
	}
	return st
}

@throws
func mkProg(n: imut u64) owns some mut BytecodeProgram{
	?catch(e){ throw e }
	let p = mut ? new BytecodeProgram()
	for i in 0..n{
		let st = mut ? mkStm(wrap:<s32>(i % 4))
		? p.add(p, st)
	}
	return p
}

'''

def head():
    h = G.CHEAD.replace('import "../BytecodeParser/src/main/java/caspien/bytecode/Text.caspien"\n', 'import "../BytecodeParser/src/main/java/caspien/bytecode/Text.caspien"\nimport "../BytecodeParser/src/main/java/caspien/bytecode/BytecodeProgram.caspien"\n')
    i = h.index("func chk(")
    return h[:i] + C_HELP + h[i:]

WORDS = ["PUSH", "8", "ALLOC", "imut_u64", "@L1", "x.y", "%t0", "$-16", "[", "ab", "q"]
STRS = ['"a b"', '"x\\"y z"', '"back\\\\"', '""', '"unterminated', '"t\\', '"a"b', '"\\n x"']
CHRS = ["'a'", "' '", "'\\n'", "'\\\\'", "'ab'", "'a'x", "'", "'a", "''", "'\\'", "'\\''"]
SEPS = [" ", "  ", "\t", "\x0b", "", " "]

def rline(rng):
    parts = []
    for _ in range(rng.randint(0, 6)):
        parts.append(rng.choice(WORDS) if rng.random() < 0.5 else (rng.choice(STRS) if rng.random() < 0.5 else rng.choice(CHRS)))
        parts.append(rng.choice(SEPS))
    return "".join(parts)

def build():
    rng = random.Random(4004)
    cases = []
    def case(tag, jbody, cbody):
        cases.append((tag, jbody, cbody))
    for i in range(60):
        ln = rline(rng)
        lineno = rng.randint(1, 9)
        j = "    { String r = stm(BytecodeParser.parseLine(%s, \"f.hob\", %d)); out.append(r); end(); }\n" % (G.jlit(ln), lineno)
        c = ["\tlet a = mut ? new String(%s)" % G.clit(ln), "\tlet f = mut ? new String(\"f.hob\")", "\tlet o = mut ? new String()",
             "\tlet st = mut ? BytecodeParser.parseLine(a, f, %d)" % lineno, "\t? putStm(o, st)"]
        case("parseLine", j, c)
    for i in range(25):
        lines = [rline(rng) for _ in range(rng.randint(0, 4))]
        j = "    { BytecodeProgram p = new BytecodeParser().parse(Arrays.asList(new String[] {%s}), \"f.hob\"); out.append(prog(p) + \"#\" + rs(ser(p))); end(); }\n" % ", ".join(G.jlit(l) for l in lines)
        c = ["\tlet f = mut ? new String(\"f.hob\")", "\tlet o = mut ? new String()", "\tlet ls = mut ? new ObjectList:<String>()"]
        for k, l in enumerate(lines):
            c += ["\tlet l%d = mut ? new String(%s)" % (k, G.clit(l)), "\t? ls.add(ls, l%d)" % k]
        c += ["\tlet pr = mut ? new BytecodeParser()", "\tlet p = mut ? pr.parse(pr, ls, f)", "\t? putProg(o, p)", "\t? o.appendChar(o, '#')",
              "\tlet sr = mut ? new BytecodeSerializer()", "\tlet tx = mut ? sr.serialize(sr, p)", "\t? putS(o, tx)"]
        case("parse", j, c)
    for i in range(25):
        lines = [rline(rng) for _ in range(rng.randint(0, 4))]
        text = ""
        for k, l in enumerate(lines):
            text += l.replace("\n", "").replace("\r", "") + rng.choice(["\n", "\r\n", "\r", "\n\n"] if k < len(lines) - 1 else ["", "\n", "\r\n", "\r", "\n\n"])
        j = "    { out.append(readLines(%s)); end(); }\n" % G.jlit(text)
        c = ["\tlet a = mut ? new String(%s)" % G.clit(text), "\tlet f = mut ? new String(\"f.hob\")", "\tlet o = mut ? new String()",
             "\tlet pr = mut ? new BytecodeParser()", "\tlet p = mut ? pr.parseText(pr, a, f)", "\t? putProg(o, p)"]
        case("parseText", j, c)
    for i in range(30):
        size = 0; jl = []; cl = ["\tlet o = mut ? new String()", "\tlet st = mut ? new BytecodeStatement()", "\tlet other = mut ? mkStm(3)"]
        jl.append("BytecodeStatement st = new BytecodeStatement(); BytecodeStatement other = mkStm(3); StringBuilder sb = new StringBuilder();")
        u = 0
        for step in range(14):
            u += 1
            op = rng.choice(["add", "add", "ins", "rem", "set", "slice", "addAll", "addAllAt", "clear", "text", "opcode", "line", "copy"])
            txt = rng.choice(["A", "B", "MOV", "9", "\"s t\""]); isstr = txt.startswith('"'); ln = rng.randint(1, 20)
            if op == "ins" or op == "addAllAt": idx = rng.randint(0, size)
            elif op in ("rem", "set"): idx = rng.randint(0, size - 1) if size else None
            else: idx = None
            if op in ("rem", "set") and idx is None: op = "add"
            tk_j = 'tk(%s, %d, %s)' % (G.jlit(txt), ln, "true" if isstr else "false")
            tk_c = 'let t%d = mut ? mkTok(%s, %d, BytecodeTokenKind.%s)' % (u, G.clit(txt), ln, "STRING" if isstr else "CODE")
            if op == "add":
                jl.append("st.add(%s);" % tk_j); cl += ["\t" + tk_c, "\t? st.add(st, t%d)" % u]; size += 1
            elif op == "ins":
                jl.append("st.add(%d, %s);" % (idx, tk_j)); cl += ["\t" + tk_c, "\t? st.insertAt(st, %d, t%d)" % (idx, u)]; size += 1
            elif op == "rem":
                jl.append("st.remove(%d);" % idx); cl += ["\t? st.removeAt(st, %d)" % idx]; size -= 1
            elif op == "set":
                jl.append("st.set(%d, %s);" % (idx, tk_j)); cl += ["\t" + tk_c, "\tst.set(st, %d, t%d)" % (idx, u)]
            elif op == "slice":
                a = rng.randint(0, size); b = rng.randint(a, size)
                jl.append("sb.append(stm(new BytecodeStatement(st.slice(%d, %d)))).append('~');" % (a, b))
                cl += ["\tlet sl%d = mut ? st.slice(st, %d, %d)" % (u, a, b), "\t? putStm(o, sl%d)" % u, "\t? o.appendChar(o, '~')"]
            elif op == "addAll":
                jl.append("st.addAll(other);"); cl += ["\t? st.addAll(st, other)"]; size += 3
            elif op == "addAllAt":
                jl.append("st.addAll(%d, other.asList());" % idx); cl += ["\t? st.addAllAt(st, %d, other)" % idx]; size += 3
            elif op == "clear":
                jl.append("st.clear();"); cl += ["\t? st.clear(st)"]; size = 0
            elif op == "text":
                k = rng.randint(0, size + 1)
                jl.append("sb.append(rs(st.text(%d))).append('~');" % k); cl += ["\tlet x%d = mut ? st.text(st, %d)" % (u, k), "\t? putS(o, x%d)" % u, "\t? o.appendChar(o, '~')"]
            elif op == "opcode":
                jl.append("sb.append(rs(st.opcode())).append('~');"); cl += ["\tlet x%d = mut ? st.opcode(st)" % u, "\t? putS(o, x%d)" % u, "\t? o.appendChar(o, '~')"]
            elif op == "line":
                jl.append("sb.append(st.sourceLine()).append('~');"); cl += ["\t? o.appendS64(o, wrap:<s64>(st.sourceLine(st)))", "\t? o.appendChar(o, '~')"]
            elif op == "copy":
                jl.append("sb.append(stm(new BytecodeStatement(st))).append('~');"); cl += ["\tlet cp%d = mut ? BytecodeStatement.copyOf(st)" % u, "\t? putStm(o, cp%d)" % u, "\t? o.appendChar(o, '~')"]
            jl.append("sb.append(stm(st)).append('#');"); cl += ["\t? putStm(o, st)", "\t? o.appendChar(o, '#')"]
        case("statement", "    { " + " ".join(jl) + " out.append(sb); end(); }\n", cl)
    for i in range(30):
        size = 0; jl = ["BytecodeProgram p = new BytecodeProgram(); BytecodeProgram other = mkProg(3); StringBuilder sb = new StringBuilder();"]
        cl = ["\tlet o = mut ? new String()", "\tlet p = mut ? new BytecodeProgram()", "\tlet other = mut ? mkProg(3)"]
        u = 0
        for step in range(14):
            u += 1
            op = rng.choice(["add", "add", "ins", "rem", "set", "slice", "addAll", "addAllAt", "removeRange", "clear", "get", "copy"])
            n = rng.randint(0, 3)
            if op in ("ins", "addAllAt"): idx = rng.randint(0, size)
            elif op in ("rem", "set"): idx = rng.randint(0, size - 1) if size else None
            else: idx = None
            if op in ("rem", "set") and idx is None: op = "add"
            if op == "add":
                jl.append("p.add(mkStm(%d));" % n); cl += ["\tlet s%d = mut ? mkStm(%d)" % (u, n), "\t? p.add(p, s%d)" % u]; size += 1
            elif op == "ins":
                jl.append("p.add(%d, mkStm(%d));" % (idx, n)); cl += ["\tlet s%d = mut ? mkStm(%d)" % (u, n), "\t? p.insertAt(p, %d, s%d)" % (idx, u)]; size += 1
            elif op == "rem":
                jl.append("p.remove(%d);" % idx); cl += ["\t? p.removeAt(p, %d)" % idx]; size -= 1
            elif op == "set":
                jl.append("p.set(%d, mkStm(%d));" % (idx, n)); cl += ["\tlet s%d = mut ? mkStm(%d)" % (u, n), "\tp.set(p, %d, s%d)" % (idx, u)]
            elif op == "slice":
                a = rng.randint(0, size); b = rng.randint(a, size)
                jl.append("sb.append(prog(p.slice(%d, %d))).append('~');" % (a, b)); cl += ["\tlet sl%d = mut ? p.slice(p, %d, %d)" % (u, a, b), "\t? putProg(o, sl%d)" % u, "\t? o.appendChar(o, '~')"]
            elif op == "addAll":
                jl.append("p.addAll(other);"); cl += ["\t? p.addAll(p, other)"]; size += 3
            elif op == "addAllAt":
                jl.append("p.addAll(%d, other);" % idx); cl += ["\t? p.addAllAt(p, %d, other)" % idx]; size += 3
            elif op == "removeRange":
                a = rng.randint(0, size); b = rng.randint(a, size)
                jl.append("p.removeRange(%d, %d);" % (a, b)); cl += ["\t? p.removeRange(p, %d, %d)" % (a, b)]; size -= (b - a)
            elif op == "clear":
                jl.append("p.clear();"); cl += ["\t? p.clear(p)"]; size = 0
            elif op == "get":
                k = rng.randint(0, size)
                jl.append("sb.append(%d < p.size() ? stm(p.get(%d)) : \"N\").append('~');" % (k, k))
                cl += ["\tlet g%d = mut p.get(p, %d)" % (u, k), "\tlet gs%d = mut ? new String()" % u, "\tmatch Some(g%d){ ? putStm(gs%d, g%d) }" % (u, u, u),
                       "\tlet isn%d = mut true" % u, "\tmatch Some(g%d){ isn%d = false }" % (u, u), "\tif isn%d{ ? gs%d.appendChar(gs%d, 'N') }" % (u, u, u), "\t? o.append(o, gs%d)" % u, "\t? o.appendChar(o, '~')"]
            elif op == "copy":
                jl.append("sb.append(prog(new BytecodeProgram(p))).append('~');"); cl += ["\tlet cp%d = mut ? BytecodeProgram.copyOf(p)" % u, "\t? putProg(o, cp%d)" % u, "\t? o.appendChar(o, '~')"]
            jl.append("sb.append(prog(p)).append('#');"); cl += ["\t? putProg(o, p)", "\t? o.appendChar(o, '#')"]
        case("program", "    { " + " ".join(jl) + " out.append(sb); end(); }\n", cl)

    tmp = tempfile.mkdtemp(prefix="bpt", dir="/tmp/bpt")
    try:
        saved = G.JAVA_HEAD
        G.JAVA_HEAD = JHEAD
        wants = G.run_java("".join(c[1] for c in cases), tmp)
        G.JAVA_HEAD = saved
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    assert len(wants) == len(cases), (len(wants), len(cases))
    out = [head()]
    for i, (c, w) in enumerate(zip(cases, wants)):
        out.append("@throws\nfunc t%d() mut u64{\n\t?catch(e){ throw e }\n" % i + "\n".join(c[2]) + "\n\treturn chk(%s, o, %s)\n}\n" % (G.clit("%s#%d" % (c[0], i)), G.clit(w)))
    out.append("@throws\nfunc main() void{\n\t?catch(e){ throw e }\n\tlet bad = mut 0\n")
    for i in range(len(cases)):
        out.append("\tlet r%d = mut ? t%d()\n\tbad = bad + r%d\n" % (i, i, i))
    out.append('\tlet total = mut %d\n\tif bad == 0{\n\t\tprint("PASS bytecode_text ")\n\t} else {\n\t\tprint("FAIL bytecode_text ")\n\t}\n\tprint(total)\n\tprint(" cases, failed ")\n\tprintln(bad)\n}\n' % len(cases))
    return "".join(out)

if __name__ == "__main__":
    print(build())
