// k-nucleotide benchmark: same sequence, k-mers and output as knucleotide.c (see there), but the counting uses the language's own hash map keyed by the packed 2-bit k-mer.
// Node / Bun: Map keyed by the packed number (keys fit in 24 bits, so they are small integers).
const KS = [1, 2, 3, 4, 6, 12];
const QK = [1, 1, 1, 1, 2, 2, 2, 3, 4, 6, 12];
const QV = [0, 1, 2, 3, 10, 11, 0, 43, 172, 2767, 11337487];
const n = process.argv.length > 2 ? parseInt(process.argv[2]) : 30000000;
let last = 42;
const seq = new Uint8Array(n + 1);
for (let i = 0; i < n; i++) {
  last = (last * 3877 + 29573) % 139968;
  seq[i] = last < 42404 ? 0 : last < 70117 ? 1 : last < 97767 ? 2 : 3;
}
let first12 = 0;
for (let i = 0; i < 12 && i < n; i++) first12 = first12 * 4 + seq[i];
const distinct = [], counts = [];
for (let ki = 0; ki < 6; ki++) {
  const k = KS[ki];
  const m = new Map();
  const mod = Math.pow(4, k);
  let key = 0;
  for (let i = 0; i < n; i++) {
    key = (key * 4 + seq[i]) % mod;
    if (i + 1 >= k) { const v = m.get(key); m.set(key, v === undefined ? 1 : v + 1); }
  }
  distinct.push(m.size);
  for (let q = 0; q < 11; q++) {
    if (QK[q] !== k) continue;
    const v = m.get(QV[q]);
    counts.push(v === undefined ? 0 : v);
  }
  if (k === 12) { const v = m.get(first12); counts.push(v === undefined ? 0 : v); }
}
console.log(distinct.join(" ") + " " + counts.join(" "));
