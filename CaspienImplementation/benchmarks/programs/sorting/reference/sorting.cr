# Sorting and searching benchmark: see sorting.c.
module Rng
  @@x = 12345_u32

  def self.next : UInt32
    @@x = @@x &* 1664525_u32 &+ 1013904223_u32
    @@x
  end
end

def quicksort(a : Array(Int32), n : Int64)
  stack = StaticArray(Int64, 128).new(0_i64)
  sp = 0
  stack[sp] = 0_i64; sp += 1
  stack[sp] = n - 1; sp += 1
  while sp > 0
    sp -= 1; hi = stack[sp]
    sp -= 1; lo = stack[sp]
    while lo < hi
      pivot = a[(lo + hi) // 2]
      i = lo
      j = hi
      while i <= j
        while a[i] < pivot
          i += 1
        end
        while a[j] > pivot
          j -= 1
        end
        if i <= j
          t = a[i]; a[i] = a[j]; a[j] = t
          i += 1; j -= 1
        end
      end
      if j - lo < hi - i
        stack[sp] = i; sp += 1
        stack[sp] = hi; sp += 1
        hi = j
      else
        stack[sp] = lo; sp += 1
        stack[sp] = j; sp += 1
        lo = i
      end
    end
  end
end

def mergesort(a : Array(Int32), tmp : Array(Int32), n : Int64)
  src = a
  dst = tmp
  w = 1_i64
  while w < n
    lo = 0_i64
    while lo < n
      mid = lo + w < n ? lo + w : n
      hi = lo + 2 * w < n ? lo + 2 * w : n
      i = lo; j = mid; k = lo
      while i < mid && j < hi
        if src[i] <= src[j]
          dst[k] = src[i]; i += 1
        else
          dst[k] = src[j]; j += 1
        end
        k += 1
      end
      while i < mid
        dst[k] = src[i]; i += 1; k += 1
      end
      while j < hi
        dst[k] = src[j]; j += 1; k += 1
      end
      lo += 2 * w
    end
    src, dst = dst, src
    w *= 2
  end
  if !src.same?(a)
    n.times { |i| a[i] = src[i] }
  end
end

def siftdown(a : Array(Int32), root : Int64, n : Int64)
  loop do
    c = 2 * root + 1
    break if c >= n
    c += 1 if c + 1 < n && a[c + 1] > a[c]
    break if a[root] >= a[c]
    t = a[root]; a[root] = a[c]; a[c] = t
    root = c
  end
end

def heapsort(a : Array(Int32), n : Int64)
  i = n // 2 - 1
  while i >= 0
    siftdown(a, i, n)
    i -= 1
  end
  e = n - 1
  while e > 0
    t = a[0]; a[0] = a[e]; a[e] = t
    siftdown(a, 0_i64, e)
    e -= 1
  end
end

def bsearch_has(a : Array(Int32), n : Int64, key : Int32) : Bool
  lo = 0_i64
  hi = n
  while lo < hi
    m = (lo + hi) // 2
    if a[m] < key
      lo = m + 1
    else
      hi = m
    end
  end
  lo < n && a[lo] == key
end

n = (ARGV[0]? || "2000000").to_i64
orig = Array(Int32).new(n.to_i32) { (Rng.next >> 1).to_i32 }
a = orig.dup
b = orig.dup
d = orig.dup
tmp = Array(Int32).new(n.to_i32, 0)
quicksort(a, n)
mergesort(b, tmp, n)
heapsort(d, n)
h = 0_u32
ok = true
n.times do |i|
  h = h &* 31_u32 &+ a[i].to_u32
  ok = false if a[i] != b[i] || a[i] != d[i]
  ok = false if i > 0 && a[i - 1] > a[i]
end
bs = 0_i64
n.times do |k|
  key = (Rng.next >> 1).to_i32
  key = orig[key.to_u32 % n] if k % 2 == 0
  bs += 1 if bsearch_has(a, n, key)
end
ls = 0_i64
20.times do |k|
  key = (Rng.next >> 1).to_i32
  key = orig[key.to_u32 % n] if k % 2 == 0
  n.times do |i|
    if orig[i] == key
      ls += 1
      break
    end
  end
end
puts "#{h} #{bs} #{ls} #{ok ? "OK" : "BAD"}"
