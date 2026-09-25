# n 이 홀수일 때 절반 두 개를 합친 뒤 마지막 항 a^(n-1) 을 더하지 않는다.
import sys


def solve(a, n, m):
    if n == 0:
        return 0, 1 % m
    s, p = solve(a, n // 2, m)
    s2 = (s + s * p) % m
    p2 = p * p % m
    if n % 2 == 1:
        p2 = p2 * a % m
    return s2, p2


def main():
    a, n, m = map(int, sys.stdin.read().split())
    a %= m
    print(solve(a, n, m)[0])


main()
