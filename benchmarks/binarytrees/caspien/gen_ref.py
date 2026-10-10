#!/usr/bin/env python3
"""Generates binarytrees_ref_{naive,safe,unsafe}.caspien: the REF variants (nodes are real heap objects linked with plain `ref` members of their own
struct; the owning pool is a safe dynarray of Holder{n: owns}). Same output as binarytrees_{naive,safe,unsafe}. usage: gen_ref.py [outdir]"""
import os, sys
out = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
KIND = None
CNT = [0]

def uid():
    CNT[0] += 1
    return CNT[0]

def ind(txt, n):
    return "\n".join(("\t" * n + l if l else l) for l in txt.split("\n"))

# ---- u64 work stack (build) ----
def push(v):
    if KIND == "unsafe": return f"stack[sp] = {v}\nsp += 1"
    if KIND == "safe": return f"match sp into stack{{\n\tstack[sp] = {v}\n}}\nsp += 1"
    return f"match sp into stack.backing{{\n\tstack.set(stack, sp, {v})\n}}\nsp += 1"
def pop(dst):
    if KIND == "unsafe": return f"sp -= 1\n{dst} = stack[sp]"
    if KIND == "safe": return f"sp -= 1\nmatch sp in stack{{\n\t{dst} = stack[sp]\n}}"
    return f"sp -= 1\nmatch sp in stack.backing{{\n\t{dst} = stack.get(stack, sp)\n}}"
# ---- ref work stack (check) ----
def rpush(x):
    if KIND == "unsafe": return f"rstack[sp].n = {x}\nsp += 1"
    if KIND == "safe": return f"match sp into rstack{{\n\trstack[sp].n = {x}\n}}\nsp += 1"
    q = f"q{uid()}"
    return f"let {q} = mut QRef{{n= {x}}}\nmatch sp into rstack.backing{{\n\trstack.setPtr(rstack, sp, auto {q})\n}}\nsp += 1"
def rpop(dst):
    if KIND == "unsafe": return f"sp -= 1\n{dst} = rstack[sp].n"
    if KIND == "safe": return f"sp -= 1\nmatch sp in rstack{{\n\t{dst} = rstack[sp].n\n}}"
    return f"sp -= 1\nmatch sp in rstack.backing{{\n\t{dst} = rstack.backing[sp].n\n}}"

def alloc(dst):
    nn = f"nn{uid()}"
    return (f"{dst} = top\ntop += 1\nlet {nn} = mut ? new Node{{l= null, r= null}}\nmatch {dst} into pool{{\n\tpool[{dst}].n = {nn}\n}}")
def build(root, depth):
    return ("fbase = top\n" + alloc(root) + "\nsp = 0\n" + push(f"{root} * 64 + {depth}") + "\nloop{\n\tif sp == 0{\n\t\tbreak\n\t}\n" +
            ind(pop("e"), 1) + "\n\tid = e / 64\n\tdp = e % 64\n\tif dp > 0{\n" +
            ind(alloc("ca"), 2) + "\n" + ind(alloc("cb"), 2) + "\n" +
            "\t\tmatch id in pool{\n\t\t\tmatch ca in pool{\n\t\t\t\tmatch cb in pool{\n\t\t\t\t\tmatch Some(pool[id].n){\n"
            "\t\t\t\t\t\tpool[id].n.l = ref pool[ca].n\n\t\t\t\t\t\tpool[id].n.r = ref pool[cb].n\n\t\t\t\t\t}\n\t\t\t\t}\n\t\t\t}\n\t\t}\n" +
            ind(push("ca * 64 + (dp - 1)"), 2) + "\n" + ind(push("cb * 64 + (dp - 1)"), 2) + "\n\t}\n}")
def check(root):
    return ("cnt = 0\nsp = 0\nmatch " + root + " in pool{\n" + ind(rpush(f"ref pool[{root}].n"), 1) + "\n}\nloop{\n\tif sp == 0{\n\t\tbreak\n\t}\n" +
            ind(rpop("cur"), 1) + "\n\tmatch Some(cur){\n\t\tcnt += 1\n\t\tlet nl = mut cur.l\n\t\tlet nr = mut cur.r\n" +
            ind(rpush("nl"), 2) + "\n" + ind(rpush("nr"), 2) + "\n\t}\n}")
def free(base):
    return (f"for i in {base}..top{{\n\tmatch i into pool{{\n\t\tpool[i].n = null\n\t}}\n}}\ntop = {base}")

DESC = {
 "naive": "NAIVE variant: the build stack is a stdlib DynamicArray<u64> and the check stack a DynamicArray<QRef> (setPtr / backing reads), every access under its bounds proof; the node pool that owns the nodes is a safe dynarray (an owning struct cannot be a DynamicArray element).",
 "safe": "SAFE variant: the node pool and both work stacks are safe dynarrays, every access under a bounds proof (match i in/into).",
 "unsafe": "UNSAFE variant: the two work stacks are unsafe dynarrays without bounds checks; the node pool that owns the nodes stays a safe dynarray (an unsafe one would destruct uninitialised owns slots on assignment).",
}

