// String manipulation benchmark (C++). Default: std::string / std::vector freed automatically; -DLEAK: raw new[] never deleted.
#include <cstdio>
#include <cstdlib>
#include <string>
static unsigned x = 12345;
static unsigned next() { x = x * 1664525u + 1013904223u; return x; }
static unsigned hash(const unsigned char *b, long n) { unsigned h = 7; for (long i = 0; i < n; i++) h = h * 31 + b[i]; return h; }
int main(int argc, char **argv) {
    long n = argc > 1 ? atol(argv[1]) : 4000000L;
#ifdef LEAK
    unsigned char *text = new unsigned char[n];
    unsigned char *rev = new unsigned char[n];
#else
    std::basic_string<unsigned char> text(n, 0), rev(n, 0);
#endif
    for (long i = 0; i < n; i++) { unsigned r = (next() >> 16) % 27; text[i] = r == 26 ? ' ' : (unsigned char)('a' + r); }
    long words = 0, longest = 0, cur = 0;
    for (long i = 0; i < n; i++) {
        if (text[i] == ' ') { if (cur > 0) { words++; if (cur > longest) longest = cur; cur = 0; } } else cur++;
    }
    if (cur > 0) { words++; if (cur > longest) longest = cur; }
    long abc = 0;
    for (long i = 0; i + 2 < n; i++) if (text[i] == 'a' && text[i + 1] == 'b' && text[i + 2] == 'c') abc++;
    for (long i = 0; i < n; i++) rev[i] = text[n - 1 - i];
#ifdef LEAK
    unsigned hrev = hash(rev, n);
#else
    unsigned hrev = hash(rev.data(), n);
#endif
    long es = 0;
    for (long i = 0; i < n; i++) if (text[i] == 'e') es++;
    long m = n + es;
#ifdef LEAK
    unsigned char *rep = new unsigned char[m];
#else
    std::basic_string<unsigned char> repS(m, 0);
    unsigned char *rep = &repS[0];
#endif
    long k = 0;
    for (long i = 0; i < n; i++) { if (text[i] == 'e') { rep[k++] = '3'; rep[k++] = '3'; } else rep[k++] = text[i]; }
    unsigned hrep = hash(rep, m);
#ifdef LEAK
    unsigned char *up = new unsigned char[n];
#else
    std::basic_string<unsigned char> upS(n, 0);
    unsigned char *up = &upS[0];
#endif
    for (long i = 0; i < n; i++) up[i] = text[i] == ' ' ? ' ' : text[i] - 32;
    unsigned hup = hash(up, n);
    printf("%ld %ld %ld %u %ld %u %u\n", words, longest, abc, hrev, m, hrep, hup);
    return 0;
}
