# 금액을 바깥 반복, 우표 종류를 안쪽 반복으로 두어 순서만 다른 같은 구성을 서로 다른 방법으로 센다.
import sys

MOD = 1_000_000_007


def main():
    data = sys.stdin.read().split()
    n, m = int(data[0]), int(data[1])
    coins = [int(x) for x in data[2:2 + n]]
    dp = [0] * (m + 1)
    dp[0] = 1
    for s in range(1, m + 1):
        for c in coins:
            if c <= s:
                dp[s] = (dp[s] + dp[s - c]) % MOD
    print(dp[m])


main()