def program(kind):
    global KIND
    KIND = kind
    udyn_tag = " udyn" if kind == "unsafe" else ""
    if kind == "unsafe":
        lines = ["let stack = mut unsafe dyn:<u64>([])", "stack = resize(stack, 128)",
                 "let rstack = mut unsafe dyn:<QRef>([])", "rstack = resize(rstack, 128)"]
        imp = ""
    elif kind == "safe":
        lines = ["let stack = mut ? dyn:<u64>([])", "let:<mut u64> zero = mut 0", "stack = ? resize(stack, 128, zero)",
                 "let rstack = mut ? dyn([QRef{n= null}])", "rstack = ? resize(rstack, 128, QRef{n= null})"]
        imp = ""
    else:
        lines = ["let:<mut u64> zero = mut 0", "let rs = mut ? dyn:<u64>([])", "rs = ? resize(rs, 128, zero)",
                 "let stack = mut ? new DynamicArray:<u64>(rs)",
                 "let rr = mut ? dyn([QRef{n= null}])", "rr = ? resize(rr, 128, QRef{n= null})",
                 "let rstack = mut ? new DynamicArray:<QRef>(rr)"]
        imp = 'import "../../../stdlib/dynamic_array.caspien"\n'
    setup = "\n\t\t".join(lines) + "\n\t\t"
    return f"""// Binary trees (BINARYTREES_N = max depth), REF {DESC[kind]}
// Prints one line: stretch-tree check, then for d = 4,6,..,N the summed check of 2^(N-d+4) trees of depth d, then the long-lived tree check
// (check = number of nodes reached by walking the tree), exactly as binarytrees.c and the index-based variants.
//
// REF VARIANT: every node is its own heap object (`new Node`, registered in the ghost table) whose `l`/`r` are plain `ref` members of the
// same struct (a self-referential struct: allowed for `ref`, never for `ref some`/`owns`/`raw`/`auto`). Ownership lives in a pool of
// Holder{{n: owns}} slots; a tree is built node by node (children linked with `ref pool[i].n`), walked through the refs with an explicit
// stack of refs (each deref under a `match Some` alive proof, no recursion), and freed by dropping its pool slots (`pool[i].n = null`).
// The pool is filled by a bump index that is reset after each tree is freed (the long-lived tree keeps its slots).
import "../../../stdlib/libc.caspien"
{imp}import "../../../stdlib/gt/*"

extern getenv(static imut string) static imut string
extern atol(static imut string) mut u64

struct Node{{@pub{{
	l: ref mut Node
	r: ref mut Node
}}}}
struct Holder{{@pub{{ n: owns mut Node }}}}
struct QRef{{@pub{{ n: ref mut Node }}}}

func main() void{{
	?catch(e){{ return }}
	unsafe extern loop{udyn_tag}{{
		let maxd = mut 0
		maxd = atol(getenv("BINARYTREES_N"))
		if maxd < 6{{
			maxd = 6
		}}
		let cap = mut 1
		let lim = mut (maxd + 2)
		for i in 0..lim{{
			cap = cap * 2
		}}
		cap += 2
		let pool = mut ? dyn([Holder{{n= null}}])
		pool = ? resize(pool, cap, Holder{{n= null}})
		{setup}let top = mut 0
		let fbase = mut 0
		let llbase = mut 0
		let sp = mut 0
		let e = mut 0
		let id = mut 0
		let dp = mut 0
		let ca = mut 0
		let cb = mut 0
		let cnt = mut 0
		let root = mut 0
		let llroot = mut 0
		let sum = mut 0
		let d = mut 4
		let iters = mut 0
		let:<ref mut Node> cur = mut null
		// stretch tree: build, check, free
		let sd = mut (maxd + 1)
{ind(build("root", "sd"), 2)}
{ind(check("root"), 2)}
		printf("%llu", cnt)
{ind(free("fbase"), 2)}
		// long-lived tree: kept until the end
{ind(build("llroot", "maxd"), 2)}
		llbase = fbase
		loop{{
			if d > maxd{{
				break
			}}
			iters = 1
			let sh = mut ((maxd - d) + 4)
			for k in 0..sh{{
				iters = iters * 2
			}}
			sum = 0
			for it in 0..iters{{
{ind(build("root", "d"), 4)}
{ind(check("root"), 4)}
				sum += cnt
{ind(free("fbase"), 4)}
			}}
			printf(" %llu", sum)
			d += 2
		}}
{ind(check("llroot"), 2)}
		printf(" %llu\\n", cnt)
{ind(free("llbase"), 2)}
	}}
}}
"""

for k in ("naive", "safe", "unsafe"):
    CNT[0] = 0
    open(os.path.join(out, f"binarytrees_ref_{k}.caspien"), "w").write(program(k))
