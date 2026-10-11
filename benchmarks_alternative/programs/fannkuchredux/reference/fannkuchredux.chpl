// fannkuch-redux, single threaded, see fannkuchredux.c.
proc fannkuchredux(n: int): int {
  var perm, perm1, count: [0..<16] int(32);
  var maxFlipsCount = 0, permCount = 0, checksum = 0;
  var r = n;
  for i in 0..<n do perm1[i] = i: int(32);
  while true {
    while r != 1 { count[r - 1] = r: int(32); r -= 1; }
    for i in 0..<n do perm[i] = perm1[i];
    var flipsCount = 0;
    while true {
      const k = perm[0]: int;
      if k == 0 then break;
      const k2 = (k + 1) >> 1;
      for i in 0..<k2 { const t = perm[i]; perm[i] = perm[k - i]; perm[k - i] = t; }
      flipsCount += 1;
    }
    if flipsCount > maxFlipsCount then maxFlipsCount = flipsCount;
    checksum += if permCount % 2 == 0 then flipsCount else -flipsCount;
    while true {
      if r == n { writeln(checksum); return maxFlipsCount; }
      const perm0 = perm1[0];
      var i = 0;
      while i < r { const j = i + 1; perm1[i] = perm1[j]; i = j; }
      perm1[r] = perm0;
      count[r] -= 1;
      if count[r] > 0 then break;
      r += 1;
    }
    permCount += 1;
  }
  return 0;
}

proc main(args: [] string) {
  const n: int = if args.size > 1 then args[1]: int else 7;
  const flips = fannkuchredux(n);
  writeln("Pfannkuchen(", n, ") = ", flips);
}
