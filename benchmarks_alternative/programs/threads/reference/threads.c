// threads: spawn many OS threads, each runs the same integer kernel on its own state, join them all and add up the results.
// No data is shared between the threads. Tasks run in waves of 64 threads (spawn 64, join 64, next wave); N (argv[1]) is a multiple of 64.
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#define WORK 1000000
#define WAVE 64

static void *task(void *arg) {
	uint64_t id = (uint64_t)arg;
	uint64_t s = (id + 1) * 2654435761ULL, acc = 0;
	for (uint64_t i = 0; i < WORK; i++) {
		s ^= s << 13; s ^= s >> 7; s ^= s << 17;
		acc += s & 255;
	}
	return (void *)acc;
}

int main(int argc, char **argv) {
	uint64_t n = argc > 1 ? strtoull(argv[1], 0, 10) : 1024, total = 0;
	for (uint64_t base = 0; base < n; base += WAVE) {
		pthread_t th[WAVE];
		for (uint64_t j = 0; j < WAVE; j++) pthread_create(&th[j], 0, task, (void *)(base + j));
		for (uint64_t j = 0; j < WAVE; j++) { void *r; pthread_join(th[j], &r); total += (uint64_t)r; }
	}
	printf("tasks=%llu checksum=%llu\n", (unsigned long long)n, (unsigned long long)total);
	return 0;
}
