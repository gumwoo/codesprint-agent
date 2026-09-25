import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, m = int(data[0]), int(data[1])
    diff = [0] * (n + 2)
    idx = 2
    for _ in range(m):
        l = int(data[idx])
        r = int(data[idx + 1])
        v = int(data[idx + 2])
        idx += 3
        diff[l] += v
        diff[r + 1] -= v
    out = []
    cur = 0
    for i in range(1, n + 1):
        cur += diff[i]
        out.append(cur)
    print(' '.join(map(str, out)))


main()
