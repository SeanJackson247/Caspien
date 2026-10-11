// JSON serialise + parse benchmark (C++). Same hand-written serialiser / iterative parser / checksum as json_serde.c (see there).
// Default: std::vector buffers freed automatically; -DLEAK: raw new[] never deleted.
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <vector>
struct Rec { uint64_t id, name_off, name_len, score, tag_off, tag_cnt; };
static uint32_t x = 12345;
static uint32_t next() { x = x * 1664525u + 1013904223u; return x; }
static long wnum(unsigned char *t, long p, uint64_t v) {
    long d = 1; uint64_t u = v; while (u >= 10) { u /= 10; d++; }
    for (long k = d - 1; k >= 0; k--) { t[p + k] = (unsigned char)('0' + v % 10); v /= 10; }
    return p + d;
}
static long wlit(unsigned char *t, long p, const char *s) { while (*s) t[p++] = (unsigned char)*s++; return p; }
int main(int argc, char **argv) {
    long n = argc > 1 ? atol(argv[1]) : 4000000L;
#ifdef LEAK
    unsigned char *text = new unsigned char[n * 80 + 16];
    Rec *recs = new Rec[n + 1];
    unsigned char *names = new unsigned char[n * 10 + 8];
    unsigned char *tags = new unsigned char[n * 4 + 8];
#else
    std::vector<unsigned char> textv(n * 80 + 16), namesv(n * 10 + 8), tagsv(n * 4 + 8);
    std::vector<Rec> recsv(n + 1);
    unsigned char *text = textv.data(), *names = namesv.data(), *tags = tagsv.data();
    Rec *recs = recsv.data();
#endif
    long p = 0;
    text[p++] = '[';
    for (long i = 0; i < n; i++) {
        if (i > 0) text[p++] = ',';
        p = wlit(text, p, "{\"id\":"); p = wnum(text, p, (uint64_t)i);
        p = wlit(text, p, ",\"name\":\"");
        long nl = 3 + (next() >> 16) % 8;
        for (long k = 0; k < nl; k++) text[p++] = (unsigned char)('a' + (next() >> 16) % 26);
        p = wlit(text, p, "\",\"score\":");
        uint64_t cents = (next() >> 8) % 1000000;
        p = wnum(text, p, cents / 100); text[p++] = '.'; text[p++] = (unsigned char)('0' + cents % 100 / 10); text[p++] = (unsigned char)('0' + cents % 10);
        p = wlit(text, p, ",\"tags\":[");
        long tc = (next() >> 16) % 5;
        for (long k = 0; k < tc; k++) { if (k > 0) text[p++] = ','; p = wnum(text, p, (next() >> 16) % 100); }
        p = wlit(text, p, "]}");
    }
    text[p++] = ']';
    long tlen = p;
    long count = 0, noff = 0, toff = 0, q = 1;
    for (;;) {
        unsigned char c = text[q];
        if (c == ']') break;
        if (c == ',') { q++; continue; }
        q++;
        Rec r = {0, 0, 0, 0, 0, 0};
        for (;;) {
            q++;
            unsigned char k0 = text[q];
            while (text[q] != '"') q++;
            q += 2;
            if (k0 == 'i') {
                uint64_t v = 0; while (text[q] >= '0' && text[q] <= '9') { v = v * 10 + (text[q] - '0'); q++; }
                r.id = v;
            } else if (k0 == 'n') {
                q++;
                r.name_off = noff;
                while (text[q] != '"') { names[noff++] = text[q]; q++; }
                r.name_len = noff - r.name_off;
                q++;
            } else if (k0 == 's') {
                uint64_t v = 0; while (text[q] >= '0' && text[q] <= '9') { v = v * 10 + (text[q] - '0'); q++; }
                q++;
                v = v * 100 + (text[q] - '0') * 10 + (text[q + 1] - '0'); q += 2;
                r.score = v;
            } else {
                q++;
                r.tag_off = toff;
                while (text[q] != ']') {
                    if (text[q] == ',') q++;
                    uint64_t v = 0; while (text[q] >= '0' && text[q] <= '9') { v = v * 10 + (text[q] - '0'); q++; }
                    tags[toff++] = (unsigned char)v;
                }
                r.tag_cnt = toff - r.tag_off;
                q++;
            }
            if (text[q] == ',') q++; else { q++; break; }
        }
        recs[count++] = r;
    }
    uint32_t h = 7;
    for (long i = 0; i < count; i++) {
        Rec r = recs[i];
        h = h * 31 + (uint32_t)r.id;
        h = h * 31 + (uint32_t)r.score;
        h = h * 31 + (uint32_t)r.name_len;
        for (uint64_t k = 0; k < r.name_len; k++) h = h * 31 + names[r.name_off + k];
        h = h * 31 + (uint32_t)r.tag_cnt;
        for (uint64_t k = 0; k < r.tag_cnt; k++) h = h * 31 + tags[r.tag_off + k];
    }
    std::printf("%ld %ld %u\n", count, tlen, h);
    return 0;
}
