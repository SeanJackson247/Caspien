// JSON serialise + parse benchmark, see json_serde.c: hand-written serialiser and index-based parser over a byte buffer.
record Rec { var id: uint(64); var nameOff: int; var nameLen: int; var score: uint(64); var tagOff: int; var tagCnt: int; }
var x: uint(32) = 12345;
inline proc next(): uint(32) { x = x * 1664525: uint(32) + 1013904223: uint(32); return x; }

proc wnum(ref t: [] uint(8), p: int, v0: uint(64)): int {
  var d = 1, u = v0;
  while u >= 10 { u /= 10; d += 1; }
  var v = v0;
  var k = d - 1;
  while k >= 0 { t[p + k] = (48 + v % 10): uint(8); v /= 10; k -= 1; }
  return p + d;
}
proc wlit(ref t: [] uint(8), p0: int, s: bytes): int {
  var p = p0;
  for c in s { t[p] = c; p += 1; }
  return p;
}

proc main(args: [] string) {
  const n: int = if args.size > 1 then args[1]: int else 4000000;
  var text: [0..<(n * 80 + 16)] uint(8);
  var p = 0;
  text[p] = 91; p += 1;
  for i in 0..<n {
    if i > 0 { text[p] = 44; p += 1; }
    p = wlit(text, p, b"{\"id\":"); p = wnum(text, p, i: uint(64));
    p = wlit(text, p, b",\"name\":\"");
    const nl = 3 + ((next() >> 16) % 8): int;
    for 0..<nl { text[p] = (97 + (next() >> 16) % 26): uint(8); p += 1; }
    p = wlit(text, p, b"\",\"score\":");
    const cents: uint(64) = ((next() >> 8) % 1000000): uint(64);
    p = wnum(text, p, cents / 100); text[p] = 46; p += 1;
    text[p] = (48 + cents % 100 / 10): uint(8); p += 1;
    text[p] = (48 + cents % 10): uint(8); p += 1;
    p = wlit(text, p, b",\"tags\":[");
    const tc = ((next() >> 16) % 5): int;
    for k in 0..<tc {
      if k > 0 { text[p] = 44; p += 1; }
      p = wnum(text, p, ((next() >> 16) % 100): uint(64));
    }
    p = wlit(text, p, b"]}");
  }
  text[p] = 93; p += 1;
  const tlen = p;
  // ---- parse ----
  var recs: [0..<n] Rec;
  var names: [0..<(n * 10 + 8)] uint(8);
  var tags: [0..<(n * 4 + 8)] uint(8);
  var count = 0, noff = 0, toff = 0, q = 1;
  while true {
    const c = text[q];
    if c == 93 then break;
    if c == 44 { q += 1; continue; }
    q += 1;
    var r = new Rec(0, 0, 0, 0, 0, 0);
    while true {
      q += 1;
      const k0 = text[q];
      while text[q] != 34 do q += 1;
      q += 2;
      if k0 == 105 {
        var v: uint(64) = 0;
        while text[q] >= 48 && text[q] <= 57 { v = v * 10 + (text[q] - 48): uint(64); q += 1; }
        r.id = v;
      } else if k0 == 110 {
        q += 1;
        r.nameOff = noff;
        while text[q] != 34 { names[noff] = text[q]; noff += 1; q += 1; }
        r.nameLen = noff - r.nameOff;
        q += 1;
      } else if k0 == 115 {
        var v: uint(64) = 0;
        while text[q] >= 48 && text[q] <= 57 { v = v * 10 + (text[q] - 48): uint(64); q += 1; }
        q += 1;
        v = v * 100 + (text[q] - 48): uint(64) * 10 + (text[q + 1] - 48): uint(64); q += 2;
        r.score = v;
      } else {
        q += 1;
        r.tagOff = toff;
        while text[q] != 93 {
          if text[q] == 44 then q += 1;
          var v: uint(64) = 0;
          while text[q] >= 48 && text[q] <= 57 { v = v * 10 + (text[q] - 48): uint(64); q += 1; }
          tags[toff] = v: uint(8); toff += 1;
        }
        r.tagCnt = toff - r.tagOff;
        q += 1;
      }
      if text[q] == 44 then q += 1; else { q += 1; break; }
    }
    recs[count] = r; count += 1;
  }
  var h: uint(32) = 7;
  for i in 0..<count {
    const r = recs[i];
    h = h * 31: uint(32) + r.id: uint(32);
    h = h * 31: uint(32) + r.score: uint(32);
    h = h * 31: uint(32) + r.nameLen: uint(32);
    for k in 0..<r.nameLen do h = h * 31: uint(32) + names[r.nameOff + k]: uint(32);
    h = h * 31: uint(32) + r.tagCnt: uint(32);
    for k in 0..<r.tagCnt do h = h * 31: uint(32) + tags[r.tagOff + k]: uint(32);
  }
  writeln(count, " ", tlen, " ", h);
}
