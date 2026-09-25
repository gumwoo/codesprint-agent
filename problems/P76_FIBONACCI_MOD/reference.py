import sys


def pair(n, m):
    """(F(n), F(n+1)) 를 m 으로 나눈 나머지. n 을 절반으로 줄여 가며 구한다."""
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
    print(pair(n, m)[0])


main()
