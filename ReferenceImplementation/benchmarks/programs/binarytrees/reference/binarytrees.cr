# Binary trees: ordinary heap nodes with left/right references, one allocation per node (see binarytrees.c).
class Node
  getter l : Node?
  getter r : Node?

  def initialize(@l : Node?, @r : Node?)
  end

  def check : Int64
    l = @l
    if l
      1_i64 + l.check + @r.not_nil!.check
    else
      1_i64
    end
  end
end

def make(d : Int32) : Node
  if d > 0
    l = make(d - 1)
    r = make(d - 1)
    Node.new(l, r)
  else
    Node.new(nil, nil)
  end
end

maxd = (ARGV[0]? || "16").to_i
maxd = 6 if maxd < 6
t = make(maxd + 1)
print t.check
longlived = make(maxd)
d = 4
while d <= maxd
  iters = 1_i64 << (maxd - d + 4)
  sum = 0_i64
  iters.times do
    a = make(d)
    sum += a.check
  end
  print " ", sum
  d += 2
end
puts " #{longlived.check}"
