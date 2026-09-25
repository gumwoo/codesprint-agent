import sys


def solve(a, n, m):
    if n == 0:
        return 0, 1 % m
    s, p = solve(a, n // 2, m)
    s2 = (s + s * p) % m
    p2 = p * p % m
    if n % 2 == 1:
        s2 = (s2 + p2) % m
        p2 = p2 * a % m
    return s2, p2


def main():
    a, n, m = map(int, sys.stdin.read().split())
    a %= m
    print(solve(a, n, m)[0])


main()
