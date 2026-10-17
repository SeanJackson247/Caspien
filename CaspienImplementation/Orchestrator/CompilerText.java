import java.util.ArrayList;
import java.util.List;

/** The few string helpers Compiler and CompilerCache need (the stages have caspien.bytecode.Text; the root files are compiled alone, so this is a trimmed copy of its split/strip methods). */
final class CompilerText {
    private CompilerText() {
    }

    private static final int SEP_WHITESPACE = 1;
    private static final int SEP_LINE_BREAK = 3;

    /** \s */
    static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
    }

    /** the characters that Java treats as line terminators */
    private static boolean isLineTerminator(char c) {
        return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    /** Java's $ (no MULTILINE): the end of the input, or just before a line terminator that ends the input. */
    private static boolean atDollar(String s, int pos) {
        int n = s.length();
        if (pos == n) {
            return true;
        }
        if (pos == n - 1) {
            char c = s.charAt(pos);
            if (c == '\n') {
                return !(pos > 0 && s.charAt(pos - 1) == '\r');
            }
            return c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
        }
        if (pos == n - 2) {
            return s.charAt(pos) == '\r' && s.charAt(pos + 1) == '\n';
        }
        return false;
    }

    /** replaceFirst("\\.caspien$", "") for a suffix (the $ may sit before a final line terminator) */
    static String stripSuffixAtEnd(String s, String suffix) {
        int n = s.length();
        int pos = s.lastIndexOf(suffix);
        while (pos >= 0) {
            if (atDollar(s, pos + suffix.length())) {
                return s.substring(0, pos) + s.substring(pos + suffix.length());
            }
            if (pos == 0) {
                break;
            }
            pos = s.lastIndexOf(suffix, pos - 1);
        }
        return s;
    }

    /** replaceFirst("^[#;]\\s*", "") */
    static String stripCommentLead(String s) {
        if (s.isEmpty() || (s.charAt(0) != '#' && s.charAt(0) != ';')) {
            return s;
        }
        int i = 1;
        while (i < s.length() && isSpace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    /** s.split(String.valueOf(sep), limit) for a single non-special character */
    static String[] split(String s, char sep, int limit) {
        return splitBy(s, 0, sep, limit);
    }

    /** s.split("\\s+", limit) */
    static String[] splitSpace(String s, int limit) {
        return splitBy(s, SEP_WHITESPACE, ' ', limit);
    }

    /** s.split("\\R") */
    static String[] splitLineBreaks(String s) {
        return splitBy(s, SEP_LINE_BREAK, ' ', 0);
    }

    private static int nextSeparator(String s, int kind, char sep, int from, int[] startOut) {
        int n = s.length();
        for (int i = from; i < n; i++) {
            char c = s.charAt(i);
            if (kind == 0) {
                if (c == sep) {
                    startOut[0] = i;
                    return i + 1;
                }
            } else if (kind == SEP_WHITESPACE) {
                if (isSpace(c)) {
                    int j = i;
                    while (j < n && isSpace(s.charAt(j))) {
                        j++;
                    }
                    startOut[0] = i;
                    return j;
                }
            } else if (kind == SEP_LINE_BREAK) {
                if (c == '\r' && i + 1 < n && s.charAt(i + 1) == '\n') {
                    startOut[0] = i;
                    return i + 2;
                }
                if (c == '\n' || c == '\u000B' || c == '\f' || c == '\r' || c == '\u0085' || c == '\u2028'
                        || c == '\u2029') {
                    startOut[0] = i;
                    return i + 1;
                }
            }
        }
        return -1;
    }

    private static String[] splitBy(String s, int kind, char sep, int limit) {
        List<String> list = new ArrayList<String>();
        boolean limited = limit > 0;
        int index = 0;
        int[] start = new int[1];
        int pos = 0;
        while (true) {
            int end = nextSeparator(s, kind, sep, pos, start);
            if (end < 0) {
                break;
            }
            pos = end;
            if (!limited || list.size() < limit - 1) {
                list.add(s.substring(index, start[0]));
                index = end;
            } else if (list.size() == limit - 1) {
                list.add(s.substring(index));
                index = end;
            }
        }
        if (index == 0) {
            return new String[] {s};
        }
        if (!limited || list.size() < limit) {
            list.add(s.substring(index));
        }
        int size = list.size();
        if (limit == 0) {
            while (size > 0 && list.get(size - 1).isEmpty()) {
                size--;
            }
        }
        String[] out = new String[size];
        for (int i = 0; i < size; i++) {
            out[i] = list.get(i);
        }
        return out;
    }
}
