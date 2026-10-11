using System;

public static class Fasta
{
    const string ALU = "GGCCGGGCGCGGTGGCTCACGCCTGTAATCCCAGCACTTTGGGAGGCCGAGGCGGGCGGATCACCTGAGGTCAGGAGTTCGAGACCAGCCTGGCCAACATGGTGAAACCCCGTCTCTACTAAAAATACAAAAATTAGCCGGGCGTGGTGGCGCGCGCCTGTAATCCCAGCTACTCGGGAGGCTGAGGCAGGAGAATCGCTTGAACCCGGGAGGCGGAGGTTGCAGTGAGCCGAGATCGCGCCACTGCACTCCAGCCTGGGCGACAGAGCGAGACTCCGTCTCAAAAA";
    const string CHARS = "ACGTBDHKMNRSVWYACGT";
    static readonly int[] THR = { 37792, 54588, 71384, 109176, 111975, 114774, 117574, 120373, 123172, 125972, 128771, 131570, 134370, 137169, 139968, 42404, 70117, 97767, 139968 };
    static readonly string[] HDR = { ">ONE Homo sapiens alu\n", ">TWO IUB ambiguity codes\n", ">THREE Homo sapiens frequency\n" };

    public static void Main(string[] args)
    {
        int n = args.Length > 0 ? int.Parse(args[0]) : 40000000;
        int[] cnt = { 0, 0, 0 };
        cnt[0] = (int)((long)n * 2 / 10);
        cnt[1] = (int)((long)n * 3 / 10);
        cnt[2] = n - cnt[0] - cnt[1];
        int[] off = { 0, 0, 15 };
        int last = 42;
        byte[] buf = new byte[n + n / 60 + 1024];
        byte[] alu = System.Text.Encoding.ASCII.GetBytes(ALU);
        byte[] chars = System.Text.Encoding.ASCII.GetBytes(CHARS);
        int pos = 0, ai = 0;
        long a = 0, c = 0, g = 0, t = 0, other = 0;
        for (int s = 0; s < 3; s++)
        {
            byte[] hd = System.Text.Encoding.ASCII.GetBytes(HDR[s]);
            Array.Copy(hd, 0, buf, pos, hd.Length);
            pos += hd.Length;
            int col = 0;
            for (int i = 0; i < cnt[s]; i++)
            {
                byte ch;
                if (s == 0)
                {
                    ch = alu[ai];
                    if (++ai == 287) ai = 0;
                }
                else
                {
                    last = (last * 3877 + 29573) % 139968;
                    int j = off[s];
                    while (last >= THR[j]) j++;
                    ch = chars[j];
                }
                buf[pos++] = ch;
                if (ch == (byte)'A') a++; else if (ch == (byte)'C') c++; else if (ch == (byte)'G') g++; else if (ch == (byte)'T') t++; else other++;
                if (++col == 60) { buf[pos++] = (byte)'\n'; col = 0; }
            }
            if (col > 0) buf[pos++] = (byte)'\n';
        }
        uint h = 7;
        for (int i = 0; i < pos; i++) h = unchecked(h * 31 + buf[i]);
        Console.Out.Write(pos + " " + h + " " + a + " " + c + " " + g + " " + t + " " + other + "\n");
    }
}
