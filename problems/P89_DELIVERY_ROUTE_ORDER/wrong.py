# i 번에서 j 번으로 가는 시간으로 T[i][j] 대신 T[j][i] 를 읽어 행과 열을 뒤바꿨다.
import sys


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    t = [[int(data[1 + i * n + j]) for j in range(n)] for i in range(n)]
    INF = float('inf')
    full = 1 << n
    dp = [[INF] * n for _ in range(full)]
    dp[1][0] = 0
    for mask in range(1, full, 2):
        row = dp[mask]
        for last in range(n):
            cur = row[last]
            if cur == INF:
                continue
            tl = [t[k][last] for k in range(n)]
            for nxt in range(n):
                if mask >> nxt & 1:
                    continue
                nm = mask | (1 << nxt)
                v = cur + tl[nxt]
                if v < dp[nm][nxt]:
                    dp[nm][nxt] = v
    print(min(dp[full - 1]))


main()
