# caspien-optimizer

Stage 2 of the Caspien toolchain: higher-order bytecode (text) in, higher-order bytecode (text) out. It holds the "shallow" passes
that rewrite the bytecode before it is lowered. **Twelve passes do real work (see below; `SizeofResolutionPass` is always on); `StructMemberReorderingPass` is still a no-op** (they return their input unchanged). `RegVarHintPass` adds `REGVAR name weight` hints for hot scalar locals
(an `f` token at the end marks an f32 variable; consumed by the LowerOrderGenerator when `variables-in-registers` is on; otherwise stripped there). The real lowering work is the LowerOrderGenerator's job (the next stage).

## Usage

    optimizer -i input.txt output.txt

`input.txt` is the ASTGenerator's bytecode. The orchestrator (`Compiler.java` at the project root) runs this stage for you;
`java Compiler -i hello.caspien output/hello` runs all four. This component takes no config file of its own.

## Build

    cd Optimizer && javac -d out $(find src/main/java -name '*.java')

The zip ships `out/` prebuilt.

## Pipeline (see `BytecodeOptimizer`)

    repeat until no change:
        struct unpacking
        repeat until no change: constant folding, variable elision, variable shifting
        dead control flow removal, dead function removal, unused declaration removal, loop unrolling, function inlining
    struct member reordering (once, placeholder)
    variable allocation reordering (once; `variable-allocation-reordering`, off unless set)
    register-variable hints (once): REGVAR name weight, see RegVarHintPass

Real passes: `RegVarHintPass`, `LoopUnrollingPass` (`loop-unrolling*` keys of `compiler.config`), `FunctionInliningPass` (`function-inlining: off|conservative|balanced|aggressive` and `inline-max-*`), `ConstantFoldingPass` (`constant-folding: on|off`), `VariableElisionPass` (`variable-elision: on|off`), `VariableShiftingPass` (`variable-shifting: on|off`), `StructUnpackingPass` (`struct-unpacking: on|off`), `DeadControlFlowRemovalPass` (`dead-control-flow-removal: on|off`), `DeadFunctionRemovalPass` (`dead-function-removal: on|off`) and `UnusedDeclarationRemovalPass` (`unused-declaration-removal: on|off`);
the configurable ones are off unless set (see the root README, `### loop-unrolling`, `### constant-folding` and `### variable-elision and variable-shifting`). Struct member reordering is still a no-op.

Each pass is a pure function `List<lines> -> PassResult` (`OptimizationPass`); passes know nothing about each other.

## Where things are

`src/main/java/caspien/optimizer/`: one file per pass, `BytecodeOptimizer` (pipeline), `Main` (CLI), `BytecodeParser` /
`BytecodeSerializer` / `BytecodeToken` (the text interchange format).

## Related documents

Root `README.md` (orchestrator, `toolchain.config`), root `CLAUDE.md` (project memory), `LowerOrderGenerator/` (the register-form
pass that does the real code-quality work today lives there, not here).
