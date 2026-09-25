import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    k = int(data[1])
    INF = float("inf")
    best = [INF] * (k + 1)
    best[0] = 0
    idx = 2
    for _ in range(n):
        s = int(data[idx])
        c = int(data[idx + 1])
        idx += 2
        for j in range(k, 0, -1):
            p = j - s
            if p < 0:
                p = 0
            v = best[p] + c
            if v < best[j]:
                best[j] = v
    print(best[k] if best[k] != INF else -1)


main()
