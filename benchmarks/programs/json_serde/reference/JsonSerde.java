// JSON serialise + parse benchmark (Java). Same hand-written serialiser / iterative parser / checksum as json_serde.c.
// Records are parallel long[] arrays (Java has no value structs); names and tags go into byte[] pools.
public class JsonSerde {
    static int x = 12345;
    static int next() { x = x * 1664525 + 1013904223; return x; }
    static int wnum(byte[] t, int p, long v) {
        int d = 1; for (long u = v; u >= 10; u /= 10) d++;
        for (int k = d - 1; k >= 0; k--) { t[p + k] = (byte) ('0' + v % 10); v /= 10; }
        return p + d;
    }
    static int wlit(byte[] t, int p, String s) { for (int i = 0; i < s.length(); i++) t[p++] = (byte) s.charAt(i); return p; }
    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 4000000;
        byte[] text = new byte[n * 80 + 16];
        int p = 0;
        text[p++] = '[';
        for (int i = 0; i < n; i++) {
            if (i > 0) text[p++] = ',';
            p = wlit(text, p, "{\"id\":"); p = wnum(text, p, i);
            p = wlit(text, p, ",\"name\":\"");
            int nl = 3 + (next() >>> 16) % 8;
            for (int k = 0; k < nl; k++) text[p++] = (byte) ('a' + (next() >>> 16) % 26);
            p = wlit(text, p, "\",\"score\":");
            long cents = (next() >>> 8) % 1000000;
            p = wnum(text, p, cents / 100); text[p++] = '.'; text[p++] = (byte) ('0' + cents % 100 / 10); text[p++] = (byte) ('0' + cents % 10);
            p = wlit(text, p, ",\"tags\":[");
            int tc = (next() >>> 16) % 5;
            for (int k = 0; k < tc; k++) { if (k > 0) text[p++] = ','; p = wnum(text, p, (next() >>> 16) % 100); }
            p = wlit(text, p, "]}");
        }
        text[p++] = ']';
        int tlen = p;
        long[] rid = new long[n + 1], rnoff = new long[n + 1], rnlen = new long[n + 1], rscore = new long[n + 1], rtoff = new long[n + 1], rtcnt = new long[n + 1];
        byte[] names = new byte[n * 10 + 8];
        byte[] tags = new byte[n * 4 + 8];
        int count = 0, noff = 0, toff = 0, q = 1;
        for (;;) {
            int c = text[q];
            if (c == ']') break;
            if (c == ',') { q++; continue; }
            q++;
            long id = 0, nameOff = 0, nameLen = 0, score = 0, tagOff = 0, tagCnt = 0;
            for (;;) {
                q++;
                int k0 = text[q];
                while (text[q] != '"') q++;
                q += 2;
                if (k0 == 'i') {
                    long v = 0; while (text[q] >= '0' && text[q] <= '9') { v = v * 10 + (text[q] - '0'); q++; }
                    id = v;
                } else if (k0 == 'n') {
                    q++;
                    nameOff = noff;
                    while (text[q] != '"') { names[noff++] = text[q]; q++; }
                    nameLen = noff - nameOff;
                    q++;
                } else if (k0 == 's') {
                    long v = 0; while (text[q] >= '0' && text[q] <= '9') { v = v * 10 + (text[q] - '0'); q++; }
                    q++;
                    v = v * 100 + (text[q] - '0') * 10 + (text[q + 1] - '0'); q += 2;
                    score = v;
                } else {
                    q++;
                    tagOff = toff;
                    while (text[q] != ']') {
                        if (text[q] == ',') q++;
                        long v = 0; while (text[q] >= '0' && text[q] <= '9') { v = v * 10 + (text[q] - '0'); q++; }
                        tags[toff++] = (byte) v;
                    }
                    tagCnt = toff - tagOff;
                    q++;
                }
                if (text[q] == ',') q++; else { q++; break; }
            }
            rid[count] = id; rnoff[count] = nameOff; rnlen[count] = nameLen; rscore[count] = score; rtoff[count] = tagOff; rtcnt[count] = tagCnt;
            count++;
        }
        int h = 7;
        for (int i = 0; i < count; i++) {
            h = h * 31 + (int) rid[i];
            h = h * 31 + (int) rscore[i];
            h = h * 31 + (int) rnlen[i];
            for (long k = 0; k < rnlen[i]; k++) h = h * 31 + (names[(int) (rnoff[i] + k)] & 0xff);
            h = h * 31 + (int) rtcnt[i];
            for (long k = 0; k < rtcnt[i]; k++) h = h * 31 + (tags[(int) (rtoff[i] + k)] & 0xff);
        }
        System.out.println(count + " " + tlen + " " + Integer.toUnsignedString(h));
    }
}
