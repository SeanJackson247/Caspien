# Heap graph benchmark: see graph.c. One heap object per node.
module Rng
  @@x = 12345_u32

  def self.next : UInt32
    @@x = @@x &* 1664525_u32 &+ 1013904223_u32
    @@x
  end
end

class Node
  property value : UInt32
  property dist : Int32
  @e : StaticArray(Node, 4)

  def initialize(@value : UInt32)
    @dist = -1
    @e = uninitialized StaticArray(Node, 4)
    4.times { |k| @e[k] = self }
  end

  def edge(k : Int32) : Node
    @e[k]
  end

  def set_edge(k : Int32, v : Node)
    @e[k] = v
  end
end

n = (ARGV[0]? || "2000000").to_i64
nodes = Array(Node).new(n.to_i32)
n.times { nodes << Node.new(Rng.next & 0xFFFFFF_u32) }
nodes[n * 7 // 10].value = 0xFFFFFFFF_u32
n.times do |i|
  u = nodes[i]
  u.set_edge(0, nodes[(i + 1) % n])
  1.upto(3) { |k| u.set_edge(k, nodes[Rng.next % n]) }
end
q = Array(Node).new(n.to_i32, nodes[0])
head = 0_i64
tail = 1_i64
nodes[0].dist = 0
maxdepth = 0
needledist = -1
sum = 0_u32
while head < tail
  u = q[head]
  head += 1
  sum &+= u.value
  needledist = u.dist if u.value == 0xFFFFFFFF_u32
  maxdepth = u.dist if u.dist > maxdepth
  4.times do |k|
    v = u.edge(k)
    if v.dist < 0
      v.dist = u.dist + 1
      q[tail] = v
      tail += 1
    end
  end
end
puts "#{tail} #{maxdepth} #{needledist} #{sum}"
