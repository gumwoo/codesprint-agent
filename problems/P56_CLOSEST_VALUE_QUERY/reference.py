import sys
from bisect import bisect_left


def main():
    data = sys.stdin.buffer.read().split()
    n, q = int(data[0]), int(data[1])
    a = list(map(int, data[2:2 + n]))
    xs = data[2 + n:2 + n + q]
    out = []
    for t in xs:
        x = int(t)
        i = bisect_left(a, x)
        if i == n:
            out.append(a[n - 1])
        elif i == 0:
            out.append(a[0])
        else:
            lo = a[i - 1]
            hi = a[i]
            out.append(lo if x - lo <= hi - x else hi)
    sys.stdout.write("\n".join(map(str, out)) + "\n")


main()
