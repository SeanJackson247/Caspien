// FASTA benchmark (port of fasta.c): everything is written into one GC byte buffer, only the summary line is printed.
import core.stdc.stdio;
import std.conv : to;

immutable string ALU = "GGCCGGGCGCGGTGGCTCACGCCTGTAATCCCAGCACTTTGGGAGGCCGAGGCGGGCGGATCACCTGAGGTCAGGAGTTCGAGACCAGCCTGGCCAACATGGTGAAACCCCGTCTCTACTAAAAATACAAAAATTAGCCGGGCGTGGTGGCGCGCGCCTGTAATCCCAGCTACTCGGGAGGCTGAGGCAGGAGAATCGCTTGAACCCGGGAGGCGGAGGTTGCAGTGAGCCGAGATCGCGCCACTGCACTCCAGCCTGGGCGACAGAGCGAGACTCCGTCTCAAAAA";
immutable string CHARS = "ACGTBDHKMNRSVWYACGT";
immutable uint[19] THR = [37792,54588,71384,109176,111975,114774,117574,120373,123172,125972,128771,131570,134370,137169,139968,42404,70117,97767,139968];
immutable string[3] HDR = [">ONE Homo sapiens alu\n", ">TWO IUB ambiguity codes\n", ">THREE Homo sapiens frequency\n"];

int main(string[] args) {
    long n = args.length > 1 ? args[1].to!long : 40_000_000L;
    long[3] cnt;
    cnt[0] = n * 2 / 10; cnt[1] = n * 3 / 10; cnt[2] = n - cnt[0] - cnt[1];
    int[3] off = [0, 0, 15];
    auto buf = new ubyte[n + n / 60 + 1024];
    uint last = 42;
    long pos = 0, a = 0, c = 0, g = 0, t = 0, other = 0, ai = 0;
    for (int s = 0; s < 3; s++) {
        foreach (hc; HDR[s]) buf[pos++] = cast(ubyte) hc;
        int col = 0;
        for (long i = 0; i < cnt[s]; i++) {
            ubyte ch;
            if (s == 0) {
                ch = cast(ubyte) ALU[ai];
                if (++ai == 287) ai = 0;
            } else {
                last = (last * 3877u + 29573u) % 139968u;
                int j = off[s];
                while (last >= THR[j]) j++;
                ch = cast(ubyte) CHARS[j];
            }
            buf[pos++] = ch;
            if (ch == 'A') a++; else if (ch == 'C') c++; else if (ch == 'G') g++; else if (ch == 'T') t++; else other++;
            if (++col == 60) { buf[pos++] = '\n'; col = 0; }
        }
        if (col > 0) buf[pos++] = '\n';
    }
    uint h = 7;
    for (long i = 0; i < pos; i++) h = h * 31 + buf[i];
    printf("%ld %u %ld %ld %ld %ld %ld\n", pos, h, a, c, g, t, other);
    return 0;
}
