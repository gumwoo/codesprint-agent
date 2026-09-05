import sys
from collections import deque

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
g = [[int(data[2 + i * m + j]) for j in range(m)] for i in range(n)]

if g[0][0] == 0:
    print(-1)
    raise SystemExit

INF = -1
dist = [[INF] * m for _ in range(n)]
dist[0][0] = 0
q = deque([(0, 0)])
best = 0
while q:
    x, y = q.popleft()
    best = max(best, dist[x][y])
    for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        nx, ny = x + dx, y + dy
        if 0 <= nx < n and 0 <= ny < m and g[nx][ny] == 1 and dist[nx][ny] == INF:
            dist[nx][ny] = dist[x][y] + 1
            q.append((nx, ny))
print(best)
