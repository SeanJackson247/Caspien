# gasreport

The `--audit` and `--viz` analyses computed from the emitted HOB text: worst-case gas, stack depth, heap use, termination classes. Notes: ASTGenerator/CLAUDE.md and docs/COMPILER_REFERENCE.md.

## Why this is a folder of classes

`GasReport` was one file of thousands of lines. Java has no partial classes, so it is now one class split over a **chain of abstract classes**, each `extends` the one before it, and the last one is the real, public `GasReport`:

    GasReportCore -> GasReport

Every method body is unchanged and still lives in the class it came from; the split only moved code between files, in the original order. Consequences worth knowing when you edit here:

- Fields, nested types, `static` helpers and instance-initialiser blocks are all in the first class (the "model"), so every later class sees them by inheritance.
- A method that an earlier part calls but a later part defines is declared at the top of the earlier part as `abstract` (under "methods defined in later parts of the chain"). When you add a method that an earlier part must call, add that one-line declaration too; the compiler will tell you when it is missing.
- Members are `public`, not package-private, because the chain classes live in this package while their callers (`caspien.Main`, `FixTool`, `LockNestingCheck` ...) live in `caspien`.
- The constructor lives in the last class; fields it assigns are therefore not `final`.
- Callers use the final class only: `import caspien.gasreport.GasReport;`. Nested types are reached through it (`GasReport.TypeInfo`).

## Parts

| File | Lines | Holds |
|---|---:|---|
| `GasReportCore.java` | 1365 | model, report driver, path solver |
| `GasReport.java` | 1167 | methods of GasReport |
