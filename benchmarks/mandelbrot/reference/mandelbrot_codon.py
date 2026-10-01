import sys

def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 3000
    dn = float(n)
    inside = 0
    total = 0
    for y in range(n):
        ci = 2.0 * float(y) / dn - 1.0
        for x in range(n):
            cr = 2.0 * float(x) / dn - 1.5
            zr = 0.0
            zi = 0.0
            tr = 0.0
            ti = 0.0
            i = 0
            while i < 100 and tr + ti <= 4.0:
                zi = 2.0 * zr * zi + ci
                zr = tr - ti + cr
                tr = zr * zr
                ti = zi * zi
                i += 1
            total += i
            if i == 100:
                inside += 1
    print(inside, total)

main()
