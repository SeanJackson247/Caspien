! n-body: five bodies as an array of derived types (array of structs), advanced N steps of dt = 0.01.
program nbody
  implicit none
  real(8), parameter :: PI = 3.141592653589793d0, SOLAR_MASS = 4.0d0 * PI * PI, DPY = 365.24d0
  type :: planet
    real(8) :: x, y, z, vx, vy, vz, mass
  end type planet
  type(planet) :: bodies(5)
  integer(8) :: n, i
  integer :: j
  real(8) :: px, py, pz
  character(len=32) :: arg
  n = 1000_8
  if (command_argument_count() >= 1) then
    call get_command_argument(1, arg)
    read (arg, *) n
  end if
  bodies(1) = planet(0d0, 0d0, 0d0, 0d0, 0d0, 0d0, SOLAR_MASS)
  bodies(2) = planet(4.84143144246472090d+00, -1.16032004402742839d+00, -1.03622044471123109d-01, &
       1.66007664274403694d-03 * DPY, 7.69901118419740425d-03 * DPY, -6.90460016972063023d-05 * DPY, &
       9.54791938424326609d-04 * SOLAR_MASS)
  bodies(3) = planet(8.34336671824457987d+00, 4.12479856412430479d+00, -4.03523417114321381d-01, &
       -2.76742510726862411d-03 * DPY, 4.99852801234917238d-03 * DPY, 2.30417297573763929d-05 * DPY, &
       2.85885980666130812d-04 * SOLAR_MASS)
  bodies(4) = planet(1.28943695621391310d+01, -1.51111514016986312d+01, -2.23307578892655734d-01, &
       2.96460137564761618d-03 * DPY, 2.37847173959480950d-03 * DPY, -2.96589568540237556d-05 * DPY, &
       4.36624404335156298d-05 * SOLAR_MASS)
  bodies(5) = planet(1.53796971148509165d+01, -2.59193146099879641d+01, 1.79258772950371181d-01, &
       2.68067772490389322d-03 * DPY, 1.62824170038242295d-03 * DPY, -9.51592254519715870d-05 * DPY, &
       5.15138902046611451d-05 * SOLAR_MASS)
  px = 0; py = 0; pz = 0
  do j = 1, 5
    px = px + bodies(j)%vx * bodies(j)%mass
    py = py + bodies(j)%vy * bodies(j)%mass
    pz = pz + bodies(j)%vz * bodies(j)%mass
  end do
  bodies(1)%vx = -px / SOLAR_MASS
  bodies(1)%vy = -py / SOLAR_MASS
  bodies(1)%vz = -pz / SOLAR_MASS
  call show(energy())
  do i = 1, n
    call advance(0.01d0)
  end do
  call show(energy())
contains
  subroutine show(e)
    real(8), intent(in) :: e
    character(len=32) :: buf
    write (buf, '(F20.9)') e
    print '(A)', trim(adjustl(buf))
  end subroutine show

  subroutine advance(dt)
    real(8), intent(in) :: dt
    integer :: a, b
    real(8) :: dx, dy, dz, d2, mag
    do a = 1, 5
      do b = a + 1, 5
        dx = bodies(a)%x - bodies(b)%x
        dy = bodies(a)%y - bodies(b)%y
        dz = bodies(a)%z - bodies(b)%z
        d2 = dx * dx + dy * dy + dz * dz
        mag = dt / (d2 * sqrt(d2))
        bodies(a)%vx = bodies(a)%vx - dx * bodies(b)%mass * mag
        bodies(a)%vy = bodies(a)%vy - dy * bodies(b)%mass * mag
        bodies(a)%vz = bodies(a)%vz - dz * bodies(b)%mass * mag
        bodies(b)%vx = bodies(b)%vx + dx * bodies(a)%mass * mag
        bodies(b)%vy = bodies(b)%vy + dy * bodies(a)%mass * mag
        bodies(b)%vz = bodies(b)%vz + dz * bodies(a)%mass * mag
      end do
    end do
    do a = 1, 5
      bodies(a)%x = bodies(a)%x + dt * bodies(a)%vx
      bodies(a)%y = bodies(a)%y + dt * bodies(a)%vy
      bodies(a)%z = bodies(a)%z + dt * bodies(a)%vz
    end do
  end subroutine advance

  function energy() result(e)
    real(8) :: e, dx, dy, dz
    integer :: a, b
    e = 0
    do a = 1, 5
      e = e + 0.5d0 * bodies(a)%mass * (bodies(a)%vx * bodies(a)%vx + bodies(a)%vy * bodies(a)%vy + bodies(a)%vz * bodies(a)%vz)
      do b = a + 1, 5
        dx = bodies(a)%x - bodies(b)%x
        dy = bodies(a)%y - bodies(b)%y
        dz = bodies(a)%z - bodies(b)%z
        e = e - bodies(a)%mass * bodies(b)%mass / sqrt(dx * dx + dy * dy + dz * dz)
      end do
    end do
  end function energy
end program nbody
