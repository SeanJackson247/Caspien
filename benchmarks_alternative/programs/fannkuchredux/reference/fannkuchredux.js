// fannkuch-redux, single threaded. Same algorithm as fannkuchredux-gcc-1 of the Computer Language Benchmarks Game.
function fannkuchredux(n) {
  const perm = new Int32Array(16), perm1 = new Int32Array(16), count = new Int32Array(16);
  let maxFlips = 0, permCount = 0, checksum = 0;
  for (let i = 0; i < n; i++) perm1[i] = i;
  let r = n;
  for (;;) {
    while (r !== 1) { count[r - 1] = r; r--; }
    for (let i = 0; i < n; i++) perm[i] = perm1[i];
    let flips = 0, k;
    while ((k = perm[0]) !== 0) {
      const k2 = (k + 1) >> 1;
      for (let i = 0; i < k2; i++) { const t = perm[i]; perm[i] = perm[k - i]; perm[k - i] = t; }
      flips++;
    }
    if (flips > maxFlips) maxFlips = flips;
    checksum += permCount % 2 === 0 ? flips : -flips;
    for (;;) {
      if (r === n) { console.log(String(checksum)); return maxFlips; }
      const perm0 = perm1[0];
      let i = 0;
      while (i < r) { const j = i + 1; perm1[i] = perm1[j]; i = j; }
      perm1[r] = perm0;
      count[r]--;
      if (count[r] > 0) break;
      r++;
    }
    permCount++;
  }
}
const n = process.argv.length > 2 ? parseInt(process.argv[2], 10) : 7;
console.log("Pfannkuchen(" + n + ") = " + fannkuchredux(n));
