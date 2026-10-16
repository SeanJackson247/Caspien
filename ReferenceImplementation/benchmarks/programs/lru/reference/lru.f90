! LRU cache benchmark (see lru.c): fixed-capacity cache, chained hash buckets threaded through the node pool, index-based
! doubly linked recency list. All arrays are 32-bit (unsigned values kept as bit patterns in integer(4)).
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

program lru
  use lcg
  implicit none
  integer(4), parameter :: CAP = 262144, KEYS = 1048576, HOT = 131072, NB = 524288, NONE = -1
  integer(4), allocatable :: key(:), val(:), prv(:), nxt(:), hn(:), bucket(:)
  integer(4) :: head, tail, size, i, k, range, b, j
  integer(8) :: n, op, a, y, hits, misses, sm, fin
  character(len=32) :: arg
  n = 20000000_8
  if (command_argument_count() >= 1) then
    call get_command_argument(1, arg)
    read (arg, *) n
  end if
  allocate (key(0:CAP - 1), val(0:CAP - 1), prv(0:CAP - 1), nxt(0:CAP - 1), hn(0:CAP - 1), bucket(0:NB - 1))
  bucket = NONE
  head = NONE; tail = NONE; size = 0
  hits = 0; misses = 0; sm = 0
  do op = 0, n - 1
    a = next(); y = next()
    if (mod(shiftr(y, 20), 4_8) == 0) then
      range = KEYS
    else
      range = HOT
    end if
    k = int(mod(shiftr(a, 8), int(range, 8)), 4)
    i = find(k)
    if (mod(shiftr(y, 24), 4_8) /= 0) then
      if (i /= NONE) then
        hits = hits + 1
        sm = sm + iand(int(val(i), 8), 4294967295_8) + k
        call unlink_node(i); call push_front(i)
      else
        misses = misses + 1
      end if
    else if (i /= NONE) then
      val(i) = wrap(y)
      call unlink_node(i); call push_front(i)
    else
      if (size == CAP) then
        i = tail
        sm = sm + key(i)
        call unlink_node(i); call hash_remove(i)
      else
        i = size; size = size + 1
      end if
      key(i) = k; val(i) = wrap(y)
      b = hsh(k); hn(i) = bucket(b); bucket(b) = i
      call push_front(i)
    end if
  end do
  fin = 0
  j = head
  do while (j /= NONE)
    fin = mod(fin * 31 + key(j), 4294967296_8)
    j = nxt(j)
  end do
  print '(I0,1X,I0,1X,I0)', hits, misses, mod(sm + fin, 4294967296_8)
  deallocate (key, val, prv, nxt, hn, bucket)
contains
  function wrap(v) result(r)
    integer(8), intent(in) :: v
    integer(4) :: r
    if (v >= 2147483648_8) then
      r = int(v - 4294967296_8, 4)
    else
      r = int(v, 4)
    end if
  end function wrap

  function hsh(kk) result(r)
    integer(4), intent(in) :: kk
    integer(4) :: r
    r = int(shiftr(iand(int(kk, 8) * 2654435761_8, 4294967295_8), 13), 4)
  end function hsh

  subroutine unlink_node(ii)
    integer(4), intent(in) :: ii
    if (prv(ii) /= NONE) then
      nxt(prv(ii)) = nxt(ii)
    else
      head = nxt(ii)
    end if
    if (nxt(ii) /= NONE) then
      prv(nxt(ii)) = prv(ii)
    else
      tail = prv(ii)
    end if
  end subroutine unlink_node

  subroutine push_front(ii)
    integer(4), intent(in) :: ii
    prv(ii) = NONE; nxt(ii) = head
    if (head /= NONE) then
      prv(head) = ii
    else
      tail = ii
    end if
    head = ii
  end subroutine push_front

  function find(kk) result(r)
    integer(4), intent(in) :: kk
    integer(4) :: r
    r = bucket(hsh(kk))
    do while (r /= NONE)
      if (key(r) == kk) exit
      r = hn(r)
    end do
  end function find

  subroutine hash_remove(ii)
    integer(4), intent(in) :: ii
    integer(4) :: bb, jj
    bb = hsh(key(ii))
    if (bucket(bb) == ii) then
      bucket(bb) = hn(ii)
      return
    end if
    jj = bucket(bb)
    do while (hn(jj) /= ii)
      jj = hn(jj)
    end do
    hn(jj) = hn(ii)
  end subroutine hash_remove
end program lru
