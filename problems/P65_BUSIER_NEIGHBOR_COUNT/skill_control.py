# Skill 대조 풀이(ADR-0033). 인접 리스트 없이 간선 목록만 저장하고, 정점마다 간선 전체를 훑어 이웃을 찾는다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, m = int(data[0]), int(data[1])
    edges = []
    deg = [0] * (n + 1)
    idx = 2
    for _ in range(m):
        a = int(data[idx])
        b = int(data[idx + 1])
        idx += 2
        edges.append((a, b))
        deg[a] += 1
        deg[b] += 1
    out = []
    for v in range(1, n + 1):
        c = 0
        for a, b in edges:
            if a == v:
                w = b
            elif b == v:
                w = a
            else:
                continue
            if deg[w] > deg[v]:
                c += 1
        out.append(c)
    print(' '.join(map(str, out)))


main()
