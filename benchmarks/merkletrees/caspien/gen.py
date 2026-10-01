#!/usr/bin/env python3
"""Generates merkletrees_{naive,safe,unsafe}.caspien (real SHA-256, see ../SPEC.md). The three differ only in how the three big arrays
(leaf data, flat tree of 8-word digests, proof path) are accessed: naive = stdlib DynamicArray<u32> (get/set under bounds proofs),
safe = safe dynarrays (match i in/into), unsafe = unsafe dynarrays (no checks). The SHA-256 compression is identical in all three.
usage: gen.py [outdir]   (default: the directory of this script)"""
import os, sys

args = [a for a in sys.argv[1:] if not a.startswith("--")]
OUT = args[0] if args else os.path.dirname(os.path.abspath(__file__))
KIND = None

K = [0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
     0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
     0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
     0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
     0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
     0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
     0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
     0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2]
H0 = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]
M32 = 0xFFFFFFFF


def ind(txt, n):
    return "\n".join(("\t" * n + l if l else l) for l in txt.split("\n"))


# ---------------------------------------------------------------- the compression (identical in all variants)
def sched_words():
    """the padding block of a node message: 0x80000000, 13 zeros... W[15] = 512; returns its 64-word schedule"""
    w = [0x80000000] + [0] * 14 + [512]
    rot = lambda x, n: ((x >> n) | (x << (32 - n))) & M32
    for i in range(16, 64):
        s0 = rot(w[i - 15], 7) ^ rot(w[i - 15], 18) ^ (w[i - 15] >> 3)
        s1 = rot(w[i - 2], 17) ^ rot(w[i - 2], 19) ^ (w[i - 2] >> 10)
        w.append((w[i - 16] + s0 + w[i - 7] + s1) & M32)
    return w


DUP = 4294967297   # 2^32 + 1: for a clean 32-bit x held in a u64, x * DUP = x << 32 | x, so (x * DUP) >> n has rotr32(x, n) in its low 32 bits


def sig3(dup, n0, n1, n2):
    """low 32 bits of the xor of three right-rotations by n0 n1 n2 of the value whose doubled form is `dup`; the high bits are garbage that
    later additions carry only upwards (everything is masked back to 32 bits where a value is stored)"""
    return f"bits_xor(bits_xor(bits_right({dup}, {n0}), bits_right({dup}, {n1})), bits_right({dup}, {n2}))"


def round_stmts(v, kterm):
    """one round, working variables renamed (v = names for a..h this round); a..h always hold clean 32-bit values in u64 locals.
    kterm: the literal or local added as K[i] + W[i]. Each statement is a nested expression of locals and literals only."""
    a, b, c, d, e, f, g, h = v
    return [f"ee = {e} * {DUP}",
            f"p = {h} + {kterm}",
            f"p = p + {sig3('ee', 6, 11, 25)}",
            f"p = p + bits_xor({g}, bits_and({e}, bits_xor({f}, {g})))",
            f"aa = {a} * {DUP}",
            f"q = {sig3('aa', 2, 13, 22)}",
            f"q = q + bits_or(bits_and({a}, {b}), bits_and({c}, bits_or({a}, {b})))",
            f"{d} = bits_and({d} + p, 0xffffffff)",
            f"{h} = bits_and(p + q, 0xffffffff)"]


def rounds_unrolled(kterm_stmts):
    names = list("abcdefgh")
    out = []
    for i in range(64):
        v = names[-(i % 8):] + names[:-(i % 8)] if i % 8 else names[:]
        pre, term = kterm_stmts(i)
        out += pre
        out += round_stmts(v, term)
    return "\n".join(out)


