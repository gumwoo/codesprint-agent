import sys

MOD = 1_000_000_007


def main():
    data = sys.stdin.read().split()
    n, m = int(data[0]), int(data[1])
    coins = [int(x) for x in data[2:2 + n]]
    dp = [0] * (m + 1)
    dp[0] = 1
    for c in coins:
        for s in range(c, m + 1):
            dp[s] = (dp[s] + dp[s - c]) % MOD
    print(dp[m])


main()
