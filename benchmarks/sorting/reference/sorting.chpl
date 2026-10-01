// Sorting and searching benchmark, see sorting.c: quicksort (explicit stack), bottom-up merge sort, heap sort, binary + linear search.
var x: uint(32) = 12345;
inline proc next(): uint(32) { x = x * 1664525: uint(32) + 1013904223: uint(32); return x; }

proc quicksort(ref a: [] int(32), n: int) {
  var stack: [0..<128] int;
  var sp = 0;
  stack[sp] = 0; sp += 1;
  stack[sp] = n - 1; sp += 1;
  while sp > 0 {
    sp -= 1; var hi = stack[sp];
    sp -= 1; var lo = stack[sp];
    while lo < hi {
      const pivot = a[(lo + hi) / 2];
      var i = lo, j = hi;
      while i <= j {
        while a[i] < pivot do i += 1;
        while a[j] > pivot do j -= 1;
        if i <= j { const t = a[i]; a[i] = a[j]; a[j] = t; i += 1; j -= 1; }
      }
      if j - lo < hi - i {
        stack[sp] = i; sp += 1; stack[sp] = hi; sp += 1; hi = j;
      } else {
        stack[sp] = lo; sp += 1; stack[sp] = j; sp += 1; lo = i;
      }
    }
  }
}
proc mergesort(ref a: [] int(32), ref tmp: [] int(32), n: int) {
  var srcIsA = true;
  var w = 1;
  while w < n {
    var lo = 0;
    while lo < n {
      const mid = if lo + w < n then lo + w else n;
      const hi = if lo + 2 * w < n then lo + 2 * w else n;
      var i = lo, j = mid, k = lo;
      if srcIsA {
        while i < mid && j < hi {
          if a[i] <= a[j] { tmp[k] = a[i]; i += 1; } else { tmp[k] = a[j]; j += 1; }
          k += 1;
        }
        while i < mid { tmp[k] = a[i]; k += 1; i += 1; }
        while j < hi { tmp[k] = a[j]; k += 1; j += 1; }
      } else {
        while i < mid && j < hi {
          if tmp[i] <= tmp[j] { a[k] = tmp[i]; i += 1; } else { a[k] = tmp[j]; j += 1; }
          k += 1;
        }
        while i < mid { a[k] = tmp[i]; k += 1; i += 1; }
        while j < hi { a[k] = tmp[j]; k += 1; j += 1; }
      }
      lo += 2 * w;
    }
    srcIsA = !srcIsA;
    w *= 2;
  }
  if !srcIsA then for i in 0..<n do a[i] = tmp[i];
}
proc siftdown(ref a: [] int(32), r: int, n: int) {
  var root = r;
  while true {
    var c = 2 * root + 1;
    if c >= n then break;
    if c + 1 < n && a[c + 1] > a[c] then c += 1;
    if a[root] >= a[c] then break;
    const t = a[root]; a[root] = a[c]; a[c] = t;
    root = c;
  }
}
proc heapsort(ref a: [] int(32), n: int) {
  var i = n / 2 - 1;
  while i >= 0 { siftdown(a, i, n); i -= 1; }
  var e = n - 1;
  while e > 0 {
    const t = a[0]; a[0] = a[e]; a[e] = t;
    siftdown(a, 0, e);
    e -= 1;
  }
}
proc bsearchHas(const ref a: [] int(32), n: int, key: int(32)): bool {
  var lo = 0, hi = n;
  while lo < hi {
    const m = (lo + hi) / 2;
    if a[m] < key then lo = m + 1; else hi = m;
  }
  return lo < n && a[lo] == key;
}

proc main(args: [] string) {
  const n: int = if args.size > 1 then args[1]: int else 2000000;
  var orig: [0..<n] int(32);
  for i in 0..<n do orig[i] = (next() >> 1): int(32);
  var a = orig, b = orig, d = orig;
  var tmp: [0..<n] int(32);
  quicksort(a, n);
  mergesort(b, tmp, n);
  heapsort(d, n);
  var h: uint(32) = 0;
  var ok = true;
  for i in 0..<n {
    h = h * 31: uint(32) + a[i]: uint(32);
    if a[i] != b[i] || a[i] != d[i] then ok = false;
    if i > 0 && a[i - 1] > a[i] then ok = false;
  }
  var bs = 0;
  for k in 0..<n {
    var key = (next() >> 1): int(32);
    if k % 2 == 0 then key = orig[(key: uint(32)): int % n];
    if bsearchHas(a, n, key) then bs += 1;
  }
  var ls = 0;
  for k in 0..<20 {
    var key = (next() >> 1): int(32);
    if k % 2 == 0 then key = orig[(key: uint(32)): int % n];
    for i in 0..<n do if orig[i] == key { ls += 1; break; }
  }
  writeln(h, " ", bs, " ", ls, " ", if ok then "OK" else "BAD");
}
