// threads: see threads.c. Each std::thread writes its result into its own slot of a local array; join waits for it.
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <thread>
#define WORK 1000000
#define WAVE 64

static void task(uint64_t id, uint64_t *out) {
	uint64_t s = (id + 1) * 2654435761ULL, acc = 0;
	for (uint64_t i = 0; i < WORK; i++) {
		s ^= s << 13; s ^= s >> 7; s ^= s << 17;
		acc += s & 255;
	}
	*out = acc;
}

int main(int argc, char **argv) {
	uint64_t n = argc > 1 ? strtoull(argv[1], 0, 10) : 1024, total = 0;
	for (uint64_t base = 0; base < n; base += WAVE) {
		std::thread th[WAVE];
		uint64_t res[WAVE];
		for (uint64_t j = 0; j < WAVE; j++) th[j] = std::thread(task, base + j, &res[j]);
		for (uint64_t j = 0; j < WAVE; j++) { th[j].join(); total += res[j]; }
	}
	printf("tasks=%llu checksum=%llu\n", (unsigned long long)n, (unsigned long long)total);
	return 0;
}
