// swaplock: see swaplock.c. Lock: std::mutex with std::lock_guard.
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <thread>
#define THREADS 32

static std::mutex m;
static uint64_t count = 0, sum = 0, K;

static void worker(uint64_t id) {
	for (uint64_t i = 0; i < K; i++) {
		std::lock_guard<std::mutex> g(m);
		count += 1;
		sum += id + (i % 7);
	}
}

int main(int argc, char **argv) {
	K = argc > 1 ? strtoull(argv[1], 0, 10) : 500000;
	std::thread th[THREADS];
	for (uint64_t j = 0; j < THREADS; j++) th[j] = std::thread(worker, j);
	for (int j = 0; j < THREADS; j++) th[j].join();
	printf("count=%llu sum=%llu\n", (unsigned long long)count, (unsigned long long)sum);
	return 0;
}
