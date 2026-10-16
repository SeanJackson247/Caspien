! FASTA benchmark: N nucleotides as 60-column FASTA text into ONE heap buffer (see fasta.c). Prints textlength checksum A C G T other.
program fasta
  implicit none
  character(len=287), parameter :: ALU = &
    'GGCCGGGCGCGGTGGCTCACGCCTGTAATCCCAGCACTTTGGGAGGCCGAGGCGGGCGGATCACCTGAGGTCAGGAGTTCGAGACCAGCCTGGCCAACATGGTGAAACCCCGTCTCTAC' // &
    'TAAAAATACAAAAATTAGCCGGGCGTGGTGGCGCGCGCCTGTAATCCCAGCTACTCGGGAGGCTGAGGCAGGAGAATCGCTTGAACCCGGGAGGCGGAGGTTGCAGTGAGCCGAGATCGCG' // &
    'CCACTGCACTCCAGCCTGGGCGACAGAGCGAGACTCCGTCTCAAAAA'
  character(len=19), parameter :: CHARS = 'ACGTBDHKMNRSVWYACGT'
  integer, parameter :: THR(0:18) = [37792, 54588, 71384, 109176, 111975, 114774, 117574, 120373, 123172, 125972, 128771, &
                                     131570, 134370, 137169, 139968, 42404, 70117, 97767, 139968]
  character(len=*), parameter :: NL = achar(10)
  character(len=30), parameter :: HDR1 = '>ONE Homo sapiens alu'//NL
  character(len=30), parameter :: HDR2 = '>TWO IUB ambiguity codes'//NL
  character(len=30), parameter :: HDR3 = '>THREE Homo sapiens frequency'//NL
  character(len=30) :: hdr
  integer :: hl, off(3), s, col, j, ai, ch
  integer(8) :: n, cnt(3), pos, a, c, g, t, other, i, last, h, k
  integer(1), allocatable :: buf(:)
  character(len=32) :: arg
  n = 40000000_8
  if (command_argument_count() >= 1) then
    call get_command_argument(1, arg)
    read (arg, *) n
  end if
  cnt(1) = n * 2 / 10
  cnt(2) = n * 3 / 10
  cnt(3) = n - cnt(1) - cnt(2)
  off = [0, 0, 15]
  last = 42
  allocate (buf(0:n + n / 60 + 1023))
  pos = 0; a = 0; c = 0; g = 0; t = 0; other = 0; ai = 0
  do s = 1, 3
    select case (s)
    case (1); hdr = HDR1
    case (2); hdr = HDR2
    case default; hdr = HDR3
    end select
    hl = index(hdr, NL)
    do k = 1, hl
      buf(pos) = int(iachar(hdr(k:k)), 1); pos = pos + 1
    end do
    col = 0
    do i = 1, cnt(s)
      if (s == 1) then
        ch = iachar(ALU(ai + 1:ai + 1))
        ai = ai + 1
        if (ai == 287) ai = 0
      else
        last = mod(last * 3877_8 + 29573_8, 139968_8)
        j = off(s)
        do while (last >= THR(j))
          j = j + 1
        end do
        ch = iachar(CHARS(j + 1:j + 1))
      end if
      buf(pos) = int(ch, 1); pos = pos + 1
      select case (ch)
      case (65); a = a + 1
      case (67); c = c + 1
      case (71); g = g + 1
      case (84); t = t + 1
      case default; other = other + 1
      end select
      col = col + 1
      if (col == 60) then
        buf(pos) = 10_1; pos = pos + 1; col = 0
      end if
    end do
    if (col > 0) then
      buf(pos) = 10_1; pos = pos + 1
    end if
  end do
  h = 7
  do i = 0, pos - 1
    h = iand(h * 31 + int(buf(i), 8), 4294967295_8)
  end do
  print '(I0,1X,I0,1X,I0,1X,I0,1X,I0,1X,I0,1X,I0)', pos, h, a, c, g, t, other
  deallocate (buf)
end program fasta
