# Skill 대조 풀이(ADR-0033). 각 깃대마다 앞의 모든 깃대를 확인해 그 깃대로 끝나는 최대 개수를 구하는 O(N^2) 동적 계획법.
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
            if a[j] < ai and dp[j] + 1 > best:
                best = dp[j] + 1
        dp[i] = best
    print(max(dp))


main()
