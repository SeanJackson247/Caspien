using System;

public static class Strings
{
    static uint x = 12345;
    static uint Next() { x = x * 1664525u + 1013904223u; return x; }
    static uint Hash(byte[] b, int n) { uint h = 7; for (int i = 0; i < n; i++) h = h * 31 + b[i]; return h; }

    public static void Main(string[] args)
    {
        int n = args.Length > 0 ? int.Parse(args[0]) : 4000000;
        byte[] text = new byte[n];
        for (int i = 0; i < n; i++) { uint r = (Next() >> 16) % 27; text[i] = r == 26 ? (byte)' ' : (byte)('a' + r); }
        long words = 0, longest = 0, cur = 0;
        for (int i = 0; i < n; i++)
        {
            if (text[i] == (byte)' ') { if (cur > 0) { words++; if (cur > longest) longest = cur; cur = 0; } } else cur++;
        }
        if (cur > 0) { words++; if (cur > longest) longest = cur; }
        long abc = 0;
        for (int i = 0; i + 2 < n; i++) if (text[i] == (byte)'a' && text[i + 1] == (byte)'b' && text[i + 2] == (byte)'c') abc++;
        byte[] rev = new byte[n];
        for (int i = 0; i < n; i++) rev[i] = text[n - 1 - i];
        uint hrev = Hash(rev, n);
        int es = 0;
        for (int i = 0; i < n; i++) if (text[i] == (byte)'e') es++;
        int m = n + es;
        byte[] rep = new byte[m];
        int k = 0;
        for (int i = 0; i < n; i++) { if (text[i] == (byte)'e') { rep[k++] = (byte)'3'; rep[k++] = (byte)'3'; } else rep[k++] = text[i]; }
        uint hrep = Hash(rep, m);
        byte[] up = new byte[n];
        for (int i = 0; i < n; i++) up[i] = text[i] == (byte)' ' ? (byte)' ' : (byte)(text[i] - 32);
        uint hup = Hash(up, n);
        Console.Out.Write(words + " " + longest + " " + abc + " " + hrev + " " + m + " " + hrep + " " + hup + "\n");
    }
}
