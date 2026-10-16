import java.util.ArrayList;
import java.util.List;

/** The few string helpers Compiler and CompilerCache need (the stages have caspien.bytecode.Text; the root files are compiled alone, so this is a trimmed copy of its split/strip methods). */
final class CompilerText {
    private CompilerText() { }

    private static final int SEP_WHITESPACE = 1;
    private static final int SEP_LINE_BREAK = 3;

    /** \s */
    static boolean isSpace(char c) { return false; }

    /** the characters that Java treats as line terminators */
    private static boolean isLineTerminator(char c) { return false; }

    /** Java's $ (no MULTILINE): the end of the input, or just before a line terminator that ends the input. */
    private static boolean atDollar(String s, int pos) { return false; }

    /** replaceFirst("\\.caspien$", "") for a suffix (the $ may sit before a final line terminator) */
    static String stripSuffixAtEnd(String s, String suffix) { return null; }

    /** replaceFirst("^[#;]\\s*", "") */
    static String stripCommentLead(String s) { return null; }

    /** s.split(String.valueOf(sep), limit) for a single non-special character */
    static String[] split(String s, char sep, int limit) { return null; }

    /** s.split("\\s+", limit) */
    static String[] splitSpace(String s, int limit) { return null; }

    /** s.split("\\R") */
    static String[] splitLineBreaks(String s) { return null; }

    private static int nextSeparator(String s, int kind, char sep, int from, int[] startOut) { return 0; }

    private static String[] splitBy(String s, int kind, char sep, int limit) { return null; }
}
