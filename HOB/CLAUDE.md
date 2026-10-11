# CLAUDE.md -- HOB (shared bytecode representation)

Package `caspien.hob` (`HOBToken`, `HOBStatement`, `HOBProgram`, `HOBParser`, `HOBSerializer`), used by `Optimizer/` and `LowerOrderGenerator/`; `README.md` has the rules. Sources only: each stage compiles `../HOB/src/main/java` into its own `out/` (rebuild both after a change here). Imports no stage. `Main` of a stage is the only caller of parser/serializer; passes build generated lines with `HOBStatement.fromText`. Codegen still has a private parser copy (not moved). Check: `tests/hob_phases_check.sh`.
