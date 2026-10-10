#!/usr/bin/env python3
"""Generates lru_ref_{naive,safe,unsafe}.caspien: the REF variants of the LRU benchmark. Entries are real heap nodes of one struct whose
prv/nxt (recency list) and hn (hash chain) members are plain `ref`s to other entries of the same struct; the bucket array holds ref heads;
the entries are owned by a safe dynarray of Holder{n: owns}. Same output as lru_{naive,safe,unsafe}. usage: gen_ref.py [outdir]"""
import os, sys
out = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
KIND = None
C = [0]
def u(p):
    C[0] += 1
    return f"{p}_{C[0]}"
def ind(txt, n):
    return "\n".join(("\t" * n + l if l else l) for l in txt.split("\n"))

def bget(dst, h):
    if KIND == "unsafe": return f"{dst} = bucket[{h}].n"
    if KIND == "safe": return f"match {h} in bucket{{\n\t{dst} = bucket[{h}].n\n}}"
    return f"match {h} in bucket.backing{{\n\t{dst} = bucket.backing[{h}].n\n}}"
def bset(h, x):
    if KIND == "unsafe": return f"bucket[{h}].n = {x}"
    if KIND == "safe": return f"match {h} into bucket{{\n\tbucket[{h}].n = {x}\n}}"
    q = u("qh")
    return f"let {q} = mut QRef{{n= {x}}}\nmatch {h} into bucket.backing{{\n\tbucket.setPtr(bucket, {h}, auto {q})\n}}"

def hsh(dst, k):
    return f"let {dst} = mut ((bits_and({k} * 2654435761, 0xFFFFFFFF)) / 8192)"

def unlink(e):
    p, q = u("p"), u("q")
    return (f"let {p} = mut {e}.prv\nlet {q} = mut {e}.nxt\nmatch Some({p}){{\n\t{p}.nxt = {q}\n}}\nelse{{\n\thead = {q}\n}}\n"
            f"match Some({q}){{\n\t{q}.prv = {p}\n}}\nelse{{\n\ttail = {p}\n}}")
def pushfront(e):
    return (f"{e}.prv = nil.n\n{e}.nxt = head\nmatch Some(head){{\n\thead.prv = {e}\n}}\nelse{{\n\ttail = {e}\n}}\nhead = {e}")

