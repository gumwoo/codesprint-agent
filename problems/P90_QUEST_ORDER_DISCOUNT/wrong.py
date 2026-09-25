# 할인 행렬의 행과 열을 뒤바꿔 d[j][i] 대신 d[i][j] 를 더한다.
import sys


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    t = [int(x) for x in data[1:1 + n]]
    p = 1 + n
    d = [[int(x) for x in data[p + i * n:p + (i + 1) * n]] for i in range(n)]
    full = 1 << n
    bonus = [None] * full
    bonus[0] = [0] * n
    for mask in range(1, full):
        low = mask & -mask
        j = low.bit_length() - 1
        prev = bonus[mask ^ low]
        bonus[mask] = [prev[i] + d[i][j] for i in range(n)]
    INF = 1 << 60
    dp = [INF] * full
    dp[0] = 0
    for mask in range(full):
        cur = dp[mask]
        if cur == INF:
            continue
        b = bonus[mask]
        for i in range(n):
            bit = 1 << i
            if mask & bit:
                continue
            c = t[i] - b[i]
            if c < 1:
                c = 1
            nxt = mask | bit
            if cur + c < dp[nxt]:
                dp[nxt] = cur + c
    print(dp[full - 1])


main()
