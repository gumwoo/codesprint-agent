# 방문 표시를 큐에서 꺼낼 때 수행하고 중복 처리를 걸러내지 않는다
import sys
from collections import deque


def main():
    data = sys.stdin.read().split()
    idx = 0
    n = int(data[idx]); idx += 1
    m = int(data[idx]); idx += 1
    s = int(data[idx]); idx += 1
    adj = [set() for _ in range(n + 1)]
    for _ in range(m):
        u = int(data[idx]); idx += 1
        v = int(data[idx]); idx += 1
        adj[u].add(v)
        adj[v].add(u)
    graph = [sorted(adj[i]) for i in range(n + 1)]

    visited = [False] * (n + 1)
    order = []
    q = deque([s])
    while q:
        cur = q.popleft()
        visited[cur] = True
        order.append(cur)
        for nxt in graph[cur]:
            if not visited[nxt]:
                q.append(nxt)
    print(' '.join(map(str, order)))


main()
