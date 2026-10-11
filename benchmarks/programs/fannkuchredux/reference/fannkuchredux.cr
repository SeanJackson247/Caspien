# fannkuch-redux, single threaded: see fannkuchredux.c.
def fannkuchredux(n : Int32) : Int32
  perm = StaticArray(Int32, 16).new(0)
  perm1 = StaticArray(Int32, 16).new(0)
  count = StaticArray(Int32, 16).new(0)
  max_flips = 0
  perm_count = 0
  checksum = 0
  r = n
  n.times { |i| perm1[i] = i }
  while true
    while r != 1
      count[r - 1] = r
      r -= 1
    end
    n.times { |i| perm[i] = perm1[i] }
    flips = 0
    while (k = perm[0]) != 0
      k2 = (k + 1) >> 1
      k2.times do |i|
        t = perm[i]
        perm[i] = perm[k - i]
        perm[k - i] = t
      end
      flips += 1
    end
    max_flips = flips if flips > max_flips
    checksum += perm_count % 2 == 0 ? flips : -flips
    while true
      if r == n
        puts checksum
        return max_flips
      end
      perm0 = perm1[0]
      i = 0
      while i < r
        j = i + 1
        perm1[i] = perm1[j]
        i = j
      end
      perm1[r] = perm0
      count[r] -= 1
      break if count[r] > 0
      r += 1
    end
    perm_count += 1
  end
  0
end

n = (ARGV[0]? || "7").to_i
f = fannkuchredux(n)
puts "Pfannkuchen(#{n}) = #{f}"
