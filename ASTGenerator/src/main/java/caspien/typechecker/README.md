# typechecker

The semantic checker: every type, ownership, proof, lock and purity rule of the language, plus the synthesised code (async trampolines and handle structs, interface dispatchers, recursion-to-loop lowering). Rules and design notes: `ASTGenerator/docs/TypeChecker.md`.

## Why this is a folder of classes

`TypeChecker` was one 20k-line file. Java has no partial classes, so it is split into **eleven classes held together by composition** (no inheritance): `TypeChecker` (public, the hub) owns one instance of each other class, and each holds a `hub` reference back to it:

    TypeChecker (hub: entry point `check(..)`, constructor, a few members)
      model -> declarations -> impls -> signatures -> statements -> controlFlow
      -> expressions -> calls -> locksAndProofs -> accessAndAsync   (the hub field names)

- A part reaches another part as `hub.<field>.x` (hub members as `hub.x`); the hub reaches its parts as `<field>.x`. The state (fields, nested types such as `TypeInfo`/`FuncInfo`/`Scope`, constants) is in `TypeCheckerModel`; nested types and statics are qualified by their class (`TypeCheckerModel.TypeInfo`).
- The parts and their fields are public because other stages read the checker's results (`BytecodeEmitter`, `ProofKills`, `LockNestingCheck`, `Frontend`, `FixTool`): they write `checker.model.structs`, `checker.declarations.usesThrow()`, `TypeCheckerModel.WARNINGS`. `check(..)` is the entry point on `TypeChecker` itself.
- The output (HOB, warnings and error messages) is byte-identical to the old chain (checked on every test, example and benchmark source in three configs).

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
