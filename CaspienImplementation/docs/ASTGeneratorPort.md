# Porting the ASTGenerator (stage 1) to Caspien

Goal: `CaspienImplementation/ASTGenerator` becomes a native Caspien program (`ast_generator` exe + importable `Frontend` library), producing HOB
text byte-identical to the Java front end in `ReferenceImplementation/ASTGenerator`, so `Compiler.caspien` (the orchestrator) no longer needs the JVM.
Owner decisions (19 Oct 2026): error texts may differ from Java for now (Caspien `throw` takes literals; a failing compile only has to fail with a
message); regex is already gone from the Java front end (`Text.split` etc.); `BigInt` (`stdlib/bigint.caspien`) replaces `java.math.BigInteger`.

## Size and order (Java lines)
token 1.6k, lexer 1.2k, config 0.9k, parser 3.5k, rpnconverter 1.1k, treebuilder 0.4k, importresolver 0.3k, dupexpander 0.3k,
genericsexpander 1.0k, typechecker 18.5k (49 files), bytecodeemitter 5.4k, postpasses 4.3k (gas report, audit, fix, viz), frontend 0.2k.
Order = pipeline order; every stage is verified against the Java front end before the next one starts:
1. token model + lexer (+ `DocComments`, `FsPolicy` runtime-roots text, `TraceConfig`)  2. parser  3. rpn  4. treebuilder  5. importresolver  6. dup/generics expanders
7. typechecker  8. bytecodeemitter  9. postpasses (the HOB path first: `--audit`, `--fix`, `--viz`, gas report last)  10. Frontend + Main + orchestrator switch.

## Design decisions
* **Tokens live in an arena.** One `TokenArena` owns every `Token` (`ObjectList<Token>`); every link (`left`, `right`, `next`, `childs`, `sub`, `pinnedComments`,
  `chk.catchTry`, `chk.unlockOnExit`, ...) is a `u64` id or a `Vec` of ids, with `NONE = u64Max`. Java shares tokens between lists and maps (`Map<Token,..>`, 9 uses;
  `chk.catchTry`, `someIndexElementExpr`, `unlockOnExit`, `preLoopInit`), which an ownership language cannot express with owning pointers; ids make aliasing, identity
  comparison (`==`) and cloning (`TokenCloner`) plain integer work, and sidestep `ProofKills` on the token graph. Ids are never reused within a compile.
* **Lists are pooled too.** The parser aliases whole lists between tokens (`gathered.childs = policyBodyBlock.childs`, cloned tokens share `pinnedComments`/`decorators`) and
  mutates them in place (`flat.clear(); flat.addAll(result)`), so `Token.childs/sub/pinnedComments/...` hold a `u64` list id into a `ListPool` (`Vec` of token ids per list), not
  an owned vector. Sharing = same list id, in-place edits = pool edits; this reproduces the Java semantics exactly. The explicit-stack frames (`ParserTask`, `GatherFrame`) keep
  their shape, with `Object ret/got` replaced by tagged id fields (token id, list id, small result structs in side pools).
* **Recursion is gone from the design** (the front end already avoids it: `CheckFrame`/`CheckDriver`, `ParserTask`, `RpnTask`, `TreeTask`, `ImportFrame`). The five
  recursive helpers in `TypeCheckerLifetimes` (`isScopedStruct`, `refSomePaths`, `rootVar`, `regionOf`, `sourceRoots`) become loops/worklists.
