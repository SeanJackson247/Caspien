// Heap graph benchmark, see graph.c: heap class nodes with 4 outgoing references, BFS from node 0.
class Node {
  var value: uint(32);
  var dist: int(32) = -1;
  var e0, e1, e2, e3: unmanaged Node?;
}
var x: uint(32) = 12345;
inline proc next(): uint(32) { x = x * 1664525: uint(32) + 1013904223: uint(32); return x; }

proc main(args: [] string) {
  const n: int = if args.size > 1 then args[1]: int else 2000000;
  var nodes: [0..<n] unmanaged Node?;
  for i in 0..<n {
    var nd = new unmanaged Node();
    nd.value = next() & 0xFFFFFF;
    nodes[i] = nd;
  }
  nodes[n * 7 / 10]!.value = 0xFFFFFFFF: uint(32);
  for i in 0..<n {
    var u = nodes[i]!;
    u.e0 = nodes[(i + 1) % n];
    u.e1 = nodes[(next(): int) % n];
    u.e2 = nodes[(next(): int) % n];
    u.e3 = nodes[(next(): int) % n];
  }
  var q: [0..<n] unmanaged Node?;
  var head = 0, tail = 0;
  q[tail] = nodes[0]; tail += 1;
  nodes[0]!.dist = 0;
  var maxdepth: int(32) = 0, needledist: int(32) = -1;
  var sum: uint(32) = 0;
  while head < tail {
    const u = q[head]!; head += 1;
    sum += u.value;
    if u.value == 0xFFFFFFFF: uint(32) then needledist = u.dist;
    if u.dist > maxdepth then maxdepth = u.dist;
    var v = u.e0!;
    if v.dist < 0 { v.dist = u.dist + 1; q[tail] = v; tail += 1; }
    v = u.e1!;
    if v.dist < 0 { v.dist = u.dist + 1; q[tail] = v; tail += 1; }
    v = u.e2!;
    if v.dist < 0 { v.dist = u.dist + 1; q[tail] = v; tail += 1; }
    v = u.e3!;
    if v.dist < 0 { v.dist = u.dist + 1; q[tail] = v; tail += 1; }
  }
  writeln(tail, " ", maxdepth, " ", needledist, " ", sum);
  for i in 0..<n do delete nodes[i]!;
}
