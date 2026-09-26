# Skill 대조 풀이(ADR-0033). 남아 있는 등불 집합을 비트마스크로 두고 다음에 끌 등불을 모두 시도하는 메모이제이션 탐색 (상태 수 2^N).
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys
from functools import lru_cache


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    a = [int(x) for x in data[1:1 + n]]
    sys.setrecursionlimit(10000)

    @lru_cache(maxsize=None)
    def go(mask):
        if mask == 0:
            return 0
        idxs = [i for i in range(n) if mask >> i & 1]
        best = 0
        for p, i in enumerate(idxs):
            left = a[idxs[p - 1]] if p > 0 else 1
            right = a[idxs[p + 1]] if p + 1 < len(idxs) else 1
            v = left * a[i] * right + go(mask & ~(1 << i))
            if v > best:
                best = v
        return best

    print(go((1 << n) - 1))


main()
