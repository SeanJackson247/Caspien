#!/usr/bin/env python3
"""Generates tests/bits_ops_test.caspien: every expected value in it comes from the independent model below (and, for the
hot-loop programs, from Python's own zlib / hashlib-free reference loops), never from hand arithmetic.

    python3 tests/bits_ops_gen.py > tests/bits_ops_test.caspien

The model is the specification of the language's bitwise builtins (see CLAUDE.md, "Bitwise builtins"):
  bits_and / bits_or / bits_xor / bits_not  act on the low `width` bits; the result is the same integer type.
  bits_left(a, n)   a << n   -- n is read as an UNSIGNED number of a's own width (a negative signed count is huge);
                    n >= width gives 0, otherwise the bits shifted past the top are discarded.
  bits_right(a, n)  a >> n   -- logical (zero fill) for unsigned, arithmetic (sign fill) for signed; n as above;
                    n >= width gives 0 (unsigned) or the sign fill 0 / -1 (signed).
"""
import sys
import zlib

TYPES = [("u8", 8, False), ("u16", 16, False), ("u32", 32, False), ("u64", 64, False),
         ("s8", 8, True), ("s16", 16, True), ("s32", 32, True), ("s64", 64, True)]
M64 = (1 << 64) - 1

# The generator writes five files (full-optimisation compile time grows with the square of the number of folded checks in one
# program, because dead-control-flow removal restarts after every folded branch):
#   python3 tests/bits_ops_gen.py            -> bits_ops_test.caspien        (all widths, thinned value/count matrix, plus every other section)
#   python3 tests/bits_ops_gen.py m8|m16|m32|m64 -> bits_ops_matrix<W>_test.caspien (the FULL value x count matrix of one width pair)
MODE = sys.argv[1] if len(sys.argv) > 1 else "main"
THIN = MODE == "main"
if MODE != "main":
    _W = int(MODE[1:])
    TYPES = [t for t in TYPES if t[1] == _W]


# ---------------------------------------------------------------------------------------------- the model
def mask(w):
    return (1 << w) - 1


def norm(p, w, s):
    """pattern -> value of the type"""
    p &= mask(w)
    return p - (1 << w) if s and (p >> (w - 1)) else p


def tmax(w, s):
    return (1 << (w - 1)) - 1 if s else mask(w)


def tmin(w, s):
    return -(1 << (w - 1)) if s else 0


def m_and(a, b, w, s):
    return norm(a & b, w, s)


def m_or(a, b, w, s):
    return norm(a | b, w, s)


def m_xor(a, b, w, s):
    return norm(a ^ b, w, s)


def m_not(a, w, s):
    return norm(~a, w, s)


def m_shl(a, c, w, s):
    cp = c & mask(w)
    return 0 if cp >= w else norm((a & mask(w)) << cp, w, s)


def m_shr(a, c, w, s):
    cp = c & mask(w)
    if s:
        return a >> min(cp, w - 1)
    return 0 if cp >= w else (a & mask(w)) >> cp


OPS = {"and": m_and, "or": m_or, "xor": m_xor, "left": m_shl, "right": m_shr}
BUILTIN = {"and": "bits_and", "or": "bits_or", "xor": "bits_xor", "left": "bits_left", "right": "bits_right", "not": "bits_not"}
WRAPNAME = {"and": "and", "or": "or", "xor": "xor", "left": "shl", "right": "shr", "not": "not"}


# ---------------------------------------------------------------------------------------------- literal spelling
def group(s, n):
    out = []
    while s:
        out.append(s[-n:])
        s = s[:-n]
    return "_".join(reversed(out))


def mixcase(h, i):
    out = []
    for k, ch in enumerate(h):
        out.append(ch.upper() if (k + i) % 2 == 0 else ch.lower())
    return "".join(out)


