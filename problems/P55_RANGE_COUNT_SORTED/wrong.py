# R 쪽 경계를 upper bound(R 초과가 처음 나오는 위치) 대신 lower bound(R 이상이 처음 나오는 위치)로 구해 R과 같은 원소를 세지 않는다.
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


def main():
    data = sys.stdin.buffer.read().split()
    n, q = int(data[0]), int(data[1])
    a = [int(x) for x in data[2:2 + n]]
    p = 2 + n
    out = []
    for _ in range(q):
        l, r = int(data[p]), int(data[p + 1])
        p += 2
        out.append(lower_bound(a, r) - lower_bound(a, l))
    sys.stdout.write("\n".join(map(str, out)) + "\n")


main()
