# Heap graph benchmark (port of graph.c): N heap-allocated nodes (ref objects) with 4 outgoing references, BFS from node 0.
import std/[os, strutils]

var x: uint32 = 12345
proc next(): uint32 =
  x = x * 1664525'u32 + 1013904223'u32
  x

type
  Node = ref object
    value: uint32
    dist: int32
    e: array[4, Node]

let n = if paramCount() > 0: parseInt(paramStr(1)) else: 2_000_000
var nodes = newSeq[Node](n)
for i in 0 ..< n:
  nodes[i] = Node(value: next() and 0xFFFFFF'u32, dist: -1)
nodes[n * 7 div 10].value = 0xFFFFFFFF'u32
for i in 0 ..< n:
  nodes[i].e[0] = nodes[(i + 1) mod n]
  for k in 1 .. 3: nodes[i].e[k] = nodes[int(uint64(next()) mod uint64(n))]
var q = newSeq[Node](n)
var head, tail = 0
q[tail] = nodes[0]; inc tail
nodes[0].dist = 0
var maxdepth, needledist = 0'i32
needledist = -1
var sum: uint32 = 0
while head < tail:
  let u = q[head]; inc head
  sum += u.value
  if u.value == 0xFFFFFFFF'u32: needledist = u.dist
  if u.dist > maxdepth: maxdepth = u.dist
  for k in 0 .. 3:
    let v = u.e[k]
    if v.dist < 0:
      v.dist = u.dist + 1
      q[tail] = v; inc tail
echo tail, " ", maxdepth, " ", needledist, " ", sum
