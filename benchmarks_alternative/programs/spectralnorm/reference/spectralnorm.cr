# spectral-norm, single threaded: see spectralnorm.c.
@[AlwaysInline]
def eval_a(i : Int32, j : Int32) : Float64
  1.0 / ((i + j) * (i + j + 1) // 2 + i + 1)
end

def a_times_u(n : Int32, u : Array(Float64), au : Array(Float64))
  n.times do |i|
    s = 0.0
    n.times { |j| s += eval_a(i, j) * u[j] }
    au[i] = s
  end
end

def at_times_u(n : Int32, u : Array(Float64), au : Array(Float64))
  n.times do |i|
    s = 0.0
    n.times { |j| s += eval_a(j, i) * u[j] }
    au[i] = s
  end
end

def ata_times_u(n : Int32, u : Array(Float64), atau : Array(Float64))
  v = Array(Float64).new(n, 0.0)
  a_times_u(n, u, v)
  at_times_u(n, v, atau)
end

n = (ARGV[0]? || "100").to_i
u = Array(Float64).new(n, 1.0)
v = Array(Float64).new(n, 0.0)
10.times do
  ata_times_u(n, u, v)
  ata_times_u(n, v, u)
end
vbv = 0.0
vv = 0.0
n.times do |i|
  vbv += u[i] * v[i]
  vv += v[i] * v[i]
end
printf "%0.9f\n", Math.sqrt(vbv / vv)