def comp_funcs():
    zeros64 = ", ".join(["0"] * 64)
    sw = sched_words()
    kw = [(K[i] + sw[i]) & M32 for i in range(64)]
    sched = []
    for i in range(16, 64):
        sched += [f"x15 = W[{i - 15}]", f"x2 = W[{i - 2}]",
                  f"ee = x15 * {DUP}", f"aa = x2 * {DUP}",
                  f"p = bits_xor(bits_xor(bits_right(ee, 7), bits_right(ee, 18)), bits_right(x15, 3))",
                  f"q = bits_xor(bits_xor(bits_right(aa, 17), bits_right(aa, 19)), bits_right(x2, 10))",
                  f"x15 = W[{i - 16}]", f"x2 = W[{i - 7}]",
                  f"W[{i}] = bits_and(p + q + x15 + x2, 0xffffffff)"]
    load = "\n".join(f"{n} = S[{k}]" for k, n in enumerate("abcdefgh"))
    store = "\n".join(f"x15 = S[{k}]\nS[{k}] = bits_and(x15 + {n}, 0xffffffff)" for k, n in enumerate("abcdefgh"))
    decl = "\n".join(f"let:<mut u64> {n} = mut 0" for n in ["a", "b", "c", "d", "e", "f", "g", "h", "p", "q", "ee", "aa", "x15", "x2", "wv"])
    r1 = rounds_unrolled(lambda i: ([f"wv = W[{i}]"], f"wv + 0x{K[i]:08x}"))
    r2 = rounds_unrolled(lambda i: ([], f"0x{kw[i]:08x}"))
    return f'''// The SHA-256 working state S[0..7] and message schedule W[0..63] live in global u64 arrays (every value is a 32-bit quantity held in a u64, so
// all arithmetic is plain 8-byte integer arithmetic, the shape the register-form pass fuses best; additions are masked back to 32 bits where a
// value is kept) so one compression function serves every call site (an array cannot be passed to a function). W[0..15] is the input block (16
// big-endian words), S the 8-word state; `comp` updates S in place. A rotate right by n of a clean 32-bit x is the low half of
// (x * 4294967297) >> n, so a three-rotation sigma costs 1 multiply, 3 shifts and 2 xors instead of 9 shift/or operations and 2 xors.
let static:<mut u64[64]> W = mut [{zeros64}]
let static:<mut u64[8]> S = mut [0, 0, 0, 0, 0, 0, 0, 0]

// compress the block in W[0..15] into S: schedule W[16..63], 64 rounds fully unrolled with the working variables renamed instead of shifted
func comp() void{{
	unsafe{{
{ind(decl, 2)}
{ind(chr(10).join(sched), 2)}
{ind(load, 2)}
{ind(r1, 2)}
{ind(store, 2)}
	}}
}}

// compress the second block of a 64-byte message, which is pure padding (0x80000000, 13 zero words... , 512): its schedule is a constant, so
// every round adds one precomputed literal K[i] + W[i] and no schedule is computed
func compPad() void{{
	unsafe{{
{ind(decl, 2)}
{ind(load, 2)}
{ind(r2, 2)}
{ind(store, 2)}
	}}
}}
'''


# ---------------------------------------------------------------- element access primitives (statements; ix is a bare variable)
def rd(dst, arr, ix, widen=False):
    cast = " as u64" if widen else ""
    if KIND == "unsafe":
        return f"{dst} = {arr}[{ix}]{cast}"
    if KIND == "safe":
        return f"match {ix} in {arr}{{\n\t{dst} = {arr}[{ix}]{cast}\n}}"
    return f"match {ix} in {arr}.backing{{\n\t{dst} = {arr}.get({arr}, {ix}){cast}\n}}"


def wr(arr, ix, val):
    if KIND == "unsafe":
        return f"{arr}[{ix}] = {val}"
    if KIND == "safe":
        return f"match {ix} into {arr}{{\n\t{arr}[{ix}] = {val}\n}}"
    return f"match {ix} into {arr}.backing{{\n\t{arr}.set({arr}, {ix}, {val})\n}}"


def load_to_W(arr, base, woff):
    """W[woff + k] = arr[base + k] for k in 0..7 (base is a variable holding the first word index)"""
    out = []
    for k in range(8):
        out.append(f"ix = {base} + {k}\n" + rd(f"W[{woff + k}]", arr, "ix", True))
    return "\n".join(out)


def store_S(arr, base):
    out = []
    for k in range(8):
        out.append(f"ix = {base} + {k}\n" + wr(arr, "ix", f"wrap:<u32>(S[{k}])"))
    return "\n".join(out)


HEADERS = {
    "naive": "NAIVE variant: the stdlib DynamicArray<u32> class (get/set methods, each under its bounds proof) for the leaf data, the flat tree and the proof path.",
    "safe": "SAFE variant: safe dynarrays, every element access under a bounds proof (match i in/into array).",
    "unsafe": "UNSAFE variant: unsafe dynarrays, no bounds proofs.",
}


