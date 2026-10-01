// String manipulation benchmark (Java, byte[] buffers).
public class Strings {
    static int x = 12345;
    static int next() { x = x * 1664525 + 1013904223; return x; }
    static int hash(byte[] b, int n) { int h = 7; for (int i = 0; i < n; i++) h = h * 31 + (b[i] & 0xff); return h; }
    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 4000000;
        byte[] text = new byte[n];
        for (int i = 0; i < n; i++) { int r = (next() >>> 16) % 27; text[i] = (byte) (r == 26 ? ' ' : 'a' + r); }
        long words = 0, longest = 0, cur = 0;
        for (int i = 0; i < n; i++) {
            if (text[i] == ' ') { if (cur > 0) { words++; if (cur > longest) longest = cur; cur = 0; } } else cur++;
        }
        if (cur > 0) { words++; if (cur > longest) longest = cur; }
        long abc = 0;
        for (int i = 0; i + 2 < n; i++) if (text[i] == 'a' && text[i + 1] == 'b' && text[i + 2] == 'c') abc++;
        byte[] rev = new byte[n];
        for (int i = 0; i < n; i++) rev[i] = text[n - 1 - i];
        int hrev = hash(rev, n);
        int es = 0;
        for (int i = 0; i < n; i++) if (text[i] == 'e') es++;
        int m = n + es;
        byte[] rep = new byte[m];
        int k = 0;
        for (int i = 0; i < n; i++) { if (text[i] == 'e') { rep[k++] = '3'; rep[k++] = '3'; } else rep[k++] = text[i]; }
        int hrep = hash(rep, m);
        byte[] up = new byte[n];
        for (int i = 0; i < n; i++) up[i] = text[i] == ' ' ? (byte) ' ' : (byte) (text[i] - 32);
        int hup = hash(up, n);
        System.out.println(words + " " + longest + " " + abc + " " + Integer.toUnsignedString(hrev) + " " + m + " " + Integer.toUnsignedString(hrep) + " " + Integer.toUnsignedString(hup));
    }
}
