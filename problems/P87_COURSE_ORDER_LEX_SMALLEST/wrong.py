# 순서를 정하지 못하고 남은 과목이 있는지(사이클) 확인하지 않고, 정해진 일부 순서를 그대로 출력한다.
import sys
import heapq


def main():
    data = sys.stdin.buffer.read().split()
    n, m = int(data[0]), int(data[1])
    adj = [[] for _ in range(n + 1)]
    indeg = [0] * (n + 1)
    idx = 2
    for _ in range(m):
        a = int(data[idx])
        b = int(data[idx + 1])
        idx += 2
        adj[a].append(b)
        indeg[b] += 1
    heap = [v for v in range(1, n + 1) if indeg[v] == 0]
    heapq.heapify(heap)
    order = []
    while heap:
        v = heapq.heappop(heap)
        order.append(v)
        for w in adj[v]:
            indeg[w] -= 1
            if indeg[w] == 0:
                heapq.heappush(heap, w)
    print(' '.join(map(str, order)))


main()
