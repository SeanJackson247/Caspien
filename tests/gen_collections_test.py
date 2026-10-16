# Generates tests/collections_test.caspien: random operation sequences on ObjectList<String>, StringSet, StringMap<String>
# and StringScalarMap<s64> (Codegen/.../codegenCaspien/collections.caspien), expected values from a Python model.
import random
random.seed(20261016)
KEYS = ["a", "b", "c", "dd", "eee", "", "k_1", "k.2"]
VALS = ["v0", "v1", "xx", "", "long value", "z"]
D = "../Codegen/src/main/java/caspien/codegen/codegenCaspien/collections.caspien"
out = []
def w(line):
    if line.startswith('\t{ '):
        line = '\t' + line[3:]
        assert line.endswith(' }')
        line = line[:-2]
    out.append(line)
w('import "../stdlib/libc.caspien"')
w('import "../stdlib/gt/*"')
w('import "../stdlib/print.caspien"')
w('import "../stdlib/string.caspien"')
w('import "%s"' % D)
w('')
w('func report(name: static imut string, ok: imut bool) void{')
w('\tif ok{ print("PASS ") } else { print("FAIL ") }')
w('\tprintln(name)')
w('}')
w('')
L = []            # ObjectList<String>
S = []            # StringSet
M = []            # StringMap<String>: [key, value]
N = []            # StringScalarMap<s64>: [key, value]
Q = lambda s: '"' + s + '"'
nops = 0
def part(idx, count):
    global nops
    w('@throws')
    w('func part%d(L: ref some mut ObjectList<String>, S: ref some mut StringSet, M: ref some mut StringMap<String>, N: ref some mut StringScalarMap<s64>) void{' % idx)
    w('\t?catch(e){ throw e }')
    for _ in range(count):
        nops += 1
        u = nops
        op = random.choice(["ladd", "ladd", "lsize", "lpeek", "lget", "lpop", "lclear", "lset", "lcont", "sadd", "sadd", "scont", "ssize",
                            "mput", "mput", "mget", "mhas", "msize", "mkey", "mval", "nput", "nput", "nget", "nhas", "nsize", "nkey"])
        if op == "ladd":
            v = random.choice(VALS); L.append(v)
            w('\t{ let k%d = mut ? new String(%s)\n\t  match Some(L){ ? L.add(L, k%d) } }' % (u, Q(v), u))
        elif op == "lsize":
            w('\tmatch Some(L){ report("L.size", L.size(L) == %d) }' % len(L))
        elif op == "lpeek":
            if L:
                w('\tmatch Some(L){ let p%d = L.peek(L)\n\t  match Some(p%d){ report("L.peek", p%d.equals(p%d, %s)) } }' % (u, u, u, u, Q(L[-1])))
            else:
                w('\tmatch Some(L){ let p%d = L.peek(L)\n\t  report("L.peek null", L.isEmpty(L)) }' % u)
        elif op == "lget":
            i = random.randint(0, len(L) + 1)
            if i < len(L):
                w('\tmatch Some(L){ let g%d = L.get(L, %d)\n\t  match Some(g%d){ report("L.get", g%d.equals(g%d, %s)) } }' % (u, i, u, u, u, Q(L[i])))
            else:
                w('\tmatch Some(L){ report("L.get oob", L.size(L) <= %d) }' % i)
        elif op == "lpop":
            if L: L.pop()
            w('\tmatch Some(L){ ? L.removeLast(L) }')
        elif op == "lclear":
            if random.random() < 0.2:
                L.clear()
                w('\tmatch Some(L){ ? L.clear(L) }')
        elif op == "lset":
            if L:
                i = random.randrange(len(L)); v = random.choice(VALS); L[i] = v
                w('\t{ let k%d = mut ? new String(%s)\n\t  match Some(L){ L.setAt(L, %d, k%d) } }' % (u, Q(v), i, u))
        elif op == "lcont":
            t = random.choice(VALS)
            w('\tmatch Some(L){ report("L.contains", listContainsLiteral(L, %s) == %s) }' % (Q(t), 'true' if t in L else 'false'))
            w('\tmatch Some(L){ report("L.indexOf", listIndexOfLiteral(L, %s) == %d) }' % (Q(t), L.index(t) if t in L else -1))
        elif op == "sadd":
            k = random.choice(KEYS); new = k not in S
            if new: S.append(k)
            w('\t{ let k%d = mut ? new String(%s)\n\t  match Some(S){ let r%d = mut ? S.add(S, k%d)\n\t    report("S.add", r%d == %s) } }' % (u, Q(k), u, u, u, 'true' if new else 'false'))
        elif op == "scont":
            k = random.choice(KEYS)
            w('\tmatch Some(S){ report("S.contains", S.contains(S, %s) == %s) }' % (Q(k), 'true' if k in S else 'false'))
        elif op == "ssize":
            w('\tmatch Some(S){ report("S.size", S.size(S) == %d) }' % len(S))
        elif op == "mput":
            k = random.choice(KEYS); v = random.choice(VALS)
            for e in M:
                if e[0] == k: e[1] = v; break
            else: M.append([k, v])
            w('\t{ let a%d = mut ? new String(%s)\n\t  let b%d = mut ? new String(%s)\n\t  match Some(M){ ? M.put(M, a%d, b%d) } }' % (u, Q(k), u, Q(v), u, u))
        elif op == "mget":
            k = random.choice(KEYS)
            hit = [e[1] for e in M if e[0] == k]
            if hit:
                w('\tmatch Some(M){ let g%d = M.get(M, %s)\n\t  match Some(g%d){ report("M.get", g%d.equals(g%d, %s)) } }' % (u, Q(k), u, u, u, Q(hit[0])))
            else:
                w('\tmatch Some(M){ let g%d = M.get(M, %s)\n\t  report("M.get null", M.containsKey(M, %s) == false) }' % (u, Q(k), Q(k)))
        elif op == "mhas":
            k = random.choice(KEYS)
            w('\tmatch Some(M){ report("M.has", M.containsKey(M, %s) == %s) }' % (Q(k), 'true' if any(e[0] == k for e in M) else 'false'))
        elif op == "msize":
            w('\tmatch Some(M){ report("M.size", M.size(M) == %d) }' % len(M))
        elif op == "mkey":
            if M:
                i = random.randrange(len(M))
                w('\tmatch Some(M){ let q%d = M.keyAt(M, %d)\n\t  match Some(q%d){ report("M.keyAt", q%d.equals(q%d, %s)) } }' % (u, i, u, u, u, Q(M[i][0])))
        elif op == "mval":
            if M:
                i = random.randrange(len(M))
                w('\tmatch Some(M){ let q%d = M.valueAt(M, %d)\n\t  match Some(q%d){ report("M.valueAt", q%d.equals(q%d, %s)) } }' % (u, i, u, u, u, Q(M[i][1])))
        elif op == "nput":
            k = random.choice(KEYS); v = random.randint(-5, 1000000)
            for e in N:
                if e[0] == k: e[1] = v; break
            else: N.append([k, v])
            w('\t{ let a%d = mut ? new String(%s)\n\t  match Some(N){ ? N.put(N, a%d, %d) } }' % (u, Q(k), u, v))
        elif op == "nget":
            k = random.choice(KEYS)
            hit = [e[1] for e in N if e[0] == k]
            w('\tmatch Some(N){ report("N.getOrDefault", N.getOrDefault(N, %s, -7) == %d) }' % (Q(k), hit[0] if hit else -7))
        elif op == "nhas":
            k = random.choice(KEYS)
            w('\tmatch Some(N){ report("N.has", N.containsKey(N, %s) == %s) }' % (Q(k), 'true' if any(e[0] == k for e in N) else 'false'))
        elif op == "nsize":
            w('\tmatch Some(N){ report("N.size", N.size(N) == %d) }' % len(N))
        elif op == "nkey":
            if N:
                i = random.randrange(len(N))
                w('\tmatch Some(N){ let q%d = N.keyAt(N, %d)\n\t  match Some(q%d){ report("N.keyAt", q%d.equals(q%d, %s)) } }\n\tmatch Some(N){ report("N.valueAt", N.valueAt(N, %d, -7) == %d) }' % (u, i, u, u, u, Q(N[i][0]), i, N[i][1]))
    w('}')
    w('')
parts = 6
for p in range(parts):
    part(p, 60)
w('func main() void{')
w('\t?catch(e){ return }')
w('\tlet L = mut ? new ObjectList:<String>()')
w('\tlet S = mut ? new StringSet()')
w('\tlet M = mut ? new StringMap:<String>()')
w('\tlet N = mut ? new StringScalarMap:<s64>()')
for p in range(parts):
    w('\tmatch Some(L){ match Some(S){ match Some(M){ match Some(N){ ? part%d(L, S, M, N) } } } }' % p)
w('}')
open('tests/collections_test.caspien', 'w').write('\n'.join(out) + '\n')
