import sys
from math import sqrt

def eval_A(i: int, j: int) -> float:
    return 1.0 / float((i + j) * (i + j + 1) // 2 + i + 1)

def eval_A_times_u(N: int, u: List[float], Au: List[float]):
    for i in range(N):
        s = 0.0
        for j in range(N):
            s += eval_A(i, j) * u[j]
        Au[i] = s

def eval_At_times_u(N: int, u: List[float], Au: List[float]):
    for i in range(N):
        s = 0.0
        for j in range(N):
            s += eval_A(j, i) * u[j]
        Au[i] = s

def eval_AtA_times_u(N: int, u: List[float], AtAu: List[float]):
    v = [0.0] * N
    eval_A_times_u(N, u, v)
    eval_At_times_u(N, v, AtAu)

def main():
    N = int(sys.argv[1]) if len(sys.argv) > 1 else 100
    u = [1.0] * N
    v = [0.0] * N
    for _ in range(10):
        eval_AtA_times_u(N, u, v)
        eval_AtA_times_u(N, v, u)
    vBv = 0.0
    vv = 0.0
    for i in range(N):
        vBv += u[i] * v[i]
        vv += v[i] * v[i]
    print(f"{sqrt(vBv / vv):.9f}")

main()
