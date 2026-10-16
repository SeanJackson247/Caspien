// SHA-256 known-answer self-test (same compress() as merkletrees.c). Build: gcc -O2 sha256_selftest.c -o sha256_selftest && ./sha256_selftest
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
static const uint32_t K[64] = {
0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
static const uint32_t H0[8] = {0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
#define ROR(v, n) (((v) >> (n)) | ((v) << (32 - (n))))
// one compression: st[8] updated in place with the 16-word big-endian block b
static void compress(uint32_t *st, const uint32_t *b) {
    uint32_t w[64];
    for (int i = 0; i < 16; i++) w[i] = b[i];
    for (int i = 16; i < 64; i++) {
        uint32_t s0 = ROR(w[i-15], 7) ^ ROR(w[i-15], 18) ^ (w[i-15] >> 3);
        uint32_t s1 = ROR(w[i-2], 17) ^ ROR(w[i-2], 19) ^ (w[i-2] >> 10);
        w[i] = w[i-16] + s0 + w[i-7] + s1;
    }
    uint32_t a = st[0], bb = st[1], c = st[2], d = st[3], e = st[4], f = st[5], g = st[6], h = st[7];
    for (int i = 0; i < 64; i++) {
        uint32_t S1 = ROR(e, 6) ^ ROR(e, 11) ^ ROR(e, 25);
        uint32_t ch = (e & f) ^ (~e & g);
        uint32_t t1 = h + S1 + ch + K[i] + w[i];
        uint32_t S0 = ROR(a, 2) ^ ROR(a, 13) ^ ROR(a, 22);
        uint32_t mj = (a & bb) ^ (a & c) ^ (bb & c);
        uint32_t t2 = S0 + mj;
        h = g; g = f; f = e; e = d + t1; d = c; c = bb; bb = a; a = t1 + t2;
    }
    st[0] += a; st[1] += bb; st[2] += c; st[3] += d; st[4] += e; st[5] += f; st[6] += g; st[7] += h;
}

static void sha256(const unsigned char *m, size_t len, char *hex) {
    uint32_t st[8], b[16];
    memcpy(st, H0, sizeof st);
    size_t full = len / 64;
    for (size_t k = 0; k < full; k++) {
        for (int i = 0; i < 16; i++) { const unsigned char *q = m + 64 * k + 4 * i; b[i] = (uint32_t)q[0] << 24 | q[1] << 16 | q[2] << 8 | q[3]; }
        compress(st, b);
    }
    unsigned char tail[128] = {0};
    size_t r = len - 64 * full;
    memcpy(tail, m + 64 * full, r);
    tail[r] = 0x80;
    size_t tl = (r < 56) ? 64 : 128;
    uint64_t bits = (uint64_t)len * 8;
    for (int i = 0; i < 8; i++) tail[tl - 1 - i] = (unsigned char)(bits >> (8 * i));
    for (size_t k = 0; k < tl / 64; k++) {
        for (int i = 0; i < 16; i++) { const unsigned char *q = tail + 64 * k + 4 * i; b[i] = (uint32_t)q[0] << 24 | q[1] << 16 | q[2] << 8 | q[3]; }
        compress(st, b);
    }
    for (int i = 0; i < 8; i++) sprintf(hex + 8 * i, "%08x", st[i]);
}
static int fails = 0;
static void check(const char *name, const unsigned char *m, size_t len, const char *want) {
    char hex[65];
    sha256(m, len, hex);
    int ok = strcmp(hex, want) == 0;
    printf("%s %s\n", ok ? "PASS" : "FAIL", name);
    if (!ok) { printf("  got  %s\n  want %s\n", hex, want); fails++; }
}
int main(void) {
    check("empty", (const unsigned char *)"", 0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    check("abc", (const unsigned char *)"abc", 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    const char *m2 = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";
    check("448-bit", (const unsigned char *)m2, strlen(m2), "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");
    unsigned char *big = malloc(1000000);
    memset(big, 'a', 1000000);
    check("million a", big, 1000000, "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0");
    free(big);
    printf(fails ? "SELFTEST FAILED\n" : "SELFTEST OK\n");
    return fails != 0;
}
