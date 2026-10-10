#!/usr/bin/env python3
"""Generates binarytrees_{naive,safe,unsafe}.caspien (the three differ only in how the node pool / work stack are accessed).
usage: gen.py [outdir]"""
import os, sys
out = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
KIND = None

def ind(txt, n):
    return "\n".join(("\t" * n + l if l else l) for l in txt.split("\n"))

# ---- element access primitives (statements) ----
def rdL(dst, i):
    if KIND == "unsafe": return f"{dst} = nodes[{i}].l"
    if KIND == "safe": return f"match {i} in nodes{{\n\t{dst} = nodes[{i}].l\n}}"
    return f"match {i} in nodes.backing{{\n\tlet nd = mut nodes.get(nodes, {i})\n\t{dst} = nd.l\n}}"
def rdR(dst, i):
    if KIND == "unsafe": return f"{dst} = nodes[{i}].r"
    if KIND == "safe": return f"match {i} in nodes{{\n\t{dst} = nodes[{i}].r\n}}"
    return f"match {i} in nodes.backing{{\n\tlet nd = mut nodes.get(nodes, {i})\n\t{dst} = nd.r\n}}"
def wrLR(i, l, r):
    if KIND == "unsafe": return f"nodes[{i}].l = {l}\nnodes[{i}].r = {r}"
    if KIND == "safe": return f"match {i} into nodes{{\n\tnodes[{i}].l = {l}\n\tnodes[{i}].r = {r}\n}}"
    return f"match {i} into nodes.backing{{\n\tlet nn = mut Node{{l= {l}, r= {r}}}\n\tnodes.setPtr(nodes, {i}, auto nn)\n}}"
def wrL(i, l):
    if KIND == "unsafe": return f"nodes[{i}].l = {l}"
    if KIND == "safe": return f"match {i} into nodes{{\n\tnodes[{i}].l = {l}\n}}"
    return f"match {i} into nodes.backing{{\n\tnodes.backing[{i}].l = {l}\n}}"
def push(v):
    if KIND == "unsafe": return f"stack[sp] = {v}\nsp += 1"
    if KIND == "safe": return f"match sp into stack{{\n\tstack[sp] = {v}\n}}\nsp += 1"
    return f"match sp into stack.backing{{\n\tstack.set(stack, sp, {v})\n}}\nsp += 1"
def pop(dst):
    if KIND == "unsafe": return f"sp -= 1\n{dst} = stack[sp]"
    if KIND == "safe": return f"sp -= 1\nmatch sp in stack{{\n\t{dst} = stack[sp]\n}}"
    return f"sp -= 1\nmatch sp in stack.backing{{\n\t{dst} = stack.get(stack, sp)\n}}"

# ---- tree operations ----
def alloc(dst):
    return (f"if freeh != 0{{\n\t{dst} = freeh\n" + ind(rdL("freeh", dst), 1) + "\n}else{\n"
            f"\t{dst} = top\n\ttop += 1\n}}\n" + wrLR(dst, "0", "0"))
def build(root, depth):
    return (alloc(root) + "\nsp = 0\n" + push(f"{root} * 64 + {depth}") + "\nloop{\n\tif sp == 0{\n\t\tbreak\n\t}\n" +
            ind(pop("e"), 1) + "\n\tid = e / 64\n\tdp = e % 64\n\tif dp > 0{\n" +
            ind(alloc("ca"), 2) + "\n" + ind(alloc("cb"), 2) + "\n" + ind(wrLR("id", "ca", "cb"), 2) + "\n" +
            ind(push("ca * 64 + (dp - 1)"), 2) + "\n" + ind(push("cb * 64 + (dp - 1)"), 2) + "\n\t}\n}")
def check(root):
    return ("cnt = 0\nsp = 0\n" + push(root) + "\nloop{\n\tif sp == 0{\n\t\tbreak\n\t}\n" + ind(pop("id"), 1) +
            "\n\tcnt += 1\n" + ind(rdL("ca", "id"), 1) + "\n\tif ca != 0{\n" + ind(push("ca"), 2) + "\n" +
            ind(rdR("cb", "id"), 2) + "\n" + ind(push("cb"), 2) + "\n\t}\n}")
def free(root):
    return ("sp = 0\n" + push(root) + "\nloop{\n\tif sp == 0{\n\t\tbreak\n\t}\n" + ind(pop("id"), 1) + "\n" +
            ind(rdL("ca", "id"), 1) + "\n\tif ca != 0{\n" + ind(push("ca"), 2) + "\n" + ind(rdR("cb", "id"), 2) + "\n" +
            ind(push("cb"), 2) + "\n\t}\n" + ind(wrL("id", "freeh"), 1) + "\n\tfreeh = id\n}")

