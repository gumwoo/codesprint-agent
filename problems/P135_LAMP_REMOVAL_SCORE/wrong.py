# 구간에서 마지막에 끄는 등불의 점수를 구간 바깥 양옆 등불이 아니라 처음 줄에서의 양옆 등불 밝기로 계산한다.
import sys


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    b = [1] + [int(x) for x in data[1:1 + n]] + [1]
    dp = [[0] * (n + 2) for _ in range(n + 2)]
    for length in range(1, n + 1):
        for l in range(1, n - length + 2):
            r = l + length - 1
            row = dp[l]
            best = 0
            for k in range(l, r + 1):
                v = row[k - 1] + dp[k + 1][r] + b[k - 1] * b[k] * b[k + 1]
                if v > best:
                    best = v
            row[r] = best
    print(dp[1][n])


main()
