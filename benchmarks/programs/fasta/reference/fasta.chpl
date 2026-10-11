// FASTA benchmark, see fasta.c: all output goes into one byte buffer, then a checksum and letter counts are printed.
const ALU = b"GGCCGGGCGCGGTGGCTCACGCCTGTAATCCCAGCACTTTGGGAGGCCGAGGCGGGCGGATCACCTGAGGTCAGGAGTTCGAGACCAGCCTGGCCAACATGGTGAAACCCCGTCTCTACTAAAAATACAAAAATTAGCCGGGCGTGGTGGCGCGCGCCTGTAATCCCAGCTACTCGGGAGGCTGAGGCAGGAGAATCGCTTGAACCCGGGAGGCGGAGGTTGCAGTGAGCCGAGATCGCGCCACTGCACTCCAGCCTGGGCGACAGAGCGAGACTCCGTCTCAAAAA";
const CHARS = b"ACGTBDHKMNRSVWYACGT";
const THR: [0..<19] int = [37792,54588,71384,109176,111975,114774,117574,120373,123172,125972,128771,131570,134370,137169,139968,42404,70117,97767,139968];
const HDR = [b">ONE Homo sapiens alu\n", b">TWO IUB ambiguity codes\n", b">THREE Homo sapiens frequency\n"];

proc main(args: [] string) {
  const n: int = if args.size > 1 then args[1]: int else 40000000;
  const cnt0 = n * 2 / 10, cnt1 = n * 3 / 10;
  const cnt = [cnt0, cnt1, n - cnt0 - cnt1];
  const off = [0, 0, 15];
  var buf: [0..<(n + n / 60 + 1024)] uint(8);
  var last: uint(32) = 42;
  var pos = 0, a = 0, c = 0, g = 0, t = 0, other = 0, ai = 0;
  for s in 0..2 {
    const hdr = HDR[s];
    for k in 0..<hdr.size { buf[pos] = hdr[k]; pos += 1; }
    var col = 0;
    for 1..cnt[s] {
      var ch: uint(8);
      if s == 0 {
        ch = ALU[ai];
        ai += 1;
        if ai == 287 then ai = 0;
      } else {
        last = (last * 3877: uint(32) + 29573: uint(32)) % 139968: uint(32);
        var j = off[s];
        while last >= THR[j] do j += 1;
        ch = CHARS[j];
      }
      buf[pos] = ch; pos += 1;
      if ch == 65 then a += 1;
      else if ch == 67 then c += 1;
      else if ch == 71 then g += 1;
      else if ch == 84 then t += 1;
      else other += 1;
      col += 1;
      if col == 60 { buf[pos] = 10; pos += 1; col = 0; }
    }
    if col > 0 { buf[pos] = 10; pos += 1; }
  }
  var h: uint(32) = 7;
  for i in 0..<pos do h = h * 31: uint(32) + buf[i]: uint(32);
  writeln(pos, " ", h, " ", a, " ", c, " ", g, " ", t, " ", other);
}
