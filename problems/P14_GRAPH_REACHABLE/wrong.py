# 간선을 한 방향으로만 넣는다. 방향 없는 그래프인데 절반만 따라간다.
import sys
from collections import deque

data = sys.stdin.read().split()
n, e = int(data[0]), int(data[1])
adjacent = [[] for _ in range(n + 1)]
for i in range(e):
    u, v = int(data[2 + i * 2]), int(data[3 + i * 2])
    adjacent[u].append(v)

visited = [False] * (n + 1)
visited[1] = True
q = deque([1])
count = 0
while q:
    node = q.popleft()
    count += 1
    for nxt in adjacent[node]:
        if not visited[nxt]:
            visited[nxt] = True
            q.append(nxt)
print(count)
