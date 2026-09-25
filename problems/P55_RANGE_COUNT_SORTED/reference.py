import sys


def lower_bound(a, x):
    lo, hi = 0, len(a)
    while lo < hi:
        mid = (lo + hi) // 2
        if a[mid] < x:
            lo = mid + 1
        else:
            hi = mid
    return lo


def upper_bound(a, x):
    lo, hi = 0, len(a)
    while lo < hi:
        mid = (lo + hi) // 2
        if a[mid] <= x:
            lo = mid + 1
        else:
            hi = mid
    return lo


def main():
    data = sys.stdin.buffer.read().split()
    n, q = int(data[0]), int(data[1])
    a = [int(x) for x in data[2:2 + n]]
    p = 2 + n
    out = []
    last = 0
    for _ in range(q):
        l, r = int(data[p]) ^ last, int(data[p + 1]) ^ last
        p += 2
        last = upper_bound(a, r) - lower_bound(a, l)
        out.append(last)
    sys.stdout.write("\n".join(map(str, out)) + "\n")


main()
