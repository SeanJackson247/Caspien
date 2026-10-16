package caspien.bytecode;

import java.util.ArrayList;
import java.util.List;

/**
 * Small hand-written string helpers used where the compiler used to call java.util.regex.
 * Each method states the pattern it replaces; tests/text_helpers_check.sh compares them with
 * the real regex on random input.
 */
public final class Text {
    private Text() { }

    // separator kinds for split()
    private static final int SEP_WHITESPACE = 1;      // \s+
    private static final int SEP_NON_WORD = 2;        // [^A-Za-z0-9_]+
    private static final int SEP_LINE_BREAK = 3;      // \R
    private static final int SEP_CRLF_OR_LF = 4;      // \r?\n

    public static boolean isDigit(char c) { return false; }

    public static boolean isWordChar(char c) { return false; }

    /** \s */
    public static boolean isSpace(char c) { return false; }

    /** the characters that Java treats as line terminators */
    private static boolean isLineTerminator(char c) { return false; }

    /** [0-9]+ or \d+ */
    public static boolean isDigits(String s) { return false; }

    /** -?\d+ */
    public static boolean isSignedDigits(String s) { return false; }

    /** -?\d+\.\d+([eE][+-]?\d+)? */
    public static boolean isDecimalNumber(String s) { return false; }

    /** [A-Za-z0-9_]+ */
    public static boolean isWord(String s) { return false; }

    /** [A-Za-z_][A-Za-z_0-9.]* */
    public static boolean isDottedName(String s) { return false; }

    /** prefix followed by exactly one character from chars, e.g. "%t" + [0-3] */
    public static boolean isPrefixedChar(String s, String prefix, String chars) { return false; }

    /** prefix followed by one or more digits, e.g. "ARG[0-9]+" or "r\d+" */
    public static boolean isPrefixedDigits(String s, String prefix) { return false; }

    /** r(8|9|1[0-5]) */
    public static boolean isHighGpr(String s) { return false; }

    /** \d+\.(start|end) */
    public static boolean isDigitsDotStartOrEnd(String s) { return false; }

    private static boolean isScalarName(String s) { return false; }

    /** (mut|imut)_scalar, or with allowIndeterminate (mut|imut|indeterminate)_scalar */
    public static boolean isQualifiedScalar(String s, boolean allowIndeterminate) { return false; }

    /** (imut_|mut_)?range */
    public static boolean isRangeType(String s) { return false; }

    /** ^(?:imut|mut|indeterminate)_range(?:\(.*\))?$ (matches(), no line breaks inside the parentheses) */
    public static boolean isByValueRangeType(String s) { return false; }

    /** .*[us](8|16|32) matched against the whole string (no line breaks) */
    public static boolean endsWithSmallIntType(String s) { return false; }

    /** [us]\d+ matched against the whole string */
    public static boolean isSizedIntName(String s) { return false; }

    /** removes one leading imut_ or mut_ (replaceFirst("^(imut_|mut_)", "")) */
    public static String stripMutPrefix(String s) { return null; }

    /** removes every leading mut_ / imut_ / indeterminate_ (replaceFirst("^((mut|imut|indeterminate)_)+", "")) */
    public static String stripQualifierPrefixes(String s) { return null; }

    /**
     * Splits "name[N]" into {name, N}. mode 0: the name is one or more characters (^(.+)\[([0-9]+)\]$), mode 1: one or
     * more word characters, mode 2: zero or more characters (^(.*)\[(\d+)\]$). Returns null when s has another shape.
     */
    public static String[] splitArrayType(String s, int mode) { return null; }

    /** Java's $ (no MULTILINE): the end of the input, or just before a line terminator that ends the input. */
    private static boolean atDollar(String s, int pos) { return false; }

    /** replaceAll("\\s+$", ""): the first whitespace run that ends at a $ position goes. */
    public static String stripTrailingSpace(String s) { return null; }

    /** replaceAll("\\s+", " ") */
    public static String collapseSpace(String s) { return null; }

    /** replaceAll("[^A-Za-z0-9_]", with) */
    public static String replaceNonWordChars(String s, String with) { return null; }

    /** replaceFirst("\\.caspien$", "") for a suffix (the $ may sit before a final line terminator) */
    public static String stripSuffixAtEnd(String s, String suffix) { return null; }

    /** replaceFirst("^[#;]\\s*", "") */
    public static String stripCommentLead(String s) { return null; }

    /** replaceFirst("^0+(?=.)", ""): leading zeros go, but not the last character */
    public static String stripLeadingZeros(String s) { return null; }

    /** replaceFirst(":\\d+\"$", "\"") */
    public static String stripLineNumberBeforeQuote(String s) { return null; }

    private static String firstLineNumberMatch(String s) { return null; }

    /**
     * ^(\s*)unsafe\b[^{]*\{\s*(//.*)?$ against a whole line: returns {leading whitespace, the trailing comment
     * starting at the slashes or null}, or null when the line has another shape.
     */
    public static String[] unsafeOpenParts(String line) { return null; }

    /** ^\s*\}\s*(//.*)?$ against a whole line */
    public static boolean isCloseLine(String line) { return false; }

    /** \bunsafe\s+unaudited\s*\{ : returns {start, end} of the first match, or null */
    public static int[] findUnsafeUnaudited(String s) { return null; }

    /**
     * True when text holds name as a whole word: not preceded by a word character (or, with dotBlocks, a dot) and
     * not followed by one. With suffixChars, one optional character from it may follow name first, e.g. the
     * pattern (?<![A-Za-z0-9_])r12[dwb]?(?![A-Za-z0-9_]).
     */
    public static boolean containsWord(String text, String name, String suffixChars, boolean dotBlocks) { return false; }

    /** containsWord with any of several names (an alternation) */
    public static boolean containsAnyWord(String text, String[] names, String suffixChars) { return false; }

    /** s.split(String.valueOf(sep), limit) for a single non-special character */
    public static String[] split(String s, char sep, int limit) { return null; }

    /** s.split("\\s+", limit) */
    public static String[] splitSpace(String s, int limit) { return null; }

    /** s.split("[^A-Za-z0-9_]+") */
    public static String[] splitNonWord(String s) { return null; }

    /** s.split("\\R") */
    public static String[] splitLineBreaks(String s) { return null; }

    /** s.split("\r?\n", limit) */
    public static String[] splitNewlines(String s, int limit) { return null; }

    // finds the next separator at or after from; returns its end (exclusive) and stores its start in startOut[0], or -1
    private static int nextSeparator(String s, int kind, char sep, int from, int[] startOut) { return 0; }

    // the String.split algorithm for separators that are never empty
    private static String[] splitBy(String s, int kind, char sep, int limit) { return null; }
}
