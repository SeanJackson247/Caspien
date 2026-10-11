// spectral-norm, single threaded, see spectralnorm.c.
use IO, Math;

inline proc evalA(i: int, j: int): real { return 1.0 / ((i + j) * (i + j + 1) / 2 + i + 1): real; }

proc evalAtimesU(n: int, const ref u: [] real, ref au: [] real) {
  for i in 0..<n {
    var s = 0.0;
    for j in 0..<n do s += evalA(i, j) * u[j];
    au[i] = s;
  }
}
proc evalAtTimesU(n: int, const ref u: [] real, ref au: [] real) {
  for i in 0..<n {
    var s = 0.0;
    for j in 0..<n do s += evalA(j, i) * u[j];
    au[i] = s;
  }
}
proc evalAtAtimesU(n: int, const ref u: [] real, ref atau: [] real) {
  var v: [0..<n] real;
  evalAtimesU(n, u, v);
  evalAtTimesU(n, v, atau);
}

proc main(args: [] string) {
  const n: int = if args.size > 1 then args[1]: int else 100;
  var u: [0..<n] real = 1.0;
  var v: [0..<n] real;
  for 1..10 { evalAtAtimesU(n, u, v); evalAtAtimesU(n, v, u); }
  var vBv = 0.0, vv = 0.0;
  for i in 0..<n { vBv += u[i] * v[i]; vv += v[i] * v[i]; }
  writef("%.9dr\n", sqrt(vBv / vv));
}
