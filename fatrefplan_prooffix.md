# Fat refs and proof invalidation: design (research document)

Status: **research / design.** UPDATE 8 Oct 2026: step 1 of section 8 (kill analysis, defect 1.2) is IMPLEMENTED (`ASTGenerator/.../ProofKills.java`, tests `ref_free_*_error_test`, `ref_proof_kill_test`); owner decisions: no `@no_destroy` (stdlib helpers with `unsafe` end proofs like anything else), `unsafe assume{` blocks are not kills; finding: a `ref` made from an `owns some` owner is `ref some`, so no `match Some` was needed at all in probe 1.2, and the implementation also covers `ref some` locals and (since 8 Oct, later) `ref some` parameters/`self`, with type-based precision (not struct members). Fat refs and ids (defect 1.1, steps 2-3) are NOT implemented; owner is weighing a thin 8-byte `ref` = unique 64-bit id resolved through the ghost table against the fat `{addr, id}` form. The text below is the original design. Written 7 Oct 2026 from a review of the repository
at `b7eaa4c` plus two probe programs compiled and run against it. Nothing in the compiler, stdlib or tests was changed.

Two defects in the `ref` model, and the design that closes them:

1. **Address reuse.** A `ref` is a bare address. `match Some` asks the ghost table whether that address is registered, so a freed
   address that `malloc` hands out again looks alive and the ref silently denotes a different object.
2. **Free inside a `match Some` body.** The liveness check runs once, at the top of the body. Nothing invalidates the proof
   afterwards, so a body that frees its own target still dereferences it.

Fixes: (A) fat refs `{addr, id}` with a unique id per registration; (B) a compile-time kill analysis that ends a `match Some`
proof at any event that may destroy an allocation.

## 1. The defects, reproduced

Linux target (`target linux`, `default: sysv_x64`), otherwise the shipped `toolchain.config`. Programs import the usual
`gt_init`, `gt_register`, `gt_alive_check`, `gt_destruct`, `gt_moved`, `libc`.

### 1.1 Address reuse

```
struct Node{@pub{ value: mut u64 }}

func probe() void{
	?catch(e){ return }
	let:<ref mut Node> stale = null
	for i in 0..1{
		let a = mut ? new Node{value= 111}
		stale = ref a                       // a dropped at end of the loop body
	}
	let before = mut 0
	match Some(stale){ before = stale.value }
	let b = mut ? new Node{value= 222}      // same size class: malloc returns the freed address
	let after = mut 0
	match Some(stale){ after = stale.value }
}
```

Output:

```
stale ref read before reuse: 0     (skipped as dead)
stale ref read after  reuse: 222   (the ref now sees the new node)
```

Same-type reuse is the common case: glibc returns the most recently freed block of a size class. The program is not
deterministic with respect to the source; it depends on allocator state.

Type confusion: a stale `ref Pair{lo, hi}` (24 bytes) whose address was reused by a `Small{x}` (16 bytes) passed
`match Some`, and a write and read of `hi` through it worked (printed 99). Both sizes fall in the same 32-byte malloc chunk,
so the write landed in chunk slack and nothing visibly broke. It is still outside the new object. Reuse across clearly different
sizes (large blocks that get split) should give real out-of-bounds writes; **not tested**. Under valgrind the repro does not
show, because valgrind's allocator does not reuse freed blocks immediately.

### 1.2 Free inside the match body

```
func consume(n: owns mut Node) void{ }

let a = mut ? new Node{value= 111}
let r = mut ref a
match Some(r){
	consume(a)              // a's Node is freed here
	seen = r.value          // compiles with no error
}
```

Compiles cleanly. Native run printed `8806774524152050239` (freed memory). Under valgrind:
`Invalid read of size 8 ... Address is 8 bytes inside a block of size 16 free'd`. No address reuse, no threads. Tested on one
shape only (an owner consumed by a call); other shapes (shrinking `resize`, overwriting an owns slot) were not run.

