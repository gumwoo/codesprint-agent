import sys


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    b = [1] + [int(x) for x in data[1:1 + n]] + [1]
    dp = [[0] * (n + 2) for _ in range(n + 2)]
    for length in range(1, n + 1):
        for l in range(1, n - length + 2):
            r = l + length - 1
            outer = b[l - 1] * b[r + 1]
            row = dp[l]
            best = 0
            for k in range(l, r + 1):
                v = row[k - 1] + dp[k + 1][r] + outer * b[k]
                if v > best:
                    best = v
            row[r] = best
    print(dp[1][n])


main()
