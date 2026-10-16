package caspien.typechecker;

import caspien.token.Token;

/** "ASM name { ... }" or "ASM name \"path\"": opaque assembly; `rawText` is the final content for both forms. */
public class AsmInfo {
    /** Null for an unnamed declaration, which self-emits at its declaration point. */
    public String name;
    public String rawText;
    public Token declTok;
}
