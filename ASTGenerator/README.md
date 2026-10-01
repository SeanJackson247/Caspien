# ASTGenerator

(Formerly called "the compiler" -- renamed once a separate top-level
orchestrator project took that name. This project's own job hasn't
changed: it's stage 1 of the Caspien toolchain, compiling Caspien
source (`.caspien`) into plain-text bytecode. See `CLAUDE.md` for the
full history and file-by-file breakdown -- its own internal references
to "the compiler" predate the rename and still mean this project.)

## Build

Plain `javac`, no build tool:

    javac -d out $(find src/main/java -name '*.java')

## Run

    java -cp out caspien.Main -i path/to/program.caspien output.txt

`compiler.config` must be present in the current working directory.

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
