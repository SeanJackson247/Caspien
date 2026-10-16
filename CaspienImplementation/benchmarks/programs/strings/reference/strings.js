// String manipulation benchmark (Node / Bun, Uint8Array buffers).
const n = process.argv.length > 2 ? parseInt(process.argv[2]) : 4000000;
let x = 12345;
function next() { x = (Math.imul(x, 1664525) + 1013904223) >>> 0; return x; }
function hash(b, len) { let h = 7; for (let i = 0; i < len; i++) h = (Math.imul(h, 31) + b[i]) >>> 0; return h; }
const text = new Uint8Array(n);
for (let i = 0; i < n; i++) { const r = (next() >>> 16) % 27; text[i] = r === 26 ? 32 : 97 + r; }
let words = 0, longest = 0, cur = 0;
for (let i = 0; i < n; i++) {
  if (text[i] === 32) { if (cur > 0) { words++; if (cur > longest) longest = cur; cur = 0; } } else cur++;
}
if (cur > 0) { words++; if (cur > longest) longest = cur; }
let abc = 0;
for (let i = 0; i + 2 < n; i++) if (text[i] === 97 && text[i + 1] === 98 && text[i + 2] === 99) abc++;
const rev = new Uint8Array(n);
for (let i = 0; i < n; i++) rev[i] = text[n - 1 - i];
const hrev = hash(rev, n);
let es = 0;
for (let i = 0; i < n; i++) if (text[i] === 101) es++;
const m = n + es;
const rep = new Uint8Array(m);
let k = 0;
for (let i = 0; i < n; i++) { if (text[i] === 101) { rep[k++] = 51; rep[k++] = 51; } else rep[k++] = text[i]; }
const hrep = hash(rep, m);
const up = new Uint8Array(n);
for (let i = 0; i < n; i++) up[i] = text[i] === 32 ? 32 : text[i] - 32;
const hup = hash(up, n);
console.log(`${words} ${longest} ${abc} ${hrev} ${m} ${hrep} ${hup}`);
