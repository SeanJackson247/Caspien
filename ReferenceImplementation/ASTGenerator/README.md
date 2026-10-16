# ASTGenerator

(Formerly called "the compiler" -- renamed once a separate top-level
orchestrator project took that name. This project's own job hasn't
changed: it's stage 1 of the Caspien toolchain, compiling Caspien
source (`.caspien`) into plain-text bytecode. See `CLAUDE.md` for the
full history and file-by-file breakdown -- its own internal references
to "the compiler" predate the rename and still mean this project.)

## Build

Plain `javac`, no build tool:

    javac -d out $(find src/main/java ../BytecodeParser/src/main/java -name '*.java')

## Run

    java -cp out caspien.Main -i path/to/program.caspien output.txt

`compiler.config` must be present in the current working directory.

## Phases

`Frontend.run` runs these in order; each has its own package under `src/main/java/caspien/`.

1. `lexer/` -- source text to `Lexeme`s (strings, comments and code told apart; doc comments registered).
2. `parser/` -- lexemes to `Token` lines: nesting, templates, keyword blocks, `?` sugar, decorators.
3. `rpnconverter/` -- operator precedence (infix to RPN).
4. `treebuilder/` -- RPN to expression trees.
5. `importresolver/` -- loads each import and runs phases 1-4 on it.
6. `dupexpander/` -- `dup` expansion.
7. `genericsexpander/` -- monomorphisation of generics.
8. `typechecker/` -- all semantic rules; annotates tokens (`token.chk`), synthesises glue, lowers `@recursive`.
9. `bytecodeemitter/` -- walks the checked tree and builds the bytecode (`LockNestingCheck` and `checkProofKills` run here, once the whole program is checked), then it is serialised to text.
10. `postpasses/` -- only on request, working from the emitted text: `--audit`, `--fix`, `--viz`, `--fs-report`, debug dump. (`gasreport/` holds the worst-case cost, stack and heap reports.) The `termination:` doc-comment labels are compared with the computed class right after serialising.

## Output

Plain-text bytecode, one instruction per line -- this is the
interchange format consumed by the sibling `Optimizer` and
`LowerOrderGenerator` projects.

## Normal usage

This project is ordinarily run *through* the top-level orchestrator
(`Compiler.java`, one directory up) rather than invoked directly --
the orchestrator handles writing `compiler.config` into place, chaining
this project's output into `Optimizer`, and so on. Run it directly only
when developing or debugging this stage in isolation.

## Tests

See `examples/` (kept in this project's own git history / working
copy, not duplicated into every orchestrator zip) and the "Testing"
section of `CLAUDE.md`.
