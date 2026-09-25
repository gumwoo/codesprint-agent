# 표에 빈 접두사용 0 행 · 0 열을 두지 않고 dp[i-1][j-1] 을 그대로 읽어, i 나 j 가 0 일 때 Python 음수 인덱스가 이미 채운 다른 칸의 값을 가져온다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, m = int(data[0]), int(data[1])
    a = list(map(int, data[2:2 + n]))
    b = list(map(int, data[2 + n:2 + n + m]))
    dp = [[0] * m for _ in range(n)]
    for i in range(n):
        for j in range(m):
            if a[i] == b[j]:
                dp[i][j] = dp[i - 1][j - 1] + 1
            else:
                dp[i][j] = max(dp[i - 1][j], dp[i][j - 1])
    print(dp[n - 1][m - 1])


main()
