# F(0) 을 1 로 본다 - 수열의 시작을 헷갈려 n 이 0 인 경우를 1 로 처리한다.
import sys


def pair(n, m):
    if n == 0:
        return 0, 1 % m
    half_value, half_next = pair(n // 2, m)
    even = half_value * (2 * half_next - half_value) % m
    odd = (half_value * half_value + half_next * half_next) % m
    if n % 2 == 0:
        return even, odd
    return odd, (even + odd) % m


def main():
    n, m = map(int, sys.stdin.read().split())
    if n == 0:
        print(1 % m)
        return
    print(pair(n, m)[0])


main()
