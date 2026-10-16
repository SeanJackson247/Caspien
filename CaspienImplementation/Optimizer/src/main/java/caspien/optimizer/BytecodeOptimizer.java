package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;


/**
 * Top-level orchestrator for the "shallow methods" stage only. This
 * class used to also run the real lowering pipeline (membership
 * lowering, clone generation, drop-glue generation, ARG-to-ALLOC
 * lowering, address lowering) -- that work has moved to the sibling
 * caspien-lowerordergenerator project, run as its own separate program
 * against this program's own plain-text output, confirmed directly:
 * "separate the optimizer into two programs -- the shallow methods
 * architecture at the start of the optimizer will be extracted and be
 * the optimizer, and the actual work thats currently been done in the
 * optimizer will just be called the LowerOrderGenerator."
 *
 * Every pass driven by the fixed-point loop below is still a shallow
 * no-op (see each pass's own file) -- this class only wires up that
 * part of the pipeline's shape, not any real optimization logic yet.
 *
 * Pipeline:
 *
 *   repeat until no change (outer loop):
 *       struct member reordering (placeholder, see StructMemberReorderingPass; first, because it
 *           changes layout)
 *       sizeof resolution (always on: SIZEOF Struct T -> PUSH n T from the STRUCT declarations as
 *           they now stand, so constant folding below sees the literal)
 *       struct unpacking
 *
 *       repeat until no change (inner loop):
 *           constant folding
 *           variable elision
 *           variable shifting
 *
 *       dead control flow removal
 *       dead function removal
 *       unused declaration removal (externs, globals/statics, strings nothing refers to any more)
 *       loop unrolling
 *       function inlining
 *
 *   variable allocation reordering (once, after the loop above --
 *   placeholder, not implemented yet, see
 *   VariableAllocationReorderingPass)
 *
 * A note on where the two reordering passes ended up: in the original,
 * single combined project these two placeholders sat *inside* the
 * lowering sequence, specifically "immediately after ArgToAllocLoweringPass
 * and immediately before AddressLoweringPass" -- both of which have now
 * moved to caspien-lowerordergenerator, a genuinely separate program
 * this project's output is handed to as plain text, the same way this
 * project itself only ever receives caspien-compiler's plain-text
 * output. There is no longer a single in-process pipeline for a pass to
 * sit "in the middle of." Since both reordering passes are still true
 * no-ops (neither has any real logic yet), they've been placed here,
 * at the end of this project's own pipeline, alongside every other
 * still-a-placeholder shallow method -- the closest available
 * equivalent to their originally-intended position, given the process
 * boundary. This is a judgment call, not something asked for directly:
 * once either pass grows real logic that needs post-ARG-to-ALLOC or
 * pre-address-lowering information, it may need to move to
 * caspien-lowerordergenerator instead (or that project may need its own
 * pre-address-lowering hook) -- worth revisiting then, not decided here.
 */
public class BytecodeOptimizer {

    private final SizeofResolutionPass sizeofResolution = new SizeofResolutionPass();
    private final StructUnpackingPass structUnpacking;
    private final ConstantFoldingPass constantFolding;
    private final VariableElisionPass variableElision;
    private final VariableShiftingPass variableShifting;
    private final DeadControlFlowRemovalPass deadControlFlowRemoval;
    private final DeadFunctionRemovalPass deadFunctionRemoval;
    private final UnusedDeclarationRemovalPass unusedDeclarationRemoval;
    private final LoopUnrollingPass loopUnrolling;
    private final FunctionInliningPass functionInlining;
    private final StructMemberReorderingPass structMemberReordering;
    private final VariableAllocationReorderingPass variableAllocationReordering;
    private final RegVarHintPass regVarHint = new RegVarHintPass();

    public BytecodeOptimizer() { }

    public BytecodeOptimizer(UnrollConfig unrollConfig) { }

    public BytecodeOptimizer(UnrollConfig unrollConfig, FoldConfig foldConfig) { }

    public BytecodeOptimizer(UnrollConfig unrollConfig, FoldConfig foldConfig, VariableConfig variableConfig) { }

    public BytecodeOptimizer(UnrollConfig unrollConfig, FoldConfig foldConfig, VariableConfig variableConfig, InlineConfig inlineConfig) { }

    /** `messages` receives every note/warning the passes produce. */
    public BytecodeOptimizer(UnrollConfig unrollConfig, FoldConfig foldConfig, VariableConfig variableConfig, InlineConfig inlineConfig, Messages messages) { }

    public BytecodeProgram optimize(BytecodeProgram input) { return null; }
}
