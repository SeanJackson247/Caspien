// LRU cache benchmark, see lru.c: chained hash buckets threaded through the node pool + index-based doubly linked recency list.
param CAP = 262144;
param KEYS: uint(32) = 1048576;
param HOT: uint(32) = 131072;
param NB = 524288;
param NONE: uint(32) = 0xFFFFFFFF;
var x: uint(32) = 12345;
inline proc next(): uint(32) { x = x * 1664525: uint(32) + 1013904223: uint(32); return x; }

var key, val, prv, nxt, hn: [0..<CAP] uint(32);
var bucket: [0..<NB] uint(32);
var head: uint(32) = NONE, tail: uint(32) = NONE;

inline proc hsh(k: uint(32)): int { return ((k * 2654435761: uint(32)) >> 13): int; }
proc unlinkNode(i: uint(32)) {
  if prv[i] != NONE then nxt[prv[i]] = nxt[i]; else head = nxt[i];
  if nxt[i] != NONE then prv[nxt[i]] = prv[i]; else tail = prv[i];
}
proc pushFront(i: uint(32)) {
  prv[i] = NONE; nxt[i] = head;
  if head != NONE then prv[head] = i; else tail = i;
  head = i;
}
proc find(k: uint(32)): uint(32) {
  var i = bucket[hsh(k)];
  while i != NONE && key[i] != k do i = hn[i];
  return i;
}
proc hashRemove(i: uint(32)) {
  const b = hsh(key[i]);
  if bucket[b] == i { bucket[b] = hn[i]; return; }
  var j = bucket[b];
  while hn[j] != i do j = hn[j];
  hn[j] = hn[i];
}

proc main(args: [] string) {
  const n: int = if args.size > 1 then args[1]: int else 20000000;
  bucket = NONE;
  var size: uint(32) = 0;
  var hits: uint(64) = 0, misses: uint(64) = 0, sum: uint(64) = 0;
  for 1..n {
    const a = next(), y = next();
    const rng = if (y >> 20) % 4 == 0 then KEYS else HOT;
    const k = (a >> 8) % rng;
    var i = find(k);
    if (y >> 24) % 4 != 0 {
      if i != NONE {
        hits += 1; sum += val[i]: uint(64) + k: uint(64); unlinkNode(i); pushFront(i);
      } else misses += 1;
    } else if i != NONE {
      val[i] = y; unlinkNode(i); pushFront(i);
    } else {
      if size == CAP {
        i = tail; sum += key[i]: uint(64); unlinkNode(i); hashRemove(i);
      } else { i = size; size += 1; }
      key[i] = k; val[i] = y;
      const b = hsh(k); hn[i] = bucket[b]; bucket[b] = i;
      pushFront(i);
    }
  }
  var fin: uint(64) = 0;
  var i = head;
  while i != NONE { fin = (fin * 31 + key[i]: uint(64)) % 4294967296; i = nxt[i]; }
  writeln(hits, " ", misses, " ", (sum + fin) % 4294967296);
}
