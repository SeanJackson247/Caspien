// swaplock: 32 OS threads all update one shared struct (count, sum) under one lock, K times each (K = argv[1]).
// Each update: count += 1; sum += id + (i % 7). The result does not depend on the order of the updates.
// Lock: pthread_mutex_t.
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#define THREADS 32

static struct { pthread_mutex_t m; uint64_t count, sum; } shared = { PTHREAD_MUTEX_INITIALIZER, 0, 0 };
static uint64_t K;

static void *worker(void *arg) {
	uint64_t id = (uint64_t)arg;
	for (uint64_t i = 0; i < K; i++) {
		pthread_mutex_lock(&shared.m);
		shared.count += 1;
		shared.sum += id + (i % 7);
		pthread_mutex_unlock(&shared.m);
	}
	return 0;
}

int main(int argc, char **argv) {
	K = argc > 1 ? strtoull(argv[1], 0, 10) : 500000;
	pthread_t th[THREADS];
	for (uint64_t j = 0; j < THREADS; j++) pthread_create(&th[j], 0, worker, (void *)j);
	for (int j = 0; j < THREADS; j++) pthread_join(th[j], 0);
	printf("count=%llu sum=%llu\n", (unsigned long long)shared.count, (unsigned long long)shared.sum);
	return 0;
}
