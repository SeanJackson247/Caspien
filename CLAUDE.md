## New: real FNV-1a `hashOf`, a real SHA-256 in the stdlib (`stdlib/sha256.caspien`), and the Merkle benchmark ported to real SHA-256 (1 Oct 2026)

Asked for, once `bits_xor` / `bits_and` / `bits_not` / shifts existed. No compiler change (no compiler bug found; nothing in ASTGenerator / Optimizer / LowerOrderGenerator / Codegen was touched).

**1. `stdlib/hash.caspien`.** `hashOf<T>(x: imut T) mut u64` keeps its signature and behaviour for every T it could be instantiated with, but is now real 64-bit FNV-1a: offset basis `0xcbf29ce484222325`, per byte `h = bits_xor(h, byte)` then `h = h * 0x100000001b3` (wrapping), bytes in memory order (little-endian for integers). It still copies the value into a malloc'd scratch buffer (a `raw T` cannot be stepped byte by byte: there is no cast between raw pointer types) and then calls the new `@pub fnv1a64Bytes(p: raw imut u8, byteCount: mut u64) mut u64`, which has users (hashOf, `tests/fnv1a_test.caspien`) and is the hook for strings and structs. The header comment no longer says Caspien has no XOR. Findings: (a) `hashOf` was NEVER instantiable for a struct T (the by-value `imut T` parameter is rejected: "parameter 'x' of function 'hashOf_Pair' is a plain (by-value) 'Pair'"); that is unchanged, and the stdlib `HashMap` therefore only takes non-struct keys. (b) A struct has a hidden 8-byte class-id header in front of its members (`sizeof` of a two-`u64` struct is 24) and padding bytes are uninitialised, so hash struct members from a padding-free struct and skip the header (the test copies the value out of an `auto` pointer first: `raw p` of an `auto` parameter is the address of the parameter, not of the struct). (c) The stdlib `HashMap` slot order changes (FNV-1a vs `h*33 + byte`); nothing observable depends on it.

**2. `stdlib/sha256.caspien` (new).** FIPS 180-4 SHA-256, pointer-based like `hash.caspien`. One shot `sha256(data: raw imut u8, byteCount, digest: raw mut u8)`; streaming `Sha256Ctx` struct + `sha256Begin/Update/Finish/Release` (`auto mut` ctx); block level `sha256Init`, `sha256Compress(state: raw mut u32, block: raw imut u8)` (one 64-byte block, no padding), `sha256CompressWords(state, words: raw imut u32)` (one block as 16 words, for precomputed padding), `sha256Digest`, and the buffer helpers `sha256NewState/FreeState`, `sha256WordsAlloc/WordsFree` (a `raw mut u32` can only come from an extern declared with that return type: `@link_name(malloc) extern ... raw mut u32`). Constraints discovered while writing it: a `deref(p)` is not assignable (writes go through `memcopy(p + i, mut 4, raw v)`); no pointer to a local array element (`raw st[0]` is rejected), no array or dynarray parameter (`ref` cannot precede a dynarray), so the two compress entry points duplicate the same body; a range bound must be a bare variable or literal; static arrays and statics need `unsafe`; a string literal is not a `raw imut u8` (copy it with `memcopy`). Because every call site of an aggressively inlined function is a private copy, the 64-round loops use a `let static` bound (`sha256Rounds`) so the unroller leaves them alone, and `sha256Update` / `sha256Finish` each contain ONE compress call site (data is collected in the pending buffer). A first test with 167 inline calls of the unrolled version ran the Optimizer out of memory (`FunctionInliningPass.tryInline`, `OutOfMemoryError: Java heap space`, 2 GB default heap); that is the documented "unlimited growth" of the aggressive preset, not a bug fixed here: `tests/sha256_test.caspien` is table-driven (cases in `let static` arrays, one call site per kind of check, loop bounds from statics) and compiles in 10-18 s with everything on.

**3. Tests (new, generated, expected values from Python only).** `tests/fnv1a_test.caspien` (148 checks, generator `tests/fnv1a_gen.py`: the three published vectors asserted in the generator, ten strings and 19 byte-pattern lengths through `fnv1a64Bytes`, `hashOf` on u8/u16/u32/u64/s32/s64 values and literals against FNV-1a of the little-endian bytes, determinism/difference checks, two padding-free structs through `fnv1a64Bytes`). `tests/sha256_test.caspien` (168 checks, generator `tests/sha256_gen.py`, digests from `hashlib`, FIPS vectors asserted too: "", "abc", the 56-byte and 112-byte strings, the fox sentence, one million `a` one-shot and in three split patterns, patterned messages of 42 lengths including 0, 55, 56, 57, 63, 64, 65, 119, 120, 121, 127, 128, 129, 183..193, 255..257 and 1000, streaming with 7 chunk sizes on 14 lengths against hashlib AND the one-shot digest, `sha256Compress` / `sha256CompressWords` on padded single blocks (they agree), the Merkle leaf shape (6 cases incl. an index above 2^32 and `d` = 2^32) and node shape (4 cases, two blocks, state carried over)). Both print `PASS`/`FAIL` lines plus a summary; identical output with everything on, the shipped Linux config, register-form only and everything off.

**4. Merkle benchmark (`benchmarks/merkletrees/`, spec in `SPEC.md`, hashlib model `reference/model.py`).** `caspien/merkletrees_{naive,safe,unsafe}.caspien` are GENERATED by `caspien/gen.py` (run it to regenerate; the three share every line of the hash and differ in how the leaf data, the flat tree of 8-word `u32` digests and the 64-digest proof path are accessed: naive = stdlib `DynamicArray<u32>` get/set under bounds proofs, safe = safe dynarrays with `match i in/into`, unsafe = unsafe dynarrays). Same LCG (`u32`, native wrap), message layouts, odd-node rule, proof/tamper rules (`d + 1` as a 64-bit value, carry into `dhi`) and output line as the SPEC. Design, all of it found by reading the LOB of the first versions: the register-form pass fuses a statement only if it is made of plain 8-byte operations on locals and literals; an array element inside a nested expression, or `u32` operands, makes the whole region fall back to the stack form. So the compression keeps every 32-bit value in a `u64` local (masked with `bits_and(.., 0xffffffff)` after additions), reads `W[i]` / `S[k]` (global `u64` arrays: the message schedule and the working state, because an array cannot be passed to a function) into locals in separate statements, and does a 3-way rotate-xor sigma as `bits_xor(bits_xor((x * 4294967297) >> n0, ... >> n1), ... >> n2)` (`x * (2^32 + 1)` duplicates a clean 32-bit `x` into both halves of a `u64`, so a right shift by n has `rotr32(x, n)` in its low half; high garbage only ever flows upward). 64 rounds are unrolled with the working variables renamed instead of shifted (the generated program is about 1100 lines, compile 3-6 s); the padding block of an internal node (`compPad`) has a constant message schedule, so its rounds add one precomputed `K[i] + W[i]` literal. The optimizer inlines `comp`/`compPad`/`leaf`/`node` into the hot loops under `function-inlining: aggressive`. The path to the final shape, in informal single runs (everything on, N = 140000, NOT measurements, no timing harness was run; C -O2 took 0.95 s on the same machine): `u32` locals with `W[i]` inside nested expressions 3.8 s (stack-form fallback), the same with one-operation statements and rotates as shift/shift/or 4.8 s (register form but narrow `u32` operands, every operation wrapped in mov/zero-extend), `u64` locals with the duplicate-and-shift rotate 1.8 s (unsafe variant; safe and naive about 2.2-2.3 s).\n`benchmarks/bench_suite.py` (`merkletrees`: n = 140000, descriptions say real SHA-256), `benchmarks/charts_all.py` (the Merkle description and the caveat sentence: "mix64" / "NOT SHA-256" / "no XOR or AND" are gone; the k-nucleotide sentence is unchanged) and `benchmarks/RESULTS.md` (the Merkle row now holds the re-run numbers: N=140000, fastest of 3, C -O2 1.01 s, Caspien unsafe 1.83 s (1.8x), safe 2.10 s, naive 2.19 s, all outputs equal to C; `results.json` was rewritten; the knucleotide and lru naive rows were re-timed too because the stdlib HashMap hash changed: k-nucleotide naive 39.3 -> 19.2 s, lru naive 10.5 -> 8.2 s) were updated. The old `mix64` programs are gone.

**5. Verification (Linux, executed; everything compared to a baseline recorded BEFORE editing).** (a) Baseline vs after: every `tests/*.caspien` and the n-body ports (72 programs) compiled and run under the four sweep configs (`/tmp/sr/tc.full`, `tc.origlinux`, `tc.regonly`, `tc.off`): output byte-identical to the baseline in all 288 runs (including `stdlib_test`, 59 PASS lines, which exercises `hashOf` and the HashMap); the two new tests are the only additions (74 programs, 0 failures, 0 non-compiling). The 15 `tests/*_check.sh` scripts give identical logs before and after. (b) Merkle: all three variants equal `reference/model.py` for N = 1, 2, 3, 5, 7, 100, 1000, 4097 and 140000 under the everything-off AND everything-on configs (54 of 54 runs; N = 140000 prints the SPEC line `47269b99...c744 52500 17500`), and a run with an LCG seed chosen so that a tampered proof hits `d = 0xFFFFFFFF` (N = 8) equals the seed-modified C reference and model in all three variants under both configs (the `dhi = 1` branch itself is not observable in the output, only the rejection count; SPEC section 8 notes the same). (c) Dependents of `hashOf` / the stdlib `HashMap` (only `stdlib/hash_map.caspien` imports it; users: `tests/stdlib_test.caspien`, the naive k-nucleotide and the naive LRU benchmark): the naive Caspien programs equal the C `-O2` build of the reference under both configs at N = 1000, 100000, 1000000 (k-nucleotide) / 2000000 (LRU) and at the default N = 20000000 (the outputs also equal the old recorded `results.json` C outputs); the naive json_serde, strings, graph and binarytrees programs (they use `DynamicArray` / `String`, not `hash`) equal C at N = 1000 and 100000 (binarytrees depth 4, 10, 14). No test needed an expected value changed: no old expectation was the old hash's output.

**Not verified.** Windows targets (nothing was run; the new code uses no new instruction forms); timing was run afterwards (see the next sentence); SHA-256 of messages of 2^32 bytes or more (the one-shot/streaming byte counter is a `u64` of bytes times 8, not tested above 1 MB); no fuzzing of the SHA-256 beyond the listed lengths and chunkings.


## New: full bitwise support -- `bits_and`, `bits_xor`, `bits_not`, hex / binary / underscore integer literals, one well-defined shift rule, all passes and both x86 backends (always on, no switch)

Asked for ("FULL bitwise support"). Before this only `bits_left`, `bits_right`, `bits_or` existed (see git-less history in `BITWISE_STATUS.md`, now rewritten), the AND instruction was an internal `StrengthReductionPass` artefact, no XOR/NOT existed, and there were no hex/binary literals.
**Language surface.**
- Builtins (all take integers, result has the operand type): `bits_and(a, b)`, `bits_or(a, b)`, `bits_xor(a, b)`, `bits_left(a, n)`, `bits_right(a, n)`, and the new unary `bits_not(a)`. `bits_not` is the complement truncated to the operand's own width: `u8` `bits_not(0)` = 255, `s8` `bits_not(0)` = -1 (two's complement); the result of a narrow unsigned op is zero-extended. Arity errors name the builtin ("'bits_not' takes exactly one argument, got 2").
- Literal adaptation, ALL `bits_*` builtins, same machinery as arithmetic (`TypeInfo.literalValue`, `literalAdapts`/`adaptLiteral`): in the binary builtins and in the shift COUNT, a literal operand takes the type of the other (typed) operand if its value fits (`bits_and(x_u8, 0xF0)`, `bits_left(x_u8, 3)`, `bits_xor(0x80, x_s8)` is an error: 128 does not fit s8); if it does not fit: "'bits_xor': literal 300 does not fit in 'u8'". Two typed operands must still have the SAME integer type ("requires both sides to be the same integer type, got 'mut_u64' and 'mut_u8'"). A call with only literals is typed like an arithmetic literal expression: `u64`, or `s64` when an operand is negative. Typed limit constants (`u8Max`, `s16Min`, ...) are the way to get a typed literal for narrow widths in a constant expression (they are `imut`; pass a `mut` copy where a `mut` parameter is expected).
- Literals: `0xFF`, `0XFF`, `0b1010`, `0B1010`, `_` between digits (`1_000_000`, `0xFF_FF`, `0b1111_0000`) for decimal, hex and binary. They are NORMALISED TO DECIMAL AT LEX TIME (`Lexer.normalizeIntegerLiteral`, BigInteger), so the three later modules only ever see decimal INTEGER tokens (`Token.radixLiteral` only stops float merging: `0x1F.5` is not a float). Typed and range-checked exactly like decimal (`0xFFFFFFFFFFFFFFFF` == 18446744073709551615 == `u64Max`; `-0x8000000000000000` == `s64Min`; a literal above u64 max is the clear type error "does not fit in any integer type (the largest, u64, ends at 18446744073709551615)", it used to crash codegen). Usable wherever a decimal literal is: `match`/`case` arms, `const NAME 0xF0F0`, `enum E{A=0x10,...}` (enum values stay 63-bit, a larger value is now a parse error instead of a `NumberFormatException`), array sizes (`u8[0x4]`), range bounds (`0x0..0b1010`). Lex errors: `0x` ("no digits after the '0x' prefix"), `0b2` ("'2' is not a valid binary digit"), `0xG`, `0x_`/`1__0` ("'_' must sit between two digits").
**Shift-count rule (DECIDED, identical in the constant folder, the stack form and the register form, Linux and Windows, every width).** The count is read as an UNSIGNED number of the count operand's own width (a signed count -1 is its all-ones pattern, i.e. a large count). If count >= the operand's width: `bits_left` and `bits_right` on an unsigned type give 0, `bits_right` on a signed type gives the sign fill (0 or -1, the arithmetic shift saturates). Otherwise an ordinary shift of the width-bit pattern (left shifts truncate to the width, unsigned right is logical, signed right is arithmetic). So there is no hardware masking (x86 masks to 6 bits; the old `bits_left(x, 64) == x` is gone). Implementation: `cmp $64,%rcx; sbb %rcx,%rcx; and` for SHL/SHR (count taken from the zero-extended count word), clamp to 63 via `r15` + `cmova` for SAR (the operand is sign-extended from its width first), constant counts are resolved at compile time; narrow values are kept right (zero-extended / sign-extended) so a count between the width and 63 also works.
**Mnemonics.** HOB: `SHL`, `SHR` (the optimizer's `SAR` split for signed stays), `BITS_OR`, new `BITS_AND`, `BITS_XOR`, `BITS_NOT`. `BytecodeEmitter` emits `BITS_AND/BITS_XOR/BITS_OR/SHL/SHR leftType rightType resultType` (HOB binary form, same shape as SHL/SHR) and `BITS_NOT argType resultType`; `AddressLoweringPass` erases each to `OP size` (LOB). `StrengthReductionPass`'s internal `BITS_AND 8` (unsigned `x % 2^n`) is unchanged and still works. Register form: `R_BIN BAND|BOR|BXOR|SHL|SHR|SAR size dst a b` (variable counts included) and `R_UN BNOT size dst a`.
**Passes touched.** ASTGenerator: `Lexer`, `Token` (`radixLiteral`), `Parser` (`enumLiteralValue`), `TypeChecker` (`BUILTIN_NAMES`, `checkBuiltinCall`, `checkBitsBuiltin` with the unary case and literal adaptation, the >64-bit literal check), `BytecodeEmitter` (`bits_not`, the five binary builtins). Optimizer: `ConstantFoldingPass` (`foldBits`: and/or/xor/not/shl/shr/sar at every width, folded only where the runtime result is defined and identical: operands are reduced to unsigned width-bit patterns, results wrapped back; typed literals only reach narrow widths through the limit constants), `FunctionInliningPass`, `StructUnpackingPass`, `StructMemberReorderingPass` (binary/unary op lists). LowerOrderGenerator: `AddressLoweringPass` (`VALUE_UNARY_OPS`, binary rewrite incl. `BITS_AND`/`BITS_XOR`, new `BITS_NOT` rule), `RangeEndHintPass` (BIN set), `RegisterFormPass` (BIN_OPS, `BITS_NOT` handler, `fuseBinary`: fusion of AND/OR/XOR/NOT/SHL/SHR/SAR incl. variable counts, a `zx` flag so a narrow result is zero-extended, `flushIsFaithful`). Codegen: `X86Backend` stack form `SHL/SHR/SAR/BITS_OR/BITS_AND/BITS_XOR/BITS_NOT`, `emitShiftCore` (shared by stack and register form; the count goes in `%cl`, `rcx` is saved/restored with `push/pop` (it may hold a call argument) and `rdx` is not touched), `rfShift`, `rfBin` (BOR/BXOR/BAND, commutative operand swap, `narrowCmp` exclusion, zero-extension of a narrow result), `rfUn` BNOT; Linux AT&T and the windows_gnu AT&T path share the AT&T code, the MASM/Intel path has its own forms.
**Tests.** Five generated programs (`tests/bits_ops_gen.py`; expected values come from the generator's independent Python model, plus `zlib.crc32` for the CRC loop, never from hand arithmetic): `tests/bits_ops_test.caspien` (1919 checks: every builtin at u8/u16/u32/u64/s8/s16/s32/s64 on a thinned value x count matrix, plus literal forms (hex/binary/underscore, `const`, enum values, array sizes, range bounds, `match` arms, `match x fits u8`), values from `atol` and from loop variables (sweeps checked as checksums), results stored into struct fields, array elements and dynarray elements, narrow results passed straight to a `printf`-style vararg (the one consumer that does not extend a narrow stack word; formatted with `snprintf` and read back with `atol`), hot loops (xorshift64, popcount two ways, bit reversal u8..u64, CRC32, rotate-left/right, a SHA-256-style mix), nested chained calls) and `tests/bits_ops_matrix8_test.caspien`, `..16..`, `..32..`, `..64..` (822 + 850 + 850 + 1338 checks: the FULL value x count matrix, shift counts 0, 1, 2, width/2, width-1, width, width+1, 63, 64, 65, 127, 200, 255 and (signed) -1, -width, min, in constant form and in runtime form) = 5779 checks. Each prints `PASS name` / `FAIL name expected E got G` and a summary. All five give byte-identical output under the four sweep configs (full, origlinux, regonly, off). Why five files: `DeadControlFlowRemovalPass` redoes a whole-function reachability after every folded `if true`/`if false`, and `function-inlining: aggressive` puts every check into `main`, so compile time with everything on grows with the SQUARE of the number of foldable checks in one program (one 4266-check program took 8 minutes; the five files take 20 to 150 seconds each). `tests/bits_ops_check.sh` (34 compile-time cases: arity, float/bool/string arguments, mismatched widths and signedness, literals that do not fit, lex errors, over-u64 literals, and forms that must still compile; each asserts a non-zero exit AND a message substring). Mutation checks (rebuilt module per mutant): signed shift fill dropped in the folder, SAR count clamp removed (stack form), register-form XOR emitted as OR, the SHL/SHR `sbb` mask replaced by an always-true mask, and `BITS_NOT` not zero-extended (needed the vararg checks) are all caught; one survivor is equivalent (folder tests `count > w` instead of `>= w` for SHL: shifting by exactly w and masking gives 0 anyway). The earlier references to a `tests/bits_ops_lowering_test.caspien` (and `dot_lowering_test.caspien`) pointed at fixtures that no longer exist; `bits_ops_test.caspien` replaces the first.
**Verified / not verified.** See `BITWISE_STATUS.md`. Linux execution only; the windows_gnu output of the tests was assembled and linked (not run) with mingw, and a small MASM-target (`windows`) program was only sanity-assembled with GNU as in Intel-syntax mode; MASM (the `windows` target) and real Windows/Wine execution were NOT run.
## Fixed: `DynamicArray<S>` for a struct `S` compiles and works; generic-impl methods are checked lazily; methods may return a struct by value (also through `try`/`?`) (1 Oct 2026)
Root cause: `new DynamicArray:<S>(arr)` monomorphizes the whole generic impl, so the `seed: mut T`, `set(x: mut T)`, `pushBack`, `pushFront`, `popBack(defaultValue: mut T)`, `popFront(defaultValue: mut T)` signatures became plain by-value `S` parameters, and `TypeChecker.buildFuncInfo` rejects those unconditionally (passing a struct on the stack is broken at runtime; that rule is NOT relaxed). It fired even though nothing called them. Two more bugs stood behind it once that was out of the way: (a) a METHOD returning a struct by value (`d.get(d, i)`, `popBack`) crashed at runtime: only plain-function calls got the hidden return-value-optimization destination, the callee wrote through a garbage pointer; (b) a struct-returning call under `try`/`?` (`let e = mut ? f()`, plain functions too, pre-existing) crashed for the same reason: the type checker flagged the call but the emitter never looked through the `try`.
What changed:
1. Lazy per-method checking (ASTGenerator `TypeChecker`, `GenericsExpander`, `Token`): every method/constructor token cloned out of a generic `impl<T>` template carries `Token.fromGenericInstance`. If its signature is illegal ONLY for the chosen T (by-value struct parameter), `buildFuncInfo` throws `DeferredSignatureException`; `collectImpl` catches it, drops the method (never registered, checked or emitted) and records the reason in `unavailableGenericMethods`. A call that then finds no match gets the reason appended ("method 'pushBack' of 'DynamicArray_Edge' is unavailable for this type argument: parameter 'x' of 'pushBack' is a plain (by-value) 'Edge' ... use a pointer-taking variant"). Ordinary (non-generic) code and non-generic impls keep the hard error unchanged.
2. Pointer-taking stdlib twins (`stdlib/dynamic_array.caspien`), usable for ANY T (u64, char, structs of every size): `setPtr(i, x: auto mut T)`, `pushBackPtr`, `pushFrontPtr`, `popBackPtr(defaultValue: auto mut T)`, `popFrontPtr`. Call as `d.pushBackPtr(d, auto s)` with `s` a LOCAL variable (`auto` needs an addressable variable: `auto d.backing[i]` and struct literals are rejected; copy the element to a local first). Reading: `d.get(d, i)` (bind to a `let`) or `d.backing[i].field` under `match i in d.backing`; element field write: `d.backing[i].field = v` under `match i into d.backing`. For a struct S the by-value seed constructor, `set`, `pushBack`, `pushFront`, `popBack`, `popFront` are unavailable; use `new DynamicArray:<S>()` or the adopt constructor, then the `...Ptr` methods. No pointer-seed constructor: overloads are told apart by parameter BASE type only (`sameSignature`), so `(seed: auto mut T)` collides with `(seed: mut T)` for T = u64.
3. Return value optimization for method calls (`TypeChecker.markStructRvoMethodCall`, called from `checkMethodCall`, `checkStaticMethodCall`, `checkGenericMethodCall`): same two legal positions as plain functions (a `let`'s right side, or `x = call` with a bare variable target; anything else: "bind it to a 'let' first"). `BytecodeEmitter.unwrapMutWrappers` now also looks through a non-block `try`, and `emitStructRvoCallSequence` re-attaches that try's catch label (`ownerTryOf`), so `let v = mut ? f()` / `let v = mut try f() catch(e){..}` / `return ?f()` work for struct returns (plain functions and methods).
NOT done: implicit by-pointer passing of struct-typed generic T parameters (design option 3, so that `d.pushBack(d, s)` would work for a struct): it needs a body rewrite of every use of the parameter (`x` -> `deref(x)`), an argument rewrite at each call site, and temporaries for non-addressable arguments (struct literals, elements), and the overload/literal-adaptation machinery would have to know about it; the `...Ptr` twins give the same reach explicitly. Still open (pre-existing): a plain struct parameter is illegal, a `deref(p) = wholeStruct` target mis-compiles, `auto` cannot point at a dynarray element.
Verified (Linux only): new `tests/dynarray_struct_generic_test.caspien` (65 checks: adopt constructor, `get`, field reads/writes, `setPtr`/`pushBackPtr`/`pushFrontPtr`/`popBackPtr`/`popFrontPtr` on 16/24/40/56-byte structs, a mixed-width struct, a struct containing a struct, pop on an empty array giving the default, a user instance/static method returning a struct, `DynamicArray<u64>` and `<char>` regression and the pointer twins on a scalar) identical with everything on, everything off, register-form only and the shipped Linux config; new `tests/dynarray_struct_generic_check.sh` (14 compile-time checks: each unavailable method errors only when called and names the method and reason, the twins compile, the plain by-value struct parameter and a non-generic impl method are still rejected, a user generic impl is lazy); old-tree against new-tree sweeps of every `tests/*.caspien` plus the n-body ports under 4 configs show only the new test as different (it does not compile on the old tree). Benchmarks: the NAIVE `binarytrees` (`DynamicArray<Node>` pool, via `gen.py`) and `json_serde` (`DynamicArray<Rec>`) variants now use `DynamicArray<struct>`; outputs equal C at small and default N under both configs.
Not verified: Windows/Intel targets.

# CLAUDE.md -- caspien-compiler Project Memory

Read this first in any new conversation thread before making changes.
This file describes the **current state** of this project only.


## CURRENT STATE AND OPEN ITEMS (1 Oct 2026, 21:35) -- read this first in a new thread

**Where we are.** Owner's last substantive request (21:12): add the recursion benchmark (DONE, nine languages), answer whether directly-recursive `ref` structs can be allowed, whether recursion can ever beat a loop, what to do about execution time, and why peak memory is so good; plus re-list the "Things To Know" list (STILL OWED: the list was lost in a context reset and could not be recovered; ask the owner which list they mean or rebuild it from this file). Then asked for the full zip with synced masters (DONE, twice).

**Q1: allow direct self-`ref` recursive structs (A may reference A; A and B may not reference each other; strictly `ref`, never `owns`)?**
- Status: NOT implemented. Experiment only: the ban lives in `validateNoRecursiveStructs`/`detectStructCycle` (TypeChecker); relaxing it for a direct self-`ref` member is about one line, and loop traversal compiled and ran. The first node needs `null` (plain `ref` is nullable and needs `match Some(x)` to dereference); `ref some` is not allowed as a struct member.
- Memory safety: no garbage cycles, because `ref` is non-owning; dangling/alive checks (`gt_alive_check`) still apply. Ref cycles built by assignment are possible but harmless to freeing.
- Recursion/termination: unchanged, `@recursive` stays the only recursion; traversals are loops and need constant bounds to keep static termination/WCET.
- WCET COST (the real one): `gt_alive_check` is a linear scan of the ghost table under a spin lock, so every ref hop is O(live allocations). Static worst-case time is only known if the table is bounded or hashed. A hash set in the ghost table fixes the average cost and makes the bound O(1) per hop.
- Performance gain: none expected over index pools (the benchmarks already use index-based trees and are 1.0-1.2x C on binarytrees/graph).
- Parts of the project built on the "no recursive structs" assumption and UNVERIFIED with the relaxed checker: drop glue (`DropGlueGenerationPass`), clone generation, struct unpacking, struct member reordering, `sizeof`, and a full `tests/` sweep. Mutual recursion must stay banned (A->B->A).

**Q2: can real recursion beat a loop?** No (see `benchmarks/RESULTS.md`, recursion section). Tail recursion at best ties (gcc/g++/rustc -O2, LuaJIT); Go and Java are ~5x slower; for NON-tail algorithms the native call stack beats an emulated explicit stack (~4x at C -O2), which is the only case where recursion wins, and Caspien does not allow non-tail recursion. Caspien's lowered `@recursive` loop is 0.706 s vs hand loop 0.285 s (everything on): a compiler gap (range rebuilt and `within` tested per step).

**Q3: execution-time plan** (Caspien vs C -O2: binarytrees 1.2x, fannkuch 1.0, fasta 1.7, graph 1.2, json_serde 2.9, knucleotide 1.9, lru 1.8, mandelbrot 1.2, merkletrees 2.0, nbody 2.1, sieve 1.0, sorting 1.5, spectralnorm 1.0, strings 2.6 (naive String class 21.4)). Candidates, roughly by payoff: strings and the String class (`appendChar`/`charAt`); json_serde; merkletrees (stack-form fallback, u32 operands); n-body float code and `sqrt`; naive stdlib HashMap/DynamicArray paths (fasta, knucleotide, lru); hash the ghost table; `within` short-circuit and `ARGn` pushes in the lowered `@recursive` loop; unfused `POP $slot 8`; fuse `LOOKUP_DYN`+`DOT` for struct elements.

**Q4: why peak memory matches C:** no GC and no runtime; allocation is libc malloc; data layouts are the same as C's (structs padded like C, dynarrays are one header plus elements); the ghost table adds only 8 bytes per registered pointer.

**How to rebuild/run:** see the rules at the top of every `master_code.md`. Benchmarks: `benchmarks/bench_suite.py`, `bench_program.py`, `nbody/bench.py`, `recursion/run.py`, report via `charts_all.py`. Never compile two programs at once in one compiler tree; VM timing noise is ~30%, use best of 3-5.

## Benchmarks: recursion micro-benchmark now covers nine languages (1 Oct 2026)

`benchmarks/recursion/run.py` builds and times C (-O0/-O1/-O2), C++, Rust, Go, Java, Node, Bun, LuaJIT and Caspien on a tail-recursive chain and a non-tail binary tree; ports live in `recursion/ports/`, results in `recursion/results.json`, a "Recursion" section in `charts.html` (`charts_all.py`) and a table in `benchmarks/RESULTS.md`. Findings: recursion never beats a loop (tail calls tie at C/C++/Rust -O2 and LuaJIT, Go and Java are ~5x slower, native stack beats an explicit stack ~4x for non-tail); Caspien's lowered `@recursive` loop 0.706 s vs hand loop 0.285 s (everything on). Not changed in the compiler: recursion rules intact (only `@recursive` tail self-calls; recursive structs still rejected). Experiment, not shipped: allowing a direct self-`ref` struct member needs a one-line relaxation of `validateNoRecursiveStructs`/`detectStructCycle`; loop traversal worked, but drop glue, clone, struct unpacking and ref cycles by assignment are unverified, and ghost-table `gt_alive_check` is a linear scan, so ref hops are O(live allocations).

## New: `RangeWordSplitPass` -- 16-byte range constructions and copies become two 8-byte moves, so the lowered `@recursive` loop is ~40% faster (1 Oct 2026, LowerOrderGenerator, always on with `deferred-operands`)

Asked for after the recursion/loop benchmark ("im happy for you to optimize the recursive func to loop conversion"). The recursion rules were re-checked first and have NOT drifted: a non-`@recursive` self-call, a self-call that is not the sole expression of a `return` (`return f(x) + 1`), and indirect/mutual recursion are all compile errors (checked with three programs); only a tail self-call inside `match r2 within r` in a `@recursive` function with a final `imut range` parameter is accepted, and that is lowered to a bounded `for` (`TypeChecker.lowerRecursiveFunctionToLoop`, unchanged). Not checked: recursion through a function pointer (`INVOKE`).
Cause of the cost (read from the LOB): every `range` construction (`ADDR 16 $X ; PUSH 8 a ; PUSH 8 b ; ASSIGN 16 16 16`) and copy (`ADDR 16 $X ; PUSH 16 $Y ; ASSIGN 16 16 16`) stayed stack-form, three per step in the lowered loop, and they kept every range slot out of registers. New `LowerOrderGenerator/.../RangeWordSplitPass.java` (runs after `RangeEndHintPass`, before `RegisterFormPass`): both shapes become two 8-byte `ADDR/PUSH/ASSIGN` triples (operands `$slot`, literal or `ARGn`; a slot read after the first word is written must not overlap the destination; a copy needs disjoint slots, a self copy is dropped), then it emits `REGHINT` for each word of the range whose every mention is a plain 8-byte `PUSH`/`ADDR` (the promotion re-checks everything, all-or-nothing). Result (Linux, fastest of 5, same run): chain 1000 x 200000 `@recursive` everything on 1.12 -> 0.67 s (hand-written loop 0.28 s, C -O2 0.22 s); the numbers on this VM vary by about 30% between runs (0.67-0.91 s seen for the same build). Tried and reverted: skipping the `__recur_tmp` copy for a bare local argument (measured no better). Remaining gap: the `within` check is a full strict-subrange test with no short-circuit, parameters are pushed from `ARGn` in stack form at entry (blocks their promotion), and the shadow parameters are copied through temporaries.
Verified (Linux only): new `tests/range_split_test.caspien` (13 checks, hand computed: range copy, rebuild, pass to a function, loop over a copy, `@recursive` weighted sum against the same computation by hand, empty and single-step ranges, step counts 4/3/1 for a start+2/end-1 shrink); all 66 `tests/*.caspien` compiled and run with the everything-on Linux config, old tree against new tree: identical output (3 of them were first reported different because the sweep compiled two programs at once in one tree, which shares scratch files; re-run one at a time they are identical). Not verified: the shipped config (deferred operands mostly off, so the pass does not run), Windows/MASM, a mutation run of the new pass.

## New: benchmark suite extended to 15 more languages, report grouped by metric, recursion micro-benchmark (1 Oct 2026)

`benchmarks/newlangs.py` holds the language table (build/run commands, `rows()` used by `bench_suite.py`, `bench_program.py`, `nbody/bench.py`; `python3 benchmarks/newlangs.py check <prog|all> <lang>` verifies a port against C). Languages: Fortran, C#, Odin, Zig, Nim, WebAssembly, Crystal, Kotlin, Chapel, Codon, D (LDC/GDC), Objective-C, OCaml, Swift; toolchains in `/opt/tc` or apt in the sandbox. Dart could not be installed (network allowlist) and has no row. C# builds offline through an empty local NuGet feed. Caspien benchmark sources use `bits_and`/hex literals instead of `% 4294967296`. `charts_all.py` was rewritten: metric first, sortable tables, geomean vs C -O2. `benchmarks/recursion/run.py` measures recursion against loops (results in RESULTS.md). Finding: the `@recursive` lowered loop is ~4x slower than a hand loop in Caspien (range rebuilt and `within` tested per iteration); not yet optimised. Verified on Linux only.

## Fixed: reading a field of a dynarray-of-struct element (`es[i].to`) -- Codegen `LOOKUP_DYN`/`LOOKUP_ARRAY` now tell the following `DOT` the block size; struct-node graph benchmark added (always on, no switch)

Asked for ("can we fix the indexing of an array of structs? the recursion bans are wanted, but indexing an array of structs is wanted"). The long-standing "dynarray-of-struct member access crashes" gap (flagged since the benchmark port, "DOT with no preceding pushed value") was NOT in the front end or the lowering: the bytecode (`LOOKUP_DYN 24 / PUSH_FIELDNAME 8 8 / DOT 8`) was right. In `X86Backend`, `LOOKUP_ARRAY` records the byte size of a pushed multi-word element in `lastValueBlockSize` so the `DOT` that follows can address the field inside the block (words are reversed on the stack, the front word is deepest); `LOOKUP_DYN` (safe dynarray) and the pointer-base path of `LOOKUP_ARRAY` (unsafe dynarray, which pushes `PUSH 8 $ptr / PUSH 8 idx / LOOKUP_ARRAY size`) never did, so `DOT` found `pendingBlockSize <= 0`, emitted a `TODO(codegen)` comment and pushed 0. Reads gave 0 or crashed; WRITES (`es[i].to = x`, `DOT_LHS` on an address) and whole-element copies (`let e = es[i]`) always worked. Fix: both paths set `lastValueBlockSize = elemSize` for an element wider than 8 bytes (two small edits in `X86Backend`). Nothing else changed; the recursion ban stays.
Verified (Linux only): new `tests/dynarray_struct_test.caspien` (15 checks: a 24-byte `Edge` filled and read through the element, a whole-element copy, a field read as a call argument, a `Mixed` struct with u8/u64/u32/u16/u8 fields at awkward offsets, a nested struct field (`os[i].in1.x`, a 24-byte field read out of a 48-byte element, then an 8-byte field out of that), and an unsafe dynarray of `Edge`; expected values from an independent Python model), identical with everything on, everything off, register-form only and the shipped Linux config; before the fix the test crashes (2 `TODO(codegen)` DOT lines); old-tree against new-tree sweeps of 66 programs under 4 configs: the only difference is this new test (it crashes on the old Codegen), all other programs identical. The register-form `LOOKUP_DYN` fusion only fires for element sizes 1/2/4/8, so struct elements always take this stack path.
Benchmark: `benchmarks/graph/caspien/graph_struct_safe.caspien` and `graph_struct_unsafe.caspien` keep the whole graph in ONE dynarray of `Node{value, dist, e0..e3}` structs (edges are still node INDICES: a recursive struct is rejected on purpose, so no node pointers); registered in `bench_suite.py` and `charts_all.py`; output equals C at 2M nodes. Time (1 Oct 2026, same run, fastest of 3, seconds): C -O2 0.275, parallel arrays safe 0.430 / unsafe 0.472, struct nodes safe 0.752 / unsafe 0.688 (about 2.5-2.7x C). The struct versions are slower than parallel arrays because every field read pushes the whole 56-byte element (7 words) and extracts the field from it. Possible next step, not done: fuse `LOOKUP_DYN n / PUSH_FIELDNAME off size / DOT size` into one `R_LEA` (displacement 16+off) + `R_LD size` in `RegisterFormPass`, which would skip the block push. Not verified: Windows/Wine/MASM (the Intel form of the change is the same two assignments, unexecuted).

## New: dynarray access in register form (`LEN`, `LOOKUP_DYN`, `ZEXT`, `TRUNC`), r8-r10 for variables in allocating functions, `@lock` functions are inlined (always on, no switch)

Asked for ("yes, please do", after the benchmark review showed the sieve/graph/sorting programs 2-5x behind C). Profiling the safe sieve showed the hot loops (`f[i] = 1`, `if f[j] != 0`, `count += f[k] as u64`) still in STACK form: nothing fused a dynarray length read, an indexed element access or a widening/narrowing cast, so each access pushed/popped, and the loop variables stayed in memory (a plain `PUSH` makes a slot unrenameable in `RegVarPromotionPass`). Three changes:
1. **`RegisterFormPass` handlers** (LowerOrderGenerator). `LEN` (`fuseLen`, shares `derefN` with `fuseDeref`): `R_LD 8 %t [ptr]`, dynarray layout is `[len:8][cap:8][elements]`, so the length is at offset 0. `LOOKUP_DYN n` (`fuseLookupDyn`): `R_LEA %tD base idx n 16` (address = base + 16 + idx*n; `R_LEA` gained an OPTIONAL 6th token, a displacement) and, in value form, `R_LD n %t %t` (declined unless n is 1/2/4/8, and declined when the next line starts with `LOOKUP` or `DOT`, which still need the address form). `LOOKUP_DYN_LHS` yields the address for any scale. `ZEXT` and `TRUNC` (`fuseZext`, `fuseTrunc`) keep the value in the register: ZEXT 8 8 is a no-op; a frame value is simply re-read at the narrower width; a temp that is already zero-extended (new `Entry.zx` flag, set by `loadInto`/`loadedTemp` for loads from memory) is relabelled; otherwise a 1/2-byte source is masked (`R_BIN BAND 8 %t %t #255|#65535`) and a 4-byte source is declined. TRUNC (8 -> 1/2/4) narrows a frame value or masks a temp. IMPORTANT trap: narrow `R_BIN ADD/SUB/MUL` results are NOT masked in the register (only loads are zero-extended), which is why a relabel is only allowed for `zx` temps; the first version of ZEXT got this wrong in design and the `zx` flag fixes it. Handlers return 0 (decline) without emitting anything, as always.
2. **`IndexedAccessPass` / `RegVarPromotionPass` / `X86Backend`**: `R_LEA` with 5 or 6 tokens is accepted by the promotion pass (`mentionOk`: `(size==5 || size==6) && k==3 && h.size==8`); `IndexedAccessPass` still requires exactly 5 tokens (a displaced lea is not fused). `X86Backend` `case "R_LEA"` passes the optional displacement to `rfLea(dst, base, idx, scale, extra)`: an immediate index folds to `imm*scale+extra`, a scaled index is `extra(%d,%x,scale)` when it fits imm32, otherwise `lea` + `rfAddImm`; the imul path adds `extra` with `rfAddImm`.
3. **Variables in functions that allocate** (`RegVarPromotionPass`). A function containing `NEW*`/`RESIZE*`/`CLONE`/`DOT`/`LOOKUP_ARRAY`/`ASM_*` used to get no promotion at all, because the backend uses r12-r14 as scratch there. Now such a "blocked" function gets `intLimit` 0 for the callee-saved registers but may still use the caller-saved r8-r10 for any variable that is not live across a line outside `VOL_SAFE` (every allocating line is outside it, so a variable live across `new`/`resize` stays in memory). Test: `tests/regvars_blocked_test.caspien` (15 checks, expected values from a Python model: a value defined before a `new` and read after, an allocation inside the loop, an unsafe-dynarray sieve, four running sums over a `u64` dynarray), identical with variables-in-registers on and off.
4. **Inliner** (`Optimizer/.../FunctionInliningPass.java`): `OK_DECORATORS` now includes `@lock`. `@lock(match i into self.backing)` on the stdlib `DynamicArray.get/set` (and the struct-level `@lock`) is a compile-time proof contract only; the lock code itself is emitted at the CALLER (`match @lock`), so inlining the callee body cannot skip a lock. Before, every stdlib `get`/`set` stayed a call, which kept the naive variants slow and every loop variable pinned. `CASPIEN_INLINE_WHY=1` prints refusal reasons. `tests/inline_hob_check.sh` has two new cases (d4: an `@lock` leaf is inlined; d5: `@gt_init` still refuses), 57 PASS.
Result (Linux, 2-core VM, same session, previous tree vs this tree, everything-on, seconds): safe sieve 3.24 -> 1.85, unsafe sieve 2.77 -> 1.87, naive (stdlib DynamicArray) sieve 7.24 -> about 3.4, all outputs identical. End-of-work full re-run (noisy machine, C -O2 sieve 0.85-1.27 s depending on the run, so only same-run ratios mean anything): safe is 1.3x (sieve), 1.4x (graph), 1.6x (sorting), 4.5x (strings) C -O2; unsafe 1.3x / 1.6x / 2.2x / 5.1x; naive 2.9x / 2.7x / 2.9x / 24x. fannkuch, spectral and n-body: no regression beyond noise (fannkuch n=11 naive 3.99, optimized 3.89, C 3.65 in the same run). Full tables and caveats: `benchmarks/RESULTS.md` (last section), `benchmarks/charts.html`.
Verified (Linux only): new `tests/regform_dyn_test.caspien` (26 checks: dynarray read/write at u8/u16/u32/u64 scales, length reads, `as u64` widening from u8/u16/u32 including values after narrow wrap-around, `wrap:<u8>` narrowing, prefix sums, nested index expressions, a `LOOKUP_DYN` followed by `DOT`) identical with everything on, everything off, register-form only and the shipped Linux config; new `tests/regform_dyn_check.sh` (47 hand-made stack-form LOB cases through `RegisterFormPass`, env `LOB_CP` for a mutant classpath); `tests/regvars_volatile_check.sh` now 75 checks; old-tree against new-tree sweeps of 65 programs (every `tests/*.caspien` plus the n-body variants) under 4 configs (everything on, shipped Linux config, register-form only, everything off) for the LowerOrderGenerator/Codegen change AND, separately, for the inliner change: 0 differences in all 8 sweeps; `callee_saved_probe.sh` PASS; `rangecheck_check.sh` 28, `lob_passes_check.sh` 41, `floatindex_check.sh` 35, `regform_globals_check.sh` 19, `regvars_share_check.sh` 19 pass.
Mutation checks on the new register-form handlers (harness `mut.py`, one rebuilt LowerOrderGenerator per mutant, checked by `regform_dyn_check.sh` and the runtime test): wrong lea scale, wrong or missing displacement, wrong load width, missing next-line guard, missing/incorrect `zx` handling (relabel of a non-zero-extended temp, mask swapped, source 8 treated as no-op, ZEXT from a narrow M entry not narrowed, TRUNC T4/narrow-M/mask-swap) all caught; the survivors are equivalent (conservative variants that only decline more). Crashes count as caught.
Not verified / caveats: Linux only (windows_gnu assembles and links but was not run: no Wine here; MASM/Intel untested; the Intel form of the displaced lea is unexecuted); the String class (`appendChar`/`charAt`/`setCharAt`, 24x C) is NOT improved by this work; the naive variants still carry stack-form residue (`POP $slot 8` is not handled by `fusePop`, so the `match i in f.backing` proof chain rolls back); the unsafe sorting variant measured slower than safe in the last run (not understood); only dynarray element sizes 1/2/4/8 take the value form.
Test-writing notes: `^` is not an operator (use `bits_or`); a program's expected values change if an earlier loop rewrote the array in place; a `dyn:<u8[3]>` or type alias as a dynarray element does not compile.

## New: four benchmarks (sieve, strings, heap graph, sorting/searching), generic harness `benchmarks/bench_suite.py`, Lua 5.4 dropped, chart colours by group

Asked for (see `benchmarks/RESULTS.md`, last section, for the table and caveats). `bench_suite.py <name>` builds and times C -O0/-O2 (free/leak), C++, Rust (free/leak via `--cfg leak`), Go, Java, Node, Bun, LuaJIT and every Caspien variant (off/full), checks output against C, writes `<name>/results.json`; `charts_all.py` draws `charts.html` (green Caspien, red bare metal, purple Java, blue JS/LuaJIT, shades per language; overview table). Lua 5.4 removed from the harnesses, charts and old results.json. Finding: the best Caspien variant is 1.9-4.6x behind C on these programs, naive stdlib versions 6-26x. Compiler gaps hit while writing Caspien versions: recursive structs rejected, dynarray-of-struct member access crashes (not fixed), `char` not castable, no `for` step.

## New: step 5 of the gap-to-C plan -- the hidden `for` range end is promoted to a register (`RangeEndHintPass`), and r8/r9/r10 are variable registers in CALL-FREE regions (`RegVarPromotionPass`, `X86Backend`); always on, no switch (needs `variables-in-registers` and `deferred-operands`)

Asked for ("please", after the fannkuch register-pressure experiment: raising the register budget by hand gave -18%). Two parts.
1. **Range end (LowerOrderGenerator `RangeEndHintPass`, new, after `RangeCheckFusionPass`, before `RegisterFormPass`; only when `deferred-operands` and `variables-in-registers` are on).** A `for` loop's hidden range `$r` is 16 bytes (start at `$r`, end at `$r+8`) built by `ADDR 16 $r / <a> / <b> / ASSIGN 16 16 16`; the whole 16-byte slot was a plain `ASSIGN`, so neither half could ever be a register. The pass splits it into two 8-byte stores and emits `REGHINT <ro+8> 8 <weight>` (weight = sum of 8^min(loopDepth,4) over the loop's uses; emitted only when >= 8). Only the shapes it can prove (both bounds are plain 8-byte pushes of a literal or a slot; the 16-byte slot is mentioned nowhere else, checked with `usable()` over `$ro-8 < o < $ro+16`) are touched; anything else is left byte-for-byte.
2. **r8/r9/r10 as variable registers `%v3..%v5` (`RegVarPromotionPass` + `X86Backend.RF_VAR_REGS = {r13,r14,r12,r8,r9,r10}`).** They are CALLER-saved, so a variable may live in one only where no call can clobber it. Only the share-registers path (candidates > callee-saved limit) uses them, so a function that is not over-subscribed produces exactly the old output. A variable is `volOk` if it is never in `in[i]`, `out[i]` or `def[i]` of a line whose mnemonic is not in `VOL_SAFE` (R_MOV, R_LD, R_ST, R_BIN, R_UN, R_BRC, R_BRF, R_LEA, R_RMW, R_SETV, R_LDX, R_STX, R_FBIN, R_FBINX, R_FCMP, R_XTOG, R_GTOX, R_XMOV, R_PUSH, R_PUSHA, R_XVAR, R_RET, R_RETF, JMP, CMP, ALLOC, FUNC_START, FUNC_END; labels are safe). So any CALL, INVOKE, EXTERN_CALL, SLEEP, CC_START, R_ARG ... line (and every unknown mnemonic) pins the variable to a callee-saved register or to memory. Function-level bans: a token `ARGk` with k >= 2 bans r8/r9 (they are win64 argument registers 3 and 4); `PUSH_RET_FLOAT` or `R_GETRETF` bans r10 (float-result stash). `colourInt` tries the volatile registers first for volOk variables, then the callee-saved ones; non-volOk variables only the callee-saved ones. Backend: r8/r9 are only touched through `argReg`, r10 only by the NEW repack (blocked functions), INVOKE and the float stash; `rfReg` sets `rfVarUsed` only for index < 3, and `finishCalleeSaved` ignores r8-r10 (nothing to save).
Result (Linux, 2-core VM, same session, previous tree vs this tree, alternating, two rounds, best of both, "full" rows, seconds, outputs identical on every row): **fannkuch naive 4.27 -> 3.69 (-14%), optimized 4.23 -> 3.50 (-17%)**; spectral naive 0.348 -> 0.341, optimized 0.226 -> 0.241; n-body natural f64 0.617 -> 0.632, plain f64 0.565 -> 0.580, loop f64 0.569 -> 0.581, arr f64 0.579 -> 0.558, scalars f64 0.575 -> 0.593, plain f32 0.598 -> 0.580, loop f32 0.609 -> 0.576, arr f32 0.634 -> 0.599, scalars f32 0.609 -> 0.577 (all within the ~5-10% noise of this VM). Part 1 alone gives nothing (the callee-saved registers are full); part 2 is what pays. Details: `benchmarks/RESULTS.md` ("Step 5"), `benchmarks/charts_before_after_step5.html`, `benchmarks/before_after_step5.json`, `charts.html`.
Verified (Linux only): new `tests/regvars_volatile_test.caspien` (25 checks, expected values from a Python model: `wide` (more hot variables than callee-saved registers), `wideCalls` (calls in some phases), `phases`, `nested` loops, `narrow` types, `deep` expression, `catchWide` (a throw caught in a loop), `endFixed`, `endExpr`, `startNonZero`, `triangle`) identical under the shipped Linux config, everything on, and everything off; new `tests/regvars_volatile_check.sh` (59 PASS: hand-made register-form cases through `RegVarPromotionPass` and 11+ shape/reject cases through `RangeEndHintPass`; env `LOB_CP` for a mutant classpath); `tests/regvars_share_check.sh` case 2 got a `CALL g` line (with volatile registers a never-called function now fits D and E, so the case needed a call to keep all variables live across it); old-tree against new-tree sweeps of 63 programs under 4 configs: 0 differences; `callee_saved_probe.sh` PASS; `check_callee_saved.py` 0 problems on the volatile test, fannkuch and the windows_gnu builds; `rangecheck_check.sh`, `lob_passes_check.sh`, `floatindex_check.sh`, `regform_globals_check.sh` pass.
Mutation checks on the new code: 38 mutants (`/tmp`-harness, rebuilt per mutant). All behaviour-changing ones are caught. First run's survivors led to four new tests (V6b, V9b, V11b, a deep expression), and then to a fix of my own test (V6 and V6b wrote `R_ARG 0 $slot`, the real layout is `R_ARG n size src`, so the line was not a read of the slot at all; with the correct form `volNoIn` and `volOnlyOut` are caught too). Survivors that remain are equivalent or only conservative: `volNoOut`, `volNoDef` (the scan is redundant with the in/out sets for the shapes that occur), `argBanBadParse` (an unparsable ARG token cannot occur), `colourTakenXmm` (xmm registers never share the integer `taken` array), `rangeOverlapBelowOff1` (a more precise overlap test, still correct).
Not verified / caveats: it only fires in functions whose promoted-variable candidates exceed the callee-saved limit (the share path), so most programs, and the shipped default (`variables-in-registers` etc. mostly off), see no change; Linux only (windows_gnu builds assemble and link, check_callee_saved is clean, but they were not RUN: no Wine here; the win64 argument-register ban is derived from the ABI, not exercised); MASM/Intel untested; the `VOL_SAFE` list is a whitelist, so a call-free region containing any mnemonic outside it simply loses the optimisation (conservative); the loop's range START (`$r`) is not promoted, only the end; a range bound in source must be a bare literal or variable, so expression bounds only arrive via other passes (covered by the LOB checks, not by source programs).
Test-writing notes: range bounds in source must be a bare literal or a bare variable (compute `a + b` into a variable first); a hand-made LOB case has to use the real `R_ARG n size src` layout.

## New: steps 3 and 4 of the gap-to-C plan -- a float constant pool, and indexed addressing for global float arrays (`R_LDXI`/`R_STXI`); `FloatTempPass` keeps `R_FBIN` results headed for a store in xmm (always on, no new switch)

Asked for ("if its a good sensible optimization anyway, like in general, then i want it even if it doesnt help hugely in these particular benchmarks - so go ahead and implement and then test"), after steps 1, 2 and 2b. Also asked for in the same breath: Lua 5.4 and LuaJIT in the benchmarks from now on.
1. **Step 3, constant pool (Codegen, AT&T targets only).** A float immediate in register form used to be built in a general register (`movabs $bits, %r15 ; movq %r15, %xmmN`) every time it was used. Now each distinct constant (key = width and bit pattern; f32 patterns masked to 32 bits) gets one entry `.LFC<k>` in a read-only section emitted after the last function (`.section .rodata`, or `.rdata` on windows_gnu), and is used as a memory operand (`mulsd .LFC3(%rip), %xmm0`) or loaded with one `movss`/`movsd`; zero is `xorps`. Covers `rfLoadXmm` (an immediate operand), `rfFloatX`/`rfFloat` (an immediate b operand) and the `R_GTOX` immediate path. This is POOLING, not hoisting: nothing is kept in a register across a loop (there are only 4 float temporaries and 6 float variable registers, and the memory operand is cheap). The MASM/Intel path (`isWindows()`, which means MASM only; windows_gnu uses AT&T like Linux) is unchanged and does not use the pool.
2. **Step 4, indexed addressing (LowerOrderGenerator `IndexedAccessPass` + Codegen `R_LDXI`/`R_STXI`).** `R_LEA %tX &sym %vK scale ; ... ; R_LDX n %xK %tX` becomes `R_LDXI n %xK &sym %vK scale`, and `R_STX n %tX %xK` becomes `R_STXI n &sym %vK scale %xK`. Scale must equal the access width (4 for f32, 8 for f64); integer fusion stays at scale 8 / size 8 only. RIP-relative addressing cannot carry an index register, so one `lea sym(%rip), %r15` (`RF_SCRATCH`) remains in front of each indexed access: the second `lea` is what goes away. `MAX_GAP` 12 -> 40 (a float statement has more lines between the lea and its consumer); `DEST` and `PURE_DEF` learned `R_FBIN`, `R_FCMP`, `R_XTOG` and the xmm forms so the scan can pass them.
3. **`FloatTempPass`.** A chain defined by `R_FBIN` (f32 or f64) whose only reader is a store (`a[i] -= a[i]*c`: `R_FBIN SUB 8 %t1 %y0 %y1 ; R_ST 8 %t0 %t1`) had `floatUse == false` and stayed in general registers; it now counts as a float chain (`R_FBINX` + `R_STX`). A plain `R_LD` -> `R_ST` copy is deliberately left alone.
Result (Linux, 2-core VM, same session, previous tree vs this tree, two rounds alternating, best of both, "full" rows, seconds, outputs identical on every row): n-body natural f64 0.76 -> 0.64, plain f64 0.70 -> 0.62, loop f64 0.68 -> 0.65, arr f64 0.71 -> 0.62, scalars f64 0.70 -> 0.56, plain f32 0.69 -> 0.65, loop f32 0.77 -> 0.63, arr f32 0.66 -> 0.60, scalars f32 0.69 -> 0.56; fannkuch naive 4.45 -> 4.21, optimized 4.51 -> 4.53; spectral naive 0.48 -> 0.32, optimized 0.26 -> 0.24. So the float programs gain 4-20%, spectral-norm naive 32%, and fannkuch (integer code, no float constants, no float arrays) is unchanged within the ~10% noise of this VM. Details: `benchmarks/RESULTS.md` ("Steps 3 and 4"), `benchmarks/charts_before_after_step3_4.html`, `benchmarks/before_after_step3_4.json`, the full all-language charts in `benchmarks/charts.html`.
Verified (Linux only): new `tests/floatpool_test.caspien` (18 checks: f64 and f32 expressions with six constants, a constant as the LEFT operand, zero, twenty distinct constants, constants reused in a loop, large/small/dyadic values, compares with immediates, call arguments and returns, globals, the same numeric value as f32 and as f64 in one function, negative constants as operands) and `tests/floatindex_test.caspien` (11 checks: static `f64[5]`/`f32[5]`/`f32[3]`/`f64[3]` arrays, variable-index read and write, `a[i] -= a[i]*c`, two arrays in one statement, nested loops, square in place, source arrays untouched), each identical with everything on, everything off, and the shipped config; new `tests/floatindex_check.sh` (36 hand-made register-form LOB cases for `IndexedAccessPass`, env `LOB_CP` for a mutant classpath); the generated text confirms the forms are used (floatpool test: 48 pool entries, 75 pool users, 2 `xorps`; `R_LDXI`/`R_STXI` present in the n-body `loop` programs: 345 lines; the `arr` programs index with literals and have none); old-tree against new-tree sweeps of 62 programs (every `tests/*.caspien` plus the n-body variants) under four configs (everything on, the shipped Linux config, register-form only, everything off), 0 differences; `tests/callee_saved_probe.sh` PASS; `lob_passes_check.sh`, `rangecheck_check.sh`, `regform_globals_check.sh`, `regvars_share_check.sh` pass. windows_gnu (mingw) builds of the two new tests and two n-body programs assemble and link; not run (no Wine here).
Mutation checks on the new code: 25 LowerOrderGenerator mutants (wrong scale accepted/rejected, width not equal to scale, address or source not the expected register, operand order of the fused lines, `MAX_GAP` 12 and huge, wrong or missing `DEST` entries, `PURE_DEF` entries, dead-after check, the `floatUse` fix, `R_FBIN` size handling) and 13 Codegen mutants (pool key without the width, no 32-bit mask, zero always/never, `.long` for 8-byte entries, label reuse, `movss`/`movsd`, scale forced to 8, store direction, base register). Every mutant that changes behaviour is caught (mostly by `floatindex_check.sh`, by the runtime tests for the Codegen ones). Survivors, all equivalent or conservative: scale test widened/narrowed to values that never occur, the `R_LDX`/`R_STX` address-equals-x tests (x can only sit in that position), `R_FCMP`/`R_STX`/`R_XTOG` missing from the scan tables (they only make the pass fuse LESS), the f32 mask in the pool key and load (a negative float literal is built with `NEG_FLOAT`, so no immediate has the top bit set; the negative-constant check was added to find out), zero loaded from the pool instead of `xorps`, and `movsd` in place of `movss` for an f32 pool load or an `R_LDXI` (reads 4 bytes more, the low half is the value).
Not verified / caveats: Intel/MASM and Wine (not run; the pool is not used on the MASM path and the Intel forms of `R_LDXI`/`R_STXI` are unexecuted); the shipped `toolchain.config` has the register-form switches that matter off, so the shipped default sees little of this gain; only sizes 4 and 8 with scale = width are fused for floats; integer arrays are not touched by this step (scale 8 / size 8 only, as before); the pool is not register hoisting, so a loop still re-reads its constants from memory (an L1 hit).
Test-writing notes: a negative float literal is `NEG_FLOAT`, not an immediate; `for i in 0..6` (not `range(6)`) for an integer literal bound.
New in the benchmarks: Lua 5.4 and LuaJIT rows (`lua5.4`, `luajit`; the same `nbody.lua`, `fannkuchredux.lua`, `spectralnorm.lua` run on both, outputs identical to C), every language has its own colour in `charts.html`, and the rows now name the language in words as well. Python, Ruby and PHP reference ports exist in the benchmark folders but are not in the harness.

## New: step 2b -- the constant-bounds `match i in/into <array>` test no longer builds the range on the stack (`RangeCheckFusionPass`, always on, no switch)

Asked for ("i approve this step, please implement") after step 2 left the `loop` n-body variants 2.4x behind the rest. Reading the source showed the first diagnosis was imprecise: it is not "proof data" but the RUNTIME bounds test every plain `match i in/into <array>` compiles to (the `assume match` variants `plain`/`natural` skip it). The inner loop of `nbody_loop*.caspien` nests seven such matches (not four, my earlier figure was wrong), each rebuilding the literal range every iteration:
`PUSH 8 $i / PUSH 8 K0 / PUSH 8 K1 / POP $r 16 / POP $l 8 / PUSH $l / PUSH $r / GT_EQ_INT 8 / PUSH $l / PUSH $r+8 / LT_INT 8 / AND 1 / CMP / JMP @L` ($r = K0, $r+8 = K1, $l = copy of the index). Besides the five stack operations and two stores, the `PUSH 8 $i` kept the loop counters out of variable registers (a plain `PUSH` makes a hinted slot unrenameable in `RegVarPromotionPass`).
New `LowerOrderGenerator/.../RangeCheckFusionPass.java`, called in `LowerOrderGenerator.java` right after `StrengthReductionPass` and BEFORE `RegisterFormPass` (stack-form text). It rewrites exactly that 14-line shape to `PUSH i / PUSH K1 / LT_INT 8 / CMP / JMP` when K0 is 0 (an unsigned index is always >= 0), else to `PUSH i / PUSH K0 / GT_EQ_INT 8 / PUSH i / PUSH K1 / LT_INT 8 / AND 1 / CMP / JMP`; the normal passes then give `R_BRC LT 8 %v0 #5 @L` and the counters can be promoted. Conditions (anything else is left untouched): index = `PUSH 8 $slot`, bounds = decimal literals 0..2^31-1 (so they fit an immediate), both compares UNSIGNED (`GT_EQ_INT`/`LT_INT`, not the `S*` forms), the range popped as 16 bytes and the copy as 8, every `PUSH` reads the slot it should, the shape ends `AND 1 / CMP / JMP`, and none of the three temp slots ($r, $r+8, $l) is mentioned on any other line of the same function (function = `FUNC_START`..`FUNC_END`; the whole text if there is none), because the two stores are dropped. Layout fact the pass relies on: `POP $r 16` stores the first-pushed word at the LOWER address (K0 at $r, K1 at $r+8).
Result (Linux, 2-core VM, same session, previous tree vs this tree, two rounds alternating, best of both, "full" rows, seconds, outputs identical on every row): n-body loop f64 1.64 -> 0.68 (-58%), loop f32 1.59 -> 0.70 (-56%); every other row within the ~10% noise (natural f64 0.73 -> 0.72, plain f64 0.66 -> 0.70, arr f64 0.64 -> 0.66, scalars f64 0.64 -> 0.66, plain f32 0.63 -> 0.67, arr f32 0.71 -> 0.67, scalars f32 0.64 -> 0.66, fannkuch naive 4.23 -> 4.16, optimized 4.56 -> 4.12, spectral naive 0.49 -> 0.51, optimized 0.24 -> 0.25). The loop variants now run as fast as `plain`. All 35 `POP .. 16` pairs of that program's low-order text are gone. Details: `benchmarks/RESULTS.md` ("Step 2b"), `benchmarks/charts_before_after_step2b.html`, `benchmarks/before_after_step2b.json`.
Verified (Linux only): new `tests/rangecheck_test.caspien` (21 checks, hand-computed: arrays of length 5, 3, 1 and an f64 array of 4 against limits below, at and past the length; a sum through the proof; two proofs of different lengths around one index; a write proof; an index that changes between proofs; and literal ranges `match k in 3..7` / `1..5` / `4..4` / `7..3` / `4000000000..4000000003`, which give a NON-ZERO lower bound) identical with everything on, everything off, register-form only and the shipped Linux config, and the generated low-order text has no `POP .. 16` left; new `tests/rangecheck_check.sh` (25 hand-made stack-form LOB cases, incl. the non-zero lower bound and start 1, signed/unsigned mixes, bound 2^31 vs 2^31-1, variable bound, wrong slots, wrong pop sizes, no CMP/JMP, temp slot used elsewhere in the same function (before, after, upper word) vs in another function, two consecutive checks, shared temps, no `FUNC_START`, truncated text; env `LOB_CP` for a mutant classpath); old-tree against new-tree sweeps of 60 programs (every `tests/*.caspien` plus the n-body variants), 0 differences under everything on, the shipped Linux config, register-form only and everything off (the pass is always on, so everything off is included); `tests/callee_saved_probe.sh` PASS. windows_gnu (mingw) builds of the new test and two n-body programs assemble and link; not run (no Wine here).
Mutation checks on the new code: 29 mutants (dropping the lower bound always / for start <= 1 / never, no or partial temp-slot scan, wrong upper-word offset, signed compares accepted, no bound limit, swapped bounds or compare operators, dropped AND, wrong skip count, relaxed shape checks, wrong default function range, ...). The first round left 7 survivors, all of them missing LOB cases (start 1, one signed compare, wrong slot in the second compare or the lower compare, no CMP, no JMP); cases were added for each and those mutants re-run: all 29 are caught. After the runtime test gained the non-zero-bound checks (see below) the mutation run was repeated in full: 9 of the 29 are also caught by the runtime test (the three lower-bound-dropping mutants, swapped bounds, swapped compare operators, dropped AND, both wrong skip counts, wrong second index load), the rest only by the LOB check. The last two (`noCmpCheck`, `noJmpCheck`) pin a strictness choice rather than a semantic need: the rewrite would be equivalent without them.
Not verified / caveats: Intel/MASM and Wine (not run); (correction: I first wrote that a non-zero lower bound could not be produced from source and was covered only by LOB cases; that was wrong, I had mistaken the `r.exe` binary name of the shipped windows_gnu target for a compile failure. `match k in 3..7` compiles and runs, and the runtime test covers it.) `match` on a `dyn` array (runtime length) and the `for` loop's own `i < end` test do not take this path; the redundant repeated checks (the seven matches of a loop level test the same index against the same 5, so seven identical `R_BRC LT %v0 #5` follow each other) are NOT removed, a possible follow-up. Steps 3 (hoist/pool float constants) and 4 (indexed addressing `R_LEA`+`R_LDX`) are NOT started.

## New: step 2 of the gap-to-C plan -- `RegisterFormPass` fuses global scalar reads (`PUSH n <global>` -> `R_LD`) and flushes instead of rolling back in front of a call when that is faithful (no new switch; active with `deferred-operands: on`)

Asked for ("yes, please implement", approving the step 2 proposed at the end of the section below). Two changes in `LowerOrderGenerator/.../RegisterFormPass.java`:
1. **Global scalar reads.** `GLOBAL`/`ALLOC_STATIC` names (dotted alias names excluded) are pre-scanned into `globalNames` (cleared at the start of every `run()`, so one pass instance can be reused). `PUSH n <global>` used to return 0 from `fusePush` (neither a slot nor a literal), so every read of a static scalar stayed a stack push. It now becomes an eager `R_LD n %tD &sym` plus a T entry of width n, marks the region risky, and is declined when no temp is free (`freeTemps() < 1`) or when an `ATOMIC_SWAP` follows 2 lines later (the backend looks at offset 2: `PUSH addr / PUSH newValue / ATOMIC_SWAP`). `FloatTempPass` turns the `R_LD` into `R_LDX` for floats. The load is eager, so a global read BEFORE a call that changes the global keeps its old value (tested).
2. **Faithful flush.** In a risky region with entries remembered, an unfusable line used to roll the whole region back to its original lines. Now, when `flushIsFaithful(line)` (mnemonic `CC_START`, or ending in `_FLOAT` or `_INT`) and `st.plainWords()` (every remembered entry has size 8), the model is flushed (entries written back as stack form, `R_PUSH`/`R_PUSHA` when a temp is above) and the earlier fusion is kept. Narrow and f32 entries, unknown mnemonics and the end-of-run case still roll back as before. This is what fused the natural n-body: an inlined `sqrt` call sat under a pending `e = e - ...`, which rolled back 42 lines of stack form.
Part (b) of the plan (array element read-modify-write through a computed address "falls back to stack form"): checked and found NOT to be the hot cause. Array RMW already fuses in the hot loops; the 238-line stack runs in the hand-unrolled programs are cold `energy` code that needs more than 4 temps (only `%t0..%t3` exist). Nothing was changed for (b).
Result (Linux, 2-core VM, same session, previous tree vs this tree, two rounds alternating, best of both, "full" rows, seconds, outputs identical on every row): n-body natural f64 0.70 -> 0.68, plain f64 0.68 -> 0.64, loop f64 1.60 -> 1.61, arr f64 0.66 -> 0.66, **scalars f64 1.27 -> 0.66, scalars f32 1.20 -> 0.64** (about -47%), plain f32 0.67 -> 0.65, loop f32 1.51 -> 1.52, arr f32 0.65 -> 0.62; fannkuch naive 4.13 -> 4.03, optimized 4.18 -> 4.29; spectral naive 0.48 -> 0.48, optimized 0.25 -> 0.25. Only the scalar-statics programs gain; everything else is inside the ~10% noise floor. C -O2: n-body 0.26 s, fannkuch 3.38 s, spectral 0.25 s. The natural variant's LOB shrank from 1982 to 1702 lines but its time did not change (bound by `sqrt` latency and the dependency chain). Details: `benchmarks/RESULTS.md`, `benchmarks/charts_before_after_step2.html`, `benchmarks/before_after_step2.json`.
Verified (Linux only): new `tests/regform_globals_test.caspien` (26 PASS, hand-computed: global reads of u8/u16/u32/u64/s64/f32/f64, six nested global reads (more than 4 temps), read-before-call order, `e - g*sqrt()/g` shapes, array loops) identical with `deferred-operands` off, register-form only, everything on and the shipped Linux config; new `tests/regform_globals_check.sh` (14 hand-made LOB cases through one shared `RegisterFormPass` instance; env `LOB_CP` for a mutant classpath); old-tree against new-tree sweeps of 59 programs (tests and n-body variants), 0 differences under everything-on, the shipped Linux config and register-form only (`tc.off` skipped: the pass does not run with deferred operands off); `tests/callee_saved_probe.sh` PASS. windows_gnu builds (mingw) of the new test and two n-body programs assemble and link only (no Wine here, they were not run).
Mutation checks on the new code: 14 mutants, 12 caught (most only by the LOB check script; `noTempCheck` also by the runtime test). Survivors: `noAtomicGuard` is equivalent (the risky-region rollback also covers `PUSH g / PUSH K / ATOMIC_SWAP`) and `loadAfterAnchorMissing` only affects line metadata. The runtime test alone is the weaker net (it misses, for instance, a wrong load size for narrow globals, because narrow ops re-truncate); the LOB check catches those. Bug found by the mutation run: my first guard looked at `idx+1` for the `ATOMIC_SWAP`; the backend looks at `idx+2`, fixed.
Not verified / caveats: the Intel/MASM target and Wine (not run at all for this change); the shipped `toolchain.config` has `float-variables-in-registers` and inlining off, so the shipped default sees little of the gain; the n-body programs read N from env `NBODY_N` (without it they segfault, old tree and new alike). Remaining stack-form residues, NOT fixed (candidates for a later step, need approval): in the `loop` variants every iteration's `match` bounds proof does `PUSH i / PUSH 0 / PUSH 5 / POP 16 / POP 8` before an `R_BRC` (four per inner iteration, probably why `loop` is ~2.4x the others), and the cold `energy` code that needs more than 4 temps. Steps 3 (hoist/pool float constants) and 4 (indexed addressing `R_LEA`+`R_LDX`) are NOT started.
Test-writing notes: globals need `unsafe` for every access from non-atomic code (including checks and function bodies); f64 locals need an explicit `let:<mut f64>`; the n-body programs need `NBODY_N` in the environment.

## New: step 1 of the gap-to-C plan -- f64 variables promoted through `R_MOV` slots, and promoted variables SHARE registers when their lifetimes do not overlap (`RegVarPromotionPass`, no new switch)

Asked for ("your plan seems solid, follow it, being sure to give me the re-measurements and zips as you go, but if you then decide to deviate on step 2 ... explain the proposed changes and wait for approval"). Two changes, both in `LowerOrderGenerator/.../RegVarPromotionPass.java`, both only active when `variables-in-registers` (and for floats `float-variables-in-registers`) are on:
1. **Fix A, f64 slots.** An f64 slot is mentioned as `R_MOV 8 %tD $slot` / `R_MOV 8 $slot %tS|#imm` (8-byte move), which `mentionOkX` rejected, so every f64 variable stayed in memory and "float variables in registers" did nothing for f64 programs. `mentionOkX` now accepts `R_MOV` on a float slot and `rewrite()` turns it into `R_GTOX`/`R_XTOG` (width 8).
2. **Fix B, register sharing (`shareRegisters` + `colour`).** Before, the top N candidates by weight got a register each and every other candidate stayed in memory. Now, ONLY when `intCands.size() > intLimit || xCands.size() > XVAR_COUNT` (otherwise the original top-N path runs, byte-identical output), candidates are coloured heaviest first onto the registers; two candidates share a register only if they are never simultaneously live and neither is written while the other is live-out (a dead write still clobbers). Liveness is a backward dataflow over the register-form text. Successors: fallthrough; `JMP` (conditional if the previous line is a bare `CMP`); `R_BRC`/`R_BRF` (target plus fallthrough); an edge from every `CALL`/`INVOKE`/`EXTERN_CALL`/`SLEEP` to every label referenced by a non-jump line (catch/landing pads, `PUSH_LABEL`). No fallthrough after `RET`, `RET_FLOAT`, `R_RET`, `R_RETF`, `GT_UNWIND`, `EXIT`, `EXIT_THREAD`, `FUNC_END`. Defs: only `R_ST` (operand 2), `R_MOV` with the slot as destination, and the float "ADDR ... ASSIGN" pair (def at the `R_POPX`/`R_GETRETF` line); every other mention is a read. Variables live at function entry (parameters) interfere pairwise; floats of different width never share. One `R_XVAR %xK $home n` per register (home = heaviest occupant's slot); the backend spills xvars around calls and reloads after `@catch_` labels as before.
Result (Linux, 2-core VM, same session, fastest of 3, everything-on config, seconds, previous tree -> this tree): spectral naive 0.666 -> 0.469, spectral optimized 0.665 -> 0.248, fannkuch naive 4.701 -> 4.011, fannkuch optimized 4.127 -> 4.148 (unchanged), n-body natural f64 0.768 -> 0.673, n-body scalars f64 1.474 -> 1.216. C -O2 on the same machine: n-body 0.26, fannkuch 3.38, spectral about 0.25. The "off" rows (which this change cannot touch) moved between -10% and +3% between the two runs, so about 10% is the noise floor of this VM: the fannkuch naive gain (-15%) is at that level, the spectral and f64 n-body gains are clearly above it. Outputs identical on every row. Details and the other languages' rows: `benchmarks/RESULTS.md`, `charts.html`, `charts_before_after.html`.
Step 2 evidence (hand-patching `sqrtsd` in for `call sqrt` in the generated asm): n-body natural 0.657 -> 0.640 s, scalars 1.22 -> 1.24 s, i.e. no measurable gain, so inline sqrt is NOT the next thing to do. What dominates the remaining gap in the scalar n-body is stack-form float code: `RegisterFormPass.fusePush` returns 0 for `PUSH n <global name>` (global scalar value reads: `parseSlot` and `parseIntLiteral` both fail), so those reads stay `PUSH 8 x0 ... SUB_FLOAT 8 ... ASSIGN`, and an array element read-modify-write through a computed address (`ADDR 40 v / PUSH idx / LOOKUP_ARRAY_LHS / DEREF / ... / ASSIGN`) also falls back to stack form. Static count in the scalars n-body between two `call sqrt`: 118 asm instructions, 42 push/pop and 37 `movq`. Proposed next step (not started, waiting for approval): fuse those two shapes in `RegisterFormPass`.
Verified (Linux only): new `tests/regvars_share_test.caspien` (15 checks: float loops, float values across calls, a throw caught inside a float loop, conditional initialisation, f32 and f64 together, integer loops, two floats live across a loop, integer catch) identical with every switch off, everything on and inlining off; the generated LOB shows sharing firing (8 float candidates in 6 registers); new `tests/regvars_share_check.sh` (19 hand-made LOB cases through `RegVarPromotionPass(true,true)`, incl. disjoint lifetimes share / all-live-at-once do not, loop back edge, live-at-entry pairs, dead write clobber, unwind edge through `PUSH_LABEL`, stack-form `CMP`+`JMP`, back edge by `R_BRC` and by `JMP`, f32 against f64, one `R_XVAR` per register, pair-assignment def location; env `LOB_CP` runs it against a mutant classpath); old-tree against new-tree sweeps with 0 differences: 48 programs everything on, 48 everything off, 49 with the shipped `toolchain.config`; `regvars_test`, `regvars3_test`, `regvars_float_test`, `f64_test`, `floattemps_test` identical off/full/no-inlining; `tests/callee_saved_probe.sh` and `check_callee_saved.py` pass. Mutation checks on the new code: 17 mutants; caught: the three store rules, every-mention-is-def, no-unwind-edges, no-entry-livein, dead-write-ignored, no-width-check, brc-no-fallthrough, jmp-no-target, brc-no-target, jmp-always-uncond, share-trigger-and, pair-def-at-addr, colour-ignores-taken, liveness-no-use. Survivors, all conservative-only (they can only make the pass share LESS, never miscompile): jmp-always-cond, ret-falls-through, pair-def-skipped. No-unwind-edges, no-entry-livein, dead-write-ignored and no-width-check are caught only by the hand-made LOB driver, not by the runtime programs.
Not verified / caveats: Intel/MASM target and Wine (not run here at all for this change); the shipped `toolchain.config` still has `float-variables-in-registers: off` and inlining off, so the shipped default sees little of this gain, the figures above are for the everything-on configuration; the f32 n-body rows were not re-measured (their outputs are identical off/full; the "output differs" line a filtered run prints for f32 is the known f32 operation-order difference against C, plus the `--only` filter dropping the C f32 reference); other languages' benchmark rows are from the earlier same-day run.
Test-writing note: a mutation harness needs a timeout (a miscompiled loop hangs); `runt.sh` uses `timeout 20`.

## New: the remaining fannkuch-redux gaps to C -- no lower-bound test in `for`, jump cleanup, one `lea` fewer for global arrays (always on, no switch); more variable registers NOT done

Asked for ("can you address these: every `for` loop still tests the lower bound, `if c { break }` still produces `jne; jmp; jmp` chains, global arrays still get a double `lea`, and there are only three variable registers").
1. **Lower bound dropped from the `for` test** (`MembershipLoweringPass.buildRangeCheck`). The per-iteration test of a compiler-generated loop (`$for_range_N` holder) was `i >= range.start && i < range.end`. The counter starts at `range.start`, is immutable in the body and only the loop increments it after `i < end` held, so `i >= start` is always true; the test is now `i < range.end`. An inverted range (7..3) and an empty one (5..5) still run zero times, because `start < end` is what `i < end` tests on entry. Every other `in` check is untouched.
2. **Jump cleanup** (`LowerOrderGenerator/.../JumpCleanupPass`, new, runs after `BranchFusionPass`): up to 8 rounds of three local rewrites on register-form text: a `JMP`/`R_BRC` directly after an unconditional `JMP` is dropped; a `JMP` to the label that follows (only labels between) is dropped; `R_BRC C a b @L1 ; JMP @L2 ; @L1:` becomes `R_BRC C' a b @L2 ; @L1:` with C' the opposite condition (EQ/NEQ, LT/GT_EQ, LT_EQ/GT, SLT/SGT_EQ, SLT_EQ/SGT). Labels are never removed; only `JMP`/`R_BRC` lines are deleted. `if c { break }` went from `jne; jmp; jmp` to one `je`.
3. **Indexed global access** (`LowerOrderGenerator/.../IndexedAccessPass`, new, runs after `JumpCleanupPass`; `X86Backend` `R_LDI`/`R_STI`): `R_LEA %tX &sym %vK 8 ; ... ; R_LD 8 %tD %tX` becomes `R_LDI 8 %tD &sym %vK 8`, and the store form `R_STI 8 &sym %vK 8 src`. The backend still needs one `lea sym(%rip)` (RIP-relative addressing cannot carry an index register; absolute `sym(,%r13,8)` is not safe on mingw/win64) but the second `lea` is gone: `movq (%rax,%r13,8), %rax`. Conditions: global base, index in a variable register, scale 8, 8-byte access; every line between the lea and its consumer is a known register-form mnemonic that neither mentions `%tX` nor writes the index register; `%tX` is dead afterwards. Only the 8-byte shape is fused because no program in `tests/` or `benchmarks/` produces any other (narrow arrays do not take the register-form path); the backend also contains the 1/2/4-byte forms and they are UNEXECUTED.
4. **More variable registers: not done, on purpose.** The only registers left are r8/r9/r10: caller-saved, and r8/r9 are argument registers on SysV (r10 holds the INVOKE target and float stash). Using them means spilling around every call and catch label and banning functions with 5 or more call arguments, INVOKE or inline assembly. That is a substantial change to the calling code with a real correctness risk, for a gain I expect to be small next to the three items above (the fannkuch hot loops are call-free, so they are limited by register count, but I did not build it to find out). Left as the remaining known gap.
Result (Linux, 2-core VM, same harness and session, previous tree -> this tree, fastest of 3; `benchmarks/RESULTS.md`, `charts.html`, `charts_before_after.html`): fannkuch n = 11 optimized full 4.28 -> 3.96 s, naive full 5.04 -> 4.48 s, everything off 13.47 -> 11.59 s and 14.33 -> 12.08 s (C -O2 3.20 s); n-body (5M) natural f64 off/full 3.73 -> 3.24 / 0.85 -> 0.77 s, scalars f64 1.78 -> 1.66 / 1.48 -> 1.41 s; spectral-norm unchanged within noise (full rows 0.65 -> 0.67 and 0.60 -> 0.64 s, i.e. +3% and +7%, not investigated; off rows -7% and -1%). The machine was faster in this session than in earlier ones, so absolute figures in older sections of this file are not comparable. Output identical on every row.
Follow-up check on the Optimizer's dead control flow removal (asked for, after the JumpCleanupPass bug): that pass was not at fault (the bug was in my new LOB-level pass), and it handles `PUSH true/false; CMP; JMP` as a unit. New `tests/deadflow2_test.caspien` (9 checks: `if true { break }`, `if false { break }`, constants inside `for`, early return behind constants, else-if chain with a constant arm, nested constants, an exit inside the else of a false, folded compares); all 48 programs of `tests/` compiled and run with every optimisation off vs the pass alone vs the pass plus folding/elision/shifting vs those plus dead-function and unused-declaration removal: identical output, no differences (Linux). The pass fires (new test, HOB 486 -> 393 lines, 360 with folding). Two deliberately broken versions (true/false swapped; the always-jump case dropping the jump) are both caught by `deadflow_test`/`deadflow2_test`.
Bug found and fixed by the benchmark run (not by my first test sweeps, which ran with register-form code only): in STACK form a `JMP` directly after a bare `CMP` is the conditional jump, and `JumpCleanupPass` treated it as unconditional, deleting the `break` jump behind it; fannkuch with every optimisation off segfaulted. `JumpCleanupPass` now never treats a `JMP` right after `CMP` as unconditional (`followsCmp`), `tests/lob_passes_check.sh` has three stack-form cases, and the old-tree-against-new-tree sweep was re-run with every optimisation off and with the shipped settings (Linux target): 47 programs, no differences.
Verified (Linux only): new `tests/loopjump_index_test.caspien` (23 checks: empty, inverted, single-trip and ordinary `for` ranges with variable bounds; `if c { break }` for `==`, `!=`, `<`, `<=`, `>`, `>=` unsigned and `==`, `>`, `>=`, `<`, `<=` signed; a nested break; global `u8/u16/u32/u64` arrays filled and read back through a variable index; an immediate store with neighbours checked) and `tests/lob_passes_check.sh` (40 hand-made register-form and stack-form cases for both passes, including every inverse-condition pair); all 46 programs of `tests/` compiled and run with everything on, old tree against new, identical output; fannkuch n = 9 and n = 11 checksums unchanged. Mutation checks on the new code (each inverse condition, dead-jump rules, the next-label rule, the label guard, index-write check, dead-temp check, label-as-dead rule, store source equal to the address temp, pure-definition rule) are all caught by the new test, the LOB check or fannkuch; the survivors are equivalent (`%t` test on a load destination, `R_RMW` removed from the write table only makes the pass more cautious). Not verified: the Intel/MASM target and Wine (the new `R_LDI`/`R_STI` Intel forms are unexecuted), the 1/2/4-byte indexed forms (no program reaches them).
Test-writing notes: a narrow integer store needs `wrap:<T>(x)` (plain `as` only widens); `assume match` needs `unsafe`; arrays of exactly 8 bytes are a known crash, so the test uses `u8[10]`, `u16[5]`, `u32[5]`, `u64[4]`; bounds read from a `let static` keep the loops from being unrolled or folded after inlining (literal bounds did, and then no indexed access was left to test).

## New: fannkuch-redux speed-up -- strength reduction, compare-and-branch fusion, word-wise range copy, three register variables, loop counters declared in several loops (always on, no switch)

Asked for ("ok so for the Fannkuch-redux execution time, we lag behind more than we do for the nbody ... what more can we do?", then "please do" to items 1-4 of my proposal). The disassembly of the hot loop showed: `/` and `%` by a constant power of two went through `div` (20-40 cycles); every `for` iteration built its `start..end` range with `rep movsb` (microcoded, slow for 16 bytes); every compare materialised a 0/1 in a register and branched on it; the loop counter and range bounds lived in memory (a store-forwarding chain on `inc`). None of the changes has a config switch; they are checked by comparing output against a baseline tree built without them.
1. **Strength reduction** (`LowerOrderGenerator/.../StrengthReductionPass`, new): unsigned size-8 `x / 2^n` -> `SHR n`, `x % 2^n` -> `BITS_AND 2^n-1`, only with a decimal literal immediately before the operator (signed ops untouched). `RegisterFormPass` and `X86Backend` learned `SHR` (constant count 0..63) and `BAND`; `BITS_AND` is also a stack-form case.
2. **Word-wise construction store** (`X86Backend.assignConstructionBlock`): 8..64 bytes, multiple of 8 -> per-word `mov` through rdi; other sizes keep `rep movsb`.
3. **Compare-and-branch fusion** (`LowerOrderGenerator/.../BranchFusionPass`, new, runs last; `R_BRC` in the backend): an 8-byte compare feeding `R_BRF` becomes `cmp` + one conditional jump of the inverse condition; `a && b` of two compares becomes two jumps. Immediate first operands are swapped with the condition mirrored; two slots go through r15.
4. **Register variables** (`RegVarHintPass`, `RegVarPromotionPass`, `X86Backend.RF_VAR_REGS`): a name declared several times with the same type is now eligible (every `for i in ...` declares `i` again, all sharing one slot, so loop counters never had a hint); a function that never uses `%t3` and has no `R13_R14_USERS` mnemonic may promote a third variable (`%v2` = r12, the same register as the fourth temporary).
Result (Linux, 2-core VM, fastest of 3; `benchmarks/RESULTS.md`): fannkuch n = 11, everything on: naive 14.5 s -> 7.0 s, optimized 14.9 s -> 6.0 s (C -O2 3.0 s, Java 3.3, Go 3.0, Rust 2.9, Bun 4.3, Node 5.8); everything off 34.9 -> 33.0 and 32.5 -> 29.5 s. n-body (N = 5M, only `natural f64` and `scalars f64` re-measured) 1.98 -> 1.71 s and 2.55 -> 2.54 s with everything on. Spectral-norm unchanged within noise (5.75 / 1.09 / 2.33 / 0.77 s), because its hot loop has no integer division or fusable compare. Steps in the ladder at n = 10, everything on: 1.25 s -> 0.95 (shift/mask) -> 0.73 (word copy) -> 0.65 (branch fusion) -> 0.58 (third register + counters).
Verified (Linux only): new `tests/strength_test.caspien` (25 checks: power-of-two divide/modulo incl. 1, 2^31, 2^32, unsigned and signed compares with constants on either side, slot against slot, range loops, `&&` pairs) and `tests/regvars3_test.caspien` (three hot variables around a call; one counter name in several loops), each identical with the features' surroundings on and off; all 45 programs of `tests/` compiled and run with everything on, old tree against new, identical output; `tests/callee_saved_probe.sh` PASS (sentinels in rbx, r12-r15 survive) and `check_callee_saved.py` clean. Mutation checks on the new code (wrong shift count, wrong mask, every condition code, unmirrored operand swap including signed, slot-against-slot memory operand, oversized immediate, dropped branch) are all caught; the survivors are equivalent or unobservable (`/ 1` as a shift by 0; a wider mask limit; the BRF-temp equality guard; BAND commutativity, since the mask is always the second operand). A first round of that mutation run was wrong (stale class files, a stale test binary, hangs not counted) and its "no survivors" result was discarded; it was redone with a harness that rebuilds both components, removes the binary first and counts PASS as well as FAIL.
Not verified: the Intel/MASM target and Wine (none here; every new instruction form was written for AT&T and only run there); the n-body rows other than `natural f64`/`scalars f64` and the C/Rust/Go/Java/Node/Bun figures were not re-measured; still about 2x behind C -O2 on fannkuch (the section above this one removes the lower-bound test, the jump chains and the double `lea`; only three variable registers remain).

## New: struct member reordering (`struct-member-reordering: on|off`, `StructMemberReorderingPass`, shipped `off`)

Asked for ("ok i want you to implement the struct reordering, and then rerun the benchmarks only if it will make a difference"); the stub that was flagged earlier is now real, and it lives in the Optimizer (the stub's own place), not the front end. Per struct it moves the members so the largest alignment comes first (stable inside a class), which gathers the padding at the end (`Mixed{u8,u64,u8,u32}` 32 -> 24 bytes; `___type` stays first), and rewrites in the same run every place that lays the struct out positionally: the `STRUCT_MEMBER/STRUCT_PADDING` declaration, stack and static-of-local literals (`ADDR x / PUSH classId / member units / STACK_LOCK gaps / ASSIGN`), `new`, array literals of structs and RVO (`PUSH $ret_dest raw_mut_S` ... `ASSIGN`). A construction is parsed as a sequence of member value units (PUSH / arithmetic / casts, each a complete postfix expression) by a DFS split with type matching and an exactly-one-parse rule; trailing padding is a real trailing `STACK_LOCK`. Named accesses (`x.m`), static `GLOBAL g.member` lines and the LowerOrderGenerator offsets need nothing: they follow the new declaration. `SizeofResolutionPass` runs right after and sums the new declaration, so `sizeof(S)` follows.
Left alone (the whole struct): any struct with `extends` or extended by another, any decorator (`@lock` etc.), a member that is a struct, array, pointer, dynarray or range (plain scalars only), a `raw` pointer to the struct anywhere (punning, `memcopy`, C interop see the layout), a construction site the parser cannot place with certainty (unknown line, unaccounted class-id push), and a struct named by `STRUCT_PIN`. `let static n = sizeof(S)` is folded by `BytecodeEmitter.foldStaticConstant` from the front end's own layout, so that fold now emits `STRUCT_PIN S` first; the pass pins that struct (the pin set survives the outer-loop rounds) and always strips the line, switch on or off, so it never reaches the LowerOrderGenerator.
Consequences to know: member initialisers are evaluated in the new order (they are pure expressions, so nothing visible changes); the layout is no longer the declaration order, so code that relies on it through a `raw` pointer is exactly what the blocker exists for.
Config: `VariableConfig` key `struct-member-reordering` (8-arg constructor), both other `CompilerConfig` parsers skip it, `toolchain.config` and the three component copies document it, shipped off.
Verified (Linux): new `tests/structreorder_test.caspien` (35 checks: stack, RVO, `new`, array literal, static literal, copies, member writes, pinned static sizeof equals the runtime value, extends and raw-pointed structs untouched) identical with the switch off and on except the last printed line (`sizeof Mixed` 32 off, 24 on); new `tests/structreorder_hob_check.sh` (9 hand-made HOB cases: rewritten stack/computed-member/`new`; left alone for raw pointer, unparseable construction, `STRUCT_PIN`, extends, array member; off keeps layout and strips the pin); all 44 programs of `tests/` compiled and run with the switch off and on give identical output apart from that one line. Bug found while writing it and fixed: the pin set was forgotten after the first outer-loop round (the pass strips the lines in round one), so a pinned struct was reordered in round two; the pass instance now keeps the pins.
Not verified: the Intel/MASM target, Wine (none in this environment, the runs are the Linux target), a fuzzer.
Fixed afterwards (asked for): an RVO `return S{a: 1, b: x + 1, c: 3, d: 4}` (a computed member plus narrow members) segfaulted with every switch off. Cause: `AddressLoweringPass.findStructLiteralAssigns` only recognised a literal stored through `ADDR v T`, so the RVO store (`PUSH $ret_dest raw_mut_S / classId / members / ASSIGN mut_S indeterminate_S mut_S`) got no layout tokens and Codegen copied the pushed words blindly once a member was computed. The finder now also accepts the `$ret_dest` form, so the store carries `m8 m1 p7 ...` like a stack literal; `tests/structreorder_test.caspien` uses `b: x + 1` again (the crash is reproduced without the fix).
Benchmarks: no Caspien benchmark (n-body, fannkuch-redux, spectral-norm) declares a struct, so the pass cannot change their code; they were not re-run.

## New: `sizeof` of a struct is resolved by the Optimizer (`SizeofResolutionPass`, always on), struct member reordering moved to the top of the outer loop

Asked for ("Shouldnt we just fold sizeof as the last step and make sure the thing is in one giant loop?", then "go ahead please"). Before, the front end folded `sizeof(Struct)` (and the scale of `raw Struct` pointer arithmetic) itself, from a layout that ignored nothing but was fixed at that moment, so `sizeof` could never follow a layout change made later, and a struct member reordering pass could not be run safely. Now the front end emits a symbolic `SIZEOF TypeName <type>` for a struct, an array of structs and the scale of `raw Struct` `++`/`+`/`-`/pointer difference (`Token.sizeofStruct`, `Token.pointerScaleStruct`); primitives, pointers and ranges still fold to `PUSH n` there. The Optimizer's new `SizeofResolutionPass` turns each `SIZEOF` into `PUSH n` from the STRUCT declarations the program carries (sum of STRUCT_MEMBER sizes and STRUCT_PADDING, `___type` first; an unknown struct is an `IllegalStateException`, so the LowerOrderGenerator never sees `SIZEOF`). Outer loop order is now: structMemberReordering (still a no-op stub) -> sizeofResolution -> structUnpacking -> inner loop (constantFolding, variableElision, variableShifting) -> deadControlFlow -> deadFunction -> unusedDecl -> loopUnrolling -> functionInlining, so `3 * sizeof(S)` folds to one `PUSH`. `let static n = sizeof(Struct)` is a data line, not code, so `BytecodeEmitter.foldStaticConstant` folds it from the front end's own layout (struct member reordering handles this with `STRUCT_PIN`, see the section above). Details: README `### sizeof-resolution`, `Optimizer/CLAUDE.md`, `ASTGenerator/CLAUDE.md`.
Verified (Linux): `tests/sizeof_stride_test.caspien` (8 checks: sizeof with padding, nested struct, array of structs, generic struct, `raw P ++` stepping equals sizeof, `p + n`, pointer difference, a static initialiser) and `tests/sizeof_hob_check.sh` (6 cases, see README).
Not verified: the Intel/MASM target.

## New: variable allocation reordering (`variable-allocation-reordering: on|off`, shipped `off`)

Asked for ("please do", after the owner dropped struct member reordering for now and asked whether this one was simpler). `VariableAllocationReorderingPass` was a no-op stub; it now sorts each function's leading ALLOC run by alignment (largest first, which removes the padding address lowering leaves for a declaration order like `u8, u64, u8, u64`) and, inside one alignment class, by use weight (nearest rbp first). `gt_routine_address` / `gt_error_message` stay the first two slots, parameters stay ahead of the locals, functions with inline assembly are left alone. Details, what stays put and why: README `### variable-allocation-reordering`, `Optimizer/CLAUDE.md`. Config: `VariableConfig` gained the key; both other `CompilerConfig` parsers skip it; `toolchain.config` documents it (off).
Struct member reordering (`StructMemberReorderingPass`) was discussed and put off by the owner at that time (built afterwards, see its section at the top): reordering the declaration is easy (accesses go by name), but construction pushes values positionally, `___type` must stay first, `extends` children need the parent's prefix, `@lock` needs its field first, `sizeof` was folded by the front end before the Optimizer ran (now symbolic and resolved by `SizeofResolutionPass`, see the section above, which removes that obstacle), and unsafe punning, `memcopy` and C interop see the layout. It is still a no-op.
Verified: new `tests/allocreorder_test.caspien` (6 checks; `mixed`'s frame 192 -> 160 bytes) identical at off/on, `tests/allocreorder_hob_check.sh` 10/10, 50-program sweep with everything on including this switch at aggressive and conservative identical, Wine spot checks identical, 11 of 11 broken versions caught (details in README). Not verified: the Intel/MASM target.

## Fixed: `return ?f(x)` drops no catch any more; rows of 3/5/6/7 bytes build and read correctly; `DOT` of a >8-byte field that is not a whole number of words (`BytecodeEmitter`, `TypeChecker`, `X86Backend`, no switch)

Asked for ("Unrelated open bugs, unchanged: `return ?f(x)` drops its catch, plus the construction bug for 3/5/6/7-byte rows. The `DOT` shrink for a >8-byte field that isn't a whole number of words ... can you fix this?"). Three pre-existing bugs, all reproduced with every switch off before fixing.
1. `return ?f(x)` (front end). The `?` sugar expands in `Parser.gatherKeywordBlocks` into a synthetic `try` token, which for `return ?f(x)` sits as the single `.sub` of the `return` KEYWORD. `BytecodeEmitter.tryNodeOf` (and so `collectTryCatchNodes` / `collectHoistedAllocs` / `collectTryBlockLabels`) only looked for a try at statement level or as the right side of `=`, so the catch was never hoisted and the call was emitted plain: the `?catch(e){...}` was ignored and the throw simply propagated. `tryNodeOf` now also finds a non-block `try` that is the sole operand of a `return` (through `mut`/`imut`), and `TypeChecker.statementContainsRealTryCatch` got the same `return` case (a `try{}` block whose only real try is a `return ?f(x)` was rejected). The stdlib's `return ?new ...` sites now run their catches too (they used to behave as "always re-throw"); `tests/stdlib_test.caspien` still passes 60/60.
2. Rows of 3/5/6/7 bytes (`X86Backend`). No single x86 load/store exists for those sizes, and the backend used an 8-byte move, so a construction such as `let:<mut u16[3][2]> w = mut [a0, a1]` (each row a separately pushed word) stored garbage before any read. New helpers `isOddSize`, `storeOddSize`, `storePiece`, `rotReg` store an odd size as exact 4/2/1-byte pieces (rotating the register and restoring it); used by `storeSizedToAddr`, `storeSizedToFrame`, `storeConstructionOrPlainPush` (both the first-store and the non-zero-offset branch) and by `pushBlockFromFrameConstructionAware`, which now copies piecewise through rax (a construction run stores each field at its final place BELOW the current rsp, so `push rcx` or `rep movsb` (rdi/rsi/rcx) during a run corrupted earlier fields). `LOOKUP_ARRAY` reads an odd-size element that straddles two words with `shrd`, masks an odd-size element of a small in-register array with shl/shr, and the multi-word branch calls the new shared `extractWordsFromReversedBlock`.
3. `DOT` of a >8-byte field that is not a whole number of words (`X86Backend`). It used the same `total*8 - size` shrink the row read had. The field address is now computed like a `LOOKUP` (word index plus byte offset inside the word, block layout: word k at rsp + (T-1-k)*8, natural byte order inside a word) and the words are extracted with `extractWordsFromReversedBlock`. Found on the way: `assignBlock` copied a non-word-multiple size wrongly (word index is now `words-1-i`, the last partial word is stored with its exact size).
Verified (Linux): new `tests/return_try_test.caspien` (14 checks: `return ?f`, `return try f(x) catch(e){...}`, re-throw, the catch sees the message, `return ?new`, an owns local plus throw, `return` in a loop, a catch ending in `continue` inside `try{}`), `tests/array_row_odd_test.caspien` (14: 3/5/6/7-byte rows built from variables and literals, every element read back, read as a call argument), `tests/dot_wide_field_test.caspien` (11: 10-, 12- and 20-byte fields, direct and through an array of structs); each identical with every switch off and at aggressive; full sweep of 49 programs (every `tests/*.caspien`, the n-body ports, Hello World), off vs inlining-only vs everything on, at aggressive, balanced and conservative, all identical.
Also verified: `tests/dot_wide_field_test.caspien` grew to 14 checks (a wide-field read as the 5th call argument keeps the 4th argument in rcx; a 20-byte array assigned into an existing variable leaves both neighbours untouched); the three new tests plus `array_row_read_test`, `inline_owns_test`, `struct_literal_nested_test`, `array_member_shapes_test` and `stdlib_test` run on `windows_gnu`/Wine with everything off and everything on (aggressive) and give identical output (the only FAIL, in `stdlib_test`, is the known Wine "process: write mode" popen quirk, same with everything off); `tests/inline_hob_check.sh` 55/55. Mutation checks: 12 deliberately broken versions of the new logic (`tryNodeOf` return case never matching / never returning; `TypeChecker` return case removed; odd store dropping a piece / storing 7 bytes as 4+2; `extractWordsFromReversedBlock` with a zero shrd count / rcx not restored / rsp raised one word too far (Linux form) / destination words not reversed; `assignBlock` with the word index not reversed / the last partial word written whole) are each caught by at least one test; the mutant that removes the rotate-back of the source register in `storeOddSize` is NOT caught (equivalent today: no caller reads the register after an odd store). Not verified: the Intel/MASM target (`windows_gnu` runs the Intel-syntax path of the new code, MASM assembly of it was never done), the win64 form of the rsp-raise mutant (only the Linux form was mutated).
Next, asked for by the owner: `VariableAllocationReorderingPass` (built, see the next section). `StructMemberReorderingPass` stays a no-op stub (owner: "nvm struct reordering for now").

## Changed: calls that move an owns variable into the callee (`take(o2)`) are now inlined (`FunctionInliningPass`, no switch)

Offered at the end of the throw/catch inlining round, then asked for ("do that if that makes more sense to do"). Passing an owns variable into a call emits, inside the call bracket right after the argument's `POP ARGn`, a null-out of the variable (`ADDR o / PUSH null / ASSIGN`, one triple per moved owns argument). `tryInline` saw those lines as leftover argument code after the last `POP` and refused the site, so every call that moved an owns variable stayed a call. Now lines after the last `POP` are accepted if they are only such null-out triples (`moveNullOuts`); they are emitted right after the parameter copies and before the callee body, which is the same order as the call (argument read, variable cleared, callee runs). A null-out between two arguments was already carried inside the next argument's segment and works. Anything else after the last `POP` (any other line, or a store that is not `PUSH null`) still stops the inlining. The callee's own destruct of its owns parameter (`GT_DESTRUCT b / ADDR b / PUSH null / ASSIGN` at its end) needed no change: the parameter is a renamed local of the caller after inlining.
Verified: new `tests/inline_owns_test.caspien` (10 checks: take, two owns arguments around a scalar, a callee that only drops, a throwing callee that moves its argument with and without the throw, take in a loop, each moved variable refilled and moved again; the language rejects any read of a moved variable, so "the variable is empty" cannot be tested from source) identical with inlining off and at conservative/balanced/aggressive (9 calls of take/take2/drop/takeThrow remain off, 9 at conservative, 5 at balanced, 0 at aggressive); valgrind: no errors and the same heap totals (26 allocs, 25 frees, only the ghost table's own block in use) off and at aggressive; `tests/inline_hob_check.sh` now 55 checks (new o1-o5: a null-out is inlined and kept, two triples, another line after the POP refused, a non-null store refused, a null-out between two arguments).
Not verified: the Intel/MASM target.

## Changed: functions that throw, catch, re-throw or call throwing functions are now inlined; found and fixed a pre-existing inliner crash (`FunctionInliningPass`, no switch)

Asked for ("ok if you could just continue thru those 2 please", steps 2 and 3 of the plan after step 1 below). What changed, all in `Optimizer/.../FunctionInliningPass.java` (Codegen untouched this round): `@throws` is an accepted decorator; a callee that STAGES its own calls (`ADDR gt_routine_address / PUSH_LABEL @gt_callsite__f_N|@catch_N / ASSIGN` before each call) is accepted; `THROW x` (a no-op marker) is dropped; each `GT_UNWIND [MSG]` of the callee becomes `JMP X` where X is the label the CALLER staged for this call (its landing pad or its `@catch_N`; `stagedLabel` now accepts both, the staging triple is still cut). The callee's `gt_routine_address` / `gt_error_message` names are not renamed and not captured: the inlined lines use the caller's own slots (the message write lands where the callee's frame would have delivered it), and the caller must have the slot. The callee's own landing pads and hoisted catch blocks are copied with renamed labels (`@catch_`/`@gt_callsite__` prefixes kept). Result: in `tests/inline_throw_test.caspien` at aggressive 62 calls become 16 (the rest sit in `main`, inside argument lists of `check(...)` where nothing is inlined once an argument register is loaded, plus the one float refusal), and the messages, catches, `continue`, re-throws and owns cleanup give the same answers.
Still calls, on purpose: an unwinding callee whose staged label is a `@catch_` when the caller or callee has a float variable (Codegen's catch entry `R_XRELOAD` reloads float variables from their home slots, which only calls write, so a jump in from inlined code would restore stale values); a callee that defines a `@catch_` label, at a site with operands of an enclosing expression beneath it; a callee that unwinds where the call site has no staged label (old-form pads of non-throw owns programs); a throwing callee with no `RET` where a value is expected (`selfThrower`). Known and accepted (same as before inlining): a catch ending in `continue` leaks one operand-stack word per throw.
Bugs found by the sweeps and fixed on the way: (1) a callee with a catch of its own inlined in the middle of an expression (`acc += tryOne(i)`) left the words its statement had pushed at the call (`ADDR r`) on the operand stack after an unwind, so the code after the copy consumed the wrong operands (`regvars_test` "throw caught in a loop" gave a wrong sum) -- refused at that shape. (2) PRE-EXISTING, NOT from this round (the shipped build crashes on it too, reproduced with a 9-line non-throwing program): a callee that CALLS something and ignores the result leaves that `PUSH_RET` word on the operand stack (a frame of its own hides it, `RET` resets rsp); inlined with operands beneath the site (`t += noisy(1)`, or a value that must sit directly above an address) the word landed between them and crashed (`stdlib_test` at aggressive after step 2 made `testLibc` inlinable). New `Callee.balanced` (an operand-stack depth simulation over the body, every mnemonic must have a known effect, 0 at every `RET` and at the end) decides: an unbalanced callee is inlined only with nothing beneath the site, or in result-variable form with one address beneath (the address is pushed again after the copy). New checks in `tests/inline_test.caspien` (45 now) fail without it.
Verified: `tests/inline_throw_test.caspien` (new, 17 checks: catcher, 1- and 2-level re-throw with the message seen at the top, translated message, two throwing calls in one function, a catch with `continue` in a loop, an owns local destructed in a landing pad, a float variable around a catch), `throw_message_test`, `inline_throwprog_test`, `inline_test`, `regvars_test`, `regvars_float_test`, `stdlib_test` identical off and at conservative/balanced/aggressive on Linux, and (aggressive, all switches on) on `windows_gnu`/Wine; full sweep of 45 programs (every `tests/*.caspien`, the n-body ports, Hello World), off vs inlining-only vs everything on, at aggressive, balanced and conservative: all identical (three earlier failures, described above, were found and fixed by this sweep); `tests/inline_hob_check.sh` now 49 checks (unwinding callees t1-t5: throw becomes a `JMP` to the staged pad / catch, `THROW` dropped, float caller at a catch refused, no staged label refused; s5/s6: slot-using callee with the caller having the slots; h1-h3: a callee that leaves a word behind stays a call with operands beneath and is inlined otherwise; the `l2` size case's body had to be made stack-balanced because the balance rule now applies to it). Mutation checks: nine deliberately broken versions of the new logic (unwind jumps to the normal end; unwinding callee accepted with no staged label; float/catch guard removed; catch callee accepted at `cls == 2`; balance check removed; caller-slot check removed; `GT_UNWIND` not counted as unwinding; `@catch_` not accepted as a staged label; `PUSH_RET` not counted as a push) are each caught by at least one of `inline_throw_test`, `inline_test`, `regvars_test`, `stdlib_test`, `throw_message_test` or the hob check (the float guard only after `floatAround` was changed to a hot float loop whose catch reads the float: with the guard removed the catch sees a stale value; the `@catch_`-not-accepted mutant only costs optimisation, not correctness, and is caught by hob case t2).
Not verified: the Intel/MASM target; a catch-ending-in-`continue` loop that throws many times under inlining (the leak is accepted, no test of the depth it can reach); float variables around a catch are only PROTECTED (refused), not made to work.
Done afterwards (next section): the owns-move null-out lines at a call site (`take(o2)`).

## Changed: leaf functions in throw/`new`/owns programs are now inlined; found and fixed a Codegen crash on 2D array reads (`FunctionInliningPass`, `X86Backend`, no switch)

Asked for ("please", to step 1 of the plan for optimizing across `@throws`): in any program that uses throw/`new`/owns, every function carries `ALLOC gt_routine_address` and `ALLOC gt_error_message`, and the inliner refused every callee for it, so even a non-throwing leaf like `check` or `show` was never inlined. Now `analyse` treats those two `ALLOC`s as dead scaffolding: a callee whose body never mentions either name is accepted, and the two slots are NOT copied into the caller (the caller has its own). A body that mentions them (staging `ADDR gt_routine_address / PUSH_LABEL / ASSIGN` for a call it makes, a message write, THROW, unwinding) is still refused (`why` = the mnemonic, or "uses gt_routine_address"). So: leaves are inlined; callees that call something or throw are still calls (steps 2 and 3 of the plan, not done).
Bug found by the sweep and fixed (pre-existing, in Codegen): `let ok = mut grid[1][2]` on a 2D array whose rows are not a multiple of 8 bytes (a `u32[3]` row is 12) segfaulted with everything off. `LOOKUP_ARRAY` shrank the array block by (total - elemSize) bytes instead of whole words, leaving rsp 4 bytes off and writing 4 bytes past the old block over the pending store address. It never showed before because such reads were passed straight to a call; inlining `check("..", g[0][0] == 1)` turns the argument into a store. Fix: shrink by (totalWords - elemWords) * 8.
Second Codegen fix, asked for right after ("fix that"): the MIDDLE element of row 1 of a `u32[3][2]` (`grid[1][1]`, `g1.g[1][1]`) read a wrong value (pre-existing, also with the old Codegen and a register argument). Block layout is word k at rsp + (T-1-k)*8 with natural byte order inside a word, so a row that starts mid-word (byte 12) is not contiguous in memory. The multi-word branch of `LOOKUP_ARRAY` now builds each result word with `shrd` from the two source words (count 0 for an aligned element leaves it unchanged), writes it into the slot of source word j, then raises rsp; %rcx is stashed in r13 around it (r13/r14 were already backend scratch for LOOKUP_ARRAY). The two related bugs left open then (3/5/6/7-byte row construction; `DOT` of a >8-byte field that is not a whole number of words) are FIXED in the section at the top of this file.
Verified: new `tests/inline_throwprog_test.caspien` (10 checks; leaves inlined, throw and catch around them, message preserved) identical off/conservative/balanced/aggressive on Linux and on `windows_gnu`/Wine; new `tests/array_row_read_test.caspien` (15 checks, incl. every element of a 2D array, `grid[1][1]`, a read as a later call argument) passes with inlining off and aggressive on Linux and Wine, and crashes with the old Codegen; `tests/inline_hob_check.sh` is 36 checks (new s1-s4: a scaffolded leaf is inlined and its slots are not copied; bodies that write either slot stay calls; a mutant that drops the mention check fails s2 and s3). Sweep of `tests/*` + n-body + Hello World, off vs inlining-only vs everything on: 44 programs (every `tests/*.caspien`, the n-body ports, Hello World), aggressive and balanced: all identical (before the Codegen fix, `struct_literal_nested_test` crashed at both; conservative was 43/43); Wine, aggressive: struct_literal_nested_test, throw_safe_test, throw_message_test, inline_test, inline_argreg_test, recursion_base_test, stdlib_test identical (the one `stdlib_test` FAIL, "process: write mode", is the known Wine popen quirk and is the same with inlining off).
Not verified: the Intel/MASM target.

## Fixed: a `catch(e)` now sees the message that was thrown, from any frame up the chain (`GT_UNWIND MSG`, no switch)

Asked for ("fix the message propagation gap first"), after the owner asked how the routing address and error message are held in the HOB. Finding: both are ordinary `ALLOC`s (`gt_routine_address`, and `gt_error_message` in any program that uses `throw`), written by plain `ADDR/PUSH/ASSIGN`. The compiler's comments claimed the backend copied the message one frame up on unwind; it did not (nothing in `GT_UNWIND`). A `throw` wrote the thrower's own slot and a `catch` read its own never-written slot, so `catch(e)` from a callee's throw printed garbage; only a same-frame catch (e.g. the out-of-memory check after `new`) worked. Fix, three parts: (1) `ArgToAllocLoweringPass` keeps `gt_error_message` right behind `gt_routine_address`, ahead of the parameters, so it is at rbp-16 in every frame (it used to shift with the parameter count); (2) `BytecodeEmitter.gtUnwindLine()` emits `GT_UNWIND MSG` (a new operand) when the program uses throw; (3) `X86Backend` `GT_UNWIND MSG` loads `-16(%rbp)` into rax before the epilogue and stores it into the caller's `-16(%rbp)` after it, then loads `-8` and jumps as before (Intel form for windows). Every hop copies, and call-site landing pads use the same `GT_UNWIND`, so re-throws chain. `RegVarHintPass` now also refuses `gt_error_message`.
Verified: new `tests/throw_message_test.caspien` (9 checks: message seen 1, 2 and 3 frames up, a different throw site, a catch that throws a different message, thrown and caught one frame apart, no throw, and a second round proving earlier messages do not leak) passes with inlining off and at conservative/aggressive on Linux and on `windows_gnu`/Wine; with the copy disabled the test crashes; `callee_saved_probe.sh` and `check_callee_saved.py` pass; the 41-program sweep off vs on is identical. Not verified: the Intel/MASM target (the `windows_gnu` Intel-syntax path is what Wine ran).
(The `return ?f(x)` gap noted here is FIXED, see the section at the top of this file.)
Consequence for inlining functions that throw (still not done): the message write would land in the caller's own slot, and with this fix the un-inlined program now delivers the same message, so the two behaviours agree.

## Changed: recursion-to-loop conversion also checks the base case AFTER the loop (`TypeChecker.lowerRecursiveFunctionToLoop`, no switch)

Asked for directly ("make it so", after the owner proposed it: "is the cleaner case not to just have the base case also be after the loop?"). The converted `for` loop runs (initial range length) times, so a `@recursive` function whose range shrinks by ONE per step needed one more "call" than that to reach its base case, fell off the loop and returned an uninitialised slot (garbage; a different garbage with inlining). Now a copy of the validated base-case block (`match r is base{...}`, the required first statement, barred from containing a self-call) is placed after the loop: it is what the next recursive call would have done on entry, and it is certain to hold because every step strictly shrinks the range (`within` is a strict-subrange proof). The loop stays bounded. The copy is made with the new `Token.deepCloneChecked()` (reflection-based deep copy of a checked tree; `deepCloneRaw` cannot be used after checking). Shrink-by-two recursions were never affected. Observed semantics worth knowing: an odd-length range shrunk from both ends ends on an inverted range (e.g. 4..3), which still passes `within` and is a base case, so steps = ceil(length/2).
Verified: new `tests/recursion_base_test.caspien` (16 checks: shrink by one at several lengths and starts, empty range, shrink by two incl. odd lengths, a base case that declares a local and computes, two self-call sites) identical with inlining off and at every preset on Linux, and on `windows_gnu`/Wine; with the copy removed 12 of the checks fail. Not verified: the Intel/MASM target. Known pre-existing gap, unchanged: two self-call `return`s in ONE block (the second would still run after the first is turned into a reassignment); use `if`/`else` as the test does.

## New: configurable function inlining (`function-inlining: off|conservative|balanced|aggressive` + `inline-max-callee-lines|depth|growth`, shipped `off`) and the cross-language benchmark

Asked for directly ("function inlining for the HOB ... configurable ... the most aggressive form inlines all functions"; don't inline async, function pointers (`call()`/`INVOKE`) or C functions; do inline recursive calls after the recursion-to-loop conversion, which was confirmed to happen in the ASTGenerator (`TypeChecker.lowerRecursiveFunctionToLoop`) before the Optimizer). Implemented as the Optimizer's `FunctionInliningPass` (was a no-op stub) with `InlineConfig`; both other `CompilerConfig` parsers skip the keys. Design, presets, exclusions and range-parameter handling: `Optimizer/CLAUDE.md`, README `### function-inlining`, `toolchain.config`. Also fixed on the way: `DeadControlFlowRemovalPass` dropped a label definition that a kept line still referenced (only visible once inlining produced the shape).
Bug found and fixed after delivery: a call inlined inside another call's argument list after an earlier argument was already in an argument register (`printf("..", f(x))`, `check("name", f(x) == v)`) let inlined code clobber those registers (backend block copies/divides use rdi/rsi/rdx/rcx as scratch); such sites are no longer inlined (`expandRange` `regsLoaded`). Also fixed: `@inline` is an accepted front-end decorator and my inliner refused any function carrying it; it is now a non-blocker (not a forcing hint). New `tests/inline_argreg_test.caspien` (7 checks; the broken version fails it), `inline_hob_check.sh` now 31 cases.
Verified: `tests/inline_test.caspien` (43 checks) identical off vs on at conservative, balanced and aggressive, alone and with every other switch, on Linux and on `windows_gnu`/Wine; `tests/inline_hob_check.sh` (31 checks); a 39-program sweep with everything on at three presets, all identical to off. Not verified: the Intel/MASM target, a fuzzer (the owner does not want one for the HOB optimizer), mutation testing of the inliner.
Known limits: callees that take a struct/array/dynarray by value are not inlined (callees that throw or make calls in throw/`new`/owns programs are inlined since the section above); `@inline` is accepted but does not force inlining; the recursion-to-loop conversion quirk described in the next section is FIXED.
Benchmark: `benchmarks/nbody/caspien/nbody_natural_f64.caspien` is the n-body written naturally (small functions, plain loops); `benchmarks/nbody/bench.py` builds and runs every Caspien program (f64 and f32 variants) with everything off and everything on against C, C++, Rust, Go, Java, Node and Bun and measures time, peak RSS (through a tiny C launcher, because `wait4` on a child of Python reports Python's inherited peak), compile time and size; `charts.py` draws `charts.html` (green Caspien, red bare metal, purple Java, blue JavaScript). N = 5M: natural n-body 7.5 s off, 2.0 s full; C -O2 0.41 s; every output matched its C reference. Results and caveats: `benchmarks/nbody/RESULTS.md`.
FLAGGED FOR LATER (owner asked for this to be recorded): `StructMemberReorderingPass` is still a no-op stub (`VariableAllocationReorderingPass` has since been built, see its section at the top of this file); the Intel/MASM target is unverified (accepted for now).
The shipped `toolchain.config` still has every optimizer switch `off`.

## Update: arrays of structs, multi-dimensional arrays and inline structs inside a struct literal with a computed member (same files, no switch)

Asked for ("arrays of structs or multi-dimensional arrays as members of a literal that also has a computed member ... best to know if there are gaps"). Five bugs found by tests and fixed: layout tokens now come from a recursive walk of the literal's value units (`AddressLoweringPass.layoutFromUnits`/`valueFromUnits`; `splitValueUnits` matches a nested `NEW` to its own class id, `findNewRunStart` likewise); the `DOT` read of a field of at most 8 bytes computes its address from the word-reversed block (`X86Backend`); `LOOKUP_ARRAY` carries `t8` (target is a by-value array of exactly 8 bytes) and `ra` (result is a by-value array under 8 bytes). Test `tests/struct_literal_nested_test.caspien` (60 checks) passes on Linux and Wine, switches off and on; all 10 broken versions caught (one needed an extra test, heapNested2). n-body ports (8) verified on Wine on/off. Nested array literals (`[[1,2,3],[4,5,6]]`) now adapt to `T[3][2]` (TypeChecker flattens the rows). Known gaps: the Intel/MASM target cannot be assembled here and its generated text mixes NASM- and MASM-style operands (`dword [rax]`, `movzx rax, rax`), so it is unverified and probably not valid as it stands (pre-existing). Details: README `### struct literal stores`.

## Update: array-of-structs-with-array-member reads, word-straddling array members, local narrow array literals with computed elements, mixed literal/variable array literals

Asked for ("test these shapes"). Fixed and covered by `tests/array_member_shapes_test.caspien` (48 checks): `DOT` of a fixed-array field of at most 8 bytes carries `ra` (LowerOrderGenerator) and Codegen assembles it with `shrd` across two struct words and tags it for the next `LOOKUP_ARRAY`; `AddressLoweringPass.findArrayLiteralAssigns` gives a local narrow array literal with a computed element the `a<e>x<n>` repack token (Codegen repack condition is now `line.size() > 4`, any size); `TypeChecker.checkArrayLiteral` lets literals adopt a typed integer element's type. Details and remaining gaps: README `### struct literal stores`.

## New: struct literal store fix (Codegen + LowerOrderGenerator, no switch)

Asked for after the struct unpacking work ("the struct bug first"): a stack struct literal with a narrow member and a computed member value crashed (all optimizer switches off). Fixed the way heap `new` already worked: `AddressLoweringPass.findStructLiteralAssigns` marks a literal's top-level `ASSIGN` and appends the layout tokens (`m<bytes>`/`p<bytes>`); `X86Backend.emitAssignRepack` repacks the pushed words into the destination. The all-plain-push fast path is untouched. Second round (asked for directly after the first): narrow-element array members (`u32[3]`) in such literals, stack and heap `new`, via the `a<elem>x<n>` layout token; then all the gaps that turned up were closed on request ("we must fix all the current gaps before continuing"): array read through an owning heap pointer (Codegen `DEREF` now tags the pushed aggregate), the `%rcx` clobber by small-array element extraction and variable shifts inside a call's argument list (this was the real cause of the earlier "windows u8/u16 struct array read" crash), same-typed array members written differently, and nested literals (per-member form decided by `splitValueUnits`, no more counting). Details and verification: README `### struct literal stores`; test `tests/struct_literal_test.caspien` (crashes before, passes after; 7 broken versions caught).

## New: unused declaration removal (`unused-declaration-removal: on|off`, shipped `off`)

The follow-up the owner named after dead function removal ("removing now-unused `extern`s, globals and strings ... that would be the next step"). `UnusedDeclarationRemovalPass`, a new class with its own
switch (`VariableConfig` key, skipped by the ASTGenerator/LowerOrderGenerator config parsers), runs after `DeadFunctionRemovalPass`. Deletes unreferenced `EXTERN`, `GLOBAL`, `ALLOC_STATIC` and `STRING`
declaration lines; keeps the backend's fixed-name externs and `ghost_table`; no-op without `main` or with `ASM_START`. Details: README `### unused-declaration-removal`, `Optimizer/CLAUDE.md`. Verified:
`tests/unusedecl_test.caspien`, `tests/unusedecl_hob_check.sh`, 34-program sweep on vs off, Wine spot checks, 7 of 8 broken versions caught (the 8th is equivalent).
No compiler changes were made for recursion at any point (recursion stays `@recursive` only); the only compiler sources touched for this and the previous pass are the config skip lists.

## New: dead function removal (`dead-function-removal: on|off`, shipped `off`)

Asked for as "the dead function removal step -- does nothing if there's an INVOKE, otherwise check all the CALL sites and remove a function that isn't called anywhere". Built as
specified, with three additions the bytecode requires: roots (`main`, the four `gt_*` hooks the backend calls by name, functions with special decorators), address-taken functions count as used
(`PUSH f ...`), and no-`main` programs are left alone. It uses reachability from the roots (so an unentered call cycle and a self-only-recursive function are removed too) rather than a bare "no call
site" test -- a small deliberate strengthening. `VariableConfig` gained the key. Verified: `tests/deadfunc_test.caspien`, `tests/deadfunc_hob_check.sh`, the full sweep with everything on (README).

## New: dead control flow removal (`dead-control-flow-removal: on|off`, shipped `off`)

Asked for as "work on the dead control flow removal"; scope agreed with the owner: the `PUSH true|false / CMP / JMP` pattern and removal of the dead branch it leaves, nothing broader
(no general unreachable-code sweep). `DeadControlFlowRemovalPass` was a stub; `VariableConfig` gained the key. Dead code is decided by reachability before vs after the rewrite (only lines
that became unreachable are removed). Details: README `### dead-control-flow-removal`, `Optimizer/CLAUDE.md`. Verified: `tests/deadflow_test.caspien` (17 checks) at on/off, all other tests + n-body with
everything on vs off identical; 4 of 10 broken versions change the result, the other 6 are masked by redundant safeguards or cannot occur (README).

## New: struct unpacking (`struct-unpacking: on|off`, shipped `off`)

Asked for as "fill in the struct unpacking step ... get the HOB optimizer filled in as much as we can". `StructUnpackingPass` was a stub. Implemented for local structs with
scalar members only, all mentions being construction / member read / member write (details: README `### struct-unpacking`, `Optimizer/CLAUDE.md`). Config: `VariableConfig` gained
`struct-unpacking`. Verified: `tests/structunpack_test.caspien` (11 checks) at on/off, all other tests + n-body with everything on vs off identical, 6 of 8 broken versions caught
(2 equivalent survivors). Found while doing this, since FIXED (next section): a pre-existing Codegen segfault for struct literals with a narrow member and a computed member.

## New: variable elision and variable shifting (`variable-elision: on|off`, `variable-shifting: on|off`, both shipped `off`)

Asked for from the owner's notes (elision: `PUSH x / PUSH 0 / ASSIGN` with x never assigned again and no pointer taken, replace all x by the constant;
shifting: a variable that would be elidable if each assignment were its own variable gets a fresh hoisted-allocation variable at each reassignment and
later references follow). Decisions taken with the owner: only straight-line splits (reassignment inside a branch or loop is not shifted, post-merge
references keep the original), loop-touched variables never elided or shifted, parameters/address-taken/struct fields skipped, new variables keep type
and mutability, build elision first and shifting on top, separate on/off switches. Code: `Optimizer/.../VarAnalysis.java` (shared mention scan, dominance and
loop checks), `VariableElisionPass`, `VariableShiftingPass`, `VariableConfig`. Details: `Optimizer/CLAUDE.md`, README `### variable-elision and variable-shifting`.
Verified: `tests/varelide_test.caspien` (20 checks) plus all other `tests/`, Hello World and the n-body ports identical with the switches on and off; 6 of 8 deliberately
broken versions caught (the two survivors are an unreachable case and a redundant guard, see README). The empty-value config quirk (misleading "no calling convention" error) is unchanged.

## New: constant folding (`constant-folding: on|off`, shipped `off`)

Asked for directly ("can we now move onto the constant infolding portion of the hob optimizer?"). Decisions taken with the owner: fold floats too
(f32/f64 `+ - * /`, compares, negation); "check the language" for zero-divisor/overflow cases (the language already rejects a literal `/ 0` and `% 0`
and requires a signed divisor proven `> 0`, so nothing special is needed; a float result that is inf/NaN/-0.0 is simply not folded); an on/off switch,
shipped off. Implemented as the Optimizer's `ConstantFoldingPass` (was a no-op stub) with `FoldConfig`. Operands must be literals sitting directly
before the operator; no constant propagation (variable elision's job). Details, exact operator list and limits: `Optimizer/CLAUDE.md`, README
`### constant-folding`, `toolchain.config`.
Verified: new `tests/constfold_test.caspien` (11 checks, folded literal expression vs the same operation through variables) at on/off on Linux and
`windows_gnu`/Wine; 17 deliberately broken versions of the pass (each operator, swapped operands, missing wrap, identity NOT/NEG, ...) all fail it (an
earlier version of the test missed the compare operators, which is why equal-operand compares were added); folding fired in seven other `tests/`
programs with identical output; all eight n-body ports identical (f64 energies match the reference `-0.169075164` / `-0.169086185`). Not done: a
fuzzer (the owner said none is needed for the HOB optimizer), the Intel/MASM target. Note the n-body programs have almost nothing to fold.

## New: configurable loop unrolling (`loop-unrolling: off|conservative|balanced|aggressive` + four numeric overrides, shipped `off`)

Asked for directly ("i want the compiler to be configurable, from a little bit of unrolling to full aggressive ... it is important not to pre-emptively reach into other optimizations - so if the bounds are a variable which cant be known on site, it cant be unrolled"; config style: "both", presets plus numeric overrides). Implemented as the Optimizer's `LoopUnrollingPass` (was a no-op stub) with `UnrollConfig`. Only a `for` loop whose trip count is in the range type text as literals is touched (`imut_range(0,4)`); variable bounds and `loop{}` never are. Full unroll for N <= full-max-trips, else partial (factor U, N >= 2U, remainder copied after). Details, presets and skip rules: `Optimizer/CLAUDE.md`, README `### loop-unrolling`, `toolchain.config`. `Compiler.java` now also writes `Optimizer/compiler.config`; both other `CompilerConfig` parsers skip the `loop-unroll*` keys.
Verified: new `tests/unroll_test.caspien` (16 checks) at off/conservative/balanced/aggressive on Linux and `windows_gnu`/Wine, plus 12 numeric-override combinations on Linux, malformed values rejected; other `tests/` programs and Hello World identical at every preset (only `array_match_test`, `regform2_test` and `unroll_test` contain a literal-bound `for`, so for the rest this checks little). No duplicate labels in any generated HOB. Not done: a fuzzer, the Intel/MASM target. Measured: n-body `nbody_plain`/`nbody_loop` (N = 1e6): no measurable change at any preset -- the hot inner loop has a variable bound (`for j in j0..5`), and getting the `nbody_arr` shape from the loop version would need constant propagation/folding, which this pass deliberately does not do. Reminder: `nbody`, `nbody_arr` (and f64 twins) are hand-unrolled SOURCE files, not compiler output; only `nbody_plain`/`nbody_loop` contain real loops.
Found, not caused by this pass: an empty value on any `toolchain.config` key (`variables-in-registers: `) produces the misleading error "declares no calling convention by that name".

## New: `f64` (double precision), at parity with `f32` -- registers, xmm temporaries, literals, libm, fuzzed

Asked for directly ("please add support for f64, do the fuzzer first"). `f64` is a second float type; there is no implicit f32/f64 mixing (`x as f64` widens, `wrap:<f32>(d)`/`wrap:<f64>(x)` convert, both lower to `FCONV`).
Syntax: `let:<mut f64> x = mut 0.0`, `let static:<mut f64> g = mut 1.5`, `let static:<mut f64[5]> a = mut [...]`; `extern sqrt(mut f64) mut f64`. Note the lexer reads `1.0e9` as `1.0 . e9`; write `1000000000.0`.
- **Literals.** A float literal is a `floatLiteral` TypeInfo that adapts to the slot it lands in (default f32): arguments, returns, assignment, both sides of arithmetic/compare, literal groups, negated literals, and float array literals (`floatArrayLiteral`, `floatArrayLiteralAdapts`). Operand adaptation is mutability-neutral (`unifyLiteralOperands`). Unary minus recurses through `adaptLiteral`, so `-(3.7 * 0.3)` is one f64 literal. `collectGlobal` checks a `let static:<T>` initializer against the bound T.
- **Lowering/codegen.** Width-explicit register-form lines: `R_XTOG`, `R_GTOX`, `R_LDX`, `R_STX`, `R_POPX`, `R_GETRETF`, `R_XVAR`, `R_FBINX` carry the size (4 or 8); `R_FBIN`/`R_FCMP`/`R_FARG`/`R_RETF` are used at size 8 for f64 too. `RegVarHintPass` emits `REGVAR name weight f` for both widths; `RegVarPromotionPass` and `FloatTempPass` keep f64 in `%x0..%x5` / `%y0..%y3` exactly like f32 (value in the low 64 bits). Statics initialise with 8-byte IEEE bits.
- **Bug fixed on the way (also hit f32).** A float scratch value was clobbered when a call sat in the middle of an expression (`g(a, 0.0 - f(x))`); fixed in this round (see the git-less history in the session notes: X86Backend/RegisterFormPass). Regression: `tests/f64_test.caspien` ("float scratch across a call").
- **Verified (Linux).** `tests/f64_test.caspien` (19 PASS + one printed line: 2^24+1, 0.1+0.2, expression shapes, sqrt, f32<->f64, 10x0.1, sum of squares over calls, statics, negated literal product, scratch across a call, more variables than registers) gives identical output with every switch combination (all off; vars on; float vars on; float temps on). A float fuzzer (`fuzzf.py`: the same random AST rendered as Caspien and as numpy IEEE, f32 and f64, compared across five switch combinations) ran f64 seeds 1-83 and f32 seeds 1-60, 101-160 (plus 200-225 on the final build), f64 seeds 1-40 re-run on the final build, with 0 mismatches. All four n-body ports match the C double reference at 1e6 and 5e7 steps.
- **Windows.** `tests/f64_test.caspien` also passes (19 PASS, `third=0.33333333333333331` via an f64 `printf` argument) on `windows_gnu` under Wine 9.0 with all switches off and all on.
- **Not verified.** The Windows fuzz run and n-body f64 timings on Wine, the `windows` (Intel/MASM) target, static f64 arrays as struct fields (alignment), struct initialisers with f64 members, off-switch byte identity for the f32 snapshot set after the latest TypeChecker edits.
- **Measured (Linux, 5e7 steps, best of 3, float regs on/off):** nbody_arr_f64 8.20/11.63 s, nbody_f64 15.58/17.51, nbody_plain_f64 20.50/26.10, nbody_loop_f64 30.40/35.29; C double -O2 2.74. f64 is 2-20% SLOWER than f32; it buys precision, not speed.
- **Test-writing gotchas.** A float compare, negation or `==` on an unproven float needs `unsafe` (or a `match ... finite`); reading a static from `main` needs `unsafe`.

## New: f32 expression temporaries in xmm registers (`float-temporaries-in-registers: on|off`, shipped off)

Asked for directly ("if you could please fix that gap"). Before this, the register-form pass held every temporary, floats included, in a general register (`%t0..%t3`), so each float operation did `movd` in, compute in an xmm scratch register, `movd` out. New `FloatTempPass` (LowerOrderGenerator, runs LAST, after `RegVarPromotionPass`, on the final register-form text) keeps float temporaries in xmm registers `%y0..%y3` (SysV xmm4-7, win64 xmm12-15).
Chains: a float temp is DEFINED by `R_FBIN OP 4` (-> `R_FBINX OP 4 %yK`), `R_LD 4` (-> `R_LDX %yK addr`) or `R_XTOG` (-> read the variable's register directly if nothing rewrites it before the last read, else `R_XMOV %yK %xJ`), and READ only by float consumers: an operand of `R_FBIN`/`R_FBINX`/`R_FCMP` (size 4), `R_FARG`, `R_RETF`, `R_ST 4` (-> `R_STX`), `R_GTOX` (-> `R_XMOV`). A chain is rewritten only if every read is such a consumer (all-or-nothing on the final text), only register-form lines sit between definition and last read, at most four chains are live at once (a chain may take the register another chain frees on its own defining line), and, inside a call bracket, the call passes fewer than 4 float arguments (SysV xmm4-7 are argument registers). Otherwise the temp stays a general register exactly as before, and a consumer may mix `%y`, `%x`, `%t`, slot and immediate operands freely (the backend loads each as needed).
Backend: `rfIsXvar` accepts `%y`, `xvReg` maps `%y` through `yPhys` (`YT_COUNT` = 4), new `R_XMOV dst src`. Register-form temps are never live across a label, jump or call bracket, so a `%y` register is never live across a call and needs no spill on SysV; on win64 xmm12-15 are callee-saved and `finishCalleeSaved` saves any it uses.
Gating: `float-temporaries-in-registers: on|off` (needs `deferred-operands: on`; independent of `float-variables-in-registers`), parsed by both `CompilerConfig`s. Off = output byte-identical to before.
Verified: `tests/floattemps_test.caspien` (15 checks: balanced and two-subtree expressions, right-nested, destination equal to the right operand of a non-commutative op, divide, compares of computed values, computed float arguments incl. nested calls, libm of an expression, immediates, statics read and written, loop mixing variables and temporaries, printf of an expression, a temporary live across a call) passes on Linux and `windows_gnu`/Wine with (variables off, temps off), (off, on) and (on, on); all 156 snapshot outputs (tests, Hello World, small benchmark, both targets) identical with everything off vs everything on (the one FAIL line, `stdlib_test` "process: write mode" on Wine, is the same with everything off); `check_callee_saved.py` clean on every build. Not run: the `windows` (MASM/Intel) target, a float fuzzer (none exists), Wine timing.
Measured (Linux, 2-core VM, N = 1e6, best of 5; float variables / temporaries): off/off nbody 0.325, nbody_arr 0.230, nbody_plain 0.528, nbody_loop 0.647 s; off/on 0.278, 0.131, 0.378, 0.622; on/off 0.334, 0.232, 0.447, 0.648; on/on 0.286, 0.141, 0.337, 0.569. Final energies identical in all sixteen runs. Remaining: array indices and hidden `for` variables stay in memory, only two integer variable registers, float ops on values that need >4 live temps or sit in stack-form regions (deeply nested expressions) fall back.

## New: float register variables -- hot f32 locals live in xmm registers (`float-variables-in-registers: on|off`, shipped off)

Asked for directly ("wouldnt promoting floats be the next logical step?" ... "go ahead with both windows and linux versions ... and then test the nbody problem again"). Same three stages as integer register variables; a float hint is `REGVAR name weight f` (Optimizer `RegVarHintPass` appends `f` when the allocated type is `*_f32`) -> `REGHINT off size weight f` (AddressLoweringPass) -> `RegVarPromotionPass` (second class: `XVAR_COUNT` = 6, `%x0..%x5`).
Registers: SysV xmm8-13, win64 xmm6-11; value = f32 in the low 32 bits. Scratch xmm stays SysV xmm14/15 / win64 xmm4/5 (`rfXmmA/B`); argument registers are untouched. `X86Backend`: `R_XVAR %xK $off` (declares the home slot, right after the frame `ALLOC`), `R_XTOG`/`R_GTOX` (move xmm<->GPR temp), `R_LDX`/`R_STX`, `R_FBINX`, `R_GETRETF` (call result straight into the variable), `R_POPX` (pop a stack-form value into the variable), `R_XRELOAD` (after every `@catch_N:` label).
Calls: SysV has no callee-saved xmm register, so `emitCallByName`/`emitCallIndirect` spill every xvar to its home slot before the call and reload after (`xvSpillAll`/`xvReloadAll`); a throw lands in a catch label whose reload restores them from the slots the spill wrote. Win64: xmm6-15 are callee-saved, `finishCalleeSaved` (regex over the function text) saves/restores any it uses, incl. on `GT_UNWIND`; no spill needed.
Promotion is all-or-nothing per slot, decided on the final register-form text: a hinted slot is promoted only if every mention is renameable (`mentionOkX`); a general `ADDR 4 $x ... ASSIGN 4 4 4` pair (a value computed between, e.g. `total = total + f(j+1)`) becomes `R_POPX`, the exact "call bracket + PUSH_RET_FLOAT" form becomes `R_GETRETF`. Functions using r13/r14 as backend scratch are skipped only for the INT class; ASM blocks block both.
Gating: `float-variables-in-registers: on|off` (needs `variables-in-registers: on`, `deferred-operands: on`), parsed by both `CompilerConfig`s. With it off, float hints go to the GPR class exactly as before (verified: output identical). Ships OFF.
Verified: `tests/regvars_float_test.caspien` (11 PASS + one printed line: accumulators, float compare, calls inside loops, extern libm call, float args/results, nested calls, throw caught in a float loop, catch body reading a promoted float, more floats than registers, a static f32, printed float, allocating function) passes on Linux and `windows_gnu`/Wine with the switch on and off; all 148 snapshot outputs (`tests/*`, Hello World, small benchmark, Linux + Wine) identical float on vs off (the one FAIL line, `stdlib_test` "process: write mode", is the same with the switch off, a Wine `popen` quirk); `check_callee_saved.py` clean on every build; n-body outputs identical. Not run: the `windows` (MASM/Intel) target, dedicated xmm6-15 save/restore execution beyond the snapshot programs, float-generating fuzz (the fuzzer emits integer code only), a C library calling back into Caspien code holding promoted floats.
Measured (Linux, 2-core VM, N = 1e6, best of 5, off -> on): nbody 0.331 -> 0.334 s, nbody_arr 0.225 -> 0.230, nbody_plain 0.500 -> 0.449, nbody_loop 0.667 -> 0.612. Remaining cost: float temporaries still pass through GPRs (`movd`), array indices and hidden `for` variables stay in memory, SysV spills around calls. An xmm temp cache/allocator would be the next step (complex, not done).

## New: register variables -- hot scalar locals live in r13/r14 (`variables-in-registers: on|off`)

Asked for directly ("update the lowering level to be aware of these weights and to use that information to put variables in registers ... put it all together"). Three stages cooperate; the hint carries a WEIGHT, never a register.
1. **Optimizer** `RegVarHintPass` (runs once, last): an `ALLOC` of a plain scalar type with a unique non-`$` name, mentioned only by `ALLOC`/`PUSH`/`ADDR` (address-taken variables -- `PUSH`/`ADDR` followed by `ADDR_OF` -- are excluded), gets weight = sum over mentions of 8^min(loopDepth,4) (a backward `JMP` marks a loop). Weight >= 8 emits `REGVAR name weight` right after the `ALLOC`.
2. **LowerOrderGenerator** `AddressLoweringPass` turns `REGVAR` into `REGHINT offset size weight`; `RegisterFormPass` runs as before; then the new `RegVarPromotionPass` (runs on the FINAL register-form text). Per function it bans every hinted slot that has any mention it cannot rename (see `mentionOk`: `R_MOV` size 8, `R_LD`/`R_ST` at the slot's exact width, `R_LEA` index, `R_BIN`/`R_UN` size-8 operands, `R_FBIN`/`R_FCMP`/`R_ARG`/`R_FARG` at exact width, `R_RMW`, `R_RET`, `R_PUSH`; a plain `PUSH`, `ADDR`, `ASSIGN`, `R_PUSHA`, `R_ARGA` ... keeps the slot in memory), takes the top two by weight and renames `$off` to `%v0`/`%v1` (`R_LD` becomes `R_MOV`, a narrow `R_ST` becomes `R_SETV n %vK src`: truncate then zero-extend). Invariant: a variable register holds the value zero-extended to 64 bits. Functions containing `NEW`, `NEW_DYN`, `NEW_UDYN`, `NEW_FROM_STRING`, `CLONE`, `RESIZE`, `URESIZE`, `DOT`, `LOOKUP_ARRAY`, `ASM_START`/`ASM_END` are skipped (the backend uses r13/r14 as scratch for those). `REGHINT`/`REGVAR` are always stripped, so Codegen never sees them.
3. **Codegen** `X86Backend`: `%v0`/`%v1` = `r13`/`r14`; `R_SETV`; an internal error is thrown at `FUNC_END` if a function ends up with both a variable register and a backend use of r13/r14. The callee-saved preservation (`finishCalleeSaved`) already saves/restores r13/r14 in every function that touches them, so values survive calls, throws (`GT_UNWIND` restores per frame) and threads.
Gating: `variables-in-registers: on|off` (top level of `===compiler.config===`, parsed by ASTGenerator's and LowerOrderGenerator's `CompilerConfig`); needs `deferred-operands: on`. With `off`, the low-order bytecode and assembly are byte-identical to the build without this feature. The shipped `toolchain.config` says `on` -- provisional; it is the least-exercised feature (see below).
Verified (Linux; Windows under Wine 9.0): off is byte-identical (LOB + asm) to the previous build; on/off program output identical for every program in `tests/` + Hello World + small benchmark on both targets; new `tests/regvars_test.caspien` (11 PASS: u64 loop, computed updates, u8/u32 wrap, f32 accumulator, calls inside a loop, throw caught inside a loop, return from a catch in a loop, address-taken variable, more hot variables than registers, an allocating function that must stay in memory) passes both ways on both targets; `tests/callee_saved_probe.sh` (sentinels in rbx/r12-r15 checked around loops that use r13/r14, including a throw out of a promoted loop) PASS; `check_callee_saved.py` clean; a random-program fuzzer compared on/off output on 90+ seeds with no difference. Not run: the `windows` (Intel/MASM) target -- every Intel form of `%v`/`R_SETV` is unexecuted; the xmm6-15 saves are structurally checked only; no dedicated test where a C library calls back into Caspien code that has promoted variables (the callee-saved probe covers the register contract).
Measured (2-core VM, N = 1e6, best of 3, off -> on): nbody 0.336 -> 0.324 s, nbody_arr 0.237 -> 0.231, nbody_plain 0.524 -> 0.530 (no change), nbody_loop 0.775 -> 0.689. The gain is small because these programs are float- and call-bound; integer loop counters/accumulators are where it pays.
Limits (deliberate): two variable registers per function; function parameters and the hidden `for`-loop variables are not promoted (they are not `ALLOC`s with a hint); a function that allocates or clones stays in memory; a hinted slot with any unrenameable mention stays in memory, all-or-nothing, decided on the final text so there is nothing to roll back. Future: free more registers (make the NEW/CLONE/RESIZE code save r13/r14 or use others), promote parameters.

## New: callee-saved registers are now preserved (a latent ABI bug found while planning variables-in-registers)

Asked for directly ("please" -- iron out registers, calls, unwinding and threads before variables in registers). Before this change no generated function saved anything: the stack-form code has always used `rbx` (as scratch in nearly every binary op), `r12`-`r15` in places, and the register-form pass added `r12`/`r15` -- all callee-saved under both ABIs. Caspien code mostly calls Caspien code, so nothing broke, but a C caller (libc calling `main`, pthread calling a trampoline, any Caspien function used as a callback) could have found its own `rbx`/`r12`-`r15` changed. A probe confirmed it (stripping the new saves makes the sentinel test report `rbx`, `r12`-`r15` clobbered).

Now `X86Backend` handles it per function (`finishCalleeSaved`): every exit path (`RET`, the `FUNC_END` fall-through, `GT_UNWIND`) emits a `@@CSR@@` mark; at `FUNC_END` the function's text is scanned for callee-saved registers (SysV: `rbx`, `r12`-`r15`; win64 also `rdi`, `rsi`, `xmm6`-`xmm15`, saved with `movups`), the function's single `ALLOC` is grown by a 16-aligned save area BELOW the locals (so `gt_routine_address` stays at `rbp-8`), the registers are stored right after it, and every mark becomes the restores (or nothing if the function touches none). A function with no `ALLOC` gets its own `sub` after the prologue. `GT_UNWIND` restores before its `mov rsp,rbp` / `pop rbp`, so a throw passing through a frame restores that frame's registers and the catching frame sees its registers as they were at the call. Threads need nothing: each has its own registers, and safe code cannot share a plain variable between threads (shared state is `atomic`/`swap`, which must never be promoted). Internal errors (not user errors): a callee-saved register used before the frame exists.

Register partition (what a future variables-in-registers pass may use): `rax` result/temp0; `rbx` temp1 (and stack-form scratch); `r11` temp2; `r12` temp3; `r15` RegisterFormPass scratch `%s`; `r10` `INVOKE` target / float-result stash; `r13`, `r14` used by the backend's `RESIZE`/`NEW` loops and repack code (free for variables only once those are audited or made to save them); argument registers per ABI; SysV `xmm14`/`xmm15`, win64 `xmm4`/`xmm5` are the float scratch. Every callee-saved register a function uses is now saved and restored by the function itself, so a value held in one across a call is safe.

Verified (Linux; Windows under Wine 9.0 for program output): all 124 snapshot outputs (`tests/*`, Hello World, small benchmark) identical to before with the pass on and off, on Linux and `windows_gnu`; new `tests/check_callee_saved.py` structural checker (every function saves what it touches and restores on every `ret`/unwind exit) passes on 376 functions from both targets; new `tests/callee_saved_probe.sh` + `tests/callee_saved_test.caspien` (asm driver loads sentinels into `rbx`, `r12`-`r15`, calls `probeThrow(0)` (throws through g and h, caught), `probeThrow(5)` and `probePlain(9)` through the C ABI, checks the sentinels; PASS, and FAIL with the saves stripped). n-body speed unchanged (0.34/0.25/0.55/0.80 s). Not run: the `windows` (MASM/Intel) target; the xmm6-15 saves (4 present in the Wine builds) were only structurally checked.

Owner decisions recorded: the `examples/` fixtures are kept at the project root (of the owner's working copy; not in the zip); the Optimizer now has a README.md and CLAUDE.md.

## New: deferred operands, stage 2 -- float, narrow-integer and pointer operands, computed-address stores, ARG/RET fast paths, `x += c` (same `deferred-operands: on|off` switch)

Asked for directly ("continue to stage 2"). `RegisterFormPass` now also handles: 1/2/4-byte integers (a narrow frame value is loaded zero-extended, arithmetic is 64-bit and stores truncate, compares zero- or sign-extend by size), `f32` (`R_FBIN`/`R_FCMP`, computed in xmm scratch registers and moved back to a GPR; SysV scratch is xmm14/15, win64 xmm4/5 so ABI arg and non-volatile registers are never touched), pointer operands (`R_LD n %tD addr`, `R_LEA %tD base idx scale` for `DEREF`, `LOOKUP_ARRAY_LHS`, `PUSH_FIELDNAME`+`DOT_LHS`), stores through computed addresses (`R_ST n addr src`), `POP ARGn`/`POP FARGn` (`R_ARG`, `R_ARGA`, `R_FARG`), `RET`/`RET_FLOAT` (`R_RET`, `R_RETF`, single-entry only; void and >8-byte returns stay stack form) and `x += c` as one memory-operand instruction (`R_RMW OP 8 $x b`, peephole in `tryRmw`). Calls themselves stay stack-based (`CC_START`..`CC_END` flush the model).

Model: entries are K (constant; a float's bit pattern), M (frame-slot value of size 1/2/4/8), A (address of a frame slot `$off` or of a global `&sym`) and T (temp, zero-extended to 64 bits, with a VALUE width). `isAddress(e)` = A, or a T/M of size 8; it keeps a struct-construction run (several 8-byte pushes then `ASSIGN`) apart from a scalar store. Store hazards: a deferred M read is loaded before any store that may overwrite it (frame targets: exact range overlap; pointer stores: all deferred M entries; global stores: none). Rollback: a "risky" region (anything beyond plain 8-byte ints) that reaches an unfusable instruction with entries still remembered is discarded and its ORIGINAL lines are emitted (`cpIn`/`cpOut`/`risky`, `st.rollback`); handlers must not emit anything before returning 0.

Backend facts found the hard way: original `PUSH` lines use rax/rbx as scratch (so flushes under a live temp use `R_PUSH`/`R_PUSHA`); `findRunStart` only knows PUSH/ATOMIC_PUSH/STACK_LOCK/nested NEW as construction-run members; `constructionScopedAssign` applies even for sizes <= 8; `PUSH n ARGk` reads the whole register at function entry; `argReg(idx)` falls back to `rax` out of range; `noteArgRegLoaded` must be told about every `R_ARG`.

Verified (Linux and `windows_gnu`/Wine 9.0): with the pass off the low-order bytecode, assembly and program output are byte-identical to the pre-stage-2 build for every program in `tests/` plus Hello World; with it on every program's output is identical to off; `tests/regform_test.caspien` (15 PASS) and new `tests/regform2_test.caspien` (26 PASS: float arithmetic/compares/args/returns, `sqrtf`, u8/u16/u32/s8/s32 wrap and compares, RMW, register args, static u64/f32 arrays with variable index, static scalars, a struct with u64/u32/f32/u8 fields through an `auto` pointer) pass with the pass on and off. Not run: `windows` (Intel/MASM) target -- the Intel forms of every `R_*` line mirror the AT&T ones and are unexecuted.

Measured (2-core VM, N = 1e6 n-body steps, best of 3, pass off -> on; phase-1 "on" in brackets): `nbody` 0.56 -> 0.44 s (0.60), `nbody_arr` 0.87 -> 0.26 s (0.94), `nbody_plain` 1.21 -> 0.50 s (1.21), `nbody_loop` 1.70 -> 0.72 s (1.53). C -O0 is 0.31 s, C -O2 0.07 s. Full table, the other languages and the reference programs: `benchmarks/nbody/`.

Deliberately NOT done (the design leaves room): (1) keeping variables in registers -- assessed and declined for now, see below; (2) call transparency (parking K/A entries across `CC_START`..`CC_END`, `PUSH_RET_INT` as a temp on rax, `PUSH_RET_FLOAT`); (3) an `R_PUSHN n $off` flush that keeps a narrow value's width (a flush of a narrow M currently goes through the rollback path); (4) a prologue peephole for `PUSH n ARGk` argument spills.

Variables in registers: built afterwards -- see the "register variables" section at the top of this file (it satisfies the conditions listed here through `RegVarPromotionPass`: exact-width renameable mentions only, no address-taken slots, callee-saved save/restore, denylist of functions using r13/r14 as scratch; a call-free restriction turned out unnecessary because of the callee-saved saves).

Found, not fixed (both reproduce with the pass OFF, so not caused by it): a struct literal whose fields are computed narrow values segfaults; an array literal probe with computed elements segfaulted; `(x = 3)` as a sub-expression compiles but segfaults. Open, not investigated: the final n-body energy differs in the last digits between the literal-index and variable-index programs (`-0.169092044` vs `-0.169092014`) with the pass on or off -- an f32 operation-order difference between the two source layouts, not the pass.

## Phase 1 (still in effect): deferred operands -- temporaries of 8-byte integer expressions live in registers
Asked for directly (phase 1 only: variables stay in memory; variables-in-registers is a later phase). A `RegisterFormPass` in `LowerOrderGenerator` runs last, after address lowering, and rewrites runs of `PUSH`/`ADDR`/arithmetic/compare/`ASSIGN 8 8 8`/`CMP`+`JMP` into register-form lines: `R_MOV 8 dst src`, `R_BIN OP size %tD a b`, `R_UN OP size %tD a`, `R_BRF src @label`, `R_PUSH 8 src`, `R_PUSHA 8 $off`. Operands are `#imm`, `$off`, `%tN`; temps `%t0..%t3` are `rax, rbx, r11, r12`, `r15` is the backend's spare scratch. Anything the pass does not recognise flushes its model of the operand stack (real pushes) and passes through unchanged, so unknown mnemonics are safe. Switch: top-level `deferred-operands: on|off` in `===compiler.config===` (parsed by ASTGenerator's and LowerOrderGenerator's `CompilerConfig`; ships `on`). With `off` the low-order bytecode and assembly are byte-identical to the previous build (checked on 13 programs x 2 targets).

Things the backend depends on, found the hard way: a flush that re-emits original `PUSH` lines while a temp is live above them clobbers `rax`/`rbx` (the original PUSH lines use them as scratch), so a flush uses `R_PUSH`/`R_PUSHA` whenever a temp is above the entry or live off-model; `X86Backend.findRunStart` only recognises `PUSH`/`ATOMIC_PUSH`/`STACK_LOCK`/nested `NEW` as construction-run members, so entries not beneath a live temp are re-emitted as the original lines; pushes go through `pushReg` because stack-delta tracking only understands that form.

Verified: every program (Hello World, `tests/*`, a small benchmark) gives output identical to the baseline with the pass on and off, on Linux and on `windows_gnu` under Wine 9.0; new `tests/regform_test.caspien` (chains, more than four live operands, signed/unsigned compares, bool logic, nested calls, sequential stores, loop with `break`, narrow ints, `++`/`--`; 15 PASS lines). Measured (2-core VM, 1e9 iterations of `x += 8`, best of 3): 6.6 s off, 2.1 s on; the loop body went from about 51 to 18 instructions. Not run: the `windows` (MASM/Intel) target -- the Intel forms of `R_*` mirror the AT&T ones and are unexecuted. Everything phase 1 left out is covered by stage 2 above, except variables in registers. Found, not fixed: `(x = 3)` as a sub-expression compiles but segfaults at runtime (assignment is not an expression).

## Changed: reading one element of a fixed array loads just that element

Asked for directly ("yes please run it and compare it to the others"), after the array n-body ran 2-3x slower than the same program on scalar statics. `BytecodeEmitter.emitLookup` used to push the whole array (`PUSH 20 x`, three words for an `f32[5]`) and then pick one element out of the copy. For a fixed array held in a variable it now emits `ADDR x` + index + `LOOKUP_LHS` + `DEREF elem` -- the address sequence a write already used -- so a read touches only the element. Only a plain variable root with a scalar element (`u8`..`u64`, `s8`..`s64`, `f32`, `bool`, `char`) is rewritten: a row of a 2D array or a struct element is a block whose size the next `LOOKUP`/`DOT` tracks from the `PUSH` that made it (`DEREF` doesn't carry that), and a nested `a[i][j]`, a call result, a struct member chain, `dynarray` and string reads all go the old way. Found, not fixed (pre-existing, same output before and after): reading `g[1][2]` from a 2D `let static` array gives 0 instead of the element. Output is identical. Measured (Linux, 2-core Xeon, 50M n-body steps, best of 3): unrolled scalar statics 17.5 s (unchanged); unrolled static arrays 37.2 -> 33.1 s; plain loops with `assume match` 46.0 -> 40.6 s; nested-`match` loops 62 -> 56 s. The rest of the gap to the scalar version is the general stack-machine code (every operand is pushed and popped; an array read is about 14 instructions, a scalar read 3) -- there is no register allocation, so that is a code-generation-quality item, not an array-design one. Verified (Linux): the new tests, all existing tests, four n-body variants. Not run: Windows target.

## Changed: a fixed array works as the right side of `in` / `into` / `for` without `range(...)`

Asked for directly ("im supposed to be able to do match i in arr{} to access them?" -- yes, and `into` should behave the same, "thats more about the mutability of the array"). A `dyn` array already took the bare form; a fixed array was rejected (`'in' requires a range or a dynarray on the right, got 'mut_f32[5]'`) and needed the explicit `range(arr)`, which already worked. `TypeChecker.checkIn` and the `for ... in` check now rewrite a bare fixed array into the synthesized call `range(arr)` (`wrapFixedArrayAsRange`, the same CALL shape the parser builds), so `match i in arr{...}`, `match i into arr{...}`, `for i in arr{...}` and `for match i in/into arr{...}` all work. The bounds-proof key is unchanged (`boundsTargetSlotOf` already unwraps `range(X)` to `X`), so the proof authorises `arr[i]` exactly as before; the length is the array's compile-time length; an out-of-range index skips the block at run time; a write under a plain `in` proof is still rejected (`'[]' cannot be written to here ... requires a write-authorizing 'into' proof`). Strings are not affected (only `range("literal")` works for them). New `tests/array_match_test.caspien`. Verified (Linux): that test; a looped n-body (`gen3.py`: seven `f32[5]` statics, nested `match i in/into <array>` per array) prints the expected result; Hello World and all other tests in `tests/`. Not run: Windows target. Performance: see the next entry (array reads now load one element). Each array needs its own proof (a proof is keyed to one array), so a loop touching seven arrays nests seven `match` blocks; chaining them with `and` was rejected for `into`.

## Changed: static (global) arrays now read correctly, with their initial values; float statics initialise

Found porting n-body (asked for directly: "please do" fix the array-read bug). Four separate faults, all fixed. (1) Reading any element of a `let static` array segfaulted: `PUSH 12 a` of a multi-word global took the single-word path and pushed only the array's first 8 bytes, which `LOOKUP_ARRAY` then indexed into as if they were the array. `X86Backend` now pushes a global wider than 8 bytes as a whole value block (`pushSizedOrBlockFromAddr`, the same shape a local of that size gets) and tags a narrow (<8 byte) global the way a narrow local is tagged. (2) A static array's element values were dropped: `GLOBAL a.0 4 1.5` lines are aliases into the parent block and their initialisers were discarded, so the array started zeroed. They are now collected (`aliasInits`) and the parent block is emitted with those bytes. (3) Any float initialiser on a static was silently 0 (`parseInitValue` only understood integers); `initBits` now emits the IEEE-754 bits (4 bytes single, else double). (4) `BytecodeEmitter.foldStaticConstant` threw `NumberFormatException` on a negative float (`Long.parseLong("1.16")`), crashing the compiler on `let static a = mut [1.5, -2.5]` or `let static h = mut -0.25`; the unary minus now negates the text of a float constant. Also fixed: a small (<8 byte) `f32[1]` array element read emitted `andq $4294967295, %rax`, which the assembler rejects (`andq` takes a sign-extended 32-bit immediate); a zero-extending `movl`/`movzwl`/`movzbl` is used instead. New `tests/static_array_test.caspien`. Verified (Linux): that test, an n-body port using seven `f32[5]` static arrays (same output as the scalar version), Hello World and all other tests in `tests/`. Not run: Windows target (the Intel-syntax `mov eax, eax` / `movzx` lines and the `db` byte image are untested). Found, not fixed: (a) a fixed array of exactly 8 bytes (`f32[2]`, `u8[8]`) segfaults on read: `PUSH 8` is indistinguishable from a pointer value, so `LOOKUP_ARRAY` treats it as a base address (arrays under 8 bytes are tagged and work, larger ones are pushed as blocks and work); (b) a read of one element of a fixed array pushed the whole array to the stack first (fixed in the next entry); (c) a `dyn` array in a `for match i in a{...}` loop in a plain `main` segfaulted for me (both before and after these changes, integer elements, `?catch(e){ return }` plus the ghost-table imports); not investigated; (d) fixed arrays accept only literal indices, so an array cannot be looped over without `dyn`.

## Changed: f32 negation flips the sign bit, and the link step passes `-lm`

Asked for directly, after a benchmark port (n-body) hit both. (1) Negating an `f32` (a negative literal such as `-1.16`, or unary minus in `unsafe`) lowered to the integer `NEG size`, and the backend emitted `negq` on the float's bit pattern, so `-1.16` became `-3.68` with no error. `AddressLoweringPass` now emits `NEG_FLOAT size` for a float operand (same `isFloatBaseType` test as `INC_FLOAT`), and `X86Backend` handles it by XOR-ing the sign bit (`xorl $0x80000000,%eax`; for size 8, `xorq` with `0x8000...`; Intel form for the MASM-style syntax). Integer `NEG` is unchanged. (2) `Compiler.java` passed `-pthread` but not `-lm`, so any extern libm call (`sqrtf`) failed with `undefined reference`; `-lm` is now added for `linux` and `windows_gnu` (mingw has libm). New `tests/float_neg_test.caspien` (negative literal, unary minus on a variable and an expression, `0.0 - literal`, `sqrtf(16.0)`; prints `ALL FLOAT NEG TESTS PASSED`). Verified (Linux): that test, an n-body port that now builds with the compiler alone, Hello World and all other tests in `tests/` compile. Not run: Windows target (the `NEG_FLOAT` Intel-syntax line and `-lm` under mingw are untested). Found, not fixed: (a) `foldStaticConstant` throws `NumberFormatException` on a negative float literal inside a static array initialiser; (b) reading an element of an `f32` array emits `andq $4294967295, %rax` (invalid immediate; should be `movl %eax, %eax`), related to the known `array_literal_narrow_elem` failure; (c) `stdlib/libc.caspien` has no libm externs (`sqrtf` must be declared in the user file); (d) unary minus on an unproven `f32` variable is rejected in safe code (needs `unsafe` or a `match ... finite`) -- by design.

## Changed: `make_safe_args` advances its cursor with `cur += 8`

The eight repeated `cur++` (and a comment claiming a raw pointer has no `+8`) were left over from before pointer arithmetic existed. `cur` is a `raw mut u8`, so `cur += 8` moves it 8 bytes. Compiles (Linux); not run at runtime -- the safe-args `main` shape still exits 1 (older open item).

## Changed: `match @lock` -- every CLOSED path must end in `continue` (retry), `break` (give up), `return` or `throw`

Asked for directly ("make every branch which isnt the target have to end in continue (meaning retry), return or throw -- doing what they do in a function -- or break, meaning fallthrough"). Applies only to `match @lock` on a struct with a `swap` mutex field (the spin loop); struct-level `@lock`, `@guard` and block `lock x{}` are untouched. Before, a CLOSED case that fell off its end implicitly retried, so `CLOSED:{ }` meant "spin". Now: `continue` = retry the acquire (jump to the spin loop's start, after any `CLOSED:default` counter init, so counters are not reset); `break` = give up, leave the match without the lock (it already targeted the spin loop); `return` / `throw` as in any function. Falling off the end on any path is a compile error ("the 'CLOSED' case of 'match @lock' must end, on every path, in ..."). `continue` is valid directly in CLOSED (also inside `if`/`match` there); it is still an error in OPEN, in a nested loop inside CLOSED, and outside a catch body elsewhere; inside a `catch` it keeps its try-block meaning. `CLOSED:default(...)` policy bodies get an implicit trailing `continue` unless they already end every path. Implementation: `TypeChecker.lockClosedLoopScope`, `closedCaseTerminates` (`statementDefinitelyReturns` counts `break` only while it runs), new `Token.isLockSpinLoop` / `isLockRetryContinue`, `BytecodeEmitter.lockRetryLabels` pushed in `emitLoop`. Stdlib: the four empty `CLOSED:{ }` in `gt_init`/`gt_register`/`gt_alive_check`/`gt_destruct` are now `CLOSED:{ continue }` (same bytecode behaviour). New `tests/lock_match_terminators_test.caspien` (retry after 3 failed attempts, give-up via `break`, `return`, mixed paths). Verified (Linux): that test, Hello World, and all other tests in `tests/`; the compile errors for fall-off, one path falling off, `continue` in OPEN, in a nested loop, and bare. Not verified: Windows target; a `CLOSED:default` policy (`impl default match @lock`). Found, not fixed: a swap-mutex struct as a plain LOCAL variable segfaults at runtime (statics work); the test uses statics.

## Changed: stdlib `unsafe` blocks narrowed to the operations that need them

Asked for directly: `unsafe` should only wrap what the compiler actually treats as unsafe. Every stdlib `unsafe{}` was cut down to the extern calls, `memcopy`, `raw x` construction and unbounded `loop{}` (plain `loop` still needs `unsafe`). Because `unsafe X` as an expression form exists only for `dyn`, a value produced by an unsafe call is declared first (`let:<raw mut u8> buf = null`) and assigned inside a small `unsafe{}`. Changed: `ghost_table.caspien` (`gtSlotAt` is now fully safe -- passing, casting and stepping a raw pointer is safe; `gtReadSlot`/`gtWriteSlot` keep only their `memcopy`), `gt_init`/`gt_register`/`gt_destruct` (only `malloc`/`realloc`; the `ghost_table.base` writes are outside), `hash.caspien` (`malloc`/`memcopy`/`free`; the loop and arithmetic are safe), `make_safe_args` (each `memcopy`, and the two `loop{}`s), `process.caspien` (`popen`, `pclose`, `malloc`+`fgets`; `spawn`'s `match` and `new` are outside, and the duplicated READ/WRITE bodies are one), `sleep.caspien`, `par_call`/`await_call` (only the `pthread_*` calls). Behaviour is unchanged. Verified (Linux): Hello World and every test in `tests/` (stdlib_test, integer_tightening_test, nested_call_args_test, stack_args_test, throw_safe_test, deref_safe_test, deref_unsafe_test); `par_call`, `await_call`, `sleep` and `guard` compile. Not run: Windows target; the three `event_loop*` files (they need a user `main`, and contain no `unsafe`).

## Changed: `deref(p)` requires `unsafe` unless the pointer is proven alive

Found while auditing what raw pointers allow in safe code: `deref(p)` compiled anywhere, with no null/alive proof, and because it yields an inline value it also bypassed the "no `.` member access through a pointer" rule (`deref(p).x`). Now `TypeChecker.checkDerefBuiltin` uses the same test as `.` member access (`requiresAliveProof`): in safe code `deref` is accepted only for an `auto` pointer, a `some`-tagged pointer, or a pointer inside `match Some(p){...}`; anything else needs `unsafe` (`'deref' of a pointer requires 'unsafe' code unless the pointer is proven alive`). Checked for all five kinds (`owns`, `ref`, `raw`, `auto`, `static`), each with plain / `unsafe` / `some` / `match Some` forms: plain is rejected for all except `auto`; `unsafe`, `some` and `match Some` are accepted for all; `auto some` is not a legal type. Unchanged: passing/returning `raw`, pointer arithmetic and the `raw` cast are safe; constructing a `raw` and `memcopy` need `unsafe`; `x[i]` on a pointer is unsupported. The stdlib never used `deref`. New tests: `tests/deref_unsafe_test.caspien` (unsafe `deref` of a `raw`, prints `v=7`) and `tests/deref_safe_test.caspien` (safe `deref` of an `auto` and, inside `match Some`, of an `owns some` box; prints `auto=7`, `owns box.v=41`). Verified (Linux): both, plus Hello World, stdlib_test, integer_tightening_test, nested_call_args_test, stack_args_test, throw_safe_test unchanged. Not run: Windows target. `deref(...)` is also rejected as a write target (`deref(p) = x`, `deref(p) += x`, `deref(p) swap x`; `rejectDerefAsWriteTarget` in `checkAssign`/`checkCompoundAssign`/`checkIncrement`/`checkSwapOperator`), asked for directly: it yields a copy, not a place. It used to type-check and then fail to link (`undefined reference to 'CALL'`, the emitted assignment target was the literal word `CALL`). Writing through a pointer in safe code is `.` member assignment on a proven-alive struct pointer; otherwise `memcopy` in `unsafe`. `deref(p)++` / `deref(p)--` get the same message: postfix `++`/`--` after a call used to bind to the argument group (a `RpnConverter.toRpn` ordering bug, which surfaced as `'deref' takes exactly one argument, got 0`); the pending `CALL` is now emitted before the postfix operator, so `f(x)++` applies to the call result. Bytecode for the tests and stdlib is byte-identical to the previous build. Verified (Linux): the new errors for assignment (safe and in `unsafe`) and compound assignment; reads of `deref(p)` unaffected.

## Changed: `throw` (and so `try`/`?`) no longer requires `unsafe`

Asked for directly: throwing was marked unsafe before its behaviour was defined; it is now fully defined (per-throw-site destruct list, unwind to the nearest `try`/`catch`, `@throws` contract, no throw reachable from the ghost-table functions), so it is safe code. `TypeChecker.checkThrow` no longer raises `'throw' requires 'unsafe' code`; `try`/`catch`/`?` never required `unsafe`. All other `throw` rules are unchanged (`@throws` required, string operand, not reachable from `@gt_*`). The stdlib's 13 `?catch(e){ unsafe{ throw e } }` sites are now `?catch(e){ throw e }`. New `tests/throw_safe_test.caspien`: safe `throw` in `f`, re-thrown through a `?catch` in `g`, caught in a safe `main` (prints `caught`). Verified (Linux): that test, Hello World, stdlib_test, integer_tightening_test, nested_call_args_test and stack_args_test unchanged. Not run: Windows target.

## New: a struct decorated `@lock(match self.X : ...)` must have `X` as its FIRST member

Asked for directly (and repeatedly): when a struct is decorated with `@lock`, its first member must be the lock in question. `TypeChecker.flattenStruct` now rejects a struct-level `@lock(match self.X : ...)` whose `X` is not the first user-declared member (the hidden `___type` classId is not counted), with: `'GhostTable' is decorated '@lock(match self.lockState : ...)', so 'lockState' must be its first member, but its first member is 'base'`. With `extends`, the first INHERITED member counts as first (so a lock field declared in the child of a parent with other members is rejected; a lock field that is the parent's first member is accepted). The rule applies only to the struct-level enum form; the method-level `@lock` and the `@lock(match i into self.backing)` form (DynamicArray) are unchanged. `stdlib/ghost_table.caspien` now declares `swap lockState` first (the global `ghost_table` is built with named fields, so layout order does not matter to it). Verified (Linux): the old GhostTable shape is rejected with the message above; lock-first compiles; `extends` with a non-first inherited member is rejected and with the lock as the parent's first member is accepted; Hello World, stdlib_test, integer_tightening_test (64/64), nested_call_args_test and stack_args_test unchanged. Not run: Windows target; an `impl default match @lock` policy.

## Fixed: `GhostTable` was not actually locked, and `match @lock` now grants the struct-lock proof in its OPEN case

Found by the owner reading `stdlib/ghost_table.caspien`: "why is this struct not locked?" `GhostTable` had a `swap` mutex field but no struct-level `@lock(match self.lockState : OPEN)`, and a swap-lock struct is exempt from the global/static `unsafe` rule, so SAFE code could read or write `ghost_table.base/len/capacity` with no lock at all (confirmed: `func peek() mut u64{ return ghost_table.len }` compiled). Now `GhostTable` carries `@lock(match self.lockState : OPEN)`, so any access to another member needs a proof (or `unsafe`). To make that usable, `checkLockMatchStatement` now plants the ordinary `enum_field_variants` proof (receiver slot, swap field, `OPEN`) on the OPEN case body's scope only; CLOSED gets none. The error for an unproven access on a swap-lock struct now also suggests `match @lock recv{...}`. Verified (Linux): the unlocked read in a plain function and a read in the CLOSED case are both rejected; every gt_* function (all inside OPEN) still compile; stdlib_test, integer_tightening_test, nested_call_args_test unchanged. The stdlib's `unsafe` blocks still bypass the proof by design. Windows not run (front end only).

## Changed: `lock match` is now `match @lock` (and `impl default match @lock`)

Asked for directly ("it's jarring to look at"; then, once `match lock EXPR{` proved ambiguous, "match @lock EXPR{...} to avoid that ambiguity"). The spin-lock case-match statement is written `match @lock EXPR{ OPEN:{...} CLOSED:{...} }`, and the backoff policy template `impl default match @lock MyMutex{ CLOSED:(tries:imut u64)=>{...} }`. Mid-line, `@` is an ordinary OPERATOR token (a line-leading `@` is stripped earlier as a decorator), so `match @lock` can never be mistaken for a match on a variable or call named `lock`: `match lock{...}` and `match lock(x){...}` stay ordinary matches. Old spellings are parse errors that name the new one: `lock match ...`, `match lock EXPR{...}` (a bare `lock` followed by an identifier) and the two `impl default` equivalents. The block form `lock EXPR{...}` is unchanged. Parser only (`isLockStatementStart`, `gatherLockStatement`, the `impl default` check); the stdlib `gt_*` files use the new spelling. Verified (Linux): stdlib_test, integer_tightening_test and nested_call_args_test unchanged (every `new` goes through the ghost table's `match @lock`); all four old spellings rejected; `match lock{` on a variable named lock still parses as an ordinary match; `impl default match @lock` parses. Not run: an actual `impl default match @lock` policy.

## New: integer tightening -- literal adaptation, `match x fits T`, `wrap:<T>(x)` / `sat:<T>(x)`; signed `/ % < <= > >=` now correct

Asked for directly ("cleanly, ergonomically, and in accordance with the existing design philosophy"), design approved point by point. Ranges stay unsigned. Before this, an integer could only widen inside its own signedness family (`SEXT`/`ZEXT`), every literal was a `u64`, and `let b = mut (a + 1)` with `a: u8` was a type error.

**1. Literal adaptation (ASTGenerator `TypeChecker`).** An integer literal (or a negated one) has no width of its own: it takes the type of the slot it lands in when its value fits, and is a compile error when it does not. `TypeInfo.literalValue` carries the value; `literalAdapts`/`adaptLiteral` accept and retag it. It applies at: function/method/extern/`sleep`/function-pointer arguments (`retagLiteralArgs` after the overload is chosen), `return`, assignment, struct-literal members, and both sides of arithmetic, comparison and compound assignment (`unifyLiteralOperands`: `a + 1`, `a == 5`, `a += 2`, `x < -5`). Overloads: a candidate that needs no literal adaptation is tried first (`orderByLiteralExactness`), so `f(5)` with `f(u64)` and `f(u8)` picks `f(u64)`; otherwise declaration order. A VARIABLE is never a literal (`let x = mut 5` is a plain `u64` slot; the VARREF case strips `literalValue`), so `takes8(x)` is still an error.

**2. `match x fits T{...}` (proof).** `fits` is an operator legal only as a `match` condition, with a plain integer variable on the left and an integer type name on the right. Inside the branch `x as T` is allowed to narrow or change sign (`isProvenFits`, exact target match, invalidated by any write to `x` like the other match proofs). `else`/`elsematch` work as usual (`else{` must start its own line). Cast lowering: narrower = new `TRUNC srcType dstType` (lowered to `TRUNC srcSize dstSize`), same width = relabel, wider = `SEXT`/`ZEXT` by the SOURCE's signedness. The runtime test (`BytecodeEmitter.emitFits`) widens x to 64 bits and uses only unsigned compares against literals below 2^63. The narrowing errors now say: prove it with `match x fits T`, or use `wrap`/`sat`.

**3. `wrap:<T>(x)` and `sat:<T>(x)` (total, no `unsafe`).** Builtin calls; T is one integer type name, x any integer expression, the result is `T` with indeterminate mutability (bind with `mut`). `wrap` keeps the low bits (widen by the source's signedness, relabel, or `TRUNC`). `sat` clamps to T's range with branch-free arithmetic built only from unsigned compares and literals below 2^63 (`emitConvert`), so it evaluates its argument several times: it must be a variable, field chain or literal (optionally negated/`mut`); bind a computed value to a `let` first (compile error otherwise). When every value of x fits T, `sat` is exactly `wrap`'s widening. The type argument travels on the builtin's VARREF (`GenericsExpander.processGenericToken` keeps `genericArgs` for `dyn`, `wrap` and `sat`). `wrap` and `sat` are now reserved builtin names.

**4. Signed arithmetic and ordering (LowerOrderGenerator + Codegen).** The backend used to treat every integer compare and `/` `%` as unsigned (`-6 / -2` was 0; negative pointer differences printed garbage). `AddressLoweringPass` now emits separate mnemonics for signed operands: `SDIV_INT`, `SMOD_INT`, `SLT_INT`, `SLT_EQ_INT`, `SGT_INT`, `SGT_EQ_INT` (`EQ`/`NEQ` unchanged). `X86Backend` sign-extends both operands from their declared width (`signExtendReg`) and uses `idiv`/`setl`/`setle`/`setg`/`setge`. `SEXT` also clears the sign bits above the destination width (a narrow value is kept zero-extended in its stack word), and a `PUSH` of a u64 literal above 2^63-1 no longer crashes Codegen. Paren-wrapped literals and array-literal elements now adapt too: `let:<mut u8[3]> s = mut [1, 2, 200]` types each element to the slot's element type (`literalElements`/`arrayLiteralAdapts`, an out-of-range element is an error), a mixed-sign literal array unifies to `s64`, and a negated literal folds to one `PUSH -N` (`emitUnaryMinus`). The out-of-range message now lists the types the value does fit. Closed this round (Codegen/LowerOrderGenerator): `MIN / -1` and `MIN % -1` no longer trap (`SDIV_INT`/`SMOD_INT` special-case divisor -1: negate / zero); `bits_right` on a signed type lowers to a new arithmetic `SAR size` (unsigned stays `SHR`); unsigned narrow compares, `DIV`/`MOD` and shifts zero-extend their operands first and shifts re-zero-extend the result, so a stray high bit in a narrow stack word can no longer change a compare.

**Verified (Linux/sysv_x64).** `tests/integer_tightening_test.caspien` (new, 64 checks): literal adaptation in arguments, operands, comparisons, compound assignment and overloads; `fits` at the u8/s8/u64 boundaries with `else`; `wrap`/`sat` at their boundaries; signed `/ % <` `<=` on s64 and s8. Also run by hand: `wrap`/`sat` from s64, u64, s8 and u8 sources to several targets, negative pointer differences, and the compile errors (`takes8(mut 300)`, `x as u8` on an s64, `sat` of a computed argument). `tests/stdlib_test.caspien`, `nested_call_args_test`, `stack_args_test` and Hello World are unchanged. Not executed: the win64 target (Intel branches written to mirror the AT&T ones; no mingw/Wine here).

## Fixed: stack-passed arguments (7th and later integer arguments on Linux/SysV, 5th and later on win64) arrived in the wrong order

Found while fixing nested call arguments (`eight(1,2,3,4,5,6,7,8)` saw `g` and `h` swapped; a `printf` with eight `%llu` printed `7 8 6` for the last three); fixed on request ("yes please fix that").

**The bug (Codegen, `X86Backend`).** Arguments are evaluated and pushed left-to-right, so at the `call` the first stack-passed word was deepest and the last was on top. Both conventions (and every callee, Caspien's own reading `$16`, `$24`, ... and C's `printf`) require the FIRST stack-passed argument at the LOWEST address, immediately above the return address. With two or more stack-passed words every one of them was read from the mirrored slot. One stack-passed argument (the only case the older overflow fixtures covered) looked fine, which is how it went unnoticed.

**The fix.** `resolveStackArgBytesAndCall` now calls the new `reverseStackArgWords(stackArgBytes)` after every argument has been produced and before the win64 shadow-space reservation and the `call`: an in-place reversal of the stack-passed 8-byte slots (scratch: `rax` and `r11`; `r10`, which may hold an `INVOKE` target, is untouched). Evaluation order stays strictly left-to-right, no bytecode or front-end change was needed, and calls with fewer than two stack-passed words emit exactly what they did before.

**Verified (Linux/sysv_x64).** `tests/stack_args_test.caspien` (new): 7, 8, 9 and 10-argument Caspien calls (1 to 4 stack-passed words), calls in the stack-passed slots and in every slot, `printf` with ten integer varargs and with eight call-result varargs, a mixed int/float call with stack-passed words of both kinds, and an 11-`f32` call. The program printed wrong values before this change. All of `tests/stdlib_test.caspien`, `tests/nested_call_args_test.caspien`, Hello World and the String/DynamicArray/HashMap/generics/harness programs still give the same output. Not executed: the win64 target (the reversal is emitted in Intel form for it; no mingw/Wine here).

## Fixed: a call used as an argument of another call no longer clobbers the outer call's already-loaded argument registers

Found while writing `tests/stdlib_test.caspien` (check names printed as garbage when a check's second argument was itself a call), reproduced in a small program, and fixed on request ("yes fix it like this please").

**The bug (Codegen, `X86Backend`).** Each argument is popped into its ABI register the moment it is evaluated (`POP ARGn`). `show("nested", two(1, 2), 9)` therefore loaded the string into `%rdi`, then evaluated `two(1, 2)`, whose own argument setup overwrote `%rdi`/`%rsi`; the result was popped into `%rsi`, and `show` was finally called with `%rdi` = 1. `show("x", 9, two(1, 2))` lost the `9` the same way. Any call inside another call's argument list was affected (user functions, methods, externs such as `printf("%llu", f(x))`); the result was garbage or a segfault. The bytecode was already correct at every earlier stage.

**The fix.** `X86Backend` now records, per open `CC_START`/`CC_END` bracket, which argument registers its `POP ARGn`/`POP FARGn` lines have loaded (`loadedArgRegsStack`, `noteArgRegLoaded`). A `CC_START` that is nested inside another call pushes the enclosing call's loaded registers (float-bank registers through `r11`) before opening its own bracket; the matching `CC_END` pops them back in reverse order, leaving `rax` (an integer result) untouched. The inner call's result is pushed after the restore and popped into its own argument register as before. The pushes and pops cancel exactly, so the enclosing call's stack-passed-byte accounting is unchanged. One special case: xmm0 is both float argument 0 and the float return register, so if xmm0 has to be restored the possible float result is first stashed in `r10` and the next `PUSH_RET_FLOAT` reads it from there (`floatResultInR10`).

**Verified (Linux/sysv_x64).** `tests/nested_call_args_test.caspien` (new) covers a call in the first, middle and last argument slot, two calls in one argument list, deep nesting, calls in every slot of a six-argument call, a string argument before a nested call, a `printf` with call varargs, and floats (an `f32` argument before a nested int call, and nested float calls). With the old Codegen that program crashes; now every case passes. `tests/stdlib_test.caspien` was switched back to the natural `check("name", d.get(d, i) == 20)` style and all its checks pass. Hello World, and the String, DynamicArray, HashMap, generics, overloading and harness programs give the same output as before. The Windows target (Intel/win64 register set) was only compiled to assembly, never executed: no mingw/Wine here.

**Found here and fixed separately:** stack-passed arguments arrived in the wrong order for calls with more than six integer arguments (see the section above this one).

## Changed: stdlib brought up to `owns some` style; `dyn(...)`/`resize(...)` now yield `some`; new `tests/stdlib_test.caspien`

Asked for directly: make `String.chars` `owns some`, remove the redundant `match Some(...)` on freshly-`new`'d data across the stdlib, use `owns some` return types where appropriate, then check the whole stdlib compiles and write a test program that tests all of it.
- **Stdlib.** `String.chars`, `DynamicArray.backing` and `HashMap.slots` are declared `owns some` (every constructor sets them), so no method needs `match Some(_self.chars)` any more (all eight in `string.caspien` are gone). `String.sub`, `spawn` and `make_safe_args` return `owns some`; `closeProcess` takes `ref some`; the adopt constructor `new DynamicArray:<T>(arr)` takes `owns some mut dynarray`; `make_safe_args` no longer wraps its return in `match Some`. Header/doc comments that explained the old extra `match Some` were rewritten.
- **Compiler (ASTGenerator `TypeChecker`).** A `some` field could not be assigned from `? resize(...)` or `? dyn(...)` because both returned a plain (non-`some`) `owns` dynarray. Both are allocation-that-throws-instead-of-returning-null, exactly like `new`, so the safe-dynarray results of `dyn(...)`, `dyn:<T>([])`, `dyn(text)` and `resize(...)` now carry `some` (`withSome(true)`). The `unsafe` dynarray forms are unchanged.
- **Test.** `tests/stdlib_test.caspien` (imports `../stdlib/...`, since imports resolve relative to the importing file) covers DynamicArray (all three constructors, get/set/pushBack/popBack/pushFront/popFront, empty pops, `char` element type), `hashOf`, HashMap (set/get/contains/update/collision/zero capacity), String (both constructors, concat both overloads and self, appendChar, sub, firstIndexOf, charAt, setCharAt), libc (`strlen`, `clock`, `putchar`), process (`spawn` READ and WRITE, `StdOut.read`, `StdIn.write`, `closeProcess`), `sleep_call`, and `await` on an `@async` function. Run: `java Compiler -i tests/stdlib_test.caspien output/stdlib_test && ./output/stdlib_test`; it prints one PASS/FAIL line per check and `ALL STDLIB TESTS PASSED`. Changing an expected value makes it print `FAIL` and a failure tally.
- **Test-writing gotchas found.** (The first one, a call inside another call's argument list corrupting the earlier arguments, was found here and then FIXED -- see the next section.) `main` must return `imut s32`/`imut bool`/`void`, and no integer literal can produce an `s32` (they are `u64`), so the test's `main` is `void`.
- **Not covered by the test:** `make_safe_args` and the three `event_loop*` files (they need a safe-args / C-args `main`, and the safe-args `main` shape still exits 1 -- see the older note), `ghost_table`/`gt_*` (exercised implicitly by every `new`, `match Some` and destruct in the test), `guard`, `par_call` (only `await_call`'s path is run).

## Changed: the stdlib's instance-making static methods are now constructors (generic constructors made usable)

Asked for directly: "my static methods in the stdlib that really just make instances of things - they should be constructors ... lift the restriction, remove the static constructors." Every stdlib `static func` that only built an instance is gone; the stdlib now uses `impl constructor for`:
- `new DynamicArray:<T>()` (was `DynamicArray:<T>.make()`), `new DynamicArray:<T>(seed)` (was `fromSeed`), and a new `new DynamicArray:<T>(arr)` that adopts an existing `owns mut dynarray` as its backing store.
- `new String()` (was `String.make()`) and `new String(text)` (was `String.from(text)`; built as `new DynamicArray:<char>(dyn(text))`).
- `new HashMap:<T>(defaultKey, defaultValue, capacity)` (was `fromCapacity`).
The struct-literal lockout now applies to these structs: `DynamicArray:<T>{...}`, `String{...}` and `HashMap:<T>{...}` are legal only inside their own constructors. `ProcessHandle`'s `static func read`/`write` are not instance-makers and stay.

Syntax: a generic constructor is written OUTSIDE the struct's bare `impl`, one `impl<T> constructor for X<T>(params) self { ... }` per constructor, with function decorators (`@throws`, `@pub`) above it. `?` cannot sit inside a struct-literal field, so bind first: `let arr = mut ? dyn:<T>([])` then `return X:<T>{backing= arr}`.

Compiler changes (ASTGenerator), all needed to make a generic `@throws` constructor work:
1. **`TypeChecker.collectImpl`**: an impl made only of constructors no longer counts as the struct's "bare impl block". A generic struct needs one `impl` wrapper per constructor, so the second one used to fail with "'Box_u64' already has a bare 'impl' block". Constructor-only impls are now skipped by the one-bare-impl and method-ambiguity checks (they contribute no methods), and may sit beside a real bare impl.
2. **`Parser.gatherConstructorImpl`**: the generic wrapper `impl` received all the decorators and rejected `@throws` ("not a valid decorator on an 'impl'"). The wrapper now gets only `@pub`; the constructor function keeps the full list.
3. **`@throws` constructor under `new`**: `new X(...)` wrapped in one `try`/`?` covers both the allocation and the constructor's own throw (there is no syntax to wrap the inner call separately). `checkNew` sets a new `Token.coveredByThrowingNew` on the constructor CALL node and `requireThrowsWrapping` accepts it. The bytecode already staged the try's catch label at the constructor call (`emitStructRvoCallSequence` -> `stageCallSiteForUnwind`), so no emitter change was needed. The throw path (a constructor actually throwing) is not exercised by a test.
4. **Overload mangling**: `signatureSuffix` now maps every character outside `[A-Za-z0-9_]` to `_`. A parameter type such as `owns_mut_dynarray(u64)` put parentheses into an assembly label (`as` rejected `__ctor__..__sig_owns_mut_dynarray(u64)`). Names that were already plain letters/underscores are unchanged.
Verified (Linux): the String, DynamicArray and HashMap runtime tests give the same output as before the change, the harness and generic/overload/pointer tests are unchanged, Hello World's assembly is byte-identical, and `DynamicArray:<u64>{...}` outside a constructor is rejected with the lockout message. Windows targets untested.

## Fixed: struct construction layout (`new X{...}`), `RESIZE` fill width, drop-glue calls, wide dynarray element reads, `hashOf` -- String, DynamicArray and HashMap now RUN

Asked for directly: "yes fix the struct construction bug please." The bug (described in the pointer-arithmetic section below as "the other, still-open cause") was in Codegen: a `new X{...}` whose run of field pushes is interrupted (the null-out after moving an `owns` variable into a field, the out-of-memory check after a nested `new`/`dyn`) was not recognised as a construction run, so the fields were pushed as blind 8-byte words and landed in REVERSE order in the heap object (`new Holder{backing= arr}` put the type id where `backing` should be). Fixing it exposed four further bugs on the way to a working String, all fixed in the same round, each found by running the stdlib, not by inspection:

1. **Construction layout (LowerOrderGenerator + Codegen).** `AddressLoweringPass` now writes the struct's layout onto the lowered `NEW`: `NEW <size> m8 m8 p4 ...` (`m<n>` = a member of n bytes, `p<n>` = n bytes of padding). Codegen's `precomputeConstructionOffsets` records which `NEW` lines it already packed (`packedNewLines`); for any other `NEW` that carries a descriptor, the new `emitNewRepack` copies each chunk out of the (reversed, word-per-push) stack image into a heap buffer at its true offset (wide blocks are word-reversed, as `pushBlockFromFrame` pushes them). A `NEW` without a descriptor, and every already-packed `NEW`, emit exactly what they did before: Hello World's assembly is byte-identical to the previous build.
2. **`RESIZE` fill width (Codegen).** The fill loop stored 8 bytes (`movq %r14,(...)`) whatever the element size, so `resize` of a `char` dynarray overran the block by 7 bytes ("realloc(): invalid next size" on the second `appendChar`). It now stores exactly `elemSize` bytes, and a fill wider than a word (a struct element, e.g. `HashMapEntry`) is treated as the whole pushed block (`newCount` and the old pointer sit `nw*8` and `nw*8+8` bytes up the stack) and copied per element by `emitResizeWideFillCopy`. `sizedReg` gained the `r8`-`r15` low forms (`r14b`/`r14w`/`r14d`), which it previously returned unchanged for a 1/2/4-byte access (an assembler error).
3. **Drop-glue calls (Codegen).** `DropGlueGenerationPass` calls `__drop_T` as a bare `PUSH ptr` / `CALL` with no `CC_START`, and the routine reads its one parameter as the stack word right above the return address. The generic aligned-call wrapper put its alignment slot BETWEEN them, so the routine read an uninitialised slot: it only worked while stale stack contents happened to match, and a nested drop (String -> DynamicArray) dereferenced null at the end of any function holding a String. `case "CALL"` now sends a `__drop_*` callee outside a call bracket to `emitDropGlueCall`, which pops the argument, aligns, saves the old rsp below it, pushes the argument back so it is adjacent to the return address, and calls.
4. **Dynarray elements wider than 8 bytes (Codegen).** `pushSizedOrBlockFromAddr` loaded each word into `rax` while `LOOKUP_DYN` passes `rax` as the address register, destroying the address after the first word; every read of a struct element out of a dynarray (`_self.slots[idx]`) returned garbage. It now loads through `r11`.
5. **`RESIZE`/dynarray element size for a generic struct (LowerOrderGenerator).** `AddressLoweringPass.Sizes.sizeOf` re-parsed an already-stripped struct name as a canonical type string: `HashMapEntry_u64` was read as mutability `HashMapEntry` + base type `u64` = 8 bytes instead of 32. A name the struct table knows is now used whole.
6. **`stdlib/hash.caspien` (`hashOf`).** It copied each byte of the value into a pointer variable and then dereferenced that variable (`memcopy(raw bytePtr, 1, cur)` then `memcopy(raw byteVal, 1, bytePtr)`), i.e. read from address = the byte value. It now reads the byte straight into `byteVal`.

**Verified at runtime for the first time (Linux/sysv_x64):** `String.from`, `String.make`, `appendChar` (repeatedly), `concat` with a literal and with a `String` (including itself), `setCharAt`, `charAt`, `firstIndexOf` (found and -1), `sub`, and scope-end destruction; `DynamicArray:<u64>` `make`, `fromSeed`, `pushBack`, `pushFront`, `popBack`, `popFront`, `get`, `set`; `HashMap:<u64>.fromCapacity`, `set` (insert and update), `get` (hit and miss), `contains`. Also unchanged and correct: Hello World (assembly byte-identical), the DynamicArray/HashMap/String harness, the String-import program, generics, overloading, pointer arithmetic, `dyn:<T>([])`, and the earlier struct-construction probes (`new Outer{p= i, k= 9}` etc). The Windows target is still not execution-tested (no mingw/Wine in this environment); the Windows/Intel branches of the new code were written to mirror the AT&T ones and are unverified.

**Still open (found, not fixed):**
- A function or method call used as an argument of an unsafe `printf` (`printf("%llu\n", mut f(mut 4))`) segfaults; it does so on the previous build too. Bind the result to a `let` first.
- Reading an inline struct member through a heap pointer (`t.inner.a`) returns 0.
- Signed division/modulo is unsigned (`-6 / -2` = 0); this also makes negative pointer differences wrong.
- A `main` with the safe-args shape exits 1 with no output.
- `__drop_DynamicArray_char` does not free the `backing` dynarray itself (only the struct), so a `String` leaks its character buffer at scope end. Not a crash.

## New: C-style pointer arithmetic on `raw` pointers (`++`, `--`, `+`, `-`, `+=`, `-=`, pointer difference), and the two real causes of the String/DynamicArray runtime crashes

Asked for directly: "#2 it really is to be like C when it comes to arithmetic on pointers" -- and in C every one of these scales by `sizeof(pointee)`. Before this, `p++` on a `raw u64` added **1 byte**, not 8, though `stdlib/ghost_table.caspien`'s `gtSlotAt` (and its own doc comment) assumed 8.

**Semantics now (all in ASTGenerator: `TypeChecker.checkIncrement`/`checkCompoundAssign`/`checkArithmetic`, `BytecodeEmitter.emitPointerStep`/`emitPointerArith`, two new `Token` fields `pointerScale`/`pointerArithKind`):**
- `p++` / `p--` / `p += n` / `p -= n`: move by `n * sizeof(pointee)` bytes. `raw u8` is unchanged (scale 1), `raw u64` steps 8, `raw SomeStruct` steps by the struct's size, a pointer to a pointer steps 8, `raw void` steps 1 (as GNU C does).
- `p + n`, `n + p`, `p - n`: a pointer (same `raw` type) moved by `n` elements.
- `p - q` (same pointee type): the element count between them, typed `s64`.
- The integer side must be `u64` or `s64` (widen a narrower one with `as u64` first). `*`, `/`, `%` on a pointer keep their old plain-numeric meaning.
- A one-byte pointee with a same-type operand (`raw u8` `++`) takes the old path and emits byte-identical bytecode. The only bytecode in the stdlib that changed is the two `INC` sites inside `gtSlotAt` (and its dundered duplicate), which now emit `PUSH 8 / ADD / ASSIGN`.
- Verified: `u64 ++ / -- / += / -= / p + n / n + p`, `u8` (unchanged), a struct pointee (`Rec`, 24 bytes: `++` -> 24, `+= 2` -> 72, difference 3 elements).
- **Known gap, separate from this change:** a *negative* pointer difference (`start - b64` when `b64 > start`) prints wrong (e.g. 2305843009213693951 instead of -1), because the backend treats ALL integer division as unsigned (`X86Backend` `DIV_INT`/`MOD_INT` -- a documented gap: "this bytecode carries only a byte size per operator, never a signedness flag"). This affects every signed division in the language, not just this: `-6 / -2` evaluates to 0. Not fixed yet.

**Why this mattered -- the ghost table.** `gtSlotAt` stepped 1 byte per slot, so every `gt_register` overwrote 7 bytes of the previous registration and only the most recently registered pointer passed `gt_alive_check`. Every earlier `owns` pointer looked dead, so `match Some(x){...}` silently skipped its block. That was the "second `match Some(...)` block never runs" symptom seen in tests. With the fix, two live objects both pass (`box v=42`, `two a=5 b=6`; the generic `Box:<u64>`/`Box:<char>` test now prints all four calls).

**The other cause of the crash (now FIXED -- see the section above): struct construction lays fields out in reverse when the run of field pushes is interrupted.** `X86Backend.findRunStart` gives up on any line that is not a plain `PUSH` (or a nested `NEW`) -- the null-out after moving an `owns` variable into a field (`ADDR x / PUSH null / ASSIGN`), the out-of-memory check after a nested `new`/`dyn(...)`, `NEW_DYN` itself -- and the whole construction falls back to plain, reversed pushes. So `new Holder{backing= arr}` puts the type id where `backing` should be (`len(h.backing)` then dereferences `1` and segfaults), and `new Outer{p= i, k= 9}` / `new Outer{p= new Inner{...}, k= 9}` read `k` as 2 (the type id), silently. This is in Codegen, not in "OTC lowering" as the older note about `struct_dynarray_field_new_segfault_probe` says. `DynamicArray<T>` and `String` are exactly this shape. (This was the state before the fix above.)

**Also still open (unchanged):** a program whose `main` takes the safe args shape (`owns some mut dynarray(...)`) exits 1 with no output, on the old compiler too; `make_safe_args` still advances its `raw mut u8` cursor with eight `cur++` (now expressible as `cur += mut 8`, unchanged for now).

## New: `String.setCharAt(_self, i, c)` -- writes through to `DynamicArray::set` by composition

Asked for directly: "String needs a setCharAt function as well, and it should be passing those commands along to the DynamicArray class by composition." `setCharAt(_self: ref some mut self, i: mut u64, c: mut char) void` takes the `match i into _self.chars.backing{...}` write-authorization that `DynamicArray::set` (`@lock(match i into self.backing)`) demands, inside the usual `match Some(_self.chars){...}`, and calls `_self.chars.set(_self.chars, i, c)` -- the same hand-off pattern `appendChar` uses for `pushBack`. An out-of-range `i` is a silent no-op (mirroring `charAt`, which returns `'\0'`). Verified: compiles and links through all four stages, and the emitted code calls `DynamicArray_char_set`. Not verified at runtime -- `String` methods still segfault (the existing struct-with-dynarray-field bug).

## New: `dyn:<T>([])` -- a typed, empty dynarray (and the stdlib code that worked around not having one)

Asked for directly: "cant we just allow empty arrays if they are immediately passed to a dyn() and that dyn is typed" -- `dyn:<T>([])`. Before this, `dyn([])` was rejected (`array literal must have at least one element`) and there was no other way to make a zero-length dynarray, which is why `String.make()` (then still named `empty()`) built a one-element array and popped the element back off.

- **Syntax:** `dyn:<T>([])`, and only with the empty literal. `T` is one type name (a generic instantiation such as `HashMapEntry:<T>` is fine, since it is resolved to a single mangled name first; a compound like `mut char` is not). The element type is **`mut T`**, the same as `dyn([seed])` gives for a `mut` seed. Also valid as `unsafe dyn:<T>([])`.
- **Errors:** bare `dyn([])` now says an empty literal has no element type and to write `dyn:<T>([])`; `dyn:<T>([1])` (a type argument with a non-empty literal, or with a string) is rejected; `dyn:<nope>([])` reports `'nope' is not a known type`.
- **Where:** `GenericsExpander.processGenericToken` special-cases the `dyn` VARREF (there is no template to monomorphize): it resolves the type argument and **leaves `genericArgs` on the `dyn` node** -- the second exception, after `receiver.method:<U>(...)`, to "TypeChecker never sees a non-null genericArgs". `TypeChecker.checkDynBuiltinCore` reads it and returns `dynarray(mut T)` with count 0. `BytecodeEmitter`'s `dyn` case skips pushing the (empty) literal and emits `NEW_DYN dynarray(T) 0`; nothing after ASTGenerator needed to change.
- **Verified:** `dyn:<u64>([])` gives `len` 0, and `resize` on it grows it and fills correctly (`len=0`, `len=3`, `b[1]=7`). A generic argument works too (`dyn:<HashMapEntry:<T>>([])`). Old and new compilers produce byte-identical bytecode for Hello World, the harness and the String-import program.
- **Stdlib updated to use it:** `DynamicArray<T>` gained `static func make()` (`new DynamicArray:<T>{backing= mut dyn:<T>([])}`); `fromSeed` stays, for starting with one element, with its "there is no way to construct an empty DynamicArray" doc line corrected. `String.make()` is now a single `new String{chars= mut new DynamicArray:<char>{backing= mut dyn:<char>([])}}`, no seed-and-pop. `HashMap.fromCapacity` starts from `dyn:<HashMapEntry:<T>>([])` and lets `resize` fill it, instead of a one-element `dyn([seed])`.
- **Not verified at runtime:** constructing a struct that has a dynarray field (`DynamicArray.make()`, `String.make()`) still segfaults, because of the existing, separate bug described in "a plain struct with an `owns mut dynarray(...)` field segfaults at runtime". The typed-empty `dyn` itself is fine (above); the crash is in the `new` around it.

**Renamed `empty()` to `make()`** (asked for directly: "empty() - thats a terrible name for the function. It's just make()"): both `String.make()` and `DynamicArray<T>.make()` (the constructors of a zero-length value) were called `empty()` when first added; the old name no longer exists anywhere in the stdlib. Naming rule from the owner: this is not C++ -- avoid names borrowed from C++ conventions where a plain word does the job.

## Changed: the whole stdlib now uses method overloading where it had worked around the lack of it

Asked for directly: "i want the entire stdlib updated to reflect the fact we now support method overloading." Every `impl` method in `stdlib/` was surveyed. The only workaround-by-name in the whole library was `String`'s concat family:
- `String.concatLiteral(_self, other: static imut string)` is now **`String.concat(_self, other: static imut string)`**, an overload of `String.concat(_self, other: ref some imut self)`. A call with a string literal picks the `string` overload, a call with a `String` picks the `String` one (both verified in the emitted `CALL` targets).
- `String.concatRef(_self, other: ref some mut self)` is **removed**. Its body was identical to `concat`'s and its signature differed only in mutability, which never distinguishes overloads (see the overloading section above), so it could not be merged by name -- but `concat` already accepts the same String as both receiver and argument (`s.concat(s, s)` type-checks), so it was redundant. Nothing in the project called it.
- No other stdlib method has a same-purpose sibling under a different name (`DynamicArray`, `HashMap`, `ProcessHandle`, and the plain-function files were all checked), so nothing else changed. `appendChar` stays as it is: it takes one `char` and does a different job.
Verified: the new `string.caspien` compiles and links, and the four calls (`concat` with a literal, with another `String`, with itself, plus the removed name's absence) resolve as above. Runtime is still unverified for `String` -- the existing `String` segfaults are unrelated and still open.

## New: method overloading in `impl` blocks (instance and static)

Asked for directly: "i need method overloading in my impls." Before this, a second same-named method in a bare `impl` was rejected outright (`'P' already has a method named 'add' in this impl block`), because `ImplInfo.methods`/`.staticMethods` are `LinkedHashMap<String, FuncInfo>` (one entry per name). Plain functions and constructors already overloaded via `functions: Map<String, List<FuncInfo>>`.

How it works now (all in `TypeChecker`):
- `ImplInfo` gained `methodOverloads` / `staticMethodOverloads` (name -> every overload in declaration order) and `candidates(name, isStatic)`, which falls back to the plain map so anything `put()` straight into `methods` still resolves.
- `registerImplMethod(...)` does the registration. The first method of a name goes into `methods`/`staticMethods` under the plain name and keeps its original mangled name (so a program with no overloads emits **byte-identical** bytecode -- verified on Hello World, the DynamicArray/HashMap/String harness, and the String-import program). A further overload is stored in the same map under the synthetic key `name#signature` (so every existing `.values()` walk -- body checking, emission, guard validation -- still sees it) and gets the mangled name `<first mangled>__sig_<signatureSuffix>`, so FUNC_START/CALL symbols never collide.
- Two methods clash when `sameSignature` says so: same parameter count, and per parameter the same **base type** and alias. Storage and mutability (`ref some mut self` vs `ref some imut self`, `mut` vs `imut`) do NOT distinguish overloads -- the same rule plain functions use. So `concat(other: ref some imut self)` and `concatRef(other: ref some mut self)` could not be merged into one name; `concat(String)` and `concat(string)` can.
- Call resolution (`checkMethodCall`, `checkStaticMethodCall`) tries each overload in declaration order and takes the first whose parameters accept the arguments (`argSatisfiesParam`), the same as plain functions.
- Only a bare `impl` may overload. An `impl Interface for T` cannot (an interface method is identified by name alone) and keeps the old error. The cross-impl ambiguity check now compares whole overload lists. Generic methods (`method:<U>`) still allow one template per name.
- New error: `'P' already has a method named 'add' with this parameter signature in this impl block`.

Verified: instance, static and generic-impl (`impl<T> Box<T>`) overloads compile, link and run (correct overload picked per call); duplicate-signature and no-matching-overload calls give the errors above. The stdlib now uses it (see the section above).

## Changed: stdlib now uses the `?` / `?catch` operator instead of spelled-out `try ... catch`

Asked for directly: "The question mark operator is there to be used. Please bring the stdlib up to scratch." Every `try EXPR catch(e){ throw e }` / `catch(e){ unsafe{ throw e } }` in `stdlib/dynamic_array.caspien`, `hash_map.caspien`, `make_safe_args.caspien`, `process.caspien` and `string.caspien` (26 sites) is now written `? EXPR`, with one `?catch(e){ ... }` declared as the first statement of each function that uses it (each function re-declares it: a pending `?catch` resets at every function/method body, see the `?` section below). Each function's `?catch` body is the exact body its sites already had, so behavior is unchanged -- confirmed by compiling a harness that instantiates every converted function (`DynamicArray` for `u64` and `char`, `HashMap<u64>.fromCapacity`, `String`, `spawn`, `make_safe_args`) against the old and the new stdlib: the higher-order bytecode (3955 lines), and the generated assembly (11164 lines), are byte-for-byte identical.

## Changed: `String.firstIndexOf` now returns `-1` when not found, instead of taking a `notFound` argument

Asked for directly: the method's `notFound: mut u64` parameter is gone; the signature is now `firstIndexOf(_self: ref some imut self, target: mut char) mut s64`, returning the index of the first match or `-1`. The proposed body (`return i`) doesn't type-check as written -- `i` is the loop's `u64` index and `as` only widens within one signedness family, so `u64` can't become `s64` -- so the method keeps its own signed counter, `let position = mut -1` (a negative literal is `s64`; a plain `mut 0` is always `u64`, even under a `let:<mut s64>` annotation), incremented at the top of each loop iteration, and returns that. No caller of `firstIndexOf` exists anywhere in the project. Verified: the stdlib compiles and links through all four stages. Not verified at runtime: running `String` methods still segfaults for an unrelated, still-open reason (see the `String.make()` / `len(s.chars.backing)` crashes), so the returned values themselves haven't been observed.

## New: Return Value Optimization -- returning structs by value via a hidden destination pointer

Asked for directly: "I now need to support Return Value Optimization;
returning structs on the stack by manipulating a hidden struct on the
stack in the parent frame," with the classic example:

```
struct Point{
	x:mut u64
	y:mut u64
}

func newPoint() Point{
	return Point{x:23,y:45}
}

func main()void{
	let point = newPoint()
}
```

This is the real x86-64 "hidden sret pointer" ABI convention (System V
RDI / win64 RCX): the caller allocates the struct's storage in its own
frame and passes that address as an implicit leading argument; the
callee writes the struct's fields directly through it rather than
building a value that gets copied back afterward.

### The whole feature lives in this compiler stage only -- zero downstream changes

Before writing any code, this was proven directly: a hand-authored `.hob`
file implementing the exact proposed bytecode shape (below) was run
through the real, completely unmodified `caspien-optimizer` ->
`caspien-lowerorder` -> `caspien-codegen` pipeline, assembled, linked, and
executed, printing the correct result. The key fact this relies on:
`ASSIGN` was already confirmed completely agnostic to *how* its own
destination address arrived on the stack -- a plain `ADDR name Type`
(a named local's own computed address) works identically to a plain
`PUSH pointerVarName PointerType` (an arbitrary already-computed pointer
value read from a parameter). Since a function parameter is already just
an ordinary named value once `ArgToAllocLoweringPass` (in the optimizer)
lowers it, no ABI-level register-shifting or new mnemonics were needed
anywhere past this compiler.

### Scope, deliberately narrow for v1

Modeled on this project's own established pattern (`?`, `continue`,
`&&then`) of shipping a narrow, fully-correct, clearly-documented slice
rather than full generality in one pass:

- Only a plain struct return type (no storage modifier) is affected --
  `TypeChecker.buildFuncInfo`'s existing "a value type without a storage
  modifier can't be returned by value" check, still fully enforced for
  enum and array, is relaxed for struct only.
- A struct-RVO call (`TypeChecker.Token.isStructRvoCall`, set by
  `checkCall`) is only legal in exactly two positions: a fresh `let`
  binding's own right-hand side, or a plain assignment's right-hand side
  with a **bare-variable** target. Anywhere else -- a function argument,
  nested in a larger expression, a `return` statement's own expression, a
  bare/ignored statement, a dotted or indexed assignment target -- is a
  compile error ("bind it to a 'let' first"). Enforced via the same
  "single-shot eligibility flag" pattern `&&then`/`||then` already
  established (`TypeChecker.insideStructRvoDestinationPosition`, captured
  and reset once at the very top of `resolveOperatorType`, re-armed only
  at the two legal call sites in `checkAssign`) -- including a
  transparency fix for the `mut`/`imut` wrapper case (see below).
- The struct-by-value-returning function's own `return EXPR` is restricted
  to either a **fresh struct-literal expression** (an `INSTANTIATE` node,
  e.g. `return Point{x:23,y:45}`) or a **bare, already-existing local
  variable** (a `VARREF`, e.g. `return p`) -- checked in `TypeChecker.
  checkReturn`. A dotted field-access chain (`return self.point`) or any
  other kind of expression isn't accepted yet -- kept narrow for the same
  "one legal shape at a time" reason as everywhere else in this feature,
  not because of any deeper semantic concern. See "returning an existing
  variable" below for why the bare-variable case needed no new move/
  destruct machinery of its own, unlike what was originally assumed.

### The `mut`/`imut` transparency fix

A fresh struct literal always has indeterminate mutability, and a `let`
binding's own right-hand side must have a *concrete* mutability -- so the
realistic form of this feature's own example is `let point = mut
newPoint()`, not the bare `let point = newPoint()`. That interposes a
`"mut"` OPERATOR node between the `let`'s own RHS (where eligibility is
armed) and the real CALL node underneath it. `resolveOperatorType`
captures-and-unconditionally-resets the eligibility flag at its own very
top, before dispatching into any operator's own case -- correct for
almost every operator (stops eligibility leaking into an unrelated nested
expression), but wrong for `mut`/`imut` specifically, since they're pure
compile-time retags with no other operand and should be transparent to
whatever eligibility a wrapped expression already had. Fixed by re-arming
`insideStructRvoDestinationPosition` immediately before dispatching into
`checkMutabilityOf`, right in the `"mut"`/`"imut"` case of
`resolveOperatorType`'s own switch -- and, symmetrically on the
`BytecodeEmitter` side, `emitAssign`'s own `isStructRvoCall` check first
unwraps any number of `mut`/`imut` wrappers (`unwrapMutWrappers`) to find
the real CALL node, since `mut x`/`imut x` have no bytecode presence of
their own at all.

### Callee side (`BytecodeEmitter.emitFuncUnderName`/`emitStructRvoReturn`)

A struct-by-value-returning function gets one synthetic, hidden leading
parameter, `$ret_dest` (the `$`-prefix hidden-variable convention already
established by the for-loop's own hidden range variable -- the Lexer can
never produce `$` inside a real identifier, so this can never collide),
emitted before every real, user-declared `ARG` line:

```
ARG $ret_dest raw_mut_Point
```

`ArgToAllocLoweringPass` (in `caspien-optimizer`) needed zero changes --
confirmed directly, it already lowers any declared `ARG name type` line
into an `ALLOC`+load sequence with no special-casing by name.

`return Point{x:23,y:45}` then compiles to:

```
PUSH $ret_dest raw_mut_Point
PUSH 0 imut_u64
PUSH 23 indeterminate_u64
PUSH 45 indeterminate_u64
ASSIGN mut_Point indeterminate_Point mut_Point
PUSH $ret_dest raw_mut_Point
RET raw_mut_Point
```

-- the struct literal's own ordinary flat field-push sequence
(`emitExpr`, completely unchanged, genuinely oblivious to the fact its
result is headed through a pointer) followed by one whole-struct `ASSIGN`
into `$ret_dest`, then a `RET` of the same destination pointer -- matching
real sret-convention code, which conventionally also returns the
destination pointer in RAX, even though this project's own call sites
never read it back. `RET`/`PUSH_RET` needed no changes at all: already
fully generic for any pointer-sized return type (an `owns`/`raw` struct
pointer return was already legal before this feature).

### Caller side (`BytecodeEmitter.emitStructRvoAssign`)

```
let point = mut newPoint()
```

compiles to:

```
ALLOC point mut_Point
CC_START sysv_x64
PUSH point mut_Point
ADDR_OF RAW mut_Point raw_indeterminate_Point
POP ARG0 raw_indeterminate_Point
CALL newPoint
CC_END sysv_x64
```

No `PUSH_RET`/`ASSIGN` at all -- `point`'s own memory is already fully
populated by the callee by the time the call returns. The hidden-argument
push deliberately mirrors, byte for byte, the bytecode shape a real,
ordinary `raw destVar` call argument already produces (confirmed directly
by probing an already-legal program, `takesPtr(raw p)`) -- including
relying on the pre-existing rule that a `raw_indeterminate_...`-typed
argument (what `raw x` always produces -- indeterminate mutability,
regardless of `x`'s own) freely coerces to a `raw_mut_...`-declared
parameter. Every real, user-written argument is transferred exactly as an
ordinary call would (`emitArgTransfer`/`ArgCounters`, reused unchanged),
just with the hidden pointer occupying register slot 0 ahead of them --
`ArgCounters` is a genuinely running per-call counter, so nothing about
the ordinary-argument machinery needed to know this call was any
different.

### Returning an existing variable (`return p`)

Extended right after the literal-only v1 shipped, once asked directly:
"the first thing would be `return someExistingVariable` -- here the
return would just copy the local struct into the rvo struct." That's
exactly right, and turned out to need almost no new work:

- **`BytecodeEmitter.emitStructRvoReturn` needed zero changes.** It was
  already written against `expr.resolvedType` and a generic
  `emitExpr(expr)` call, never against "this is definitely a literal" --
  a fresh struct literal's own flat, one-push-per-field sequence and a
  bare variable's own single "PUSH varName Type" whole-value read both
  push the struct's full byte width, and `ASSIGN` was already confirmed
  agnostic to which of those shapes produced it. Only
  `TypeChecker.checkReturn`'s own restriction (INSTANTIATE-only) was
  ever actually stopping this -- relaxed to also accept a bare `VARREF`.
- **No new move/destruct machinery was needed either**, despite that
  being the original, more cautious assumption (see the CLAUDE.md
  history/git blame around this section for the original, narrower
  wording). The concern had been: what if the returned struct has a
  nested `owns` field -- does writing it through the caller's raw
  pointer, instead of an ordinary local-to-local copy, break some
  ownership invariant? It can't, because this project's ownership
  tracking is already flat *everywhere*, by design: `markMovedIfOwned`/
  `scope.ownsDeclaredHere` both gate strictly on
  `"owns".equals(declaredType.storage)` -- a plain struct-by-value local
  is never tracked for a move or a `GT_DESTRUCT` at all, regardless of
  what's nested inside it. Destructing/moving a struct's own nested
  `owns` members is explicitly documented elsewhere in this file as
  deferred to a not-yet-built later stage, for *every* existing
  struct-by-value use already (an ordinary `let p2 = p1` struct copy, a
  struct passed by value as a parameter, ...) -- not something RVO
  introduces or needed to newly solve. A bare-variable RVO return is
  therefore exactly as safe, and exactly as limited, as any other
  struct-by-value read this language already allows.
- Still restricted to a *bare* variable, not a dotted field-access chain
  (`return self.point`) -- kept out for the same "one legal shape at a
  time" reason as the rest of this feature's scoping, not a semantic
  concern; likely the next thing to extend.

### Returning a dotted field-access chain (`return self.point`)

Extended again right after, once asked directly: "return self.point --
this is just following the pointer and doing the same." Confirmed
correct, and needed only one, narrower-than-expected change:

- `TypeChecker.checkReturn`'s own accepted-shapes check now calls
  `isAddressableLvalue` (already existing, already used by `raw`/`auto`/
  `ref` for the identical underlying question) instead of a bare
  `retExpr.type == VARREF` test -- accepting a `VARREF`, or a `.` chain
  of member accesses rooted in one (through any number of levels), or
  either wrapped in transparent grouping parens. A chain rooted in
  anything else (a `LOOKUP`, a call result, ...) is still rejected.
- **`BytecodeEmitter` needed zero changes again**, for the same reason
  as the bare-variable case: `emitStructRvoReturn` was already generic,
  and `emitDot` already handles a whole-struct-typed field exactly like
  any other field -- a single `"PUSH qualifiedName Type"` line when the
  chain is plain-value-rooted with no pointer anywhere along it (e.g.
  `self` itself has no storage), or the existing `DOT_LHS`+`DEREF`
  address-then-load path when `self` or an intermediate segment is
  itself a pointer (confirmed both ways -- see fixtures below). Neither
  path has ever special-cased field width, so a struct-sized field was
  already exactly as supported as a scalar one; nothing about this
  feature's own read side needed to know a field access was involved at
  all.

A genuinely separate, pre-existing bug was found (not fixed, not part of
this feature) while building the pointer-rooted fixture: **passing a
struct by value as an ordinary function argument is currently broken**
-- `func showPoint(p: mut Point) void { ... }` called as `showPoint(p)`
either prints garbage field values or segfaults, confirmed with a
minimal fixture that has nothing to do with RVO at all (no struct
return, no hidden pointer, just a plain by-value struct parameter). Not
investigated further or fixed -- flagging it here since it's exactly the
kind of gap this project's own CLAUDE.md convention is to record, not
silently work around. The RVO fixtures below route around it by taking
struct receivers via `raw`/`auto` pointer parameters instead of by
value, which already worked correctly before and after this feature.

### Returning an indexed element (`return arr[i]`)

Extended again once asked directly why `arr[i]` specifically wasn't
supported: "what is the issue here?" The honest answer turned out to be
"the wrong precedent was reused, not a real gap" -- `isAddressableLvalue`
(borrowed wholesale for the dotted-field case above) answers a strictly
*harder* question than RVO's `return` restriction actually needs to ask.
`raw`/`auto` need a real, computable *address*, and their own `ADDR_OF`
lowering only ever supported a statically-known offset -- never a
runtime-computed array index -- which is why `isAddressableLvalue` never
had a `LOOKUP` case at all. RVO's own `return` path never computes an
address at all, though -- it just *reads* the existing value
(`emitExpr`) and copies it through `$ret_dest` -- and `emitLookup`'s own
"LOOKUP baseType indexType resultType" was already exactly as
size-generic as `PUSH`/`DOT`/`ASSIGN` everywhere else (confirmed
directly: `let p = mut arr[1]` for an array of structs already compiled
and ran correctly, completely independent of RVO, before this was ever
extended).

Implemented via a new, dedicated `isReadableValueChain` helper (deliber-
ately not a change to `isAddressableLvalue` itself, since `raw`/`auto`'s
own restriction has its own, real, separate reason and shouldn't be
loosened by this) -- accepts a `VARREF`, a `.`/`LOOKUP` chain rooted in
one, or any nesting of the two, with **one deliberate, tested exclusion**:
a `.` node whose own base contains a `LOOKUP` anywhere below it (e.g.
`arr[i].field`) is rejected, even though the *reverse* nesting
(`self.arr[i]`, a `LOOKUP` whose base contains a `.`) is accepted. This
isn't a scoping choice -- it's routing around a real, confirmed,
pre-existing `caspien-codegen` gap, found by testing rather than assumed:
`X86Backend`'s own read-`DOT` falls back, whenever its base can't be
resolved to a flat compile-time name and isn't itself a pointer either,
to extracting the field from an already-pushed value block -- and that
fallback's `size > 8` branch is a still-open `TODO(codegen): DOT of a
>8-byte nested field not yet implemented -- pushing 0 as a placeholder`.
Confirmed directly: `arr[i].point` (`Point` being 16 bytes) compiled
clean but silently returned zeroed fields at runtime; `h.arr[1]` (the
reverse nesting) and a bare `arr[i]` were both confirmed fully correct
end to end. Not fixed here (a `caspien-codegen`-stage change, out of
scope) -- routed around precisely instead of either being silently
permitted (which would have shipped a compiles-clean-but-wrong-value
RVO path) or being overbroadly rejected (which would have also blocked
the already-safe `self.arr[i]` shape for no reason).

### `return arr[i].field` -- the one exclusion above is now lifted

The `caspien-codegen` gap that motivated `isReadableValueChain`'s own
"one deliberate, tested exclusion" (just above) is now fixed for real
-- asked for directly, once told the fix landed: "yes, i want to be
able to return structs by rvo." See `caspien-codegen`'s own CLAUDE.md,
"Fixed: `DOT` of a >8-byte nested field..." for the full account (the
wrong-anchor addressing bug, and a follow-on overlapping-copy bug it
surfaced, both found and fixed via real execution tests, not just a
clean compile).

`isReadableValueChain`'s own `.`-branch `containsLookup` check --
the thing that used to reject a `.` node whose base contains a `LOOKUP`
anywhere below it -- has been removed entirely. `containsLookup` itself
is gone too, having no other caller. `return arr[i].field` is now
accepted exactly like every other `.`/`LOOKUP` nesting already was
(`return self.point`, `return arr[i]`, `return self.arr[i]`) -- no
change needed anywhere else (`BytecodeEmitter`'s own RVO emission was
already fully generic against `expr.resolvedType` and a plain
`emitExpr(expr)` call, same as every earlier extension in this
section).

The former negative fixture for this exact shape,
`rvo_struct_return_dot_after_lookup_error_test.caspien`, is gone --
replaced by a positive one, `rvo_return_dot_after_lookup_field_test.
caspien` (a `Holder` with a field *before* `point`, so the extracted
field isn't flush with either end of the containing block -- the exact
shape that caught `caspien-codegen`'s own overlapping-copy bug), which
now compiles, runs, and prints the real field values with zero
`TODO(codegen)` comments in the generated assembly.

### `return foo()` -- tail-forwarding into a third legal RVO position

Asked for directly, as a follow-on to a broader question ("why are there
restrictions at the call site... why can't it just be used in any part
of an expression?"). The honest answer worked out across that
conversation: the restriction was never fundamental to the ABI (a
hidden destination pointer, callee writes straight through it, no
register-based return at all) -- it's that the caller-side codegen
needed a real, *durable* address to hand the callee, and the only thing
this compiler knew how to compute a durable address for was a named,
`ALLOC`'d local. `return foo()` turns out to need no such address at
all, though: this function's own `$ret_dest` -- already a pointer, not
a struct needing its address taken -- can simply be forwarded straight
into `foo()` as *its* `$ret_dest`, so `foo()` writes the struct directly
into the memory the outermost original caller is waiting on, with zero
intermediate copies, however many `return foo()`-chained frames deep
this goes.

**TypeChecker**: `checkReturn` now arms `insideStructRvoDestinationPosition`
around its own `resolveExprType` call whenever `isStructByValueReturn
(func.returnType)`, making this a third legal position alongside a
`let` RHS and a bare-variable assignment RHS. The pre-existing
"fresh-literal-or-readable-value-chain" restriction gained a third
accepted shape: the (`mut`/`imut`-unwrapped) return expression itself
being a call `checkCall` already marked `isStructRvoCall` (checked via
the flag directly, never re-derived -- `checkCall` only ever sets it
once it has independently confirmed the callee returns a struct by
value *and* this exact position was eligible, so this can't be spoofed
by an unrelated call).

**BytecodeEmitter**: `emitStructRvoAssign`'s own call-sequence logic
(CC_START, hidden-arg transfer, real-argument transfer, CALL, CC_END)
was extracted into a shared `emitStructRvoCallSequence`, parameterized
over *how* the hidden destination pointer's own value gets pushed --
`emitStructRvoAssign` still takes a struct value's address
("PUSH destVar; ADDR_OF RAW"), while the new `emitStructRvoForwardCall`
just forwards the already-a-pointer `$ret_dest` directly
("PUSH $ret_dest"). `emitStructRvoReturn` picks between this and the
pre-existing "PUSH $ret_dest; emitExpr(expr); ASSIGN" shape based on
whether the (unwrapped) return expression `isStructRvoCall`.

**Verified**: `rvo_return_tail_call_test.caspien` (one level:
`forwardPoint()` returns `makePoint()`'s own result directly) and
`rvo_return_tail_call_chain_test.caspien` (three levels: `outer()` ->
`middle()` -> `makePoint()`, still a single hidden pointer forwarded
through unchanged at every hop) both compile with zero `TODO(codegen)`
comments and print the real field values through the real 4-stage
pipeline. The former negative fixture for this exact shape,
`rvo_struct_return_forward_error_test.caspien`, is gone (this shape is
legal now). Full fixture sweep (875 fixtures): identical 120-item
known-baseline failure list, zero regressions.

### Investigated but not shipped: a struct-RVO call as an arbitrary sub-expression (a call argument, a struct-literal field value, ...)

Asked for directly as the next step, after confirming the design
(`STACK_LOCK` already reserves raw stack bytes with no value pushed --
used today only for padding gaps inside a struct/array literal's own
construction sequence -- and a new mnemonic, deliberately bypassed by
`AddressLoweringPass`'s name-based rewriting the same way
`GT_DESTRUCT_ADDR` is, would push the address of what `STACK_LOCK` just
reserved: `movq %rsp, %rax; pushq %rax`, snapshotted once and from then
on just an ordinary pointer value, immune to any later stack movement).

Built the `ADDR_OF_STACK_LOCK` mnemonic's design and started wiring a
struct-RVO call into `raw`/`auto` (i.e. `raw makePoint()` -- a struct-
RVO call as a third kind of thing `raw`/`auto` can point to, alongside
an addressable lvalue and, for `raw`, a string literal) -- then found a
genuine, second-order problem before shipping any of it, and backed the
half-finished `TypeChecker.checkAddressOf` change out again rather than
leave a change that type-checks but silently miscompiles:

**The call's own `CC_START`/`CC_END` region does real, load-bearing
stack-byte accounting that a `STACK_LOCK`'d temporary would corrupt.**
Per the "Register-overflow function arguments" fix's own design
(`argTrackDeltaStack`/`resolveStackArgBytesAndCall` in `caspien-codegen`):
whatever real bytes are still unaccounted for at a call's own "CALL"
line are, by construction, treated as that call's own stack-passed
argument bytes, to be reserved-for and discarded-after in one
specific, load-bearing way. A `STACK_LOCK`'d RVO destination reserves
real bytes that must NOT be discarded after the call returns (they hold
the actual result) and were never a real argument to begin with -- but
if the `STACK_LOCK` runs anywhere inside that same call's own
`CC_START`..`CC_END` span (which it unavoidably would, for a struct-RVO
call used as *another* call's own argument, since argument evaluation
is exactly what happens inside that span), the tracker can't tell it
apart from a genuine stack-passed argument and would misclassify it --
reserving and discarding memory that's still needed. Worse, `CC_START`
doesn't just track bytes, it opens a whole separate output buffer
(flushed at the matching `CC_END`, needed to correctly interleave
nested calls) -- so even the ordering of when code "belongs" to which
call's own accounting is more entangled here than it first looks.

**A second, separate problem**, found while working through where
`raw makePoint()`'s own destination memory could live long enough to be
useful: `let p = raw makePoint()` needs the `STACK_LOCK`'d memory to
outlive the one statement that created it -- `p` itself is a *pointer*-
typed local, not the struct storage, so the struct's own real bytes
have to keep living somewhere for as long as `p` might still be read.
This is a genuine dynamic-stack-slot lifetime question (closer to
`alloca` than anything this compiler already does -- every real local
today gets one static, whole-function `ALLOC`'d slot) that a single
`STACK_LOCK`/`ADDR_OF_STACK_LOCK` pair doesn't answer by itself.

Neither problem is a dead end -- the first is a real but scoped codegen
fix (teach the delta-tracker to recognize and exempt an RVO temp's own
reservation), and the second only bites when the pointer is meant to
outlive one statement (a `raw makePoint()` used and discarded within a
single call argument, never bound to a `let`, wouldn't need it) -- but
both need their own dedicated, carefully-verified round rather than
being bolted on alongside `return foo()`. Not shipped: no
`ADDR_OF_STACK_LOCK` mnemonic exists in `caspien-codegen`, and
`TypeChecker.checkAddressOf` is back to its pre-investigation state,
confirmed via a full rebuild and the same 120-item zero-regression
sweep as every other round.

### Verification

Fixtures: `rvo_return_value_optimization_test.caspien` (the user's own
example, extended with a `printf` readback), `rvo_reassign_test.caspien`
(the plain-assignment, non-`let` position), `rvo_imut_test.caspien` (the
`imut` wrapper), `rvo_return_variable_test.caspien` (`return p` for an
existing local), `rvo_return_dotted_field_test.caspien` (`return
h.point` through an `auto`-pointer receiver -- the `DOT_LHS`+`DEREF`
path), `rvo_return_dotted_field_local_test.caspien` (`return h.point`
where `h` is a plain, no-storage local -- the single-`PUSH` nameable-dot
path), `rvo_return_indexed_test.caspien` (`return arr[i]`),
`rvo_return_dotted_array_element_test.caspien` (`return h.arr[i]` --
the reverse nesting), `rvo_return_dot_after_lookup_field_test.caspien`
(`return arr[i].field` -- the nesting that used to be excluded, now
accepted), `rvo_return_tail_call_test.caspien` and
`rvo_return_tail_call_chain_test.caspien` (`return foo()` tail-
forwarding, one level and three), plus seven `*_error_test.caspien`
negative fixtures, one per still-rejected position (call argument,
nested expression, bare/ignored statement, dotted assignment target, an
indexed base wrapped in a fresh array literal, and a mutability mismatch
on an otherwise-legal bare-variable `return` -- the two others, for the
`arr[i].field` nesting and `return`-forwarding, are gone now that both
shapes are accepted). All run through the real 4-stage pipeline (this
compiler -> `caspien-optimizer` -> `caspien-lowerorder` ->
`caspien-codegen` -> `as`/`gcc` -> execute); the positive fixtures print
the expected field values with no heap allocation and no extra copy --
true RVO directly into the caller's own already-declared local, or (for
tail-forwarding) directly into the outermost caller's own. Full fixture
sweep (875 fixtures, after this round's two additions/one removal) re-
run after this round: identical 120-item known-baseline failure list,
zero
regressions.

### Known, deliberately-unfixed gaps found while investigating (not part of this feature)

- **Passing a struct by value as an ordinary function argument is
  broken** -- found while building the dotted-field-return fixtures (see
  above). A minimal, RVO-unrelated repro: `func showPoint(p: mut Point)
  void { printf("x=%llu y=%llu\n", p.x, p.y) }` called as `showPoint(p)`
  prints garbage instead of `p`'s real fields (a slightly larger,
  nested-struct version of the same repro segfaults outright). Not
  investigated further or fixed.
- `BytecodeEmitter.emitAssignTarget`'s generic fallback mishandles
  `deref(x) = wholeStructValue` as an assignment target (found in the
  original RVO round, unchanged since) -- emits `ADDR CALL <type>`
  (using the CALL node's own literal text "CALL" as if it were a
  variable name), which the optimizer passes through unchanged and
  `caspien-lowerorder` then misresolves into a phantom, unrelated local
  literally named "CALL". Confirmed broken by direct pipeline testing.
  Explicitly out of scope for this feature and left unfixed.

## New: `&&then`/`||then` -- lazy chain evaluation for `if`/`elseif` conditions

Asked for directly: "I need lazy chain evaluation, achieved with the then
pseudo operator," with the exact codegen contrast spelled out by example --
a plain `if someCondition() && someOtherCondition(){...}` always calls
*both* sides (`CALL someCondition; ...; CALL someOtherCondition; ...; AND;
CMP; JMP`), but `if someCondition() && then someOtherCondition(){...}` must
skip the second call entirely when the first is already false (`CALL
someCondition; ...; CMP; JMP endOfIf; CALL someOtherCondition; ...`). "So
early on compilation `&& then` ... just becomes `&&then` and `||then` are
just operators until they are parsed out to their instructions in the
hob[,] ... the whitespace isnt even necessary for the user: `&&then` and
`||then` should work just as well as `&& then` and `|| then`[.] They only
work at the top level of an expression in a conditional (if or else if
statement)."

### Architecture: a real operator all the way through, unlike the `?` pseudo-operator below

The `?` pseudo-operator (next section) is pure syntactic sugar, fully
expanded into ordinary `try`/`catch` tokens at the `Parser` stage, before
`RpnConverter`/`TreeBuilder` ever run -- no other stage needs to know `?`
existed. `&&then`/`||then` are the opposite kind of pseudo-operator: they
survive as genuine binary `OPERATOR` nodes all the way through
`RpnConverter` (real precedence-table entries), `TreeBuilder` (ordinary
arity-2 nodes, no changes needed there at all), and `TypeChecker` (a real
type-checked operator, sharing `checkLogical` with plain `&&`/`||`), and
are only ever given their real, short-circuit *instruction* shape at the
very last stage, `BytecodeEmitter` -- "just operators until they are
parsed out to their instructions in the hob," confirmed directly.

### Spelling unification: `Parser.mergeThenOperators`, not a Lexer change

No `Lexer` change was needed at all: `&&`/`||` are already in
`MULTI_CHAR_OPERATORS`, and a bare, non-reserved word like `then` already
lexes as an ordinary `VARREF` whether glued (`&&then`) or space-separated
(`&& then`) -- both tokenize identically, as `[OPERATOR "&&"/"||", VARREF
"then"]` adjacent in the flat token stream, once `Parser.removeWhitespaceInFlat`
has already stripped whitespace. The only thing needed was a new merge
pass, `Parser.mergeThenOperators`/`mergeThenInFlat`, recognizing that exact
two-token adjacency and collapsing it into one synthetic `OPERATOR` token
with text `"&&then"`/`"||then"`. It runs once, right after
`removeWhitespaceInLines` and *before* `insertCallLookupInLines`
specifically -- ordering matters, since running after would let
`&&then(x)` (no space before a parenthesized right operand) first get
`then(` misread as a `CALL` to a function literally named `then`,
corrupting the merge target before this pass ever saw the plain adjacent
`VARREF` it needs.

`then` is deliberately **not** reserved as a global keyword -- this reuses
the exact same "structural, not reserved" detection precedent
`isLockStatementStart` already established for `lock` in this codebase. A
bare `VARREF` named `then` remains completely legal as an ordinary
variable/function name everywhere else; only this specific two-token
adjacency is ever merged. Known, accepted trade-off (the identical shape
`lock` itself already accepts): `a && then` where `then` happens to be a
real boolean variable the user intended as the eager right-hand operand
(with nothing following it) is merged into a valueless `&&then` operator
missing its own right operand, and fails downstream with a generic
"missing operand" error rather than something naming this ambiguity
directly.

`RpnConverter` needed one addition: `"&&then"`/`"||then"` in `BINARY_TEXTS`,
and matching precedence-table entries -- `"&&then"` at the same tier (40)
as `"&&"`, `"||then"` at the same tier (30) as `"||"`, both left-
associative (neither added to `RIGHT_ASSOCIATIVE`) -- so a mixed chain
like `a && then b || then c && d` parses with the exact same tree shape
ordinary `a && b || c && d` would; only which AST operator text ends up at
each node differs.

### Legality: "top level" means the condition's own root, or a further `&&then`/`||then` reached from it -- never through a plain `&&`/`||`

The widest reading of "top level of an expression in a conditional" would
allow a `&&then`/`||then` anywhere in the condition's combinator tree,
including as an operand of a plain, *eager* `&&`/`||`. That reading was
deliberately rejected: an eager `&&`/`||` always evaluates **both** sides
and combines them into one pushed value (see `BytecodeEmitter.emitLogical`),
but a `&&then`/`||then` node has no such value-producing form at all -- it
only ever lowers to a jump cascade, never a pushed bool -- so there is no
sound bytecode shape for "the value of a lazy chain" that a plain eager
operator could combine with. Concretely, by ordinary precedence, `a &&
then b && c` parses as `(a &&then b) && c` -- the `&&then` node ends up as
the *left operand* of a plain, outer `&&`, which is exactly this
unsound shape, and is rejected the same as if it were nested inside a
function call.

So the actual rule, enforced in `TypeChecker`: a `&&then`/`||then` node is
legal only if it is the condition's own root operator, or every node on
the path from that root down to it (inclusive) is *also* `&&then`/`||then`
-- never through a plain `&&`/`||`. `a && then b || then c && d` is legal
(root `||then`, left = `&&then(a,b)`, right = a plain, ordinary `&&(c,d)`
leaf with no lazy operator nested inside it at all); `a && then b && c` is
rejected (the `&&then`'s parent is a plain `&&`).

Implemented with a new instance field, `TypeChecker.insideShortCircuitEligiblePosition`
-- deliberately narrower than the existing "sticky true for a whole
recursive descent" fields (`insideCatchBody`/`insideThrowingNewContext`):
it must be true for the *exact next* `resolveExprType` call only, then
immediately go back to `false` for everything that call's own resolution
reaches, so a `&&then`/`||then` buried inside a `!`, a function argument, a
comparison operand, a `match`, or a plain `&&`/`||` is correctly rejected.
Consumed once, unconditionally, right at the very top of
`resolveOperatorType` -- the single choke point every operator-shaped node
(arithmetic, comparison, call, logical, ...) passes through -- and handed
to `checkLogical` as a plain parameter (`eligibleHere`) from there, since
by the time `checkLogical` itself runs the field has already been reset.
The only two places that ever set it back to `true` are `checkIfChain`
(around a branch's own condition, the one true "top level") and
`checkLogical` itself (around each of `op.left`/`op.right`, but *only*
when `op` is itself `&&then`/`||then` -- never for a plain `&&`/`||`).
Verified directly: `then_operator_not_top_level_error_test.caspien` (inside
a `!`), `then_operator_function_arg_error_test.caspien` (as a call
argument), `then_operator_let_rhs_error_test.caspien` (as a `let`'s
right-hand side), and `then_operator_nested_plain_and_error_test.caspien`
(nested under a plain `&&`) all fail with the same, single, deliberately
generic "not top level" type error.

### Codegen: `BytecodeEmitter.emitShortCircuit` -- a cascading CMP/JMP, deliberately with no `AND`/`OR` instruction

`emitIfChain`'s per-branch condition check now dispatches on whether the
condition's own root is `&&then`/`||then` (`BytecodeEmitter.isThenVariant`):
if not, the pre-existing, unchanged eager path (`emitExpr; CMP; JMP
nextLabel`); if so, the new `emitShortCircuit(cond, nextLabel, null)`.

`emitShortCircuit(node, falseLabel, trueLabel)` -- exactly one of
`falseLabel`/`trueLabel` is ever non-null per call, the other meaning
"just fall through on that outcome" -- recurses only through further
`&&then`/`||then` nodes (guaranteed by the TypeChecker rule above to never
have a further lazy operator buried any deeper than that); anything else
(a plain `&&`/`||` subtree included) is an opaque leaf, emitted completely
normally via `emitExpr` (preserving that leaf's own "always evaluate both
sides" guarantee exactly, since it is never decomposed) and tested with a
single `CMP`:
  - `&&then`, ordinary case (`trueLabel == null`): both operands cascade
    against the *same* `falseLabel` -- `emitShortCircuit(left, falseLabel,
    null); emitShortCircuit(right, falseLabel, null)`.
  - `||then`, ordinary case (`falseLabel == null` on the *recursive* call
    only): the left operand jumps straight to a local `passLabel` if true
    (skipping the right operand entirely -- "A was true -> skip straight
    into the body, don't evaluate B at all," confirmed directly), else
    falls through to test the right operand against the real `falseLabel`.
  - The two "reversed" cases (an `&&then` as the left operand of an
    enclosing `||then`, needing to jump to a `trueLabel` only once *both*
    sides pass; an `||then` as the left operand of an enclosing `||then`,
    forwarding the same `trueLabel` to both of its own operands) are
    handled by the identical recursive function, just with the two
    parameters' roles swapped.
  - A leaf tested for "jump on false" uses the ordinary, already-proven
    `CMP; JMP falseLabel` pairing directly. A leaf tested for "jump on
    true" (needed only for a `||then`'s own left operand) has no dedicated
    opcode, so it's built from the same pairing plus one extra
    unconditial jump: `CMP; JMP skip; JMP trueLabel; skip:`.

Deliberately emits **no** `AND`/`OR` instruction anywhere in the cascade,
diverging slightly from the user's own literal sketch (which included one
for `&&then`): each operand independently determines whether to jump away,
so no combined boolean value is ever needed -- ANDing with a value already
known (from having fallen through) to be "true" is a no-op -- and omitting
it avoids any question of whether `CMP` pops or peeks its operand, staying
entirely within the already-proven "one push, one CMP, one JMP" pairing
used everywhere else in this codebase.

### Verification

Three new fixtures, run through the real 4-stage pipeline (this compiler
-> `caspien-optimizer` -> `caspien-lowerorder` -> `caspien-codegen` ->
`as`/`gcc`) as actually compiled, linked, and run binaries, each writing an
observable `printf` marker from inside the "right side" function so a
skipped call is provably never made at runtime, not just a shape read off
the bytecode:
  - `then_operator_and_short_circuit_test.caspien`: `leftFalse() && then
    rightSideEffect()` -- confirmed `rightSideEffect`'s own "right called"
    print never happens; `leftTrue() &&then rightSideEffect()` (glued
    spelling) still calls it and takes the branch.
  - `then_operator_or_short_circuit_test.caspien`: the mirror-image `||
    then` case -- `leftTrue() || then rightSideEffect()` never calls the
    right side; `leftFalse() ||then rightSideEffect()` (glued spelling)
    does.
  - `then_operator_mixed_chain_test.caspien`: `a() && then b() || then
    c() && d()` -- confirms the full mixed-precedence shape end to end:
    `a()` false skips `b()` entirely (no "b called"), then the plain,
    eager `c() && d()` leaf still calls **both** `c()` and `d()` (both
    print) even though `c()` itself is false, exactly preserving eager
    `&&`'s own "always both" guarantee for that leaf.

Four new negative fixtures confirm the "top level only" restriction is
enforced (see the Legality section above for exactly which ones and why).

Ran the full fixture sweep (855 fixtures total) against the rebuilt
compiler: the same, pre-existing 120 unexpected results as the prior
baseline (unrelated, already-known gaps -- array bounds-checking, `new`'s
mandatory `try`/`catch` wrapping, etc.), byte-for-byte the same fixture
names as the baseline list -- zero regressions, and none of the seven new
`then_operator_*` fixtures among them.

## New: the `?` pseudo-operator -- pure syntactic sugar for `try EXPR catch(e){ BLOCK }`

Asked for directly, confirmed as "unused in the Caspien language so far,"
with the exact expansion spelled out: "`?catch(e){...}` ... and `?EXPR` ...
expands earlier on compilation" into the exact same `try EXPR catch(e){
BLOCK }` shape already fully implemented (see the real `try`/`catch`
unwind-redirect section below), where `BLOCK` is "the block from the most
recently encountered `?catch` statement":

    try{
        ?catch(e){ continue }
        let x = ?thingIwantToIgnoreIfProblem()
        let x2 = ?thingIwantToIgnoreIfProblem2()
        ?catch(e){ throw e }
        let x = ?thingIfHasProblemICantIgnore()
    }

`?` is legal in exactly two shapes, both purely declarative/expansive --
neither ever reaches RPN conversion, tree-building, type-checking, or
bytecode emission as itself: **`?catch(e){ BLOCK }`**, a standalone
declaration statement (produces no code of its own -- it only updates
which catch body is currently "pending"), and **`?EXPR`**, legal
"whenever a `try` (not a `try{}`) is legal" -- i.e. exactly the two
positions a real `try EXPR catch(e){...}` is: a bare statement (`?foo();`)
or a `let`/assignment's own right-hand side (`let x = ?foo()`), wrapping
either an ordinary `@throws` call or a throwing `new` (`let p = mut ?new
Point{x:12,y:13}`) -- expanding to `try EXPR catch(e){ pendingBlock }`
using whichever `?catch(...)` was most recently declared.

**Where this is implemented, and why**: entirely inside `Parser.
gatherKeywordBlocks`, at the raw-token level, BEFORE `RpnConverter`/
`TreeBuilder` ever run -- "it's pure syntactic sugar" taken completely
literally: `?catch(e){...}`/`?EXPR` are fully desugared into the exact
same token shape a hand-written `try EXPR catch(e){...}` already produces
(the same synthetic `KEYWORD "try"` token, with `.sub`/`.childs`/
`.catchParamName` populated identically), so every downstream stage --
`RpnConverter`, `TreeBuilder`, `TypeChecker`, `BytecodeEmitter` -- needed
**zero changes**. This is a deliberate, load-bearing design choice, not
an accident of where the code happened to land: it guarantees `?` can
never behave even slightly differently from its own hand-written
expansion, since after expansion there is no longer any way to tell the
two apart at all.

**`?` is added to `Lexer.SINGLE_CHAR_OPERATORS`** (a plain `OPERATOR`
token, text `"?"`) -- but, per the above, this is the *only* place
outside `Parser` that ever needs to know `?` exists; nothing downstream
recognizes it as an operator of its own.

**`?catch(e){ BLOCK }` recognition**: checked at the very top of
`gatherKeywordBlocks`'s per-line loop, before everything else (including
the ordinary real-`try` scan) -- `?` must be the line's own first token,
immediately followed by the `catch` KEYWORD. Its own `"(e){...}"` shape
is parsed by a new, shared `gatherCatchClause` helper, factored out of
`gatherTryCatchSpan`'s own former inline logic (byte-for-byte identical
validation: exactly one plain, untyped parameter name; a mandatory
trailing `{...}` block; nothing may follow that block's own closing `}`
on the same line) -- both a real `try...catch(...)` and a `?catch(...)`
declaration now share this one helper, differing only in the error
message's own wording and one further difference below. Matching
declares (or replaces) two new `Parser` instance fields,
`pendingOptionalCatchParamName`/`pendingOptionalCatchRawBody`; the
declaration line itself is dropped entirely -- nothing is added to
`gatherKeywordBlocks`'s own `result` list for it.

**Why the pending catch body is kept genuinely raw, not eagerly
gathered**: a `?catch(...)`'s own declared block may be spliced into
*many* different `?EXPR` use sites later on, each needing its own,
completely independent copy -- gathering/RPN-conversion/tree-building/
type-checking all mutate tokens in place, so reusing the same Token
objects across two different use sites would corrupt one from the
other's own check (the same reasoning, applied to the same problem
shape, this project's own pre-existing `TypeChecker` "impl default lock
match" policy template already established: "kept completely raw...
deep-clones a fresh copy... only that copy is ever gathered"). So
`gatherCatchClause` takes a `gatherBodyNow` flag: `true` for a real
`try...catch` (gathered immediately, used exactly once, unchanged from
before this round), `false` for a `?catch(...)` declaration (left raw).
Every `?EXPR` use site then deep-clones the raw body via the
pre-existing `Token.deepCloneRaw()` (written for, and already proven by,
that same "impl default lock match" precedent) and gathers *that* fresh
clone via an ordinary `gatherKeywordBlocks` call -- confirmed directly
against real compiled bytecode: two independent `?f(...)` uses of the
same `?catch(e){ continue }` declaration produce two entirely separate
hoisted catch blocks (`@catch_3`/`@catch_4` in one such run), each with
its own `ALLOC e` and its own `JMP @after_try_block_...`, never aliased.

**"Most recently encountered" is unscoped, sequential state, not a
lexical declaration** -- the single most consequential design decision
this feature required, since the spec's own wording ("the block from the
most recently encountered `?catch` statement") never says whether a
`?catch` declared inside a nested block (an `if`, a loop, ...) should
stop applying once that nested block ends. Resolved in favor of the most
literal reading: `pendingOptionalCatchParamName`/`pendingOptionalCatchRawBody`
are plain, shared `Parser` instance fields, updated in place as
`gatherKeywordBlocks`'s single, depth-first walk proceeds through a
function body, with **no save/restore around any nested block at all**
(`if`/`match`/`loop`/`for`/`cast`/`unsafe`/`safe`/`lock`/a real
try/catch's own catch body/a `try { ... }` block's own body all
recurse through this exact same method, so they naturally inherit
whatever is currently pending, and a `?catch` declared *inside* one of
them remains in effect after returning from it too) -- deliberately
unlike a lexically-scoped variable declaration. The only reset is at a
genuinely new function/method body's own start (`gatherFunc`, the single
choke point both plain functions and `impl` methods already funnel
through -- `gatherImpl` calls `gatherFunc` directly per method), so one
function's own `?catch` declarations can never leak into a sibling
function or method; confirmed directly by construction
(`optional_try_state_resets_per_function_error_test.caspien`).

**Known, deliberately unaddressed gaps**, flagged rather than silently
handled: (1) a second, nested `?` inside a `?EXPR`'s own wrapped
expression (e.g. as one of its call arguments) is never recognized --
only the *first* `?` on a line is ever treated as the sugar's own marker,
so a nested one survives untouched into the wrapped expression's own raw
tokens and fails downstream with an ordinary, if unhelpfully generic,
parse/RPN error; (2) if a `?catch(...)`'s own declared block itself
contains further `?catch`/`?` sugar, that inner sugar is only expanded
lazily, once per outer use site, through this same unscoped shared-state
mechanism -- any `?catch` encountered while gathering one use site's own
clone remains in effect afterward, for whatever code lexically follows
that particular `?EXPR` in the *enclosing* scope too. Neither shape
appears anywhere in the feature's own spec or examples.

**New fixtures**: `optional_try_basic_test.caspien` (the user's own
worked example almost verbatim -- confirms the "most recently
encountered" switch mid-block: two calls share one `?catch(e){ continue
}`, a third uses a later `?catch(e){ return }`); `optional_try_continue_
skips_rest_test.caspien` (the first call actually throws -- confirms
`continue`, reached via the sugar, really does skip the rest of the
enclosing `try { ... }` block, not just look like it should in the
bytecode text); `optional_try_new_test.caspien` (`?new Point{...}`,
confirming `?` covers the throwing-`new` shape, not just an ordinary
`@throws` call); `optional_try_no_decl_error_test.caspien` (a bare `?`
with no preceding declaration at all); `optional_try_state_resets_per_
function_error_test.caspien` (a `?catch` declared in one function must
not leak into a later, sibling function); `optional_try_continue_no_
enclosing_try_block_error_test.caspien` (the sugar doesn't relax
`continue`'s own real-enclosing-try-block requirement at all);
`optional_try_catch_bad_param_error_test.caspien` (`?catch` shares real
`catch`'s own mandatory "exactly one plain, untyped parameter" grammar
rule).

**Verified end to end**, not just at the type-checker: `optional_try_
basic_test.caspien` and `optional_try_new_test.caspien` were run through
the real 4-stage pipeline (this compiler -> `caspien-optimizer` ->
`caspien-lowerordergenerator` -> `caspien-codegen`) and the real linked
binary on `linux`/sysv_x64 -- zero `TODO(codegen)` lines, and each
printed its documented expected output (`x2=2 x3=3` / `after`, and `x=12
y=13`, respectively) with exit code 0. `optional_try_continue_skips_rest_
test.caspien` likewise run and confirmed to print only `after` (skipping
the wrapped call's own `printf` entirely), proving the `continue` reached
through `?catch(e){ continue }` really does jump past the enclosing
try-block at runtime, not merely in the emitted bytecode's own text. Full
~841-fixture pre-existing corpus regression sweep (before vs. after every
change in this round): the exact same 120 fixtures are "unexpected" both
before and after -- confirmed by diffing the two sweeps' own sorted
fixture-name lists directly, zero difference -- plus the 7 new fixtures
above, each giving exactly its intended result.

## New: `continue` -- a catch body's third valid terminator, jumping to just past its enclosing `try { ... }` block

Asked for directly, as a resolution to a real ambiguity in the `try { ... }`
block feature documented just below: "the /*block*/ must contain a return
or throw, because of ambiguity about fall thru... A fall thru... should be
to just beneath the surrounding try block, IF there is one, if there isn't
its a syntax error... Furthermore, a fallthru should actually be achieved
with a continue," confirmed directly:

    try{
        let x = try foo() catch(e){ continue }
    }
    //continue comes out here -- x was never legal anyway

**Grammar**: a brand-new reserved keyword (`Lexer.KEYWORDS`), with no
expression of its own -- like `break`, it's a bare statement (`RpnConverter`/
`TreeBuilder` both route it through the identical `case "break": case
"continue":` no-operand shape `break` already has).

**The rule, enforced by `TypeChecker.checkStatement`'s new "continue" case,
in this order**: (1) `continue` is a hard error anywhere outside a
`catch(e){ ... }` body (new `insideCatchBody` instance field, save/restored
around `checkTry`'s own catch-body check, exactly like the pre-existing
`insideThrowingNewContext` pattern -- chosen over threading a new parameter
through the whole `checkStatement`/`checkLines` call chain); (2) even inside
a catch body, `continue` is a hard error unless that catch is itself
lexically inside an enclosing `try { ... }` block -- "if there isn't [one],
it's a syntax error - the fallthrough is not allowed," confirmed directly.
`Scope.tryBlockBoundaryScope` is the new mechanism for (2): the identical
`Scope.loopBoundaryScope`/`break` pattern, mirrored exactly -- a new private
`TryBlockBoundaryMarker` enum (`{ MARK }`) disambiguates a new `Scope`
constructor overload from the pre-existing `Scope(Scope, boolean, boolean)`
constructor, which a second `boolean` parameter would otherwise collide
with under Java erasure; `checkTryBlock` uses this new constructor to mark
a try-block's own child scope as a `tryBlockBoundaryScope` root, and every
other `Scope` constructor inherits its parent's value unchanged. `continue`
also computes its own `destructOnExit`/`unlockOnExit` against
`scope.tryBlockBoundaryScope`, via the same `collectOwnsToDestruct`/
`collectLockReleasesToBoundary` helpers `break` already uses against
`scope.loopBoundaryScope`.

`checkTry`'s own "catch must be terminating" requirement (see the real
`try`/`catch` unwind-redirect section below) now accepts `continue` as a
third legal terminator alongside `return`/`throw` (`statementDefinitelyReturns`'s
own "continue" case, alongside "return"/"throw"); `checkNoCodeAfterTerminalStatement`'s
error message was updated to mention it too.

**Known, deliberately accepted gap in the "unreachable code" check, found
directly during this round's own fixture-writing** (not a corner case
imagined in advance): `checkNoCodeAfterTerminalStatement` is a purely
*structural* pre-pass -- it walks `statementDefinitelyReturns` over a
block's own raw statement list once, at the very top of `checkLinesInScope`,
BEFORE any of that block's own statements are individually validated via
`checkStatement`. Since `statementDefinitelyReturns`'s new "continue" case
returns `true` unconditionally for any literal `continue` token, wherever
found -- it has no way to first confirm that `continue` is actually legally
placed -- a *misplaced* `continue` (outside any catch body, or inside a
catch with no enclosing try-block) that has real code following it
anywhere in its own enclosing call chain gets flagged "unreachable code:
nothing may follow a statement that always ends this block's own
execution" FIRST, before the real, more specific "'continue' can only be
used inside a 'catch(e) { ... }' block" error the misplaced statement
itself would otherwise produce ever gets a chance to run. Confirmed by
direct construction: an early draft of
`continue_outside_catch_error_test.caspien` had a trailing `return` in
`main()` after the whole `unsafe`/`try` block containing the misplaced
`continue`, and reliably hit the "unreachable code" message instead of the
intended one. Not fixed -- both messages are truthful (the code really is
unreachable, in the sense that this path never falls through regardless of
which reason is cited), and this is the exact same category of intentional
"structural, not full reachability analysis" scope limit
`collectNaturalEndDestruct`'s own doc comment already flags for a
different, adjacent case. The fixture itself was written to avoid tripping
this (no statement follows the misplaced `continue` anywhere in its own
enclosing scopes), so it actually isolates and exercises the intended,
more specific check.

**The real bytecode lowering -- the hardest part of this feature, and the
one genuine bug found and fixed while building it.** `continue` compiles to
a jump straight to a label emitted immediately after its target try-block's
own body (`BytecodeEmitter`'s "try" `isTryBlock` case in `emitStatement`) --
conceptually the exact same shape `break` already has for `loopEndLabels`,
just targeting a try-block's own end instead of a loop's.

**First attempt, and why it broke**: implemented as a live `List<String>
tryBlockEndLabels` stack -- pushed with a freshly generated label when
`emitStatement` walks into a try-block's own `isTryBlock` case, popped
right after -- the identical mechanism `loopEndLabels`/`break` already
uses successfully. This crashed immediately, on both positive test
fixtures, with `IllegalStateException: internal error: 'continue' reached
bytecode gen with no enclosing 'try { ... }' block`, despite
`TypeChecker` having already confirmed a real enclosing try-block exists.

**Root cause**: a REAL catch body (the only place `continue` can ever live)
is hoisted out of its lexical position entirely -- `emitFuncUnderName`
calls `emitHoistedCatchBlocks` (which emits every real catch body in the
function, up front) BEFORE it calls `emitBlock` on the function's own real,
in-place body (where the try-block itself -- and, under the first attempt,
its label push -- would normally be reached, in program order). So by the
time a hoisted catch's own `continue` needs to consult the live stack, the
try-block lexically containing that catch hasn't been walked (and its
label pushed) yet at all -- the stack is still empty. This is exactly the
same "hoisted code needs to reference something from its own future
lexical surroundings" problem `tryCatchLabels`/`collectTryCatchNodes`
already solved for a real catch body's own label, just one level removed
(a `continue` *inside* that hoisted catch body needing a label from
*outside* it, rather than the catch body needing its own label).

**The actual fix, mirroring that exact precedent**: replaced the live
stack with two new precomputed `Map<Token, String>` fields,
`tryBlockLabels` (every try-block's own "after" label, keyed by the
try-block's own Token) and `continueTargetLabels` (which label each
`continue` Token targets), both populated once per function by a new
`collectTryBlockLabels` pre-pass -- called from `emitFuncUnderName`,
save/restored around it exactly like `tryCatchLabels`, and run BEFORE
`FUNC_START` is even emitted (so certainly before any hoisted catch body
is). `collectTryBlockLabels` mirrors `collectTryCatchNodes`'s own whole-
function traversal shape exactly (if/match's `.right` chain, loop/for/
cast/unsafe/safe/lock, a real try/catch's own catch body via `tryNodeOf`),
adding a `Deque<Token> enclosingTryBlocks` stack: entering a `"try"`
(`isTryBlock`) node assigns its label and pushes/pops it around recursing
into its own body; a `"continue"` node, if the stack is non-empty, records
`tryBlockLabels.get(enclosingTryBlocks.peek())` as its own target. Since
this is a text-based bytecode format where a `JMP` can validly reference a
label appearing later in the emitted text (resolved by later pipeline
stages, not a single-pass assembler), having every label and every
target already decided *before* any of it is written out causes no
problem at all -- `emitStatement`'s own "try" case now just looks up
`tryBlockLabels.get(stmt)` and emits it as a plain trailing label, no
generation or stack manipulation left at emission time; `emitContinue`
looks up `continueTargetLabels.get(continueTok)` the same way. The
obsolete `tryBlockEndLabels` field was deleted outright once both call
sites were migrated off it.

**Verified**: 5 new fixtures --
`continue_catch_basic_test.caspien` (the ordinary case: `f(0)` throws,
`continue` skips the rest of the try-block, landing on the code just past
it); `continue_catch_nested_try_block_test.caspien` (two nested
try-blocks, `continue` targets the *inner* one specifically, not the
outer -- confirmed directly against the real compiled bytecode, the `JMP`
from the hoisted catch body lands on `@after_try_block_3`, the inner
label, not `@after_try_block_2`, the outer one); `continue_catch_no_enclosing_try_block_error_test.caspien`
(a `catch(e){ continue }` with no enclosing try-block at all -- rejected);
`continue_outside_catch_error_test.caspien` (a bare `continue` outside any
catch body, even lexically right next to a real try/catch -- rejected,
written to avoid the "unreachable code" masking gap described above so it
actually exercises the intended check); `continue_catch_unreachable_code_error_test.caspien`
(nothing may follow a `continue` in its own immediate block, the identical
rule `return`/`throw` already get -- rejected).

Verified end to end through the real 4-stage pipeline (this compiler ->
`caspien-optimizer` -> `caspien-lowerordergenerator` -> `caspien-codegen`),
real compiled/linked binaries, both fixtures actually run (not just
compiled): `continue_catch_basic_test.caspien` prints `after` (skipping the
`x=%llu` line entirely) and exits 0; `continue_catch_nested_try_block_test.caspien`
prints `outer start` then `outer end` (skipping the inner try-block's own
`x=%llu` line, landing correctly at the *inner* try-block's own end, one
line before the outer try-block's own end), exits 0 -- direct, executed
confirmation that nested-targeting resolves correctly at runtime, not just
in the emitted bytecode text. A third, ad hoc, non-fixture check (the
non-throwing path: `f(1)` instead of `f(0)`, same shape as the basic
fixture) confirmed `continue` is never reached at all when the wrapped
call doesn't throw -- prints `x=1` then `after`, exits 0, exactly as an
ordinary successful `try`/`catch` already would with no `continue` in the
picture at all.

Full ~841-fixture corpus regression sweep (the 836 pre-existing fixtures,
run before and after every change in this round, plus this round's own 5
new fixtures): the exact same 120 fixtures are "unexpected" both before
and after on the 836 pre-existing set -- confirmed by diffing the two
sweeps' own sorted fixture-name lists directly, zero difference -- plus
the 5 new fixtures, each giving exactly its intended result.

## New: the `try { ... }` block -- a second, unrelated construct sharing only the "try" keyword

Asked for directly: "I now want to add the notion of the try block...
It's a syntax error if there isnt at least one regular try statement in
the try block. In terms of the AST, its a lexical scope as far as
variable resolution goes, but it compiles to nothing in the hob and
lob." A brand-new, completely distinct construct from the throwing
`try EXPR catch(e){...}` documented just below -- the two share nothing
but the literal "try" KEYWORD text:

    try{
        let p = mut try new Point{x:1,y:2} catch(e){ throw e }
        ...
    }

**Grammar and disambiguation** (`Token.isTryBlock`, a new field): both
shapes are KEYWORD tokens with `.text.equals("try")`, told apart the
moment `Parser.gatherKeywordBlocks`'s own "try" pre-scan finds a "try"
anywhere in a line's flat token list (needed since the throwing shape
can appear mid-expression, e.g. "let x = try foo() catch(e){...}"). A
new check runs *before* that pre-scan hands off to
`gatherTryCatchSpan` (which otherwise would scan forward for "catch",
never find one, and throw the wrong error): if "try" is the line's
very first token *and* the very next token is a `{` DELINEATOR (no
wrapped expression, no "catch" at all), it's the new block form --
gathered via the same `requireTrailingBlock` helper `loop`/`unsafe`/
`safe` already use, with `isTryBlock = true` set on the resulting
synthetic KEYWORD token. Every downstream stage checks this flag
first, never infers the shape from `sub`/`catchParamName` being empty.

**"It's a lexical scope... but compiles to nothing"**, enforced
identically to `unsafe`/`safe` at every stage:
- `TypeChecker.checkTryBlock` (the new top-level checker, dispatched
  from `checkStatement`'s existing "try" case, split on `isTryBlock`)
  checks the body in a fresh child `Scope` via `checkLinesInScope` --
  a plain `Scope(Scope parent)` (not the `Scope(parent, override)`
  form `unsafe`/`safe` use), since a try-block carries no safety-context
  meaning of its own.
- `BytecodeEmitter`'s existing "try" case, likewise split on
  `isTryBlock`: emits `emitBlock(stmt.childs)` +
  `emitDestructList(stmt.destructOnExit, stmt)` and nothing else for
  the wrapper -- the identical "zero bytecode presence" shape
  `unsafe`/`safe` already have. Verified directly against real compiled
  output: no `TRY`/marker instruction of any kind appears for the
  wrapper, only the inner statements' own bytecode.

**"It's a syntax error if there isn't at least one regular try
statement in the try block"**, enforced by a new
`containsRealTryCatch`/`statementContainsRealTryCatch` pair
(`TypeChecker`) that walks arbitrary nesting depth -- into
`if`/`match` (via the `.right` branch chain), `loop`/`for`/`unsafe`/
`safe`/`lock`/`cast`, a `hasBlock`-guarded `assume`, and a nested
`try { ... }` block's own body (a real try/catch inside *that* still
satisfies the outer block) -- looking for a real, non-block "try". A
genuinely easy-to-miss shape this walk has to handle: a real
`try EXPR catch(e){...}` used as a `let`'s or a plain assignment's own
right-hand side (`"let x = try f() catch(e){...}"`) is embedded as
that `"="` OPERATOR's own right operand (through any `mut`/`imut`
wrapper) -- not as the statement's own top-level KEYWORD the way a
bare `try f() catch(e){...}` statement is -- mirroring exactly the
shape `BytecodeEmitter.tryNodeOf` already has to unwrap for the same
reason. **This was a real, caught-by-testing bug, not just a
theoretical concern**: the first version of this check only looked at
`stmt.type == TokenType.KEYWORD`, so every positive fixture using the
natural, expected `"let x = try f() catch(e){...}"` shape inside a
`try{ }` block was wrongly rejected with "requires at least one real
try/catch" even though one was right there -- caught immediately by
running the new fixtures below, fixed by adding the identical
OPERATOR-`"="`-unwrap `tryNodeOf` already does.

**A second, more serious bug found and fixed during the same
systematic audit** (checking every `case "unsafe":` in the codebase --
14 locations across 5 files -- for a needed parallel `case "try":`,
gated on `isTryBlock`): `BytecodeEmitter.collectTryCatchNodes` -- the
whole-function pre-pass that finds every real try/catch anywhere in a
function body so its catch block can be hoisted to the top of the
frame -- had no "try" case in its switch at all, so a real try/catch
nested *inside* a new `try { ... }` block would never have been
discovered or hoisted, silently breaking the natural, expected use
case (a real `try`/`catch` wrapped in a `try{ }` block, exactly as the
feature is meant to be used) with broken bytecode. Fixed by adding a
`case "try":` (reached only for `isTryBlock` nodes, since `tryNodeOf`
is checked first in that same method and already intercepts the
throwing shape) that recurses into `stmt.childs`. The identical
parallel case was also needed in
`collectHoistedAllocsFromStatement` (the whole-function ALLOC-hoisting
collector) and in `statementDefinitelyReturns`/`statementContainsBreak`
(both narrow, `isTryBlock`-only -- a throwing try/catch's own normal,
non-throwing completion isn't itself a return, and its catch body is
never reached by a `break` belonging to an *enclosing* loop, so both
still fall through to their existing `default` for that shape).

**Confirmed NOT needed**, by inspection, for the remaining `case
"unsafe":` locations in the audit: `BytecodeEmitter.emitExpr`'s and
`TypeChecker.resolveOperatorType`'s own general operator-dispatch
switches (both are **expression**-position dispatchers for a wrapped
*value*, e.g. `unsafe dyn(...)`) -- a `try { ... }` block is exclusively
a **statement**-position construct (there is no `let x = try { ... }`
shape at all), so an `isTryBlock` node can never reach either switch.
`TypeChecker.flattenRootLines` (root-level `unsafe`/`safe` handling,
for wrapping a root-level `func`/`struct` declaration) likewise needs
no change -- a try-block is a statement inside a function body, never a
root-level declaration wrapper.

**New fixtures**: `try_block_basic_test.caspien` (a real try/catch
nested directly inside a try-block, the non-throwing path -- compiles
cleanly, zero wrapper bytecode); `try_block_no_real_try_error_test.
caspien` (a try-block with no real try/catch inside -- rejected);
`try_block_scoping_error_test.caspien` (a variable declared inside a
try-block is out of scope immediately after it -- confirmed rejected
with "use of undeclared variable", not the "requires real try/catch"
error, proving the scoping itself, not just the containment check,
works); `try_block_deeply_nested_test.caspien` (a real try/catch nested
three levels deep -- inside a `for`, inside an `if` -- inside a
try-block, exercising both `containsRealTryCatch`'s and
`collectTryCatchNodes`'s deep recursion).

**Verified end to end**, not just at the type-checker: both
`try_block_basic_test.caspien` and `try_block_deeply_nested_test.
caspien` were run through the full 4-stage pipeline (this compiler ->
`caspien-optimizer` -> `caspien-lowerordergenerator` -> `caspien-codegen`)
and the real linked binary, on `linux`/sysv_x64 -- zero `TODO(codegen)`
lines in the generated assembly for either, and each printed its
correct expected output (`x=1`, exit 0). A third, ad hoc check (not
kept as a permanent fixture) confirmed the *throwing* path too: the
same shape with `f(0)` instead of `f(1)` printed `caught` and exited 0,
confirming a real, wrapped throw still correctly unwinds into its own
catch block even with a `try{ }` wrapper around it. Neither
`caspien-optimizer` nor `caspien-lowerordergenerator` nor
`caspien-codegen` needed any change at all for this feature -- confirmed
directly (zero TODOs, correct execution), not just assumed, since the
try-block itself never reaches those stages as anything but its own
already-existing inner statements' ordinary bytecode.

Full ~836-fixture corpus regression sweep (before vs. after every
change in this round): the exact same 120 fixtures are "unexpected"
both before and after (a pre-existing, unrelated baseline gap -- the
`new`-throwing migration was only ever applied to the 4 stdlib files
per that round's own explicit scope, leaving plenty of other example
fixtures with unmigrated bare `new`/`@throws` call sites -- confirmed
by diffing the two sweeps' own fixture-name lists directly: zero
difference), plus the 4 new fixtures above, each giving exactly its
intended result.

## New: `catch` now takes a mandatory bound parameter (`catch(e){...}`), and `throw` accepts that parameter back

Two small, purely structural grammar/type-checking additions on top of
the real `try`/`catch` lowering documented just below -- explicitly
scoped as "parsing first, no lowering yet," the same two-phase spirit
the original structural-only `try`/`catch` round used: "It still doesnt
do anything with it in the lowering, but it needs to work at the higher
levels," confirmed directly.

**1. `catch{...}` is now `catch(e){...}` -- exactly one bare, untyped
parameter, mandatory.** `Parser.gatherTryCatchSpan` now requires a
`"(" IDENT ")"` immediately after `catch`, before its `{...}` block --
`catch{...}` with no parens at all is now a parse error (`"'catch'
requires exactly one parameter in parentheses, e.g. 'catch(e) {
... }'"`), and so is anything inside the parens other than one plain
identifier -- zero, two-or-more, or a typed parameter (`catch(e mut
u64){...}`) all fail with `"'catch' takes exactly one plain, untyped
parameter name -- got ... tokens inside '(...)'"`. "doesnt have to be
called e, but anything other one plain argument is an error," confirmed
directly -- any identifier is accepted, only the *shape* (exactly one,
untyped) is fixed. The name itself is stored on the gathered `"try"`
token's own new `catchParamName` field (`Token.java`), and
`TypeChecker.checkTry` declares it into the catch body's own scope
before checking that body (mirroring how a function's own params are
registered into scope before its body is checked) -- with the identical
type a thrown message itself already requires (`"static imut
string"`), so `e` behaves as an ordinary local variable of that type
throughout the catch block.

**2. `throw` now also accepts a bare variable -- already true at the
type-check level, newly exercisable via `catch(e){...}`.** `TypeChecker.
checkThrow` already resolved `throw`'s own operand via the general
`resolveExprType` and already restricted it to `expr.type == STRING ||
expr.type == VARREF` (a leftover from this round's discovery, not a new
restriction this round added) -- so `throw e` inside `catch(e){...}`
requires no change to `checkThrow` at all: `e`'s declared type (`static
imut string`, from point 1 above) already satisfies `checkThrow`'s own
`requiredType` check unconditionally. `BytecodeEmitter.emitThrow`
likewise already handled a VARREF operand (`operand = expr.type ==
VARREF ? expr.text : literalValueOf(expr)`), so `"catch(e){ throw e
}"` -- re-propagating the exact value this catch just caught, straight
back up to whichever `try`/`catch` (if any) wraps *this* function's own
call site -- compiles to a plain `THROW e` line, structurally identical
to `THROW string_id1` for a literal message, and needed zero emitter
changes either.

**Verified end-to-end, not just structurally**: a three-level chain --
`f()` throws, `g()`'s own `catch(e){ throw e }` re-throws the caught
value, `main()`'s own `catch(e){...}` catches the re-thrown value --
compiled, linked, and actually run (both `linux`/sysv_x64 native and
`windows_gnu`/win64 via Wine) prints `"caught in main"` exactly once
and exits cleanly, confirming the re-thrown `GT_UNWIND` correctly
propagates up through `g`'s own frame (which has no `try`/`catch` of
its own around its *own* call site staging -- it's `g`'s own `catch`
block doing the re-throw, not a nested `try`) and lands in `main`'s
`gt_routine_address`-staged catch, exactly like an ordinary top-level
throw would. Full ~832-fixture corpus sweep re-run after both changes:
still exactly 105 unexpected, zero new regressions -- the handful of
existing `try`/`catch` fixtures in the corpus (`try_requires_catch_
error_test`/`try_unnecessary_error_test`/`try_wraps_non_call_error_
test`) are all already-`_error_test` fixtures expected to fail to
compile regardless, and still do (for their own original, unrelated
structural reasons, confirmed to still trigger before this round's new
`catch(...)` parenthesization requirement would even be reached).

**Still not addressed** (deliberately, per this round's own explicit
scope -- "it still doesnt do anything with it in the lowering"): `e`
carries no real runtime value at all yet, in either direction -- a
caught throw's own original message is never actually captured into
`e` (the catch block's own frame slot for it doesn't exist; `e` is
purely a compile-time-typed name right now, with nothing in
`BytecodeEmitter` ever writing to it), and `THROW e` emits `e`'s own
*variable name* as its bytecode operand text, not whatever runtime
value `e` might someday hold -- identical, deliberately, to how `THROW
string_id1` already never reads that string's real contents either
("the thrown message remains genuinely inert at runtime," confirmed
directly, unchanged from before this round). Wiring an actual runtime
value through `e` is future work, same as the rest of the real
throw-value-propagation machinery this project has consistently
deferred so far.

## New: real `try`/`catch` unwind-redirect lowering, plus two new compile-time contracts it depends on

Follows directly on from the "structural only" `try`/`catch` round
below -- this round is phase two, the actual lowering, plus two new
restrictions surfaced by walking through, with the user, exactly when a
`TRY`-guarded call site's own staging write would (and wouldn't) be
followed by anything.

**1. `@throws` now requires a real `throw`.** The direct mirror of the
pre-existing "`throw` requires `@throws`" check: `TypeChecker.
checkFunctionBody` now throws `"'f' is decorated '@throws' but its body
contains no 'throw' -- remove '@throws' (or add a 'throw')"` once a
function's whole body has been checked and no real, fully-validated
`throw` statement was ever seen in it (`FuncInfo.sawThrow`, set
alongside the pre-existing `usesThrow = true` in `checkThrow`). Closes a
real gap the `isThrows` field's own doc comment had actually already
(incorrectly) claimed was enforced.

**2. `throw` is now banned anywhere transitively reachable from a
`@gt_init`/`@gt_alive_check`/`@gt_destruct`/`@gt_register` function**
(`TypeChecker.validateNoThrowReachableFromGhostTableFunctions`, run once
from `check()` alongside `checkNoRecursion`). Resolved a real design
question the user raised directly, choosing between two options:
"the dundered functions need [their own] unwinding mechanism... just
without any of the gt functions" vs. "just not allow the `@gt_`
functions to throw" -- decided in favor of the second, on the same
precedent C++/Rust already establish for destructors/`Drop::drop`
(letting cleanup code become a new, arbitrary source of escaping control
flow reopens exactly the reentrancy hazard the whole dundered-duplicate
mechanism already exists to prevent, one layer up). Deliberately
narrower than "no `@throws` function may even be called from there":
calling one and catching it locally would be fine once unwinding
correctly stops at the nearest enclosing `catch` -- it's banned for now
because, empirically, right now it doesn't reliably stop there (a
dundered/gt-suppressed function has no `gt_routine_address` slot to
redirect through at all, since a real `GT_UNWIND` reaching a suppressed
frame has nothing staged to read -- see BytecodeEmitter's `TRY`
redirect, point 3 below, for why this exact case can no longer even
arise once combined with point 1). Reuses the existing `callEdges` list
(the same one `checkSafetyCallGraph`/`checkNoRecursion` already walk)
rather than building a second call graph -- a new `ThrowSite` list
records every real `throw`'s own enclosing `FuncInfo`, checked against
the reachable set computed by walking outward from the (up to four)
real ghost-table functions.

**A consequence of combining 1 and 2, confirmed directly by construction,
not just tested:** since `TRY` can only ever wrap a call to an `@throws`
function (`checkTry`), and every `@throws` function must now contain a
real `throw` (point 1), and that `throw`'s own enclosing function can
now never be gt-reachable (point 2) -- `TRY` itself can no longer occur
inside *any* gt-suppressed/dundered function at all; the source program
that would produce that shape simply fails to compile. Combined with
`checker.usesThrow()` necessarily being `true` program-wide the moment
any `TRY` exists at all (its wrapped `@throws` function's own `throw`
had to pass checking for `TRY` to exist), this means `stageCallSiteFor
UnwindNames`'s early-return gate (`!checker.usesThrow() ||
inGtSuppressedContext`) can now never fire for the staging write a `TRY`
redirects into -- confirmed by direct construction of both previously-
possible counterexamples (an `@throws` function with no real `throw`;
an `@throws` function called, via `try`/`catch`, from code reachable
from a `@gt_` function) and confirming each now fails to compile.

**3. The real `TRY` lowering** (`BytecodeEmitter`): "early on in the
lowering, the TRY eats the next [three] instructions," confirmed
directly. No standalone `"TRY <label>"` bytecode line is emitted at all
any more -- instead, a new one-shot `pendingTryCatchLabel` field is set
immediately before `emitExpr`ing the exact call node a `try` directly
wraps (`emitTryGuardedCall`, shared by `emitExpr`'s and `emitStatement`'s
own "try" cases) and consumed by the very next `stageCallSiteFor
UnwindNames` call -- guaranteed to be that exact call's own staging
write, since `emitCall`'s own `stageCallSiteForUnwind` call happens
before any of that call's own arguments are evaluated, so the redirect
can never leak into an unrelated call nested in that call's own
argument list (verified directly: `try f(g()) catch{...}` -- `g()`'s
own staging still gets its own ordinary, undisturbed landing-pad label).
When consumed, `stageCallSiteForUnwindNames` writes the *catch* block's
own label into `PUSH_LABEL` directly, in place of a freshly-generated
`@gt_callsite__...` landing-pad label, and adds no entry to
`pendingCallSiteUnwindLandingPads` at all (nothing should ever land at
an intermediate pad for a `TRY`-guarded call -- only directly in the
catch block), so a function whose only calls are all `TRY`-guarded no
longer emits the trailing `JMP .../@gt_callsite__...:/...:` block at
all. Net shape, for `let x = try f() catch{...}`:

    ADDR x mut_u64
    ADDR gt_routine_address code_addr
    PUSH_LABEL @catch_1 code_addr
    ASSIGN code_addr code_addr code_addr
    CC_START sysv_x64
    CALL f
    CC_END sysv_x64
    PUSH_RET mut_u64
    ASSIGN mut_u64 mut_u64 mut_u64

**Bug found via real end-to-end execution (not just HOB inspection), and
the *right* fix for it, found after a wrong first fix was tried and
corrected:** `emitHoistedCatchBlocks` originally gave every hoisted catch
block the same trailing `JMP <skipLabel>` used at function entry to jump
over the whole hoisted-catch region. That's two different things wearing
one label: "the one-time jump-over-the-hoisted-region point" and "where
this specific catch body should resume normal execution" happen to
coincide only when the `try`-guarded call isn't the function's first real
statement. `observe.caspien` (`let x = try f() catch{ printf("caught\n")
} printf("after, x=%llu\n", x)`, `f` unconditionally throwing) is the
common case where they don't: compiled, linked, and actually run (via the
shipped orchestrator, Wine on `windows_gnu`/win64), it printed `"caught"`
in an infinite loop -- 32000+ lines -- and never reached the `"after,
..."` line, because jumping to the shared skip-label after handling the
catch landed control back *before* the call, re-triggering it forever.

The first fix tried gave each `try` node its own second "after" label,
emitted right after the wrapped call's own code, and had every catch
body `JMP` there instead of the shared entry label. That did stop the
infinite loop, but a direct question about it ("how is `x = try foo()
catch{}` any different from `x = foo()`, the stack shouldn't be
disturbed...") surfaced that it only patched the symptom: the real
problem was that *falling out of a catch block back into normal
execution* has no well-defined meaning at all -- the wrapped call never
returned (a caught throw redirects straight into `@catch_1` from deep
inside `f`'s own unwind, never back through the call site's own
`PUSH_RET`/`ASSIGN`), so whatever value `x` had going into that catch
block is whatever garbage was already on the stack, not any real return
value. The after-label made the loop go away without ever answering "so
what is `x` on the caught path" -- it just let the program keep running
past that undefined value.

**The actual fix, replacing the after-label entirely**: "we dont need
that special after label. Instead what we need is our catch block to be
terminating -- return or throw inside," confirmed directly. `TypeChecker.
checkTry` now requires every catch body to definitely end in its own
`return` or `throw` (`CompilerException`: `"'catch' must be terminating
-- every path through its body must end in a 'return' or a 'throw'..."`),
reusing the exact same `definitelyReturns` analysis the "not every path
returns" function-body check already uses (it already treats a bare
`throw` as terminating, identically to `return`, so no catch-specific
variant was needed). With that guarantee in place, a catch body
categorically never falls off its own bottom -- its own `return`/`throw`
is already fully, genuinely control-flow-terminating (`RET`/`EXIT`/
`GT_UNWIND`, no fallthrough) -- so `emitHoistedCatchBlocks` needs no
label, and no `JMP`, after a catch body's own emitted code at all; it
was removed outright, along with the whole `tryAfterLabels` mechanism.

This also fully closes the previously-open "what value does `x` get on
the caught path" question, rather than just working around it: there no
longer *is* a caught path that rejoins normal execution. Reaching the
code after a `try`/`catch` statement now categorically means the wrapped
call returned normally -- `x` is always its real return value there,
nothing else, because the only other way execution could have gotten
past that `try`/`catch` is for the catch body's own `return`/`throw` to
have already exited the function (or propagated the throw onward)
first. Confirmed via the full pipeline (compile, link, execute) both
ways: a catch body ending in `return` prints `"caught"` once and exits
before ever reaching `"after, ..."`; a catch body with no `return`/
`throw` (the old empty-catch shape) is now rejected at compile time with
the message above, on both `linux`/sysv_x64 and `windows_gnu`/win64.

**Still open** (unrelated to any of the above): lock release during an
unwind through a `try` (the pre-existing, already-documented "no lock
release during unwind" gap this project's history already flags for
`throw`/call-site landing pads generally).

**Verified**: full ~832-fixture corpus sweep, before and after every
change in this round (the `@throws`-requires-`throw` check, the
gt-reachable-`throw` ban, the real `TRY` lowering, and the
catch-must-be-terminating requirement that replaced the after-label fix)
-- 105 unexpected results every time, zero new failures introduced by
any of the four changes. Direct, hand-built fixtures for each new
restriction (an `@throws` function with no real `throw`; a `try`/
`catch`-wrapped `@throws` call reachable from a `@gt_destruct` function;
a non-terminating catch body) confirmed to now fail to compile with the
correct, dedicated error message each. The real lowering's redirected
bytecode shape, and the terminating-catch requirement's actual runtime
behavior, both confirmed directly against real compiled-and-linked
binaries, run (not just compiled) on both `linux`/sysv_x64 (native) and
`windows_gnu`/win64 (Wine).

## `try`/`catch`, structural (parsing + type-checking) only -- lowering/codegen deliberately NOT yet implemented (superseded above -- kept for the parsing/type-checking design it still fully describes)

Asked for directly, as an explicit two-phase instruction: **"Just make
those structural changes first, and then we'll address the lowering/
codegen of TRY."** This entry covers phase one only.

**Grammar**: `try EXPR catch { BLOCK }` is an *expression* -- legal as
a whole statement on its own ("try foo() catch{ ... }") or as any
expression's value, most notably a `let`'s own right-hand side ("let x
= try foo() catch{ ... }"). `EXPR` must be a direct call (nothing more
general -- "try foo().bar catch{}"/"try 1+foo() catch{}" are both
rejected); `BLOCK` is an ordinary nested statement block, run only if
the wrapped call actually throws and unwinds back into it.

**The contract, enforced both directions** (`TypeChecker.
requireThrowsWrapping`, called from every one of this project's 5
call-resolution success sites once the callee is actually known --
`checkCall`, `checkLibraryCall`, `checkMethodCall`,
`checkGenericMethodCall`, `checkStaticMethodCall`): a call to a
function decorated `@throws` (see this file's own earlier "Redesigned:
per-call-site unwind staging..." entry for that decorator) *must* be
wrapped in `try ... catch { ... }`, and a `try`/`catch` wrapping a call
to a function that is *not* `@throws` is equally an error (pointless --
that call can never throw). `FuncInfo.isThrows` (mirroring the existing
`isAsync` field exactly) is what each call-resolution site actually
reads; `Token.insideTry` (set only on the exact CALL node a `try`
directly wraps, by `TypeChecker.checkTry`, before that call is
type-checked) is what lets `requireThrowsWrapping` tell an unwrapped
call apart from a wrapped one.

**Parsing** (`Parser.gatherTryCatchSpan`, called from a new whole-line
pre-scan at the very top of `gatherKeywordBlocks`, since -- unlike
every other gathered construct in that switch -- 'try' isn't
necessarily a line's own first token: "let x = try foo() catch{...}"
has 'let' first): the entire "try EXPR catch { BLOCK }" span, found
anywhere in a line's flat token list, collapses to one synthetic 'try'
KEYWORD token (mirroring "for"/"match"/"assume": `.sub` holds the
wrapped call's own raw tokens, `.childs` the catch block's own gathered
statement lines), which then replaces that whole span in place --
everything else in the line (e.g. a `let x =` prefix) is left
completely alone. `RpnConverter`/`TreeBuilder` each needed three small,
symmetric additions (their own single-keyword-line switch, their own
`processGathered`/equivalent, and their own `resolveNestedGroups`, for
the "embedded mid-expression" shape) to treat this one new KEYWORD
shape as an ordinary operand -- no new Token fields needed for `.sub`/
`.childs` themselves, both are the same generic slots "for"/"match"/
"assume" already reuse.

**Type-checking** (`TypeChecker.checkTry`): confirms the wrapped
expression really is a call (not something more general), marks that
exact CALL node `insideTry`, type-checks it exactly like any ordinary
call expression (so `requireThrowsWrapping`, invoked from inside that
same resolution, can enforce the contract above), checks the catch
block as an ordinary nested statement block (the same "fresh child
scope via `checkLines`" shape `unsafe`/`safe` already use), and resolves
the whole `try` expression's own type to the wrapped call's own return
type -- so `let x = try foo() catch{...}` gives `x` `foo`'s own return
type, and ordinary completion (no throw) behaves exactly as if
`try`/`catch` weren't there at all.

**Deliberately NOT yet addressed** (phase two, not yet started): what
value (if any) `x` gets on the *caught* path; the real "TRY
<catch_label>" bytecode line (`BytecodeEmitter.emitExpr`/`emitStatement`
both throw a clean, deliberate `CompilerException` for now --
`"'try'/'catch' is not yet implemented past type-checking"` -- rather
than silently miscompiling or crashing with a raw stack trace); hoisting
the catch block to the top of its enclosing stack frame behind its own
skip-jump (mirroring how the old, now-removed whole-function
`gt_routine` body used to sit at the top of a frame, per the user's own
description: "the catch block is hoisted to the top of the stack frame,
so like how before in the stack frame the first command was to jump
over the centralised gt routine... now the command will be to jump over
the catch blocks"); and the interaction between a real `TRY` unwind
target and the existing per-call-site `GT_UNWIND` landing pads.
`insideLoop` is a known, narrow gap in the expression-position path
specifically (`resolveExprTypeInner`'s own "try" case always passes
`false`, since `resolveExprType` has no such parameter at all -- no
expression-position construct has ever needed to embed a real
statement block, with its own `break`-validity concerns, before `try`)
-- documented directly on `checkTry`'s own doc comment.

**A real, direct, and entirely expected consequence** of enforcing this
contract everywhere at once: every *existing* call site to an
`@throws` function anywhere in this corpus -- including inside the real
stdlib itself (`stdlib/event_loop.caspien`/`event_loop_c_args.caspien`/
`event_loop_safe_args.caspien`, each of which calls the user's own,
possibly-`@throws`, `main` directly) -- now fails to compile with the
new "must be wrapped in 'try ... catch { ... }'" error, including the 4
fixtures added in the previous "per-call-site unwind" round that were
previously verified working end-to-end through real codegen
(`gt_unwind_throw_multi_frame_cg_test`, and the three `gt_callsite_*`
fixtures). This is correct, not a regression to fix now -- confirmed by
diffing the full ~832-fixture corpus sweep against the immediately
prior baseline: every one of these newly-"unexpected" fixtures was
already failing before this round too (all for the same, unrelated,
pre-existing "@gt_register" baseline gap this project's history already
documents), just later in the pipeline; nothing new or unrelated broke,
and zero regressions appear anywhere else in the corpus. These
particular fixtures (event_loop's own internal call to `main` chief
among them) are exactly the ones phase two -- the real lowering/codegen
of `try`/`catch` -- needs to make pass again; deliberately left
unmodified for now rather than adding `try`/`catch` wrapping that would
just move them to the same "not yet implemented" bytecode error with no
real improvement.

**New fixtures**, verified through `caspien.Main` (type-checking only --
none reach codegen, per the above): `try_missing_error_test.caspien`
(the "must wrap" direction), `try_unnecessary_error_test.caspien` (the
"only needed around a real `@throws` call" direction),
`try_wraps_non_call_error_test.caspien` (the "must wrap a direct call,
not a general expression" restriction), `try_requires_catch_error_test.
caspien` (a parse-level check -- 'try' with no matching 'catch' at
all). Two further fixtures that exercise the full, correct *happy*
path all the way up to (and no further than) the deliberate "not yet
implemented" bytecode-stage message -- `try_basic_test.caspien` ("let x
= try f(1) catch{...}") and `try_bare_statement_test.caspien` (the bare-
statement form) -- are kept in `examples/wip_try_catch/` rather than
directly in `examples/`, specifically so the ordinary full-corpus
regression sweep (which globs `examples/*.caspien` non-recursively and
expects everything not named `*_error_test` to compile all the way to
a real binary) doesn't misclassify them as regressions; move them back
up once phase two lands and they're expected to compile all the way
through again.

## Redesigned: per-call-site unwind staging + per-throw-site destruct lists (replaces the old whole-function `gt_routine`), plus the new mandatory `@throws` decorator

Asked for directly, after a design discussion the user walked through in
full before any code was touched (**"Don't build anything, just hear me
out"**), confirmed back to the user point by point before implementation
began. The starting problem: the old design's one whole-function
`gt_routine__<func>` label -- reached directly, by name, from a `THROW`
inside that same function AND from every caller's `GT_UNWIND` alike --
means a callee's own unwind teleports straight into the caller's shared
label without ever really "visiting" the caller's own call site. That's
fine as long as nothing ever catches an exception (true today -- this
language still has no `catch`), but it structurally blocks any future
`try`/`catch` mechanism, since there is no way for a handler to know
*which call* actually unwound.

**The fix has two parts, both required together (confirmed explicitly by
the user: doing only the first without the second "wasn't the only
change"):**

1. **Per-call-site staging.** `GT_UNWIND` itself is entirely UNCHANGED --
   still just: tear down the current frame, read the *caller's* own
   `gt_routine_address` slot, jump there. What changed is *what* a
   caller stages into *its own* slot, and *when*. Previously: set once,
   in the prologue, pointing at the function's one shared label. Now:
   re-staged immediately before **every** ordinary call the function
   makes (`BytecodeEmitter.stageCallSiteForUnwind`, called from
   `emitCall` right before `emitCallSequence`), each time pointing at a
   label unique to that exact call site. No information about "where do
   I go on unwind" is carried through the callee at all -- the callee
   never sees or touches the caller's slot value; it simply exists,
   already updated, by the time any unwind could possibly reach it.
2. **Per-throw-site (and per-call-site) precise destruct lists, replacing
   the old whole-function conservative one.** The old design's single
   shared label carried one conservative destruct list: *every* owns
   local the function ever declares, anywhere in its body, since a throw
   from anywhere in the function funneled through that same one label.
   Now that a throw resolves, at compile time, to exactly one known
   lexical point, and each call site gets its own dedicated landing pad,
   each one can be exactly as precise as an ordinary `return` at that
   same point -- computed the same way (`TypeChecker.
   collectOwnsToDestruct`, already used by `checkReturn`), just now also
   called from `checkThrow` (no preserved slot -- throw never hands an
   owns value back to anything) and at every one of the 5 ordinary
   call-resolution success sites (no preserved slot either -- carried on
   the call token itself, `op.destructOnExit`).

**New `@throws` decorator, enforced at compile time.** Also asked for
directly, in the same instruction: any function containing a `throw`
statement, anywhere in its body, must now be decorated `@throws` on its
own declaration, or it's a hard compile error (`TypeChecker.checkThrow`,
right after the existing `unsafe` check -- `unsafe` still fires first for
a throw outside `unsafe`, since that check runs first and is unrelated).
Added to `FUNC_BASE_DECORATORS`. This is a real, enforced *contract*
akin to a checked-exception signature, not just documentation -- a
caller can't yet do anything with that information (no `catch` exists),
but the decorator is now required regardless, ahead of whenever `catch`
does arrive.

**A real, self-found-and-fixed bug during implementation, not a user
correction:** the first pass computed each call site's own
`op.destructOnExit` *before* that call's own `markMovedIfOwned` loop ran
(both follow the same `op.resolvedCallConvention = candidate.
callConvention;` line at each of the 5 sites) -- which would have left
an owns argument being *moved into* the current call still listed in the
caller's own call-site destruct list, since its move hadn't been
recorded yet. Caught before any test was run (by re-reading the edit),
fixed by reordering so `markMovedIfOwned`'s loop always runs first, then
`destructOnExit` is computed after -- verified with a dedicated new
fixture (see below) that this ordering is what actually prevents a
double-destruct, not just a theoretical concern.

**Implementation summary** (`BytecodeEmitter.java`): `emitGtRoutineBody`
now returns immediately, doing nothing, whenever `checker.usesThrow()`
is true (the whole old label/list/JMP/EXIT-or-GT_UNWIND block is now
provably dead code in that case, left completely unmodified for the
`usesOwnsRefDynNew()`-but-not-`usesThrow()` case, where it's still the
only mechanism and still fully correct). New `pendingCallSiteUnwindLandingPads`
(a per-function list, saved/restored around `emitFuncUnderName` the same
way other per-function emitter state already is) buffers each call
site's own label + precise destruct list as the body is emitted;
`emitCallSiteUnwindLandingPads` flushes them all, behind a `JMP`-around
("dead unless jumped into"), right before that function's own
`FUNC_END`. `emitThrow` was rewritten to emit its own precise
`GT_DESTRUCT` list inline, then `EXIT`(main)/`EXIT_THREAD`(`@async`)/
`GT_UNWIND`(otherwise) -- exactly the same three-way choice `return`'s
own destruct-list emission already makes, just reached from `throw`
instead. `THROW`'s own bytecode line now carries only ONE operand (the
thrown value) -- the second, target-label operand is gone, since a throw
no longer jumps anywhere at all; it falls straight through into its own
inline destruct-and-terminate sequence. The sibling `caspien-codegen`
project's `case "THROW":` was updated to match (see its own CLAUDE.md).
Deliberately NOT implemented, matching the old design's own pre-existing
limitation: no lock release during unwind, for either a throw site or a
call-site landing pad.

A gt-suppressed (dundered, ghost-table-reachable) function duplicate
never gets a `gt_routine_address` slot at all, old design or new -- both
`emitThrow` and the new `stageCallSiteForUnwind` skip all of this
machinery in that context (`inGtSuppressedContext`), which is strictly
*safer* than the old design's latent (and likely never-hit, since real
ghost-table functions are separately banned from using `throw`)
dangling-label gap for the identical case.

**All 9 pre-existing throw-using example fixtures** needed `@throws`
added to whichever of their functions actually throw, to keep compiling
under the new decorator requirement -- chosen in each case to preserve
that fixture's own originally-intended demonstrated behavior/error (one
deliberate exception: `throw_requires_unsafe_error_test.caspien`, left
unchanged, since its own `unsafe` check already fires first regardless
of `@throws`). A further, wider sweep after the first full corpus run
caught **14 more** fixtures across the corpus that also throw without
`@throws` (including the real stdlib's own `stdlib/make_safe_args.
caspien`, used by several `main_args_*`/`gt_init_safe_args_*` fixtures) --
all fixed the same way.

**New, dedicated fixtures added specifically to prove the new behavior**
(not just re-verify the old one still holds): `gt_callsite_precise_
destruct_test.caspien` (a call site's own landing pad must NOT include
an owns local declared only *after* that call -- the old whole-function
conservative list would have wrongly included it); `gt_callsite_move_
ordering_no_double_destruct_test.caspien` (an owns argument moved into a
call that itself throws must be destructed exactly ONCE -- by the
callee, never a second time by the caller's own landing pad -- pinning
down the move-ordering bug found and fixed above); `gt_callsite_
multiple_landing_pads_test.caspien` (a function making two separate
calls to the same callee gets two genuinely distinct landing pads -- the
first call's own pad, from a call that returns normally, must never
fire at all); `throws_decorator_missing_error_test.caspien` (the new
decorator requirement itself, as a hard compile error).

**Verified**, via the real, full 4-stage toolchain (`ASTGenerator` ->
`Optimizer` -> `LowerOrderGenerator` -> `Codegen`, through the shipped
`Compiler.java` orchestrator), on both `linux`/sysv_x64 and
`windows_gnu`/win64/Wine:
- All 4 new fixtures above print exactly their documented expected
  output and exit code, identically on both targets.
- `gt_unwind_throw_multi_frame_cg_test.caspien` (the pre-existing,
  three-real-stack-frame-deep throw fixture) re-verified byte-for-byte
  against its own long-documented expected output (three "destruct"
  lines, innermost frame first, exit code 1, "unreached" never printed)
  -- unchanged under the new design, confirming the redesign is a real
  behavior-preserving refactor for every case the old design already
  handled correctly, not just a reshuffling that happens to work for new
  cases.
- Full ~824-fixture example-corpus regression sweep (compile-stage only,
  `caspien.Main`): the 15 fixtures that failed to compile purely because
  of the new `@throws` requirement (see above) all now compile again;
  zero new failure categories introduced anywhere else in the corpus --
  the remaining, unrelated "unexpected" failures in this sweep (missing
  `@gt_register`/`@await_call` functions in a handful of fixtures that
  don't import the real stdlib, a few pre-existing struct-padding/
  recursive-struct/bounds-checking type errors) are the exact same
  long-standing, pre-existing, unrelated baseline gaps this project's
  own history already documents elsewhere -- confirmed by their error
  text alone (none mention `throw`, `gt_routine`, or unwind at all,
  which is the only surface this redesign touches).
- Confirmed via direct inspection that `caspien-optimizer` and
  `caspien-lowerordergenerator` (the two sibling stages between this
  project and codegen) need **zero changes** for the new bytecode shape:
  neither project's passes do any real processing keyed on `THROW`'s
  operand count, `gt_routine`-label cardinality/position, or a global
  label-uniqueness registry -- both are purely structural, `FUNC_START`/
  `ALLOC`-anchored (lowerorder) or fully no-op (optimizer, unchanged
  since this project's own original split) passes that treat the many
  additional labels/`ADDR`/`PUSH_LABEL`/`ASSIGN` occurrences per function
  exactly like any other line of text.

## Cleanup: dead code + stale doc comments for the long-removed "value as mut/imut" `as` shape

Found while explaining the current state of `cast`/`as` to the user
directly (not a functional bug -- confirmed nothing could ever reach the
dead branch). `value as mut`/`value as imut` (a pure compile-time
mutability reinterpretation) used to be a third legal shape of the `as`
operator, alongside alias-relabeling and same-family integer widening.
It's since been removed **at the parser level** -- `mut`/`imut` no
longer parses as the right-hand side of `as` at all (confirmed directly:
`x as mut` now fails with a parse error, "expression ends unexpectedly,"
before `TypeChecker.checkAs` ever runs) -- and `checkAs`'s own
unconditional throw for a non-type-name right-hand side spells this out
explicitly: `"mutability is never affected by 'as' (use the 'mut'/
'imut' prefix operator instead)"`. Mutability reinterpretation is the
dedicated `mut`/`imut` prefix operator's own job now (`checkMutabilityOf`
-- a completely separate operator from `as`).

Despite this, `BytecodeEmitter.emitAs` still carried a defensive
`if (op.right.type == TokenType.MODIFIER) { return; }` branch, with a
doc comment describing it as live behavior -- genuinely unreachable
dead code (by the time bytecode emission runs, `op.right` is guaranteed
to be a plain type-name `VARREF`), and a stale doc comment describing a
feature the grammar no longer has at all. `Token.java`'s own
`isAliasCast` doc comment also referenced "the `mut`/`imut` MODIFIER
case just above it" as if it still existed.

Removed the dead branch, rewrote `emitAs`'s doc comment, moved and
rewrote the orphaned doc comment sitting above `TypeChecker.
resolveCastTargetBare` (originally `checkAs`'s own doc, left behind
disconnected from its real method once `resolveCastTargetBare` was
inserted between them at some point) to sit directly above `checkAs`
itself and describe the current, accurate two-shape reality, and fixed
`Token.isAliasCast`'s own cross-reference. Per this project's own
standing "don't guess/handle what can't happen" discipline -- dead code
left in place invites a future change to build on a premise ("this
shape can still occur") that was already false.

**Verified**: rebuilt clean; `cast_within_split_manual_check_test.
caspien`, `as_operator_test.caspien`, and `type_alias_basic_test.
caspien` (the three existing fixtures exercising `as`) re-run by hand
against their own compiled bytecode, byte-for-byte unchanged from before
this cleanup (still `SEXT`/`ZEXT`/no-instruction exactly as before). Full
`_cg_test` execution-suite regression sweep (44 fixtures): zero new
regressions, same 2 pre-existing, unrelated crashes as every previous
round this session. Purely a documentation/dead-code cleanup -- no
behavioral change, no new fixture needed.

## Fixed: the "`GT_DESTRUCT` dotted-operand gap" -- reassigning an owns-typed field through a pointer used to leak the old value silently

The last of the three array/ownership-codegen gaps identified this
session (after the two closed by the chained-array-indexing fix above)
-- asked about directly ("does LOOKUP_LHS work..."; "what is the
LOOKUP_ARRAY_LHS problem"; "we have this issue with addressing pointers
in general... so its just the automatically generated routines need to
be updated to reflect this?" -- the last question is exactly right, and
is what this fix does).

**The bug:** `emitDestructOldOwnedValue` (called from `emitAssign`,
ahead of any reassignment to an `owns`-typed target, to destruct
whatever the target held before it's overwritten -- otherwise the old
value leaks) built a flat, dot-joined name string
(`qualifiedDotName`, e.g. `"pp.leaf"`) for ANY `.` target
unconditionally, and simply gave up (emitting nothing at all) for a
LOOKUP target. Downstream, `AddressLoweringPass.resolveAddress`
(lowerorder) can only fold a name like `"pp.leaf"` to a single
compile-time byte offset when every segment is an ordinary,
stack-resident value -- the moment a real pointer (`raw`/`owns`/`ref`/
`auto`/`static`) sits anywhere in the chain (e.g. `pp` itself being a
`raw mut Node`), there is no single fixed offset to fold to, and
`resolveAddress` deliberately bails (`return null`). When that happens,
the rewrite is abandoned and the `GT_DESTRUCT` line is left with its
unresolved name text; `X86Backend`'s own `GT_DESTRUCT` case has no
fallback for that shape -- it just emits a `TODO(codegen): ... not yet
implemented` comment and skips the destructor call. Net effect: not a
crash, not even a compile error -- a silent, compiles-clean memory leak.

**The fix is NOT "invent pointer-dereference codegen" -- that already
exists and is already correct.** `isQualifiedNameableDot` is the exact
gate `emitDot` (ordinary reads) and `emitAssignTarget` (assignment
targets, via `emitDotLhsRoot`) already check before deciding whether a
`.` chain is safe to flatten to a name; when it isn't, they already fall
back to a genuine runtime address computation (`DOT_LHS`/`LOOKUP_LHS`),
which walks through a pointer correctly by construction (a pointer's own
value already IS the address to use -- see `emitDotLhsRoot`'s own doc
comment, and the chained-array-indexing fix above, which proved this
mechanism correct through arbitrary pointer/array nesting). The bug was
that `emitDestructOldOwnedValue` was the one remaining routine that
never checked that gate and never used that fallback.

`emitDestructOldOwnedValue` now: for a bare VARREF, or a `.` chain that
passes `isQualifiedNameableDot`, keeps the original flat-name
`GT_DESTRUCT name` behavior unchanged. For a `.` chain that fails the
gate (a pointer in the chain), or any LOOKUP target (never
flat-nameable to begin with -- its own index is a runtime value), it
instead: emits `emitAssignTarget(target)` to compute the target's real
address (the same call `emitAssign` would make anyway for the store),
duplicates it with the existing `DUP_TOP` mnemonic (added for the win64
varargs-duplication fix earlier this session, reused here for an
unrelated purpose -- duplicating any 8-byte stack word), and emits a new
`GT_DESTRUCT_ADDR <type>` line to destruct through one copy. The method
now returns a boolean telling `emitAssign` whether the address is
already sitting on the stack (skip the normal `emitAssignTarget` call
for the store -- calling it again would both waste work and, worse,
double-evaluate any side-effecting sub-expression inside the target,
such as a LOOKUP index containing a call) or whether the caller still
needs to compute it itself (the unchanged, flat-name case).

New bytecode mnemonic `GT_DESTRUCT_ADDR <type>` (`X86Backend`, sibling
project): unlike the existing `GT_DESTRUCT $offset` (which reads the
owns pointer's value directly off a known, fixed frame offset via
`loadSizedFromFrame`), this one receives an already-computed ADDRESS on
the stack (left there by `DUP_TOP`, from a chain that may have crossed a
real pointer) and does the one additional dereference
(`loadSizedFromAddr`) needed to get from "address of the owns field" to
"the owns pointer's own value" before calling `gt_destruct` -- otherwise
identical to the existing case. Needs no `AddressLoweringPass` change at
all: an unrecognized mnemonic passes through this pass completely
unchanged by construction (`tryRewrite` returns `null`, caller keeps the
original line), and this mnemonic's only operand is a type string, never
a name needing resolution.

**Verified**: `gt_destruct_pointer_crossing_cg_test.caspien` (`pp.leaf =
newLeaf`, `pp` a `raw mut Node`, `leaf` an `owns mut Leaf` field) --
previously would have silently skipped the destructor entirely; a
self-written `@gt_destruct` stub that prints on every call now
confirms it fires exactly once, with the correct old value replaced,
confirmed by reading the new value back through the original variable
afterward: prints `destruct fired` then `after=2`, on both `linux`/
sysv_x64 and `windows_gnu`/Wine/win64. Confirmed in the generated
assembly too: no `TODO(codegen)` placeholder, a real `call gt_destruct`
with the expected load-address / dereference / move-to-arg0 sequence
ahead of it. Full `_cg_test` execution-suite regression sweep (44
fixtures): zero new regressions, same 2 pre-existing, unrelated crashes
as every previous round this session
(`arrays_safe_dynarray_full_api_with_gt_register_cg_test`,
`atomic_swap_global_scalar_cg_test`).

All three array/ownership-codegen gaps identified this session are now
closed. None remain open.

## Chained array indexing (`grid[1][1]`) -- two separate bugs, one in the parser (read side), one in `BytecodeEmitter` (write side)

Fixes a real, confirmed gap: `grid[1][1]` (indexing a fixed array of
fixed arrays twice in a row) used to fail outright on the read side, and
silently corrupt the stack on the write side. Two independent bugs, found
and fixed in sequence while chasing the same feature request.

**Bug 1 (parser, read side): a `[...]` DELINEATOR group was never a valid
LOOKUP target on its own.** `Parser.java`'s `insertCallLookupInFlat`
inserts a synthetic `LOOKUP` marker token ahead of a `[...]` DELINEATOR
group so `RpnConverter`/the RPN pass can tell "this `[` starts an index
expression" apart from every other use of `[`. That insertion only fired
when the token immediately to the group's left was a bare `VARREF` or
`STRING` -- never when it was itself another `[...]` DELINEATOR group
(the already-completed result of a prior lookup, e.g. the `grid[1]` in
`grid[1][1]`). So `grid[1][1]` never got a second `LOOKUP` marker inserted
at all, and failed to parse as a chained lookup.

Fixed by widening that same per-token check to also accept a `[`
DELINEATOR group as a valid LOOKUP target: `t.type == TokenType.DELINEATOR
&& t.text.equals("[")`, alongside the existing VARREF/STRING check. No
downstream stage needed any change for this half: `TypeChecker.
checkLookup` already recurses into a nested LOOKUP generically (via
`resolveExprType`'s own recursion, since a LOOKUP's `op.left` is just
another expression to resolve), and `caspien-codegen`'s own LOOKUP_ARRAY
read-side addressing was already correct for multi-word array elements
from an earlier fix (see the sibling `caspien-codegen` project's CLAUDE.md,
"`LOOKUP_ARRAY`'s read-side address formula" section). This half alone
was enough to make `let v = grid[1][1]` read correctly, at any depth
(verified through 3 levels: `cube[1][0][1]`).

**Bug 2 (`BytecodeEmitter`, write side, found while verifying bug 1's fix
is complete): `grid[1][1] = x` corrupted the stack.** `emitAssignTarget`'s
own `LOOKUP` branch computes an assignment target's address by calling
`emitDotLhsRoot(target.left)` to get the *base* address, then indexing off
it. `emitDotLhsRoot` already special-cased a bare VARREF base (swapping in
an address-producing `ADDR` instead of an ordinary value-reading `PUSH`),
but a base that was itself another `.`/LOOKUP node fell through to plain
`emitExpr(root)` -- an ordinary *value read*. For a stack-resident (no
storage modifier) array/struct element, an ordinary read pushes a COPY of
its bytes onto the stack (`caspien-codegen`'s own LOOKUP_ARRAY read-side
handling: "the whole array pushed by value... never an address"), not an
address. So `grid[1][1] = 99`'s outer write indexed into that copied byte
value as if it were a real pointer, corrupting the stack -- confirmed
directly via a real segfault inside glibc's own malloc/free machinery on
a real fixture before this fix.

Fixed by having `emitDotLhsRoot` recurse into `emitAssignTarget` itself
(the exact same address-computing LOOKUP_LHS/DOT_LHS path, one level
deeper) for a stack-resident `.`/LOOKUP root, instead of routing it
through the value-reading `emitExpr`. This gives an assignment target
chained to arbitrary depth a chain of nested LOOKUP_LHS/DOT_LHS address
computations all the way down to the true base, mirroring how the read
side already recurses via plain `emitExpr` on nested lookups' own left
sides. A pointer-typed `.`/LOOKUP root (rare, but reachable e.g. through
a `raw`-returning accessor) is left routed through ordinary `emitExpr`,
matching the pre-existing pointer-typed-VARREF case: its own pushed value
already IS the address, no recursion needed.

**Verified**: both bugs' fixes compiled and run correctly, at 2 and 3
levels of chaining, for both read and write:
`chained_lookup_grid_cg_test.caspien` (`grid[1][1]` read, expects `v=5`),
`chained_lookup_3level_cg_test.caspien` (`cube[1][0][1]` read, expects
`v=6`), `chained_lookup_write_grid_cg_test.caspien` (`grid[1][1] = 99`
write-then-read, expects `v=99`), `chained_lookup_write_3level_cg_test.
caspien` (`cube[1][0][1] = 77` write-then-read, expects `v=77`). A full
`_cg_test` execution-suite regression sweep (all 43 fixtures in
`examples/`) shows zero new regressions: the same 2 pre-existing,
unrelated crashes as before this fix
(`arrays_safe_dynarray_full_api_with_gt_register_cg_test`,
`atomic_swap_global_scalar_cg_test`), all others compiling with 0 TODOs
and exiting/running as expected.

**Side effect: this fix also closed the previously-separate, documented
`LOOKUP_ARRAY_LHS` write-side gap** (`caspien-codegen`'s own CLAUDE.md,
"Known separate gap... LOOKUP_ARRAY_LHS write-side gap"), which was
originally ranked as a distinct, medium-difficulty bug in its own right.
That gap and Bug 2 above turned out to be the *same* root cause wearing
two different faces: both are "an assignment target's base is a `.`/LOOKUP
node, not a bare VARREF, and got routed through a value-reading `emitExpr`
instead of an address-producing one." `emitDotLhsRoot`'s new recursive
branch was written to cover a stack-resident `.` root and a stack-resident
LOOKUP root identically (`root.text.equals(".") || root.text.equals
("LOOKUP")`) precisely because the ambiguity is identical either way -- so
the one fix, written to solve the chained-LOOKUP case, closed the
struct-field-array case too without any further change. Confirmed
directly, not assumed: `array_struct_member_cg_test.caspien` (`bd.cells[2]
= 8`, an array *inside* a struct) and `arrays_of_struct_field_write_
segfault_cg_test.caspien` (`pts[1].x = 77`, a struct field reached
*through* an array index -- the mirror-image shape) both previously
segfaulted and now both run and print correct values (`a=1 b=2 c=8 d=4`
and `a=1 b=77 c=4` respectively). Both fixtures' own doc comments were
rewritten in place to record this (kept their original names/`_segfault_`
suffix per this project's no-rename convention -- see
`float_register_overflow_args_cg_test.caspien` for precedent).
`array_struct_member_cg_test.caspien` was also strengthened while at it:
its original form only ever read back an *untouched* array index, so it
was recorded "OK" by every previous TODO-count-only regression sweep
despite the write itself silently corrupting the stack -- a gap in the
fixture, not just the compiler. It now reads back every index, including
the one actually written.

The `GT_DESTRUCT` dotted-operand gap mentioned here as still open is now
also fixed -- see this file's own top section ("Fixed: the 'GT_DESTRUCT
dotted-operand gap'..."), and it turned out to need neither
`caspien-optimizer` nor a redesign of anything in `caspien-codegen`, just
one new, narrowly-scoped bytecode mnemonic (`GT_DESTRUCT_ADDR`) reusing
machinery this fix's own chained-lookup work had already proven correct.
No array/ownership-codegen bugs remain open from this session's work.

## `emitCallSequence`/`emitArgWord` now know which argument index a variadic call's true varargs start at, for C's own default-argument-promotion rule

Fixes a real, confirmed bug: `printf("%f", x)` always printed
`0.000000`, even for a single, non-overflowing `f32` argument -- C
requires a `float` reaching the `...` portion of a variadic call to be
promoted to `double` first, and this compiler never emitted anything to
do that widening at all. Full design and every bug this surfaced (a
codegen-side `%xmm0` scratch-register clobber, a previously-latent
stack-alignment bug, and win64's own separate register-duplication
rule) are documented in the sibling `caspien-codegen` project's own
CLAUDE.md -- this project's own share of the fix is narrow: emitting the
new `PROMOTE_F32_TO_F64`/`VARARGS_XMM_COUNT`/`DUP_TOP` bytecode lines at
exactly the right call sites, with exactly the right, compile-time-known
counts.

`emitExternCall` now computes `varargStartIndex` -- `info.paramTypes.
size()` when the extern's own declaration ended in `...`, `-1`
otherwise -- and passes it to a new `emitCallSequence` overload (the old
5-argument form still exists, delegating with `-1`, for every other,
non-variadic call site: ordinary/recursive calls, `rand`, `main`'s own
synthesized wrapper calls, async trampolines). Inside the per-argument
loop, an argument at or past `varargStartIndex` is a true vararg;
`emitArgTransfer`/`emitArgWord`/`emitArgTransferTail` each gained a
`promoteToDouble`/`isTrueVarargFloat` parameter (the same boolean serves
both jobs -- promotion and win64's own register-duplication rule share
the identical "true vararg, and `f32`" precondition) threaded down to
where the actual `PROMOTE_F32_TO_F64`/`DUP_TOP` lines get emitted. A new
`ArgCounters.floatRegistersUsed()` (clamped to the convention's real
register count, unlike the unclamped `floatIdx` running total) and
`ArgCounters.isShared()` accessor support the SysV `%al`-count line and
the win64-only duplication branch respectively.

Verified end to end (compiled bytecode, generated assembly, and real
execution on both `linux` and `windows_gnu`/Wine) -- see the sibling
`caspien-codegen` project's own CLAUDE.md for the full fixture list and
results.

## `RANGE` retired -- `range()` now folds to plain `PUSH`/`LEN` at the front end

The `RANGE` bytecode mnemonic was never actually implemented anywhere in
`caspien-codegen` (confirmed by direct grep -- zero `case "RANGE"`
existed there at all; every `range(...)` call silently miscompiled,
corrupting the stack). Rather than build a codegen implementation for
it, `range(...)` is now retired at the compiler level entirely -- the
same "fold at the front end, no new instruction needed" treatment
`sizeof`/`len` already get for their own compile-time-constant cases.

**Two real shapes, both handled in `checkRangeBuiltin`
(`TypeChecker.java`):**
- **A string literal or fixed array** -- length is always known at
  compile time, so `range(x)` now returns `imut range(0,length)` with
  `op.builtinConstantValue` set to `length`, exactly like `sizeof`'s own
  constant fold. `BytecodeEmitter`'s `"range"` case then emits two plain
  literal pushes, `PUSH 0 imut_u64` / `PUSH <length> imut_u64` -- no
  instruction at all, just two hand-synthesized literal operands.
- **A safe dynarray** -- its length is a genuine runtime value with no
  compile-time bound, so `range(d)` now returns a **bare, boundless
  `imut range`** (no parens, no baked-in bounds -- the identical type
  text a `func(r: mut range) void{}` parameter already uses) and
  compiles to `PUSH 0 imut_u64` / `emitExpr(d)` / `LEN` -- reusing the
  already-fully-implemented `LEN` opcode verbatim (pop pointer, read the
  hidden length header at offset 0, push result). No new codegen
  instruction needed at all for this shape either.
- An `unsafe dynarray` argument is rejected outright (`isUnsafeDynArrayType`) --
  it has no hidden length header to read, and no scan terminator the way
  `len`'s own unsafe-dynarray form has, so there is nothing for `range`
  to report.

This is a genuinely new, previously-illegal shape being made legal
(`range(dynarray)`), not just a retirement -- `isArrayType()` only ever
matched fixed-size arrays (ending in `]`), so a dynarray argument used
to be flatly rejected with "'range' requires a string or an array"
before this.

**Immediate follow-on bug this surfaced, fixed separately:**
`for i in range(dynarray)` initially still failed -- not a codegen bug
this time, but `caspien-lowerordergenerator`'s own `MembershipLoweringPass`
having several `IN`/`WITHIN` rewrite-eligibility gates that only ever
recognized bounds-known `"range("`-prefixed type text, not this new
bare `"range"` type, even though the underlying rewrite mechanism reads
real runtime `.start`/`.end` fields regardless of whether the bounds
were ever known at compile time. See the sibling
`caspien-lowerordergenerator` project's own CLAUDE.md for the full fix
(four call sites widened to match a fifth, pre-existing one that
already handled this correctly).

`range()`'s own bound-check divergence, found and flagged (not fixed):
`let:<T>` explicit-type-annotation bound-checking (`TypeChecker.java`,
the hand-rolled check used for `let:<Bound> name = rhsExpr`, distinct
from the general `typesCompatible` rule) does not share
`typesCompatible`'s own explicit "a bare range accepts a literal-bounds
range value" rule -- so `let:<imut range> r = range("literal")` fails to
compile even though the general compatibility rule would allow the
identical assignment through a function parameter. Not fixed here --
out of scope, found only as a tangent while building a test fixture (the
test was rewritten to go through a function parameter instead, which is
what actually surfaced the far more serious `assignConstructionBlock`
`%rcx`-clobbering bug -- see the sibling `caspien-codegen` project's own
CLAUDE.md).

Verified via `examples/range_dynarray_membership_cg_test.caspien` (a
real `for i in range(dynarray)` loop, run end to end through the full
pipeline and the real shipped orchestrator on both `linux` and
`windows_gnu`/Wine, printing each index/value pair correctly) and by
re-checking the pre-existing `builtin_range_literal_test`/
`match_validation_bounds_test` fixtures for regressions (none).

## `yield` now implemented, and `sleep` introduced as a real keyword

**`yield`**: implemented in Codegen's `X86Backend` (`case "YIELD"`) as a
plain call to POSIX's own `sched_yield(void)` -- confirmed empirically
to link and run correctly on *both* real targets (glibc/linux,
mingw-w64's own libpthread/sched.h compatibility layer/windows_gnu, the
latter already linked in via "-pthread"/"-static" for
pthread_create/join/exit) with zero per-target branching needed, the
same "one literal C symbol name, no branching" shape "exit"/
"pthread_exit" already established. `pthread_yield` -- the more
obvious-looking choice, given "pthread_create"/"_join"/"_exit" already
being called this way -- was tried first and rejected: a non-standard
GNU extension glibc happens to also provide, but mingw-w64's own
winpthreads does not export it at all (confirmed by a real "undefined
reference to 'pthread_yield'" link failure under windows_gnu).

**`sleep`**: a brand-new real Lexer keyword (not a mere reserved
builtin name the way `sizeof`/`len`/etc. are), matching `yield`'s own
reserved-word status. Unlike `yield`, `sleep(...)` takes a real
argument and returns a real value, and unlike the four original ghost-
table opcodes (`GT_INIT` etc.), its call sites resolve *dynamically* to
whichever function actually carries a new `@sleep` decorator (found via
the same `ghostTableFunctions` registry `@par_call`/`@await_call`
already share -- singleton, fixed-required-signature, exactly the same
pattern), never through a hardcoded literal the way `GT_INIT`/etc.'s
bytecode-level callees are. Using `sleep(...)` with no `@sleep`-
decorated function anywhere in the compilation unit is a hard compile-
time error, the identical shape "par"/"await" already have for
`@par_call`/`@await_call`.

Parsing shape (`sleep(...)` needs the identical "CALL" marker an
ordinary VARREF call target gets, but `insertCallLookupInFlat` is
VARREF-only by default): `Parser.insertCallLookupInFlat` now also
inserts a "CALL" marker after a bare `sleep` KEYWORD directly followed
by `(`; `TypeChecker.checkCall` intercepts this shape at its very top
(before the ordinary "op.left must be a VARREF" guard) and hands it to
a new `checkSleepCall`, modeled closely on `checkExternCall`'s own
shape (a fixed-arity, fixed-type signature checked via plain
`typesCompatible`, no overload resolution needed since there is, by
construction, only ever one `@sleep` function in the whole compilation
unit) rather than the ordinary multi-overload path.

**Emits a dedicated `SLEEP` bytecode mnemonic, not an ordinary `CALL`**
-- confirmed directly, corrected from this round's own first attempt
(which routed `sleep(...)` through the completely ordinary `CALL`
machinery, indistinguishable in the bytecode from any other function
call; asked directly whether that had produced a real `SLEEP` opcode,
and it hadn't). `checkSleepCall` now also sets a new `Token.
isSleepCall` flag (alongside `resolvedCallTarget`/`resolvedCallConvention`
as before), which `BytecodeEmitter.emitCallOrBuiltin` checks first,
before the ordinary async/VARREF/extern dispatch, routing to a new
`emitSleepCall`. The emitted shape: the one required argument is pushed
by ordinary `emitExpr` exactly as any call argument would be, then a
single line, `"SLEEP " + op.resolvedCallTarget + " " + durationArg.
resolvedType"` -- e.g. `SLEEP sleep_call indeterminate_u64` -- carrying
the real `@sleep`-decorated function's own mangled name directly as an
operand (never a hardcoded literal the codegen stage would otherwise
have to already know -- the same "resolved dynamically" property
`@par_call`/`@await_call` already have, just made visible in the
bytecode text itself here) plus the pushed argument's own resolved type
(the identical "every instruction states the type of what it operates
on" convention `NEG`/`DEREF`/`CLONE`/`LEN_SCAN` already follow, though
`Codegen`'s own `case "SLEEP"` never actually branches on it --
`requireSleepSignature` already guarantees it's always exactly one
plain `u64`). No `CC_START`/`CC_END`/`PUSH_RET` wrapping at all -- shaped
like `GT_ALIVE_CHECK` instead (pop the one pushed argument, move it
into the first argument register, call, push the real result back),
since the argument/return shape is always fixed.

`@sleep`'s required signature (`TypeChecker.requireSleepSignature`,
enforced the same way `requireGhostTableSignature` enforces
`gt_init`/etc.'s shapes): exactly one plain `u64` parameter, returning
a plain `u64` -- "have it take the same arguments as the function in
C," confirmed directly, approximating the real POSIX `unsigned int
sleep(unsigned int)` the same way this stdlib's other C wrappers
already widen a narrower C type (e.g. putchar/getchar's own "int" ->
"u64").

**A real naming collision this surfaced, and its fix**: the real C
library only ever exports its `sleep` function under that exact literal
symbol name, but an extern's Caspien-side identifier and its linked
symbol name were always previously required to be identical (confirmed
directly: "externs are never mangled -- their bytecode symbol is always
the bare declared name") -- and "sleep" itself becoming a keyword means
`extern sleep(...)` can no longer even parse. Fixed with a new, small,
general escape hatch: `@link_name(realName)`, a new decorator accepted
on `extern` declarations only, letting the Caspien-side name
(`c_sleep`, in `stdlib/libc.caspien`) and the real linked symbol
(`sleep`) differ. Its argument is a bare word, not a string, deliberately
mirroring `@dont(par)`'s own pre-existing tolerance for naming a
keyword as a decorator argument ("decorator names/arguments live in a
completely separate namespace from ordinary code") -- so `@link_name
(sleep)` parses fine even though `sleep` is a global keyword everywhere
else. `ExternInfo` gained a `linkName` field (defaults to `name` for
every other extern, zero behavior change anywhere else); the one real
call site (`checkExternCall`'s `op.resolvedCallTarget = info.name`) and
the "EXTERN_CALL"/"EXTERN" bytecode-emission sites (`BytecodeEmitter.
emitExternCall`/`emitExtern`) were updated to read `linkName` instead.

`stdlib/sleep.caspien` (new file) is the real `@sleep` wrapper --
`import "libc.caspien"`, then `@sleep @pub func sleep_call(seconds: mut
u64) mut u64 { unsafe{ return c_sleep(seconds) } }`. Two existing
fixtures (`default_lock_match_bare_test.caspien`/
`default_lock_match_param_test.caspien`) that called the old literal
`extern sleep(...)` directly were updated to `import "stdlib/
sleep.caspien"` instead. `examples/stdlib/` carries its own separately-
vendored copy of `libc.caspien`/`par_call.caspien` (import paths
resolve relative to the *importing* file's own directory, not a fixed
project root -- confirmed directly, `ImportResolver`'s own doc comment)
-- found out the hard way when a stale copy there still declared the
now-illegal `extern sleep(...)` and broke every fixture under
`examples/` that imports it, regardless of whether that fixture uses
`sleep` at all; re-synced alongside the canonical `stdlib/` copies, plus
a copy of the new `stdlib/sleep.caspien` added there too.

Verified end to end through the real shipped `Compiler.java`
orchestrator (a real program printing before/after a `yield`/`yield()`
pair, then genuinely blocking for one full wall-clock second via
`sleep(1)` and printing its real remaining-seconds return value), on
both `linux` and `windows_gnu`/Wine targets. Full example-fixture suite
re-run afterward with no new regressions: two fixtures newly discovered
to segfault (`default_lock_match_bare_test`/`_param_test`) were
isolated with a minimal repro (a bare `atomic`/`swap`-decorated struct
member and a "default lock match" retry loop, with zero `sleep`/
`yield`/`join` content at all) and confirmed to crash *identically*
before this round's changes ever touch them -- a real, pre-existing,
unrelated bug in the atomic-swap/lock-match codegen path (the crash is
a segfault on the very first `ATOMIC_SWAP`/`xchg` instruction, on the
very first loop iteration, before any retry/`sleep`/`yield` branch could
ever be reached), almost certainly the same root cause as the already-
known, already-documented `atomic_swap_global_scalar_cg_test` failure.
Flagged here, not fixed -- out of scope for this round.

## `join` removed from the language entirely

`join`/`join()` (the keyword, both its bare and call-syntax forms) and
the `JOIN` bytecode mnemonic they compiled to have been removed
outright, not deferred -- there is currently no user-facing way to
join a `par`'d thread at all. `yield` is untouched and still exists,
still parses, still type-checks, and still emits a bare `YIELD`
mnemonic (which the codegen stage still doesn't implement -- see that
project's own CLAUDE.md).

Removed from: `Lexer.KEYWORDS` (`"join"` dropped from the reserved-word
set entirely, so it's now an ordinary identifier again -- e.g. a
function or variable named `join` is legal); `TreeBuilder`'s and
`RpnConverter`'s bare-statement dispatch (each previously had a shared
`case "yield": case "join":` -- now `yield`-only); `RpnConverter`'s
`yield()`/`join()` call-syntax normalization (now `yield()`-only);
`TypeChecker.checkStatement`'s bare-statement dispatch (same shared-case
split); `BytecodeEmitter.emit`'s bare-statement dispatch (the
`case "join": line("JOIN");` branch deleted outright, alongside the
`case "yield": line("YIELD");` branch it used to sit beside, which
stays). Every fixture and stdlib comment that referenced `join`/`JOIN`
as bytecode/keyword was updated or removed to match (`coverage.caspien`/
`coverage2.caspien`'s `yield / join` blocks trimmed to `yield` alone;
`async_yield_join_bare_and_call_test.caspien` replaced with
`async_yield_bare_and_call_test.caspien`, covering `yield`/`yield()`
only; `stdlib/par_call.caspien`'s doc comment, which referenced "a
possible future join mechanism," updated to say plainly that no such
mechanism currently exists in the language).

**`AsyncHandle_T`'s `threadId` field was deliberately left in place**,
not removed alongside `join` -- it isn't itself part of the removed
language surface (nothing in the grammar, the type checker, or the
bytecode format ever named it `join`-specific), it's already load-
bearing plumbing `@par_call`/`@await_call` need regardless (the single
word `pthread_create`'s `arg` parameter carries the handle pointer
through), and ripping it out would mean re-deriving the handle struct's
own layout for no functional gain. It simply has no reader anywhere
right now.

Regression-tested: full `_cg_test`/example suite re-run after this
removal with identical pass/fail results to before it (this change
touches no codegen/optimizer/lowerorder stage at all -- purely front-
end parsing/type-checking/bytecode-emission surface).

## Fixed: a `@gt_init`/`@gt_alive_check`/`@gt_destruct`/`@gt_register`-decorated function with a non-literal name broke the linker

The bug: Codegen's `GT_INIT`/`GT_ALIVE_CHECK`/`GT_DESTRUCT`/`GT_REGISTER`
opcodes each compile down to a direct call against a **hardcoded literal
symbol** -- `"gt_init"`, `"gt_alive_check"`, `"gt_destruct"`,
`"gt_register"` -- never against whatever the source actually named the
function carrying the matching decorator (confirmed directly by reading
every `emitCallByName("gt_...")` call site in `X86Backend`; none of them
ever consult a mangled name at all). The real stdlib's own
`gt_init.caspien`/`gt_register.caspien`/etc. happen to name their
functions literally that way, so this was invisible in ordinary use --
it only broke a program that instead declared its own, differently-named
stub (e.g. `func gtInit() void { ... }` under `@gt_init`, the exact
pattern `gt_unwind_no_throw_cg_test.caspien`/
`gt_unwind_throw_multi_frame_cg_test.caspien` both use, since the real
stdlib's `ghost_table.caspien` still can't run through this codegen
stage). That function type-checked fine (its signature is all
`requireGhostTableSignature` ever verified) and BytecodeEmitter never
emits an ordinary `CALL` to it by name (only the bare `GT_INIT`/etc.
mnemonics), so nothing caught the mismatch until the final link step,
which failed to find a symbol literally called `gt_init` anywhere.

The fix (`TypeChecker.forceGhostTableFunctionNames`, called once from
`check()` right after `collectDeclarations` -- every function in the
whole compilation unit, every import included, is fully collected by
then): force each of the (up to four) ghost-table-decorated functions'
own `mangledName` -- the single field every emission site (`FUNC_START`'s
own label, and any ordinary `CALL` to the function by name) already
treats as the sole source of truth for its assembly-level identity -- to
be the literal decorator name itself, regardless of what the user
actually wrote in source. A collision guard runs first: if a *separate*
function is already literally named e.g. `gt_init` while a different
function also carries the `@gt_init` decorator, that's a hard compiler
error (the two can't coexist once the decorated one is also forced to
that name) rather than a silent, arbitrary pick.

`@par_call`/`@await_call` are deliberately **not** touched by this fix:
their own call sites (`BytecodeEmitter.emitAsyncCall`) already call
`glueInfo.mangledName` directly rather than a hardcoded literal, so they
were never affected by this bug in the first place.

Verified against both previously-broken fixtures, through the real
shipped `Compiler.java` orchestrator, on both `linux` and `windows_gnu`
(under Wine) targets: `gt_unwind_no_throw_cg_test.caspien` now links and
runs, printing `r=42` and exiting 0 exactly as its own doc comment
claims; `gt_unwind_throw_multi_frame_cg_test.caspien` now links and
runs, printing `main enter` / `g enter` / `f enter` then exactly three
`destruct` lines (innermost frame first) and exiting 1, exactly as its
own doc comment claims. Full 21-fixture `_cg_test` regression suite
re-run afterward with no new failures (the same pre-existing,
unrelated failures as before this change: `array_literal_narrow_elem`,
`array_struct_member`, `atomic_swap_global_scalar`,
`register_overflow_args_even`/`odd`, `struct_array_member_narrow_elem`,
`struct_mixed_width`).

## Real, OS-thread-backed "par"/"await" -- the design finalized earlier is now implemented

Previously "par"/"await" were bytecode-inert: `checkPar`/`checkAwait`
type-checked their operand but emitted a bare `PAR_CALL`/`AWAIT_CALL`
mnemonic naming the callee, with no handle, no thread, no real
semantics at the bytecode level at all. This round makes them genuinely
real: `await foo(...)` now blocks on a real OS thread and yields
`foo`'s real return value; `par foo(...)` now starts a real OS thread
and (for a non-void `foo`) hands back a real, `gt_register`'d handle
pointer the caller can poll/`:resolve()` later. **`PAR_CALL`/
`AWAIT_CALL` no longer exist anywhere in this bytecode format at all**
-- confirmed by grep against real compiled output -- everything below
desugars to ordinary `NEW`/`GT_REGISTER`/`CALL`/`ASSIGN`/`GT_DESTRUCT`
shapes an existing backend already knows how to handle.

**1. `@async` parameter restriction** (`TypeChecker.requireAsyncParamShape`,
called from `buildFuncInfo` once `paramTypes` is populated): an `@async`
function may take at most one parameter, and if present, it must fit in
a single machine register (<=8 bytes) -- `u8/16/32/64`, `s8/16/32/64`,
`bool`, `char`, or any pointer/`raw` type (checked via `paramType.storage
!= null`). A `range` is explicitly excluded even though its own
`sizeof` might suggest otherwise, and **any struct is excluded
outright, regardless of its own real size** -- not just an "oversized"
one, confirmed directly: "no range, no oversized struct" was the exact
instruction, and the simplest, most conservative reading of it bans
every struct base type unconditionally rather than trying to special-
case a small, pointer-only one through (see
`async_par_nested_struct_pointer_requires_unsafe_error_test.caspien`,
a pre-existing negative fixture whose own single-pointer-member `Box`
struct is now rejected by *this* check specifically, before ever
reaching the separate "requires unsafe" check its own name references
-- still a correct rejection, just for this new, more fundamental
reason now hit first). See `async_param_shape_ok_test.caspien` (the
0-arg/1-arg-scalar positive shapes) and
`async_param_shape_too_many_args_error_test.caspien`/
`async_param_shape_range_arg_error_test.caspien` (the 2-arg and
range-arg negative shapes) for dedicated fixtures covering exactly
this restriction.

**2. The compiler-synthesized `AsyncHandle_T` struct now carries real
plumbing fields, and a void `@async` function gets its own hidden,
never-exposed handle shape.** `getOrCreateAsyncHandleType(resultType,
paramType, at)` -- `resultType == null` means "hidden": no `state`,
no `result`, no `resolve()` method, nothing a real "par"/"await" ever
exposes to a caller, used purely as codegen-internal plumbing for a
void callee. Exposed shape (member order): `state` (`AsyncState`),
`value` (the packed argument, typed exactly as the real parameter --
never a uniform `u64`, since this bytecode's `as` cast only ever widens,
never narrows or reinterprets a pointer), `threadId` (`mut u64`,
`pthread_t`-sized), `result` (the real return type). Hidden shape:
just `value`/`threadId`. Memoized by `(resultType-or-"void",
paramType-or-"none")` so two different `@async` functions sharing a
parameter/return shape share one handle struct.

**Struct name is a hex hash, not a literal-substituted type string --
found and fixed the hard way, via a real segfault.** The first version
named a handle struct `"AsyncHandle_" + key.replaceAll("[^a-zA-Z0-9]",
"_")` (e.g. `AsyncHandle_mut_u64_mut_u64`) -- this crashed on the
*second* real `await`/`par` in any program, corrupting the ghost
table's own heap state, eventually segfaulting deep inside
`gt_register`/`gt_destruct`'s own internals. Root cause: the sibling
`caspien-lowerordergenerator` project's own `AddressLoweringPass` sizes
a bare `NEW <structName>` operand by running it through
`CanonicalType.parse` -- a parser built to read a real canonical type
string ("storage?_some?_mutability_baseType"), not a bare struct name.
An underscore-heavy struct name shaped enough like that pattern gets
silently misparsed (e.g. "AsyncHandle_mut_u64_mut_u64" reads as
storage=null, baseType="mut_u64_mut_u64" -- garbage, no struct table
entry, falls back to a generic wrong 8-byte size), so every `NEW` for
that struct allocated far too little heap, corrupting whatever memory
sat immediately after it. Fixed by naming the struct
`"AsyncHandle" + Integer.toHexString(key.hashCode() & 0xFFFFFFF)`
instead -- no underscores at all, so it can never be misparsed as a
canonical type, at the deliberate, acceptable cost of the name no
longer being human-readably tied to its own param/result types (nothing
ever surfaces this name to a real Caspien programmer -- it's purely
compiler-internal).

**3. One compiler-synthesized trampoline function per `@async` function**
(`synthesizeAsyncTrampoline`), pthread's own fixed `void*(void*)` entry-
point shape: `func __trampoline_<name>(argPtr: raw mut u8) raw mut u8`.
Built as **real Caspien source text**, run through the ordinary front-
end pipeline (`Lexer`/`Parser`/`RpnConverter`/`TreeBuilder`) exactly as
`caspien.Main` does for a real file -- a deliberate departure from
`getOrCreateAsyncHandleType`'s own "hand-build the token tree directly"
precedent, since this body is genuinely non-trivial (an `unsafe` block,
a typed `let`, a `memcopy` call, conditional member reads/writes) and
re-lexing a small, self-contained snippet is dramatically lower-risk
than replicating the parser's own exact Token shapes by hand. Body
shape: reinterpret `argPtr` as the real handle type (a `memcopy`-based
two-step "plain-then-`some`-bound" `let` pair -- see its own doc
comment for why a genuine `match Some(...)` alive-proof is deliberately
avoided here, to not force a new `@gt_alive_check` dependency onto
every "par"/"await"-using program), call the real function (reading
`handlePtr.value` if a parameter exists), and, if a result exists,
write it into `handlePtr.result` and set `handlePtr.state =
AsyncState.READY` (**no stray `mut` wrapping this enum-variant
assignment** -- `EnumName.Variant` resolves as `imut`, not
`indeterminate`, so a `mut` wrap there is a real, separate type error:
"cannot make an 'imut' value 'mut'"). Registered and body-checked
*eagerly*, right at synthesis time (`checkFunctionBody` called
directly), not left to `check()`'s own generic top-level sweep -- that
sweep snapshots its own worklist before any function body is checked,
so a function added to `functions` *during* an in-progress body check
(exactly what happens here) would never be visited by it otherwise.
`Token.isTrampolineOwnCall` is the one, narrowly-scoped bypass for this
trampoline's own direct call into its wrapped `@async` function --
ordinarily banned outright ("can only be called through 'par' or
'await'").

**The void-`par` fire-and-forget handle-cleanup problem, and its fix:
a second, distinct, self-destructing trampoline.** A void `@async`
function's hidden handle is never exposed to any caller when reached
via `par` (`checkPar` exposes plain `void` for that whole expression,
not the handle) -- so nothing a caller could ever `GT_DESTRUCT` it
through exists. The caller can't safely destruct it itself either: the
real OS thread the trampoline is running on may still be reading
`handle.value` well after this non-blocking `par_call` has already
returned -- a genuine use-after-free/race, not just a leak, if the
caller destructed it immediately. Fixed by having *this one shape only*
(hidden handle, reached via `par`) get its own, separately-synthesized,
self-destructing trampoline (`prepareAsyncCallSite`'s own
`selfDestructHidden` flag, keyed into the trampoline's own name via a
`"__par_fire_and_forget"` suffix so it's never accidentally shared with
that same function's `await` call sites, which keep using the ordinary,
non-self-destructing trampoline -- `await_call`'s own blocking
`pthread_join` already makes the caller-side destruct safe and non-
racy there, void or not). The self-destructing trampoline calls
`gt_destruct` directly as an ordinary Caspien function call (not the
bare `GT_DESTRUCT` bytecode mnemonic every other destruct site uses),
reinterpreting its own concrete handle pointer down to `raw imut u8`
via the identical `memcopy`-based technique used to unpack `argPtr` in
the first place -- `gt_destruct`'s ordinary exact-baseType-match
argument-compatibility rule has no other way to accept a
struct-specific pointer type here. `Token.asyncTrampolineName` records
each call site's own actual (possibly self-destructing) trampoline name
directly, so `BytecodeEmitter` never has to re-derive it.

**4. `@par_call`/`@await_call`** -- two new decorators, validated
exactly like the existing `@gt_init`/`@gt_register`/etc. family
(`FUNC_BASE_DECORATORS`, one function per compilation unit,
`registerGhostTableFunction`/`getGhostTableFunction`): fixed signature
`func(fn: raw imut u8, handle: raw mut u8) void`
(`requireAsyncGlueSignature`).

**5. `BytecodeEmitter.emitAsyncCall`** -- the real desugaring, at every
`par`/`await` call site: construct the handle inline (classId if any,
then each member per `computeStructLayout`'s own padded layout,
`STACK_LOCK`-reserving the still-unwritten `result` field's own slot
rather than pushing a placeholder value for it), `NEW`+`GT_REGISTER`
it, `ASSIGN` it into a hidden local temp, then `CC_START`/push the
trampoline's own address (`PUSH_LABEL`-free -- just an ordinary `PUSH
name funcPointerCanonical`, resolved to a real function's own code
address by a new `X86Backend` mechanism, see that project's own
CLAUDE.md) and the handle pointer as `ARG0`/`ARG1`, `CALL` the
resolved `@par_call`/`@await_call` function, `CC_END`. Then:
- **`await`**, non-void: read `.result` back out (the same
  `PUSH`+`PUSH_FIELDNAME`+`DOT_LHS`+`DEREF` shape `emitDot`'s own
  storage-pointer branch uses), then `GT_DESTRUCT` the temp -- safe
  unconditionally, void or not, since `await_call`'s own blocking
  `pthread_join` guarantees the thread that used it has already
  finished.
- **`await`**, void: skip the `.result` read entirely (there is no
  such field on the hidden handle shape) but still `GT_DESTRUCT` the
  temp.
- **`par`**, non-void: push the temp as this whole expression's own
  value -- ownership (and eventually `GT_DESTRUCT`) is the caller's
  problem from here on, like any other freshly-`new`'d value.
- **`par`**, void: push nothing at all (matches `voidSafeReturnType`'s
  own "no `PUSH_RET`" convention) -- see the self-destructing-
  trampoline fix above for how the temp still gets reclaimed.

The hidden local's own `ALLOC` is deliberately emitted inline, at the
call site, rather than hoisted to the function's own top the way every
other local's `ALLOC` is (this project's "Whole-function ALLOC
hoisting" convention, above) -- a real, accepted, documented deviation:
retrofitting arbitrary-depth expression scanning (a `par`/`await` can
appear anywhere inside an expression, not just at statement position)
into the hoisting collector was out of scope this round, and nothing
about correctness depends on hoisting position -- the low-order
address-lowering stage assigns every named local's own frame offset
purely from the textual order its `ALLOC` lines appear in, not from
where in the function body they sit.

**6. Real stdlib defaults** -- `stdlib/par_call.caspien`/
`stdlib/await_call.caspien` (both `unsafe`-wrapped, both just a bare
`pthread_create`; `await_call` additionally `pthread_join`s
immediately after), and `pthread_create`/`pthread_join` extern
declarations added to `stdlib/libc.caspien` (`pthread_exit` is
deliberately *not* declared there -- called directly by `X86Backend`'s
own `EXIT_THREAD` case by its fixed C symbol name, the same "already
linked in" precedent `exit` already has for `EXIT`). Linking any of
this needs `-pthread` (added to `/tmp/run_pipeline.sh`'s own `gcc`
invocation) -- though on this sandbox's glibc (2.34+, which merged
`libpthread` into `libc` itself), linking without `-pthread` was also
directly confirmed to still work; `-pthread` is kept anyway as the
portably-correct flag (needed on older glibc, and on `mingw-w64`'s own
`winpthreads` for the `windows_gnu` target).

**A second, genuinely separate whole-program-trigger bug, found and
fixed via the same real end-to-end testing**: a program using *only*
"par"/"await" -- never writing a literal `owns`/`ref`/`new`/`dyn`
anywhere in its own source -- never set `usesOwnsRefDynNew` (a purely
syntactic-presence flag), even though `emitAsyncCall`'s own desugaring
unconditionally emits real `NEW`/`GT_REGISTER`/`GT_DESTRUCT`. Since
`GT_INIT` (the ghost table's own one-time initialization, allocating
its backing buffer and setting `capacity = 4`) is gated on that exact
flag, this meant `GT_INIT` silently never ran at all for such a
program -- confirmed directly via instrumented stdlib output showing
`ghost_table.capacity` stuck at its raw, zero-initialized `GLOBAL`
value (`cap=0`) on every single `gt_register`/`gt_destruct` call, with
`gt_register`'s own growth arithmetic (`capacity * 2`) staying zero
forever. A *single* register+destruct cycle happened to not crash
(pure luck of what a size-0 `realloc` happens to return), but the
*second* one, in any program, reliably corrupted the heap and
eventually segfaulted deep inside `gt_register`/`gt_destruct`'s own
internals (`__gtWriteSlot`) -- exactly the shape a plain double-`await`
of the same function reproduces every time. Fixed by setting
`usesOwnsRefDynNew = true` directly inside `prepareAsyncCallSite`, the
moment any real `par`/`await` call site is type-checked -- the same
"a real, compiler-introduced need, not just literal syntax" spirit
`checkThrow`'s own `usesThrow` trigger already has for this identical
`emitGtRoutineBody` gate.

**Pre-existing async fixtures updated, not just new ones added**:
`async_await_basic_test`, `async_await_void_test`,
`async_par_void_test`, `async_par_handle_full_test`,
`async_await_and_par_share_validation_test`, and
`gt_routine_async_gets_exit_thread_test` all predate this round's real
implementation (written back when "par"/"await" were bytecode-inert
`PAR_CALL`/`AWAIT_CALL` no-ops) and needed real imports added
(`gt_init`/`gt_register`/`gt_alive_check`/`gt_destruct`/`par_call`/
`await_call` -- previously only `gt_destruct`, or nothing at all, was
needed) now that these calls genuinely touch the ghost table and start
real threads; `gt_routine_async_gets_exit_thread_test` additionally
needed its own `"let h = par doAsyncWork()"` changed to a bare `par
doAsyncWork()` statement, since a void `@async` function's own `par`
no longer produces a bindable value at all (see point 5 above).

**Verified end to end**, via the real 4-stage pipeline
(`/tmp/run_pipeline.sh`), against a real test program
(`examples/async_real_thread_manual_check_test.caspien`) exercising a
1-scalar-arg `@async` function via both `await` (blocks, yields the
correct result) and `par` (returns immediately with a real, pollable
handle), and a void `@async` function via both `await` and `par`:
compiles with zero `TODO(codegen)` lines, zero `PAR_CALL`/`AWAIT_CALL`
anywhere in the pipeline at any stage, and runs correctly end to end.
Real OS-thread creation confirmed two ways: (1) `objdump -T` on the
final linked binary shows genuine dynamic-symbol references to
`pthread_create`/`pthread_join`/`pthread_exit`, resolved against the
system's own C library at link time -- not stubbed, not inlined; (2) a
double-`await` of the same `@async` function (two full
construct/register/thread-create/join/read/destruct cycles in the same
run) only ever ran correctly *after* the `usesOwnsRefDynNew` fix above
-- direct, load-bearing evidence the ghost table's own real allocator
state is genuinely exercised by real registration/destruction traffic,
not bypassed.

**Known gaps, now resolved**: `YIELD` is now implemented (see "'yield'
now implemented, and 'sleep' introduced as a real keyword" below);
`JOIN` was removed from the language entirely rather than implemented
(see "'join' removed from the language entirely" above) -- neither
remains an open gap.

## `new` now hands its fresh pointer through `gt_register` before use

Found via a real, minimal end-to-end test: `let p = mut new Point{...};
match Some(p){ unsafe{ printf(...) } }` compiled and ran cleanly but
printed nothing -- `gt_alive_check` always read `p` as not-alive.
Root cause: no `GT_REGISTER` mnemonic existed anywhere in the toolchain
at all, so nothing ever told the ghost table a freshly-`new`'d pointer
exists (the real `gt_register` stdlib function was reachable and
correct, just never called). Confirmed directly against the real
design intent: "what the NEW instruction is really supposed to do is:
malloc(size) amount of memory... copy the data off of the stack into
it... then I pass that malloced pointer thru the provided gt_register
function, before putting it on the stack."

Fixed in `BytecodeEmitter.emitNew`: after emitting `NEW` (unchanged),
it now also calls `requireGhostTableFunctionPresent("gt_register", op)`
and emits a bare `GT_REGISTER` line -- the exact same
gate-then-bare-mnemonic shape `GT_INIT`/`GT_ALIVE_CHECK`/`GT_DESTRUCT`
already use, needing no lowering-pass rewrite for the identical reason
those three don't. See `caspien-codegen`'s own CLAUDE.md for the new
`GT_REGISTER` codegen case this pairs with, and the register-safety bug
that surfaced (and was fixed) while wiring it up -- an existing,
previously-latent gap in the codegen backend's own call-alignment
convention, not something this compiler-side change introduced.

## STACK_LOCK reused for padding at struct construction

Closes a gap the previous round's own changelog entry incorrectly
claimed didn't exist: `emitInstantiate` (the `X{...}` construction
site) was still pushing member values back-to-back with no gaps,
so it didn't actually match the padded layout `emitStruct` declares.
`emitStaticAlloc` was fine already (see below); this was specific to
inline construction.

Fix ("I want STACK_LOCK for the padding at instantiation, and I want
the member access resolution to be correct," confirmed directly):
`emitInstantiate`'s per-member loop now walks
`computeStructLayout(structInfo).entries` -- the same padded layout
`emitStruct` already computes and caches -- instead of the flat
`structInfo.members.keySet()`. `___type` is skipped in this loop (it's
still pushed once, up front, exactly as before); every other real-member
entry is pushed exactly as before; every padding-gap entry now emits a
`STACK_LOCK <n>` line, reusing the existing "move the stack pointer
forward `n` bytes without pushing a value" instruction that the OTC
lock-violation branch already used for its own, different reason (that
branch is untouched -- it still emits its own single `STACK_LOCK
<structName>`, not a byte count, and the optimizer resolves that case
by name, unchanged).

On the optimizer side, `AddressLoweringPass`'s `STACK_LOCK` lowering
rule now checks whether its operand already parses as a plain
non-negative integer -- if so, it's one of these new
compiler-emitted byte counts and is passed through unchanged; only a
non-numeric operand (the OTC case's struct name) is still resolved via
the existing `sizes.sizeOf(structName) - 8 - 8` formula.

Member access resolution (`memberLocOf`/`structSizeOf`) needed no new
change here -- it already walks the padded `LayoutEntry` list correctly
from the earlier round; this round just makes sure construction now
lands values at the offsets that resolution already expects. See
`struct_padding_at_instantiation_test.caspien`.

**Flagged, not fixed:** `CloneGenerationPass.emitMemberPushSequence` (in
the optimizer, used for compiler-generated struct clone/init routines)
has the identical gap -- it pushes classId plus real members
back-to-back with no padding awareness. Left alone for now since it
wasn't part of what was asked; worth a decision on whether it should get
the same treatment.

## Real struct alignment padding, baked into struct definitions by the compiler

"It's actually needed... All structs get padded by the compiler (not
the optimizer); appears in higher order bytecode," confirmed directly.
Before this round, a struct's own size was always just the flat,
gap-free sum of its members' sizes -- flagged, in both projects, as
"ignores alignment/padding entirely" ever since it was first written
(`TypeChecker.computeSizeOf`'s own doc comment on the compiler side,
for the unrelated `sizeof` builtin; the optimizer's own
`SizeCalculator.structSizeOf`, for real memory layout). This round
closes that gap for real memory layout (`sizeof` itself is untouched --
a separate, still-flagged, still-open concern, since it has its own
different rules, e.g. an array's `sizeof` is its *element*'s size, not
its total size).

**`@unpadded` gets a hard, dedicated "not supported" error for now.**
`registerStructLike` (TypeChecker) checks for it immediately after its
existing `STRUCT_DECORATORS` validation and throws outright -- kept in
that allowlist rather than deleted, so writing `@unpadded` gets this
specific, named rejection instead of the generic "not a real decorator"
one. See `unpadded_not_supported_error_test.caspien`.

**Every other struct is now padded to natural/C-like alignment**,
computed and emitted directly into its own `STRUCT_START`/
`STRUCT_MEMBER`/`STRUCT_END` declaration by `BytecodeEmitter.emitStruct`
-- a new `STRUCT_PADDING <bytes>` line, interspersed wherever a real gap
falls. Rule: each field is aligned to its own size (a struct's own
alignment is the max of its members' own, computed recursively -- an
all-`u8` struct is 1-aligned, one containing a `u64` anywhere is
8-aligned); a gap is inserted immediately before any field whose
running offset isn't already a multiple of its own alignment; and the
struct's own total size is rounded up to its own overall alignment at
the end (ordinary trailing padding, so an array of these structs keeps
every element aligned too). `extends` needs no special-case handling at
all: `info.members` is already `flattenStruct`'s own fully flattened
parent-then-own field list by the time this runs, so walking it once,
with the hidden classId field prepended, naturally reproduces "child
fields laid out immediately after parent fields" -- this project's
existing flat `extends` model, not a separately-rounded embedded parent
sub-object. See `struct_padding_alignment_test.caspien`.

Checked directly, as instructed ("check the same corrupted logic is not
reflected at instantiation"): `emitStaticAlloc` emits each member as its
own individually-named `"name.member type value"` line, byte-addressed
rather than positional, so it needed no change either way. `CC_START`/
`CC_END` (call sites) are likewise unaffected -- they wrap arguments, not
struct layout. Stack frames are a deliberately separate, second pass,
not addressed here.

**`emitInstantiate` was initially, incorrectly, also claimed to need no
change here** -- wrong: `structInfo.members` never containing a
`STRUCT_PADDING` entry is true but beside the point, since the actual
push *sequence* at a construction site (`X{...}`) still needs to skip
the physical padding gaps to match the now-padded layout, and nothing
did. Fixed separately -- see "STACK_LOCK reused for padding at struct
construction" below.

While making the optimizer's own offset math (`StructTable`,
`AddressLoweringPass.memberLocOf`/`structSizeOf`) actually consume the
new `STRUCT_PADDING` lines (`StructTable` now tracks a parallel
`LayoutEntry` list -- real members interspersed with padding gaps --
alongside its existing, unchanged, real-members-only `Member` list, so
every other existing caller of `membersOf` needed no change at all),
direct inspection turned up two more, pre-existing instances of the
exact same "classId is last" corrupted assumption the earlier `___type`
fix (below) was supposed to have fully stamped out, both silently stale
ever since: `CloneGenerationPass.emitMemberPushSequence` and
`AddressLoweringPass`'s `STACK_LOCK` size-reduction formula each still
checked `members.get(members.size() - 1).name.equals("___type")`,
the reverse of where the compiler has actually emitted it since that
fix. Both now check `members.get(0)` instead (and
`emitMemberPushSequence`'s own `subList` flipped from `(0, size-1)` to
`(1, size)` to match). Neither had a dedicated regression fixture
exercising a classId-bearing struct with more than one member closely
enough to have caught this.

## The hidden "___type" field was declared last in a struct's own type declaration -- it must be first

Caught by direct inspection while drafting an unrelated struct-padding
proposal: "I'm just looking at this now and I'm seeing ___type at the
end and not at the beginning... I said so many times - the ___type is
at the front of the struct. We have so much that depends on that."

`emitStruct` -- the code that emits a struct's own
`STRUCT_START`/`STRUCT_MEMBER`/`STRUCT_END` type declaration -- was
emitting the hidden `___type` (classId) field's own `STRUCT_MEMBER`
line *last*, after every user-declared member. This directly
contradicted the convention this project had already settled and
documented elsewhere: `emitInstantiate` pushes the classId value
first, before any user-declared field value, at every real
construction site ("`X{...}`" or "`new X{...}`"), and `emitStaticAlloc`
emits `"name.___type imut_u64 <id>"` before any `"name.<member>"` line
for a struct-typed static/global literal (see the "Static/global
struct literals were missing their own hidden "___type" field" section
below). `emitStruct` was the one remaining holdout still declaring it
last.

This was not merely cosmetic. The optimizer's own `StructTable.read`
preserves `STRUCT_MEMBER` declaration order verbatim, with no
assumption of its own about where `___type` belongs, and
`AddressLoweringPass`'s own `memberLocOf`/`structSizeOf` compute every
member's own byte offset purely by walking that declared order from
zero. Declaring `___type` last in the type declaration would have put
the hidden classId field at the *end* of the struct's own real memory
layout -- exactly the reverse of the "classId first" ordering every
actual push/write site already agreed on -- even though nothing in
this session's own architecture ever intended offsets and declaration
order to diverge from each other.

Checked directly, as instructed ("check the same corrupted logic is
not reflected at instantiation"): `emitInstantiate` and
`emitStaticAlloc` were both already correct (classId first); only
`emitStruct` itself had regressed. Fixed by moving the `___type`
`STRUCT_MEMBER` line in `emitStruct` to before the loop over
`info.members`, so all three sites -- the type declaration and both
construction paths -- now agree.

See `struct_type_field_declared_first_test.caspien`: two structs
(`Base`, and `Child extends Base`) are declared and instantiated;
confirmed directly against real compiled output that `STRUCT_MEMBER
___type imut_u64` is now the first `STRUCT_MEMBER` line in both
declarations, immediately after any `STRUCT_DECORATE` lines and before
any user-declared field.

## ATOMIC_PUSH/ATOMIC_ASSIGN -- an ordinary atomic read/write needed its own mnemonic too

Follow-up to the read-modify-write fix just below. Sanity-checking that
fix surfaced a second, narrower gap: "theres no need fo an ATOMIC_ASSIGN?"
was first answered "no" on the reasoning that a single aligned load/store
of an atomic-eligible (<=8-byte) value can never tear -- true, but
incomplete. The actual problem isn't tearing, it's that
`AddressLoweringPass`'s own `ASSIGN` rewrite (mirrored exactly by its
`PUSH`/`ADDR` rewrite) erases every type operand down to a bare byte
count, so by the time low-order bytecode exists there is nothing left
anywhere on that line to tell a future backend "don't reorder this
across another thread's own access to the same location, don't hoist it
out of a loop, don't CSE it with another read of it" -- the same
`volatile`-like guarantee C gives a volatile-qualified access, needed
here because nothing else in this bytecode fences or locks around a
plain atomic read/write. Confirmed directly: "yeah but the lowering
wont so when lowered if its assigning to an atomic it needs to be
ATOMIC_ASSIGN, and it should be in the higher order too."

This applies symmetrically to reads (`PUSH`) and writes (`ASSIGN`)
alike, not just writes -- the read side had exactly the same problem and
was just as easy to miss.

Fixed by emitting a genuinely distinct mnemonic at the one point each of
plain `PUSH`/`ASSIGN` is emitted, exactly this session's standing "emit
the distinguishing fact directly, don't leave it to be inferred later"
rule (see `DEREF`/`NEW_DYN`/`NEW_UDYN`/`RESIZE`/`URESIZE`'s own identical
precedent):

- `BytecodeEmitter.emitExpr`'s `VARREF` case now emits `ATOMIC_PUSH`
  instead of `PUSH` whenever the pushed value's own resolved type is
  atomic (checked via the new private `isAtomicCanonical(String)`
  helper -- a plain `contains("atomic_")` substring test against the
  already-resolved canonical type text, exact rather than heuristic here
  because `TypeInfo.canonical()` always writes that literal segment as
  its own token, immediately bounded by either the start of the string
  or an underscore closing off a prior segment -- no real base type or
  struct name in this language is ever spelled with a literal lowercase
  "atomic_" of its own).
- `BytecodeEmitter.emitAssign` now emits `ATOMIC_ASSIGN` instead of
  `ASSIGN` whenever the assignment target's own resolved type is atomic,
  via the identical helper.
- Deliberately **not** touched: `emitCompoundAssign`/`emitIncrement`/
  `emitDecrement` (unreachable against an atomic target since the
  read-modify-write fix below bans them outright) and `emitSwap`
  (already its own dedicated `ATOMIC_SWAP` mnemonic). `emitSwap`'s own
  left-operand push, being an ordinary `VARREF` push of an atomic
  variable, now naturally comes out as `ATOMIC_PUSH` too, through the
  same single code path every other atomic read goes through -- not a
  special case, just what "emit it at the one real emission site" gives
  for free.
- Also deliberately left alone: the `KEYWORD "let"` case in `emitExpr`
  (pushes a fresh declaration's own name as a target reference, not a
  read of a pre-existing shared value -- no other thread can have
  observed it yet) and a static global's own literal initializer (never
  goes through `emitAssign` at all -- see `foldStaticConstant`'s
  existing `GLOBAL`/`ALLOC_STATIC` path -- so there is no live "previous
  value" a write to it could race with either).

A second, independent, pre-existing bug turned up while verifying this:
recompiling `atomic_global_test.caspien` before either fix landed showed
`GLOBAL myAtomicChar 8 '!'` -- wrong, a `char` should be 1 byte, not 8.
Root cause was entirely on the `caspien-optimizer` side (see that
project's own `CLAUDE.md`): `CanonicalType.parse` had no case for the
literal `"atomic_"` segment `TypeInfo.canonical()` bakes into an atomic
value's type text, so it silently folded `"atomic"` into the base type
itself and `SizeCalculator` fell back to a generic 8-byte default for
the unrecognized result -- wrong for any atomic type narrower than 8
bytes (`char`/`bool`/`u8`/`u16`/`u32`), at every site except
`ATOMIC_SWAP`'s own pre-existing, narrow, instruction-specific
workaround. Fixed generally there, not patched around here.

New fixture: `atomic_push_assign_lowering_test.caspien` -- a dedicated
lowering-demonstration companion to `atomic_global_test.caspien`,
pinning down both bugs by name and showing the correct low-order output
directly (`GLOBAL myAtomicChar 1 '!'`, `ATOMIC_PUSH 1 myAtomicChar`,
`ATOMIC_ASSIGN 1 1 1`). Full corpus re-run clean after this fix: 427/780,
0 `FAIL(optimize)`.

## Atomic read-modify-write via ordinary math was a real, silent gap -- closed

Found by working through, from first principles, what `atomic` is
actually supposed to guarantee: `counter = counter + 1` used to compile
completely silently, decomposing into an ordinary `PUSH counter / PUSH 1
/ ADD / ASSIGN` sequence -- three separate steps with a real gap between
the read and the write-back for another thread to land in, while the
type text riding along on every one of those instructions still said
`atomic`, claiming a guarantee that sequence never actually provided.
`checkSwapOperator`'s own doc comment already explains exactly why
`swap` exists as a dedicated instruction rather than an ordinary
PUSH/ASSIGN pair ("a true read-modify-write can't be safely decomposed
... while remaining atomic") -- this was that identical principle,
missed for plain arithmetic.

**What did *not* need fixing, confirmed directly by walking the actual
mechanics**: a bare `PUSH` (read) or `ASSIGN` (write) of an atomic value
needs no new instruction at all. Every `ATOMIC_ELIGIBLE_BASE_TYPES`
member (`u8/16/32/64`, `s8/16/32/64`, `bool`, `char`) is 1, 2, 4, or 8
bytes -- always exactly one native machine word or smaller, never
assembled from multiple reads -- and a single load or store of a
properly-aligned word-sized value is inherently non-tearable on real
hardware; the `LOCK`-prefixed/dedicated-instruction concern is
specifically about a read-modify-write needing to look indivisible
*across the gap between* a read and a write, which a bare, one-direction
access doesn't have. (The one real prerequisite this surfaces for
whenever a real codegen backend gets built: natural alignment of an
atomic's own storage -- a layout/allocation obligation, not a bytecode
instruction question, so nothing to build here now.) Also confirmed
`swap`'s own natural limit, directly: it's an unconditional exchange, not
a compare-and-swap, so routing an increment through it (`counter swap
(counter + 1)`) doesn't make that increment race-free either -- two
threads can still both read the same old value and one update is lost.
That's a different, inherent limitation of `swap`'s own contract, not
something this fix claims to solve.

**Fix, in two parts** (`TypeChecker.java`):
- **`checkSwapOperator`** gained `isSwapRhsAllowedShape`: `swap`'s right
  operand must now be a bare variable, a literal (`INTEGER`/`FLOAT`/
  `STRING`/`CHAR`/`BOOL`/`NULL`, or a negative-number literal), or an
  `EnumName.Variant` reference -- never a compound expression. This is
  deliberately **not** a soundness fix (`let newVal = counter + 1;
  counter swap newVal` is exactly as racy as the inline form, confirmed
  directly -- no syntactic shape check can see through a variable to
  where its value actually came from); it's a syntax-honesty rule,
  confirmed directly: "if i know my reads and writes (which have to be
  swaps) are atomic, and i know my math operations arent -- then this
  breaking atomicity is the plain reading." Forcing any real computation
  onto its own separate, ordinary (visibly non-atomic) line stops the
  compiler from ever presenting a bundled, atomic-looking
  read-modify-write.
- **`requireNotAtomicOrdinaryWriteTarget`** rejects `+=`/`-=`/`*=`/`/=`/
  `%=` and `++`/`--` against an atomic target outright, no exception --
  these are self-referencing by construction, so there's no
  variable-or-literal-shaped version that isn't secretly reading the
  same atomic. Plain `=` gets a narrower version of the same shape check
  `swap` got (right next to `checkAssign`'s own `isBareEnumVariantRight`
  handling), **not** an outright ban -- an outright ban was tried first
  and broke real, already-correct code:
  `checkLockMatchStatement`'s own synthesized lock-*release* write
  (`"receiver.field = mut StateEnum.OPEN"`) is a plain `=` writing a
  fixed enum literal, genuinely safe (zero dependency on the lock's own
  prior value), and it's reached by nearly every fixture in this
  project's whole corpus (via `gt_init`/`gt_destruct`) -- confirmed
  directly by the corpus dropping from 424 to 279 clean fixtures on the
  first, too-strong attempt, then back to 426 once narrowed to match
  `swap`'s own allowed-shape rule instead.

New fixtures: `atomic_rmw_via_math_error_test.caspien` (the original gap,
now rejected), `atomic_compound_assign_error_test.caspien`,
`atomic_increment_error_test.caspien`, `atomic_swap_computed_rhs_error_test.caspien`
(all four negative), and `atomic_math_via_hoisted_swap_test.caspien`
(positive -- every write shape that's still legal: a literal, a bare
variable, and a computed value hoisted into its own `let` before being
`swap`ped in). Full corpus: 426/426 non-error fixtures compile and
optimize cleanly, zero `FAIL(optimize)` entries, `atomic_global_test.caspien`
(a plain, non-computed atomic write) and every existing lock-match
fixture still pass unchanged.

## `resize(...)` now emits RESIZE/URESIZE instead of one shared RESIZE

`BytecodeEmitter`'s `"resize"` case used to emit one shared `RESIZE`
mnemonic for both the safe (3-argument, with a fill value) and unsafe
(2-argument, no fill value) forms, told apart downstream only by
argument count. Now it emits `RESIZE` for the safe form and `URESIZE`
for the unsafe one, decided by checking the dynarray argument's own
resolved type against the exact `"owns_mut_unsafe_dynarray("` prefix
(`checkResizeBuiltin` forces that argument's mutability to `"mut"`
unconditionally, so this prefix check is exact, not a guess) -- the same
move as the `NEW_DYN`/`NEW_UDYN` split just above, for the same reason:
the two shapes carry genuinely different information (a fill value's
type vs. none), so the mnemonic itself should say which. See
`caspien-optimizer`'s own `CLAUDE.md` for how `AddressLoweringPass`
erases each one to a single element-size operand, and for a second,
adjacent fix that split found in that project's own `CloneGenerationPass`
(a hand-synthesized `RESIZE`/`NEW` pair used when deep-cloning a
dynarray of owns-bearing elements, which needed the identical rename to
keep lowering correctly).

## `dyn([...])` now emits NEW_DYN/NEW_UDYN instead of a shared NEW

`BytecodeEmitter`'s `"dyn"` case (the array-literal branch, not the
`dyn(someString)` one -- that's still `NEW_FROM_STRING`, unchanged) used
to emit a single `NEW dynarray(T) count` / `NEW unsafe_dynarray(T) count`
line for both dynarray flavors. Now it emits two distinct mnemonics,
`NEW_DYN` for the safe flavor and `NEW_UDYN` for the unsafe one, decided
by whether the resolved type's own baseType text starts with
`unsafe_dynarray(`. This mirrors the `LOOKUP_ARRAY`/`LOOKUP_DYN` split
this project's sibling `caspien-optimizer` already made for the
identical safe/unsafe distinction: the two flavors need genuinely
different allocation treatment downstream (a safe dynarray needs room
for an 8-byte length header its unsafe counterpart doesn't have), so the
mnemonic itself says which, rather than a later stage having to parse a
type string apart to find out. See `caspien-optimizer`'s own `CLAUDE.md`
for how `AddressLoweringPass` erases each one (`NEW_UDYN size` vs.
`NEW_DYN size count`) and the full reasoning behind keeping `count` on
one but not the other.

## DEREF now emits its pointee's type

`BytecodeEmitter`'s `"deref"` case used to emit a bare, zero-operand
`"DEREF"` line -- a real gap the user spotted directly: a `DEREF` is a
byte-precise copy, so the low-order stage genuinely needs the pointee's
byte width, and nothing else in the emitted bytecode carried it (the
preceding `PUSH ptr type` line's own size is always the pointer's fixed
8 bytes, never the pointee's). Fixed by emitting `"DEREF " +
op.resolvedType` instead: `checkDerefBuiltin` already resolves the
pointee's own canonical type onto `op.resolvedType` (via the universal
`resolveExprType` wrapper), so this only had to be written down, not
computed. The sibling `caspien-optimizer` project's `AddressLoweringPass`
now erases that type to a byte count (`DEREF pointeeType` -> `DEREF
size`) -- see that project's own `CLAUDE.md` for the full fix and the
user's own stated general principle behind it ("im against getting it
from the previous line as much as possible").

## What this project is

Stage 1 of the Caspien toolchain: compiles Caspien source (`.caspien`)
down to plain-text bytecode. This is a standalone project -- it has no
dependency on the optimizer or codegen stages (see "Provenance"
below), and nothing in `src/main/java/caspien/*` imports anything
outside this package.

## Pipeline

`caspien.Main` is the CLI entry point:

    compiler -i main.caspien main.exe

Usage note: despite the `.exe`-shaped output argument, this project
never produces a real executable -- the given output path receives the
generated bytecode as plain text. That text is also printed to stdout
and written to `output.txt`. A separate `debug.txt` captures the raw
lexer output and the fully type-checked tree (every node's
`resolvedType` annotated) for inspecting intermediate pipeline state.

Stages, in order: lex -> parse -> RPN -> tree-build -> import-resolve
-> dup-expand -> generics-expand -> load `compiler.config` -> type-check
-> bytecode generation.

`compiler.config` is a fixed path (`compiler.config`, resolved
relative to the current working directory) and is required -- missing
entirely is a fatal error, confirmed directly, never a silently-applied
built-in fallback (see `CompilerConfig`).

## Whole-function ALLOC hoisting

"We static alloc, then we Alloc, including the special variable for the
unwinding being the first thing, then we have the rest of the
functionality. We do not have our routines mixed in with our allocs...
the whole point of all of this is to limit the amount of work done, not
more. Obviously the allocs are all supposed to be together, eventually
optimized to a single instruction manipulating the stack pointers,"
confirmed directly. `BytecodeEmitter.emitFuncUnderName` now enforces
this as a real, textual property of the emitted bytecode, for every
function, not just a convention a later assembly stage was trusted to
compensate for:

1. Every `ALLOC_STATIC` this function will ever need (from anywhere in
   its body, no matter how deeply nested).
2. `ALLOC gt_routine_address code_addr` -- always the very first
   *ordinary* allocation.
3. Every other `ALLOC` this function will ever need -- collected from
   every `let` declaration inside every nested `if`/`match`/`loop`/
   `for`/`cast`/`lock`/`unsafe`/`safe`/`assume` body, at any depth, plus
   a `for` loop's own two hidden variables and a `loop`'s own
   `preLoopInit` variables (the default lock-match policy's counters) --
   in the same program order they'd have been reached in.
4. Only then, the `gt_routine` prologue's own label/jump-around/
   `GT_DESTRUCT`-list/`EXIT`-or-`GT_UNWIND` machinery.
5. Only then, `GT_INIT` (for the real entry point) and the function's
   own statement bodies -- each declaration's runtime initialization
   (its `ASSIGN`) still happens at its original position; only the
   frame-reservation itself moved.

`BytecodeEmitter.collectHoistedAllocs`/`collectHoistedAllocsFromStatement`
do the recursive collection (mirroring the same dispatch
`emitStatement`/`emitIfChain`/`emitMatchChain`/`emitLoop`/`emitForLoop`
already use, but collecting rather than emitting); `emitGtRoutineAlloc`/
`emitGtRoutineBody` are the two halves `emitGtRoutinePrologue` was split
into so the reserved slot and the rest of the prologue could be emitted
at two different points. `emitBlock`, `emitPreLoopInit`, and
`emitForLoop` no longer emit any `ALLOC`/`ALLOC_STATIC` of their own --
only the initialization each declaration still needs, at its original
position. `examples/hoist_manual_check_test.caspien` is a regression
fixture specifically exercising a `let` nested inside an `if`, a
`loop`, and a `for`, all declaring `owns` locals, to confirm every one
of their `ALLOC`s ends up hoisted to the function's top ahead of the
`gt_routine` machinery.

## `u64 in string`

`in` now supports a plain `u64` byte/char-code membership check against
a `string`'s own bytes (`checkIn`, the branch checked immediately after
the existing guaranteed-enum-membership one). Unlike the ordinary
range/dynarray shape `in` already had -- a bounds/length
comparison the operands alone already resolve -- a `string` has no
stored length at all (the same "no stored length, must scan" gap
`len()`'s own 2-arg unsafe-dynarray form already has), so this compiles
to a real runtime byte-by-byte scan under its own dedicated mnemonic,
`IN_SCAN leftType rightType returnType` (`Token.isStringMembershipCheck`,
set by `checkIn`, read by `BytecodeEmitter`'s `"in"`/`"into"` case) --
never plain `IN`, the same "different runtime meaning gets its own
mnemonic" precedent `WITHIN` (vs `IN`) and `LEN_SCAN` (vs `LEN`) already
established. Codegen's own scan implementation isn't built yet (no
assembly stage exists), but the bytecode contract is fixed: scan
`rightType`'s bytes up to (not including) its null terminator for a
byte equal to `leftType`'s value.

Explicitly rejected as a match condition (`checkMatchCondition`'s `"in"`
and `"into"` branches) for the identical reason `"u64 in
GuaranteedEnum"` already is -- it's a membership scan, not a bounds
proof, so there's no index/target pair for a proof to ever relax.
`examples/in_string_membership_test.caspien` exercises the ordinary
expression form (compiles to `IN_SCAN`); `examples/
in_string_membership_match_error_test.caspien` confirms the match-
condition form is rejected.

## ARG declarations (lowering happens in the optimizer, not here)

`ARG name type` -- one bare declaration line per parameter, in declared
order, emitted right after `RETURNS` (`emitParamDecls`, called from both
`emitFuncUnderName` and `emitSafeArgsMainWrapper`). This is a pure
signature fact, never a real instruction: no frame slot, no byte size,
no register-vs-stack decision, nothing address-related at all -- exactly
the original, pre-this-session shape this line has always had.

**This compiler used to also do the *lowering*** -- turning `ARG` into a
real `ALLOC` plus an `ADDR`/`PUSH`/`ASSIGN` load sequence, including
deciding per parameter word whether it was register- or stack-
transferred and, for a stack-passed word, computing its own real,
positive byte offset (`16 + shadowStack + stackWordIndex*8`) using this
function's own resolved `@call_convention`. Confirmed directly this was
wrong, twice over, and both corrections are worth recording since they
were successive, not simultaneous:

1. First correction: the offset *arithmetic* specifically shouldn't run
   at compile time ("this is still work taking place in the compiler
   that I specifically asked to be in the optimizer... it breaks the
   purity of the compiler output"). Fixed by having `emitParamLoads`
   emit a uniform `PUSH ARGn` for every word regardless of its eventual
   fate, leaving the register-vs-stack decision and arithmetic to
   `AddressLoweringPass`.
2. Second, larger correction: the *entire* `ARG`-to-`ALLOC` substitution
   -- not just the offset arithmetic -- belongs in the optimizer, full
   stop ("i just said i wanted the ARG to ALLOC conversions to take
   place in the optimizer"). `ARG` naming a parameter and stating its
   canonical type is exactly as much a "high-order" fact as an ordinary
   local's own `let` declaration; turning it into a real, correctly-
   positioned frame slot is genuine low-order, address-lowering work,
   the same category `AddressLoweringPass` already does for every other
   name and type in the program. So `emitParamAllocs`/`emitParamLoads`/
   `argWordTypesOf` are gone from this compiler entirely -- `ARG` is now
   the compiler's *only* output for a parameter, unconditionally, and
   the sibling `caspien-optimizer` project's own `ArgToAllocLoweringPass`
   (run immediately before `AddressLoweringPass`) does the rest: see
   that project's own CLAUDE.md, "ARG-to-ALLOC lowering," for the full,
   now-corrected design.

**The one thing this compiler's own emission order still has to get
right:** `ARG` lines sit textually *before* `gt_routine_address`'s own
`ALLOC` (right after `RETURNS`, exactly where the original `ARG` line
always sat), even though the eventual frame layout needs parameters
positioned *after* it (`gt_routine_address` must sit at the exact same,
function-count-independent offset from the base pointer in literally
every frame -- see "Whole-function ALLOC hoisting" above -- which a
varying parameter count ahead of it would break). This textual-vs-frame
mismatch is deliberately left for `ArgToAllocLoweringPass` to resolve,
not smoothed over here by, say, emitting `ARG` lines after
`gt_routine_address` instead -- doing that would only reintroduce a
"the compiler still knows something about frame layout" leak of exactly
the kind just corrected. The optimizer-side pass documents exactly how
it finds the right insertion point instead.

Two call sites emit `ARG`, not just ordinary functions:
`emitFuncUnderName` (every real function/method) and
`emitSafeArgsMainWrapper` (the hand-synthesized, real C-ABI "main" the
OS/C runtime itself calls -- its own two parameters, "argc"/"argv", get
the identical bare `ARG` treatment; this synthesized function carries no
`@call_convention` decorator, so `ArgToAllocLoweringPass`/
`AddressLoweringPass` fall back to their own copy of `compiler.config`'s
own declared default when resolving this function's convention, the
identical fallback `TypeChecker.resolveCallConvention` already applies
on this side).

`examples/arg_to_alloc_lowering_manual_check_test.caspien` isolates the
plain-scalar register/stack boundary directly (a five-`u64`-parameter
function against win64's own four-register default convention) -- this
compiler's own high-order output for it is now just five bare
`ARG a mut_u64` / `ARG b mut_u64` / ... lines, with no `ALLOC`, `ADDR`,
or `PUSH` anywhere for them at all; `main_args_safe_args_full_test.caspien`
(pre-existing) likewise re-verified by hand to show only bare `ARG`
lines here now. (A second fixture, `slice_arg_codegen_test.caspien`,
used to be re-verified alongside it here too; the slice type has since
been removed from the language entirely, and that fixture along with
it -- see "Slice type removal" below.) See the
sibling `caspien-optimizer` project's CLAUDE.md, "ARG-to-ALLOC lowering"
and "Address lowering," for what these fixtures' bytecode looks like
after both of that project's own passes run.

## CAST mnemonic split

`CAST` used to be a single, undifferentiated mnemonic covering more
than one distinct runtime shape, requiring a future codegen stage to
re-derive which shape it was from the trailing operand-type text
alone. Confirmed directly this should instead be reflected as
genuinely different instructions, the same "different runtime meaning
gets its own mnemonic" precedent `IN_SCAN` (vs `IN`) already established
-- scoped narrowly to exactly what the type checker already
unambiguously supports today, not broadened into new language features
("cast just being instances where the compiler can just unambiguously
do it... other types will be castable thru functions in the stdlib,
beyond the scope for now," confirmed directly). Unlike the earlier
`IN`/`INSTANCEOF`/`IMPLEMENTS`/`CLONE` fixes (all optimizer-side lowering
passes, needed because those bytecode shapes were emitted generically
with no separable type information left for a post-hoc pass to work
with), this is a compiler-side change: the type checker already knows
exactly which shape it's looking at the moment it type-checks the
operator, so `BytecodeEmitter` just needs to be told which one, via two
new `Token` fields (`isAliasCast`/`isSignedWideningCast` -- see their
own doc comments in `Token.java`) set by `checkAs` and read directly at
emission time. No new optimizer pass, no type-recovery heuristic
needed.

**`as` (`TypeChecker.checkAs`) has exactly two shapes today:**
- **Alias relabel** (`value as SomeAlias`, legal only when the two
  sides' fully-resolved storage+baseType are already structurally
  identical -- a pure compile-time rename). Now emits **nothing** at
  all (`op.isAliasCast`), the same zero-bytecode-presence treatment the
  `mut`/`imut` MODIFIER case already gets -- there is nothing for a
  runtime instruction to do to an already-identical representation, and
  `canonical()` never embeds an alias name anyway ("there's no aliases
  in the bytecode," confirmed directly, pre-existing), so the two
  sides' emitted type text was always identical here regardless.
- **Same-signedness-family integer widening** (`checkAs` requires both
  sides be `INTEGER_TYPES`, the same family via
  `leftType.baseType.charAt(0)`, and strictly wider -- narrowing,
  cross-signedness, float, and pointer casts are all still rejected,
  unchanged). Now emits `SEXT` (signed family) or `ZEXT` (unsigned
  family) in place of the old, single `CAST` (`op.isSignedWideningCast`).

`BytecodeEmitter` only ever emitted `"CAST"` from one site (`emitAs`),
confirmed by direct grep, so this is the complete picture -- no other
implicit-conversion path needed updating.

**`within` (`TypeChecker.checkWithin`, `BytecodeEmitter`'s `"within"`
case) always emits the one plain `WITHIN` mnemonic, regardless of
pointerness on either side -- there used to be a compiler-side
`PTR_WITHIN` split here (a `Token.isPtrWithin` field set by
`checkWithin` and read at emission time, mirroring the `CAST` split
above), but it was scrapped and rebuilt on the sibling
`caspien-optimizer` project's side instead**, once that project's own
`MembershipLoweringPass` grew the exact materialize/dereference
machinery `"in"` already needed for a pointer-typed range or dynarray
operand: `"in"` never needed a `PTR_IN` mnemonic for this, because
dereferencing a genuine pointer operand (per
`CanonicalType`'s `isGenuinePointer`, i.e. real pointer storage, not a
dynarray's own intrinsic `owns` storage) is handled entirely downstream
of bytecode emission, from the trailing operand-type text alone --
`"within"` needed no different treatment once it reused the identical
mechanism, so the compiler-side split is now redundant and gone.
`checkWithin` still resolves and validates both operands are ranges;
it just no longer records which side(s) were pointers, since
`BytecodeEmitter` no longer needs to know. See
`caspien-optimizer/CLAUDE.md`'s "Membership-operator lowering" section
for how `WITHIN` gets lowered now (via `tryRewriteWithin`,
`buildRangeStrictSubsetCheck`, both built directly off `IN`'s own
`tryRewriteIn`/`derefInto`/`isGenuinePointer`).

`examples/cast_within_split_manual_check_test.caspien` exercises the
`CAST`-split shapes end to end against real compiled bytecode -- a
signed widening cast (`SEXT`), an unsigned widening cast (`ZEXT`), an
alias relabel (no instruction) -- plus confirms `"within"` compiles to
plain `WITHIN` both when both operands are already inline range values
and when one is a pointer (never `PTR_WITHIN` in either case, now).
`as_operator_test.caspien` and `type_alias_basic_test.caspien` (both
pre-existing) were also re-verified by hand against their new output --
the former now emits `ZEXT` where it used to emit `CAST`, the latter now
emits no instruction at all for its alias cast, where it used to emit a
`CAST` line whose left/right operand text happened to be identical.

## The `--` operator

Added as a brand-new language feature, confirmed directly, in the same
breath as a sibling `caspien-optimizer` request for its own `DEC`
lowering: "if you could add the support for --, and subsequently
dec_int/dec_float." Until this, `++` was the only increment/decrement
operator this compiler had -- `--` simply didn't exist anywhere in the
source (no lexer token, no parser rule, no `BytecodeEmitter` emission),
confirmed by grep before starting. Implemented as an exact mirror of
`++` end to end, one small addition at each of the same five sites
`++` itself touches, sharing every rule `++` already has rather than
duplicating any of them:

- **`Lexer`**: `"--"` added to `MULTI_CHAR_OPERATORS` alongside `"++"`
  -- greedy two-character merge, identical maximal-munch behavior (so
  `a--b`, like the pre-existing `a++b`, lexes as `a`, `--`, `b`, not as
  `a - (-b)`; write a space, `a - -b`, for that).
- **`RpnConverter`**: the existing "postfix, no-operand-required-after"
  special case for `"++"` (checked and consumed before the ordinary
  unary/binary classification) now also matches `"--"` -- both are
  handled by the identical branch, not two separate ones.
- **`TreeBuilder`**: `arityOf` now returns 1 for `"--"` too, the same
  single-operand shape `"++"` already has.
- **`TypeChecker`**: `resolveOperatorType`'s `"++"` case now also
  matches `"--"`, both routed through the same, entirely unmodified
  `checkIncrement` (renamed in comment only, not signature -- the method
  itself already had nothing `++`-specific in its body: numeric,
  mutable, not a loop's own immutable slot, exactly what `--` needs
  too). `requireNotLoopImmutable`'s own doc comment updated to mention
  both operators.
- **`BytecodeEmitter`**: a new `emitDecrement`, byte-for-byte the same
  shape as the pre-existing `emitIncrement` (assign-target, push value,
  one arithmetic-style line, then the trailing `ASSIGN`) but emitting
  `"DEC"` where `emitIncrement` emits `"INC"`. Dispatched from a new
  `case "--":` alongside the existing `case "++":`.

`math_operators_lowering_test.caspien` (see the sibling
`caspien-optimizer` project's own CLAUDE.md, "Address lowering") was
extended with an `a--` right after its existing `a++`, and a
hand-written `let f = mut 0.5; f--` was compiled separately to confirm
the float side -- both produce a `DEC leftType returnType` line,
structurally identical to `INC`'s own, erasing on the optimizer side to
`DEC_INT`/`DEC_FLOAT` exactly as `INC` erases to `INC_INT`/`INC_FLOAT`.
Re-ran the full fixture suite: all 764 pre-existing fixtures' pass/fail
status unchanged -- zero regressions (`--` being new, nothing pre-existing
could have used it yet to break).

## `clone()` now carries its own type operands

`BytecodeEmitter`'s `"clone"` builtin case used to emit a completely bare
`"CLONE"` line -- `emitExpr(arg); line("CLONE");` -- with no operand text
of its own at all, the only mnemonic in this whole format with a real,
meaningful argument but zero type annotation, unlike every other
instruction here (this bytecode's own "every instruction states the type
of what it leaves on the stack" rule). Flagged directly as a mistake:
"the compiler should output the arguments for CLONE the same style as
other standard operations, and then the optimizer should reduce it to
CLONE size during the lowering." Now emits `"CLONE argType returnType"`
-- `argType` is the cloned pointer's own resolved type
(`checkCloneBuiltin` already guarantees non-null storage), `returnType`
is the builtin's own result type (an `owns` pointer to the same base
type) -- the identical trailing-operand-type shape `NEG`/`NOT`/`DEREF`'s
siblings already use. Dispatched from the existing `case "clone":`,
unchanged in every other respect (same `singleBuiltinArg`, same
`emitExpr` ordering).

This one ripples into the sibling `caspien-optimizer` project on both
sides of its own pipeline, not just `AddressLoweringPass` (its own
CLAUDE.md, "Address lowering," has the full account): `CloneGenerationPass`
runs *before* address lowering and used to recover `CLONE`'s source
pointer type by reading the immediately *preceding* line's own last
token (the only mnemonic in that whole project needing that trick) --
now reads it directly off `CLONE`'s own first operand instead, the same
simplification this change was made for.

`clone_argument_lowering_test.caspien` (new fixture, a pointer to a
plain, non-owns-bearing struct -- the flat "allocate and bytewise copy"
case `CloneGenerationPass` leaves untouched) confirms the compiler side:
`clone(p)` now compiles to `CLONE owns_mut_Point owns_mut_Point` instead
of a bare `CLONE`. Re-ran the full fixture suite: all 766 pre-existing
fixtures' pass/fail status unchanged -- zero regressions -- plus the one
new fixture, which compiles cleanly as intended (767 fixtures total, 415
compiling cleanly).

## `raw` now requires an addressable lvalue (or a string literal)

`checkAddressOf` used to restrict only `auto` to an addressable lvalue
(`isAddressableLvalue` -- a bare `VARREF`, or a `.`-chain rooted in one);
`raw` had no such restriction at all, so `raw` on a bare literal or a
freshly-computed expression (`let x = mut raw 5`) compiled without
complaint. This surfaced as a real, load-bearing gap only while working
out the sibling `caspien-optimizer` project's `ADDR_OF` lowering (see
that project's CLAUDE.md, "Address lowering"): `ADDR_OF`'s own address
computation depends on there being a real, addressable predecessor line
to resolve a location from, and a bare literal has no such predecessor
-- "i had thought raw pointer was my catch all... but it seems like
taking a raw directly off the stack like this seems to create issues."
The fix proposed and confirmed directly: ban it at the language level,
the same way `auto` is already banned, so a fresh value has to be bound
to a `let` first (`let x = 5; let xp = raw x;`) -- "does that simplify
things?" It does, with one deliberate carve-out: a string literal
(`raw "Alice"`) stays legal unchanged, since `TypeChecker`'s own `case
STRING:` already, unconditionally, hoists every string literal to real
static storage (`"static_imut_string"`) before `raw` (or any other
storage keyword) is ever applied to it -- it's already in the same
"real memory, nothing left to compute" position an existing
`owns`/`ref`/`raw`/`static` value would be, just reached through a
literal token instead of a named one. No other literal kind (a bare
number, a `char`) gets this exception -- only `case STRING:` does this
unconditional hoisting; `case CHAR:` returns a plain, storage-less
`TypeInfo`, so a char literal is rejected by this restriction exactly
like a number literal is.

`checkAddressOf` now rejects `storage.equals("raw") && op.left.type !=
TokenType.STRING && !isAddressableLvalue(op.left)` with a dedicated
error message. Two existing fixtures needed updating to keep testing
what they actually intended once this landed: `storage_modifiers_test.
caspien` (which explicitly demonstrated "raw has no restriction" on a
bare literal and a freshly-computed expression) now binds those to
`let`s first (`let five = mut 5; let b = imut raw five;`); `if_condition_
pointer_error_test.caspien` (testing an unrelated error -- a pointer
used as an `if` condition) now binds its literal to a `let` too, so it
still fails with its own originally-intended error rather than this new
one. New negative fixture, `raw_requires_addressable_or_static_error_
test.caspien`, added to prove the restriction itself; `string_hoisting_
struct_member_test.caspien` (the `raw "Alice"` case) re-verified to
still compile cleanly, confirming the string-literal exception holds.
Re-ran the full fixture suite: pass/fail status unchanged on every
pre-existing fixture except the two updated above (which keep the same
`ok` status, just different line numbers/instruction counts) -- zero
real regressions -- plus the one new error fixture (772 fixtures total,
419 compiling cleanly, unchanged since the new fixture is itself an
error test).

## Slice type removal

The `slice(origin, range)` type has been removed from the language
entirely -- it had two known, unfixed bugs (an addressing bug: a
slice's own `storage` is always non-null by design, which made
`AddressLoweringPass.resolveAddress`'s general "bail on non-null
storage partway through a dotted chain" pointer-indirection rule
misfire on `.start`/`.end` access through a slice; and a write-path
bug: `requireMutable`'s reachability check was broken for slices,
so writing through one threw unconditionally) -- rather than keep
patching a type with no working write path. Scalar indexing
(`arr[2]`) on arrays/dynarrays/strings is unaffected; `range` and
`dynarray` themselves are unaffected. Only range-indexed slicing
(`arr[a..b]`) and the `slice` type itself are gone.

`arr[a..b]` is now a deliberate compile-time type error, raised in
`checkLookup` the moment the index expression resolves to a range
type: `"slicing ('arr[range]') is no longer supported -- Caspien has
removed the slice type; use a scalar index or a range/pointer pair
explicitly"`. Writing `slice` as a type name (e.g. `let x:
slice(u64) = ...`) is likewise rejected immediately in
`resolveTypeAnnotationRaw`. Every fixture that existed specifically
to exercise slice behavior was deleted; every fixture that used
slicing only incidentally was rewritten to drop that one part while
keeping its own actual purpose.

## Files

- `Lexer.java`, `Token.java`, `TokenType.java` -- tokenizing
- `Parser.java`, `RpnConverter.java`, `TreeBuilder.java`, `TreeDump.java` -- parsing into a tree
- `ImportResolver.java`, `DupExpander.java`, `GenericsExpander.java` -- tree-level expansion passes
- `TypeChecker.java` -- the type checker (by far the largest file in
  this project -- see "Known debt")
- `BytecodeEmitter.java` -- walks the checked tree, emits bytecode text
- `CompilerConfig.java` -- loads/parses `compiler.config`
- `CompilerException.java` -- the one exception type used for every
  fatal compile error
- `Main.java` -- CLI entry point, wires the whole pipeline together

## Known debt

`TypeChecker.java` is roughly 15,600 lines -- far past this project's
own "~1500 lines per file, one responsibility" convention. This is
pre-existing (not introduced by splitting this project out) and is
flagged here rather than silently left undocumented. Breaking it up is
out of scope for this split; treat it as a known follow-up.

## Testing

Hand-written regression fixtures live in `examples/` (no test
framework -- plain JDK, no dependencies -- so re-running these manually
through `Main` after any pipeline change is the only safety net).
Fixtures with `_error_test` or `_error` in the name are **negative**
fixtures -- expected to fail to compile. Everything else is expected to
compile cleanly. Fixtures with `_cg_test` in the name additionally
exercise the codegen stage end-to-end when run through the sibling
`caspien-codegen` project's `CodegenMain --run` -- that downstream step
lives in the other project now, not here; this project only needs to
compile them cleanly to bytecode.

Last verified state (may have drifted since the split): 403 positive
fixtures compile cleanly, 332 negative fixtures correctly fail (331
plus the directory-based `circular_test` fixture), 18 positive
fixtures intentionally frozen. Re-verified directly, before and after
the whole-function ALLOC-hoisting change above: every one of the 755
fixtures in this directory (404 compiling cleanly, 351 failing) gave
the exact same pass/fail result both before and after -- zero
regressions -- plus one new fixture added by that same change
(`hoist_manual_check_test.caspien`, see above), which also compiles
cleanly. Re-verified again, before and after the "`u64 in string`"
change above: all 755 pre-existing fixtures unchanged -- zero
regressions -- plus the two new fixtures that change added
(`in_string_membership_test.caspien`,
`in_string_membership_match_error_test.caspien`), both giving their
intended result (758 fixtures total, 406 compiling cleanly). Re-verified
again after adding `membership_lowering_manual_check_test.caspien` (a
fixture written for the sibling `caspien-optimizer` project's own
`MembershipLoweringPass` to compile real bytecode against -- see that
project's CLAUDE.md, "Membership-operator lowering" -- not itself
testing anything in this project beyond "does it compile"): all 758
pre-existing fixtures unchanged -- zero regressions -- plus the one new
fixture, which compiles cleanly as intended (759 fixtures total, 407
compiling cleanly). Re-verified again after adding
`clone_generation_manual_check_test.caspien` (a fixture exercising a
two-level owns chain and a dynarray of owns-bearing struct values
passed through `clone()`, written for the sibling `caspien-optimizer`
project's own `CloneGenerationPass` to compile real bytecode against --
see that project's CLAUDE.md, "Ownership-aware clone generation"): all
759 pre-existing fixtures unchanged -- zero regressions -- plus the one
new fixture, which compiles cleanly as intended (760 fixtures total, 408
compiling cleanly). Re-verified again after the CAST/WITHIN mnemonic
split above (see that section) and adding
`cast_within_split_manual_check_test.caspien`: all 760 pre-existing
fixtures unchanged -- zero regressions -- plus the one new fixture,
which compiles cleanly as intended (761 fixtures total, 409 compiling
cleanly). Re-verified again after the "ARG-to-ALLOC lowering" change
above (see that section) and adding
`arg_to_alloc_lowering_manual_check_test.caspien`: all 761 pre-existing
fixtures unchanged -- zero regressions -- plus the one new fixture,
which compiles cleanly as intended (762 fixtures total, 410 compiling
cleanly). Re-verified again after adding
`new_alloc_size_lowering_test.caspien` (a fixture written for the
sibling `caspien-optimizer` project's own `AddressLoweringPass`'s "NEW
TypeName" -> "NEW size" rewrite to compile real bytecode against -- a
two-`u64`-member struct allocated once on the stack, once via `new` on
the heap -- see that project's CLAUDE.md, "Address lowering"): all 762
pre-existing fixtures unchanged -- zero regressions -- plus the one new
fixture, which compiles cleanly as intended (763 fixtures total, 411
compiling cleanly). Re-verified again after adding
`math_operators_lowering_test.caspien` (a fixture written for the
sibling `caspien-optimizer` project's own `AddressLoweringPass`'s
`AND`/`OR leftType rightType returnType` -> `AND size`/`OR size` and
`INC leftType returnType` -> `INC size` rewrites to compile real
bytecode against -- one of each of this compiler's arithmetic, logical,
and increment operators used exactly once: `+`, `-`, `*`, `/`, `%`,
`&&`, `||`, `++` -- see that project's CLAUDE.md, "Address lowering"):
all 763 pre-existing fixtures unchanged -- zero regressions -- plus the
one new fixture, which compiles cleanly as intended (764 fixtures
total, 412 compiling cleanly). Re-verified again after adding
`push_fieldname_offset_lowering_test.caspien` (a fixture written for the
sibling `caspien-optimizer` project's own `AddressLoweringPass`'s
`PUSH_FIELDNAME fieldName fieldType` -> `PUSH_FIELDNAME memberOffset
size` rewrite to compile real bytecode against -- a two-member struct
(`Pair{a: mut u64, b: mut f32}`) with a non-zero-offset second field,
written to directly (`p.b = mut 9.0`) so `emitAssignTarget`'s
unconditional `PUSH_FIELDNAME` site fires with no `LOOKUP` or pointer
involved at all -- see that project's CLAUDE.md, "Address lowering"):
all 764 pre-existing fixtures unchanged -- zero regressions -- plus the
one new fixture, which compiles cleanly as intended (765 fixtures
total, 413 compiling cleanly). Re-verified again after adding
`not_neq_lowering_test.caspien` (a fixture written for the sibling
`caspien-optimizer` project's own `AddressLoweringPass`'s `NOT leftType
returnType` -> `NOT size` rewrite to compile real bytecode against --
`!=` and `!` used exactly once each, back to back, a `u64` inequality
compare feeding a logical `NOT` of its own bool result -- see that
project's CLAUDE.md, "Address lowering"): all 765 pre-existing fixtures
unchanged -- zero regressions -- plus the one new fixture, which
compiles cleanly as intended (766 fixtures total, 414 compiling
cleanly). Re-verified again after adding
`clone_argument_lowering_test.caspien` (a fixture written for the
sibling `caspien-optimizer` project's own `AddressLoweringPass`'s `CLONE
argType returnType` -> `CLONE size` rewrite to compile real bytecode
against -- see this file's own "`clone()` now carries its own type
operands" section, and that project's CLAUDE.md, "Address lowering"):
all 766 pre-existing fixtures unchanged -- zero regressions -- plus the
one new fixture, which compiles cleanly as intended (767 fixtures total,
415 compiling cleanly). Re-verified again after adding
`neg_lowering_test.caspien` (a fixture written for the sibling
`caspien-optimizer` project's own `AddressLoweringPass`'s `NEG leftType
returnType` -> `NEG size` rewrite to compile real bytecode against --
unary `-` used once on a `u64` (`checkUnaryMinus`'s own
`UNSIGNED_TO_SIGNED` map turns this into a same-width `s64` return type,
so `leftType`/`returnType` genuinely differ in text) and once, inside
`unsafe`, on an `f32` (where they're identical) -- see that project's
CLAUDE.md, "Address lowering"): all 767 pre-existing fixtures unchanged
-- zero regressions -- plus the one new fixture, which compiles cleanly
as intended (768 fixtures total, 416 compiling cleanly). Re-verified
again after adding `atomic_swap_lowering_test.caspien` (a fixture written
for the sibling `caspien-optimizer` project's own `AddressLoweringPass`'s
`ATOMIC_SWAP leftType rightType returnType` -> `ATOMIC_SWAP size`
rewrite to compile real bytecode against -- an atomic `char`, chosen
because its 1-byte size is where a naive `leftType`-based read would
have silently gone wrong: `TypeInfo.canonical()`'s own `"atomic_"`
segment on `leftType` isn't recognized by the optimizer's
`CanonicalType`, which folds it into the base type text instead of
stripping it -- see that project's CLAUDE.md, "Address lowering"): all
768 pre-existing fixtures unchanged -- zero regressions -- plus the one
new fixture, which compiles cleanly as intended (769 fixtures total, 417
compiling cleanly). Re-verified again after adding
`bits_ops_test.caspien` (formerly cited under a lowering-test name that no longer exists) and `dot_lowering_test.caspien` together
(fixtures written for the sibling `caspien-optimizer` project's own
`AddressLoweringPass` rewrites of `SHL`/`SHR`/`BITS_OR leftType rightType
returnType` -> mnemonic + `size`, and `DOT`/`DOT_LHS leftType returnType
returnType` -> `DOT`/`DOT_LHS size`, both signed off on together after a
proposed plan for the untouched-mnemonics list -- see that project's
CLAUDE.md, "Address lowering"): all 769 pre-existing fixtures unchanged
-- zero regressions -- plus the two new fixtures, both of which compile
cleanly as intended (771 fixtures total, 419 compiling cleanly).
Re-verified again after adding `raw_requires_addressable_or_static_error_
test.caspien` (proving the new "`raw` now requires an addressable
lvalue (or a string literal)" restriction above) and updating
`storage_modifiers_test.caspien`/`if_condition_pointer_error_test.
caspien` in place to keep testing what they actually intended: all 769
unmodified pre-existing fixtures unchanged, the two updated fixtures
keeping identical `ok` status (just different line numbers/instruction
counts) -- zero real regressions -- plus the one new fixture, which
fails to compile exactly as intended (772 fixtures total, 419 compiling
cleanly, unchanged since the new fixture is itself an error test).
Re-verified again after adding `addr_of_lowering_test.caspien` (a
fixture written for the sibling `caspien-optimizer` project's own
`AddressLoweringPass`'s `ADDR_OF opText leftType returnType` -> `ADDR_OF
opText $address` **or dropped entirely** rewrite to compile real
bytecode against -- covering all four real shapes in one function
(`auto`/`raw` on a bare local, `auto` on a dot chain, `ref` on an
existing `owns` value, `raw` on a string literal) -- see that project's
CLAUDE.md, "Address lowering"): all 772 pre-existing fixtures unchanged
-- zero regressions -- plus the one new fixture, which compiles cleanly
as intended (773 fixtures total, 420 compiling cleanly).

Made the bytecode representation of a dynarray carry its full type
information, and fixed a real move-checker bug alongside it -- both
asked for directly, while investigating why a dynarray of genuine
`owns`-storage pointer elements (as opposed to inline values) appeared
unconstructible from real source at all (see the sibling
`caspien-optimizer` project's own CLAUDE.md, "GT_LOOP"/"CLONE_LOOP",
for why that question came up: their per-element `owns`-pointer
dispatch paths had never been exercised by a real fixture).

**The move-checker bug**, found first: `dyn([a, b])` for two freshly-
`new`'d `owns` pointers `a`/`b` failed with "use of 'a' after its
ownership was moved" despite `a` never being referenced twice anywhere
in the source. `checkDynBuiltinCore` re-resolved every element a
*second* time (purely to get a real `TypeInfo` for the dynarray's own
element type) after `checkArrayLiteral` had already resolved them once
and move-marked every `owns`-storage one -- the second resolution
re-triggered the VARREF move-check on what was textually still the
first use. Fixed by reusing `checkArrayLiteral`'s own already-computed
`TypeInfo.arrayElementType` instead of re-resolving -- no change to
`Scope.movedSlots` or the VARREF check itself. Flagged, not fixed
(out of scope, pre-existing, unrelated to this bug): `dyn([a, a])` --
a genuine double-use of the same `owns` pointer within one literal --
was already not rejected by `checkArrayLiteral` itself even before this
fix, confirmed directly (it compiles, pushing `a` twice), since its own
move-marking loop only ever adds to `movedSlots` without first checking
it.

**The dynarray-element-storage gap**, found next: even with the move-
checker fixed, a `dynarray(owns mut Leaf)` struct member emitted
byte-for-byte identical bytecode to a plain `dynarray(Leaf)` one
(`STRUCT_MEMBER items mut_dynarray(Leaf)` either way) -- confirmed
directly by compiling both and diffing the output. Every dynarray's
canonical `baseType` was built from `elementType.baseType` alone
(`TypeInfo.dynArray()`/`unsafeDynArray()`, and the matching type-
annotation branches in `resolveTypeAnnotationRaw`), discarding the
element's own storage keyword entirely -- so a dynarray of `owns`
pointers and a dynarray of plain inline values of the same struct were
genuinely indistinguishable to anything reading the bytecode text,
including the sibling `caspien-optimizer` project (see its own CLAUDE.md
for the concrete consequence this had there). Fixed via a new
`TypeInfo.dynArrayElementText(elementType)` helper, used everywhere a
dynarray's `baseType` string is built: embeds the element's own
*storage* keyword when it has one (`"dynarray(owns_Leaf)"` instead of
`"dynarray(Leaf)"`), deliberately still leaving *mutability* out --
unlike storage, mutability was never the missing piece here, and the
pre-existing "two dynarrays holding the same kind of element are the
same type regardless of whether one happened to be constructed from
`mut`-wrapped literals and the other from bare ones" precedent (still
true, unaffected) was specifically about mutability, not storage. A
dynarray of plain values -- the overwhelmingly common case,
`elementType.storage == null` -- is completely unaffected: identical
bare element text, zero change to any existing fixture using one.

**Two further, genuinely separate bugs found and fixed while making
this change, both found only by re-running the full corpus, not
guessed at in advance:**
- A **stale, self-contradictory doc comment**: the `dynArrayElementType`
  field's own comment already claimed `baseType` was built from
  `elementType.canonical()` (the *full* storage+mutability form) --
  directly contradicting `dynArray()`'s own doc comment, right below it
  in the same file, which explicitly said storage/mutability were
  "deliberately never" embedded. The real, pre-fix code matched the
  second comment, not the first -- the first was simply wrong,
  predating (and never updated for) whatever earlier decision actually
  shipped. Rewritten to describe the real, now-true behavior via
  `dynArrayElementText`, with the discrepancy itself called out so it
  doesn't read as if this fix's own design were somehow foretold.
- A real **regression** the first version of this fix introduced,
  caught by re-running the full fixture corpus (410/413 compiling
  cleanly instead of 413/413): `examples/stdlib/make_safe_args.caspien`
  (and the three fixtures that import it) started failing with "return
  type mismatch: function returns 'owns_some_mut_dynarray(dynarray(
  char))' but this statement returns 'owns_some_mut_dynarray(
  owns_dynarray(char))'" -- correct code, spuriously rejected. Root
  cause: `TypeInfo.dynArray()`/`unsafeDynArray()` (the factories used
  for a `dyn([...])`-*inferred* dynarray value) always force `storage`
  to `"owns"` unconditionally, but the *annotation*-parsing branches in
  `resolveTypeAnnotationRaw` never did -- they returned whatever the
  local `storage` variable held, `null` unless the user happened to
  write a storage keyword directly before that exact `dynarray(...)`
  token. Real fixtures always spell "owns" at a dynarray's own
  top-level annotation slot, but routinely omit it at a *nested*
  dynarray-of-dynarrays element position (`make_safe_args`'s own
  declared return type is "owns some mut dynarray(imut dynarray(imut
  char))" -- "owns" written once, outermost, never again for the inner
  element) -- so the annotated type and the real, `dyn([...])`-
  constructed value it returns disagreed on that inner element's own
  storage the moment anything actually looked at it. Invisible before
  this fix (nothing ever read a dynarray element's `.storage`), it
  surfaced the instant `dynArrayElementText` started doing exactly
  that. Fixed by forcing `storage` to `"owns"` in both annotation
  branches too, matching the factories exactly -- with an explicit,
  clear compiler error (rather than a silent override) if the user
  writes some other, genuinely nonsensical storage keyword before a
  `dynarray(...)`/`unsafe dynarray(...)` annotation, since a dynarray
  can never actually have any storage but `owns`.
- A third, narrower consequence of the same root change, also fixed:
  `checkLookup`'s own fallback path for a *type-annotation-only* fixed
  array of dynarrays (`"rows: mut dynarray(dynarray(u64))[2]"`, which
  has no real, storage-bearing per-element `TypeInfo` the way a value
  built from a literal does) re-parses the outer array's own element
  text back apart by hand, via a bare substring extraction assumed
  to always be a plain base type name. With storage now sometimes
  embedded in that text, the extracted inner text could itself be
  `"owns_Leaf"` -- handing that straight to `new TypeInfo(null, ...)`
  would have silently manufactured a bogus, storage-less type literally
  *named* `"owns_Leaf"`. Fixed via a new `TypeInfo.
  parseDynArrayElementText`, the exact inverse of `dynArrayElementText`,
  used at that one call site instead.

**Verified end-to-end**, not just at the type-checker: added
`dynarray_owns_pointer_elements_test.caspien`, a real, compilable
fixture with two dynarrays of genuine `owns`-pointer elements -- one of
a leaf struct with no further owns content (`Leaf`), one of a struct
that itself owns a further pointer (`Branch`, owning a `Leaf`) --
compiled and then run through the sibling `caspien-optimizer` project's
own passes, confirming its previously-untested/possibly-unreachable
per-element `owns`-pointer dispatch paths (`"NONE:PTR"`/`"PTR:name"` in
`CloneGenerationPass`, the `elemIsOwnsPointer` branch in
`DropGlueGenerationPass`) now genuinely fire and lower correctly -- see
that project's own CLAUDE.md for the lowered bytecode this was checked
against. Re-ran the full corpus after every fix in this section:
final state is 763 total fixtures, 414 compiling cleanly (413
pre-existing plus this one new permanent fixture), zero regressions,
zero optimizer crashes on any of the 414 that do compile.

### Warning on `implements` against a never-implemented interface

`x implements SomeInterface` is legal syntax whenever `SomeInterface` is a
real, declared interface -- unlike `x instanceof SomeInterface` against an
interface target, which is rejected outright at compile time (`checkInstanceof`
only accepts names in the `structs` map, never `interfaces`). But if
`SomeInterface` has zero implementers anywhere in the whole program, the
check can never be true. This is legitimate, occasionally-arising code (an
interface declared for a future implementer, or one whose only implementer
was since removed) -- not a type error -- so `checkImplementsOperator` now
emits a non-fatal `[warning]` diagnostic at the check site instead of
throwing:
