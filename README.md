# Caspien

Caspien is a systems language for code that has to be *audited*, not just written. Its type checker
refuses programs it cannot show to terminate, to respect ownership, and to avoid runtime faults such as
out-of-bounds indexing, null dereference, division by zero and unproven floating point operations.
It compiles to native x86-64 code through a four-stage compiler written in Java.

```rust
import "stdlib/libc.caspien"
import "stdlib/gt_init.caspien"
import "stdlib/gt_destruct.caspien"

func main() void{
	unsafe{
		printf("Hello World!\n")
	}
	return
}
```

> **Status.** Caspien is a research language and a working compiler, not a finished product. Section 1.4
> says exactly which of the guarantees below are enforced today and which are still aspirations.

**Contents**

1. [The language](#1-the-language): philosophy, a tour with examples, and an honest status report
2. [Using the compiler](#2-using-the-compiler): building, running, targets, and every optimisation switch
3. [How the compiler works](#3-how-the-compiler-works)
4. [Performance](#4-performance): latest benchmark results and an honest comparison with other languages

---

## 1. The language

### 1.1 Philosophy

The idea behind Caspien is that one might not want to *write* in it, but might want one's *codebase* to
be in it. Code that is accepted by the compiler carries properties that other languages ask you to take
on trust: it terminates, it does not use memory it does not own, and it has no hidden runtime failure
paths. The language is verbose and explicit on purpose, because every explicit annotation is something a
reviewer, an auditor or another tool can check.

The model behind this is the **run-to-completion total slice**. A program is a flat, single event loop.
Each iteration of the loop handles one event, which is a slice of time, and the handler for that event is a
*total* function: it is guaranteed to return a result for every input, with no non-termination, no
exception and no undefined behaviour, absent a hardware fault. The loop itself is the only unbounded
construct, and it lives in a small, auditable place. (The project's working name for this idea was
*Time-Slice TIDAG*. "Total slice" says the same thing in terms that match the literature.)

Four properties make a handler total:

1. **Bounded loops over an acyclic call graph.** The call graph has no cycles: direct and mutual
   recursion are compile errors. The only recursion allowed is a tail call on a strictly shrinking
   range, which the compiler rewrites into a bounded `for` loop. Every `for` loop runs over a range that
   is fixed when the loop starts, and its counter cannot be assigned. Unbounded `loop{}` needs `unsafe`.
   This is the discipline of Meyer and Ritchie's LOOP language (1967): safe Caspien code corresponds to
   primitive recursive computation, which is a strict subset of what a Turing machine computes. A
   handler can run for a very long time, but it cannot fail to stop.
2. **Memory safety by ownership.** Every heap value has exactly one `owns` pointer. Moving it invalidates
   the old name at compile time. Borrowed pointers (`ref`) can be null or dangling, so their members are
   reachable only inside `match Some(...)`, which checks that the target is alive. Array and dynarray
   indexes must be proven in bounds before use.
3. **No runtime exceptions.** Division requires a proof that the divisor is not zero. Float arithmetic
   requires a proof that the operand is finite. Narrowing an integer requires either a proof that it fits
   or an explicit `wrap` or `sat`. Integer arithmetic wraps and never traps. The only non-local control
   flow is `throw`, which is declared with `@throws`, must be handled with `?catch` or `try`/`catch`, and
   whose handler must end in `return`, `throw` or `continue`.
4. **Explicit escape.** Everything the checker cannot prove goes in an `unsafe` block, which is easy to
   find, count and review. All C calls, `loop{}` and raw pointer dereference are in that category.

Two limits are worth stating plainly, because they separate Caspien from the claims it is sometimes
confused with:

- **Termination is not bounded time.** A nested bounded loop with large bounds can run for years. A
  scheduler that must meet deadlines also needs a worst-case execution time per slice. Caspien proves the
  first guarantee and does not yet compute the second. The shape of the language does make it tractable:
  loop bounds are ordinary range values, and an acyclic call graph gives a static bound on stack depth.
- **"Total" is relative to the primitives.** The guarantee is conditional on the escape hatches. A C
  function called from `unsafe`, or an `unsafe loop{}`, can do anything.

The idea sits in a family of established work: total functional programming (Turner, 2004), the LOOP
language (Meyer and Ritchie, 1967), synchronous languages such as Esterel (Berry and Gonthier, 1992) and
Lustre (Halbwachs, Caspi, Raymond and Pilaud, 1991), WCET analysis (Wilhelm et al., 2008), and
run-to-completion cooperative kernels. Caspien's contribution is to combine totality with ownership-based
memory safety in a low-level language with a conventional imperative surface.

### 1.2 A tour of the language

The examples below are excerpts from programs in [`docs/examples/`](docs/examples). Each program is
complete, compiles, and prints the output shown in its header comment. The code blocks use the `rust`
syntax hint only because GitHub has no Caspien highlighter.

Every program that uses `new`, `owns` or `ref` imports the four `gt_*` files from `stdlib/`. They
implement the runtime registry that tracks which heap values are alive, and they are ordinary Caspien
source, not compiler magic.

#### Values, mutability and types

Every value is `mut` or `imut`, and you write it. There are no implicit conversions between integer
types, so a literal takes the type of the slot it lands in, and a variable never silently changes width.

```rust
let acct = mut ? new Account{id= 1, balance= 100}
acct:deposit(50)

let wide = mut 200
match wide fits u8{                  // a proof that the narrowing is safe
	let narrow = mut wide as u8
}
let big = mut 300
let wrapped = mut wrap:<u8>(big)     // explicit: keep the low bits (here 44)
```

Integers are `u8` to `u64` and `s8` to `s64`; floats are `f32` and `f64`; there are also `bool`, `char`,
fixed arrays (`u64[5]`), ranges (`0..10`) and strings. Hex, binary and underscore literals work
(`0xFF_FF`, `0b1010`), and the bitwise builtins are `bits_and`, `bits_or`, `bits_xor`, `bits_not`,
`bits_left` and `bits_right`, with one fully defined shift rule for every width.

#### Structs, methods, enums and interfaces

Methods live in `impl` blocks. The receiver is an explicit `_self` parameter, and a `match` on an enum
must name every case.

```rust
enum Shape{ CIRCLE, SQUARE, TRIANGLE }

func sides(s: imut Shape) mut u64{
	match s{
		CIRCLE:{ return 0 }
		SQUARE:{ return 4 }
		TRIANGLE:{ return 3 }
	}
	return 0
}

interface Shape{
	area(_self: ref some imut self) imut u64;
}

impl Shape for Rect{
	@pub
	@realizes
	func area(_self: ref some imut self) imut u64{
		return _self.w * _self.h
	}
}
```

Generics use `:<T>` at the use site (`Pair:<u64>{first= 3, second= 4}`, `new DynamicArray:<u64>()`),
and a generic method that is illegal for some `T` is simply unavailable for that `T` instead of being an
error at the declaration.

#### Proofs instead of runtime checks

Where another language would insert a check that can fail, Caspien asks you to prove the condition, and
the proof is a `match` whose body is only reachable when it holds.

```rust
func divide(a: mut u64, b: mut u64) mut u64{
	match b != 0{
		return a / b            // without the match, this line is a compile error
	}
	return 0
}

func scale(x: mut f32) mut f32{
	match x{
		finite:{ return x * 2.0 }
		infinite:{ return 0.0 }
		nan:{ return 0.0 }
	}
	return 0.0
}

for match i in values{          // only indexes proven to be inside `values`
	total += values[i]
}

match i into squares{           // `in` proves reading, `into` proves writing
	squares[i] = i * i
}
```

These are the error messages for the most common attempts to skip a proof:

```
'/' requires the divisor to be proven nonzero first (`match z != 0{...}` for an unsigned type ...), or 'unsafe' code
'[]' is not permitted here -- the index must be a literal integer, provably within bounds
'/' on an unproven 'f32' isn't permitted -- prove it first with 'match a{ finite:{...} ... }'
member access on a pointer-typed struct value is not permitted -- the type system can't yet confirm it isn't null
```

#### Ownership and errors

`owns` is the single owner of a heap value, `ref` borrows it, `auto` points at a stack variable, and
`raw` is a C-style pointer that needs `unsafe` to dereference. A pointer that might be absent is
unwrapped with `match Some(...)`. Allocation can fail, so `new` goes through `?`, which forwards the
error, and the function is marked `@throws`.

```rust
@throws
func makeNode(v: mut u64) owns some mut Node{
	?catch(e){ throw e }                 // declared once, applies to every `?` below it
	return ?new Node{value= v}
}

func readNode(n: ref mut Node) mut u64{
	let r = mut 0
	match Some(n){                       // the block runs only if the target is alive
		r = n.value
	}
	return r
}

func parseOr(digit: mut u64, fallback: mut u64) mut u64{
	?catch(e){ return fallback }         // a catch must end in return, throw or continue
	return ?parse(digit)
}
```

Using a value after moving it is a compile error: `use of 'b' after its ownership was moved`.

#### Termination

```rust
// The range is fixed when the loop starts: this runs exactly 5 times.
let n = mut 5
for i in 0..n{
	n += 100
}

// Recursion exists only as a tail call on a smaller range. The compiler lowers it to a `for` loop.
@recursive
func sumTo(acc: mut u64, r: imut range) mut u64{
	match r is base{ return acc }
	let lo = mut (r.start + 1)
	let hi = mut r.end
	let r2 = imut (lo..hi)
	match r2 within r{ return sumTo(acc + r.start, r2) }
	else{ return acc }
}
```

Everything else is rejected:

```
'f' calls itself -- recursion is not allowed unless the function is decorated '@recursive'
indirect recursion detected: 'b' calls 'a', which (directly or transitively) calls back to 'b'
'loop' can only be used from within 'unsafe' code
'i' is immutable for the duration of this 'for'/'for match' loop
```

#### Dynamic arrays, the standard library, concurrency and locks

`dyn:<T>([])` makes a safe dynamic array, and the standard library has `DynamicArray<T>`, `HashMap<T>`,
`String`, SHA-256 and FNV-1a hashing, process spawning and libc bindings. Async functions run on their own
OS thread when called with `await` or `par`. Locks are enum-valued atomic fields, and `match @lock` is the
only way to reach the data they protect.

```rust
let squares = mut ? dyn:<u64>([])
squares = ? resize(squares, 8, zero)

@async
func triple(x: mut u64) mut u64{ return x * 3 }
let answer = mut await triple(mut 14)

struct Counter{@pub{
	swap lockState: atomic mut State
	n: mut u64
}}

match @lock c{
	OPEN:{ c.n += 1 }
	CLOSED:{ continue }       // retry. `break` gives up, `return` and `throw` leave.
}
```

#### Program entry: `main` arguments and event loops

A plain program has one `main`. It takes no arguments and returns `void`, `bool` or `s32`. It can instead
take the command-line arguments in one of two shapes.

```rust
// C shape: the raw argc/argv pair. The pointers are raw, so reading them needs `unsafe`.
func main(argc: imut s32, argv: raw mut raw mut char) void{ /* ... */ }

// Safe shape: the arguments copied into owned dynarrays of dynarrays of chars.
// Needs `import "stdlib/make_safe_args.caspien"` (the function marked `@make_safe_args`).
func main(args: owns some mut dynarray(imut dynarray(imut char))) void{
	let n = mut len(args)             // how many arguments, including the program name
	match 1 in args{                  // an index into a dynarray needs a proof
		let first = mut args[1]
		/* `first` is a dynarray of char */
	}
}
```

The safe shape is the idiomatic one: `make_safe_args` copies every argument, so your program never holds a
pointer into the C runtime's memory. (`docs/examples/07_main_c_args.caspien` and
`08_main_safe_args.caspien` are runnable versions.)

A program that might never terminate is written as an **event loop**. It can still end if the loop is broken or an error is thrown. Three decorators work together, and the
compiler checks that each appears exactly once. `main` is marked `@with_tick` and builds the initial state,
a heap struct. `@tick` marks a function that takes the state and returns it. `@event_loop` marks the real
entry point, which calls `main` once and then calls `tick` until the loop ends. It is the only function allowed a bare
`loop{}` outside `unsafe`.

```rust
struct World{@pub{
	ticks: mut u64
}}

@event_loop
func start() void{
	?catch(e){ return }
	let state = mut ? main()
	loop{
		state = tick(state)
	}
}

@with_tick
@throws
func main() owns some mut World{
	?catch(e){ throw e }
	return ? new World{ticks= 0}
}

@tick
func tick(w: owns some mut World) owns some mut World{
	w.ticks += 1
	return w
}
```

Each call to `tick` is an ordinary terminating function, so it is a total slice in the sense of section 1.1;
the only unbounded construct is the loop that schedules them. `stdlib/event_loop.caspien` is a ready-made
`@event_loop` that wraps the call to `main` in `?` and simply returns if `main` throws (so `main` must be
`@throws`, as a `main` that builds heap state has to be). If `main` takes arguments, the `@event_loop` function receives the raw `argc`
and `argv` and passes them (or wraps them, in `stdlib/event_loop_safe_args.caspien`) through to `main`.
The runnable version is `docs/examples/09_event_loop.caspien`.

### 1.3 Reading the code

The syntax is deliberately regular. Blocks use braces, statements need no semicolons, `let` introduces a
binding, `:<T>` supplies a type argument, and a `@decorator` on the line above a declaration changes how
it is checked (`@throws`, `@pub`, `@async`, `@recursive`, `@lock`, `@realizes`). Comments are `//` and
`/** ... */`. A few things that surprise newcomers:

- A name used as a value must be bound with `mut` or `imut` before it is stored, and a method is called with
  a colon (`acct:deposit(50)`), which is sugar for passing the receiver explicitly (`acct.deposit(acct, 50)`).
- Struct values cannot be passed by value as parameters. Pass a pointer, or return the struct, which the
  compiler implements without a copy.
- A dynarray's length is only known at run time, so every index into it, literal or not, needs a proof: `match 1 in a{ a[1] }` (or `into` to write). Fixed arrays with a literal index need none.
- `main` takes no arguments by default and must return `void`, `bool` or `s32`; see the entry-point section above for arguments and event loops.

### 1.4 Where the project stands against the ideal

The ideal is a language in which a type-checked program is *provably* total, memory safe and free of
runtime exceptions. Those guarantees are made about **safe code**. `unsafe` is the explicit escape hatch,
and inside it the compiler checks types but promises nothing else. So the state is reported in two layers:
what the checker enforces in safe code, then what `unsafe` gives up.

#### Safe code

| Property | Enforced today | Open (still safe code) |
|---|---|---|
| **Termination** | Direct and mutual recursion rejected; `@recursive` only as a tail call on a shrinking range, lowered to a bounded `for`; every `for` bound fixed at loop entry; counter immutable; no `loop{}` and no `call()` (both need `unsafe`). | `match @lock` spins until it acquires the lock, so it can wait forever under contention. `await` blocks on another thread. So safe code is not strictly total. |
| **Bounded execution time** | Termination is guaranteed (above), and every `for` is bounded by its range, so each loop is finite. Safe code has no unbounded loop and an acyclic call graph, which is what makes a static bound possible. | The compiler does not yet compute a worst-case execution time, so "terminates" is not yet "time-bounded". This needs tooling, not a language change: tighter loop ranges for precision (a runtime-valued bound is only limited by its type, so a bare worst case is useless), per-primitive costs for the trusted core, and a wait model for `match @lock` and `await`, the only places safe code waits on another thread. |
| **Memory safety** | Single ownership with compile-time move checking; array and dynarray indexes proven in bounds; dereferencing a pointer needs a liveness proof. | Liveness of a `ref` is checked at *run time* against a table of live allocations, so a dangling `ref` is skipped rather than rejected at compile time. |
| **No runtime exceptions** | Division, float operations, narrowing, indexing and null access all need proofs; arithmetic wraps; failures are declared (`@throws`) and handled. | Allocation failure is reported (as a thrown error), not prevented. A throw out of the `OPEN` case of `match @lock` releases the lock before unwinding, like a `return` does (`tests/lock_unwind_test.caspien` prints `PASS`). |
| **The single event loop** | `@with_tick` / `@tick` / `@event_loop` give a non-terminating program (`docs/examples/09_event_loop.caspien`). | All three stdlib loops (no arguments, C arguments, safe arguments) have been run. The example is run by hand and is not in `tests/`. `par`/`await` add real threads, which is a deliberate departure from a single loop. |

#### `unsafe` code

Inside `unsafe` the guarantees above are the programmer's responsibility. What each hatch gives up:

| `unsafe` operation | What it gives up |
|---|---|
| `loop{}` | Termination: an unbounded loop. |
| `call(fp, ...)` on a function pointer | Termination and the call graph: the recursion check only follows direct calls, so recursion through a function pointer is possible. The inliner also refuses these calls. Not allowed in `@pure` functions. |
| `deref` of an unproven pointer, constructing a `raw` pointer, `memcopy` | Memory safety: no liveness or bounds proof. |
| `extern` calls | Everything: foreign code is outside the checker. |
| Reading or writing statics and globals from non-atomic code | Data-race freedom. |
| Float comparisons on unproven values | Freedom from NaN surprises. |

The standard library is built on `unsafe` code (the ghost table, `memcopy`, the `pthread_*` calls). The
guarantee is therefore "safe user code on top of a small trusted `unsafe` core", and that core is tested,
not proved. Costs inside that core are part of its contract, not of the safe-code guarantees. For example, the liveness check behind `match Some` is currently a linear scan of the ghost table under a spin lock, and `malloc` has no bound. A timing analysis would take such costs as stated inputs, as it would for any library.

#### Beyond the language

| Property | Enforced today | Open |
|---|---|---|
| **Soundness of the checker** | About 70 runtime regression programs in [`tests/`](tests), generated tests with expected values from independent Python models, and shell checks for the optimiser passes. | There is no formal proof, mechanised or otherwise. The checker is about 19,000 lines of Java, and "the compiler accepts it" is evidence, not proof. The large corpus of compile-error fixtures is kept outside this repository. |
| **Platforms** | Linux x86-64 is the tested target. | The Windows (`windows_gnu`) output assembles and links but has not been run on a real Windows machine, and the MASM/Intel backend is unverified. |

Known bugs that affect the guarantees are tracked in the `CLAUDE.md` files. One open example: reassigning
an `owns` field reached through a pointer (`h.w = pass(h.w)`) still destructs the old value before the right
side is evaluated.

---

## 2. Using the compiler

### 2.1 Requirements and build

- A JDK (the compiler is Java; it is built with plain `javac`, no build tool).
- `gcc` and `as` on the target machine. For the Windows target, mingw-w64.

The repository contains prebuilt class files for the four stages. To build the orchestrator:

```
javac Compiler.java
```

To rebuild a stage after changing it, for example the optimiser:

```
cd Optimizer && javac -d out $(find src/main/java -name '*.java')
```

### 2.2 Compile and run

```
java Compiler -i hello.caspien output/hello
./output/hello
```

`-i` is the input file; the output path may be in a folder that does not exist yet. Imports are resolved
relative to the *importing file's* directory, so keep programs next to `stdlib/` or use relative paths
like `import "../stdlib/libc.caspien"`.

| Flag | Effect |
|---|---|
| `--hob` | Stop after the front end and optimiser. The output file is the higher-order bytecode. |
| `--lob` | Stop after lowering. The output file is the low-order bytecode. |
| `--asm` | Stop after code generation. The output file is x86-64 assembly. |
| `--no-warnings` | Hide warnings (errors are always shown). |

The intermediate files of every stage are also kept under `output/.build/`, which is the easiest way to see
what the compiler did to a program.

### 2.3 Choosing a target

All settings live in one file, [`toolchain.config`](toolchain.config), which the orchestrator copies into
each stage before every run. Edit that file, not the per-stage copies.

The repository ships configured for **Windows** (`windows_gnu`, mingw-w64). To build on **Linux**, change
two lines in `toolchain.config`, because the calling convention and the target must agree:

```
    default: sysv_x64        # in ===compiler.config===, was win64
target linux                 # in ===codegen.config===, was windows_gnu
```

| Target | Output |
|---|---|
| `linux` | ELF executable, System V ABI, GNU assembler syntax |
| `windows_gnu` | PE executable, win64 ABI, GNU assembler syntax, built with mingw-w64 |
| `windows` | MASM/Intel syntax. The compiler can produce the `.s` file but the orchestrator cannot assemble it, so use `--asm`. Unverified. |

### 2.4 Optimisations

Every optimisation is a switch in the `===compiler.config===` section of `toolchain.config`. **They all ship
off, except the two register switches.** Turning them on gives large speedups (see
[section 4](#4-performance)), and every program in `tests/` gives identical output with the switches on
and off.

| Switch | Values | What it does |
|---|---|---|
| `deferred-operands` | on, off | Keeps expression temporaries in registers instead of on the stack. The foundation for most of the speed. (shipped on) |
| `variables-in-registers` | on, off | Hot scalar locals live in registers. Needs `deferred-operands`. (shipped on) |
| `float-variables-in-registers` | on, off | Hot float locals live in xmm registers. |
| `float-temporaries-in-registers` | on, off | Float expression temporaries stay in xmm registers. |
| `function-inlining` | off, conservative, balanced, aggressive | Replaces calls with the callee's body. Tunable with `inline-max-callee-lines`, `inline-max-depth`, `inline-max-growth`. |
| `loop-unrolling` | off, conservative, balanced, aggressive | Unrolls `for` loops with literal bounds. Tunable with the `loop-unroll-*` keys. |
| `constant-folding` | on, off | Folds operators whose operands are literals. |
| `variable-elision` | on, off | Replaces a variable assigned once to a literal with the literal. |
| `variable-shifting` | on, off | Gives each reassignment its own variable so elision can apply. |
| `struct-unpacking` | on, off | Splits local scalar-only structs into separate variables. |
| `dead-control-flow-removal` | on, off | Removes branches that `if true` or `if false` makes unreachable. |
| `dead-function-removal` | on, off | Removes functions that are never called. |
| `unused-declaration-removal` | on, off | Removes unused externs, statics and strings. |
| `variable-allocation-reordering` | on, off | Reorders frame slots by alignment to remove padding. |
| `struct-member-reordering` | on, off | Reorders struct members by alignment to remove padding. |

A larger group of improvements has no switch and is always on: strength reduction (division and modulo by
a power of two become shifts), compare-and-branch fusion, jump cleanup, a float constant pool, indexed
addressing for arrays, and fusion of the bounds-proof test into the loop.

The "everything on" configuration used for the benchmarks in section 4 is:

```
deferred-operands: on
variables-in-registers: on
float-variables-in-registers: on
float-temporaries-in-registers: on
loop-unrolling: aggressive
function-inlining: aggressive
constant-folding: on
variable-elision: on
variable-shifting: on
struct-unpacking: on
dead-control-flow-removal: on
dead-function-removal: on
unused-declaration-removal: on
```

Two cautions. First, `function-inlining: aggressive` has no growth limit, and a program that calls one
large function from hundreds of places can exhaust the optimiser's heap. Use `balanced`, or raise the
limit with `JAVA_TOOL_OPTIONS=-Xmx8g`. Second, compile time grows with the number of foldable branches in
one function, so very large generated test programs are better split into several files.

### 2.5 The standard library

`stdlib/` holds ordinary Caspien source: `libc.caspien` (C bindings), `dynamic_array.caspien`,
`hash_map.caspien`, `string.caspien`, `hash.caspien` (FNV-1a), `sha256.caspien`, `process.caspien`,
`sleep.caspien`, the thread glue `par_call.caspien` and `await_call.caspien`, and the `gt_*` files that
back ownership. The older reference documentation, including the full description of every optimisation
pass, is in [`docs/COMPILER_REFERENCE.md`](docs/COMPILER_REFERENCE.md).

---

## 3. How the compiler works

The compiler is four independent Java programs, each a separate stage that reads and writes a plain-text
bytecode, plus an orchestrator (`Compiler.java`) that runs them in order and then assembles and links with
`as` and `gcc`.

```
 .caspien source
      │
      ▼
 ASTGenerator          lex → parse → RPN → tree → imports → generics → type check → emit
      │  higher-order bytecode (HOB): typed, structured, names and types intact
      ▼
 Optimizer             a fixed-point loop of tree-level passes (folding, inlining, unrolling, ...)
      │  HOB
      ▼
 LowerOrderGenerator   address lowering, register-form temporaries, register variables
      │  low-order bytecode (LOB): byte sizes and frame offsets, no names or types
      ▼
 Codegen               stack-machine and register-form lines → x86-64 assembly
      │
      ▼
 as + gcc              executable
```

**ASTGenerator** is the front end and holds all the language rules. A hand-written lexer and parser
produce a token tree. The type checker, about 19,000 lines, is where every guarantee in section 1 is
enforced: mutability, ownership and moves, bounds and nonzero proofs, the call-graph checks that reject
recursion, and the `@throws` contract. The bytecode emitter then writes a stack-machine program in which
every instruction states the types it operates on. Generics are expanded by monomorphisation before type
checking, and `@recursive` functions are lowered to bounded loops here, before any optimisation runs.

**Optimizer** works on the higher-order bytecode, where types and names still exist. Its passes
(constant folding, variable elision and shifting, struct unpacking, dead code and dead function removal,
loop unrolling, function inlining) repeat until nothing changes. Because it runs before lowering, it can
reason about whole variables, structs and calls rather than machine words.

**LowerOrderGenerator** erases the types. Parameters become frame slots, `sizeof` is resolved, names
become byte offsets, and each operation is rewritten with an explicit size. It also generates the
per-type clone and drop routines that ownership needs. Then the register passes fuse stack sequences into
register form, keep the hottest scalar variables in registers, and fuse comparisons into branches.

**Codegen** turns the low-order bytecode into assembly: GNU syntax for Linux and for mingw-w64, and Intel
syntax for MASM. It handles both calling conventions, preserves callee-saved registers, implements the
unwinding behind `throw`, and calls the system's C library for allocation and threads.

Roughly 60,000 lines of Java make up the four stages. The per-stage `CLAUDE.md` files record the design
decisions, the bugs found and how each change was verified, and `tests/` holds the runtime programs and
check scripts.

---

## 4. Performance

Fourteen programs were timed against C, C++, Rust, Go, Java, Node and Bun on a 2-core Intel Xeon VM
(1 October 2026). Every output matched the C reference. Times are seconds, fastest of two runs. "Caspien"
is the fastest Caspien variant with all optimisations on; the stdlib column is the same program written
with the standard library classes (`DynamicArray`, `HashMap`, `String`), and the last column is the best
variant with optimisations off.

| Program | C -O2 (s) | Caspien (s) | Caspien vs C | stdlib-class version vs C | optimisations off vs C |
|---|---|---|---|---|---|
| mandelbrot | 1.58 | 1.68 | 1.06x | n/a | 5.0x |
| sieve | 1.45 | 1.70 | 1.17x | 2.5x | 3.2x |
| fannkuch-redux | 2.82 | 3.34 | 1.18x | 1.2x | 7.6x |
| graph | 0.33 | 0.40 | 1.22x | 2.8x | 3.0x |
| spectral-norm | 0.21 | 0.27 | 1.30x | 1.8x | 9.8x |
| sorting | 1.28 | 1.98 | 1.55x | 3.6x | 4.6x |
| merkle trees (real SHA-256) | 0.84 | 1.49 | 1.78x | 2.1x | 11.3x |
| n-body | 0.32 | 0.62 | 1.97x | n/a | 9.7x |
| binarytrees | 0.50 | 1.06 | 2.11x | 5.9x | 4.5x |
| fasta | 0.86 | 1.82 | 2.12x | 3.7x | 5.7x |
| lru cache | 0.56 | 1.35 | 2.42x | 15.7x | 9.7x |
| k-nucleotide | 0.45 | 1.34 | 2.97x | 57.3x | 13.0x |
| strings | 0.23 | 0.81 | 3.57x | 25.5x | 8.7x |
| json_serde | 0.75 | 2.82 | 3.75x | 6.3x | 9.5x |

Geometric mean of time relative to C -O2 across the fourteen programs:

| C++ | Rust | Go | Java | **Caspien** | Bun | Node |
|---|---|---|---|---|---|---|
| 1.17x | 1.09x | 1.60x | 1.75x | **1.85x** | 2.38x | 2.60x |

Peak memory is close to C: the geometric mean is 1.07x of C's, ranging from 0.77x to 1.96x. That is no
surprise, because Caspien has no garbage collector and no runtime, allocates with `malloc`, and lays out
structs and arrays as C does.

**An honest reading.**

- With optimisations on, Caspien lands between Go and the JavaScript engines, roughly level with Java. It
  is within 1.3x of C on five of the fourteen programs, and 2.4x to 3.8x behind on four.
- The gap to C and Rust is real. The compiler has no general register allocator, no vectorisation and no
  alias analysis, and every `ref` access pays for the liveness check described in section 1.4.
- The optimisation switches matter more than any single trick. With them off, the same programs are 6.8x
  slower than C on average, and they ship off. That is the biggest single improvement available to users
  today.
- Code written against the standard library classes is much slower than code written against raw arrays
  (about 5x on average, 57x for k-nucleotide). The classes pay for bounds proofs, wrapper calls and, in
  `hashOf`, a heap allocation on each call. That is an engineering gap, not a design limit.
- Benchmarks of this kind favour the language's own authors' choices. The programs are the standard
  benchmarks-game shapes, ported by hand, and no benchmark exercises the event-loop model. Timing noise on
  this VM is about 30%, so differences under 1.3x between two rows are not meaningful. Java and the
  JavaScript engines include start-up and warm-up time.
- Two outliers in the other languages are real and unrelated to Caspien: C++ and Go are slow on the lru
  cache because of their built-in hash maps for that access pattern, and Java beats C on binarytrees
  because its allocator is faster than `malloc` and `free`.

Planned work, in order of expected payoff: ship the optimiser on by default with a bounded inlining
budget; make the standard library classes as fast as hand-written array code; native 32-bit and 8-bit
arithmetic in register form; replace the linear liveness table with a hash table; inline `sqrt` and keep
float constants in registers. The benchmark harnesses (`benchmarks/bench_suite.py`,
`benchmarks/bench_program.py`, `benchmarks/nbody/bench.py`) rebuild and re-time everything, and
[`benchmarks/RESULTS.md`](benchmarks/RESULTS.md) holds the earlier, more detailed measurements.
