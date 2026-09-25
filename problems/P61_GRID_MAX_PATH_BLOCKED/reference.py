import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, m = int(data[0]), int(data[1])
    g = [list(map(int, data[2 + i * m:2 + (i + 1) * m])) for i in range(n)]
    dp = [[-1] * m for _ in range(n)]
    for i in range(n - 1, -1, -1):
        row = g[i]
        cur = dp[i]
        for j in range(m - 1, -1, -1):
            v = row[j]
            if v < 0:
                continue
            if i == n - 1 and j == m - 1:
                cur[j] = v
                continue
            best = -1
            if i + 1 < n and dp[i + 1][j] > best:
                best = dp[i + 1][j]
            if j + 1 < m and cur[j + 1] > best:
                best = cur[j + 1]
            if best >= 0:
                cur[j] = best + v
    print(dp[0][0])


main()
