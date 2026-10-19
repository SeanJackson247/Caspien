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
* Next: parser.
