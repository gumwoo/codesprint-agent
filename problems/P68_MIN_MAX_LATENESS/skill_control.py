# Skill 대조 풀이(ADR-0033). 정렬 기준 없이 가능한 모든 처리 순서(순열)를 만들어 각각의 최대 지연을 계산하고 최솟값을 고른다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys
from itertools import permutations

def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    t = [int(data[1 + 2 * i]) for i in range(n)]
    d = [int(data[2 + 2 * i]) for i in range(n)]
    best = None
    for order in permutations(range(n)):
        time = 0
        worst = 0
        for j in order:
            time += t[j]
            if time - d[j] > worst:
                worst = time - d[j]
        if best is None or worst < best:
            best = worst
    print(best)

main()
