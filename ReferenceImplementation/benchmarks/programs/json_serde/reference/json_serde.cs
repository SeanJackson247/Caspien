using System;

// JSON serialise + parse benchmark (C#). Same hand-written serialiser / iterative parser / checksum as json_serde.c.
public static class JsonSerde
{
    struct Rec { public ulong id, nameOff, nameLen, score, tagOff, tagCnt; }
    static uint x = 12345;
    static uint Next() { x = x * 1664525u + 1013904223u; return x; }

    static int WNum(byte[] t, int p, ulong v)
    {
        int d = 1; for (ulong u = v; u >= 10; u /= 10) d++;
        for (int k = d - 1; k >= 0; k--) { t[p + k] = (byte)('0' + (int)(v % 10)); v /= 10; }
        return p + d;
    }
    static int WLit(byte[] t, int p, string s) { for (int i = 0; i < s.Length; i++) t[p++] = (byte)s[i]; return p; }

    public static void Main(string[] args)
    {
        int n = args.Length > 0 ? int.Parse(args[0]) : 4000000;
        byte[] text = new byte[(long)n * 80 + 16];
        int p = 0;
        text[p++] = (byte)'[';
        for (int i = 0; i < n; i++)
        {
            if (i > 0) text[p++] = (byte)',';
            p = WLit(text, p, "{\"id\":"); p = WNum(text, p, (ulong)i);
            p = WLit(text, p, ",\"name\":\"");
            int nl = 3 + (int)((Next() >> 16) % 8);
            for (int k = 0; k < nl; k++) text[p++] = (byte)('a' + (int)((Next() >> 16) % 26));
            p = WLit(text, p, "\",\"score\":");
            ulong cents = (Next() >> 8) % 1000000;
            p = WNum(text, p, cents / 100); text[p++] = (byte)'.'; text[p++] = (byte)('0' + (int)(cents % 100 / 10)); text[p++] = (byte)('0' + (int)(cents % 10));
            p = WLit(text, p, ",\"tags\":[");
            int tc = (int)((Next() >> 16) % 5);
            for (int k = 0; k < tc; k++) { if (k > 0) text[p++] = (byte)','; p = WNum(text, p, (Next() >> 16) % 100); }
            p = WLit(text, p, "]}");
        }
        text[p++] = (byte)']';
        int tlen = p;
        Rec[] recs = new Rec[n + 1];
        byte[] names = new byte[n * 10 + 8];
        byte[] tags = new byte[n * 4 + 8];
        int count = 0, noff = 0, toff = 0, q = 1;
        for (; ; )
        {
            byte c = text[q];
            if (c == (byte)']') break;
            if (c == (byte)',') { q++; continue; }
            q++;
            Rec r = new Rec();
            for (; ; )
            {
                q++;
                byte k0 = text[q];
                while (text[q] != (byte)'"') q++;
                q += 2;
                if (k0 == (byte)'i')
                {
                    ulong v = 0; while (text[q] >= (byte)'0' && text[q] <= (byte)'9') { v = v * 10 + (ulong)(text[q] - '0'); q++; }
                    r.id = v;
                }
                else if (k0 == (byte)'n')
                {
                    q++;
                    r.nameOff = (ulong)noff;
                    while (text[q] != (byte)'"') { names[noff++] = text[q]; q++; }
                    r.nameLen = (ulong)noff - r.nameOff;
                    q++;
                }
                else if (k0 == (byte)'s')
                {
                    ulong v = 0; while (text[q] >= (byte)'0' && text[q] <= (byte)'9') { v = v * 10 + (ulong)(text[q] - '0'); q++; }
                    q++;
                    v = v * 100 + (ulong)((text[q] - '0') * 10 + (text[q + 1] - '0')); q += 2;
                    r.score = v;
                }
                else
                {
                    q++;
                    r.tagOff = (ulong)toff;
                    while (text[q] != (byte)']')
                    {
                        if (text[q] == (byte)',') q++;
                        ulong v = 0; while (text[q] >= (byte)'0' && text[q] <= (byte)'9') { v = v * 10 + (ulong)(text[q] - '0'); q++; }
                        tags[toff++] = (byte)v;
                    }
                    r.tagCnt = (ulong)toff - r.tagOff;
                    q++;
                }
                if (text[q] == (byte)',') q++; else { q++; break; }
            }
            recs[count++] = r;
        }
        uint h = 7;
        for (int i = 0; i < count; i++)
        {
            Rec r = recs[i];
            h = h * 31 + (uint)r.id;
            h = h * 31 + (uint)r.score;
            h = h * 31 + (uint)r.nameLen;
            for (ulong k = 0; k < r.nameLen; k++) h = h * 31 + names[(int)(r.nameOff + k)];
            h = h * 31 + (uint)r.tagCnt;
            for (ulong k = 0; k < r.tagCnt; k++) h = h * 31 + tags[(int)(r.tagOff + k)];
        }
        Console.Out.Write(count + " " + tlen + " " + h + "\n");
    }
}