def program():
    nv = KIND
    imports = '''import "../../../stdlib/libc.caspien"
''' + ('import "../../../stdlib/dynamic_array.caspien"\n' if nv == "naive" else "") + '''import "../../../stdlib/gt_init.caspien"
import "../../../stdlib/gt_register.caspien"
import "../../../stdlib/gt_alive_check.caspien"
import "../../../stdlib/gt_destruct.caspien"
'''
    # allocation
    if nv == "unsafe":
        alloc = '''let dat = mut unsafe dyn:<u32>([])
let t = mut unsafe dyn:<u32>([])
let sibs = mut unsafe dyn:<u32>([])
let tn = mut ((2 * n + 64) * 8)
dat = resize(dat, n)
t = resize(t, tn)
sibs = resize(sibs, 512)'''
    elif nv == "safe":
        alloc = '''let:<mut u32> zero = mut 0
let dat = mut ? dyn:<u32>([])
let t = mut ? dyn:<u32>([])
let sibs = mut ? dyn:<u32>([])
let tn = mut ((2 * n + 64) * 8)
dat = ? resize(dat, n, zero)
t = ? resize(t, tn, zero)
sibs = ? resize(sibs, 512, zero)'''
    else:
        alloc = '''let:<mut u32> zero = mut 0
let rdat = mut ? dyn:<u32>([])
let rt = mut ? dyn:<u32>([])
let rsibs = mut ? dyn:<u32>([])
let tn = mut ((2 * n + 64) * 8)
rdat = ? resize(rdat, n, zero)
let dat = mut ? new DynamicArray:<u32>(rdat)
rt = ? resize(rt, tn, zero)
let t = mut ? new DynamicArray:<u32>(rt)
rsibs = ? resize(rsibs, 512, zero)
let sibs = mut ? new DynamicArray:<u32>(rsibs)'''
    catch = "" if nv == "unsafe" else "\t?catch(e){ return }\n"
    leaf_store = store_S("t", "tb")
    build_loads = (load_to_W("t", "lb", 0) + "\n" + load_to_W("t", "rb", 8))
    root_loads = "\n".join(f"ix = rb + {k}\n" + rd(f"r{k}", "t", "ix", True) for k in range(8))
    sib_copy = "\n".join(f"ix = sbase + {k}\n" + rd("sv", "t", "ix") + f"\nix2 = dbase + {k}\n" + wr("sibs", "ix2", "sv") for k in range(8))
    # verification: cur in c0..c7 locals; sibling from sibs into W at the half where it belongs
    ver_even = ("\n".join(f"W[{k}] = c{k}" for k in range(8)) + "\n" +
                "\n".join(f"ix = sb2 + {k}\n" + rd(f"W[{8 + k}]", "sibs", "ix", True) for k in range(8)))
    ver_odd = ("\n".join(f"ix = sb2 + {k}\n" + rd(f"W[{k}]", "sibs", "ix", True) for k in range(8)) + "\n" +
               "\n".join(f"W[{8 + k}] = c{k}" for k in range(8)))
    cur_from_S = "\n".join(f"c{k} = S[{k}]" for k in range(8))
    h0_to_S = "\n".join(f"S[{k}] = 0x{v:08x}" for k, v in enumerate(H0))
    leaf_consts = "\n".join(f"W[{k}] = 0" for k in range(5, 15))
    cdecl = "\n".join(f"let:<mut u64> c{k} = mut 0" for k in range(8))
    rdecl = "\n".join(f"let:<mut u64> r{k} = mut 0" for k in range(8))
    cmp_root = " && ".join(f"c{k} == r{k}" for k in range(8))
    text = f'''// Merkle tree benchmark with REAL SHA-256 (FIPS 180-4), see benchmarks/merkletrees/SPEC.md: MERKLE_N leaves, leaf i = SHA256(le64(d_i) || le64(i)) with
// d_i from a 32-bit LCG; the tree is built level by level in one flat array of 8-word digests (an odd last node of a level is paired with itself);
// then N/2 inclusion proofs for LCG-chosen leaves are generated (sibling digests up to the root) and verified; every 4th proof is tampered with
// (leaf data + 1) and must be rejected. Prints: <root as 64 hex digits> <verified> <rejected>.
// {HEADERS[nv]}
// GENERATED by gen.py (the three variants share every line of the hash; only the array accesses differ). The compression function is written
// out with the bitwise builtins: 64 rounds unrolled with the working variables renamed instead of shifted, every statement a single nested
// expression of locals and literals (what the register-form pass fuses), 32-bit values kept in u64 locals and masked after additions,
// a rotate done as the low half of (x * 0x100000001) >> n; the padding block of an internal node has a constant message schedule and is a
// separate function (compPad) with precomputed K + W round constants. The tree arrays are u32 (8 words per digest).
{imports}
extern getenv(static imut string) static imut string
extern atol(static imut string) mut u64

{comp_funcs()}
func bswap(v: mut u32) mut u32{{
	return bits_or(bits_or(bits_right(v, 24), bits_and(bits_right(v, 8), 0xff00)), bits_or(bits_and(bits_left(v, 8), 0xff0000), bits_left(v, 24)))
}}

// S = SHA256(le64(d) || le64(i)) for d = dhi * 2^32 + dlo, i = ihi * 2^32 + ilo: one block (16-byte message plus padding)
func leaf(dlo: mut u32, dhi: mut u32, ilo: mut u32, ihi: mut u32) void{{
	unsafe{{
		W[0] = bswap(dlo) as u64
		W[1] = bswap(dhi) as u64
		W[2] = bswap(ilo) as u64
		W[3] = bswap(ihi) as u64
		W[4] = 0x80000000
{ind(leaf_consts, 2)}
		W[15] = 128
{ind(h0_to_S, 2)}
		comp()
	}}
}}

// S = SHA256(L || R) where the 16 words of L || R are in W[0..15]: two blocks, the second one pure padding
func node() void{{
	unsafe{{
{ind(h0_to_S, 2)}
		comp()
		compPad()
	}}
}}

func main() void{{
{catch}	unsafe{{
		let n = mut 0
		n = atol(getenv("MERKLE_N"))
{ind(alloc, 2)}
		let ix = mut 0
		let ix2 = mut 0
		let:<mut u32> xl = mut 12345
		let:<mut u32> d0 = mut 0
		for i in 0..n{{
			xl = xl * 1664525 + 1013904223
			d0 = xl
			let tb = mut (i * 8)
{ind(wr("dat", "i", "d0"), 3)}
			let ihi = mut wrap:<u32>(bits_right(i, 32))
			leaf(d0, 0, wrap:<u32>(i), ihi)
{ind(leaf_store, 3)}
		}}
		let off = mut 0
		let size = mut n
		loop{{
			if size <= 1{{
				break
			}}
			let noff = mut (off + size)
			let ns = mut ((size + 1) / 2)
			for j in 0..ns{{
				let li = mut (off + 2 * j)
				let ri = mut li
				if 2 * j + 1 < size{{
					ri = li + 1
				}}
				let lb = mut (li * 8)
				let rb = mut (ri * 8)
{ind(build_loads, 4)}
				node()
				let oj = mut (noff + j)
				let tb = mut (oj * 8)
{ind(store_S("t", "tb"), 4)}
			}}
			off = noff
			size = ns
		}}
{ind(rdecl, 2)}
		let rb = mut (off * 8)
{ind(root_loads, 2)}
		let verified = mut 0
		let rejected = mut 0
		let half = mut (n / 2)
{ind(cdecl, 2)}
		let:<mut u32> sv = mut 0
		for p in 0..half{{
			xl = xl * 1664525 + 1013904223
			let xw = mut xl as u64
			let idx = mut (xw % n)
			let o = mut 0
			let s = mut n
			let pos = mut idx
			let depth = mut 0
			loop{{
				if s <= 1{{
					break
				}}
				let sb = mut (pos + 1)
				if pos % 2 != 0{{
					sb = pos - 1
				}}
				if sb >= s{{
					sb = pos
				}}
				let sbase = mut ((o + sb) * 8)
				let dbase = mut (depth * 8)
{ind(sib_copy, 4)}
				depth += 1
				o += s
				s = (s + 1) / 2
				pos = pos / 2
			}}
			let:<mut u32> dlo = mut 0
{ind(rd("dlo", "dat", "idx"), 3)}
			let:<mut u32> dhi = mut 0
			if p % 4 == 3{{
				dlo = dlo + 1
				if dlo == 0{{
					dhi = 1
				}}
			}}
			let ihi2 = mut wrap:<u32>(bits_right(idx, 32))
			leaf(dlo, dhi, wrap:<u32>(idx), ihi2)
{ind(cur_from_S, 3)}
			pos = idx
			for d in 0..depth{{
				let sb2 = mut (d * 8)
				if pos % 2 == 0{{
{ind(ver_even, 5)}
				}}else{{
{ind(ver_odd, 5)}
				}}
				node()
{ind(cur_from_S, 4)}
				pos = pos / 2
			}}
			if {cmp_root}{{
				verified += 1
			}}else{{
				rejected += 1
			}}
		}}
		printf("%08x%08x%08x%08x", r0, r1, r2, r3)
		printf("%08x%08x%08x%08x %llu %llu\\n", r4, r5, r6, r7, verified, rejected)
	}}
}}
'''
    return text


def main():
    global KIND
    for kind in ("naive", "safe", "unsafe"):
        KIND = kind
        t = program()
        with open(os.path.join(OUT, f"merkletrees_{kind}.caspien"), "w") as f:
            f.write(t)


main()