* **Maps/sets** from the stdlib: `HashMap`, `HashSet`, `LinkedHashMap`, `LinkedHashSet`, `TreeMap` (+ `ArrayDeque` as a growable list with a head index). Java `HashMap`
  iteration order is reproduced only where the output depends on it (check each loop; LOG's `HashOrder.caspien` exists for that).
* **Global registries** (`DocComments.BY_TARGET`, `FsPolicy.current`, `TraceConfig`, `DepsLog`, `TypeCheckerModel.WARNINGS`) become fields of one `FrontendState`
  value passed to the stages (no mutable globals; the Caspien program compiles one file per process anyway).
* **Text is bytes.** Java chars are UTF-16; Caspien `String` is bytes. Source files are ASCII in practice; non-ASCII letters in identifiers and `\uXXXX` escapes above
  0xFF are unsupported until a test needs them (they are written down in the lexer as the one known semantic gap).
* **Error texts:** fixed literals naming the stage and rule; the `[error]` line keeps `file:line` where the call site can pass them as a separate value.

## Conventions (ProofKills-driven)
* Text parameters are nullable refs (`ref imut String`) read through `Txt` (`ch`, `len`, `eqLit`, `copy`, `sub`, `append*`), re-matched with `match Some(x)` per use cluster.
* Mutable scan state is an owned struct (`LexState`) passed in and returned (`st = ? f(st)`); owned locals/params are never subject to proofs. A `ref` cursor is read into an owned local at entry.
* Token lists are touched through small accessors/setters (`kindAt`, `lineAt`, `textEq`, `setKind`, `setText`, `initPinned`, ...), one `match Some(list)` per call.
* `ObjectList.take` leaves a null slot (size unchanged): swap with `take` + `setAt`, not `insertAt`.
* Drivers that print must not do it in a `?catch` of a function holding refs (the handler is an `unsafe` call that kills every ref); use `throw e` there and print in `main`.

## Verification
* Per stage: a Java dump tool (`tests/ast_stage_dump`, reflection over `Token`, prints every non-default field, one token per line, children indented) and the Caspien
  `--dump-stage N` print the same text; compare on every `tests/*.caspien` and `docs/examples/*.caspien` (plus the stdlib files they import).
* End to end: HOB from `ast_generator` vs the Java front end on the whole corpus, under the shipped, off, and full compiler configs, plus the existing `*_error_test`
  programs (must fail to compile; message text is not compared).
* Memory and time of the native build are measured against the JVM front end (the combined Optimizer port already needs `-Xmx3600m` to compile).

## Status
* Stage 1 lexer (`lexer/Lexer.caspien`, `LexerWords`, `token/*`, `config/DocComments`, `util/Txt`): done. `lexer/LexDumpMain.caspien` output is byte-identical to the Java `LexDump` on all 428 files of `tests/`, `docs/examples/` and `stdlib/`.
* Stage 2 parser (`parser/ParserLines.caspien` = line passes, decorators; `parser/ParserBlocks.caspien` = keyword-block gathering with a frame Vec of PB_STRIDE slots per `GatherFrame`; `parser/ParseDumpMain.caspien` = dump tool): done. `ParseDumpMain full` is byte-identical to the Java `ParseDump` on all 428 files (stages nest, split, imports, decor, then0, generic0, lines0, lines, full each checked). Error messages are shortened (literals only); the throwing cases are the Java ones.
* Stages 3-7 done and byte-identical on 428 files (dump stages `rpn`, `tree`, `resolved`, `dup`, `generics`): RPN converter, tree builder, ImportResolver (+ SourceParser), DupExpander, GenericsExpander (+ TokClone, StrMap, `pendingBounds` column). Type checker (`typechecker/*.caspien`, dump stages `decl types flat funcs gexpr lets` of `parser/ParseDumpMain`, each compared with the Java `ParseDump` on the 428 files):
  * Declaration collection through pass 1f (structs, enums, interfaces, aliases, consts, externs, functions, impls, libraries, exports, hierarchy enums, dispatchers, overload mangling): identical to Java (`funcs` stage, 428/428).
  * Pass 1a.8 global initialisers (`Globals`, `Expr`): 514 of 521 globals identical, 7 reported "not covered" (`sizeof(S)` pins a layout; arrays of struct literals), 0 differ. Pass 1e root ASM (`RootAsm`): checked on three synthetic cases only (no corpus file has a root ASM); the Java rejects a *named* root ASM with an internal error, mirrored on purpose.
  * Function bodies (`Lets`, `Expr.typeOf` with an environment): the type of every plain `let` in every function, nested blocks included (`if`/`else`/`for`/`while`/`loop`/`match`/`unsafe` bodies), plus the resolved type of every plain assignment, call statement and `return` (`lets` stage; Java side runs the whole checker and reads `chk.resolvedType`; items keyed by function, line and name/kind, plus one `node` line per annotated sub-expression reachable the way Java's dump walks it, i.e. not through struct-literal `LINE` wrappers; the final type of each node after literal retagging): 247,739 corpus items (16,746 lets, 29,381 statements, 201,612 nodes), 247,427 identical, 312 "not covered" (`return` in `@recursive` functions, whose body Java rewrites; interface/generic method calls; `await`/`par`; nested struct-literal arrays; arithmetic on operands not typed yet), 0 differ; 165 of the 428 files cannot be compared (the standalone Java check rejects them). Block scoping: a nested block gets a copy of the environment; `for` binds its variable as `mut u64` for a range, fixed array or safe dynarray source, other binding forms (and `match` forms other than `Some`/`fits`/`in`/`into`/`within`) mark the names they mention unknown. Globals are collected (`Globals.collect`) before the bodies. Covered: literals, variables (params, earlier lets, globals), arithmetic with literal adaptation and raw-pointer arithmetic, comparisons and logical operators, unary `mut/imut/-/atomic/!`, `ref`/`raw`/`auto` address-of, `as`, `wrap`/`sat`, `bits_*`/`len`/`sizeof`/`insecure_rand`, `deref`/`clone`/`memcopy`, calls with overload selection by `typesCompatible`, extern calls, constructors, `new`, `dyn(..)`, `unsafe dyn(..)`, `dyn:<T>([])`, `resize`, indexing (arrays, strings, safe and unsafe dynarrays), member access on any expression, method and static calls, `lo..hi`, range `.start/.end`, `try`/`?`, enum variants, struct and array literals, `let:<T>` bounds. Literal retagging (`Expr.adapt`, Java `adaptLiteral`) is ported at its call sites: binary operands, compared operands, `bits_*` operands, call arguments of the chosen overload (plain, method, static, extern), `let:<T>` bounds, assignment right-hand sides, `return` operands, negative array-literal elements and forced limit constants (`u16Max` is `imut_u16`). Not covered at all: nodes Java annotates that this walker does not record (e.g. the `try` keyword node; only nodes the walker records are compared), compound assignment, retagging in struct-literal members and `as f64`/`wrap` float literals (invisible to the dump), error checking, proofs/locks/async/lifetimes.
  * Not started: statements and control flow, locks and proofs, async, lifetimes, `ProofKills`, every `node.chk` annotation the emitter reads, and the error checks themselves (the slice only types valid programs; failing programs are skipped).

## Lessons (performance and proofs)
* A nullable `ref` of many distinct objects makes the ghost table's `__gtSlotAt` quadratic: no per-token objects. Tokens, lists and strings are struct-of-arrays columns of u64 in one `TokStore` (char arena `sbuf`), read with `getSome`.
* Stores are passed as nullable `ref mut TokStore` and each accessor proves the reference itself (`match Some(ts)`), because any String creation or column growth ends a `ref some` parameter's alive proof.
* `let x = mut (a op b)` needs the parentheses; range bounds must be bare variables; `raw` is a keyword; a char is turned into a number with `String.charCode`.

## Type checker: mapping notes (in progress, see Status)

* The Java checker (18.5k lines, 50 files) is already recursion-free: `CheckFrame` + `CheckDriver` dispatch ~80 frame kinds to `step...` methods. That maps directly onto the frame-Vec scheme used for ParserBlocks, so no restructuring is needed.
* The cost is the data model: `TypeInfo` (immutable, string-canonical forms, BigInteger literal values, sets), `Scope`, `FuncInfo`, `StructInfo`, `ImplInfo` and many HashMaps keyed by string. Plan: type infos and scopes become rows in id-indexed store columns (like TokStore), strings stay string ids, maps use `StrMap`, BigInteger uses `stdlib/bigint.caspien`.
* Order: model/data types first, then Declarations (collection), Signatures, Statements/ControlFlow/Expressions, Calls, then the proof, lock and async parts. Verify with a checker dump (resolved types per token plus warnings) compared against Java on the 428 files after each part.
