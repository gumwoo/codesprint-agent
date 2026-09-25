import sys


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    a = list(map(int, data[1:1 + n]))
    dp = [0] * (n + 3)
    for i in range(n):
        dp[i + 3] = max(dp[i + 2], a[i] + dp[i])
    print(dp[n + 2])


main()