DESC = {
 "naive": "NAIVE variant: the node pool is ONE stdlib DynamicArray<Node> (struct elements: reads are `get` copies into a local, whole-node writes are `setPtr` of a local Node, the free-list link a direct field write) and the work stack a DynamicArray<u64>, every access under its bounds proof.",
 "safe": "OPTIMIZED SAFE variant: the node pool is ONE safe dynarray of Node structs and the work stack a safe dynarray<u64>, every access under a bounds proof (match i in/into).",
 "unsafe": "OPTIMIZED UNSAFE variant: unsafe dynarrays for the node pool (Node structs) and the work stack, no bounds checks.",
}

def program(kind):
    global KIND
    KIND = kind
    udyn_tag = " udyn" if kind == "unsafe" else ""
    if kind == "unsafe":
        lines = ["let nodes = mut unsafe dyn:<Node>([])", "let stack = mut unsafe dyn:<u64>([])",
                 "nodes = resize(nodes, cap)", "stack = resize(stack, 128)"]
        imp = ""
        head = ""
    elif kind == "safe":
        lines = ["let nodes = mut ? dyn:<Node>([])", "let stack = mut ? dyn:<u64>([])", "let zn = mut Node{l= 0, r= 0}",
                 "let:<mut u64> zero = mut 0", "nodes = ? resize(nodes, cap, zn)", "stack = ? resize(stack, 128, zero)"]
        imp = ""
        head = "?catch(e){ return }\n\t"
    else:
        lines = ["let:<mut u64> zero = mut 0",
                 "let zn = mut Node{l= 0, r= 0}",
                 "let rn = mut ? dyn:<Node>([])", "rn = ? resize(rn, cap, zn)", "let nodes = mut ? new DynamicArray:<Node>(rn)",
                 "let rs = mut ? dyn:<u64>([])", "rs = ? resize(rs, 128, zero)", "let stack = mut ? new DynamicArray:<u64>(rs)"]
        imp = 'import "../../../stdlib/dynamic_array.caspien"\n'
        head = "?catch(e){ return }\n\t"
    setup = "\n\t\t".join(lines) + "\n\t\t"
    extra = ""
    body = f"""// Binary trees (BINARYTREES_N = max depth), {DESC[kind]}
// Prints one line: stretch-tree check, then for d = 4,6,..,N the summed check of 2^(N-d+4) trees of depth d, then the long-lived tree check
// (check = number of nodes reached by walking the tree), exactly as binarytrees.c.
//
// DIFFERENCES FROM THE REFERENCE (pointer nodes, recursive make/check/free): This is the INDEX-BASED version (the binarytrees_ref_* variants link real heap nodes with `ref` members instead). Caspien only supports
// structurally-decreasing recursion, so the tree is built without recursion: nodes live in one pool (a dynarray of Node{{l, r}} structs, children are pool
// indices, 0 = null), allocated from a free list (a freed node is chained through its `l` field) or by bumping `top`. make/check/free are
// iterative with an explicit work stack (entries pack node*64 + depth for the build). Allocation and freeing are real: every tree is
// built node by node, walked, then every node is returned to the free list. The pool never grows (capacity 2^(N+2)+2 covers the stretch tree).{extra}
import "../../../stdlib/libc.caspien"
{imp}import "../../../stdlib/gt/*"

extern getenv(static imut string) static imut string
extern atol(static imut string) mut u64

struct Node{{
	@pub{{ l: mut u64 }}
	@pub{{ r: mut u64 }}
}}

func main() void{{
	{head}unsafe extern loop{udyn_tag}{{
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
		{setup}let freeh = mut 0
		let top = mut 1
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
		// stretch tree: build, check, free
		let sd = mut (maxd + 1)
{ind(build("root", "sd"), 2)}
{ind(check("root"), 2)}
		printf("%llu", cnt)
{ind(free("root"), 2)}
		// long-lived tree: kept until the end
{ind(build("llroot", "maxd"), 2)}
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
{ind(free("root"), 4)}
			}}
			printf(" %llu", sum)
			d += 2
		}}
{ind(check("llroot"), 2)}
		printf(" %llu\\n", cnt)
{ind(free("llroot"), 2)}
	}}
}}
"""
    return body

for k in ("naive", "safe", "unsafe"):
    open(os.path.join(out, f"binarytrees_{k}.caspien"), "w").write(program(k))
