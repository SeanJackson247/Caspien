! String manipulation benchmark: see strings.c. Text of N chars (27 symbols), word statistics, count of "abc", reverse,
! replace 'e' by "33", upper-case. Every result buffer is a fresh heap allocation.
module lcg
  implicit none
  integer(8), save :: x = 12345_8
contains
  function next() result(r)
    integer(8) :: r
    x = iand(x * 1664525_8 + 1013904223_8, 4294967295_8)
    r = x
  end function next
end module lcg

program strings
  use lcg
  implicit none
  integer(8) :: n, i, k, m, es, words, longest, cur, abc
  integer(8) :: hrev, hrep, hup, r
  integer(1), allocatable :: text(:), rev(:), rep(:), up(:)
  character(len=32) :: arg
  n = 4000000_8
  if (command_argument_count() >= 1) then
    call get_command_argument(1, arg)
    read (arg, *) n
  end if
  allocate (text(0:n - 1))
  do i = 0, n - 1
    r = mod(shiftr(next(), 16), 27_8)
    if (r == 26) then
      text(i) = 32_1
    else
      text(i) = int(97 + r, 1)
    end if
  end do
  words = 0; longest = 0; cur = 0
  do i = 0, n - 1
    if (text(i) == 32_1) then
      if (cur > 0) then
        words = words + 1
        if (cur > longest) longest = cur
        cur = 0
      end if
    else
      cur = cur + 1
    end if
  end do
  if (cur > 0) then
    words = words + 1
    if (cur > longest) longest = cur
  end if
  abc = 0
  do i = 0, n - 3
    if (text(i) == 97_1 .and. text(i + 1) == 98_1 .and. text(i + 2) == 99_1) abc = abc + 1
  end do
  allocate (rev(0:n - 1))
  do i = 0, n - 1
    rev(i) = text(n - 1 - i)
  end do
  hrev = hash(rev, n)
  es = 0
  do i = 0, n - 1
    if (text(i) == 101_1) es = es + 1
  end do
  m = n + es
  allocate (rep(0:m - 1))
  k = 0
  do i = 0, n - 1
    if (text(i) == 101_1) then
      rep(k) = 51_1; rep(k + 1) = 51_1; k = k + 2
    else
      rep(k) = text(i); k = k + 1
    end if
  end do
  hrep = hash(rep, m)
  allocate (up(0:n - 1))
  do i = 0, n - 1
    if (text(i) == 32_1) then
      up(i) = 32_1
    else
      up(i) = text(i) - 32_1
    end if
  end do
  hup = hash(up, n)
  print '(I0,1X,I0,1X,I0,1X,I0,1X,I0,1X,I0,1X,I0)', words, longest, abc, hrev, m, hrep, hup
  deallocate (text, rev, rep, up)
contains
  function hash(b, len) result(h)
    integer(8), intent(in) :: len
    integer(1), intent(in) :: b(0:len - 1)
    integer(8) :: h
    integer(8) :: j
    h = 7
    do j = 0, len - 1
      h = iand(h * 31 + int(b(j), 8), 4294967295_8)
    end do
  end function hash
end program strings
