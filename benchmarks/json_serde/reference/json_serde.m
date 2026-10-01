// JSON serialise + parse benchmark (see json_serde.c): a JsonWriter object serialises N records into one text buffer, a JsonParser
// object reads the text back (hand-written index-based iterative parser) into an array of records (names and tags go into pools).
#include <objc/objc.h>
#include <objc/runtime.h>
#pragma GCC diagnostic ignored "-Wobjc-root-class"
// GNU libobjc ships no Foundation / NSObject here, so every class derives from this tiny root class (instances are heap objects
// created with class_createInstance, released with object_dispose).
@interface Base { Class isa; }
+ (id)new;
- (void)free;
@end
@implementation Base
+ (id)new { return class_createInstance(self, 0); }
- (void)free { object_dispose(self); }
@end

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
typedef struct { uint64_t id, name_off, name_len, score, tag_off, tag_cnt; } Rec;
static uint32_t x = 12345;
static uint32_t next(void) { x = x * 1664525u + 1013904223u; return x; }

@interface JsonWriter : Base { @public unsigned char *text; long p; }
- (id)initForRecords:(long)n;
- (void)num:(uint64_t)v;
- (void)lit:(const char *)s;
- (void)writeRecords:(long)n;
@end
@implementation JsonWriter
- (id)initForRecords:(long)n { text = malloc(n * 80 + 16); p = 0; return self; }
- (void)num:(uint64_t)v {
    long d = 1, k; uint64_t u = v;
    while (u >= 10) { u /= 10; d++; }
    for (k = d - 1; k >= 0; k--) { text[p + k] = (unsigned char)('0' + v % 10); v /= 10; }
    p += d;
}
- (void)lit:(const char *)s { while (*s) text[p++] = (unsigned char)*s++; }
- (void)writeRecords:(long)n {
    long i, k, nl, tc; uint64_t cents;
    text[p++] = '[';
    for (i = 0; i < n; i++) {
        if (i > 0) text[p++] = ',';
        [self lit:"{\"id\":"]; [self num:(uint64_t)i];
        [self lit:",\"name\":\""];
        nl = 3 + (next() >> 16) % 8;
        for (k = 0; k < nl; k++) text[p++] = (unsigned char)('a' + (next() >> 16) % 26);
        [self lit:"\",\"score\":"];
        cents = (next() >> 8) % 1000000;
        [self num:cents / 100]; text[p++] = '.'; text[p++] = (unsigned char)('0' + cents % 100 / 10); text[p++] = (unsigned char)('0' + cents % 10);
        [self lit:",\"tags\":["];
        tc = (next() >> 16) % 5;
        for (k = 0; k < tc; k++) { if (k > 0) text[p++] = ','; [self num:(next() >> 16) % 100]; }
        [self lit:"]}"];
    }
    text[p++] = ']';
}
@end

@interface JsonParser : Base { @public unsigned char *text; Rec *recs; unsigned char *names, *tags; long count, noff, toff, q; }
- (id)initWithText:(unsigned char *)t records:(long)n;
- (void)parse;
- (uint32_t)checksum;
- (void)destroy;
@end
@implementation JsonParser
- (id)initWithText:(unsigned char *)t records:(long)n {
    text = t;
    recs = malloc(n * sizeof(Rec) + 8);
    names = malloc(n * 10 + 8);
    tags = malloc(n * 4 + 8);
    count = 0; noff = 0; toff = 0; q = 1;
    return self;
}
- (uint64_t)readUInt {
    uint64_t v = 0;
    while (text[q] >= '0' && text[q] <= '9') { v = v * 10 + (text[q] - '0'); q++; }
    return v;
}
- (void)parse {
    unsigned char c, k0;
    Rec r;
    uint64_t v;
    for (;;) {
        c = text[q];
        if (c == ']') break;
        if (c == ',') { q++; continue; }
        q++;                                   // '{'
        r.id = 0; r.name_off = 0; r.name_len = 0; r.score = 0; r.tag_off = 0; r.tag_cnt = 0;
        for (;;) {
            q++;                               // opening quote of the key
            k0 = text[q];
            while (text[q] != '"') q++;
            q += 2;                            // closing quote, ':'
            if (k0 == 'i') {
                r.id = [self readUInt];
            } else if (k0 == 'n') {
                q++;                           // opening quote
                r.name_off = noff;
                while (text[q] != '"') { names[noff++] = text[q]; q++; }
                r.name_len = noff - r.name_off;
                q++;                           // closing quote
            } else if (k0 == 's') {
                v = [self readUInt];
                q++;                           // '.'
                v = v * 100 + (text[q] - '0') * 10 + (text[q + 1] - '0'); q += 2;
                r.score = v;
            } else {
                q++;                           // '['
                r.tag_off = toff;
                while (text[q] != ']') {
                    if (text[q] == ',') q++;
                    v = [self readUInt];
                    tags[toff++] = (unsigned char)v;
                }
                r.tag_cnt = toff - r.tag_off;
                q++;                           // ']'
            }
            if (text[q] == ',') q++; else { q++; break; }   // next key, or '}'
        }
        recs[count++] = r;
    }
}
- (uint32_t)checksum {
    uint32_t h = 7;
    long i; uint64_t k;
    Rec r;
    for (i = 0; i < count; i++) {
        r = recs[i];
        h = h * 31 + (uint32_t)r.id;
        h = h * 31 + (uint32_t)r.score;
        h = h * 31 + (uint32_t)r.name_len;
        for (k = 0; k < r.name_len; k++) h = h * 31 + names[r.name_off + k];
        h = h * 31 + (uint32_t)r.tag_cnt;
        for (k = 0; k < r.tag_cnt; k++) h = h * 31 + tags[r.tag_off + k];
    }
    return h;
}
- (void)destroy { free(recs); free(names); free(tags); [self free]; }
@end

int main(int argc, char **argv) {
    long n = argc > 1 ? atol(argv[1]) : 4000000L;
    JsonWriter *w = [[JsonWriter new] initForRecords:n];
    JsonParser *ps;
    long tlen;
    [w writeRecords:n];
    tlen = w->p;
    ps = [[JsonParser new] initWithText:w->text records:n];
    [ps parse];
    printf("%ld %ld %u\n", ps->count, tlen, [ps checksum]);
    free(w->text); [w free]; [ps destroy];
    return 0;
}
