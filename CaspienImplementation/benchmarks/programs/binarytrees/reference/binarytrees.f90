! Binary trees (Benchmarks Game style): ordinary heap nodes with left/right POINTERS, one allocate per node.
module trees
  implicit none
  type :: node
    type(node), pointer :: l => null(), r => null()
  end type node
contains
  recursive function make(d) result(n)
    integer, intent(in) :: d
    type(node), pointer :: n
    allocate (n)
    if (d > 0) then
      n%l => make(d - 1)
      n%r => make(d - 1)
    end if
  end function make

  recursive function check(n) result(c)
    type(node), pointer, intent(in) :: n
    integer(8) :: c
    if (associated(n%l)) then
      c = 1 + check(n%l) + check(n%r)
    else
      c = 1
    end if
  end function check

  recursive subroutine release(n)
    type(node), pointer :: n
    if (associated(n%l)) then
      call release(n%l)
      call release(n%r)
    end if
    deallocate (n)
  end subroutine release
end module trees

program binarytrees
  use trees
  implicit none
  integer :: maxd, d
  integer(8) :: iters, s, i
  type(node), pointer :: t, longlived, a
  character(len=32) :: arg
  maxd = 16
  if (command_argument_count() >= 1) then
    call get_command_argument(1, arg)
    read (arg, *) maxd
  end if
  if (maxd < 6) maxd = 6
  t => make(maxd + 1)
  write (*, '(I0)', advance='no') check(t)
  call release(t)
  longlived => make(maxd)
  do d = 4, maxd, 2
    iters = ishft(1_8, maxd - d + 4)
    s = 0
    do i = 1, iters
      a => make(d)
      s = s + check(a)
      call release(a)
    end do
    write (*, '(1X,I0)', advance='no') s
  end do
  write (*, '(1X,I0)') check(longlived)
  call release(longlived)
end program binarytrees
