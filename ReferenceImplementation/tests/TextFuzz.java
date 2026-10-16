// Compares every caspien.bytecode.Text helper with the java.util.regex / String.split it replaced, on random and edge-case input.
// Run by tests/text_helpers_check.sh.
import caspien.bytecode.Text;
import java.util.*;
import java.util.regex.*;

public class TextFuzz {
    static Random rnd = new Random(12345);
    static String alpha = "ab_Z09.-+ \t\n\r\u000B\f\u0085\u2028\u2029,|#;[]{}()/\":rdwbmutinxsfeE%vARG";
    static String rand(int max, String al) {
        int n = rnd.nextInt(max + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(al.charAt(rnd.nextInt(al.length())));
        return sb.toString();
    }
    static int fails = 0;
    static void eq(String what, String in, Object a, Object b) {
        String as = a instanceof Object[] ? Arrays.toString((Object[]) a) : String.valueOf(a);
        String bs = b instanceof Object[] ? Arrays.toString((Object[]) b) : String.valueOf(b);
        if (!as.equals(bs)) {
            fails++;
            if (fails < 40) System.out.println("MISMATCH " + what + " input=" + esc(in) + " regex=" + esc(bs) + " mine=" + esc(as));
        }
    }
    static String esc(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (char c : s.toCharArray()) { if (c < 32 || c > 126) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
        return sb.append("'").toString();
    }
    static String[] samples(String[] seeds) {
        List<String> l = new ArrayList<>();
        for (String s : seeds) l.add(s);
        for (int i = 0; i < 3000; i++) l.add(rand(12, alpha));
        for (int i = 0; i < 3000; i++) {
            String s = seeds[rnd.nextInt(seeds.length)];
            // mutate a seed
            StringBuilder sb = new StringBuilder(s);
            int k = rnd.nextInt(3);
            for (int j = 0; j < k && sb.length() > 0; j++) {
                int p = rnd.nextInt(sb.length() + 1);
                int t = rnd.nextInt(3);
                if (t == 0 && p < sb.length()) sb.deleteCharAt(p);
                else sb.insert(p, alpha.charAt(rnd.nextInt(alpha.length())));
            }
            l.add(sb.toString());
        }
        return l.toArray(new String[0]);
    }
    public static void main(String[] a) {
        String[] seeds = {"", "0", "12", "-12", "1.5", "-1.5e+3", "1.e3", "1.5E", "r12", "r8", "r15", "r16", "r1", "r", "12.start", "12.end", "x.start", "ARG12", "ARG", "%t2", "%v7", "%t", "mut_u8", "imut_u64", "indeterminate_f32", "mut_range", "imut_range(3)", "mut_range()", "imut_range(a\nb)", "indeterminate_range(x)", "range", "foo[3]", "[3]", "a[b]", "x[12]y", "a b  c", " a", "a ", "a\tb\nc", "a,b,,c,,", ",", ",,a", "x.caspien", "x.caspien\n", "x.caspien\r\n", "# warn", ";x  y", "  # q", "000", "0012", "a:12\"", "a:12\"\n", "a:12\"\r\n", "a:\"", "x:1\":2\"", "unsafe{", "  unsafe extern{ // c", "unsafeX{", "unsafe", "    }", "} // end", "}x", "unsafe unaudited{", "a unsafe  unaudited {b", "xunsafe unaudited{", "unsafe unauditedx{", "a.b_c", "_x.y", "9x", "mut_u8_x", "imut_mut_u8", "foo u32", "u8", "s16", "u", "u12", "s", "a\r\nb\nc\rd\u2028e", "a\r\n\r\nb", "\r\n"};
        String[] S = samples(seeds);
        for (String s : S) {
            eq("isDigits", s, Text.isDigits(s), s.matches("[0-9]+"));
            eq("isDigits\\d", s, Text.isDigits(s), s.matches("\\d+"));
            eq("isSigned", s, Text.isSignedDigits(s), s.matches("-?\\d+"));
            eq("isDecimal", s, Text.isDecimalNumber(s), s.matches("-?\\d+\\.\\d+([eE][+-]?\\d+)?"));
            eq("isWord", s, Text.isWord(s), s.matches("[A-Za-z0-9_]+"));
            eq("isDotted", s, Text.isDottedName(s), s.matches("[A-Za-z_][A-Za-z_0-9.]*"));
            eq("%t[0-3]", s, Text.isPrefixedChar(s, "%t", "0123"), s.matches("%t[0-3]"));
            eq("%v[0-5]", s, Text.isPrefixedChar(s, "%v", "012345"), s.matches("%v[0-5]"));
            eq("%v[0-9]", s, Text.isPrefixedChar(s, "%v", "0123456789"), s.matches("%v[0-9]"));
            eq("[1248]", s, Text.isPrefixedChar(s, "", "1248"), s.matches("[1248]"));
            eq("ARG", s, Text.isPrefixedDigits(s, "ARG"), s.matches("ARG[0-9]+"));
            eq("r\\d+", s, Text.isPrefixedDigits(s, "r"), s.matches("r\\d+"));
            eq("highGpr", s, Text.isHighGpr(s), s.matches("r(8|9|1[0-5])"));
            eq("dotStartEnd", s, Text.isDigitsDotStartOrEnd(s), s.matches("\\d+\\.(start|end)"));
            eq("scalar2", s, Text.isQualifiedScalar(s, false), s.matches("(mut|imut)_(u8|u16|u32|u64|s8|s16|s32|s64|f32|f64|bool|char)"));
            eq("scalar3", s, Text.isQualifiedScalar(s, true), s.matches("(mut|imut|indeterminate)_(u8|u16|u32|u64|s8|s16|s32|s64|f32|f64|bool|char)"));
            eq("rangeType", s, Text.isRangeType(s), s.matches("(imut_|mut_)?range"));
            eq("byValueRange", s, Text.isByValueRangeType(s), s.matches("^(?:imut|mut|indeterminate)_range(?:\\(.*\\))?$"));
            eq("smallInt", s, Text.endsWithSmallIntType(s), s.matches(".*[us](8|16|32)"));
            eq("sizedInt", s, Text.isSizedIntName(s), s.matches("[us](\\d+)"));
            eq("stripMut", s, Text.stripMutPrefix(s), s.replaceFirst("^(imut_|mut_)", ""));
            eq("stripQual", s, Text.stripQualifierPrefixes(s), s.replaceFirst("^((mut|imut|indeterminate)_)+", ""));
            String[] m0 = Text.splitArrayType(s, 0); Matcher mm = Pattern.compile("^(.+)\\[([0-9]+)\\]$").matcher(s);
            eq("arr0", s, m0 == null ? "null" : m0[0] + "|" + m0[1], mm.matches() ? mm.group(1) + "|" + mm.group(2) : "null");
            String[] m1 = Text.splitArrayType(s, 1); mm = Pattern.compile("^([A-Za-z0-9_]+)\\[([0-9]+)\\]$").matcher(s);
            eq("arr1", s, m1 == null ? "null" : m1[0] + "|" + m1[1], mm.matches() ? mm.group(1) + "|" + mm.group(2) : "null");
            String[] m2 = Text.splitArrayType(s, 2); mm = Pattern.compile("(.*)\\[(\\d+)\\]$").matcher(s);
            eq("arr2", s, m2 == null ? "null" : m2[0] + "|" + m2[1], mm.matches() ? mm.group(1) + "|" + mm.group(2) : "null");
            eq("stripTrailing", s, Text.stripTrailingSpace(s), s.replaceAll("\\s+$", ""));
            eq("collapse", s, Text.collapseSpace(s), s.replaceAll("\\s+", " "));
            eq("nonWordRepl", s, Text.replaceNonWordChars(s, "_"), s.replaceAll("[^A-Za-z0-9_]", "_"));
            eq("stripCaspien", s, Text.stripSuffixAtEnd(s, ".caspien"), s.replaceFirst("\\.caspien$", ""));
            eq("commentLead", s, Text.stripCommentLead(s), s.replaceFirst("^[#;]\\s*", ""));
            eq("leadZeros", s, Text.stripLeadingZeros(s), s.replaceFirst("^0+(?=.)", ""));
            eq("lineNo", s, Text.stripLineNumberBeforeQuote(s), s.replaceFirst(":\\d+\"$", "\""));
            String[] ind0 = Text.unsafeOpenParts(s); String ind = ind0 == null ? null : ind0[0] + "~" + ind0[1]; Matcher om = Pattern.compile("^(\\s*)unsafe\\b[^{]*\\{\\s*(//.*)?$").matcher(s);
            eq("openLine", s, ind == null ? "null" : ind, om.matches() ? om.group(1) + "~" + om.group(2) : "null");
            eq("closeLine", s, Text.isCloseLine(s), Pattern.compile("^\\s*\\}\\s*(//.*)?$").matcher(s).matches());
            int[] ua = Text.findUnsafeUnaudited(s); Matcher um = Pattern.compile("\\bunsafe\\s+unaudited\\s*\\{").matcher(s);
            eq("unaudited", s, ua == null ? "null" : ua[0] + "," + ua[1], um.find() ? um.start() + "," + um.end() : "null");
            for (int lim : new int[] {-1, 0, 1, 2, 3}) {
                eq("splitComma" + lim, s, Text.split(s, ',', lim), s.split(",", lim));
                eq("splitSpaceCh" + lim, s, Text.split(s, ' ', lim), s.split(" ", lim));
                eq("splitTab" + lim, s, Text.split(s, '\t', lim), s.split("\t", lim));
                eq("splitNl" + lim, s, Text.split(s, '\n', lim), s.split("\n", lim));
                eq("splitBar" + lim, s, Text.split(s, '|', lim), s.split("\\|", lim));
                eq("splitWs" + lim, s, Text.splitSpace(s, lim), s.split("\\s+", lim));
                eq("splitCrLf" + lim, s, Text.splitNewlines(s, lim), s.split("\r?\n", lim));
            }
            eq("splitNonWord", s, Text.splitNonWord(s), s.split("[^A-Za-z0-9_]+"));
            eq("splitR", s, Text.splitLineBreaks(s), s.split("\\R"));
        }
        // word search
        String[] names = {"r12", "rbx", "ebx", "bx", "bl", "bh", "rdi", "edi", "di", "dil", "xmm3", "p", "r9"};
        String wal = "r12bdwx9_ .xmp+(),ebil3";
        for (int i = 0; i < 40000; i++) {
            String t = rand(14, wal);
            String nm = names[rnd.nextInt(names.length)];
            eq("word[dwb]", t + "~" + nm, Text.containsWord(t, nm, "dwb", false), Pattern.compile("(?<![A-Za-z0-9_])" + nm + "[dwb]?(?![A-Za-z0-9_])").matcher(t).find());
            eq("wordPlain", t + "~" + nm, Text.containsWord(t, nm, "", false), Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(nm) + "(?![0-9A-Za-z_])").matcher(t).find());
            eq("wordDot", t + "~" + nm, Text.containsWord(t, nm, "", true), Pattern.compile("(?<![A-Za-z0-9_.])" + Pattern.quote(nm) + "(?![A-Za-z0-9_])").matcher(t).find());
            eq("alt-rbx", t, Text.containsAnyWord(t, new String[] {"rbx", "ebx", "bx", "bl", "bh"}, ""), Pattern.compile("(?<![A-Za-z0-9_])(?:rbx|ebx|bx|bl|bh)(?![A-Za-z0-9_])").matcher(t).find());
            eq("alt-rdi", t, Text.containsAnyWord(t, new String[] {"rdi", "edi", "di", "dil"}, ""), Pattern.compile("(?<![A-Za-z0-9_])(?:rdi|edi|di|dil)(?![A-Za-z0-9_])").matcher(t).find());
        }
        System.out.println(fails == 0 ? "ALL OK" : fails + " mismatches");
        System.exit(fails == 0 ? 0 : 1);
    }
}
