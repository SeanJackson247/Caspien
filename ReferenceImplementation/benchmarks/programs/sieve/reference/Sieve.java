// Sieve of Eratosthenes (Java).
public class Sieve {
    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 100000000;
        byte[] f = new byte[n + 1];
        java.util.Arrays.fill(f, (byte) 1);
        for (long i = 2; i * i <= n; i++)
            if (f[(int) i] != 0)
                for (long j = i * i; j <= n; j += i) f[(int) j] = 0;
        long count = 0;
        for (int i = 2; i <= n; i++) count += f[i];
        System.out.println(count);
    }
}
