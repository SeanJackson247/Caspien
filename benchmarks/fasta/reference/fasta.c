// FASTA benchmark (benchmarks-game style generator). N nucleotides are written, as FASTA text with 60-column lines, into ONE
// heap buffer (nothing goes to stdout): 2N/10 bases of the repeated ALU sequence, 3N/10 bases drawn from the IUB ambiguity-code
// alphabet and N-2N/10-3N/10 from the human-frequency alphabet, with the benchmarks-game LCG (IM=139968, IA=3877, IC=29573,
// seed 42). Differences from the game: the cumulative probabilities are precomputed integer thresholds on the LCG value (no
// floating point), and the IUB alphabet is all upper case. Prints: textlength checksum A C G T other (checksum h=h*31+byte,
// seed 7, mod 2^32; the letter counts are over the sequence bytes only, not the header lines). Build with -DLEAK to skip free().
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static const char ALU[] = "GGCCGGGCGCGGTGGCTCACGCCTGTAATCCCAGCACTTTGGGAGGCCGAGGCGGGCGGATCACCTGAGGTCAGGAGTTCGAGACCAGCCTGGCCAACATGGTGAAACCCCGTCTCTACTAAAAATACAAAAATTAGCCGGGCGTGGTGGCGCGCGCCTGTAATCCCAGCTACTCGGGAGGCTGAGGCAGGAGAATCGCTTGAACCCGGGAGGCGGAGGTTGCAGTGAGCCGAGATCGCGCCACTGCACTCCAGCCTGGGCGACAGAGCGAGACTCCGTCTCAAAAA";
static const char CHARS[] = "ACGTBDHKMNRSVWYACGT";
static const unsigned THR[19] = {37792,54588,71384,109176,111975,114774,117574,120373,123172,125972,128771,131570,134370,137169,139968,42404,70117,97767,139968};
static const char *HDR[3] = {">ONE Homo sapiens alu\n", ">TWO IUB ambiguity codes\n", ">THREE Homo sapiens frequency\n"};
static unsigned last = 42;
int main(int argc, char **argv) {
    long n = argc > 1 ? atol(argv[1]) : 40000000L;
    long cnt[3] = {n * 2 / 10, n * 3 / 10, 0};
    cnt[2] = n - cnt[0] - cnt[1];
    int off[3] = {0, 0, 15};
    unsigned char *buf = malloc(n + n / 60 + 1024);
    long pos = 0, a = 0, c = 0, g = 0, t = 0, other = 0, ai = 0;
    for (int s = 0; s < 3; s++) {
        long hl = strlen(HDR[s]);
        memcpy(buf + pos, HDR[s], hl);
        pos += hl;
        int col = 0;
        for (long i = 0; i < cnt[s]; i++) {
            unsigned char ch;
            if (s == 0) {
                ch = ALU[ai];
                if (++ai == 287) ai = 0;
            } else {
                last = (last * 3877u + 29573u) % 139968u;
                int j = off[s];
                while (last >= THR[j]) j++;
                ch = CHARS[j];
            }
            buf[pos++] = ch;
            if (ch == 'A') a++; else if (ch == 'C') c++; else if (ch == 'G') g++; else if (ch == 'T') t++; else other++;
            if (++col == 60) { buf[pos++] = '\n'; col = 0; }
        }
        if (col > 0) buf[pos++] = '\n';
    }
    unsigned h = 7;
    for (long i = 0; i < pos; i++) h = h * 31 + buf[i];
    printf("%ld %u %ld %ld %ld %ld %ld\n", pos, h, a, c, g, t, other);
#ifndef LEAK
    free(buf);
#endif
    return 0;
}
