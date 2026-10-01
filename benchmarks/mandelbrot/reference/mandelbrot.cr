# Mandelbrot escape-time benchmark: see mandelbrot.c.
n = (ARGV[0]? || "3000").to_i64
dn = n.to_f64
inside = 0_i64
total = 0_i64
n.times do |y|
  ci = 2.0 * y.to_f64 / dn - 1.0
  n.times do |x|
    cr = 2.0 * x.to_f64 / dn - 1.5
    zr = 0.0
    zi = 0.0
    tr = 0.0
    ti = 0.0
    i = 0
    while i < 100 && tr + ti <= 4.0
      zi = 2.0 * zr * zi + ci
      zr = tr - ti + cr
      tr = zr * zr
      ti = zi * zi
      i += 1
    end
    total += i
    inside += 1 if i == 100
  end
end
puts "#{inside} #{total}"
