// Binary trees, see binarytrees.c: class nodes with left/right references, reference counted.
import Glibc

final class Node {
    let l: Node?
    let r: Node?
    init(_ l: Node?, _ r: Node?) { self.l = l; self.r = r }
}
func make(_ d: Int) -> Node {
    if d > 0 { return Node(make(d - 1), make(d - 1)) }
    return Node(nil, nil)
}
func check(_ n: Node) -> Int {
    if let l = n.l { return 1 + check(l) + check(n.r!) }
    return 1
}
var maxd = CommandLine.arguments.count > 1 ? Int(atoi(CommandLine.arguments[1])) : 16
if maxd < 6 { maxd = 6 }
var out = "\(check(make(maxd + 1)))"
let longlived = make(maxd)
var d = 4
while d <= maxd {
    let iters = 1 << (maxd - d + 4)
    var sum = 0
    for _ in 0..<iters { sum += check(make(d)) }
    out += " \(sum)"
    d += 2
}
out += " \(check(longlived))"
print(out)
