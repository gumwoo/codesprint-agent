# 구간을 나누는 위치를 range(i, j - 1) 로 돌려 마지막 분할 위치를 빼먹는다.
import sys


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    w = list(map(int, data[1:1 + n]))
    pre = [0] * (n + 1)
    for i in range(n):
        pre[i + 1] = pre[i] + w[i]
    dp = [[0] * n for _ in range(n)]
    for length in range(2, n + 1):
        for i in range(n - length + 1):
            j = i + length - 1
            row = dp[i]
            total = pre[j + 1] - pre[i]
            best = 10 ** 18
            for k in range(i, j - 1):
                a = pre[k + 1] - pre[i]
                b = total - a
                c = row[k] + dp[k + 1][j] + (a - b if a > b else b - a)
                if c < best:
                    best = c
            row[j] = best
    print(dp[0][n - 1])


main()
