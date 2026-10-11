// threads: see threads.c. Platform threads (one OS thread each); the result is a field of the Thread object, read after join().
public class Threads {
	static final int WORK = 1000000;
	static final int WAVE = 64;

	static final class Task extends Thread {
		final long id;
		long result;
		Task(long id) { this.id = id; }
		public void run() {
			long s = (id + 1) * 2654435761L, acc = 0;
			for (int i = 0; i < WORK; i++) {
				s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
				acc += s & 255;
			}
			result = acc;
		}
	}

	public static void main(String[] args) throws Exception {
		long n = args.length > 0 ? Long.parseLong(args[0]) : 1024, total = 0;
		for (long base = 0; base < n; base += WAVE) {
			Task[] th = new Task[WAVE];
			for (int j = 0; j < WAVE; j++) { th[j] = new Task(base + j); th[j].start(); }
			for (int j = 0; j < WAVE; j++) { th[j].join(); total += th[j].result; }
		}
		System.out.println("tasks=" + n + " checksum=" + total);
	}
}
