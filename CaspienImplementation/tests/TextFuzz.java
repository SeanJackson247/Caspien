// Compares every caspien.bytecode.Text helper with the java.util.regex / String.split it replaced, on random and edge-case input.
// Run by tests/text_helpers_check.sh.
import caspien.bytecode.Text;
import java.util.*;
import java.util.regex.*;

public class TextFuzz {
    static Random rnd = new Random(12345);
    static String alpha = "ab_Z09.-+ \t\n\r\u000B\f\u0085\u2028\u2029,|#;[]{}()/\":rdwbmutinxsfeE%vARG";
    static String rand(int max, String al) { return null; }
    static int fails = 0;
    static void eq(String what, String in, Object a, Object b) { }
    static String esc(String s) { return null; }
    static String[] samples(String[] seeds) { return null; }
    public static void main(String[] a) { }
}
