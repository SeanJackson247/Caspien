// Binary trees, see binarytrees.c: heap class nodes with left/right references.
class Node {
  var l, r: unmanaged Node?;
}
proc make(d: int): unmanaged Node {
  var n = new unmanaged Node();
  if d > 0 { n.l = make(d - 1); n.r = make(d - 1); }
  return n;
}
proc check(n: borrowed Node): int {
  if n.l != nil then return 1 + check(n.l!) + check(n.r!);
  return 1;
}
proc release(n: unmanaged Node) {
  if n.l != nil { release(n.l!); release(n.r!); }
  delete n;
}
proc main(args: [] string) {
  var maxd: int = if args.size > 1 then args[1]: int else 16;
  if maxd < 6 then maxd = 6;
  var t = make(maxd + 1);
  write(check(t));
  release(t);
  var longlived = make(maxd);
  var d = 4;
  while d <= maxd {
    const iters = 1 << (maxd - d + 4);
    var sum = 0;
    for 1..iters {
      var a = make(d);
      sum += check(a);
      release(a);
    }
    write(" ", sum);
    d += 2;
  }
  writeln(" ", check(longlived));
  release(longlived);
}
