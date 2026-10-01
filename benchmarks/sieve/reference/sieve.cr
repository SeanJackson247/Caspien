# Sieve of Eratosthenes: count the primes <= N (byte per number).
n = (ARGV[0]? || "100000000").to_i64
f = Bytes.new((n + 1).to_i32, 1_u8)
i = 2_i64
while i * i <= n
  if f[i] != 0
    j = i * i
    while j <= n
      f[j] = 0_u8
      j += i
    end
  end
  i += 1
end
count = 0_i64
(2_i64..n).each { |k| count += f[k] }
puts count
