! Sorting and searching benchmark: iterative quicksort, bottom-up merge sort, heap sort, binary and linear searches (see sorting.c).
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

module sorts
  implicit none
contains
  subroutine quicksort(a, n)
    integer(8), intent(in) :: n
    integer(4), intent(inout) :: a(0:n - 1)
    integer(8) :: stack(0:127), lo, hi, i, j
    integer(4) :: pivot, t
    integer :: sp
    sp = 0
    stack(sp) = 0; sp = sp + 1
    stack(sp) = n - 1; sp = sp + 1
    do while (sp > 0)
      sp = sp - 1; hi = stack(sp)
      sp = sp - 1; lo = stack(sp)
      do while (lo < hi)
        pivot = a((lo + hi) / 2)
        i = lo; j = hi
        do while (i <= j)
          do while (a(i) < pivot)
            i = i + 1
          end do
          do while (a(j) > pivot)
            j = j - 1
          end do
          if (i <= j) then
            t = a(i); a(i) = a(j); a(j) = t
            i = i + 1; j = j - 1
          end if
        end do
        if (j - lo < hi - i) then
          stack(sp) = i; sp = sp + 1
          stack(sp) = hi; sp = sp + 1
          hi = j
        else
          stack(sp) = lo; sp = sp + 1
          stack(sp) = j; sp = sp + 1
          lo = i
        end if
      end do
    end do
  end subroutine quicksort

  subroutine mpass(src, dst, n, w)
    integer(8), intent(in) :: n, w
    integer(4), intent(in) :: src(0:n - 1)
    integer(4), intent(inout) :: dst(0:n - 1)
    integer(8) :: lo, mid, hi, i, j, k
    lo = 0
    do while (lo < n)
      mid = min(lo + w, n); hi = min(lo + 2 * w, n)
      i = lo; j = mid; k = lo
      do while (i < mid .and. j < hi)
        if (src(i) <= src(j)) then
          dst(k) = src(i); i = i + 1
        else
          dst(k) = src(j); j = j + 1
        end if
        k = k + 1
      end do
      do while (i < mid)
        dst(k) = src(i); i = i + 1; k = k + 1
      end do
      do while (j < hi)
        dst(k) = src(j); j = j + 1; k = k + 1
      end do
      lo = lo + 2 * w
    end do
  end subroutine mpass

  subroutine mergesort(a, tmp, n)
    integer(8), intent(in) :: n
    integer(4), intent(inout) :: a(0:n - 1), tmp(0:n - 1)
    integer(8) :: w
    logical :: in_a
    in_a = .true.
    w = 1
    do while (w < n)
      if (in_a) then
        call mpass(a, tmp, n, w)
      else
        call mpass(tmp, a, n, w)
      end if
      in_a = .not. in_a
      w = w * 2
    end do
    if (.not. in_a) a = tmp
  end subroutine mergesort

  subroutine siftdown(a, root0, n)
    integer(8), intent(in) :: root0, n
    integer(4), intent(inout) :: a(0:*)
    integer(8) :: root, c
    integer(4) :: t
    root = root0
    do
      c = 2 * root + 1
      if (c >= n) exit
      if (c + 1 < n) then
        if (a(c + 1) > a(c)) c = c + 1
      end if
      if (a(root) >= a(c)) exit
      t = a(root); a(root) = a(c); a(c) = t
      root = c
    end do
  end subroutine siftdown

  subroutine heapsort(a, n)
    integer(8), intent(in) :: n
    integer(4), intent(inout) :: a(0:n - 1)
    integer(8) :: i, e
    integer(4) :: t
    do i = n / 2 - 1, 0, -1
      call siftdown(a, i, n)
    end do
    do e = n - 1, 1, -1
      t = a(0); a(0) = a(e); a(e) = t
      call siftdown(a, 0_8, e)
    end do
  end subroutine heapsort

  function bsearch_has(a, n, key) result(r)
    integer(8), intent(in) :: n
    integer(4), intent(in) :: a(0:n - 1)
    integer(4), intent(in) :: key
    integer(8) :: r, lo, hi, m
    lo = 0; hi = n
    do while (lo < hi)
      m = (lo + hi) / 2
      if (a(m) < key) then
        lo = m + 1
      else
        hi = m
      end if
    end do
    r = 0
    if (lo < n) then
      if (a(lo) == key) r = 1
    end if
  end function bsearch_has
end module sorts

program sorting
  use lcg
  use sorts
  implicit none
  integer(8) :: n, i, k, bs, ls, h
  integer(4) :: key
  logical :: ok
  integer(4), allocatable :: orig(:), a(:), b(:), d(:), tmp(:)
  character(len=32) :: arg
  n = 2000000_8
  if (command_argument_count() >= 1) then
    call get_command_argument(1, arg)
    read (arg, *) n
  end if
  allocate (orig(0:n - 1), a(0:n - 1), b(0:n - 1), d(0:n - 1), tmp(0:n - 1))
  do i = 0, n - 1
    orig(i) = int(shiftr(next(), 1), 4)
  end do
  a = orig; b = orig; d = orig
  call quicksort(a, n)
  call mergesort(b, tmp, n)
  call heapsort(d, n)
  h = 0; ok = .true.
  do i = 0, n - 1
    h = iand(h * 31 + int(a(i), 8), 4294967295_8)
    if (a(i) /= b(i) .or. a(i) /= d(i)) ok = .false.
    if (i > 0) then
      if (a(i - 1) > a(i)) ok = .false.
    end if
  end do
  bs = 0
  do k = 0, n - 1
    key = int(shiftr(next(), 1), 4)
    if (mod(k, 2_8) == 0) key = orig(mod(int(key, 8), n))
    bs = bs + bsearch_has(a, n, key)
  end do
  ls = 0
  do k = 0, 19
    key = int(shiftr(next(), 1), 4)
    if (mod(k, 2_8) == 0) key = orig(mod(int(key, 8), n))
    do i = 0, n - 1
      if (orig(i) == key) then
        ls = ls + 1
        exit
      end if
    end do
  end do
  if (ok) then
    print '(I0,1X,I0,1X,I0,1X,A)', h, bs, ls, 'OK'
  else
    print '(I0,1X,I0,1X,I0,1X,A)', h, bs, ls, 'BAD'
  end if
  deallocate (orig, a, b, d, tmp)
end program sorting
