! JSON serialise + parse benchmark (see json_serde.c): hand-written serialiser into one text buffer, then a hand-written
! index-based iterative parser into an array of records (names and tags go into pools).
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

module json
  implicit none
  type :: rec
    integer(8) :: id, name_off, name_len, score, tag_off, tag_cnt
  end type rec
contains
  subroutine wnum(t, p, v)
    integer(1), intent(inout) :: t(0:*)
    integer(8), intent(inout) :: p
    integer(8), intent(in) :: v
    integer(8) :: d, u, w, k
    d = 1; u = v
    do while (u >= 10)
      u = u / 10; d = d + 1
    end do
    w = v
    do k = d - 1, 0, -1
      t(p + k) = int(48 + mod(w, 10_8), 1)
      w = w / 10
    end do
    p = p + d
  end subroutine wnum

  subroutine wlit(t, p, s)
    integer(1), intent(inout) :: t(0:*)
    integer(8), intent(inout) :: p
    character(len=*), intent(in) :: s
    integer :: k
    do k = 1, len(s)
      t(p) = int(iachar(s(k:k)), 1)
      p = p + 1
    end do
  end subroutine wlit
end module json

program json_serde
  use lcg
  use json
  implicit none
  integer(1), parameter :: QUOTE = 34_1, COMMA = 44_1
  integer(8) :: n, i, p, nl, k, cents, tc, tlen, count, noff, toff, q, v, h, kk
  integer(1) :: c, k0
  integer(1), allocatable :: text(:), names(:), tags(:)
  type(rec), allocatable :: recs(:)
  type(rec) :: r
  character(len=32) :: arg
  n = 4000000_8
  if (command_argument_count() >= 1) then
    call get_command_argument(1, arg)
    read (arg, *) n
  end if
  allocate (text(0:n * 80 + 15))
  p = 0
  text(p) = 91_1; p = p + 1
  do i = 0, n - 1
    if (i > 0) then
      text(p) = COMMA; p = p + 1
    end if
    call wlit(text, p, '{"id":'); call wnum(text, p, i)
    call wlit(text, p, ',"name":"')
    nl = 3 + mod(shiftr(next(), 16), 8_8)
    do k = 0, nl - 1
      text(p) = int(97 + mod(shiftr(next(), 16), 26_8), 1); p = p + 1
    end do
    call wlit(text, p, '","score":')
    cents = mod(shiftr(next(), 8), 1000000_8)
    call wnum(text, p, cents / 100); text(p) = 46_1; p = p + 1
    text(p) = int(48 + mod(cents, 100_8) / 10, 1); p = p + 1
    text(p) = int(48 + mod(cents, 10_8), 1); p = p + 1
    call wlit(text, p, ',"tags":[')
    tc = mod(shiftr(next(), 16), 5_8)
    do k = 0, tc - 1
      if (k > 0) then
        text(p) = COMMA; p = p + 1
      end if
      call wnum(text, p, mod(shiftr(next(), 16), 100_8))
    end do
    call wlit(text, p, ']}')
  end do
  text(p) = 93_1; p = p + 1
  tlen = p
  ! ---- parse ----
  allocate (recs(0:n), names(0:n * 10 + 7), tags(0:n * 4 + 7))
  count = 0; noff = 0; toff = 0; q = 1
  do
    c = text(q)
    if (c == 93_1) exit
    if (c == COMMA) then
      q = q + 1
      cycle
    end if
    q = q + 1                                  ! '{'
    r = rec(0, 0, 0, 0, 0, 0)
    do
      q = q + 1                                ! opening quote of the key
      k0 = text(q)
      do while (text(q) /= QUOTE)
        q = q + 1
      end do
      q = q + 2                                ! closing quote, ':'
      if (k0 == 105_1) then                    ! 'i'
        v = 0
        do while (text(q) >= 48_1 .and. text(q) <= 57_1)
          v = v * 10 + (text(q) - 48_1); q = q + 1
        end do
        r%id = v
      else if (k0 == 110_1) then               ! 'n'
        q = q + 1
        r%name_off = noff
        do while (text(q) /= QUOTE)
          names(noff) = text(q); noff = noff + 1; q = q + 1
        end do
        r%name_len = noff - r%name_off
        q = q + 1
      else if (k0 == 115_1) then               ! 's'
        v = 0
        do while (text(q) >= 48_1 .and. text(q) <= 57_1)
          v = v * 10 + (text(q) - 48_1); q = q + 1
        end do
        q = q + 1                              ! '.'
        v = v * 100 + (text(q) - 48_1) * 10 + (text(q + 1) - 48_1); q = q + 2
        r%score = v
      else
        q = q + 1                              ! '['
        r%tag_off = toff
        do while (text(q) /= 93_1)
          if (text(q) == COMMA) q = q + 1
          v = 0
          do while (text(q) >= 48_1 .and. text(q) <= 57_1)
            v = v * 10 + (text(q) - 48_1); q = q + 1
          end do
          tags(toff) = int(v, 1); toff = toff + 1
        end do
        r%tag_cnt = toff - r%tag_off
        q = q + 1
      end if
      if (text(q) == COMMA) then
        q = q + 1
      else
        q = q + 1
        exit
      end if
    end do
    recs(count) = r; count = count + 1
  end do
  ! ---- checksum over the parsed records ----
  h = 7
  do i = 0, count - 1
    r = recs(i)
    h = iand(h * 31 + iand(r%id, 4294967295_8), 4294967295_8)
    h = iand(h * 31 + iand(r%score, 4294967295_8), 4294967295_8)
    h = iand(h * 31 + iand(r%name_len, 4294967295_8), 4294967295_8)
    do kk = 0, r%name_len - 1
      h = iand(h * 31 + int(names(r%name_off + kk), 8), 4294967295_8)
    end do
    h = iand(h * 31 + iand(r%tag_cnt, 4294967295_8), 4294967295_8)
    do kk = 0, r%tag_cnt - 1
      h = iand(h * 31 + int(tags(r%tag_off + kk), 8), 4294967295_8)
    end do
  end do
  print '(I0,1X,I0,1X,I0)', count, tlen, h
  deallocate (text, recs, names, tags)
end program json_serde
