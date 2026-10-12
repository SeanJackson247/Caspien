package caspien.bytecode;

import java.util.List;

/**
 * HOB text -> BytecodeProgram. Only tokenises and groups tokens into statements
 * (one per line, whitespace split, a quoted string or char literal kept as
 * one token); it does no analysis and no optimisation. Hand-scanned, no regex.
 */
public final class BytecodeParser {

    public BytecodeProgram parse(List<String> rawLines, String originFile) {
        BytecodeProgram result = new BytecodeProgram();
        for (int lineNo = 0; lineNo < rawLines.size(); lineNo++) {
            result.add(parseLine(rawLines.get(lineNo), originFile, lineNo + 1));
        }
        return result;
    }

    /** One line of text -> one statement. The only scanner; BytecodeStatement.fromText reuses it for generated instructions. */
    static BytecodeStatement parseLine(String rawLine, String originFile, int lineNumber) {
        BytecodeStatement tokens = new BytecodeStatement();
        int i = 0;
        int len = rawLine.length();
        while (i < len) {
            char c = rawLine.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '"') {
                int start = i;
                i++;
                while (i < len && rawLine.charAt(i) != '"') {
                    if (rawLine.charAt(i) == '\\' && i + 1 < len) {
                        i++;
                    }
                    i++;
                }
                if (i < len) {
                    i++; // consume closing quote
                }
                String text = rawLine.substring(start, Math.min(i, len));
                tokens.add(new BytecodeToken(text, originFile, lineNumber, BytecodeToken.Kind.STRING));
                continue;
            }
            if (c == '\'') {
                // A char-literal operand ("'a'", "'\n'", and -- the case a plain
                // whitespace split gets wrong -- "' '"): kept as ONE token, the
                // same way a quoted string is. The shape is fixed by
                // BytecodeEmitter.escapeForBytecode: a single quote, exactly one
                // character of content (or a backslash plus one character), then
                // a closing single quote, and the literal must end the token.
                int end = -1;
                if (i + 3 < len && rawLine.charAt(i + 1) == '\\' && rawLine.charAt(i + 3) == '\'') {
                    end = i + 4;
                } else if (i + 2 < len && rawLine.charAt(i + 2) == '\'') {
                    end = i + 3;
                }
                if (end != -1 && (end == len || Character.isWhitespace(rawLine.charAt(end)))) {
                    tokens.add(new BytecodeToken(rawLine.substring(i, end), originFile, lineNumber, BytecodeToken.Kind.CODE));
                    i = end;
                    continue;
                }
            }
            int start = i;
            while (i < len && !Character.isWhitespace(rawLine.charAt(i))) {
                i++;
            }
            tokens.add(new BytecodeToken(rawLine.substring(start, i), originFile, lineNumber, BytecodeToken.Kind.CODE));
        }
        return tokens;
    }
}
