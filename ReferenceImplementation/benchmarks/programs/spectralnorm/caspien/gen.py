#!/usr/bin/env python3
"""Writes spectralnorm_naive.caspien / spectralnorm_opt.caspien for a given N (the size is baked in: arrays cannot be parameters
and a `let static` array needs a literal initialiser).   usage: gen.py N [outdir]"""
import sys, os
N = int(sys.argv[1]); out = sys.argv[2] if len(sys.argv) > 2 else os.path.dirname(os.path.abspath(__file__))
ONES = ", ".join(["1.0"] * N)
ZEROS = ", ".join(["0.0"] * N)
HDR = f'''// spectral-norm (Computer Language Benchmarks Game), single threaded, N = {N}; same results as the reference C
// program (spectralnorm.c): ten rounds of v = AtA u, u = AtA v, then sqrt(vBv / vv), printed with %0.9f.
//
// DIFFERENCES FROM THE C PROGRAM (the incremental denominator above is the one deliberate optimisation; the rest are forced by the current language):
//  * u, v and t are `let static` f64[{N}] arrays (an array cannot be a parameter, a `let static` array needs a literal initialiser), so
//    the capacity is fixed at {N} (the run size n comes from SPECTRAL_N, n <= {N}; the loop bounds are runtime values), and the matrix-vector routines are written out once per array pair (t = A u, v = At t, t = A v, u = At t).
//  * there is no int-to-float cast, so every loop keeps an f64 mirror of its integer index (`fi += 1.0`); both are exact.
extern printf(static imut string,...) void
extern sqrt(mut f64) mut f64
extern getenv(static imut string) static imut string
extern atol(static imut string) mut u64

let static:<mut f64[{N}]> u = mut [{ONES}]
let static:<mut f64[{N}]> v = mut [{ZEROS}]
let static:<mut f64[{N}]> t = mut [{ZEROS}]
'''
def pair(name, src, dst, transpose, naive):
    if naive:
        a, b = ("fj", "fi") if transpose else ("fi", "fj")
        return f'''
// {dst} = {'At' if transpose else 'A'} {src}
func {name}(n: mut u64) void{{
	unsafe{{
		let:<mut f64> fi = mut 0.0
		for i in 0..n{{
			let:<mut f64> sum = mut 0.0
			let:<mut f64> fj = mut 0.0
			for j in 0..n{{
				assume match j in {src}
				sum += evalA({a}, {b}) * {src}[j]
				fj += 1.0
			}}
			assume match i into {dst}
			{dst}[i] = sum
			fi += 1.0
		}}
	}}
}}
'''
    if not transpose:
        return f'''
func {name}(n: mut u64) void{{
	unsafe{{
		let:<mut f64> fi = mut 0.0
		for i in 0..n{{
			let:<mut f64> sum = mut 0.0
			let s = mut fi
			let d = mut (fi * (fi + 1.0) * 0.5 + fi + 1.0)
			for j in 0..n{{
				assume match j in {src}
				sum += (1.0 / d) * {src}[j]
				d += s + 1.0
				s += 1.0
			}}
			assume match i into {dst}
			{dst}[i] = sum
			fi += 1.0
		}}
	}}
}}
'''
    return f'''
func {name}(n: mut u64) void{{
	unsafe{{
		let:<mut f64> fi = mut 0.0
		for i in 0..n{{
			let:<mut f64> sum = mut 0.0
			let s = mut fi
			let d = mut (fi * (fi + 1.0) * 0.5 + 1.0)
			for j in 0..n{{
				assume match j in {src}
				sum += (1.0 / d) * {src}[j]
				d += s + 2.0
				s += 1.0
			}}
			assume match i into {dst}
			{dst}[i] = sum
			fi += 1.0
		}}
	}}
}}
'''
MAIN = '''
func main() void{
	unsafe{
		let n = mut 0
		n = atol(getenv("SPECTRAL_N"))
		for round in 0..10{
			mulAu(n)
			mulAtt(n)
			mulAv(n)
			mulAtt2(n)
		}
		let:<mut f64> vBv = mut 0.0
		let:<mut f64> vv = mut 0.0
		for i in 0..n{
			assume match i in u
			assume match i in v
			vBv += u[i] * v[i]
			vv += v[i] * v[i]
		}
		let r = mut sqrt(vBv / vv)
		printf("%0.9f\\n", r)
	}
}
'''
EVAL = '''
// the matrix element A[i][j] = 1 / ((i + j)(i + j + 1) / 2 + i + 1), i and j given as f64
func evalA(fi: mut f64, fj: mut f64) mut f64{
	unsafe{
		let s = mut (fi + fj)
		return 1.0 / (s * (s + 1.0) * 0.5 + fi + 1.0)
	}
}
'''
for naive, fn in ((True, "spectralnorm_naive.caspien"), (False, "spectralnorm_opt.caspien")):
    body = HDR + (EVAL if naive else "")
    # the four routines: t = A u, v = At t, t = A v, u = At t
    body += pair("mulAu", "u", "t", False, naive) + pair("mulAtt", "t", "v", True, naive)
    body += pair("mulAv", "v", "t", False, naive) + pair("mulAtt2", "t", "u", True, naive)
    body += MAIN
    if not naive:
        body = body.replace("// spectral-norm (Computer Language Benchmarks Game), single threaded", "// spectral-norm, HAND-OPTIMISED: no evalA call, the denominator is updated incrementally instead of recomputed (single threaded", 1)
    open(os.path.join(out, fn), "w").write(body)
