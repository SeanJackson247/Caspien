package caspien.bytecode;

public enum BytecodeTokenKind {
    /** A mnemonic or operand: "PUSH", "12", "imut_u64", a label, a char literal. */
    CODE,
    /** A quoted string literal, quotes included (a STRING line's text). */
    STRING,
    /** Reserved; HOB has no comment syntax. */
    COMMENT
}
