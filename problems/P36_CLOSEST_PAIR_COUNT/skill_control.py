# Skill 대조 풀이(ADR-0033). 정렬 없이 모든 위치 쌍의 차이를 직접 계산해 최솟값과 개수를 구한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys
from itertools import combinations


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    a = [int(x) for x in data[1:1 + n]]
    best = min(abs(x - y) for x, y in combinations(a, 2))
    count = sum(1 for x, y in combinations(a, 2) if abs(x - y) == best)
    print(best, count)


main()
