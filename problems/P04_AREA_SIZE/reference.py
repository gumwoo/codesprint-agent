import sys
from collections import deque

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
g = [[int(data[2 + i * m + j]) for j in range(m)] for i in range(n)]

visited = [[False] * m for _ in range(n)]
best = 0
for si in range(n):
    for sj in range(m):
        if g[si][sj] != 1 or visited[si][sj]:
            continue
        visited[si][sj] = True
        q = deque([(si, sj)])
        size = 0
        while q:
            x, y = q.popleft()
            size += 1
            for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < n and 0 <= ny < m and not visited[nx][ny] and g[nx][ny] == 1:
                    visited[nx][ny] = True
                    q.append((nx, ny))
        best = max(best, size)
print(best)
