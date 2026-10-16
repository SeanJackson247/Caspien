// LRU cache benchmark (C++): the natural STL structure, std::unordered_map<key, list iterator> + std::list<entry> (front = most recently used),
// same capacity (262144), key space (1048576) and operation stream as lru.c. Default: freed automatically; -DLEAK: heap-allocated, never deleted.
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <list>
#include <unordered_map>
static const uint32_t CAP = 262144u, KEYS = 1048576u, HOT = 131072u;
static uint32_t x = 12345;
static uint32_t next() { x = x * 1664525u + 1013904223u; return x; }
struct Entry { uint32_t key, val; };
struct Lru {
    std::list<Entry> order;
    std::unordered_map<uint32_t, std::list<Entry>::iterator> map;
};
static uint64_t run(Lru &c, long n, uint64_t &hits, uint64_t &misses) {
    uint32_t sum = 0;
    for (long op = 0; op < n; op++) {
        uint32_t a = next(), y = next();
        uint32_t range = ((y >> 20) % 4 == 0) ? KEYS : HOT;
        uint32_t k = (a >> 8) % range;
        auto it = c.map.find(k);
        if ((y >> 24) % 4 != 0) {
            if (it != c.map.end()) { hits++; sum += it->second->val + k; c.order.splice(c.order.begin(), c.order, it->second); }
            else misses++;
        } else if (it != c.map.end()) {
            it->second->val = y; c.order.splice(c.order.begin(), c.order, it->second);
        } else {
            if (c.map.size() == CAP) {
                Entry &e = c.order.back(); sum += e.key; c.map.erase(e.key); c.order.pop_back();
            }
            c.order.push_front(Entry{k, y});
            c.map[k] = c.order.begin();
        }
    }
    uint64_t fin = 0;
    for (const Entry &e : c.order) fin = (fin * 31 + e.key) % 4294967296ull;
    return (sum + fin) % 4294967296ull;
}
int main(int argc, char **argv) {
    long n = argc > 1 ? atol(argv[1]) : 20000000L;
    uint64_t hits = 0, misses = 0;
#ifdef LEAK
    Lru *c = new Lru();
    c->map.reserve(CAP * 2);
    uint64_t chk = run(*c, n, hits, misses);
#else
    Lru cache;
    cache.map.reserve(CAP * 2);
    uint64_t chk = run(cache, n, hits, misses);
#endif
    printf("%llu %llu %llu\n", (unsigned long long)hits, (unsigned long long)misses, (unsigned long long)chk);
    return 0;
}
