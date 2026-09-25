import sys
import heapq


def main():
    data = sys.stdin.buffer.read().split()
    n, m, s = int(data[0]), int(data[1]), int(data[2])
    adj = [[] for _ in range(n + 1)]
    idx = 3
    for _ in range(m):
        u = int(data[idx])
        v = int(data[idx + 1])
        w = int(data[idx + 2])
        idx += 3
        adj[u].append((v, w))
    INF = 1 << 60
    dist = [INF] * (n + 1)
    dist[s] = 0
    pq = [(0, s)]
    while pq:
        d, u = heapq.heappop(pq)
        if d > dist[u]:
            continue
        for v, w in adj[u]:
            nd = d + w
            if nd < dist[v]:
                dist[v] = nd
                heapq.heappush(pq, (nd, v))
    out = [str(dist[i]) if dist[i] != INF else '-1' for i in range(1, n + 1)]
    sys.stdout.write('\n'.join(out) + '\n')


main()
