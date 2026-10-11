# LRU cache: chained buckets threaded through the node pool, index-based doubly linked list; see lru.c.
CAP  = 262144_u32
KEYS = 1048576_u32
HOT  = 131072_u32
NB   = 524288_u32
NONE = 0xFFFFFFFF_u32

class Cache
  @key = Array(UInt32).new(CAP, 0_u32)
  @val = Array(UInt32).new(CAP, 0_u32)
  @prv = Array(UInt32).new(CAP, 0_u32)
  @nxt = Array(UInt32).new(CAP, 0_u32)
  @hn = Array(UInt32).new(CAP, 0_u32)
  @bucket = Array(UInt32).new(NB, NONE)
  getter head = NONE
  @tail = NONE
  @x = 12345_u32

  def next : UInt32
    @x = @x &* 1664525_u32 &+ 1013904223_u32
  end

  @[AlwaysInline]
  def hsh(k : UInt32) : UInt32
    ((k.to_u64 &* 2654435761_u64) & 0xFFFFFFFF_u64).to_u32 >> 13
  end

  def unlink_node(i : UInt32)
    if @prv[i] != NONE
      @nxt[@prv[i]] = @nxt[i]
    else
      @head = @nxt[i]
    end
    if @nxt[i] != NONE
      @prv[@nxt[i]] = @prv[i]
    else
      @tail = @prv[i]
    end
  end

  def push_front(i : UInt32)
    @prv[i] = NONE
    @nxt[i] = @head
    if @head != NONE
      @prv[@head] = i
    else
      @tail = i
    end
    @head = i
  end

  def find(k : UInt32) : UInt32
    i = @bucket[hsh(k)]
    while i != NONE && @key[i] != k
      i = @hn[i]
    end
    i
  end

  def hash_remove(i : UInt32)
    b = hsh(@key[i])
    if @bucket[b] == i
      @bucket[b] = @hn[i]
      return
    end
    j = @bucket[b]
    while @hn[j] != i
      j = @hn[j]
    end
    @hn[j] = @hn[i]
  end

  def run(n : Int64)
    size = 0_u32
    hits = 0_u64
    misses = 0_u64
    sum = 0_u64
    n.times do
      a = self.next
      y = self.next
      range = ((y >> 20) % 4 == 0) ? KEYS : HOT
      k = (a >> 8) % range
      i = find(k)
      if (y >> 24) % 4 != 0
        if i != NONE
          hits += 1
          sum &+= @val[i].to_u64 &+ k
          unlink_node(i)
          push_front(i)
        else
          misses += 1
        end
      elsif i != NONE
        @val[i] = y
        unlink_node(i)
        push_front(i)
      else
        if size == CAP
          i = @tail
          sum &+= @key[i]
          unlink_node(i)
          hash_remove(i)
        else
          i = size
          size += 1
        end
        @key[i] = k
        @val[i] = y
        b = hsh(k)
        @hn[i] = @bucket[b]
        @bucket[b] = i
        push_front(i)
      end
    end
    fin = 0_u64
    i = @head
    while i != NONE
      fin = (fin &* 31_u64 &+ @key[i]) & 0xFFFFFFFF_u64
      i = @nxt[i]
    end
    puts "#{hits} #{misses} #{(sum &+ fin) & 0xFFFFFFFF_u64}"
  end
end

n = (ARGV[0]? || "20000000").to_i64
Cache.new.run(n)
