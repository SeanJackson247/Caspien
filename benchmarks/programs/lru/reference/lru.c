// LRU cache benchmark: a fixed-capacity (262144 entries) least-recently-used cache driven by N deterministic get/put operations over a
// key space of 1048576 keys (4x the capacity; 75% of the operations hit a "hot" half-capacity subset, 25% the whole key space).
// Hash map = chained buckets threaded through the node pool (hn[]), recency list = index-based doubly linked list (prv[]/nxt[]).
// get: hit -> hits++, move to front, checksum += value + key; miss -> misses++ (no insert). put: update + move to front, or insert at the
// front, evicting the least recently used entry when full (checksum += evicted key). Prints: hits misses checksum.
// Build with -DLEAK to skip freeing the arrays.
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#define CAP 262144u
#define KEYS 1048576u
#define HOT 131072u
#define NB 524288u
#define NONE 0xFFFFFFFFu
static uint32_t x = 12345;
static uint32_t next(void) { x = x * 1664525u + 1013904223u; return x; }
static uint32_t *key, *val, *prv, *nxt, *hn, *bucket;
static uint32_t head = NONE, tail = NONE;
static inline uint32_t hsh(uint32_t k) { return (uint32_t)((k * 2654435761ull) & 0xFFFFFFFFull) >> 13; }
static void unlink_node(uint32_t i) {
    if (prv[i] != NONE) nxt[prv[i]] = nxt[i]; else head = nxt[i];
    if (nxt[i] != NONE) prv[nxt[i]] = prv[i]; else tail = prv[i];
}
static void push_front(uint32_t i) {
    prv[i] = NONE; nxt[i] = head;
    if (head != NONE) prv[head] = i; else tail = i;
    head = i;
}
static uint32_t find(uint32_t k) {
    uint32_t i = bucket[hsh(k)];
    while (i != NONE && key[i] != k) i = hn[i];
    return i;
}
static void hash_remove(uint32_t i) {
    uint32_t b = hsh(key[i]);
    if (bucket[b] == i) { bucket[b] = hn[i]; return; }
    uint32_t j = bucket[b];
    while (hn[j] != i) j = hn[j];
    hn[j] = hn[i];
}
int main(int argc, char **argv) {
    long n = argc > 1 ? atol(argv[1]) : 20000000L;
    key = malloc(CAP * 4); val = malloc(CAP * 4); prv = malloc(CAP * 4); nxt = malloc(CAP * 4); hn = malloc(CAP * 4);
    bucket = malloc(NB * 4);
    for (uint32_t i = 0; i < NB; i++) bucket[i] = NONE;
    uint32_t size = 0;
    uint64_t hits = 0, misses = 0;
    uint64_t sum = 0;
    for (long op = 0; op < n; op++) {
        uint32_t a = next(), y = next();
        uint32_t range = ((y >> 20) % 4 == 0) ? KEYS : HOT;
        uint32_t k = (a >> 8) % range;
        uint32_t i = find(k);
        if ((y >> 24) % 4 != 0) {
            if (i != NONE) { hits++; sum += (uint64_t)val[i] + k; unlink_node(i); push_front(i); }
            else misses++;
        } else if (i != NONE) {
            val[i] = y; unlink_node(i); push_front(i);
        } else {
            if (size == CAP) {
                i = tail; sum += key[i]; unlink_node(i); hash_remove(i);
            } else i = size++;
            key[i] = k; val[i] = y;
            uint32_t b = hsh(k); hn[i] = bucket[b]; bucket[b] = i;
            push_front(i);
        }
    }
    uint64_t fin = 0;
    for (uint32_t i = head; i != NONE; i = nxt[i]) fin = (fin * 31 + key[i]) % 4294967296ull;
    printf("%llu %llu %llu\n", (unsigned long long)hits, (unsigned long long)misses, (unsigned long long)((sum + fin) % 4294967296ull));
#ifndef LEAK
    free(key); free(val); free(prv); free(nxt); free(hn); free(bucket);
#endif
    return 0;
}
