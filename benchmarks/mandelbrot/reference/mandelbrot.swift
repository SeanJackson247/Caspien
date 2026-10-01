// Mandelbrot escape-time benchmark, see mandelbrot.c.
import Glibc

let n = CommandLine.arguments.count > 1 ? atol(CommandLine.arguments[1]) : 3000
let dn = Double(n)
var inside = 0, total = 0
for y in 0..<n {
    let ci = 2.0 * Double(y) / dn - 1.0
    for x in 0..<n {
        let cr = 2.0 * Double(x) / dn - 1.5
        var zr = 0.0, zi = 0.0, tr = 0.0, ti = 0.0
        var i = 0
        while i < 100 && tr + ti <= 4.0 {
            zi = 2.0 * zr * zi + ci
            zr = tr - ti + cr
            tr = zr * zr
            ti = zi * zi
            i += 1
        }
        total += i
        if i == 100 { inside += 1 }
    }
}
print("\(inside) \(total)")
