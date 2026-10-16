package caspien.bytecode;

import java.util.List;

/**
 * HOB text -> BytecodeProgram. Only tokenises and groups tokens into statements
 * (one per line, whitespace split, a quoted string or char literal kept as
 * one token); it does no analysis and no optimisation. Hand-scanned, no regex.
 */
public final class BytecodeParser {

    public BytecodeProgram parse(List<String> rawLines, String originFile) { return null; }

    /** One line of text -> one statement. The only scanner; BytecodeStatement.fromText reuses it for generated instructions. */
    static BytecodeStatement parseLine(String rawLine, String originFile, int lineNumber) { return null; }
}
