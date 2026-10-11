! Sieve of Eratosthenes: count the primes <= N (byte per number).
program sieve
  implicit none
  integer(8) :: n, i, j, cnt
  integer(1), allocatable :: f(:)
  character(len=32) :: arg
  n = 100000000_8
  if (command_argument_count() >= 1) then
    call get_command_argument(1, arg)
    read (arg, *) n
  end if
  allocate (f(0:n))
  f = 1_1
  i = 2
  do while (i * i <= n)
    if (f(i) /= 0_1) then
      do j = i * i, n, i
        f(j) = 0_1
      end do
    end if
    i = i + 1
  end do
  cnt = 0
  do i = 2, n
    cnt = cnt + f(i)
  end do
  print '(I0)', cnt
  deallocate (f)
end program sieve
