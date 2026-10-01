package caspien.lowerorder;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads already-emitted, plain-text bytecode (BytecodeEmitter's own
 * output) back into the List<List<BytecodeToken>> shape every
 * OptimizationPass works with -- one inner list per line, split on
 * whitespace, with a quoted string kept as a single token (its own
 * spaces included) rather than being split apart -- and a char literal
 * ("' '" included) likewise kept as one token. No regex, hand-scanned
 * character by character, matching this project's own "no regex,
 * anywhere, ever" convention.
 */
public class BytecodeParser {

    public List<List<BytecodeToken>> parse(List<String> rawLines, String originFile) {
        List<List<BytecodeToken>> result = new ArrayList<>();
        for (int lineNo = 0; lineNo < rawLines.size(); lineNo++) {
            result.add(parseLine(rawLines.get(lineNo), originFile, lineNo + 1));
        }
        return result;
    }

    private List<BytecodeToken> parseLine(String rawLine, String originFile, int lineNumber) {
        List<BytecodeToken> tokens = new ArrayList<>();
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
