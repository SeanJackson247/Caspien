# typechecker

The semantic checker: every type, ownership, proof, lock and purity rule of the language, plus the synthesised code (async trampolines and handle structs, interface dispatchers, recursion-to-loop lowering). Rules and design notes: `ASTGenerator/docs/TypeChecker.md`.

## Why this is a folder of classes

`TypeChecker` was one file of thousands of lines. Java has no partial classes, so it is now one class split over a **chain of abstract classes**, each `extends` the one before it, and the last one is the real, public `TypeChecker`:

    TypeCheckerModel -> TypeCheckerDeclarations -> TypeCheckerImpls -> TypeCheckerSignatures -> TypeCheckerStatements -> TypeCheckerControlFlow -> TypeCheckerExpressions -> TypeCheckerCalls -> TypeCheckerLocksAndProofs -> TypeCheckerAccessAndAsync -> TypeChecker

Every method body is unchanged and still lives in the class it came from; the split only moved code between files, in the original order. Consequences worth knowing when you edit here:

- Fields, nested types, `static` helpers and instance-initialiser blocks are all in the first class (the "model"), so every later class sees them by inheritance.
- A method that an earlier part calls but a later part defines is declared at the top of the earlier part as `abstract` (under "methods defined in later parts of the chain"). When you add a method that an earlier part must call, add that one-line declaration too; the compiler will tell you when it is missing.
- Members are `public`, not package-private, because the chain classes live in this package while their callers (`caspien.Main`, `FixTool`, `LockNestingCheck` ...) live in `caspien`.
- The constructor lives in the last class; fields it assigns are therefore not `final`.
- Callers use the final class only: `import caspien.typechecker.TypeChecker;`. Nested types are reached through it (`TypeChecker.TypeInfo`).

## Parts

| File | Lines | Holds |
|---|---:|---|
| `TypeCheckerModel.java` | 1302 | nested types, fields and static helpers; proof-kill entry points |
| `TypeCheckerDeclarations.java` | 1347 | entry point, root declaration collection, struct/enum/interface flattening |
| `TypeCheckerImpls.java` | 1364 | impls, libraries, decorator and purity rules, externs and exports |
| `TypeCheckerSignatures.java` | 1371 | function signatures, type annotations, whole-program checks |
| `TypeCheckerStatements.java` | 1376 | function bodies, recursion lowering, statement dispatch, unsafe and assume |
| `TypeCheckerControlFlow.java` | 1286 | match, if, for, return, throw and try |
| `TypeCheckerExpressions.java` | 1352 | expression types, ownership moves, assignment, constants, arithmetic |
| `TypeCheckerCalls.java` | 1367 | calls, builtins, methods and generic methods |
| `TypeCheckerLocksAndProofs.java` | 1386 | match @lock, lookups, casts, alive and bounds proofs |
| `TypeCheckerAccessAndAsync.java` | 1342 | member access, new/par/await, synthesized async and dispatch code |
| `TypeChecker.java` | 699 | methods of TypeChecker |
