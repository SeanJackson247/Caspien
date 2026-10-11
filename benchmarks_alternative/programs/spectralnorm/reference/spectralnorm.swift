// spectral-norm, single threaded, see spectralnorm.c.
import Glibc

@inline(__always) func evalA(_ i: Int, _ j: Int) -> Double { return 1.0 / Double((i + j) * (i + j + 1) / 2 + i + 1) }

func evalAtimesU(_ n: Int, _ u: [Double], _ au: inout [Double]) {
    for i in 0..<n {
        var s = 0.0
        for j in 0..<n { s += evalA(i, j) * u[j] }
        au[i] = s
    }
}
func evalAtTimesU(_ n: Int, _ u: [Double], _ au: inout [Double]) {
    for i in 0..<n {
        var s = 0.0
        for j in 0..<n { s += evalA(j, i) * u[j] }
        au[i] = s
    }
}
func evalAtAtimesU(_ n: Int, _ u: [Double], _ atau: inout [Double]) {
    var v = [Double](repeating: 0, count: n)
    evalAtimesU(n, u, &v)
    evalAtTimesU(n, v, &atau)
}

let n = CommandLine.arguments.count > 1 ? Int(atoi(CommandLine.arguments[1])) : 100
var u = [Double](repeating: 1, count: n)
var v = [Double](repeating: 0, count: n)
for _ in 0..<10 { evalAtAtimesU(n, u, &v); evalAtAtimesU(n, v, &u) }
var vBv = 0.0, vv = 0.0
for i in 0..<n { vBv += u[i] * v[i]; vv += v[i] * v[i] }
_ = withVaList([(vBv / vv).squareRoot()]) { vprintf("%0.9f\n", $0) }