def program(kind):
    global KIND
    KIND = kind
    C[0] = 0
    udyn = " udyn" if kind == "unsafe" else ""
    imp = 'import "../../../stdlib/dynamic_array.caspien"\n' if kind == "naive" else ""
    if kind == "unsafe":
        setup = ("let bucket = mut unsafe dyn:<QRef>([])\n\t\tbucket = resize(bucket, 524288)\n\t\tfor bi in 0..524288{\n\t\t\tbucket[bi].n = nil.n\n\t\t}")
    elif kind == "safe":
        setup = "let bucket = mut ? dyn([QRef{n= null}])\n\t\tbucket = ? resize(bucket, 524288, QRef{n= null})"
    else:
        setup = ("let rb = mut ? dyn([QRef{n= null}])\n\t\trb = ? resize(rb, 524288, QRef{n= null})\n\t\tlet bucket = mut ? new DynamicArray:<QRef>(rb)")
    desc = {"naive": "NAIVE variant: the bucket array is a stdlib DynamicArray<QRef> (setPtr / backing reads); the entries are owned by a safe dynarray of owns slots.",
            "safe": "SAFE variant: safe dynarrays (bucket heads, owner slots), every element access under a bounds proof (match i in/into).",
            "unsafe": "UNSAFE variant: the bucket heads are an unsafe dynarray of refs without bounds checks; the owner slots stay a safe dynarray (an unsafe one would destruct uninitialised owns slots)."}[kind]
    mf_hit = unlink("i") + "\n" + pushfront("i")
    mf_put = unlink("i") + "\n" + pushfront("i")
    evict = (
        "ent = tail\nmatch Some(ent){\n\tlet evk = mut ent.key\n\tsum = bits_and(sum + evk, 0xFFFFFFFF)\n" + ind(unlink("ent"), 1) + "\n" +
        ind(hsh("hb", "evk"), 1) + "\n\tlet bh = mut nil.n\n" + ind(bget("bh", "hb"), 1) + "\n\tlet enx = mut ent.hn\n\tmatch Some(bh){\n\t\tlet bhk = mut bh.key\n\t\tif bhk == evk{\n" +
        ind(bset("hb", "enx"), 3) + "\n\t\t}else{\n\t\t\tlet j = mut bh\n\t\t\tloop{\n\t\t\t\tmatch Some(j){\n\t\t\t\t\tlet nj = mut j.hn\n\t\t\t\t\tmatch Some(nj){\n"
        "\t\t\t\t\t\tlet njk = mut nj.key\n\t\t\t\t\t\tif njk == evk{\n\t\t\t\t\t\t\tj.hn = enx\n\t\t\t\t\t\t\tbreak\n\t\t\t\t\t\t}\n\t\t\t\t\t}\n\t\t\t\t\telse{\n\t\t\t\t\t\tbreak\n\t\t\t\t\t}\n"
        "\t\t\t\t\tj = nj\n\t\t\t\t}\n\t\t\t\telse{\n\t\t\t\t\tbreak\n\t\t\t\t}\n\t\t\t}\n\t\t}\n\t}\n}")
    create = ("let ne = mut ? new Entry{key= 0, val= 0, prv= null, nxt= null, hn= null}\nmatch size into pool{\n\tpool[size].n = ne\n}\n"
              "match size in pool{\n\tent = ref pool[size].n\n}\nsize += 1")
    insert = ("match Some(ent){\n\tent.key = k\n\tent.val = y\n" + ind(hsh("hb2", "k"), 1) + "\n\tlet old = mut nil.n\n" + ind(bget("old", "hb2"), 1) + "\n\tent.hn = old\n" +
              ind(bset("hb2", "ent"), 1) + "\n" + ind(pushfront("ent"), 1) + "\n}")
    return f"""// LRU cache benchmark (LRU_N operations): fixed capacity 262144, key space 1048576, 75% of operations on a hot half-capacity subset.
// REF variant: the recency list is a doubly linked list of heap nodes, `Entry{{key, val, prv, nxt, hn}}`, whose prv/nxt/hn are plain `ref` members of
// the same struct (self-referential; allowed for `ref` only), the hash map is chained buckets of ref heads threaded through `hn`, and the entries
// are owned by a dynarray of Holder{{n: owns}} slots. Every node access is under a `match Some` alive proof. {desc}
// get: hit -> hits+1, move to front, checksum += value + key; miss -> misses+1. put: update + move to front, or insert at the front
// evicting the least recently used entry when full (the evicted node is reused for the new key; checksum += evicted key). Elements are u64.
// Prints: hits misses checksum (identical to the index-based variants). Generated by gen_ref.py.
import "../../../stdlib/libc.caspien"
{imp}import "../../../stdlib/gt/*"

extern getenv(static imut string) static imut string
extern atol(static imut string) mut u64

struct Entry{{@pub{{
	key: mut u64
	val: mut u64
	prv: ref mut Entry
	nxt: ref mut Entry
	hn: ref mut Entry
}}}}
struct Holder{{@pub{{ n: owns mut Entry }}}}
struct QRef{{@pub{{ n: ref mut Entry }}}}

func main() void{{
	?catch(e){{ return }}
	unsafe assume extern loop{udyn}{{
		let n = mut 0
		n = atol(getenv("LRU_N"))
		let nil = mut QRef{{n= null}}
		let pool = mut ? dyn([Holder{{n= null}}])
		pool = ? resize(pool, 262144, Holder{{n= null}})
		{setup}
		let head = mut nil.n
		let tail = mut nil.n
		let size = mut 0
		let hits = mut 0
		let misses = mut 0
		let sum = mut 0
		let x = mut 12345
		for op in 0..n{{
			x = bits_and(x * 1664525 + 1013904223, 0xFFFFFFFF)
			let a = mut x
			x = bits_and(x * 1664525 + 1013904223, 0xFFFFFFFF)
			let y = mut x
			let range = mut 131072
			if (y / 1048576) % 4 == 0{{
				range = 1048576
			}}
			assume match range != 0
			let k = mut ((a / 256) % range)
{ind(hsh("h", "k"), 3)}
			let i = mut nil.n
{ind(bget("i", "h"), 3)}
			let found = mut 0
			loop{{
				match Some(i){{
					let ik = mut i.key
					let inx = mut i.hn
					if ik == k{{
						found = 1
						break
					}}
					i = inx
				}}
				else{{
					break
				}}
			}}
			if (y / 16777216) % 4 != 0{{
				if found == 1{{
					hits += 1
					match Some(i){{
						let v = mut i.val
						sum = bits_and(sum + v + k, 0xFFFFFFFF)
{ind(mf_hit, 6)}
					}}
				}}else{{
					misses += 1
				}}
			}}else{{
				if found == 1{{
					match Some(i){{
						i.val = y
{ind(mf_put, 6)}
					}}
				}}else{{
					let ent = mut nil.n
					if size == 262144{{
{ind(evict, 6)}
					}}else{{
{ind(create, 6)}
					}}
{ind(insert, 5)}
				}}
			}}
		}}
		let fin = mut 0
		let c = mut head
		loop{{
			match Some(c){{
				let kc = mut c.key
				fin = bits_and(fin * 31 + kc, 0xFFFFFFFF)
				let nc = mut c.nxt
				c = nc
			}}
			else{{
				break
			}}
		}}
		let total = mut (bits_and(sum + fin, 0xFFFFFFFF))
		printf("%llu %llu %llu\\n", hits, misses, total)
	}}
}}
"""
for k in ("naive", "safe", "unsafe"):
    open(os.path.join(out, f"lru_ref_{k}.caspien"), "w").write(program(k))
