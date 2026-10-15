package caspien.codegen;

import java.util.List;

public final class CodegenResult {
    public final String asm;
    public final List<String> messages;
    public final boolean failed;

    CodegenResult(String asm, List<String> messages, boolean failed) {
        this.asm = asm;
        this.messages = messages;
        this.failed = failed;
    }
}
