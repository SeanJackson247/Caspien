# Generates tests/string_methods_test.caspien: String.length/isEmpty/equals/startsWith/endsWith
# (String and literal overloads), expected values from Python's str.
import itertools
pool = ["", "a", "ab", "abc", "abcabc", "hello", "hello world", "world", "lo", "x_y.z", "ABC", "aBc"]
out = []
w = out.append
w('import "stdlib/libc.caspien"')
w('import "stdlib/gt/*"')
w('import "stdlib/print.caspien"')
w('import "stdlib/string.caspien"')
w('')
w('func report(name: static imut string, ok: imut bool) void{')
w('\tif ok{ print("PASS ") } else { print("FAIL ") }')
w('\tprintln(name)')
w('}')
w('')
w('@throws')
w('func pair(a: static imut string, b: static imut string, n: imut u64, eq: imut bool, sw: imut bool, ew: imut bool, aEmpty: imut bool) void{')
w('\t?catch(e){ throw e }')
w('\tlet sa = mut ? new String(a)')
w('\tlet sb = mut ? new String(b)')
w('\tmatch Some(sa){')
w('\t\tmatch Some(sb){')
w('\t\t\treport(a, sa.length(sa) == n)')
w('\t\t\treport(a, sa.isEmpty(sa) == aEmpty)')
w('\t\t\treport(b, sa.equals(sa, sb) == eq)')
w('\t\t\treport(b, sa.equals(sa, b) == eq)')
w('\t\t\treport(b, sa.startsWith(sa, sb) == sw)')
w('\t\t\treport(b, sa.startsWith(sa, b) == sw)')
w('\t\t\treport(b, sa.endsWith(sa, sb) == ew)')
w('\t\t\treport(b, sa.endsWith(sa, b) == ew)')
w('\t\t}')
w('\t}')
w('}')
w('')
w('func main() void{')
w('\t?catch(e){ return }')
def lit(s): return '"' + s + '"'
def b(x): return 'true' if x else 'false'
for a, c in itertools.product(pool, pool):
    w('\t? pair(%s, %s, %d, %s, %s, %s, %s)' % (lit(a), lit(c), len(a), b(a == c), b(a.startswith(c)), b(a.endswith(c)), b(a == "")))
w('}')
open('tests/string_methods_test.caspien', 'w').write('\n'.join(out) + '\n')
