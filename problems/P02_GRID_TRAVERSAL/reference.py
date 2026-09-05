import sys
from collections import deque

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
g = [[int(data[2 + i * m + j]) for j in range(m)] for i in range(n)]

if g[0][0] == 0:
    print(0)
    raise SystemExit

visited = [[False] * m for _ in range(n)]
visited[0][0] = True
q = deque([(0, 0)])
count = 0
while q:
    x, y = q.popleft()
    count += 1
    for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        nx, ny = x + dx, y + dy
        if 0 <= nx < n and 0 <= ny < m and not visited[nx][ny] and g[nx][ny] == 1:
            visited[nx][ny] = True
            q.append((nx, ny))
print(count)
