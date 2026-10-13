package caspien.lowerorder;
import caspien.bytecode.BytecodeProgram;


/**
 * Final one-shot lowering (outside both fixed-point loops, like DropGlueGenerationPass) of IN, WITHIN,
 * INSTANCEOF, IMPLEMENTS, IS_BASE, IN_SCAN and LEN_SCAN into plain comparison/logic primitives.
 * Operands that are not simple named pushes are popped into "$in_tmpN" temps; unmatched shapes are
 * left as the original bytecode.
 * See docs/MembershipLoweringPass.md.
 */
public class MembershipLoweringPass implements OptimizationPass {

    @Override
    public String name() {
        return "membership-lowering";
    }

    @Override
    public PassResult run(BytecodeProgram lines) {
        return new MembershipLoweringWorker(StructTable.read(lines), EnumTable.read(lines)).process(lines);
    }

}
