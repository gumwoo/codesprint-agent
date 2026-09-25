# i 번 날에 일할 때 이전 상태를 dp[i] 대신 dp[i+1] 에서 가져와, 하루만 쉬면 다시 일할 수 있는 것으로 계산한다.
import sys


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    a = list(map(int, data[1:1 + n]))
    dp = [0] * (n + 3)
    for i in range(n):
        dp[i + 3] = max(dp[i + 2], a[i] + dp[i + 1])
    print(dp[n + 2])


main()
