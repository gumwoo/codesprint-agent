# Skill 대조 풀이(ADR-0033). 현재 덩어리 배열을 상태로 삼아 인접한 두 덩어리를 합치는 모든 순서를 재귀로 탐색하고, 같은 배열 상태는 메모이제이션한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys
from functools import lru_cache


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    w = tuple(map(int, data[1:1 + n]))
    sys.setrecursionlimit(10000)

    @lru_cache(maxsize=None)
    def solve(s):
        if len(s) == 1:
            return 0
        best = None
        for p in range(len(s) - 1):
            merged = s[:p] + (s[p] + s[p + 1],) + s[p + 2:]
            c = abs(s[p] - s[p + 1]) + solve(merged)
            if best is None or c < best:
                best = c
        return best

    print(solve(w))


main()
