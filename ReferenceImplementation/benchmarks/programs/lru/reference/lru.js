// LRU cache benchmark (Node / Bun): the natural idiom, a Map (insertion ordered: first key = least recently used; delete + set on access).
const n = process.argv.length > 2 ? parseInt(process.argv[2]) : 20000000;
const CAP = 262144, KEYS = 1048576, HOT = 131072;
let x = 12345;
function next() { x = (Math.imul(x, 1664525) + 1013904223) >>> 0; return x; }
const m = new Map();
let hits = 0, misses = 0, sum = 0;
for (let op = 0; op < n; op++) {
  const a = next(), y = next();
  const range = (((y >>> 20) % 4) === 0) ? KEYS : HOT;
  const k = (a >>> 8) % range;
  const v = m.get(k);
  if (((y >>> 24) % 4) !== 0) {
    if (v !== undefined) { hits++; sum = (sum + v + k) % 4294967296; m.delete(k); m.set(k, v); }
    else misses++;
  } else if (v !== undefined) {
    m.delete(k); m.set(k, y);
  } else {
    if (m.size === CAP) {
      const ek = m.keys().next().value;
      sum = (sum + ek) % 4294967296;
      m.delete(ek);
    }
    m.set(k, y);
  }
}
let fin = 0;
const keys = Array.from(m.keys());
for (let i = keys.length - 1; i >= 0; i--) fin = (fin * 31 + keys[i]) % 4294967296;
console.log(`${hits} ${misses} ${(sum + fin) % 4294967296}`);
