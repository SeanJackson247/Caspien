package caspien.codegen;

import java.util.List;

/** `asm` = the assembly text (null when `failed`); `messages` = the lines a stand-alone run prints to stderr; `failed` = a fatal codegen error. */
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
