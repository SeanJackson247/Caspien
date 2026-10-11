// JSON serialise + parse benchmark. N records (id, name, score = cents/100, tags) are written by a hand-written serialiser into one
// text buffer ([{"id":0,"name":"abc","score":12.34,"tags":[1,2]},...], no whitespace), then a hand-written index-based iterative
// parser reads the text back into an array of records (names and tags go into pools). No library JSON anywhere. Scores are integer
// cents formatted with integer maths, so there is no float formatting. Prints: records textlen checksum, the checksum (u32) being a
// *31 fold over the PARSED fields (id, score, name length + bytes, tag count + tags). Build with -DLEAK to skip the free() calls.
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
typedef struct { uint64_t id, name_off, name_len, score, tag_off, tag_cnt; } Rec;
static uint32_t x = 12345;
static uint32_t next(void) { x = x * 1664525u + 1013904223u; return x; }
static long wnum(unsigned char *t, long p, uint64_t v) {
    long d = 1; uint64_t u = v; while (u >= 10) { u /= 10; d++; }
    for (long k = d - 1; k >= 0; k--) { t[p + k] = (unsigned char)('0' + v % 10); v /= 10; }
    return p + d;
}
static long wlit(unsigned char *t, long p, const char *s) { while (*s) t[p++] = (unsigned char)*s++; return p; }
int main(int argc, char **argv) {
    long n = argc > 1 ? atol(argv[1]) : 4000000L;
    unsigned char *text = malloc(n * 80 + 16);
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
    // ---- parse ----
    Rec *recs = malloc(n * sizeof(Rec) + 8);
    unsigned char *names = malloc(n * 10 + 8);
    unsigned char *tags = malloc(n * 4 + 8);
    long count = 0, noff = 0, toff = 0, q = 1;
    for (;;) {
        unsigned char c = text[q];
        if (c == ']') break;
        if (c == ',') { q++; continue; }
        q++;                                   // '{'
        Rec r = {0, 0, 0, 0, 0, 0};
        for (;;) {
            q++;                               // opening quote of the key
            unsigned char k0 = text[q];
            while (text[q] != '"') q++;
            q += 2;                            // closing quote, ':'
            if (k0 == 'i') {
                uint64_t v = 0; while (text[q] >= '0' && text[q] <= '9') { v = v * 10 + (text[q] - '0'); q++; }
                r.id = v;
            } else if (k0 == 'n') {
                q++;                           // opening quote
                r.name_off = noff;
                while (text[q] != '"') { names[noff++] = text[q]; q++; }
                r.name_len = noff - r.name_off;
                q++;                           // closing quote
            } else if (k0 == 's') {
                uint64_t v = 0; while (text[q] >= '0' && text[q] <= '9') { v = v * 10 + (text[q] - '0'); q++; }
                q++;                           // '.'
                v = v * 100 + (text[q] - '0') * 10 + (text[q + 1] - '0'); q += 2;
                r.score = v;
            } else {
                q++;                           // '['
                r.tag_off = toff;
                while (text[q] != ']') {
                    if (text[q] == ',') q++;
                    uint64_t v = 0; while (text[q] >= '0' && text[q] <= '9') { v = v * 10 + (text[q] - '0'); q++; }
                    tags[toff++] = (unsigned char)v;
                }
                r.tag_cnt = toff - r.tag_off;
                q++;                           // ']'
            }
            if (text[q] == ',') q++; else { q++; break; }   // next key, or '}'
        }
        recs[count++] = r;
    }
    // ---- checksum over the parsed records ----
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
    printf("%ld %ld %u\n", count, tlen, h);
#ifndef LEAK
    free(text); free(recs); free(names); free(tags);
#endif
    return 0;
}
