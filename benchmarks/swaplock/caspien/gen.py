#!/usr/bin/env python3
"""Writes swaplock_naive.caspien and swaplock_opt.caspien (32 `par` calls in main, one per thread, generated)."""
import os
THREADS = 32
HERE = os.path.dirname(os.path.abspath(__file__))

HEAD = """// swaplock: 32 OS threads all update one shared struct (count, sum) under one swap lock, K times each (K comes from SWAPLOCK_K).
// Each update: count += 1; sum += id + (i % 7). The result does not depend on the order of the updates.
// The lock is a `swap` field (an atomic exchange): `match @lock shared` runs OPEN when it took the lock and CLOSED when someone holds it.
%s
import "../../../stdlib/libc.caspien"
import "../../../stdlib/gt_init.caspien"
import "../../../stdlib/gt_register.caspien"
import "../../../stdlib/gt_alive_check.caspien"
import "../../../stdlib/gt_destruct.caspien"
import "../../../stdlib/gt_moved.caspien"
import "../../../stdlib/par_call.caspien"
import "../../../stdlib/await_call.caspien"
extern getenv(static imut string) static imut string
extern atol(static imut string) mut u64

enum Gate{ OPEN, CLOSED }

@lock(match self.gate : OPEN)
struct Shared{@pub{
	swap gate: atomic mut Gate
	count: mut u64
	sum: mut u64
}}

let static shared = mut Shared{gate= Gate.OPEN, count= mut 0, sum= mut 0}
let static k = mut atomic 0
"""

POLICY = """
// A backoff policy replaces the CLOSED body of every `match @lock Shared`: spin a little, then give up the time slice.
impl default match @lock Shared{
	CLOSED:(tries:imut u64, limit:imut u64)=>{
		if tries % 16 == 0{ yield }
		continue
	}
}
"""

WORKER = """
@async
func worker(id: mut u64) void{
	let rounds = mut k
	for i in 0..rounds{
		match @lock shared{
			OPEN:{
				shared.count += 1
				shared.sum += id + (i %% 7)
			}
			CLOSED:%s
		}
	}
}

func main() void{
	?catch(e){
		unsafe extern{ printf("FAIL %%s\\n", e.msg) }
		return
	}
	let rounds = mut 0
	unsafe extern{ rounds = atol(getenv("SWAPLOCK_K")) }
	k = rounds
""" 

def build(desc, closed, policy):
    s = HEAD.replace("%s", "// " + desc) + (POLICY if policy else "") + WORKER % closed
    for j in range(THREADS):
        s += "\t? par worker(mut %d)\n" % j
    s += """	let want = mut (%d * rounds)
	for w in 0..100000000000{
		let c = mut 0
		let s = mut 0
		match @lock shared{
			OPEN:{
				c = shared.count
				s = shared.sum
			}
			CLOSED:%s
		}
		if c == want{
			unsafe extern{ printf("count=%%llu sum=%%llu\\n", c, s) }
			break
		}
		yield
	}
}
""" % (THREADS, closed)
    return s

for name, desc, closed, policy in (("swaplock_naive", "naive: a CLOSED lock is retried at once (pure spinning)", "{ continue }", False),
                                   ("swaplock_opt", "optimized: a backoff policy yields the time slice every 16th failed attempt", "default(16)", True)):
    open(os.path.join(HERE, name + ".caspien"), "w").write(build(desc, closed, policy))