The README's "bounds proofs are invalidated by writes to the index" has no counterpart for liveness proofs.

## 2. Invariant

Every dereference of a ref in safe code touches the allocation the ref was created from, and that allocation is live.

- **L1 identity:** a successful check implies the designated allocation is live now. Mechanism: fat ref + id (section 3).
- **L2 persistence:** no destroy between the check and the dereference on this thread. Mechanism: kill analysis (section 4).
- **L3 isolation:** no other thread can destroy it in that window. Mechanism: sharing rules (section 5).

A runtime re-probe at every access is not used: a failed re-probe in the middle of a body has no failure path in safe code. The
compile-time rule forces the user to write the `else` branch.

## 3. Fat refs

Representation: `ref` = `{addr: u64, id: u64}`, 16 bytes, two words (not a struct type). Null = `{0, 0}`.

Ghost table entry = `{addr, id}`. `gt_next_id` is a u64 incremented under the table lock; id 0 is never issued.

```
gt_register(p)   -> id      // ++gt_next_id, insert (p, id)
gt_id_of(p)      -> id      // one probe, 0 if not registered
gt_check(p, id)  -> bool    // one probe: entry.addr == p && entry.id == id
gt_moved(old) + gt_register(new)    // moved block: new address, new id
owner move                  // no table operation, id unchanged
```

- `ref x` (x an owner): `{x, gt_id_of(x)}`. Ref copy: copy both words.
- `match Some(r)`: `gt_check(r.addr, r.id)`.
- Uniqueness: a given (addr, id) pair is issued once. 2^64 is about 1.8e19 registrations; at an (optimistic) 1e9 per second
  that is about 585 years. A 32-bit id would wrap in about 43 seconds at 1e8 per second, so the id stays 64-bit.

Id location, working choice: **in the table entry**. It works for every registered allocation and keeps one source of truth.
Cost: a probe at ref creation. Alternative: an `___id` word after `___type` in the object. Creation is one load and shares the
object's cache line, but it costs 8 bytes per object (a 24-byte `Node` becomes 32, a 32-byte malloc chunk growing to 48, about
+50% for small nodes) and covers only structs that have `___type`. **Open decision** (depends on section 7, question 1).

ABI consequences: refs are two words in struct layout, call arguments, and method receivers (`_self: ref some self`).
`@async` takes one register-sized parameter, so fat refs cannot cross `par` without a deliberate rule (section 5).

## 4. Proof invalidation

A proof P(r) is generated at `match Some(r)` and is valid in the body. A dereference of `r` where P(r) is not available is a
compile error that names the chain (`f -> g -> resize`).

### Kill list

Every kill ends **all** open proofs, not only those related to the destroyed object.

1. Scope end, or unwind, that drops an owner. Only where an owning local is not moved-from on every path (move tracking
   already knows this). On an unwind this is on the catch edge.
2. Any `resize` on a safe dynarray, on both edges. Whether it shrinks or `realloc` moves the block is not statically known;
   a shrink frees owning elements (`GT_DESTRUCT_TAIL`), a move frees the old block, and a failed `resize` destroys the old
   array (per `CLAUDE.md`).
3. Assignment over any owns slot or owns-bearing member, including through a pointer.
4. `unsafe`, `par`, `await`. Extern, `call(fp)` and asm all require `unsafe`, so they are covered.
5. `new` or `dyn([..])` that moves owners in: kill on the **failure edge only** (the failure path frees the moved-in
   owners). Proofs survive on the success edge.
6. A call to any function containing 1-5, transitively. A callee's failure-edge kill stays on the failure edge in the caller
   (a call to a throwing function has a normal edge and a catch edge).

Not kills: successful `new`/`dyn`/`clone`, any read, a write to a non-owns field, an owner move, a call to a function with none
of 1-5 inside. `clone` is not on the list: per `CLAUDE.md` it deep-copies and on failure destroys only its own partial copies
(fresh objects). **To verify against the clone glue before relying on it.**

