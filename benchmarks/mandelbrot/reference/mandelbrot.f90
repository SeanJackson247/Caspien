! Mandelbrot escape-time benchmark: N x N grid over [-1.5,0.5] x [-1,1], at most 100 iterations. No allocation.
program mandelbrot
  implicit none
  integer(8) :: n, x, y, inside, total
  integer :: i
  real(8) :: dn, ci, cr, zr, zi, tr, ti
  character(len=32) :: arg
  n = 3000_8
  if (command_argument_count() >= 1) then
    call get_command_argument(1, arg)
    read (arg, *) n
  end if
  dn = real(n, 8)
  inside = 0; total = 0
  do y = 0, n - 1
    ci = 2.0d0 * real(y, 8) / dn - 1.0d0
    do x = 0, n - 1
      cr = 2.0d0 * real(x, 8) / dn - 1.5d0
      zr = 0.0d0; zi = 0.0d0; tr = 0.0d0; ti = 0.0d0
      i = 0
      do while (i < 100)
        if (tr + ti > 4.0d0) exit
        zi = 2.0d0 * zr * zi + ci
        zr = tr - ti + cr
        tr = zr * zr
        ti = zi * zi
        i = i + 1
      end do
      total = total + i
      if (i == 100) inside = inside + 1
    end do
  end do
  print '(I0,1X,I0)', inside, total
end program mandelbrot
