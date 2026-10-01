// LRU cache benchmark (see lru.c): an LRUCache object holding the node pool as parallel arrays, chained hash buckets threaded
// through the pool and an index-based doubly linked recency list.
#include <objc/objc.h>
#include <objc/runtime.h>
#pragma GCC diagnostic ignored "-Wobjc-root-class"
// GNU libobjc ships no Foundation / NSObject here, so every class derives from this tiny root class (instances are heap objects
// created with class_createInstance, i.e. one allocation per object, released with object_dispose).
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
#define CAP 262144u
#define KEYS 1048576u
#define HOT 131072u
#define NB 524288u
#define NONE 0xFFFFFFFFu
static uint32_t x = 12345;
static uint32_t next(void) { x = x * 1664525u + 1013904223u; return x; }
static inline uint32_t hsh(uint32_t k) { return (uint32_t)((k * 2654435761ull) & 0xFFFFFFFFull) >> 13; }

@interface LRUCache : Base {
    @public
    uint32_t *key, *val, *prv, *nxt, *hn, *bucket;
    uint32_t head, tail, size;
    uint64_t hits, misses, sum;
}
- (id)setup;
- (uint32_t)find:(uint32_t)k;
- (void)unlinkNode:(uint32_t)i;
- (void)pushFront:(uint32_t)i;
- (void)hashRemove:(uint32_t)i;
- (void)run:(long)n;
- (uint64_t)finish;
- (void)destroy;
@end
@implementation LRUCache
- (id)setup {
    uint32_t i;
    key = malloc(CAP * 4); val = malloc(CAP * 4); prv = malloc(CAP * 4); nxt = malloc(CAP * 4); hn = malloc(CAP * 4);
    bucket = malloc(NB * 4);
    for (i = 0; i < NB; i++) bucket[i] = NONE;
    head = NONE; tail = NONE; size = 0; hits = 0; misses = 0; sum = 0;
    return self;
}
- (void)unlinkNode:(uint32_t)i {
    if (prv[i] != NONE) nxt[prv[i]] = nxt[i]; else head = nxt[i];
    if (nxt[i] != NONE) prv[nxt[i]] = prv[i]; else tail = prv[i];
}
- (void)pushFront:(uint32_t)i {
    prv[i] = NONE; nxt[i] = head;
    if (head != NONE) prv[head] = i; else tail = i;
    head = i;
}
- (uint32_t)find:(uint32_t)k {
    uint32_t i = bucket[hsh(k)];
    while (i != NONE && key[i] != k) i = hn[i];
    return i;
}
- (void)hashRemove:(uint32_t)i {
    uint32_t b = hsh(key[i]), j;
    if (bucket[b] == i) { bucket[b] = hn[i]; return; }
    j = bucket[b];
    while (hn[j] != i) j = hn[j];
    hn[j] = hn[i];
}
- (void)run:(long)n {
    long op;
    uint32_t a, y, range, k, i, b;
    for (op = 0; op < n; op++) {
        a = next(); y = next();
        range = ((y >> 20) % 4 == 0) ? KEYS : HOT;
        k = (a >> 8) % range;
        i = [self find:k];
        if ((y >> 24) % 4 != 0) {
            if (i != NONE) { hits++; sum += (uint64_t)val[i] + k; [self unlinkNode:i]; [self pushFront:i]; }
            else misses++;
        } else if (i != NONE) {
            val[i] = y; [self unlinkNode:i]; [self pushFront:i];
        } else {
            if (size == CAP) {
                i = tail; sum += key[i]; [self unlinkNode:i]; [self hashRemove:i];
            } else i = size++;
            key[i] = k; val[i] = y;
            b = hsh(k); hn[i] = bucket[b]; bucket[b] = i;
            [self pushFront:i];
        }
    }
}
- (uint64_t)finish {
    uint64_t fin = 0;
    uint32_t i;
    for (i = head; i != NONE; i = nxt[i]) fin = (fin * 31 + key[i]) % 4294967296ull;
    return (sum + fin) % 4294967296ull;
}
- (void)destroy {
    free(key); free(val); free(prv); free(nxt); free(hn); free(bucket);
    [self free];
}
@end

int main(int argc, char **argv) {
    long n = argc > 1 ? atol(argv[1]) : 20000000L;
    LRUCache *c = [[LRUCache new] setup];
    uint64_t chk;
    [c run:n];
    chk = [c finish];
    printf("%llu %llu %llu\n", (unsigned long long)c->hits, (unsigned long long)c->misses, (unsigned long long)chk);
    [c destroy];
    return 0;
}
