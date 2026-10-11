# HOB: the shared bytecode representation

One parser, one set of classes for the plain-text bytecode that the Optimizer and the LowerOrderGenerator read and write (package `caspien.hob`, sources in `src/main/java/caspien/hob/`). The text format stays the only interchange between stages; this folder is just the code that turns it into Java objects and back.

- `HOBToken`: text, file, line, kind (CODE / STRING / COMMENT). Immutable.
- `HOBStatement`: one line, a list of tokens (`opcode()`, `text(i)`, `sourceLine()`). `HOBStatement.fromText("ALLOC 16", origin)` builds an instruction a pass generates, with the same scanner (never for reading a program).
- `HOBProgram`: the statements of a whole program in order. The only thing a pass reads or writes.
- `HOBParser`: text lines -> `HOBProgram`. Hand-written scanner (no regex): a `"quoted string"` with backslash escapes and a char literal such as `' '` are each ONE token. Tokenises and groups; no analysis.
- `HOBSerializer`: `HOBProgram` -> text, tokens joined by single spaces, one line per statement.

## Rules every stage using it follows
- `Main` is the only place that calls `HOBParser` and `HOBSerializer`. The order is: text -> parse (finished) -> passes on the `HOBProgram` -> serialize (starts after the last pass). Passes do no file or stdout I/O and take their config from `Main`.
- This package imports nothing from any stage.
- Checked by `tests/hob_phases_check.sh` (layering for both stages + exact round trip).

## Build
There is no separate output folder: each stage compiles these sources into its own `out/`, so its classpath is unchanged (and the stage cache hash covers them):

    cd Optimizer          && javac -d out $(find src/main/java ../HOB/src/main/java -name '*.java')
    cd LowerOrderGenerator && javac -d out $(find src/main/java ../HOB/src/main/java -name '*.java')

After a change here rebuild BOTH. Codegen (stage 4) still has its own copy of the parser/token; it is not moved yet.
