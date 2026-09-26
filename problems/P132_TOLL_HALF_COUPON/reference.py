import sys
import heapq


def main():
    data = sys.stdin.buffer.read().split()
    n, m, k = int(data[0]), int(data[1]), int(data[2])
    adj = [[] for _ in range(n + 1)]
    p = 3
    for _ in range(m):
        u, v, w = int(data[p]), int(data[p + 1]), int(data[p + 2])
        p += 3
        adj[u].append((v, w))
        adj[v].append((u, w))
    INF = float("inf")
    dist = [[INF] * (k + 1) for _ in range(n + 1)]
    dist[1][0] = 0
    pq = [(0, 1, 0)]
    while pq:
        d, u, c = heapq.heappop(pq)
        if d > dist[u][c]:
            continue
        for v, w in adj[u]:
            nd = d + w
            if nd < dist[v][c]:
                dist[v][c] = nd
                heapq.heappush(pq, (nd, v, c))
            if c < k:
                nd = d + w // 2
                if nd < dist[v][c + 1]:
                    dist[v][c + 1] = nd
                    heapq.heappush(pq, (nd, v, c + 1))
    ans = min(dist[n])
    print(-1 if ans == INF else ans)


main()
