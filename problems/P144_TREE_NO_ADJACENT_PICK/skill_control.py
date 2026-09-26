# Skill 대조 풀이(ADR-0033). 정점 조합을 크기별로 모두 나열해 인접한 쌍이 없는 조합의 점수 합 최댓값을 구한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys
from itertools import combinations


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    w = [int(x) for x in data[1:1 + n]]
    nb = [set() for _ in range(n)]
    idx = 1 + n
    for _ in range(n - 1):
        a = int(data[idx]) - 1
        b = int(data[idx + 1]) - 1
        idx += 2
        nb[a].add(b)
        nb[b].add(a)
    best = 0
    for k in range(1, n + 1):
        for comb in combinations(range(n), k):
            ok = True
            for i in range(k):
                ci = nb[comb[i]]
                for j in range(i + 1, k):
                    if comb[j] in ci:
                        ok = False
                        break
                if not ok:
                    break
            if ok:
                s = 0
                for x in comb:
                    s += w[x]
                if s > best:
                    best = s
    print(best)


main()
