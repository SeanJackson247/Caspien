// String manipulation benchmark (byte buffers), see strings.c.
var x: uint(32) = 12345;
inline proc next(): uint(32) { x = x * 1664525: uint(32) + 1013904223: uint(32); return x; }
proc hash(const ref b: [] uint(8)): uint(32) {
  var h: uint(32) = 7;
  for c in b do h = h * 31: uint(32) + c: uint(32);
  return h;
}
proc main(args: [] string) {
  const n: int = if args.size > 1 then args[1]: int else 4000000;
  var text: [0..<n] uint(8);
  for i in 0..<n {
    const r = (next() >> 16) % 27;
    text[i] = if r == 26 then 32: uint(8) else (97 + r): uint(8);
  }
  var words = 0, longest = 0, cur = 0;
  for i in 0..<n {
    if text[i] == 32 {
      if cur > 0 { words += 1; if cur > longest then longest = cur; cur = 0; }
    } else cur += 1;
  }
  if cur > 0 { words += 1; if cur > longest then longest = cur; }
  var abc = 0;
  for i in 0..<(n - 2) do
    if text[i] == 97 && text[i + 1] == 98 && text[i + 2] == 99 then abc += 1;
  var rev: [0..<n] uint(8);
  for i in 0..<n do rev[i] = text[n - 1 - i];
  const hrev = hash(rev);
  var es = 0;
  for i in 0..<n do if text[i] == 101 then es += 1;
  const m = n + es;
  var rep: [0..<m] uint(8);
  var k = 0;
  for i in 0..<n {
    if text[i] == 101 { rep[k] = 51; rep[k + 1] = 51; k += 2; } else { rep[k] = text[i]; k += 1; }
  }
  const hrep = hash(rep);
  var up: [0..<n] uint(8);
  for i in 0..<n do up[i] = if text[i] == 32 then 32: uint(8) else (text[i] - 32): uint(8);
  const hup = hash(up);
  writeln(words, " ", longest, " ", abc, " ", hrev, " ", m, " ", hrep, " ", hup);
}
