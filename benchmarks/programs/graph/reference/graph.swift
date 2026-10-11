// Heap graph benchmark, see graph.c: class nodes with 4 outgoing references, BFS from node 0.
import Glibc

final class Node {
    var value: UInt32
    var dist: Int32 = -1
    var e0: Node! = nil, e1: Node! = nil, e2: Node! = nil, e3: Node! = nil
    init(_ v: UInt32) { value = v }
}
var x: UInt32 = 12345
@inline(__always) func next() -> UInt32 { x = x &* 1664525 &+ 1013904223; return x }

let n = CommandLine.arguments.count > 1 ? atol(CommandLine.arguments[1]) : 2000000
var nodes = [Node]()
nodes.reserveCapacity(n)
for _ in 0..<n { nodes.append(Node(next() & 0xFFFFFF)) }
nodes[n * 7 / 10].value = 0xFFFFFFFF
for i in 0..<n {
    let u = nodes[i]
    u.e0 = nodes[(i + 1) % n]
    u.e1 = nodes[Int(next()) % n]
    u.e2 = nodes[Int(next()) % n]
    u.e3 = nodes[Int(next()) % n]
}
var q = [Node]()
q.reserveCapacity(n)
q.append(nodes[0]); nodes[0].dist = 0
var head = 0
var maxdepth: Int32 = 0, needledist: Int32 = -1
var sum: UInt32 = 0
while head < q.count {
    let u = q[head]; head += 1
    sum = sum &+ u.value
    if u.value == 0xFFFFFFFF { needledist = u.dist }
    if u.dist > maxdepth { maxdepth = u.dist }
    var v: Node = u.e0
    if v.dist < 0 { v.dist = u.dist + 1; q.append(v) }
    v = u.e1
    if v.dist < 0 { v.dist = u.dist + 1; q.append(v) }
    v = u.e2
    if v.dist < 0 { v.dist = u.dist + 1; q.append(v) }
    v = u.e3
    if v.dist < 0 { v.dist = u.dist + 1; q.append(v) }
}
print("\(q.count) \(maxdepth) \(needledist) \(sum)")
