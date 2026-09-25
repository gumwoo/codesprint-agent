import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, m = int(data[0]), int(data[1])
    t = list(map(int, data[2:2 + n]))
    lo, hi = 1, min(t) * m
    while lo < hi:
        mid = (lo + hi) // 2
        made = 0
        for x in t:
            made += mid // x
            if made >= m:
                break
        if made >= m:
            hi = mid
        else:
            lo = mid + 1
    print(lo)


main()