def spell(v, i):
    """a literal for the integer v in one of several spellings, chosen by i (rotating): decimal, 0x, 0X, 0b, 0B, with _ separators, padded."""
    neg = v < 0
    a = -v if neg else v
    style = i % 9
    if style == 0:
        s = str(a)
    elif style == 1:
        s = "0x" + format(a, "x")
    elif style == 2:
        s = "0X" + mixcase(format(a, "X"), i)
    elif style == 3:
        s = "0b" + format(a, "b")
    elif style == 4:
        s = "0B" + group(format(a, "b").rjust(((a.bit_length() + 3) // 4) * 4 or 4, "0"), 4)
    elif style == 5:
        s = group(str(a), 3) if a >= 1000 else str(a)
    elif style == 6:
        h = format(a, "X").rjust(((len(format(a, "X")) + 1) // 2) * 2, "0")
        s = "0x" + group(h, 2)
    elif style == 7:
        s = "0x" + format(a, "x").rjust(max(2, len(format(a, "x")) + 2), "0")
    else:
        s = "0x" + mixcase(format(a, "x"), i + 1)
    return ("-" if neg else "") + s


class Spell:
    def __init__(self):
        self.i = 0

    def __call__(self, v):
        self.i += 1
        return spell(v, self.i)


sp = Spell()


# ---------------------------------------------------------------------------------------------- output helpers
sections = {}
out = sections.setdefault("H", [])


def begin(name):
    global out
    out = sections.setdefault(name, [])


def emit(*lines):
    for l in lines:
        out.append(l)


def esc(s):
    return s.replace("%", "%%").replace('"', "'")


class Names:
    n = 0


def check_call(t, name, expr, expected):
    Names.n += 1
    return '\tchk_%s("%s", %s, %s)' % (t, esc(name), expr, sp(expected))


def vals_for(w, s):
    p55 = norm(0x5555555555555555, w, s)
    pAA = norm(0xAAAAAAAAAAAAAAAA, w, s)
    vs = [0, 1, tmax(w, s), tmin(w, s), p55, pAA]
    if THIN:
        vs = [0, tmax(w, s), tmin(w, s), p55]
    seen = []
    for v in vs:
        if v not in seen:
            seen.append(v)
    return seen


def counts_for(w, s):
    cs = [0, 1, 2, w // 2, w - 1, w, w + 1, 63, 64, 65, 127, 200, 255]
    if THIN:
        cs = [0, 1, w - 1, w, w + 1, 100]
    if s:
        cs += [-1, tmin(w, s)] if THIN else [-1, -w, tmin(w, s)]
    res = []
    for c in cs:
        if tmin(w, s) <= c <= tmax(w, s) and c not in res:
            res.append(c)
    return res


# ---------------------------------------------------------------------------------------------- header
emit("// Tests for the bitwise builtins bits_and / bits_or / bits_xor / bits_not / bits_left / bits_right at every integer width",
     "// (u8 u16 u32 u64 s8 s16 s32 s64), and for hexadecimal / binary / underscore-separated integer literals.",
     "// GENERATED by tests/bits_ops_gen.py -- every expected value below comes from that script's independent model (and zlib.crc32",
     "// for the CRC loop), none is computed by hand. Do not edit this file; edit the generator and re-run it.",
     "//",
     "// Rules under test: the result has the operand type and is truncated to its width (bits_not(0) is 255 for u8, -1 for s8);",
     "// bits_right is arithmetic for signed types and logical for unsigned; a shift count is read as an UNSIGNED number of the operand's",
     "// own width, and a count >= the width gives 0 (bits_left, unsigned bits_right) or the sign fill (signed bits_right) -- the same",
     "// answer constant-folded, on the stack path and on the register path. A bare literal operand adapts to the other operand's type.",
     "//",
     "// Each case exists in a CONSTANT form (literal operands, which constant folding may fold; narrow widths use the u8Max/s8Min-style",
     "// limit constants as the typed operand) and a RUNTIME form (values arriving as function arguments, atol() results or loop",
     "// variables, which nothing can fold). Prints `PASS name` / `FAIL name expected E got G` per check and a summary line. The output",
     "// must be identical with every optimisation switch off and on (and on both register models).",
     'import "../stdlib/libc.caspien"',
     'import "../stdlib/gt_init.caspien"',
     'import "../stdlib/gt_register.caspien"',
     'import "../stdlib/gt_alive_check.caspien"',
     'import "../stdlib/gt_destruct.caspien"',
     "",
     "extern atol(static imut string) mut u64",
     "",
     "let static passed = mut 0",
     "let static failed = mut 0",
     "",
     "func failU(name: static imut string, e: mut u64, g: mut u64) void{",
     "\tunsafe{",
     "\t\tfailed++",
     '\t\tprintf("FAIL %s expected %llu got %llu\\n", name, e, g)',
     "\t}",
     "\treturn",
     "}",
     "func failS(name: static imut string, e: mut s64, g: mut s64) void{",
     "\tunsafe{",
     "\t\tfailed++",
     '\t\tprintf("FAIL %s expected %lld got %lld\\n", name, e, g)',
     "\t}",
     "\treturn",
     "}",
     "func check(name: static imut string, ok: mut bool) void{",
     "\tunsafe{",
     "\t\tif ok{",
     "\t\t\tpassed++",
     '\t\t\tprintf("PASS %s\\n", name)',
     "\t\t}else{",
     "\t\t\tfailed++",
     '\t\t\tprintf("FAIL %s\\n", name)',
     "\t\t}",
     "\t}",
     "\treturn",
     "}")
for t, w, s in TYPES:
    cast = "s64" if s else "u64"
    fail = "failS" if s else "failU"
    emit("func chk_%s(name: static imut string, got: mut %s, exp: mut %s) void{" % (t, t, t),
         "\tif got == exp{",
         "\t\tunsafe{",
         "\t\t\tpassed++",
         '\t\t\tprintf("PASS %s\\n", name)',
         "\t\t}",
         "\t}else{",
         "\t\t%s(name, wrap:<%s>(exp), wrap:<%s>(got))" % (fail, cast, cast),
         "\t}",
         "\treturn",
         "}")
emit("")
# wrappers: runtime forms (the operands are parameters)
for t, w, s in TYPES:
    for o in ("and", "or", "xor", "left", "right"):
        emit("func %s_%s(a: mut %s, b: mut %s) mut %s{ return %s(a, b) }" % (WRAPNAME[o], t, t, t, t, BUILTIN[o]))
    emit("func not_%s(a: mut %s) mut %s{ return bits_not(a) }" % (t, t, t))
emit("")

# ---------------------------------------------------------------------------------------------- A: runtime forms
begin("A")
for t, w, s in TYPES:
    emit("// ---- %s: values arrive as function arguments (not foldable) ----" % t)
    emit("func rt_%s() void{" % t)
    vs = vals_for(w, s)
    for o in ("and", "or", "xor"):
        for a in vs:
            for b in vs:
                emit(check_call(t, "%s %s %d %d" % (o, t, a, b), "%s_%s(%s, %s)" % (WRAPNAME[o], t, sp(a), sp(b)), OPS[o](a, b, w, s)))
    for a in vs + [norm(0x0F, w, s), norm(0xF0F0F0F0F0F0F0F0, w, s)]:
        emit(check_call(t, "not %s %d" % (t, a), "not_%s(%s)" % (t, sp(a)), m_not(a, w, s)))
    for o in ("left", "right"):
        for a in vs + [norm(1 << (w - 1), w, s)]:
            for c in counts_for(w, s):
                emit(check_call(t, "%s %s %d %d" % (o, t, a, c), "%s_%s(%s, %s)" % (WRAPNAME[o], t, sp(a), sp(c)), OPS[o](a, c, w, s)))
    emit("\treturn", "}")
emit("")

# ---------------------------------------------------------------------------------------------- B: constant forms
begin("B")
emit("// ---- constant forms: literal operands (typed through the u8Max / s16Min ... limit constants where the type is narrow) ----")
for t, w, s in TYPES:
    emit("func ct_%s() void{" % t)
    lim = {"max": "%sMax" % t, "min": "%sMin" % t}
    limv = {"max": tmax(w, s), "min": tmin(w, s)}
    lits = [0, 1, norm(0x5555555555555555, w, s), norm(0xAAAAAAAAAAAAAAAA, w, s), norm(0xF0F0F0F0F0F0F0F0, w, s), tmax(w, s) // 2 if not s else -1]
    lits = list(dict.fromkeys(lits))
    if THIN:
        lits = lits[:1] + lits[2:3]
    if w == 64:
        # 64-bit: pure literals fold too (their default type is u64, or s64 when negative)
        pairs_vals = [0, 1, 0xF0F0, 0x00FF00FF00FF00FF, M64, 0x8000000000000000, 0x7FFFFFFFFFFFFFFF]
        if s:
            pairs_vals = [0, 1, -1, -16, -0x8000000000000000, 0x7FFFFFFFFFFFFFFF, 0x5555555555555555, -0x5555555555555556]
        if THIN:
            pairs_vals = [0, 0x00FF00FF00FF00FF, M64, 0x8000000000000000] if not s else [0, -1, -0x8000000000000000, 0x5555555555555555]
        for o in ("and", "or", "xor"):
            for a in pairs_vals:
                for b in pairs_vals:
                    if s and a >= 0 and b >= 0:
                        continue
                    emit(check_call(t, "const %s %s %d %d" % (o, t, a, b), "%s(%s, %s)" % (BUILTIN[o], sp(a), sp(b)), OPS[o](a, b, w, s)))
        for a in pairs_vals:
            if s and a >= 0:
                continue
            emit(check_call(t, "const not %s %d" % (t, a), "bits_not(%s)" % sp(a), m_not(a, w, s)))
        for o in ("left", "right"):
            for a in pairs_vals:
                if s and a >= 0:
                    continue
                for c in ((0, 1, 7, 31, 32, 33, 62, 63, 64, 65, 100, 255) if not THIN else (0, 1, 63, 64, 100)):
                    emit(check_call(t, "const %s %s %d %d" % (o, t, a, c), "%s(%s, %s)" % (BUILTIN[o], sp(a), sp(c)), OPS[o](a, c, w, s)))
    for cn in ("max", "min"):
        C = lim[cn]
        cv = limv[cn]
        for o in ("and", "or", "xor"):
            for L in lits:
                emit(check_call(t, "const %s %s %s %d" % (o, t, cn, L), "%s(%s, %s)" % (BUILTIN[o], C, sp(L)), OPS[o](cv, L, w, s)))
                emit(check_call(t, "const %s %s %d %s" % (o, t, L, cn), "%s(%s, %s)" % (BUILTIN[o], sp(L), C), OPS[o](L, cv, w, s)))
        emit(check_call(t, "const not %s %s" % (t, cn), "bits_not(%s)" % C, m_not(cv, w, s)))
        emit(check_call(t, "const not not %s %s" % (t, cn), "bits_not(bits_not(%s))" % C, m_not(m_not(cv, w, s), w, s)))
        for k in ((0, 1, 2, w // 2, w - 1, w, w + 1, 63, 64, 65, 100) if not THIN else (0, 1, w - 1, w, w + 1, 100)):
            if not (tmin(w, s) <= k <= tmax(w, s)):
                continue
            for o in ("left", "right"):
                emit(check_call(t, "const %s %s %s %d" % (o, t, cn, k), "%s(%s, %s)" % (BUILTIN[o], C, sp(k)), OPS[o](cv, k, w, s)))
        # a limit constant as the COUNT
        for o in ("left", "right"):
            for L in ((1, 3, norm(0xAAAAAAAAAAAAAAAA, w, s)) if not THIN else (3,)):
                emit(check_call(t, "const %s %s %d by %s" % (o, t, L, cn), "%s(%s, %s)" % (BUILTIN[o], sp(L), C), OPS[o](L, cv, w, s)))
    # nested constant expressions
    cmax, cmin = limv["max"], limv["min"]
    L1 = norm(0x5555555555555555, w, s)
    L2 = norm(0xF0F0F0F0F0F0F0F0, w, s)
    inner1 = m_xor(cmax, L1, w, s)
    inner2 = m_or(cmin, L2, w, s)
    emit(check_call(t, "const nested and(xor,or) %s" % t,
                    "bits_and(bits_xor(%sMax, %s), bits_or(%sMin, %s))" % (t, sp(L1), t, sp(L2)), m_and(inner1, inner2, w, s)))
    emit(check_call(t, "const nested not(left) %s" % t, "bits_not(bits_left(%sMax, 1))" % t, m_not(m_shl(cmax, 1, w, s), w, s)))
    emit(check_call(t, "const nested right(left) %s" % t, "bits_right(bits_left(%sMax, 3), 2)" % t, m_shr(m_shl(cmax, 3, w, s), 2, w, s)))
    emit(check_call(t, "const nested xor(not,not) %s" % t, "bits_xor(bits_not(%sMax), bits_not(%sMin))" % (t, t),
                    m_xor(m_not(cmax, w, s), m_not(cmin, w, s), w, s)))
    emit("\treturn", "}")
emit("")

# ---------------------------------------------------------------------------------------------- C: atol + loop variables
begin("X")
emit("// ---- values from atol() and from loop variables: whole sweeps of counts, compared as one checksum each ----")
for t, w, s in TYPES:
    cast = "s64" if s else "u64"
    emit("func sweep_%s() void{" % t)
    for o in ("left", "right"):
        for a in (1, tmax(w, s), tmin(w, s), norm(0xAAAAAAAAAAAAAAAA, w, s), norm(0x5555555555555555, w, s)):
            # sum over counts 0..w+70 (wrapping in 64 bits) of the result widened to 64 bits; the count is a typed loop variable
            total = 0
            for i in range(0, 70 if w >= 64 else w + 70):
                if i > tmax(w, s):
                    break
                total = (total + (OPS[o](a, i, w, s) & M64)) & M64
            hi = 70 if w >= 64 else w + 70
            hi = min(hi, tmax(w, s) + 1)
            av = a if a >= 0 else a & mask(w)
            # `a` reaches the code through atol() as its (non-negative) bit pattern, narrowed back with wrap
            if (a & mask(w)) >= (1 << 63):
                src = "wrap:<%s>(atol(\"%d\") + 9223372036854775808)" % (t, (a & mask(w)) - (1 << 63))
            else:
                src = "wrap:<%s>(atol(\"%d\"))" % (t, a & mask(w))
            emit("\tunsafe{",
                 "\t\tlet:<mut %s> x = mut %s" % (t, src),
                 "\t\tlet acc = mut 0",
                 "\t\tfor i in 0..%d{" % hi,
                 "\t\t\tlet:<mut %s> c = mut wrap:<%s>(i)" % (t, t),
                 "\t\t\tacc = acc + wrap:<u64>(%s(x, c))" % BUILTIN[o],
                 "\t\t}",
                 '\t\tcheck("sweep %s %s %d (%d counts)", acc == %d)' % (o, t, a, hi, total),
                 "\t}")
    emit("\treturn", "}")
emit("")

# ---------------------------------------------------------------------------------------------- D: struct fields, arrays, dynarrays
emit("// ---- results stored into struct fields, fixed-array elements and dynarray elements ----")
for t, w, s in TYPES:
    cast = "s64" if s else "u64"
    a0 = norm(0xC3A5C3A5C3A5C3A5, w, s)
    b0 = norm(0x0FF00FF00FF00FF0, w, s)
    n0 = 3
    emit("struct Box_%s{" % t, "\t@pub{")
    for f in ("fAnd", "fOr", "fXor", "fNot", "fShl", "fShr"):
        emit("\t\t%s: mut %s" % (f, t))
    emit("\t}", "}")
    emit("func cont_%s(a: mut %s, b: mut %s, n: mut %s) void{" % (t, t, t, t))
    emit("\t?catch(e){ return }")
    emit("\tlet bx = mut Box_%s{fAnd= bits_and(a, b), fOr= bits_or(a, b), fXor= bits_xor(a, b), fNot= bits_not(a), fShl= bits_left(a, n), fShr= bits_right(a, n)}" % t)
    emit(check_call(t, "struct fand " + t, "bx.fAnd", m_and(a0, b0, w, s)))
    emit(check_call(t, "struct for " + t, "bx.fOr", m_or(a0, b0, w, s)))
    emit(check_call(t, "struct fxor " + t, "bx.fXor", m_xor(a0, b0, w, s)))
    emit(check_call(t, "struct fnot " + t, "bx.fNot", m_not(a0, w, s)))
    emit(check_call(t, "struct fshl " + t, "bx.fShl", m_shl(a0, n0, w, s)))
    emit(check_call(t, "struct fshr " + t, "bx.fShr", m_shr(a0, n0, w, s)))
    # read-modify-write of the fields
    emit("\tbx.fAnd = bits_xor(bx.fAnd, bits_left(bx.fShr, 1))",
         "\tbx.fShl = bits_or(bits_right(bx.fShl, 1), bits_not(bx.fOr))",
         "\tbx.fNot = bits_and(bx.fNot, bits_not(bx.fXor))")
    e1 = m_xor(m_and(a0, b0, w, s), m_shl(m_shr(a0, n0, w, s), 1, w, s), w, s)
    e2 = m_or(m_shr(m_shl(a0, n0, w, s), 1, w, s), m_not(m_or(a0, b0, w, s), w, s), w, s)
    e3 = m_and(m_not(a0, w, s), m_not(m_xor(a0, b0, w, s), w, s), w, s)
    emit(check_call(t, "struct rmw fand " + t, "bx.fAnd", e1))
    emit(check_call(t, "struct rmw fshl " + t, "bx.fShl", e2))
    emit(check_call(t, "struct rmw fnot " + t, "bx.fNot", e3))
    # fixed array: element-wise read-modify-write in a loop, then every element compared
    elems = [norm(x, w, s) for x in (0x01, 0xFF, 0x7F7F7F7F7F7F7F7F, 0x8080808080808080, 0x1234567890ABCDEF, 0xFEDCBA0987654321)]
    after = [m_xor(m_shl(x, 1, w, s), b0, w, s) for x in elems]
    emit("\tlet:<mut %s[6]> arr = mut [%s]" % (t, ", ".join(sp(x) for x in elems)))
    emit("\tlet:<mut %s[6]> want = mut [%s]" % (t, ", ".join(sp(x) for x in after)))
    emit("\tfor i in 0..6{", "\t\tmatch i into arr{", "\t\t\tarr[i] = bits_xor(bits_left(arr[i], 1), b)", "\t\t}", "\t}")
    emit("\tfor match i in arr{", "\t\tmatch i in want{", '\t\t\tchk_%s("array element %s", arr[i], want[i])' % (t, t), "\t\t}", "\t}")
    # dynarray: filled from loop-variable-derived values, then combined
    dyn_in = [m_xor(m_shl(1, i, w, s), norm(i * 0x9E3779B97F4A7C15, w, s), w, s) for i in range(8)]
    dyn_out = [m_not(m_and(x, b0, w, s), w, s) for x in dyn_in]
    emit("\tunsafe{",
         "\t\tlet:<mut %s> zero = mut 0" % t,
         "\t\tlet d = mut ? dyn:<%s>([])" % t,
         "\t\td = ? resize(d, 8, zero)",
         "\t\tfor i in 0..8{",
         "\t\t\tlet:<mut %s> k = mut wrap:<%s>(i)" % (t, t),
         "\t\t\tlet:<mut %s> v = mut bits_xor(bits_left(1, k), wrap:<%s>(i * 11400714819323198485))" % (t, t),
         "\t\t\tmatch i into d{",
         "\t\t\t\td[i] = v",
         "\t\t\t}",
         "\t\t}",
         "\t\tfor i in 0..8{",
         "\t\t\tmatch i into d{",
         "\t\t\t\td[i] = bits_not(bits_and(d[i], b))",
         "\t\t\t}",
         "\t\t}",
         "\t\tlet:<mut %s[8]> dwant = mut [%s]" % (t, ", ".join(sp(x) for x in dyn_out)),
         "\t\tfor i in 0..8{",
         "\t\t\tmatch i in d{",
         "\t\t\t\tmatch i in dwant{",
         '\t\t\t\t\tchk_%s("dynarray element %s", d[i], dwant[i])' % (t, t),
         "\t\t\t\t}",
         "\t\t\t}",
         "\t\t}",
         "\t}")
    emit("\treturn", "}")
emit("")

# ---------------------------------------------------------------------------------------------- E: hot loops
emit("// ---- hot loops: register-form fusion, promoted loop variables, narrow widths in loops ----")


def xorshift64(seed, n):
    x = seed
    for _ in range(n):
        x ^= (x << 13) & M64
        x ^= x >> 7
        x ^= (x << 17) & M64
    return x


emit("func xorshift64(seed: mut u64, n: mut u64) mut u64{",
     "\tlet x = mut seed",
     "\tfor i in 0..n{",
     "\t\tx = bits_xor(x, bits_left(x, 13))",
     "\t\tx = bits_xor(x, bits_right(x, 7))",
     "\t\tx = bits_xor(x, bits_left(x, 17))",
     "\t}",
     "\treturn x",
     "}")


def varshifts(x0, n):
    acc = 0
    x = x0
    for i in range(n):
        acc = (acc + m_shr(x, i % 70, 64, False) + m_shl(x, (i * 3) % 70, 64, False)) & M64
        x = m_xor(x, m_shl(x, i % 64, 64, False), 64, False)
        x = (x + 0x9E3779B97F4A7C15) & M64
    return acc ^ x


emit("// variable counts that run past the width (i % 70 reaches 64..69): the documented count >= 64 rule, in a loop",
     "func varshifts(x0: mut u64, n: mut u64) mut u64{",
     "\tlet acc = mut 0",
     "\tlet x = mut x0",
     "\tfor i in 0..n{",
     "\t\tacc = acc + bits_right(x, i % 70) + bits_left(x, (i * 3) % 70)",
     "\t\tx = bits_xor(x, bits_left(x, i % 64))",
     "\t\tx = x + 0x9E3779B97F4A7C15",
     "\t}",
     "\treturn bits_xor(acc, x)",
     "}")

emit("func popcountShift(x: mut u64) mut u64{",
     "\tlet c = mut 0",
     "\tfor i in 0..64{",
     "\t\tc = c + bits_and(bits_right(x, i), 1)",
     "\t}",
     "\treturn c",
     "}",
     "func popcountKernighan(x: mut u64) mut u64{",
     "\tlet y = mut x",
     "\tlet c = mut 0",
     "\tunsafe{",
     "\t\tloop{",
     "\t\t\tif y == 0{",
     "\t\t\t\tbreak",
     "\t\t\t}",
     "\t\t\ty = bits_and(y, y - 1)",
     "\t\t\tc++",
     "\t\t}",
     "\t}",
     "\treturn c",
     "}",
     "func popcountSum(n: mut u64) mut u64{",
     "\tlet x = mut 1",
     "\tlet total = mut 0",
     "\tfor i in 0..n{",
     "\t\tx = x * 6364136223846793005 + 1442695040888963407",
     "\t\ttotal = total + popcountShift(x) + popcountKernighan(x) * 1000",
     "\t}",
     "\treturn total",
     "}")


def popcount_sum(n):
    x = 1
    total = 0
    for _ in range(n):
        x = (x * 6364136223846793005 + 1442695040888963407) & M64
        pc = bin(x).count("1")
        total = (total + pc + pc * 1000) & M64
    return total


def bitrev(x, w):
    r = 0
    for _ in range(w):
        r = ((r << 1) | (x & 1)) & mask(w)
        x >>= 1
    return r


for t, w in (("u8", 8), ("u16", 16), ("u32", 32), ("u64", 64)):
    emit("func rev_%s(x0: mut %s) mut %s{" % (t, t, t),
         "\tlet x = mut x0",
         "\tlet:<mut %s> r = mut 0" % t,
         "\tfor i in 0..%d{" % w,
         "\t\tr = bits_or(bits_left(r, 1), bits_and(x, 1))",
         "\t\tx = bits_right(x, 1)",
         "\t}",
         "\treturn r",
         "}",
         "func revSum_%s(n: mut u64) mut u64{" % t,
         "\tlet total = mut 0",
         "\tfor i in 0..n{",
         "\t\ttotal = total + wrap:<u64>(rev_%s(wrap:<%s>(i * 40503 + 12345))) * (i + 1)" % (t, t),
         "\t}",
         "\treturn total",
         "}")


def revsum(w, n):
    total = 0
    for i in range(n):
        total = (total + bitrev((i * 40503 + 12345) & mask(w), w) * (i + 1)) & M64
    return total


emit("func crc32(n: mut u64) mut u32{",
     "\tlet:<mut u32> crc = mut 0xFFFFFFFF",
     "\tfor i in 0..n{",
     "\t\tlet:<mut u32> byte = mut wrap:<u32>((i * 7 + 3) % 256)",
     "\t\tcrc = bits_xor(crc, byte)",
     "\t\tfor j in 0..8{",
     "\t\t\tif bits_and(crc, 1) == 1{",
     "\t\t\t\tcrc = bits_xor(bits_right(crc, 1), 0xEDB88320)",
     "\t\t\t}else{",
     "\t\t\t\tcrc = bits_right(crc, 1)",
     "\t\t\t}",
     "\t\t}",
     "\t}",
     "\treturn bits_xor(crc, 0xFFFFFFFF)",
     "}")

for t, w in (("u32", 32), ("u64", 64)):
    emit("func rotl_%s(x: mut %s, k: mut %s) mut %s{" % (t, t, t, t),
         "\treturn bits_or(bits_left(x, k), bits_right(x, %d - k))" % w,
         "}",
         "func rotr_%s(x: mut %s, k: mut %s) mut %s{" % (t, t, t, t),
         "\treturn bits_or(bits_right(x, k), bits_left(x, %d - k))" % w,
         "}",
         "func rotSum_%s(x0: mut %s) mut u64{" % (t, t),
         "\tlet total = mut 0",
         "\tfor i in 0..%d{" % (w + 1),
         "\t\tlet:<mut %s> k = mut wrap:<%s>(i)" % (t, t),
         "\t\ttotal = total + wrap:<u64>(rotl_%s(x0, k)) + wrap:<u64>(rotr_%s(x0, k)) * 3" % (t, t),
         "\t}",
         "\treturn total",
         "}")


def rotl(x, k, w):
    k %= w
    return ((x << k) | (x >> (w - k))) & mask(w) if k else x


def rotr(x, k, w):
    return rotl(x, (w - k % w) % w, w)


def rot_sum(x0, w):
    total = 0
    for k in range(w + 1):
        total = (total + rotl(x0, k, w) + rotr(x0, k, w) * 3) & M64
        # the shift-count rule makes the shift-built rotate agree with a real rotate at k = 0 and k = w too
    return total


# narrow hash loops: rotate-by-3 mixed with a complement and the loop counter, per width and signedness
for t, w, s in TYPES:
    emit("func hash_%s(n: mut u64) mut %s{" % (t, t),
         "\tlet:<mut %s> h = mut 1" % t,
         "\tfor i in 0..n{",
         "\t\tlet:<mut %s> c = mut wrap:<%s>(i)" % (t, t),
         "\t\th = bits_xor(bits_or(bits_left(h, 3), bits_right(h, %d)), bits_not(c))" % (w - 3),
         "\t\th = bits_and(h, bits_not(bits_left(c, 1)))",
         "\t\th = bits_xor(h, bits_right(h, 2))",
         "\t}",
         "\treturn h",
         "}")


def hash_loop(n, w, s):
    h = 1
    for i in range(n):
        c = norm(i, w, s)
        h = m_xor(m_or(m_shl(h, 3, w, s), m_shr(h, w - 3, w, s), w, s), m_not(c, w, s), w, s)
        h = m_and(h, m_not(m_shl(c, 1, w, s), w, s), w, s)
        h = m_xor(h, m_shr(h, 2, w, s), w, s)
    return h


emit("func sarsum(x: mut s64) mut s64{",
     "\tlet:<mut s64> acc = mut 0",
     "\tfor i in 0..80{",
     "\t\tacc = acc + bits_right(x, wrap:<s64>(i))",
     "\t}",
     "\treturn acc",
     "}")


def sarsum(x):
    return sum(m_shr(x, i, 64, True) for i in range(80))


# SHA-256 round functions on u32 (rotations built from the shifts)
emit("func rotr32(x: mut u32, k: mut u32) mut u32{",
     "\treturn bits_or(bits_right(x, k), bits_left(x, 32 - k))",
     "}",
     "func shaCh(x: mut u32, y: mut u32, z: mut u32) mut u32{",
     "\treturn bits_xor(bits_and(x, y), bits_and(bits_not(x), z))",
     "}",
     "func shaMaj(x: mut u32, y: mut u32, z: mut u32) mut u32{",
     "\treturn bits_xor(bits_xor(bits_and(x, y), bits_and(x, z)), bits_and(y, z))",
     "}",
     "func shaS0(x: mut u32) mut u32{",
     "\treturn bits_xor(bits_xor(rotr32(x, 2), rotr32(x, 13)), rotr32(x, 22))",
     "}",
     "func shaS1(x: mut u32) mut u32{",
     "\treturn bits_xor(bits_xor(rotr32(x, 6), rotr32(x, 11)), rotr32(x, 25))",
     "}",
     "func shaL0(x: mut u32) mut u32{",
     "\treturn bits_xor(bits_xor(rotr32(x, 7), rotr32(x, 18)), bits_right(x, 3))",
     "}",
     "func shaL1(x: mut u32) mut u32{",
     "\treturn bits_xor(bits_xor(rotr32(x, 17), rotr32(x, 19)), bits_right(x, 10))",
     "}",
     "func shaMix(n: mut u64) mut u32{",
     "\tlet:<mut u32> a = mut 0x6a09e667",
     "\tlet:<mut u32> b = mut 0xbb67ae85",
     "\tlet:<mut u32> c = mut 0x3c6ef372",
     "\tlet:<mut u32> acc = mut 0",
     "\tfor i in 0..n{",
     "\t\tlet:<mut u32> iw = mut wrap:<u32>(i)",
     "\t\tlet:<mut u32> t = mut (shaS1(a) + shaCh(a, b, c) + shaL0(b) + iw)",
     "\t\tlet:<mut u32> u = mut (shaS0(c) + shaMaj(a, b, c) + shaL1(t))",
     "\t\tc = b",
     "\t\tb = a",
     "\t\ta = t + u",
     "\t\tacc = bits_xor(acc, a)",
     "\t}",
     "\treturn bits_xor(acc, c)",
     "}")


def sha_mix(n):
    r32 = lambda x, k: ((x >> k) | (x << (32 - k))) & 0xFFFFFFFF
    ch = lambda x, y, z: (x & y) ^ (~x & z) & 0xFFFFFFFF
    ch = lambda x, y, z: ((x & y) ^ ((~x & 0xFFFFFFFF) & z)) & 0xFFFFFFFF
    maj = lambda x, y, z: (x & y) ^ (x & z) ^ (y & z)
    s0 = lambda x: r32(x, 2) ^ r32(x, 13) ^ r32(x, 22)
    s1 = lambda x: r32(x, 6) ^ r32(x, 11) ^ r32(x, 25)
    l0 = lambda x: r32(x, 7) ^ r32(x, 18) ^ (x >> 3)
    l1 = lambda x: r32(x, 17) ^ r32(x, 19) ^ (x >> 10)
    a, b, c, acc = 0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0
    for i in range(n):
        t = (s1(a) + ch(a, b, c) + l0(b) + (i & 0xFFFFFFFF)) & 0xFFFFFFFF
        u = (s0(c) + maj(a, b, c) + l1(t)) & 0xFFFFFFFF
        c = b
        b = a
        a = (t + u) & 0xFFFFFFFF
        acc ^= a
    return acc ^ c


# nested chains through calls (the register-form pass has to keep partial results alive across calls)
emit("func mix32(a: mut u32, b: mut u32, c: mut u32) mut u32{",
     "\treturn bits_xor(bits_and(a, bits_not(b)), bits_or(bits_left(a, 3), bits_right(c, 2)))",
     "}",
     "func mixS16(a: mut s16, b: mut s16, c: mut s16) mut s16{",
     "\treturn bits_or(bits_xor(bits_right(a, 3), bits_not(b)), bits_and(bits_left(c, 5), a))",
     "}",
     "func chain(n: mut u64) mut u64{",
     "\tlet:<mut u32> a = mut 0x12345678",
     "\tlet:<mut u32> b = mut 0x9ABCDEF0",
     "\tlet:<mut u32> c = mut 0x0F1E2D3C",
     "\tlet:<mut s16> p = mut -12345",
     "\tlet:<mut s16> q = mut 23456",
     "\tlet:<mut s16> r = mut -3",
     "\tlet total = mut 0",
     "\tfor i in 0..n{",
     "\t\ta = mix32(mix32(a, b, c), mix32(b, c, a), bits_not(mix32(c, a, b)))",
     "\t\tb = bits_xor(b, mix32(a, c, bits_left(b, 7)))",
     "\t\tc = c + 0x01010101",
     "\t\tp = mixS16(p, mixS16(q, r, p), bits_not(r))",
     "\t\tq = bits_xor(q, bits_right(p, 4))",
     "\t\tr = r + 7",
     "\t\ttotal = total + wrap:<u64>(a) + wrap:<u64>(b) * 3 + wrap:<u64>(wrap:<u16>(p)) * 5 + wrap:<u64>(wrap:<u16>(q))",
     "\t}",
     "\treturn total",
     "}")


def chain(n):
    mix32 = lambda a, b, c: m_xor(m_and(a, m_not(b, 32, False), 32, False), m_or(m_shl(a, 3, 32, False), m_shr(c, 2, 32, False), 32, False), 32, False)
    mixs = lambda a, b, c: m_or(m_xor(m_shr(a, 3, 16, True), m_not(b, 16, True), 16, True), m_and(m_shl(c, 5, 16, True), a, 16, True), 16, True)
    a, b, c = 0x12345678, 0x9ABCDEF0, 0x0F1E2D3C
    p, q, r = -12345, 23456, -3
    total = 0
    for i in range(n):
        a = mix32(mix32(a, b, c), mix32(b, c, a), m_not(mix32(c, a, b), 32, False))
        b = m_xor(b, mix32(a, c, m_shl(b, 7, 32, False)), 32, False)
        c = (c + 0x01010101) & 0xFFFFFFFF
        p = mixs(p, mixs(q, r, p), m_not(r, 16, True))
        q = m_xor(q, m_shr(p, 4, 16, True), 16, True)
        r = norm(r + 7, 16, True)
        total = (total + a + b * 3 + (p & 0xFFFF) * 5 + (q & 0xFFFF)) & M64
    return total


# variable shifts as LATER arguments of a call: %rcx is the 4th SysV argument register / 1st win64 one
emit("func six(a: mut u64, b: mut u64, c: mut u64, d: mut u64, e: mut u64, f: mut u64) mut u64{",
     "\treturn a + b * 3 + c * 5 + d * 7 + e * 11 + f * 13",
     "}",
     "func six32(a: mut u32, b: mut u32, c: mut u32, d: mut u32, e: mut u32, f: mut u32) mut u64{",
     "\treturn wrap:<u64>(a) + wrap:<u64>(b) * 3 + wrap:<u64>(c) * 5 + wrap:<u64>(d) * 7 + wrap:<u64>(e) * 11 + wrap:<u64>(f) * 13",
     "}",
     "func argShifts(x: mut u64, y: mut u64, z: mut u64, n: mut u64) mut u64{",
     "\tlet total = mut 0",
     "\ttotal = total + six(1, 2, 3, bits_left(x, n), bits_right(y, n), bits_not(z))",
     "\ttotal = total + six(bits_left(x, n), bits_right(y, n), bits_xor(z, x), bits_and(y, z), bits_left(z, n), bits_or(x, y))",
     "\ttotal = total + six(n, bits_right(x, n), 3, bits_left(y, n), bits_right(z, n), 6)",
     "\treturn total",
     "}",
     "func argShifts32(x: mut u32, y: mut u32, z: mut u32, n: mut u32) mut u64{",
     "\tlet total = mut 0",
     "\ttotal = total + six32(1, 2, 3, bits_left(x, n), bits_right(y, n), bits_not(z))",
     "\ttotal = total + six32(bits_left(x, n), bits_right(y, n), bits_xor(z, x), bits_and(y, z), bits_left(z, n), bits_or(x, y))",
     "\treturn total",
     "}")


def six(a, b, c, d, e, f):
    return (a + b * 3 + c * 5 + d * 7 + e * 11 + f * 13) & M64


def arg_shifts(x, y, z, n):
    t = 0
    t = (t + six(1, 2, 3, m_shl(x, n, 64, False), m_shr(y, n, 64, False), m_not(z, 64, False))) & M64
    t = (t + six(m_shl(x, n, 64, False), m_shr(y, n, 64, False), m_xor(z, x, 64, False), m_and(y, z, 64, False), m_shl(z, n, 64, False), m_or(x, y, 64, False))) & M64
    t = (t + six(n, m_shr(x, n, 64, False), 3, m_shl(y, n, 64, False), m_shr(z, n, 64, False), 6)) & M64
    return t


def arg_shifts32(x, y, z, n):
    w, s = 32, False
    t = six(1, 2, 3, m_shl(x, n, w, s), m_shr(y, n, w, s), m_not(z, w, s))
    t = (t + six(m_shl(x, n, w, s), m_shr(y, n, w, s), m_xor(z, x, w, s), m_and(y, z, w, s), m_shl(z, n, w, s), m_or(x, y, w, s))) & M64
    return t


emit("")
emit("func hotLoops() void{")
seed = 88172645463325252
emit('\tcheck("xorshift64 100000 steps", xorshift64(%s, 100000) == %s)' % (sp(seed), sp(xorshift64(seed, 100000))))
emit('\tcheck("xorshift64 0 steps", xorshift64(%s, 0) == %s)' % (sp(seed), sp(seed)))
emit('\tcheck("variable shifts past the width, 5000 steps", varshifts(%s, 5000) == %s)' % (sp(0x0123456789ABCDEF), sp(varshifts(0x0123456789ABCDEF, 5000))))
emit('\tcheck("popcount (shift and Kernighan) 3000 values", popcountSum(3000) == %s)' % sp(popcount_sum(3000)))
for t, w in (("u8", 8), ("u16", 16), ("u32", 32), ("u64", 64)):
    emit('\tcheck("bit reversal %s sum", revSum_%s(2000) == %s)' % (t, t, sp(revsum(w, 2000))))
    emit('\tcheck("bit reversal %s of %s", rev_%s(%s) == %s)' % (t, sp(0x1), t, sp(1), sp(1 << (w - 1))))
data = bytes((i * 7 + 3) % 256 for i in range(3000))
emit('\tcheck("crc32 bitwise loop (3000 bytes, zlib)", crc32(3000) == %s)' % sp(zlib.crc32(data)))
emit('\tcheck("crc32 bitwise loop (0 bytes, zlib)", crc32(0) == %s)' % sp(zlib.crc32(b"")))
for t, w in (("u32", 32), ("u64", 64)):
    x0 = 0x9E3779B97F4A7C15 & mask(w)
    emit('\tcheck("rotate left/right from shifts %s", rotSum_%s(%s) == %s)' % (t, t, sp(x0), sp(rot_sum(x0, w))))
for t, w, s in TYPES:
    emit('\tchk_%s("hash loop %s 2000 steps", hash_%s(2000), %s)' % (t, t, t, sp(hash_loop(2000, w, s))))
for x in (-123456789012345, -1, -9223372036854775808, 123456789012345, 0):
    emit('\tchk_s64("arithmetic right shift sum %d", sarsum(%s), %s)' % (x, sp(x), sp(norm(sarsum(x), 64, True))))
emit('\tchk_u32("sha-256 style mixing, 4000 rounds", shaMix(4000), %s)' % sp(sha_mix(4000)))
emit('\tcheck("nested chains of calls, 3000 steps", chain(3000) == %s)' % sp(chain(3000)))
for (x, y, z, n) in ((0x0123456789ABCDEF, 0xFEDCBA9876543210, 0x5555AAAA5555AAAA, 5), (1, 2, 3, 0), (M64, M64, M64, 63), (0xF0F0, 0x0F0F, 0xFF00, 64), (7, 9, 11, 200)):
    emit('\tcheck("shifts as later call arguments (64-bit) n=%d", argShifts(%s, %s, %s, %s) == %s)' % (n, sp(x), sp(y), sp(z), sp(n), sp(arg_shifts(x, y, z, n))))
for (x, y, z, n) in ((0x01234567, 0xFEDCBA98, 0x5555AAAA, 5), (1, 2, 3, 0), (0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 31), (0xF0F0, 0x0F0F, 0xFF00, 32), (7, 9, 11, 200)):
    emit('\tcheck("shifts as later call arguments (32-bit) n=%d", argShifts32(%s, %s, %s, %s) == %s)' % (n, sp(x), sp(y), sp(z), sp(n), sp(arg_shifts32(x, y, z, n))))
emit("\treturn", "}")
emit("")

# ---------------------------------------------------------------------------------------------- F: literal forms
emit("// ---- literal forms: decimal, hexadecimal (0x / 0X, mixed case), binary (0b / 0B), _ separators ----")
emit("const KMASK 0xF0F0")
emit("const KBITS 0b1010_0101")
emit("enum Shade{ DARK=0x10, MID=0b110000, LIGHT=1_000 }")
emit("func literalForms() void{")
lit_cases = [
    ("0xFF", 255), ("0XFF", 255), ("0xff", 255), ("0xAbCd", 0xABCD), ("0Xabcd", 0xABCD), ("0x0", 0), ("0X0", 0), ("0x00FF", 255),
    ("0x1", 1), ("0b0", 0), ("0b1", 1), ("0b1010", 10), ("0B1010", 10), ("0b00001111", 15), ("0b1111_0000", 240), ("0B1111_0000_1111", 0xF0F),
    ("1_000", 1000), ("1_000_000", 1000000), ("0xFF_FF", 65535), ("0xDEAD_BEEF", 0xDEADBEEF), ("0x7FFF_FFFF_FFFF_FFFF", (1 << 63) - 1),
    ("0xFFFFFFFFFFFFFFFF", M64), ("0xffffffffffffffff", M64), ("0xFFFF_FFFF_FFFF_FFFF", M64), ("0x8000000000000000", 1 << 63),
    ("0b" + "1" * 64, M64), ("0b1" + "0" * 63, 1 << 63), ("18446744073709551615", M64), ("9223372036854775808", 1 << 63),
    ("0x0000000000000000000000FF", 255), ("0b0000000000000000000000000000000000000000000000000000000000000000000001", 1),
]
for text, v in lit_cases:
    emit('\tchk_u64("literal %s", %s, %s)' % (text, text, v))
emit('\tcheck("0xFFFFFFFFFFFFFFFF == u64Max", 0xFFFFFFFFFFFFFFFF == u64Max)')
emit('\tcheck("-0x8000000000000000 == s64Min", -0x8000000000000000 == s64Min)')
emit('\tchk_s64("-0b101 is -5", -0b101, -5)')
emit('\tchk_s64("-1_000 is -1000", -1_000, -1000)')
emit('\tchk_u8("u8 0xFF", 0xFF, 255)')
emit('\tchk_u8("u8 0b11111111", 0b11111111, 255)')
emit('\tchk_u16("u16 0xFFFF", 0xFFFF, 65535)')
emit('\tchk_u32("u32 0xFFFFFFFF", 0xFFFFFFFF, 4294967295)')
emit('\tchk_s8("s8 -0x80", -0x80, -128)')
emit('\tchk_s8("s8 0x7F", 0x7F, 127)')
emit('\tchk_s16("s16 -0x8000", -0x8000, -32768)')
emit('\tchk_s32("s32 -0x80000000", -0x80000000, -2147483648)')
emit('\tchk_u64("const KMASK", KMASK, 61680)')
emit('\tchk_u64("const KBITS", KBITS, 165)')
emit('\tchk_u64("bits_and of hex literals", %s, 171)' % "bits_and(0xFF, 0xAB)")
emit('\tlet:<mut u8> small = mut 0xAB')
emit('\tchk_u8("let:<mut u8> = 0xAB", small, 171)')
emit('\tlet:<mut s16> neg = mut -0x7FFF')
emit('\tchk_s16("let:<mut s16> = -0x7FFF", neg, -32767)')
emit('\tlet:<mut u8[0x4]> arr4 = mut [0x1, 0x2, 0b11, 0X0F]')
emit('\tlet sumIdx = mut 0')
emit('\tfor match i in arr4{', '\t\tsumIdx += arr4[i] as u64', '\t}')
emit('\tcheck("array size [0x4], elements 0x1 0x2 0b11 0X0F", sumIdx == 21)')
emit('\tlet rsum = mut 0')
emit('\tfor i in 0x0..0b1010{', '\t\trsum += i', '\t}')
emit('\tcheck("range bounds 0x0..0b1010", rsum == 45)')
emit('\tlet rv = mut 0x20')
emit('\tlet hit = mut 0')
emit('\tmatch rv in 0x10..0x30{', '\t\thit = 1', '\t}')
emit('\tcheck("match in 0x10..0x30", hit == 1)')
emit('\tlet shade = Shade.MID')
emit('\tlet seen = mut 0')
emit('\tmatch shade{', '\t\tDARK:{', '\t\t\tseen = 1', '\t\t}', '\t\tMID:{', '\t\t\tseen = 2', '\t\t}', '\t\tLIGHT:{', '\t\t\tseen = 3', '\t\t}', '\t}')
emit('\tcheck("enum with 0x10 / 0b110000 / 1_000 values", seen == 2)')
emit('\tlet mm = mut 0xF0')
emit('\tmatch mm fits u8{', '\t\tseen = 10', '\t}', '\telse{', '\t\tseen = 11', '\t}')
emit('\tcheck("match fits u8 with a hex value", seen == 10)')
emit("\treturn", "}")
emit("")

# ---------------------------------------------------------------------------------------------- G: atol-sourced mixed bag + main
emit("func fromAtol() void{")
emit("\tunsafe{")
for t, w, s in TYPES:
    a = norm(0x2B2B2B2B2B2B2B2B, w, s)
    b = norm(0x1D1D1D1D1D1D1D1D, w, s)
    n = 5
    emit("\t\tlet:<mut %s> a%s = mut wrap:<%s>(atol(\"%d\"))" % (t, t, t, a & mask(w)))
    emit("\t\tlet:<mut %s> b%s = mut wrap:<%s>(atol(\"%d\"))" % (t, t, t, b & mask(w)))
    emit("\t\tlet:<mut %s> n%s = mut wrap:<%s>(atol(\"%d\"))" % (t, t, t, n))
    for o in ("and", "or", "xor"):
        emit("\t\tchk_%s(\"atol %s %s\", %s(a%s, b%s), %s)" % (t, o, t, BUILTIN[o], t, t, sp(OPS[o](a, b, w, s))))
    emit("\t\tchk_%s(\"atol not %s\", bits_not(a%s), %s)" % (t, t, t, sp(m_not(a, w, s))))
    emit("\t\tchk_%s(\"atol left %s\", bits_left(a%s, n%s), %s)" % (t, t, t, t, sp(m_shl(a, n, w, s))))
    emit("\t\tchk_%s(\"atol right %s\", bits_right(a%s, n%s), %s)" % (t, t, t, t, sp(m_shr(a, n, w, s))))
emit("\t}", "\treturn", "}")
emit("")


# ---------------------------------------------------------------------------------------------- H: narrow results passed straight to a vararg
# A vararg is the one consumer that reads the whole 64-bit stack word without extending it from the operand width, so an unsigned narrow
# result must already be zero-extended there. The text is formatted by snprintf and read back with atol, so the check is self-checking.
emit('@link_name(snprintf)', 'extern c_snprintf(raw mut u8, mut u64, static imut string, ...) mut u64',
     '@link_name(atol)', 'extern atol_buf(raw mut u8) mut u64',
     "")
emit("func varargForms() void{")
emit("\tunsafe{")
emit("\t\tlet:<raw mut u8> buf = null")
emit("\t\tbuf = malloc(64)")
for t, w, s in TYPES:
    if s or w == 64:    # (atol saturates above 2^63, and only the narrow types need the extension)
        continue
    a = norm(0xF0F0F0F0F0F0F0F0, w, s)
    b = norm(0x3C3C3C3C3C3C3C3C, w, s)
    emit("\t\tlet:<mut %s> va%s = mut wrap:<%s>(atol(\"%d\"))" % (t, t, t, a))
    emit("\t\tlet:<mut %s> vb%s = mut wrap:<%s>(atol(\"%d\"))" % (t, t, t, b))
    emit("\t\tlet:<mut %s> vn%s = mut wrap:<%s>(atol(\"3\"))" % (t, t, t))
    emit("\t\tlet:<mut %s> vk%s = mut wrap:<%s>(atol(\"%d\"))" % (t, t, t, w - 1))
    emit("\t\tlet:<mut %s> v1%s = mut wrap:<%s>(atol(\"1\"))" % (t, t, t))
    cases = [("and", "bits_and(va%s, vb%s)" % (t, t), m_and(a, b, w, s)),
             ("or", "bits_or(va%s, vb%s)" % (t, t), m_or(a, b, w, s)),
             ("xor", "bits_xor(va%s, vb%s)" % (t, t), m_xor(a, b, w, s)),
             ("not", "bits_not(va%s)" % t, m_not(a, w, s)),
             ("not not", "bits_not(bits_not(va%s))" % t, a),
             ("left 3", "bits_left(va%s, vn%s)" % (t, t), m_shl(a, 3, w, s)),
             ("left w-1", "bits_left(va%s, vk%s)" % (t, t), m_shl(a, w - 1, w, s)),
             ("right 1", "bits_right(va%s, v1%s)" % (t, t), m_shr(a, 1, w, s)),
             ("not of left", "bits_not(bits_left(va%s, vn%s))" % (t, t), m_not(m_shl(a, 3, w, s), w, s))]
    for nm, ex, exp in cases:
        emit('\t\tc_snprintf(buf, 64, "%%llu", mut %s)' % ex)
        emit('\t\tchk_u64("vararg %s %s", atol_buf(buf), %s)' % (nm, t, sp(exp)))
emit("\t\tfree(buf)")
emit("\t}", "\treturn", "}")
emit("")
begin("M")
emit("func main() void{")
for t, w, s in TYPES:
    emit("\trt_%s()" % t)
for t, w, s in TYPES:
    emit("\tct_%s()" % t)
if MODE == "main":
    for t, w, s in TYPES:
        emit("\tsweep_%s()" % t)
    for t, w, s in TYPES:
        a0 = norm(0xC3A5C3A5C3A5C3A5, w, s)
        b0 = norm(0x0FF00FF00FF00FF0, w, s)
        emit("\tcont_%s(%s, %s, %s)" % (t, sp(a0), sp(b0), sp(3)))
    emit("\tfromAtol()")
    emit("\thotLoops()")
    emit("\tliteralForms()")
    emit("\tvarargForms()")
emit("\tunsafe{",
     '\t\tprintf("bits_ops%s_test: %%llu passed, %%llu failed\\n", passed, failed)' % ("" if MODE == "main" else "_matrix" + MODE[1:]),
     "\t\tif failed == 0{",
     '\t\t\tprintf("ALL BITS OPS TESTS PASSED\\n")',
     "\t\t}",
     "\t}",
     "\treturn",
     "}")

order = ["H", "A", "B", "X", "M"] if MODE == "main" else ["H", "A", "B", "M"]
sys.stdout.write("\n".join(l for k in order for l in sections.get(k, [])) + "\n")
