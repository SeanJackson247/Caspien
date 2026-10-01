# Binary trees (port of binarytrees.c): one heap-allocated ref object per node, left/right references.
import std/[os, strutils]

type Node = ref object
  l, r: Node

proc make(d: int): Node =
  result = Node()
  if d > 0:
    result.l = make(d - 1)
    result.r = make(d - 1)

proc check(n: Node): int =
  if n.l != nil: 1 + check(n.l) + check(n.r) else: 1

var maxd = if paramCount() > 0: parseInt(paramStr(1)) else: 16
if maxd < 6: maxd = 6
var t = make(maxd + 1)
stdout.write check(t)
t = nil
let longlived = make(maxd)
var d = 4
while d <= maxd:
  let iters = 1 shl (maxd - d + 4)
  var sum = 0
  for i in 0 ..< iters:
    let a = make(d)
    sum += check(a)
  stdout.write " ", sum
  d += 2
stdout.write " ", check(longlived), "\n"
