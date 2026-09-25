import sys


def main():
    a, b, c, p, q = map(int, sys.stdin.read().split())
    count = 0
    for x in range(p + 1):
        rest = c - a * x
        if rest < 0:
            break
        if rest % b == 0 and rest // b <= q:
            count += 1
    print(count)


main()
