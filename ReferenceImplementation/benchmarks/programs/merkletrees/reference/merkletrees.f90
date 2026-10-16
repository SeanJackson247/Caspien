! Merkle tree benchmark (real SHA-256, FIPS 180-4, hand-written; see merkletrees.c). Digests are 8 x 32-bit words in one flat
! array; unsigned 32-bit words are held in integer(4) (wrapping add, ishftc rotate, logical shifts).
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

module sha
  implicit none
  integer(8), parameter :: K8(64) = [ &
    int(z'428a2f98', 8), &
    int(z'71374491', 8), &
    int(z'b5c0fbcf', 8), &
    int(z'e9b5dba5', 8), &
    int(z'3956c25b', 8), &
    int(z'59f111f1', 8), &
    int(z'923f82a4', 8), &
    int(z'ab1c5ed5', 8), &
    int(z'd807aa98', 8), &
    int(z'12835b01', 8), &
    int(z'243185be', 8), &
    int(z'550c7dc3', 8), &
    int(z'72be5d74', 8), &
    int(z'80deb1fe', 8), &
    int(z'9bdc06a7', 8), &
    int(z'c19bf174', 8), &
    int(z'e49b69c1', 8), &
    int(z'efbe4786', 8), &
    int(z'0fc19dc6', 8), &
    int(z'240ca1cc', 8), &
    int(z'2de92c6f', 8), &
    int(z'4a7484aa', 8), &
    int(z'5cb0a9dc', 8), &
    int(z'76f988da', 8), &
    int(z'983e5152', 8), &
    int(z'a831c66d', 8), &
    int(z'b00327c8', 8), &
    int(z'bf597fc7', 8), &
    int(z'c6e00bf3', 8), &
    int(z'd5a79147', 8), &
    int(z'06ca6351', 8), &
    int(z'14292967', 8), &
    int(z'27b70a85', 8), &
    int(z'2e1b2138', 8), &
    int(z'4d2c6dfc', 8), &
    int(z'53380d13', 8), &
    int(z'650a7354', 8), &
    int(z'766a0abb', 8), &
    int(z'81c2c92e', 8), &
    int(z'92722c85', 8), &
    int(z'a2bfe8a1', 8), &
    int(z'a81a664b', 8), &
    int(z'c24b8b70', 8), &
    int(z'c76c51a3', 8), &
    int(z'd192e819', 8), &
    int(z'd6990624', 8), &
    int(z'f40e3585', 8), &
    int(z'106aa070', 8), &
    int(z'19a4c116', 8), &
    int(z'1e376c08', 8), &
    int(z'2748774c', 8), &
    int(z'34b0bcb5', 8), &
    int(z'391c0cb3', 8), &
    int(z'4ed8aa4a', 8), &
    int(z'5b9cca4f', 8), &
    int(z'682e6ff3', 8), &
    int(z'748f82ee', 8), &
    int(z'78a5636f', 8), &
    int(z'84c87814', 8), &
    int(z'8cc70208', 8), &
    int(z'90befffa', 8), &
    int(z'a4506ceb', 8), &
    int(z'bef9a3f7', 8), &
    int(z'c67178f2', 8)]
  integer(8), parameter :: H8(8) = [int(z'6a09e667', 8), &
    int(z'bb67ae85', 8), &
    int(z'3c6ef372', 8), &
    int(z'a54ff53a', 8), &
                                        int(z'510e527f', 8), &
    int(z'9b05688c', 8), &
    int(z'1f83d9ab', 8), &
    int(z'5be0cd19', 8)]
  integer(4), save :: K(64), H0(8)
