using System;
public static class P {
  public static void Main(string[] a) {
    long n = a.Length > 0 ? long.Parse(a[0]) : 100000000L;
    byte[] f = new byte[n + 1];
    for (long i = 0; i <= n; i++) f[i] = 1;
    for (long i = 2; i * i <= n; i++) if (f[i] != 0) for (long j = i * i; j <= n; j += i) f[j] = 0;
    long count = 0;
    for (long i = 2; i <= n; i++) count += f[i];
    Console.WriteLine(count);
  }
}
