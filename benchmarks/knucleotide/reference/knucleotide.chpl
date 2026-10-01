// k-nucleotide benchmark, see knucleotide.c: hand-written open-addressing table (linear probing, multiplicative hash).
record Slot { var key: uint(64) = 0; var cnt: uint(32) = 0; }
const KS = [1, 2, 3, 4, 6, 12];
const QK = [1, 1, 1, 1, 2, 2, 2, 3, 4, 6, 12];
const QV: [0..<11] uint(64) = [0, 1, 2, 3, 10, 11, 0, 43, 172, 2767, 11337487]: uint(64);
const GOLD: uint(64) = 0x9E3779B97F4A7C15;

proc lookup(const ref tab: [] Slot, kk: uint(64), bits: int, cap: int): uint(64) {
  var h = ((kk * GOLD) >> (64 - bits)): int;
  while true {
    if tab[h].key == 0 then return 0;
    if tab[h].key == kk then return tab[h].cnt: uint(64);
    h = (h + 1) & (cap - 1);
  }
  return 0;
}

proc main(args: [] string) {
  const n: int = if args.size > 1 then args[1]: int else 30000000;
  var last: uint(32) = 42;
  var seq: [0..n] uint(8);
  for i in 0..<n {
    last = (last * 3877: uint(32) + 29573: uint(32)) % 139968: uint(32);
    var c: uint(8);
    if last < 42404 then c = 0; else if last < 70117 then c = 1; else if last < 97767 then c = 2; else c = 3;
    seq[i] = c;
  }
  var first12: uint(64) = 0;
  for i in 0..<min(12, n) do first12 = (first12 << 2) | seq[i]: uint(64);
  var distinct: [0..<6] uint(64);
  var counts: [0..<12] uint(64);
  var nq = 0;
  for ki in 0..<6 {
    const k = KS[ki];
    var maxd = 1 << (2 * k);
    const win = if n >= k then n - k + 1 else 0;
    if win < maxd then maxd = win;
    if maxd > 139968 then maxd = 139968;
    var bits = 1;
    while (1 << bits) < 2 * maxd do bits += 1;
    const cap = 1 << bits;
    var tab: [0..<cap] Slot;
    const mask: uint(64) = (1: uint(64) << (2 * k)) - 1;
    var key: uint(64) = 0, nd: uint(64) = 0;
    const shift = 64 - bits;
    for i in 0..<n {
      key = ((key << 2) | seq[i]: uint(64)) & mask;
      if i + 1 >= k {
        var h = (((key + 1) * GOLD) >> shift): int;
        while true {
          if tab[h].key == 0 { tab[h].key = key + 1; tab[h].cnt = 1; nd += 1; break; }
          if tab[h].key == key + 1 { tab[h].cnt += 1; break; }
          h = (h + 1) & (cap - 1);
        }
      }
    }
    distinct[ki] = nd;
    for q in 0..<11 do if QK[q] == k { counts[nq] = lookup(tab, QV[q] + 1, bits, cap); nq += 1; }
    if k == 12 { counts[nq] = lookup(tab, first12 + 1, bits, cap); nq += 1; }
  }
  for i in 0..<6 do write(distinct[i], " ");
  for i in 0..<12 do write(if i > 0 then " " else "", counts[i]);
  writeln();
}
