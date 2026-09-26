# Skill 대조 풀이(ADR-0033). 각 책마다 앞의 모든 책을 보고 그 책으로 끝나는 가장 긴 줄어들지 않는 부분 수열 길이를 갱신하는 O(N^2) 동적 계획법
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    a = list(map(int, data[1:1 + n]))
    dp = [1] * n
    for i in range(n):
        ai = a[i]
        best = 1
        for j in range(i):
            if a[j] <= ai and dp[j] + 1 > best:
                best = dp[j] + 1
        dp[i] = best
    print(n - max(dp))


main()
