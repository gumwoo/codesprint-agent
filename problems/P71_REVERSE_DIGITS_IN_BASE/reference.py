import sys


def main():
    n, b = map(int, sys.stdin.read().split())
    r = 0
    while n > 0:
        r = r * b + n % b
        n //= b
    print(r)


main()
