import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    a = [int(x) for x in data[1:n + 2]]
    MOD = 1000000007
    dp = [0] * (n + 1)
    dp[0] = 1
    for i in range(1, n + 1):
        if a[i] == 1:
            continue
        total = dp[i - 1]
        if i >= 2:
            total += dp[i - 2]
        if i >= 3:
            total += dp[i - 3]
        dp[i] = total % MOD
    print(dp[n])


main()
