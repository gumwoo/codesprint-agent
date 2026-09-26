# Skill 대조 풀이(ADR-0033). 1번을 포함하고 N번을 포함하지 않는 정점 집합을 모두 나열해 그 집합에서 밖으로 나가는 간선 용량 합의 최솟값을 구한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.read().split()
    n, m = int(data[0]), int(data[1])
    edges = []
    for i in range(m):
        u, v, c = int(data[2 + 3 * i]), int(data[3 + 3 * i]), int(data[4 + 3 * i])
        edges.append((u - 1, v - 1, c))
    best = None
    middle = n - 2
    for mask in range(1 << middle):
        side = 1 | (mask << 1)
        cut = 0
        for u, v, c in edges:
            if (side >> u) & 1 and not (side >> v) & 1:
                cut += c
        if best is None or cut < best:
            best = cut
    print(best)


main()
