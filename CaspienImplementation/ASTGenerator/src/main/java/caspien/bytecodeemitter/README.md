# bytecodeemitter

Walks the checked token tree and writes the higher-order bytecode (HOB): struct/enum/function emission, statements, expressions, calls, ownership moves, unwinding and landing pads. Design notes: ASTGenerator/docs/BytecodeEmitter.md.

## Why this is a folder of classes

`BytecodeEmitter` was one file of thousands of lines. Java has no partial classes, so it is split into **four classes held together by composition** (no inheritance): `BytecodeEmitter` (public, the hub) owns one instance each of `BytecodeEmitterCore` (state, nested types, constants, shared helpers), `BytecodeEmitterStatements` and `BytecodeEmitterOperations`, and each holds a `hub` reference back to it.

- A part reaches another part as `hub.core.x` / `hub.statements.x` / `hub.operations.x` (hub members as `hub.x`); the hub reaches its parts as `core.x` / `statements.x` / `operations.x`. Nested types and statics are qualified by their class.
- Callers use `BytecodeEmitter` only: `emit(..)`, `funcTermLabels`, `loopTermLabels`, `spinBounds`, `termSites` and `sourcePos` are exposed there. To expose another member to outside code, add a delegating member to `BytecodeEmitter`.
- The output is byte-identical to the old chain (checked on every test, example and benchmark source in three configs).

## No recursion: frames and the driver

The emitter's old mutual recursion (expression -> call -> argument -> expression, statement -> block -> statement ...) is an explicit stack. `EmitFrame` is one pending emission (kind, phase, node, a few generic slots); `EmitDriver.run(root)` pops/pushes frames and calls the kind's `step...` method, which does one phase and either finishes, asks for a nested emission (`call`) or replaces itself (`tail`). Fixed "lines and nested emissions" handlers are a SCRIPT frame: a list of `EmitOp`s run in order. Callbacks became data (call mnemonic string, `HoistedAlloc` descriptors instead of Runnables). The tree walkers (hoisting, try labels, call targets, static folding, struct layout) use work stacks. The old `emitX(..)` entry points remain only as wrappers `driver.run(xFrame(..))` for callers outside the steps. A static call-graph scan finds no cycle left in this package.

## Parts

| File | Lines | Holds |
|---|---:|---|
| `BytecodeEmitterCore.java` | 1393 | entry point, strings, struct layout, function emission, blocks, unwinding |
| `BytecodeEmitterStatements.java` | 1649 | static allocs, hoisting walkers, statement and expression frames |
| `BytecodeEmitterOperations.java` | 1316 | assignment, ownership moves, arithmetic, comparisons, calls and arguments |
| `BytecodeEmitter.java` | 612 | methods of BytecodeEmitter (lookup, convert, dot, new, instantiate) |
| `EmitDriver.java` | 145 | the run loop and kind dispatch |
| `EmitFrame.java` / `EmitOp.java` | 158 / 33 | frame and script-op records |
| `HoistedAlloc.java` | 16 | one hoisted local allocation |