### Dataflow

- Must-analysis. At joins and loop heads take the intersection of incoming edges. A catch handler ending in `continue` loses the
  proofs at the loop head.
- Reassigning `r` kills only P(r).
- D(f) = "may destroy": true if f contains 1-5 or calls a g with D(g). The call graph is acyclic (recursion only as bounded
  tail loops), so one bottom-up pass computes it.
- `ref some` parameters hold their proof at entry and lose it by the same rules inside the callee. A caller passing one
  needs P(arg) available at the call. Returned refs and ref members have no proof.
- Run on what the code expands to, not on the `unsafe` keyword: compiler-synthesized `unsafe` blocks (drop and clone glue) are
  exempt from the tag check.

### Consequences

- `unsafe extern{ printf(..) }` inside a match body kills proofs.
- Any stdlib function containing `unsafe` (`DynamicArray.get`/`set`, `pushBack`, ...) kills proofs at its call sites, even
  though it destroys nothing. **Open decision:** accept this (strict, re-match after such calls), or add an audited
  `@no_destroy` for named stdlib functions and externs, listed by `--audit` and checked by the shim test. The latter is a
  hand-vouched hole.
- Item 3 and the `ref some` rule will reject some correct code; the cost is a re-match.

## 5. Threads (L3)

Proposed: no ref-bearing type as a `par`/`await` parameter, and no ref members in `@lock` structs or shared statics. With
those, objects a thread holds a proof for are reachable only from that thread. **Current checker behaviour is not verified.**

## 6. Facts established about refs and dynarrays

- `let r = mut ref v` where `v` is a safe dynarray compiles and runs: a ref to the whole backing block is possible.
- `ref v[i]` on `u64` or inline-struct elements is rejected: `'ref' can only be constructed from an existing 'owns'-typed
  value`.
- `ref v[i].n`, where `n` is an `owns` pointer, is allowed (a ref to a separate node allocation).

## 7. Unverified, to settle first (step 0)

The compiler source was not read for this document; only the README, `CLAUDE.md` files, the ghost-table stdlib and tests.

1. Every expression that produces a ref and everything it can point at (heap start only? interior pointers? non-struct
   allocations?). Decides the id location in section 3.
2. Every operation that can destroy: derive the list from every emitter of `GT_DESTRUCT`, `GT_DESTRUCT_TAIL`, `GT_DROP_ADDR`
   and `CALL __drop_T`, not from this document.
3. Where heap can be shared between threads, and whether refs can cross `par`/`await` or sit in `@lock` structs today.
4. Whether overwriting a non-null owns slot drops the old block (assumed yes; the leak checker suggests it).
5. `clone` failure behaviour (section 4).

## 8. Order of work

1. Kill analysis and D(f). Closes defect 1.2 with no runtime change.
2. Fat refs and ids. Closes defect 1.1.
3. Thread rules.
4. Later, each with its own proof obligation: precision refinement (a function that destroys only objects it allocated itself
   cannot hit a pre-existing proof target, because proof targets pre-date the call; needs provenance tracking of owner
   slots), and a slab ghost table with a per-slot generation instead of a liveness bit. The planned slab design (a bit per slot,
   LIFO free lists) would make reuse faster and more predictable, so it must not ship without generations.

## 9. Tests

- Both probes become fixtures: the body case must be a compile error; the reuse case must print "skipped".
- Run every test under `tests/alloc_shim.c` recording each free; assert every function observed freeing has D = true.
- Generate random ref/owner/container/call programs. Anything the compiler accepts must run clean under valgrind with a
  non-reusing, poisoning allocator.
- Mutation-test the generator: remove one operation from the destroy list; it must find an accepted use-after-free program.

## 10. Not covered

Safe code over a trusted core. Not covered: stdlib `unsafe`, `assume match Some`, any `@no_destroy` annotation (hand-audited,
checked only by the shim test). Tested, not proved, as in README section 1.5.
