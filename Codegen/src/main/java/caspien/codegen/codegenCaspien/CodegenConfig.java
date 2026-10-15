package caspien.codegen.codegenCaspien;

import java.util.List;
import caspien.bytecode.Text;

public class CodegenConfig {
    public final CodegenTarget target;

    public final boolean bmi2;

    public final boolean avx;

    private CodegenConfig(CodegenTarget target, boolean bmi2, boolean avx) {
        this.target = target;
        this.bmi2 = bmi2;
        this.avx = avx;
    }

    public static CodegenConfig parse(String text, String path) {
        return null;
    }
}
