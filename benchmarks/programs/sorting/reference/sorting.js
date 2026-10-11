// Sorting and searching benchmark (JavaScript): same algorithms as sorting.c, on Int32Array.
let x = 12345;
function next() { x = (Math.imul(x, 1664525) + 1013904223) | 0; return x >>> 0; }

function quicksort(a, n) {
  const stack = new Array(128); let sp = 0;
  stack[sp++] = 0; stack[sp++] = n - 1;
  while (sp > 0) {
    let hi = stack[--sp], lo = stack[--sp];
    while (lo < hi) {
      const pivot = a[Math.floor((lo + hi) / 2)];
      let i = lo, j = hi;
      while (i <= j) {
        while (a[i] < pivot) i++;
        while (a[j] > pivot) j--;
        if (i <= j) { const t = a[i]; a[i] = a[j]; a[j] = t; i++; j--; }
      }
      if (j - lo < hi - i) { stack[sp++] = i; stack[sp++] = hi; hi = j; }
      else { stack[sp++] = lo; stack[sp++] = j; lo = i; }
    }
  }
}
function mergesort(a, tmp, n) {
  let src = a, dst = tmp;
  for (let w = 1; w < n; w *= 2) {
    for (let lo = 0; lo < n; lo += 2 * w) {
      const mid = Math.min(lo + w, n), hi = Math.min(lo + 2 * w, n);
      let i = lo, j = mid, k = lo;
      while (i < mid && j < hi) dst[k++] = src[i] <= src[j] ? src[i++] : src[j++];
      while (i < mid) dst[k++] = src[i++];
      while (j < hi) dst[k++] = src[j++];
    }
    const t = src; src = dst; dst = t;
  }
  if (src !== a) a.set(src);
}
function siftdown(a, root, n) {
  for (;;) {
    let c = 2 * root + 1;
    if (c >= n) break;
    if (c + 1 < n && a[c + 1] > a[c]) c++;
    if (a[root] >= a[c]) break;
    const t = a[root]; a[root] = a[c]; a[c] = t;
    root = c;
  }
}
function heapsort(a, n) {
  for (let i = Math.floor(n / 2) - 1; i >= 0; i--) siftdown(a, i, n);
  for (let e = n - 1; e > 0; e--) { const t = a[0]; a[0] = a[e]; a[e] = t; siftdown(a, 0, e); }
}
function bsearchHas(a, n, key) {
  let lo = 0, hi = n;
  while (lo < hi) { const m = (lo + hi) >>> 1; if (a[m] < key) lo = m + 1; else hi = m; }
  return lo < n && a[lo] === key;
}
const n = process.argv.length > 2 ? parseInt(process.argv[2]) : 2000000;
const orig = new Int32Array(n), a = new Int32Array(n), b = new Int32Array(n), d = new Int32Array(n), tmp = new Int32Array(n);
for (let i = 0; i < n; i++) orig[i] = next() >>> 1;
a.set(orig); b.set(orig); d.set(orig);
quicksort(a, n);
mergesort(b, tmp, n);
heapsort(d, n);
let h = 0, ok = true;
for (let i = 0; i < n; i++) { h = (Math.imul(h, 31) + a[i]) | 0; if (a[i] !== b[i] || a[i] !== d[i]) ok = false; if (i > 0 && a[i - 1] > a[i]) ok = false; }
let bs = 0;
for (let k = 0; k < n; k++) {
  let key = next() >>> 1;
  if (k % 2 === 0) key = orig[key % n];
  if (bsearchHas(a, n, key)) bs++;
}
let ls = 0;
for (let k = 0; k < 20; k++) {
  let key = next() >>> 1;
  if (k % 2 === 0) key = orig[key % n];
  for (let i = 0; i < n; i++) if (orig[i] === key) { ls++; break; }
}
console.log((h >>> 0) + " " + bs + " " + ls + " " + (ok ? "OK" : "BAD"));
