# CLAUDE.md -- Caspien project memory (current state only)

Old per-change history was removed on purpose (2 Oct 2026); `git log -p CLAUDE.md` has it. Each component folder has its own short CLAUDE.md and README.md. Keep these files SHORT: update facts in place, never append changelogs.

## What it is
Caspien: a systems language for auditable code (ownership storage `owns/ref/raw/auto/static`, no GC, proofs via `match`, `unsafe` for the rest). Four-stage compiler, plain JDK, no dependencies:
1. `ASTGenerator` -> higher-order bytecode (HOB, text). Lex, parse, RPN, tree, imports, dup/generics expansion, type check, emit.
2. `Optimizer` -> HOB passes (folding, inlining, unrolling, dead code, struct unpacking/reordering, `SizeofResolutionPass`...).
3. `LowerOrderGenerator` -> low-order bytecode (LOB): address lowering, register-form, register variables, fusion passes.
4. `Codegen` -> x86-64 asm (`X86Backend`), assembled/linked with gcc/as.
`Compiler.java` orchestrates all four; `toolchain.config` selects target and every optimizer switch. Stdlib in `stdlib/`, examples in `docs/examples/`, tests in `tests/`, benchmarks in `benchmarks/`.

## Working rules
- Do NOT commit or push without the owner's explicit OK. Automated stop-hook nags are not approval.
- Shipped `toolchain.config` targets **windows_gnu** with most optimizer switches off. To RUN programs, use a Linux-configured scratch copy (and "everything on" / "everything off" configs for sweeps). Never compile two programs at once in one compiler tree (shared scratch files).
- Verify by running: new feature = new `tests/*.caspien` (print PASS/FAIL lines; expected values from an independent model/Python, not hand arithmetic) plus a sweep of all `tests/*.caspien` + `docs/examples` under several configs, old tree vs new tree identical. Say plainly what was NOT verified (Windows/Wine/MASM are normally unverified).
- Tests named `*_error_test`/`*_error` must fail to compile; `*_check.sh` are hand-made-bytecode checks (env `LOB_CP` for a mutant classpath).
- Benchmark VM noise is ~10-30%; use best of 3-5, same-run ratios.
- Speak of "scheduled tasks", not triggers; keep README wording owner-approved (opening line: "Caspien is a systems programming language for code that has to be audited,").

