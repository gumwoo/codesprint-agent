# Skill 대조 풀이(ADR-0033). 끝나는 시각 순으로 놓고, 각 회의를 마지막으로 고르는 경우의 최대 개수를 앞의 모든 회의와 비교해 구하는 O(N^2) 동적 계획법.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    meetings = []
    for i in range(n):
        s = int(data[1 + 2 * i])
        e = int(data[2 + 2 * i])
        meetings.append((e, s))
    meetings.sort()
    dp = [0] * n
    for i in range(n):
        s_i = meetings[i][1]
        best = 1
        for j in range(i):
            if meetings[j][0] <= s_i and dp[j] + 1 > best:
                best = dp[j] + 1
        dp[i] = best
    print(max(dp))


main()
