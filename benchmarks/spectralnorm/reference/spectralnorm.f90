! spectral-norm, single threaded (spectralnorm-gcc-1 of the Computer Language Benchmarks Game).
module sn
  implicit none
contains
  pure function eval_a(i, j) result(r)
    integer, intent(in) :: i, j
    real(8) :: r
    r = 1.0d0 / real((i + j) * (i + j + 1) / 2 + i + 1, 8)
  end function eval_a

  subroutine a_times_u(n, u, au)
    integer, intent(in) :: n
    real(8), intent(in) :: u(0:n - 1)
    real(8), intent(out) :: au(0:n - 1)
    integer :: i, j
    do i = 0, n - 1
      au(i) = 0
      do j = 0, n - 1
        au(i) = au(i) + eval_a(i, j) * u(j)
      end do
    end do
  end subroutine a_times_u

  subroutine at_times_u(n, u, au)
    integer, intent(in) :: n
    real(8), intent(in) :: u(0:n - 1)
    real(8), intent(out) :: au(0:n - 1)
    integer :: i, j
    do i = 0, n - 1
      au(i) = 0
      do j = 0, n - 1
        au(i) = au(i) + eval_a(j, i) * u(j)
      end do
    end do
  end subroutine at_times_u

  subroutine ata_times_u(n, u, atau)
    integer, intent(in) :: n
    real(8), intent(in) :: u(0:n - 1)
    real(8), intent(out) :: atau(0:n - 1)
    real(8), allocatable :: v(:)
    allocate (v(0:n - 1))
    call a_times_u(n, u, v)
    call at_times_u(n, v, atau)
    deallocate (v)
  end subroutine ata_times_u
end module sn

program spectralnorm
  use sn
  implicit none
  integer :: n, i
  real(8), allocatable :: u(:), v(:)
  real(8) :: vbv, vv
  character(len=32) :: arg
  n = 100
  if (command_argument_count() >= 1) then
    call get_command_argument(1, arg)
    read (arg, *) n
  end if
  allocate (u(0:n - 1), v(0:n - 1))
  u = 1
  do i = 1, 10
    call ata_times_u(n, u, v)
    call ata_times_u(n, v, u)
  end do
  vbv = 0; vv = 0
  do i = 0, n - 1
    vbv = vbv + u(i) * v(i)
    vv = vv + v(i) * v(i)
  end do
  write (arg, '(F20.9)') sqrt(vbv / vv)
  print '(A)', trim(adjustl(arg))
  deallocate (u, v)
end program spectralnorm
