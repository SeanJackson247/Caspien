import java.math.BigDecimal;

/** Oracle for tests/jdk_double_test.caspien: the same vectors in the same order, printed as "kind bits toString plain" via the real JDK. */
public class JdkDoubleOracle {
    static StringBuilder out = new StringBuilder();

    static String plain(String s) {
        if (s.startsWith("N") || s.startsWith("I") || s.startsWith("-I")) return s;
        String p = new BigDecimal(s).toPlainString();
        if (p.indexOf('.') < 0) p += ".0";
        return p;
    }

    static void emitD(long bits) {
        String s = Double.toString(Double.longBitsToDouble(bits));
        out.append("D ").append(Long.toUnsignedString(bits)).append(' ').append(s).append(' ').append(plain(s)).append('\n');
        flush();
    }

    static void emitF(int bits) {
        String s = Float.toString(Float.intBitsToFloat(bits));
        out.append("F ").append(Integer.toUnsignedString(bits)).append(' ').append(s).append(' ').append(plain(s)).append('\n');
        flush();
    }

    static void flush() {
        if (out.length() > 1 << 16) { System.out.print(out); out.setLength(0); }
    }

    static long decD(long dd, long k) {
        double pd = 1.0;
        for (long j = 0; j < k; j++) pd = pd * 10.0;
        return Double.doubleToRawLongBits((double) dd / pd);
    }

    static int decF(long dd, long k) {
        float pf = 1.0f;
        for (long j = 0; j < k; j++) pf = pf * 10.0f;
        return Float.floatToRawIntBits((float) (dd % 16777215 + 1) / pf);
    }

    public static void main(String[] a) {
        long count = a.length > 0 ? Long.parseLong(a[0]) : 800;
        long st = 12345;
        emitD(0); emitD(0x8000000000000000L); emitD(1); emitD(0x000fffffffffffffL); emitD(0x0010000000000000L);
        emitD(0x7fefffffffffffffL); emitD(0x7ff0000000000000L); emitD(0xfff0000000000000L); emitD(0x7ff8000000000000L); emitD(0xffefffffffffffffL);
        emitF(0); emitF(0x80000000); emitF(1); emitF(0x007fffff); emitF(0x00800000); emitF(0x7f7fffff); emitF(0x7f800000); emitF(0xff800000); emitF(0x7fc00000);
        for (long e = 1; e < 2047; e++) { long b = e * 4503599627370496L; emitD(b - 1); emitD(b); emitD(b + 1); }
        for (int e = 1; e < 255; e++) { int b = e * 8388608; emitF(b - 1); emitF(b); emitF(b + 1); }
        for (long i = 0; i < count; i++) {
            st += 0x9e3779b97f4a7c15L;
            long z = st;
            z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
            z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
            z = z ^ (z >>> 31);
            emitD(z);
            emitF((int) (z & 0xffffffffL));
            emitD(z & 4503599627370495L);
            emitF((int) (z & 8388607L));
            long dd = Long.remainderUnsigned(z, 999999999L) + 1;
            long k = (z >>> 40) % 16;
            emitD(decD(dd, k));
            emitF(decF(dd, k % 11));
        }
        for (long q = 0; q < 2000; q++) {
            long x = 9990000 + q;
            emitD(decD(x, 0)); emitD(decD(x, 10)); emitD(decD(q, 6)); emitD(decD(q + 990, 6)); emitD(decD(q + 1, 3));
            emitF(decF(x, 0)); emitF(decF(x, 10)); emitF(decF(q + 1, 3)); emitF(decF(q + 990, 6));
        }
        System.out.print(out);
    }
}
