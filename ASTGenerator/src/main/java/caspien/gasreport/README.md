# gasreport

The `--audit` and `--viz` analyses computed from the emitted HOB text: worst-case gas, stack depth, heap use, termination classes. Notes: ASTGenerator/CLAUDE.md and docs/COMPILER_REFERENCE.md.

## Why this is a folder of classes

`GasReport` was one file of thousands of lines. Java has no partial classes, so it is split into **two classes held together by composition** (no inheritance): `GasReport` (public, the hub: constructor, the per-function analyses) owns one `GasReportCore` (state, nested types, static helpers, the report entry points) and `GasReportCore` holds a `hub` reference back to it.

- A part reaches the other as `hub.x` (from `GasReportCore`) or `core.x` (from `GasReport`); the inner class `Eval` belongs to a `GasReportCore` instance (`core.new Eval(..)`). Nested types and statics are qualified by their class (`GasReportCore.Result`).
- Callers use `GasReport` only: `POS`, `SPIN`, `build`, `viz`, `classify` and `checkLabels` are delegated there. To expose another static to outside code, add a delegating member to `GasReport`.
- The output is byte-identical to the old chain (checked on every test, example and benchmark source with and without `--audit`/`--viz`).
