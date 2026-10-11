! k-nucleotide: DNA from the benchmarks-game LCG, overlapping k-mers (k = 1,2,3,4,6,12) counted in a hand-written open-addressing
! hash table (linear probing, multiplicative hash, key stored +1 so 0 means empty). See knucleotide.c.
module ktab
  implicit none
  type :: slot
    integer(8) :: key = 0
    integer(4) :: cnt = 0
  end type slot
  integer(8), parameter :: GOLD = -7046029254386353131_8   ! 0x9E3779B97F4A7C15 as two's complement
contains
  function lookup(tab, cap, bits, kk) result(res)
    integer(8), intent(in) :: cap, kk
    integer, intent(in) :: bits
    type(slot), intent(in) :: tab(0:cap - 1)
    integer(8) :: res, h
    h = shiftr(kk * GOLD, 64 - bits)
    res = 0
    do
      if (tab(h)%key == 0) exit
      if (tab(h)%key == kk) then
        res = tab(h)%cnt
        exit
      end if
      h = iand(h + 1, cap - 1)
    end do
  end function lookup
end module ktab

program knucleotide
  use ktab
  implicit none
  integer, parameter :: KS(6) = [1, 2, 3, 4, 6, 12]
  integer, parameter :: QK(11) = [1, 1, 1, 1, 2, 2, 2, 3, 4, 6, 12]
  integer(8), parameter :: QV(11) = [0_8, 1_8, 2_8, 3_8, 10_8, 11_8, 0_8, 43_8, 172_8, 2767_8, 11337487_8]
  integer(8) :: n, i, last, first12, maxd, win, cap, mask, key, nd, h, distinct(6), counts(12)
  integer :: ki, k, bits, q, nq
  integer(1), allocatable :: seq(:)
  type(slot), allocatable :: tab(:)
  character(len=512) :: line
  character(len=32) :: arg
  n = 30000000_8
  if (command_argument_count() >= 1) then
    call get_command_argument(1, arg)
    read (arg, *) n
  end if
  allocate (seq(0:n))
  last = 42
  do i = 0, n - 1
    last = mod(last * 3877_8 + 29573_8, 139968_8)
    if (last < 42404) then
      seq(i) = 0_1
    else if (last < 70117) then
      seq(i) = 1_1
    else if (last < 97767) then
      seq(i) = 2_1
    else
      seq(i) = 3_1
    end if
  end do
  first12 = 0
  do i = 0, min(11_8, n - 1)
    first12 = ior(ishft(first12, 2), int(seq(i), 8))
  end do
  nq = 0
  do ki = 1, 6
    k = KS(ki)
    maxd = ishft(1_8, 2 * k)
    win = 0
    if (n >= k) win = n - k + 1
    if (win < maxd) maxd = win
    if (maxd > 139968) maxd = 139968
    bits = 1
    do while (ishft(1_8, bits) < 2 * maxd)
      bits = bits + 1
    end do
    cap = ishft(1_8, bits)
    allocate (tab(0:cap - 1))
    mask = ishft(1_8, 2 * k) - 1
    key = 0; nd = 0
    do i = 0, n - 1
      key = iand(ior(ishft(key, 2), int(seq(i), 8)), mask)
      if (i + 1 >= k) then
        h = shiftr((key + 1) * GOLD, 64 - bits)
        do
          if (tab(h)%key == 0) then
            tab(h)%key = key + 1; tab(h)%cnt = 1; nd = nd + 1
            exit
          end if
          if (tab(h)%key == key + 1) then
            tab(h)%cnt = tab(h)%cnt + 1
            exit
          end if
          h = iand(h + 1, cap - 1)
        end do
      end if
    end do
    distinct(ki) = nd
    do q = 1, 11
      if (QK(q) /= k) cycle
      nq = nq + 1
      counts(nq) = lookup(tab, cap, bits, QV(q) + 1)
    end do
    if (k == 12) then
      nq = nq + 1
      counts(nq) = lookup(tab, cap, bits, first12 + 1)
    end if
    deallocate (tab)
  end do
  write (line, '(6(I0,1X),I0,11(1X,I0))') distinct, counts
  print '(A)', trim(line)
  deallocate (seq)
end program knucleotide
