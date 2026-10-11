// LRU cache benchmark (port of lru.c): chained hash buckets through the node pool, index-based doubly linked recency list (parallel uint arrays).
import core.stdc.stdio;
import std.conv : to;

enum uint CAP = 262144u, KEYS = 1048576u, HOT = 131072u, NB = 524288u, NONE = 0xFFFFFFFFu;

__gshared uint x = 12345;
uint next() { x = x * 1664525u + 1013904223u; return x; }

__gshared uint[] key, val, prv, nxt, hn, bucket;
__gshared uint head = NONE, tail = NONE;

uint hsh(uint k) { return cast(uint)((k * 2654435761UL) & 0xFFFFFFFFUL) >> 13; }
void unlinkNode(uint i) {
    if (prv[i] != NONE) nxt[prv[i]] = nxt[i]; else head = nxt[i];
    if (nxt[i] != NONE) prv[nxt[i]] = prv[i]; else tail = prv[i];
}
void pushFront(uint i) {
    prv[i] = NONE; nxt[i] = head;
    if (head != NONE) prv[head] = i; else tail = i;
    head = i;
}
uint find(uint k) {
    uint i = bucket[hsh(k)];
    while (i != NONE && key[i] != k) i = hn[i];
    return i;
}
void hashRemove(uint i) {
    uint b = hsh(key[i]);
    if (bucket[b] == i) { bucket[b] = hn[i]; return; }
    uint j = bucket[b];
    while (hn[j] != i) j = hn[j];
    hn[j] = hn[i];
}

int main(string[] args) {
    long n = args.length > 1 ? args[1].to!long : 20_000_000L;
    key = new uint[CAP]; val = new uint[CAP]; prv = new uint[CAP]; nxt = new uint[CAP]; hn = new uint[CAP];
    bucket = new uint[NB];
    bucket[] = NONE;
    uint size = 0;
    ulong hits = 0, misses = 0, sum = 0;
    for (long op = 0; op < n; op++) {
        uint a = next(), y = next();
        uint range = ((y >> 20) % 4 == 0) ? KEYS : HOT;
        uint k = (a >> 8) % range;
        uint i = find(k);
        if ((y >> 24) % 4 != 0) {
            if (i != NONE) { hits++; sum += cast(ulong) val[i] + k; unlinkNode(i); pushFront(i); }
            else misses++;
        } else if (i != NONE) {
            val[i] = y; unlinkNode(i); pushFront(i);
        } else {
            if (size == CAP) {
                i = tail; sum += key[i]; unlinkNode(i); hashRemove(i);
            } else i = size++;
            key[i] = k; val[i] = y;
            uint b = hsh(k); hn[i] = bucket[b]; bucket[b] = i;
            pushFront(i);
        }
    }
    ulong fin = 0;
    for (uint i = head; i != NONE; i = nxt[i]) fin = (fin * 31 + key[i]) % 4294967296UL;
    printf("%llu %llu %llu\n", hits, misses, (sum + fin) % 4294967296UL);
    return 0;
}
