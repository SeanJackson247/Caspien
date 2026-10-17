package caspien.codegen;

import caspien.bytecode.BytecodeParser;
import caspien.bytecode.BytecodeProgram;
import caspien.codegen.x86backend.X86Backend;

import java.util.List;

public final class Codegen {
    private Codegen() {
    }

    public static CodegenResult generate(String lobText, String origin, String configText) {
        return generate(new BytecodeParser().parse(lobText.lines().toList(), origin), configText);
    }

    /** The same stage on the LowerOrderGenerator's in-memory program (the orchestrator's path; the program is consumed). */
    public static CodegenResult generate(BytecodeProgram program, String configText) {
        try {
            CodegenConfig config = CodegenConfig.parse(configText, "codegen.config");
            X86Backend backend = new X86Backend(config.target, config.bmi2);
            backend.avx = config.avx;
            return new CodegenResult(backend.generate(program.asList()), List.of(), false);
        } catch (CodegenException e) {
            return new CodegenResult(null, List.of(e.getMessage()), true);
        }
    }
}
