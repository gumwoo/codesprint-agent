# 최단 거리는 맞게 구하지만 대피소에 갈 수 없는 교차로를 -1 대신 내부 무한대 값(10**18)으로 출력한다.
import sys
import heapq


def main():
    data = sys.stdin.buffer.read().split()
    n, m, k = int(data[0]), int(data[1]), int(data[2])
    pos = 3
    shelters = [int(x) - 1 for x in data[pos:pos + k]]
    pos += k
    adj = [[] for _ in range(n)]
    for _ in range(m):
        u = int(data[pos]) - 1
        v = int(data[pos + 1]) - 1
        w = int(data[pos + 2])
        pos += 3
        adj[u].append((v, w))
        adj[v].append((u, w))
    INF = 10 ** 18
    dist = [INF] * n
    heap = []
    for s in shelters:
        dist[s] = 0
        heap.append((0, s))
    heapq.heapify(heap)
    while heap:
        d, u = heapq.heappop(heap)
        if d > dist[u]:
            continue
        for v, w in adj[u]:
            nd = d + w
            if nd < dist[v]:
                dist[v] = nd
                heapq.heappush(heap, (nd, v))
    print(' '.join(str(x) for x in dist))


main()
