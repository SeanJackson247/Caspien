// FASTA benchmark (Java, byte[] buffer). Same algorithm and output as fasta.c.
public class Fasta {
    static final String ALU = "GGCCGGGCGCGGTGGCTCACGCCTGTAATCCCAGCACTTTGGGAGGCCGAGGCGGGCGGATCACCTGAGGTCAGGAGTTCGAGACCAGCCTGGCCAACATGGTGAAACCCCGTCTCTACTAAAAATACAAAAATTAGCCGGGCGTGGTGGCGCGCGCCTGTAATCCCAGCTACTCGGGAGGCTGAGGCAGGAGAATCGCTTGAACCCGGGAGGCGGAGGTTGCAGTGAGCCGAGATCGCGCCACTGCACTCCAGCCTGGGCGACAGAGCGAGACTCCGTCTCAAAAA";
    static final String CHARS = "ACGTBDHKMNRSVWYACGT";
    static final int[] THR = {37792,54588,71384,109176,111975,114774,117574,120373,123172,125972,128771,131570,134370,137169,139968,42404,70117,97767,139968};
    static final String[] HDR = {">ONE Homo sapiens alu\n", ">TWO IUB ambiguity codes\n", ">THREE Homo sapiens frequency\n"};
    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 40000000;
        int[] cnt = {0, 0, 0};
        cnt[0] = (int) ((long) n * 2 / 10);
        cnt[1] = (int) ((long) n * 3 / 10);
        cnt[2] = n - cnt[0] - cnt[1];
        int[] off = {0, 0, 15};
        int last = 42;
        byte[] buf = new byte[n + n / 60 + 1024];
        byte[] alu = ALU.getBytes();
        byte[] chars = CHARS.getBytes();
        int pos = 0, ai = 0;
        long a = 0, c = 0, g = 0, t = 0, other = 0;
        for (int s = 0; s < 3; s++) {
            byte[] hd = HDR[s].getBytes();
            System.arraycopy(hd, 0, buf, pos, hd.length);
            pos += hd.length;
            int col = 0;
            for (int i = 0; i < cnt[s]; i++) {
                byte ch;
                if (s == 0) {
                    ch = alu[ai];
                    if (++ai == 287) ai = 0;
                } else {
                    last = (last * 3877 + 29573) % 139968;
                    int j = off[s];
                    while (last >= THR[j]) j++;
                    ch = chars[j];
                }
                buf[pos++] = ch;
                if (ch == 'A') a++; else if (ch == 'C') c++; else if (ch == 'G') g++; else if (ch == 'T') t++; else other++;
                if (++col == 60) { buf[pos++] = '\n'; col = 0; }
            }
            if (col > 0) buf[pos++] = '\n';
        }
        int h = 7;
        for (int i = 0; i < pos; i++) h = h * 31 + (buf[i] & 0xff);
        System.out.println(pos + " " + Integer.toUnsignedString(h) + " " + a + " " + c + " " + g + " " + t + " " + other);
    }
}
