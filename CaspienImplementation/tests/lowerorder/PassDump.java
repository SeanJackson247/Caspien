package caspien.lowerorder;

import caspien.bytecode.BytecodeParser;
import caspien.bytecode.BytecodeProgram;
import caspien.bytecode.BytecodeSerializer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Test tool for the LowerOrderGenerator port (not part of the compiler): runs the reference pipeline exactly like LowerOrderGenerator.generate
 * and writes the program text before the first pass (00_in.txt) and after every pass that ran (NN_name.txt) into an output folder, plus steps.txt
 * (one "NN name" line per pass that ran). Usage: PassDump <input.hob> <compiler.config> <outdir>
 */
public final class PassDump {
    private static final BytecodeSerializer SER = new BytecodeSerializer();
    private static int n = 0;
    private static StringBuilder steps = new StringBuilder();
    private static Path out;

    private static BytecodeProgram step(String name, BytecodeProgram p) throws Exception {
        n++;
        String id = String.format("%02d", n);
        Files.writeString(out.resolve(id + "_" + name + ".txt"), SER.serialize(p), StandardCharsets.UTF_8);
        steps.append(id).append(' ').append(name).append('\n');
        return p;
    }

    public static void main(String[] args) throws Exception {
        String hob = Files.readString(Paths.get(args[0]), StandardCharsets.UTF_8);
        String cfgText = Files.readString(Paths.get(args[1]), StandardCharsets.UTF_8);
        out = Paths.get(args[2]);
        Files.createDirectories(out);
        BytecodeProgram lines = new BytecodeParser().parse(hob.lines().toList(), args[0]);
        Files.writeString(out.resolve("00_in.txt"), SER.serialize(lines), StandardCharsets.UTF_8);
        CompilerConfig config = CompilerConfig.parse(cfgText, "compiler.config");
        try {
            lines = step("membership", new MembershipLoweringPass().run(lines).lines);
            lines = step("clone", new CloneGenerationPass().run(lines).lines);
            lines = step("dropglue", new DropGlueGenerationPass(config).run(lines).lines);
            lines = step("argtoalloc", new ArgToAllocLoweringPass().run(lines).lines);
            lines = step("addresslowering", new AddressLoweringPass(config).run(lines).lines);
            lines = step("strengthreduction", new StrengthReductionPass().run(lines).lines);
            lines = step("rangecheckfusion", new RangeCheckFusionPass().run(lines));
            if (config.deferredOperands && config.variablesInRegisters) {
                lines = step("rangeendhint", new RangeEndHintPass().run(lines));
            }
            if (config.deferredOperands) {
                lines = step("rangewordsplit", new RangeWordSplitPass().run(lines));
                lines = step("registerform", new RegisterFormPass().run(lines).lines);
            }
            LoopHoistPass loopHoist = new LoopHoistPass(config.deferredOperands && config.variablesInRegisters && config.hoistArrayBases);
            lines = step("loophoist", loopHoist.run(lines));
            lines = step("regvarpromotion", new RegVarPromotionPass(config.deferredOperands && config.variablesInRegisters, config.floatVariablesInRegisters, config.variablesInAllocFunctions, config.variablesInArgRegisters).run(lines));
            lines = step("loophoistfinish", loopHoist.finish(lines));
            lines = step("floattemp", new FloatTempPass(config.deferredOperands && config.floatTemporariesInRegisters).run(lines));
            lines = step("floatintrinsic", new FloatIntrinsicPass().run(lines));
            lines = step("staticfloatcache", new StaticFloatCachePass(config.deferredOperands && config.variablesInRegisters && config.floatVariablesInRegisters).run(lines));
            lines = step("branchfusion", new BranchFusionPass().run(lines));
            lines = step("jumpcleanup", new JumpCleanupPass().run(lines));
            lines = step("indexedaccess", new IndexedAccessPass().run(lines));
            lines = step("globalconstaddr", new GlobalConstAddrPass().run(lines));
            lines = step("lengthcomparefusion", new LengthCompareFusionPass(config.deferredOperands && config.fuseLengthCompare).run(lines));
            lines = step("fielddisplacement", new FieldDisplacementPass().run(lines));
            lines = step("destforwarding", new DestForwardingPass().run(lines));
            lines = step("conditionalmove", new ConditionalMovePass(config.deferredOperands && config.conditionalMove).run(lines));
            lines = step("copyforward", new CopyForwardPass(config.deferredOperands && config.copyForward).run(lines));
            lines = step("looprotation", new LoopRotationPass(config.deferredOperands && config.loopRotation).run(lines));
        } catch (RuntimeException e) {
            steps.append("ERROR ").append(e.getClass().getName()).append(": ").append(e.getMessage()).append('\n');
        }
        Files.writeString(out.resolve("steps.txt"), steps.toString(), StandardCharsets.UTF_8);
    }
}
