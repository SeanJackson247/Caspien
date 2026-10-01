// 64-bit acc as two uint32 halves (JS has no fast u64): acc = acc*31 ^ i, i < 2^32.
const depth = +process.argv[2], reps = +process.argv[3], lp = process.argv[4] === 'loop';
const M = 4294967296;
function chain(hi, lo, i, n) {
  if (i >= n) return [hi, lo];
  const p = lo * 31; const c = Math.floor(p / M);
  const nlo = ((p % M) ^ i) >>> 0; const nhi = (hi * 31 + c) % M;
  return chain(nhi, nlo, i + 1, n);
}
function chainLoop(hi, lo, i, n) {
  for (; i < n; i++) { const p = lo * 31; const c = Math.floor(p / M); lo = ((p % M) ^ i) >>> 0; hi = (hi * 31 + c) % M; }
  return [hi, lo];
}
let th = 0, tl = 0;
for (let r = 0; r < reps; r++) { const v = lp ? chainLoop(Math.floor(r / M), r % M, 0, depth) : chain(Math.floor(r / M), r % M, 0, depth); th = (th ^ v[0]) >>> 0; tl = (tl ^ v[1]) >>> 0; }
console.log((BigInt(th) * 4294967296n + BigInt(tl)).toString());
