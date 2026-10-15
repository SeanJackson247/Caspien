package caspien.bytecode;

import java.util.ArrayList;
import java.util.List;

/**
 * Small hand-written string helpers used where the compiler used to call java.util.regex.
 * Each method states the pattern it replaces; tests/text_helpers_check.sh compares them with
 * the real regex on random input.
 */
public final class Text {
    private Text() {
    }

    // separator kinds for split()
    private static final int SEP_WHITESPACE = 1;      // \s+
    private static final int SEP_NON_WORD = 2;        // [^A-Za-z0-9_]+
    private static final int SEP_LINE_BREAK = 3;      // \R
    private static final int SEP_CRLF_OR_LF = 4;      // \r?\n

    public static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    public static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    /** \s */
    public static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
    }

    /** the characters that Java treats as line terminators */
    private static boolean isLineTerminator(char c) {
        return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    /** [0-9]+ or \d+ */
    public static boolean isDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** -?\d+ */
    public static boolean isSignedDigits(String s) {
        if (s.startsWith("-")) {
            return isDigits(s.substring(1));
        }
        return isDigits(s);
    }

    /** -?\d+\.\d+([eE][+-]?\d+)? */
    public static boolean isDecimalNumber(String s) {
        int i = 0;
        int n = s.length();
        if (i < n && s.charAt(i) == '-') {
            i++;
        }
        int start = i;
        while (i < n && isDigit(s.charAt(i))) {
            i++;
        }
        if (i == start || i >= n || s.charAt(i) != '.') {
            return false;
        }
        i++;
        start = i;
        while (i < n && isDigit(s.charAt(i))) {
            i++;
        }
        if (i == start) {
            return false;
        }
        if (i == n) {
            return true;
        }
        char e = s.charAt(i);
        if (e != 'e' && e != 'E') {
            return false;
        }
        i++;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            i++;
        }
        start = i;
        while (i < n && isDigit(s.charAt(i))) {
            i++;
        }
        return i > start && i == n;
    }

    /** [A-Za-z0-9_]+ */
    public static boolean isWord(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!isWordChar(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** [A-Za-z_][A-Za-z_0-9.]* */
    public static boolean isDottedName(String s) {
        if (s.isEmpty()) {
            return false;
        }
        char c = s.charAt(0);
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_')) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            char d = s.charAt(i);
            if (!isWordChar(d) && d != '.') {
                return false;
            }
        }
        return true;
    }

    /** prefix followed by exactly one character from chars, e.g. "%t" + [0-3] */
    public static boolean isPrefixedChar(String s, String prefix, String chars) {
        return s.length() == prefix.length() + 1 && s.startsWith(prefix) && chars.indexOf(s.charAt(prefix.length())) >= 0;
    }

    /** prefix followed by one or more digits, e.g. "ARG[0-9]+" or "r\d+" */
    public static boolean isPrefixedDigits(String s, String prefix) {
        return s.startsWith(prefix) && isDigits(s.substring(prefix.length()));
    }

    /** r(8|9|1[0-5]) */
    public static boolean isHighGpr(String s) {
        if (!s.startsWith("r")) {
            return false;
        }
        String n = s.substring(1);
        return n.equals("8") || n.equals("9") || n.equals("10") || n.equals("11") || n.equals("12") || n.equals("13")
                || n.equals("14") || n.equals("15");
    }

    /** \d+\.(start|end) */
    public static boolean isDigitsDotStartOrEnd(String s) {
        int dot = s.indexOf('.');
        if (dot < 0) {
            return false;
        }
        String tail = s.substring(dot + 1);
        return isDigits(s.substring(0, dot)) && (tail.equals("start") || tail.equals("end"));
    }

    private static boolean isScalarName(String s) {
        return s.equals("u8") || s.equals("u16") || s.equals("u32") || s.equals("u64") || s.equals("s8")
                || s.equals("s16") || s.equals("s32") || s.equals("s64") || s.equals("f32") || s.equals("f64")
                || s.equals("bool") || s.equals("char");
    }

    /** (mut|imut)_scalar, or with allowIndeterminate (mut|imut|indeterminate)_scalar */
    public static boolean isQualifiedScalar(String s, boolean allowIndeterminate) {
        int us = s.indexOf('_');
        if (us < 0) {
            return false;
        }
        String q = s.substring(0, us);
        if (!(q.equals("mut") || q.equals("imut") || (allowIndeterminate && q.equals("indeterminate")))) {
            return false;
        }
        return isScalarName(s.substring(us + 1));
    }

    /** (imut_|mut_)?range */
    public static boolean isRangeType(String s) {
        return s.equals("range") || s.equals("imut_range") || s.equals("mut_range");
    }

    /** ^(?:imut|mut|indeterminate)_range(?:\(.*\))?$ (matches(), no line breaks inside the parentheses) */
    public static boolean isByValueRangeType(String s) {
        String rest;
        if (s.startsWith("imut_range")) {
            rest = s.substring(10);
        } else if (s.startsWith("mut_range")) {
            rest = s.substring(9);
        } else if (s.startsWith("indeterminate_range")) {
            rest = s.substring(19);
        } else {
            return false;
        }
        if (rest.isEmpty()) {
            return true;
        }
        if (rest.length() < 2 || rest.charAt(0) != '(' || rest.charAt(rest.length() - 1) != ')') {
            return false;
        }
        for (int i = 1; i < rest.length() - 1; i++) {
            if (isLineTerminator(rest.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** .*[us](8|16|32) matched against the whole string (no line breaks) */
    public static boolean endsWithSmallIntType(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (isLineTerminator(s.charAt(i))) {
                return false;
            }
        }
        return s.endsWith("u8") || s.endsWith("s8") || s.endsWith("u16") || s.endsWith("s16") || s.endsWith("u32")
                || s.endsWith("s32");
    }

    /** [us]\d+ matched against the whole string */
    public static boolean isSizedIntName(String s) {
        return s.length() >= 2 && (s.charAt(0) == 'u' || s.charAt(0) == 's') && isDigits(s.substring(1));
    }

    /** removes one leading imut_ or mut_ (replaceFirst("^(imut_|mut_)", "")) */
    public static String stripMutPrefix(String s) {
        if (s.startsWith("imut_")) {
            return s.substring(5);
        }
        if (s.startsWith("mut_")) {
            return s.substring(4);
        }
        return s;
    }

    /** removes every leading mut_ / imut_ / indeterminate_ (replaceFirst("^((mut|imut|indeterminate)_)+", "")) */
    public static String stripQualifierPrefixes(String s) {
        String r = s;
        boolean again = true;
        while (again) {
            again = false;
            if (r.startsWith("imut_")) {
                r = r.substring(5);
                again = true;
            } else if (r.startsWith("mut_")) {
                r = r.substring(4);
                again = true;
            } else if (r.startsWith("indeterminate_")) {
                r = r.substring(14);
                again = true;
            }
        }
        return r;
    }

    /**
     * Splits "name[N]" into {name, N}. mode 0: the name is one or more characters (^(.+)\[([0-9]+)\]$), mode 1: one or
     * more word characters, mode 2: zero or more characters (^(.*)\[(\d+)\]$). Returns null when s has another shape.
     */
    public static String[] splitArrayType(String s, int mode) {
        int n = s.length();
        if (n < 3 || s.charAt(n - 1) != ']') {
            return null;
        }
        int i = n - 2;
        while (i >= 0 && isDigit(s.charAt(i))) {
            i--;
        }
        if (i == n - 2 || i < 0 || s.charAt(i) != '[') {
            return null;
        }
        String name = s.substring(0, i);
        if (mode != 2 && name.isEmpty()) {
            return null;
        }
        if (mode == 1) {
            if (!isWord(name)) {
                return null;
            }
        } else {
            for (int k = 0; k < name.length(); k++) {
                if (isLineTerminator(name.charAt(k))) {
                    return null;
                }
            }
        }
        return new String[] {name, s.substring(i + 1, n - 1)};
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

    /** replaceAll("\\s+$", ""): the first whitespace run that ends at a $ position goes. */
    public static String stripTrailingSpace(String s) {
        int n = s.length();
        int p = 0;
        while (p < n) {
            if (!isSpace(s.charAt(p))) {
                p++;
                continue;
            }
            int q = p;
            while (q < n && isSpace(s.charAt(q))) {
                q++;
            }
            int e = q;
            while (e > p && !atDollar(s, e)) {
                e--;
            }
            if (e > p) {
                return s.substring(0, p) + s.substring(e);
            }
            p = q;
        }
        return s;
    }

    /** replaceAll("\\s+", " ") */
    public static String collapseSpace(String s) {
        StringBuilder sb = new StringBuilder();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (isSpace(c)) {
                while (i < n && isSpace(s.charAt(i))) {
                    i++;
                }
                sb.append(' ');
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** replaceAll("[^A-Za-z0-9_]", with) */
    public static String replaceNonWordChars(String s, String with) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isWordChar(c)) {
                sb.append(c);
            } else {
                sb.append(with);
            }
        }
        return sb.toString();
    }

    /** replaceFirst("\\.caspien$", "") for a suffix (the $ may sit before a final line terminator) */
    public static String stripSuffixAtEnd(String s, String suffix) {
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
    public static String stripCommentLead(String s) {
        if (s.isEmpty() || (s.charAt(0) != '#' && s.charAt(0) != ';')) {
            return s;
        }
        int i = 1;
        while (i < s.length() && isSpace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    /** replaceFirst("^0+(?=.)", ""): leading zeros go, but not the last character */
    public static String stripLeadingZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0' && !isLineTerminator(s.charAt(i + 1))) {
            i++;
        }
        return s.substring(i);
    }

    /** replaceFirst(":\\d+\"$", "\"") */
    public static String stripLineNumberBeforeQuote(String s) {
        return firstLineNumberMatch(s);
    }

    private static String firstLineNumberMatch(String s) {
        int n = s.length();
        for (int c = 0; c < n; c++) {
            if (s.charAt(c) != ':') {
                continue;
            }
            int i = c + 1;
            while (i < n && isDigit(s.charAt(i))) {
                i++;
            }
            if (i == c + 1 || i >= n || s.charAt(i) != '"') {
                continue;
            }
            if (atDollar(s, i + 1)) {
                return s.substring(0, c) + "\"" + s.substring(i + 1);
            }
        }
        return s;
    }

    /**
     * ^(\s*)unsafe\b[^{]*\{\s*(//.*)?$ against a whole line: returns {leading whitespace, the trailing comment
     * starting at the slashes or null}, or null when the line has another shape.
     */
    public static String[] unsafeOpenParts(String line) {
        int n = line.length();
        int i = 0;
        while (i < n && isSpace(line.charAt(i))) {
            i++;
        }
        int indentEnd = i;
        if (!line.startsWith("unsafe", i)) {
            return null;
        }
        i += 6;
        if (i < n && isWordChar(line.charAt(i))) {
            return null;
        }
        while (i < n && line.charAt(i) != '{') {
            i++;
        }
        if (i >= n) {
            return null;
        }
        i++;
        while (i < n && isSpace(line.charAt(i))) {
            i++;
        }
        if (i == n) {
            return new String[] {line.substring(0, indentEnd), null};
        }
        if (!line.startsWith("//", i)) {
            return null;
        }
        for (int k = i + 2; k < n; k++) {
            if (isLineTerminator(line.charAt(k))) {
                return null;
            }
        }
        return new String[] {line.substring(0, indentEnd), line.substring(i)};
    }

    /** ^\s*\}\s*(//.*)?$ against a whole line */
    public static boolean isCloseLine(String line) {
        int n = line.length();
        int i = 0;
        while (i < n && isSpace(line.charAt(i))) {
            i++;
        }
        if (i >= n || line.charAt(i) != '}') {
            return false;
        }
        i++;
        while (i < n && isSpace(line.charAt(i))) {
            i++;
        }
        if (i == n) {
            return true;
        }
        if (!line.startsWith("//", i)) {
            return false;
        }
        for (int k = i + 2; k < n; k++) {
            if (isLineTerminator(line.charAt(k))) {
                return false;
            }
        }
        return true;
    }

    /** \bunsafe\s+unaudited\s*\{ : returns {start, end} of the first match, or null */
    public static int[] findUnsafeUnaudited(String s) {
        int n = s.length();
        int from = 0;
        while (true) {
            int p = s.indexOf("unsafe", from);
            if (p < 0) {
                return null;
            }
            from = p + 1;
            if (p > 0 && isWordChar(s.charAt(p - 1))) {
                continue;
            }
            int i = p + 6;
            int ws = i;
            while (i < n && isSpace(s.charAt(i))) {
                i++;
            }
            if (i == ws || !s.startsWith("unaudited", i)) {
                continue;
            }
            i += 9;
            while (i < n && isSpace(s.charAt(i))) {
                i++;
            }
            if (i < n && s.charAt(i) == '{') {
                return new int[] {p, i + 1};
            }
        }
    }

    /**
     * True when text holds name as a whole word: not preceded by a word character (or, with dotBlocks, a dot) and
     * not followed by one. With suffixChars, one optional character from it may follow name first, e.g. the
     * pattern (?<![A-Za-z0-9_])r12[dwb]?(?![A-Za-z0-9_]).
     */
    public static boolean containsWord(String text, String name, String suffixChars, boolean dotBlocks) {
        int n = text.length();
        int from = 0;
        while (true) {
            int p = text.indexOf(name, from);
            if (p < 0) {
                return false;
            }
            from = p + 1;
            if (p > 0) {
                char before = text.charAt(p - 1);
                if (isWordChar(before) || (dotBlocks && before == '.')) {
                    continue;
                }
            }
            int q = p + name.length();
            if (q < n && suffixChars.indexOf(text.charAt(q)) >= 0) {
                q++;
            }
            if (q >= n || !isWordChar(text.charAt(q))) {
                return true;
            }
        }
    }

    /** containsWord with any of several names (an alternation) */
    public static boolean containsAnyWord(String text, String[] names, String suffixChars) {
        for (int i = 0; i < names.length; i++) {
            if (containsWord(text, names[i], suffixChars, false)) {
                return true;
            }
        }
        return false;
    }

    /** s.split(String.valueOf(sep), limit) for a single non-special character */
    public static String[] split(String s, char sep, int limit) {
        return splitBy(s, 0, sep, limit);
    }

    /** s.split("\\s+", limit) */
    public static String[] splitSpace(String s, int limit) {
        return splitBy(s, SEP_WHITESPACE, ' ', limit);
    }

    /** s.split("[^A-Za-z0-9_]+") */
    public static String[] splitNonWord(String s) {
        return splitBy(s, SEP_NON_WORD, ' ', 0);
    }

    /** s.split("\\R") */
    public static String[] splitLineBreaks(String s) {
        return splitBy(s, SEP_LINE_BREAK, ' ', 0);
    }

    /** s.split("\r?\n", limit) */
    public static String[] splitNewlines(String s, int limit) {
        return splitBy(s, SEP_CRLF_OR_LF, ' ', limit);
    }

    // finds the next separator at or after from; returns its end (exclusive) and stores its start in startOut[0], or -1
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
            } else if (kind == SEP_NON_WORD) {
                if (!isWordChar(c)) {
                    int j = i;
                    while (j < n && !isWordChar(s.charAt(j))) {
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
            } else {
                if (c == '\n') {
                    startOut[0] = i;
                    return i + 1;
                }
                if (c == '\r' && i + 1 < n && s.charAt(i + 1) == '\n') {
                    startOut[0] = i;
                    return i + 2;
                }
            }
        }
        return -1;
    }

    // the String.split algorithm for separators that are never empty
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
