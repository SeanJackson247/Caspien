# Generates tests/string_methods2_test.caspien: indexOf/contains/substring/trim/replace/replaceRange/append/
# fromS64/fromU64/parseS64/parseU64/parseS32. Expected values come from Python str/int.
import itertools
pool = ["", "a", "ab", "abc", "abcabc", "hello", "hello world", "world", "lo", "x_y.z", "aaa", "a.b.c"]
trimpool = ["", " ", "  ", "a", " a", "a ", "  ab  ", " a b ", "\\n x \\n", "abc"]
L = lambda s: '"' + s + '"'
def unesc(s): return s.replace("\\n", "\n")
B = lambda x: 'true' if x else 'false'
out = []
w = out.append
w('import "../stdlib/libc.caspien"')
w('import "../stdlib/gt/*"')
w('import "../stdlib/print.caspien"')
w('import "../stdlib/string.caspien"')
w('')
w('func report(name: static imut string, ok: imut bool) void{')
w('\tif ok{ print("PASS ") } else { print("FAIL ") }')
w('\tprintln(name)')
w('}')
w('')
# string-valued result check: build input, run op, compare to expected literal
w('@throws')
w('func idx(a: static imut string, b: static imut string, from: imut u64, c: mut char, want: imut s64, wantC: imut s64) void{')
w('\t?catch(e){ throw e }')
w('\tlet sa = mut ? new String(a)')
w('\tlet sb = mut ? new String(b)')
w('\tmatch Some(sa){')
w('\t\tmatch Some(sb){')
w('\t\t\treport(a, sa.indexOf(sa, sb, from) == want)')
w('\t\t\treport(a, sa.indexOf(sa, b, from) == want)')
w('\t\t\treport(a, sa.indexOf(sa, c, from) == wantC)')
w('\t\t\treport(a, sa.contains(sa, sb) == (sa.indexOf(sa, sb, 0) >= 0))')
w('\t\t}')
w('\t}')
w('}')
w('')
w('@throws')
w('func sub1(a: static imut string, start: imut u64, want: static imut string, a2: imut u64, b2: imut u64, want2: static imut string) void{')
w('\t?catch(e){ throw e }')
w('\tlet sa = mut ? new String(a)')
w('\tmatch Some(sa){')
w('\t\tlet r = mut ? sa.substring(sa, start)')
w('\t\tmatch Some(r){ report(want, r.equals(r, want)) }')
w('\t\tlet r2 = mut ? sa.substring(sa, a2, b2)')
w('\t\tmatch Some(r2){ report(want2, r2.equals(r2, want2)) }')
w('\t}')
w('}')
w('')
w('@throws')
w('func trim1(a: static imut string, want: static imut string) void{')
w('\t?catch(e){ throw e }')
w('\tlet sa = mut ? new String(a)')
w('\tmatch Some(sa){')
w('\t\tlet r = mut ? sa.trim(sa)')
w('\t\tmatch Some(r){ report(want, r.equals(r, want)) }')
w('\t}')
w('}')
w('')
w('@throws')
w('func rep1(a: static imut string, t: static imut string, r: static imut string, want: static imut string, c1: mut char, c2: mut char, wantC: static imut string) void{')
w('\t?catch(e){ throw e }')
w('\tlet sa = mut ? new String(a)')
w('\tlet st = mut ? new String(t)')
w('\tlet sr = mut ? new String(r)')
w('\tmatch Some(sa){')
w('\t\tmatch Some(st){')
w('\t\t\tmatch Some(sr){')
w('\t\t\t\tlet x = mut ? sa.replace(sa, st, sr)')
w('\t\t\t\tmatch Some(x){ report(want, x.equals(x, want)) }')
w('\t\t\t\tlet y = mut ? sa.replace(sa, t, r)')
w('\t\t\t\tmatch Some(y){ report(want, y.equals(y, want)) }')
w('\t\t\t\tlet z = mut ? sa.replace(sa, c1, c2)')
w('\t\t\t\tmatch Some(z){ report(wantC, z.equals(z, wantC)) }')
w('\t\t\t}')
w('\t\t}')
w('\t}')
w('}')
w('')
w('@throws')
w('func rr1(a: static imut string, s: imut u64, e2: imut u64, r: static imut string, want: static imut string) void{')
w('\t?catch(e){ throw e }')
w('\tlet sa = mut ? new String(a)')
w('\tlet sr = mut ? new String(r)')
w('\tmatch Some(sa){')
w('\t\tmatch Some(sr){')
w('\t\t\t? sa.replaceRange(sa, s, e2, sr)')
w('\t\t\treport(want, sa.equals(sa, want))')
w('\t\t}')
w('\t}')
w('}')
w('')
w('@throws')
w('func num1(v: imut s64, want: static imut string, u: imut u64, wantU: static imut string, total: imut u64) void{')
w('\t?catch(e){ throw e }')
w('\tlet a = mut ? String.fromS64(v)')
w('\tmatch Some(a){ report(want, a.equals(a, want)) }')
w('\tlet b = mut ? String.fromU64(u)')
w('\tmatch Some(b){ report(wantU, b.equals(b, wantU)) }')
w('\tlet c = mut ? new String("x=")')
w('\tmatch Some(c){')
w('\t\t? c.appendS64(c, v)')
w('\t\t? c.appendU64(c, u)')
w('\t\t? c.append(c, "!")')
w('\t\t? c.append(c, \'?\')')
w('\t\tlet d = mut ? new String("#")')
w('\t\tmatch Some(d){ ? c.append(c, d) }')
w('\t\treport(want, c.length(c) == total)')
w('\t}')
w('}')
w('')
# parse: report ok & value, or the throw
w('func parse1(text: static imut string, okS: imut bool, vS: imut s64, okU: imut bool, vU: imut u64, ok32: imut bool, v32: imut s32) void{')
w('\t?catch(e){ report(text, okS == false); return }')
w('\tlet s = mut ? new String(text)')
w('\tmatch Some(s){')
w('\t\tlet x = mut ? s.parseS64(s)')
w('\t\treport(text, okS)')
w('\t\treport(text, x == vS)')
w('\t}')
w('}')
w('func parse2(text: static imut string, okU: imut bool, vU: imut u64) void{')
w('\t?catch(e){ report(text, okU == false); return }')
w('\tlet s = mut ? new String(text)')
w('\tmatch Some(s){')
w('\t\tlet x = mut ? s.parseU64(s)')
w('\t\treport(text, okU)')
w('\t\treport(text, x == vU)')
w('\t}')
w('}')
w('func parse3(text: static imut string, ok32: imut bool, v32: imut s32) void{')
w('\t?catch(e){ report(text, ok32 == false); return }')
w('\tlet s = mut ? new String(text)')
w('\tmatch Some(s){')
w('\t\tlet x = mut ? s.parseS32(s)')
w('\t\treport(text, ok32)')
w('\t\treport(text, x == v32)')
w('\t}')
w('}')
w('')
for k in range(0,3):
    w('func part%d() void{' % k); w('\t?catch(e){ return }')
    if k == 0:
        for a, b in itertools.product(pool, pool):
            for fr in (0, 2, 50):
                want = (min(fr, len(a)) if b == "" else a.find(b, fr))
                c = b[0] if b else 'a'
                wc = a.find(c, fr)
                w("\t? idx(%s, %s, %d, '%s', %d, %d)" % (L(a), L(b), fr, c, want, wc))
        for a in pool:
            for st in (0, 1, 3, 20):
                for (x, y) in ((0, 2), (1, 4), (2, 2), (3, 1), (0, 99)):
                    w('\t? sub1(%s, %d, %s, %d, %d, %s)' % (L(a), st, L(a[st:]), x, y, L(a[x:y] if x < len(a) else "")))
        for t in trimpool:
            w('\t? trim1(%s, %s)' % (L(t), L(unesc(t).strip(" \n").replace("\n", "\\n"))))
    if k == 1:
        for a in pool:
            for t in ("", "a", "ab", "l", "lo", "abc", ".", "aa"):
                for r in ("", "X", "yy", "abc"):
                    want = a if t == "" else a.replace(t, r)
                    c1 = 'a'; c2 = 'Z'
                    w("\t? rep1(%s, %s, %s, %s, 'a', 'Z', %s)" % (L(a), L(t), L(r), L(want), L(a.replace('a', 'Z'))))
        for a in pool:
            for (s, e) in ((0, 0), (0, 2), (1, 3), (2, 99), (99, 100), (3, 1)):
                for r in ("", "Q", "QQQQ"):
                    n = len(a); aa = min(s, n); bb = min(max(e, aa), n) if True else 0
                    bb = min(e, n); bb = max(bb, aa)
                    w('\t? rr1(%s, %d, %d, %s, %s)' % (L(a), s, e, L(r), L(a[:aa] + r + a[bb:])))
    if k == 2:
        vals = [0, 1, -1, 9, 10, -10, 12345, -12345, 2147483647, -2147483648, 4294967296, 9223372036854775807, -9223372036854775808]
        uvals = [0, 1, 10, 4294967295, 9223372036854775808, 18446744073709551615]
        for v in vals:
            for u in uvals[:3] + uvals[-2:]:
                w('\t? num1(%d, %s, %d, %s, %d)' % (v, L(str(v)), u, L(str(u)), len(str(v)) + len(str(u)) + 5))
        texts = ["0", "-0", "+0", "7", "-7", "+7", "007", "123456789012", "-123456789012", "2147483647", "2147483648", "-2147483648", "-2147483649",
                 "9223372036854775807", "9223372036854775808", "-9223372036854775808", "-9223372036854775809", "18446744073709551615", "18446744073709551616",
                 "99999999999999999999", "", "-", "+", "a", "1a", "a1", "1 ", " 1", "1.5", "--1", "+-1", "1-"]
        for t in texts:
            try:
                x = int(t) if t.strip() == t and t and t.replace("_", "") == t and t not in ("+", "-") else None
                if x is not None and not (t.lstrip("+-").isdigit()): x = None
                if x is not None and (t.startswith("--") or t.startswith("+-")): x = None
            except ValueError:
                x = None
            okS = x is not None and -2**63 <= x < 2**63
            okU = x is not None and 0 <= x < 2**64 and not t.startswith("-")
            ok32 = x is not None and -2**31 <= x < 2**31
            w('\t parse1(%s, %s, %d, %s, 0, %s, 0)' % (L(t), B(okS), x if okS else 0, B(okU), B(ok32)))
            w('\t parse2(%s, %s, %d)' % (L(t), B(okU), x if okU else 0))
            w('\t parse3(%s, %s, %d)' % (L(t), B(ok32), x if ok32 else 0))
    w('}')
w('func main() void{')
w('\tpart0()')
w('\tpart1()')
w('\tpart2()')
w('}')
open('tests/string_methods2_test.caspien', 'w').write('\n'.join(out) + '\n')
