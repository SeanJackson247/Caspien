// String manipulation benchmark (port of strings.c): byte arrays, every result a fresh GC array.
import core.stdc.stdio;
import std.conv : to;

__gshared uint x = 12345;
uint next() { x = x * 1664525u + 1013904223u; return x; }
uint hash(const(ubyte)[] b) { uint h = 7; foreach (c; b) h = h * 31 + c; return h; }

int main(string[] args) {
    long n = args.length > 1 ? args[1].to!long : 4_000_000L;
    auto text = new ubyte[n];
    for (long i = 0; i < n; i++) { uint r = (next() >> 16) % 27; text[i] = r == 26 ? cast(ubyte)' ' : cast(ubyte)('a' + r); }
    long words = 0, longest = 0, cur = 0;
    for (long i = 0; i < n; i++) {
        if (text[i] == ' ') { if (cur > 0) { words++; if (cur > longest) longest = cur; cur = 0; } } else cur++;
    }
    if (cur > 0) { words++; if (cur > longest) longest = cur; }
    long abc = 0;
    for (long i = 0; i + 2 < n; i++) if (text[i] == 'a' && text[i + 1] == 'b' && text[i + 2] == 'c') abc++;
    auto rev = new ubyte[n];
    for (long i = 0; i < n; i++) rev[i] = text[n - 1 - i];
    uint hrev = hash(rev);
    long es = 0;
    for (long i = 0; i < n; i++) if (text[i] == 'e') es++;
    long m = n + es;
    auto rep = new ubyte[m];
    long k = 0;
    for (long i = 0; i < n; i++) { if (text[i] == 'e') { rep[k++] = '3'; rep[k++] = '3'; } else rep[k++] = text[i]; }
    uint hrep = hash(rep);
    auto up = new ubyte[n];
    for (long i = 0; i < n; i++) up[i] = text[i] == ' ' ? cast(ubyte)' ' : cast(ubyte)(text[i] - 32);
    uint hup = hash(up);
    printf("%ld %ld %ld %u %ld %u %u\n", words, longest, abc, hrev, m, hrep, hup);
    return 0;
}
