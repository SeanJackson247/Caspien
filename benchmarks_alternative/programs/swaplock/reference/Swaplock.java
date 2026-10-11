// swaplock: see swaplock.c. Lock: java.util.concurrent.locks.ReentrantLock, on platform threads.
import java.util.concurrent.locks.ReentrantLock;

public class Swaplock {
	static final int THREADS = 32;
	static final ReentrantLock lock = new ReentrantLock();
	static long count = 0, sum = 0;

	public static void main(String[] args) throws Exception {
		final long k = args.length > 0 ? Long.parseLong(args[0]) : 500000;
		Thread[] th = new Thread[THREADS];
		for (int j = 0; j < THREADS; j++) {
			final long id = j;
			th[j] = new Thread(() -> {
				for (long i = 0; i < k; i++) {
					lock.lock();
					try { count += 1; sum += id + (i % 7); } finally { lock.unlock(); }
				}
			});
			th[j].start();
		}
		for (Thread t : th) t.join();
		System.out.println("count=" + count + " sum=" + sum);
	}
}
