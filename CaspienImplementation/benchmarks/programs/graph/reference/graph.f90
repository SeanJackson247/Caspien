! Heap graph benchmark: N nodes allocated one by one on the heap, each with a random value and 4 outgoing pointers, then BFS.
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

module graph_types
  implicit none
  type :: node_ptr
    type(node), pointer :: p => null()
  end type node_ptr
  type :: node
    integer(8) :: value
    integer(4) :: dist
    type(node_ptr) :: e(4)
  end type node
end module graph_types

program graph
  use lcg
  use graph_types
  implicit none
  integer(8) :: n, i, head, tail, sum
  integer(4) :: k, maxdepth, needledist
  type(node_ptr), allocatable :: nodes(:), q(:)
  type(node), pointer :: u, v
  character(len=32) :: arg
  n = 2000000_8
  if (command_argument_count() >= 1) then
    call get_command_argument(1, arg)
    read (arg, *) n
  end if
  allocate (nodes(0:n - 1))
  do i = 0, n - 1
    allocate (nodes(i)%p)
    nodes(i)%p%value = iand(next(), 16777215_8)
    nodes(i)%p%dist = -1
  end do
  nodes(n * 7 / 10)%p%value = 4294967295_8
  do i = 0, n - 1
    nodes(i)%p%e(1)%p => nodes(mod(i + 1, n))%p
    do k = 2, 4
      nodes(i)%p%e(k)%p => nodes(mod(next(), n))%p
    end do
  end do
  allocate (q(0:n - 1))
  head = 0; tail = 0
  q(tail)%p => nodes(0)%p; tail = tail + 1
  nodes(0)%p%dist = 0
  maxdepth = 0; needledist = -1; sum = 0
  do while (head < tail)
    u => q(head)%p; head = head + 1
    sum = iand(sum + u%value, 4294967295_8)
    if (u%value == 4294967295_8) needledist = u%dist
    if (u%dist > maxdepth) maxdepth = u%dist
    do k = 1, 4
      v => u%e(k)%p
      if (v%dist < 0) then
        v%dist = u%dist + 1
        q(tail)%p => v; tail = tail + 1
      end if
    end do
  end do
  print '(I0,1X,I0,1X,I0,1X,I0)', tail, maxdepth, needledist, sum
  do i = 0, n - 1
    deallocate (nodes(i)%p)
  end do
  deallocate (nodes, q)
end program graph
