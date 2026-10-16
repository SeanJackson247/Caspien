# BytecodeParser: the shared bytecode representation

One parser, one set of classes for the plain-text bytecode (HOB and LOB) that the ASTGenerator, Optimizer and LowerOrderGenerator write and the Optimizer, LowerOrderGenerator and Codegen read (package `caspien.bytecode`, sources in `src/main/java/caspien/bytecode/`). The text format stays the only interchange between stages; this folder is just the code that turns it into Java objects and back.

- `BytecodeToken`: text, file, line, kind (CODE / STRING / COMMENT). Immutable.
- `BytecodeStatement`: one line, a token list it owns (a wrapper around a private list, not a subclass of one) (`opcode()`, `text(i)`, `sourceLine()`). `BytecodeStatement.fromText("ALLOC 16", origin)` builds an instruction a pass generates, with the same scanner (never for reading a program).
- `BytecodeProgram`: the statements of a whole program in order. The only thing a pass reads or writes.
- `BytecodeParser`: text lines -> `BytecodeProgram`. Hand-written scanner (no regex): a `"quoted string"` with backslash escapes and a char literal such as `' '` are each ONE token. Tokenises and groups; no analysis.
- `BytecodeSerializer`: `BytecodeProgram` -> text, tokens joined by single spaces, one line per statement.

## Rules every stage using it follows
- Per stage, one class calls `BytecodeParser`/`BytecodeSerializer`: the library entry class (`Frontend.compile` serializes, `Optimizer.optimize`, `LowerOrder.generate`, `Codegen.generate` parses); only `Main` touches files. The order is: text -> parse (finished) -> passes on the `BytecodeProgram` -> serialize (starts after the last pass). Passes do no file or stdout I/O and take their config from the entry class.
- This package imports nothing from any stage.
- Checked by `tests/bytecode_phases_check.sh` (layering for all four stages + exact round trip).

## Build
There is no separate output folder: each stage compiles these sources into its own `out/`, so its classpath is unchanged (and the stage cache hash covers them):

    cd Optimizer          && javac -d out $(find src/main/java ../BytecodeParser/src/main/java -name '*.java')
    cd LowerOrderGenerator && javac -d out $(find src/main/java ../BytecodeParser/src/main/java -name '*.java')
    cd ASTGenerator        && javac -d out $(find src/main/java ../BytecodeParser/src/main/java -name '*.java')
    cd Codegen             && javac -d out $(find src/main/java ../BytecodeParser/src/main/java -name '*.java')

After a change here rebuild ALL FOUR. The same format carries HOB (stages 1-2) and LOB (stage 3 output, Codegen input): both are one instruction per line, so Codegen reads its LOB with `BytecodeParser` too.

---

# The Caspien port (17 Oct)

Every Java file of `src/main/java/caspien/bytecode/` has a `.caspien` twin (the Java copies were removed here; the originals are in `ReferenceImplementation/BytecodeParser/`). Codegen's port imports these files directly.

## Where the port differs from the Java
- The list type is the stand-in `ObjectList<T>` of `Codegen/.../codegen/collections.caspien` (imported across packages; it got `insertAt`/`removeAt` for this port). The stdlib has no list of owned objects.
- Tokens and statements are OWNED, not shared: `BytecodeStatement`/`BytecodeProgram` `addAll`, `addAllAt`, `slice` and the copy constructors (`copyOf`) copy deeply, where Java shares the same token/statement objects between lists. Nothing in the compiler's passes relies on the sharing (a pass builds new lines).
- Not ported (no Caspien equivalent, or identity-based in Java): the capacity and `Collection` constructors, `remove(Object)`, `removeIf`, `indexOf`/`lastIndexOf`/`contains`, `sort(Comparator)`, `stream`, `iterator`, `asList`, `equals`/`hashCode`/`toString`. `set`/`removeAt` return nothing; `Statement.slice` returns a `BytecodeStatement` (Java: a `List`); `get` returns a nullable `ref` (null when out of range).
- Imports cannot be circular, so the line scanner (Java `BytecodeParser.parseLine`) is `BytecodeStatement.scanLine`; `BytecodeParser.parseLine` delegates to it and `BytecodeStatement.fromText/fromTexts` call it directly.
- `BytecodeParser.parse` takes an `ObjectList<String>`; the port-only `parseText(text, origin)` splits like `Files.readAllLines` (`\n`, `\r\n`, `\r`; no empty last line) and parses.
- Everything is byte/ASCII based: whitespace is Java's `Character.isWhitespace` for ASCII (space, 9-13, 28-31) in the scanner and `\s` in `Text`; `Text`'s line terminators are `\n` and `\r` only (Java also has U+0085, U+2028, U+2029; `\R` lacks them too).
- `Text`: `String[]` results are `owns mut ObjectList<String>` (null when Java returns null, e.g. `splitArrayType`; `unsafeOpenParts` holds a null comment element), `int[]` of `findUnsafeUnaudited` is a `Span{start, end}`, every method that builds a string is `@throws` (allocation) and returns `owns some mut String`; string parameters are `ref some imut String`. `split*` take an `s32` limit; the shared engine is `splitBy(s, kind, sep, limit)`; Java's `nextSeparator` (start via an `int[]` out parameter) is `sepStart` + `sepEnd`. `isQualifiedScalar`/`isByValueRangeType`/`isHighGpr` compare in place instead of building substrings. Port-only helpers: `copyOf`, `digitRunEnd`, `spaceRunEnd`, `hasLit` (literal at an index), `isScalarNameFrom`; `atDollar`/`isLineTerminator` are public.
- A range bound must be a bare variable, so computed bounds are hoisted into locals (`rlo1`, `rhi2`...). Functions that allocate and then read a `ref some` String copy it first or re-match it (`ProofKills`).
- Stdlib: `String.litAt` and `String.strAt` became `@pub` (the port needs them).

## Tests (all compare with the Java classes, expected values come from the originals)
`tests/gen_bytecode_text_test.py GROUP` (groups 1-3: every `Text` helper, 584 + 358 + 518 cases from seeded random fragments plus targeted edge cases) and `tests/gen_bytecode_parser_test.py` (group 4, 170 cases: `parseLine`, `parse` + `serialize`, `parseText` vs `Files.readAllLines`, scripted `BytecodeStatement`/`BytecodeProgram` operation sequences) generate `tests/bytecode_text_1..4_test.caspien`. Run with the reference compiler (they need `ReferenceImplementation/BytecodeParser` for the Java side). Not covered: non-ASCII input, U+0085/2028/2029, out-of-range indexes (Java throws), Windows/Wine.
