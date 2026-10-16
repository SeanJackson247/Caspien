// Sieve of Eratosthenes: count the primes <= N (byte per number).
proc main(args: [] string) {
  const n: int = if args.size > 1 then args[1]: int else 100000000;
  var f: [0..n] uint(8);
  f = 1;
  var i = 2;
  while i * i <= n {
    if f[i] != 0 {
      var j = i * i;
      while j <= n { f[j] = 0; j += i; }
    }
    i += 1;
  }
  var count = 0;
  for k in 2..n do count += f[k]: int;
  writeln(count);
}