contains
  pure function wrap(v) result(r)
    integer(8), intent(in) :: v
    integer(4) :: r
    if (v >= 2147483648_8) then
      r = int(v - 4294967296_8, 4)
    else
      r = int(v, 4)
    end if
  end function wrap

  subroutine init()
    integer :: i
    do i = 1, 64
      K(i) = wrap(K8(i))
    end do
    do i = 1, 8
      H0(i) = wrap(H8(i))
    end do
  end subroutine init

  pure function ror(v, n) result(r)
    integer(4), intent(in) :: v
    integer, intent(in) :: n
    integer(4) :: r
    r = ishftc(v, -n)
  end function ror

  ! one compression: st(8) updated in place with the 16-word big-endian block b
  subroutine compress(st, b)
    integer(4), intent(inout) :: st(8)
    integer(4), intent(in) :: b(16)
    integer(4) :: w(0:63), a, bb, c, d, e, f, g, h, s0, s1, ch, t1, t2, mj
    integer :: i
    w(0:15) = b
    do i = 16, 63
      s0 = ieor(ieor(ror(w(i - 15), 7), ror(w(i - 15), 18)), shiftr(w(i - 15), 3))
      s1 = ieor(ieor(ror(w(i - 2), 17), ror(w(i - 2), 19)), shiftr(w(i - 2), 10))
      w(i) = w(i - 16) + s0 + w(i - 7) + s1
    end do
    a = st(1); bb = st(2); c = st(3); d = st(4); e = st(5); f = st(6); g = st(7); h = st(8)
    do i = 0, 63
      s1 = ieor(ieor(ror(e, 6), ror(e, 11)), ror(e, 25))
      ch = ieor(iand(e, f), iand(not(e), g))
      t1 = h + s1 + ch + K(i + 1) + w(i)
      s0 = ieor(ieor(ror(a, 2), ror(a, 13)), ror(a, 22))
      mj = ieor(ieor(iand(a, bb), iand(a, c)), iand(bb, c))
      t2 = s0 + mj
      h = g; g = f; f = e; e = d + t1; d = c; c = bb; bb = a; a = t1 + t2
    end do
    st(1) = st(1) + a; st(2) = st(2) + bb; st(3) = st(3) + c; st(4) = st(4) + d
    st(5) = st(5) + e; st(6) = st(6) + f; st(7) = st(7) + g; st(8) = st(8) + h
  end subroutine compress

  pure function bswap32(v) result(r)
    integer(4), intent(in) :: v
    integer(4) :: r
    r = ior(ior(shiftr(v, 24), iand(shiftr(v, 8), 65280)), ior(iand(shiftl(v, 8), 16711680), shiftl(v, 24)))
  end function bswap32

  ! out(8) = SHA256(le64(dlo + 2^32*dhi) || le64(i))
  subroutine leaf(out, dlo, dhi, i)
    integer(4), intent(out) :: out(8)
    integer(4), intent(in) :: dlo, dhi
    integer(8), intent(in) :: i
    integer(4) :: b(16)
    b = 0
    b(1) = bswap32(dlo); b(2) = bswap32(dhi)
    b(3) = bswap32(int(iand(i, 4294967295_8) - merge(4294967296_8, 0_8, iand(i, 4294967295_8) >= 2147483648_8), 4))
    b(4) = bswap32(int(shiftr(i, 32), 4))
    b(5) = wrap(2147483648_8)
    b(16) = 128
    out = H0
    call compress(out, b)
  end subroutine leaf

  ! out(8) = SHA256(l(8 words) || r(8 words)); out must not alias l or r
  subroutine node(out, l, r)
    integer(4), intent(out) :: out(8)
    integer(4), intent(in) :: l(8), r(8)
    integer(4) :: b(16), st(8), p(16)
    b(1:8) = l; b(9:16) = r
    st = H0
    call compress(st, b)
    p = 0
    p(1) = wrap(2147483648_8)
    p(16) = 512
    call compress(st, p)
    out = st
  end subroutine node
end module sha

program merkletrees
  use lcg
  use sha
  implicit none
  integer(8) :: n, i, off, size, noff, ns, j, verified, rejected, idx, o, s, pos, depth, d, sb, p
  integer(4), allocatable :: data(:), t(:, :)
  integer(4) :: sibs(8, 0:63), cur(8), tmp(8), dlo, dhi
  logical :: eq
  character(len=96) :: line
  character(len=32) :: arg
  integer :: ci
  n = 140000_8
  if (command_argument_count() >= 1) then
    call get_command_argument(1, arg)
    read (arg, *) n
  end if
  call init()
  allocate (data(0:n - 1), t(8, 0:2 * n + 63))
  do i = 0, n - 1
    data(i) = wrap(next())
    call leaf(t(:, i), data(i), 0, i)
  end do
  off = 0; size = n
  do while (size > 1)
    noff = off + size; ns = (size + 1) / 2
    do j = 0, ns - 1
      if (2 * j + 1 < size) then
        call node(t(:, noff + j), t(:, off + 2 * j), t(:, off + 2 * j + 1))
      else
        call node(t(:, noff + j), t(:, off + 2 * j), t(:, off + 2 * j))
      end if
    end do
    off = noff; size = ns
  end do
  verified = 0; rejected = 0
  do p = 0, n / 2 - 1
    idx = mod(next(), n)
    o = 0; s = n; pos = idx; depth = 0
    do while (s > 1)
      if (mod(pos, 2_8) == 0) then
        sb = pos + 1
      else
        sb = pos - 1
      end if
      if (sb >= s) sb = pos
      sibs(:, depth) = t(:, o + sb)
      depth = depth + 1
      o = o + s; s = (s + 1) / 2; pos = pos / 2
    end do
    dlo = data(idx); dhi = 0
    if (mod(p, 4_8) == 3) then
      dlo = dlo + 1
      if (dlo == 0) dhi = 1
    end if
    call leaf(cur, dlo, dhi, idx)
    pos = idx
    do d = 0, depth - 1
      if (mod(pos, 2_8) == 0) then
        call node(tmp, cur, sibs(:, d))
      else
        call node(tmp, sibs(:, d), cur)
      end if
      cur = tmp
      pos = pos / 2
    end do
    eq = all(cur == t(:, off))
    if (eq) then
      verified = verified + 1
    else
      rejected = rejected + 1
    end if
  end do
  write (line, '(8Z8.8)') t(:, off)
  do ci = 1, 64
    if (line(ci:ci) >= 'A' .and. line(ci:ci) <= 'F') line(ci:ci) = achar(iachar(line(ci:ci)) + 32)
  end do
  print '(A,1X,I0,1X,I0)', line(1:64), verified, rejected
  deallocate (data, t)
end program merkletrees
