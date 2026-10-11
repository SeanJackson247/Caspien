! fannkuch-redux, single threaded (algorithm of the Computer Language Benchmarks Game fannkuchredux-gcc-1).
program fannkuchredux
  implicit none
  integer :: n, res
  character(len=32) :: arg
  n = 7
  if (command_argument_count() >= 1) then
    call get_command_argument(1, arg)
    read (arg, *) n
  end if
  res = fannkuch(n)
  print '(A,I0,A,I0)', 'Pfannkuchen(', n, ') = ', res
contains
  function fannkuch(n) result(maxflips)
    integer, intent(in) :: n
    integer :: maxflips
    integer :: perm(0:15), perm1(0:15), count(0:15)
    integer :: permcount, checksum, i, r, flips, k, k2, t, j, perm0
    maxflips = 0; permcount = 0; checksum = 0
    r = n
    do i = 0, n - 1
      perm1(i) = i
    end do
    do
      do while (r /= 1)
        count(r - 1) = r
        r = r - 1
      end do
      do i = 0, n - 1
        perm(i) = perm1(i)
      end do
      flips = 0
      k = perm(0)
      do while (k /= 0)
        k2 = ishft(k + 1, -1)
        do i = 0, k2 - 1
          t = perm(i); perm(i) = perm(k - i); perm(k - i) = t
        end do
        flips = flips + 1
        k = perm(0)
      end do
      if (flips > maxflips) maxflips = flips
      if (mod(permcount, 2) == 0) then
        checksum = checksum + flips
      else
        checksum = checksum - flips
      end if
      do
        if (r == n) then
          print '(I0)', checksum
          return
        end if
        perm0 = perm1(0)
        i = 0
        do while (i < r)
          j = i + 1
          perm1(i) = perm1(j)
          i = j
        end do
        perm1(r) = perm0
        count(r) = count(r) - 1
        if (count(r) > 0) exit
        r = r + 1
      end do
      permcount = permcount + 1
    end do
  end function fannkuch
end program fannkuchredux
