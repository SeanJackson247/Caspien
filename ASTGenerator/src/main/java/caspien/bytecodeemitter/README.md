# bytecodeemitter

Walks the checked token tree and writes the higher-order bytecode (HOB): struct/enum/function emission, statements, expressions, calls, ownership moves, unwinding and landing pads. Design notes: ASTGenerator/docs/BytecodeEmitter.md.

## Why this is a folder of classes

`BytecodeEmitter` was one file of thousands of lines. Java has no partial classes, so it is split into **four classes held together by composition** (no inheritance): `BytecodeEmitter` (public, the hub) owns one instance each of `BytecodeEmitterCore` (state, nested types, constants, shared helpers), `BytecodeEmitterStatements` and `BytecodeEmitterOperations`, and each holds a `hub` reference back to it.

- A part reaches another part as `hub.core.x` / `hub.statements.x` / `hub.operations.x` (hub members as `hub.x`); the hub reaches its parts as `core.x` / `statements.x` / `operations.x`. Nested types and statics are qualified by their class.
- Callers use `BytecodeEmitter` only: `emit(..)`, `funcTermLabels`, `loopTermLabels`, `spinBounds`, `termSites` and `sourcePos` are exposed there. To expose another member to outside code, add a delegating member to `BytecodeEmitter`.
- The output is byte-identical to the old chain (checked on every test, example and benchmark source in three configs).

## Parts

| File | Lines | Holds |
|---|---:|---|
| `BytecodeEmitterCore.java` | 1305 | entry point, strings, struct/enum/function emission, unwinding |
| `BytecodeEmitterStatements.java` | 1410 | static allocs, hoisting, statements, loops, expression dispatch |
| `BytecodeEmitterOperations.java` | 1067 | assignment, ownership moves, arithmetic, comparisons, calls and arguments |
| `BytecodeEmitter.java` | 494 | methods of BytecodeEmitter |
