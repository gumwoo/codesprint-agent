# Skill 대조 풀이(ADR-0033). 통행권을 쓸 도로 min(K, M)개를 모든 조합으로 고르고, 조합마다 그 도로를 반값으로 바꾼 그래프에서 일반 다익스트라를 돌린다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys
import heapq
from itertools import combinations


def main():
    data = sys.stdin.read().split()
    n, m, k = int(data[0]), int(data[1]), int(data[2])
    edges = []
    for i in range(m):
        edges.append((int(data[3 + 3 * i]), int(data[4 + 3 * i]), int(data[5 + 3 * i])))
    INF = float("inf")
    best = INF
    for chosen in combinations(range(m), min(k, m)):
        half = set(chosen)
        adj = [[] for _ in range(n + 1)]
        for i, (u, v, w) in enumerate(edges):
            c = w // 2 if i in half else w
            adj[u].append((v, c))
            adj[v].append((u, c))
        dist = [INF] * (n + 1)
        dist[1] = 0
        pq = [(0, 1)]
        while pq:
            d, u = heapq.heappop(pq)
            if d > dist[u]:
                continue
            for v, c in adj[u]:
                if d + c < dist[v]:
                    dist[v] = d + c
                    heapq.heappush(pq, (d + c, v))
        best = min(best, dist[n])
    print(-1 if best == INF else best)


main()