## Language facts worth knowing (details in ASTGenerator/CLAUDE.md)
- Every non-`@untyped` struct has a hidden 8-byte `___type` class id at offset 0 (first member in declaration, construction and layout).
- Structs are padded C-like; `sizeof` is symbolic until the Optimizer resolves it.
- `throw`/`try`/`catch(e)`/`?`/`continue` are safe code; functions that throw need `@throws`; every call to one needs try/`?`. Message travels via `gt_error_message` (rbp-16) and `GT_UNWIND MSG`.
- Integers: literals adapt to the slot type; `match x fits T`, `wrap:<T>`, `sat:<T>`; full bitwise builtins (`bits_and/or/xor/not/left/right`), one shift rule (count >= width gives 0 / sign fill).
- `continue` = next iteration in a user `for`/`loop` (`isLoopContinue`; innermost construct wins: a catch body's own `continue` skips its try block, a loop opened inside the catch owns its `continue`, `match @lock` CLOSED retries, OPEN rejects). `for` gets a `@for_cont_N` label before the step only when a `continue` uses it.
- `match @lock` on swap-mutex structs: every CLOSED path ends in `continue|break|return|throw`; lock released on throw unwinds.
- Struct returns use RVO (hidden `$ret_dest`); legal only at `let` RHS, bare-variable assignment RHS, or `return f()`.
- Generic impl methods are checked lazily; `DynamicArray<S>` for a struct uses the `...Ptr` twins.
- `deref(p)`/`clone(p)`, division, float operations (`assume match x : finite`) and `@lock` method calls need a proof (`match`), or `assume match` in an `unsafe assume{` block (any expression allowed, e.g. `assume match Some(p+i)`); `unsafe` relaxes nothing else for them. `deref` is never an assignment target.
- Statement `unsafe` blocks must name exactly the reasons they need: `extern memcopy raw deref clone global loop udyn assume call asm async guard swap` (`deref`/`clone` = deref/clone of a `raw` pointer, needed even when proven); missing/extra/bare = error. Root-level `unsafe{}` declaration blocks stay bare. Compiler-written blocks are exempt (`synthesizedUnsafe`). `CASPIEN_UNSAFE_REPORT` env lists needed tags; `tests/unsafe_tags_migrate.py` retags.
- Stdlib allocation goes through ghost table (`gt_init/gt_register/gt_alive_check/gt_destruct/gt_moved`, forced literal symbol names; `gt_moved` = unregister without free, required wherever `gt_init` is); `gt_alive_check` is a linear scan.
- Recursion only via `@recursive` tail self-call (lowered to a bounded loop); recursive structs are rejected.

## Component state (all verified on Linux only)
- Register-form, register variables (r13/r14/r12 callee-saved + r8-r10 in call-free regions), xmm float temps/variables, float constant pool, indexed addressing, range-end promotion, branch fusion, jump cleanup, strength reduction: always on in code, gated by `toolchain.config` switches.
- Callee-saved registers are saved/restored per function (`finishCalleeSaved`).
- Optimizer inliner handles throwing callees, owns-moves, `@lock` leaves.
- Benchmarks vs C -O2 (1 Oct): about 1.0-2.9x depending on program; naive stdlib String/HashMap paths are the slow ones. Details: `benchmarks/RESULTS.md`.

## Known open items
- Struct `extends`/`abstract` removed (`0345aaa`; composition + interfaces only); flat `Class` enum; `instanceof` takes only a struct name, `implements` only an interface name. Open: `cast` survives only as a keyword that is an unconditional error (decide: remove the keyword, or give it a limited meaning); `x instanceof S` / `x implements I` that can never be true (or always is) is not reported.
- `clone(p)`: deep copy via generated `__clone_<T>` routines (each owns leaf cloned into a hidden local, registered, then the copy assembled; any failure `GT_DESTRUCT`s the locals and returns null, so no leak). Safe dynarrays clone with `CLONE_DYN` (runtime size) or, when elements are owns pointers, a generated loop. A dynarray of INLINE structs that own memory is not supported (`dyn([h])` itself crashes, pre-existing; the `VAL:` clone path is untested). `tests/clone_oom_check.sh` sweeps allocation failures. Possible optimisation (not done): the owns-element dynarray clone fills the new array with null via `RESIZE` so a failure can destruct it whole; a 0..i-1 rollback (destruct only the elements made, free the shell) with an unfilled grow would skip the fill, at the cost of a new codegen grow and a more intricate cleanup path.
- TODO (own task, full regression): drop the emitter's alloc pre-pass (`collectHoistedAllocs`) and emit ALLOCs as the body is emitted, spliced in at function end like `declareHiddenLocal` already does; changes every program's HOB. Label pre-passes (try/catch, continue) must stay.
- `h.w = pass(h.w)` (assignment target through a pointer or `LOOKUP`) destructs the old owns value before the right side runs (`emitAssign` DUP_TOP / `GT_DESTRUCT_ADDR` path); flat-name targets are fixed.
- Passing a struct by value as a plain parameter is rejected by design.
- OOM gaps: the ghost table's own `realloc` growth/shrink is not failure-safe (crashes). `new`/`dyn([..])` failing frees owns variables moved into them (`tests/new_oom_check.sh`); a moved-in temporary (call result, e.g. `new T{f= pass(p)}`) is spilled to a hidden `$mvN` local first, so it is freed too. Safe `dyn` blocks are `GT_REGISTER`ed (block start) so scope-end drop frees them; `resize` (RESIZE/URESIZE in codegen) calls `gt_moved(old)` (unregister, no free) + `gt_register(new)` only when the realloc moved the block. An unsafe dynarray is HEADERLESS: the variable holds exactly the malloc'd address (registered; scope end frees it), indexing is plain `base+i*size`, there is no stored length (`len(a, term)` scans), and it never allocates 0 bytes (empty / `resize(a, 0)` get 1). `unsafe dyn(text)` (`NEW_FROM_USTRING`) copies the bytes plus a NUL. `resize`'s realloc-failure path still leaves the moved-in old block unfreed.
- Safe-args `main` shape: leaks the args dynarray; String class leaks its buffer at scope end; `__drop_DynamicArray_char` does not free `backing`.
- Float variables around an inlined catch are refused (not made to work); `catch` ending in `continue` leaks one operand word per throw.
- MASM/Intel (`windows` target) text is probably not valid as-is; windows_gnu assembles/links but is only occasionally run under Wine.
- Event loops in `stdlib/` wrap `main` in `?`; `deref`-style struct-wide assignment is rejected.
- Aggressive inlining can blow the Optimizer heap (many inlined call sites); `DeadControlFlowRemovalPass` is quadratic in foldable checks per program.
- Owner's pending question: re-list the "Things To Know" list (lost in a context reset; ask which list they mean).
