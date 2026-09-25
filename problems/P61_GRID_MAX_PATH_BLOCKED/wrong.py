# 마지막 행과 마지막 열에서 아래쪽 · 오른쪽 칸이 격자 안에 있는지 검사하지 않고 dp 를 읽어 IndexError 가 난다.
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
            if dp[i + 1][j] > best:
                best = dp[i + 1][j]
            if cur[j + 1] > best:
                best = cur[j + 1]
            if best >= 0:
                cur[j] = best + v
    print(dp[0][0])


main()
