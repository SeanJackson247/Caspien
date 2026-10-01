// JSON serialise + parse benchmark (port of json_serde.c): hand-written serialiser and index-based parser over GC ubyte arrays, array of Rec structs.
import core.stdc.stdio;
import std.conv : to;

struct Rec { ulong id, nameOff, nameLen, score, tagOff, tagCnt; }

__gshared uint x = 12345;
uint next() { x = x * 1664525u + 1013904223u; return x; }

long wnum(ubyte[] t, long p, ulong v) {
    long d = 1; ulong u = v; while (u >= 10) { u /= 10; d++; }
    for (long k = d - 1; k >= 0; k--) { t[p + k] = cast(ubyte)('0' + v % 10); v /= 10; }
    return p + d;
}
long wlit(ubyte[] t, long p, string s) { foreach (ch; s) t[p++] = cast(ubyte) ch; return p; }

int main(string[] args) {
    long n = args.length > 1 ? args[1].to!long : 4_000_000L;
    auto text = new ubyte[n * 80 + 16];
    long p = 0;
    text[p++] = '[';
    for (long i = 0; i < n; i++) {
        if (i > 0) text[p++] = ',';
        p = wlit(text, p, "{\"id\":"); p = wnum(text, p, cast(ulong) i);
        p = wlit(text, p, ",\"name\":\"");
        long nl = 3 + (next() >> 16) % 8;
        for (long k = 0; k < nl; k++) text[p++] = cast(ubyte)('a' + (next() >> 16) % 26);
        p = wlit(text, p, "\",\"score\":");
        ulong cents = (next() >> 8) % 1000000;
        p = wnum(text, p, cents / 100); text[p++] = '.'; text[p++] = cast(ubyte)('0' + cents % 100 / 10); text[p++] = cast(ubyte)('0' + cents % 10);
        p = wlit(text, p, ",\"tags\":[");
        long tc = (next() >> 16) % 5;
        for (long k = 0; k < tc; k++) { if (k > 0) text[p++] = ','; p = wnum(text, p, (next() >> 16) % 100); }
        p = wlit(text, p, "]}");
    }
    text[p++] = ']';
    long tlen = p;
    // ---- parse ----
    auto recs = new Rec[n + 1];
    auto names = new ubyte[n * 10 + 8];
    auto tags = new ubyte[n * 4 + 8];
    long count = 0, noff = 0, toff = 0, q = 1;
    for (;;) {
        ubyte c = text[q];
        if (c == ']') break;
        if (c == ',') { q++; continue; }
        q++;
        Rec r;
        for (;;) {
            q++;
            ubyte k0 = text[q];
            while (text[q] != '"') q++;
            q += 2;
            if (k0 == 'i') {
                ulong v = 0; while (text[q] >= '0' && text[q] <= '9') { v = v * 10 + (text[q] - '0'); q++; }
                r.id = v;
            } else if (k0 == 'n') {
                q++;
                r.nameOff = noff;
                while (text[q] != '"') { names[noff++] = text[q]; q++; }
                r.nameLen = noff - r.nameOff;
                q++;
            } else if (k0 == 's') {
                ulong v = 0; while (text[q] >= '0' && text[q] <= '9') { v = v * 10 + (text[q] - '0'); q++; }
                q++;
                v = v * 100 + (text[q] - '0') * 10 + (text[q + 1] - '0'); q += 2;
                r.score = v;
            } else {
                q++;
                r.tagOff = toff;
                while (text[q] != ']') {
                    if (text[q] == ',') q++;
                    ulong v = 0; while (text[q] >= '0' && text[q] <= '9') { v = v * 10 + (text[q] - '0'); q++; }
                    tags[toff++] = cast(ubyte) v;
                }
                r.tagCnt = toff - r.tagOff;
                q++;
            }
            if (text[q] == ',') q++; else { q++; break; }
        }
        recs[count++] = r;
    }
    // ---- checksum over the parsed records ----
    uint h = 7;
    for (long i = 0; i < count; i++) {
        Rec r = recs[i];
        h = h * 31 + cast(uint) r.id;
        h = h * 31 + cast(uint) r.score;
        h = h * 31 + cast(uint) r.nameLen;
        for (ulong k = 0; k < r.nameLen; k++) h = h * 31 + names[r.nameOff + k];
        h = h * 31 + cast(uint) r.tagCnt;
        for (ulong k = 0; k < r.tagCnt; k++) h = h * 31 + tags[r.tagOff + k];
    }
    printf("%ld %ld %u\n", count, tlen, h);
    return 0;
}
