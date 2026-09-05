# 큐가 아니라 스택을 쓴다. 먼저 닿은 경로가 최단이라는 보장이 사라진다.
import sys

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
g = [[int(data[2 + i * m + j]) for j in range(m)] for i in range(n)]

if g[0][0] == 0 or g[n - 1][m - 1] == 0:
    print(-1)
    raise SystemExit

dist = [[0] * m for _ in range(n)]
dist[0][0] = 1
stack = [(0, 0)]
while stack:
    x, y = stack.pop()
    for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        nx, ny = x + dx, y + dy
        if 0 <= nx < n and 0 <= ny < m and dist[nx][ny] == 0 and g[nx][ny] == 1:
            dist[nx][ny] = dist[x][y] + 1
            stack.append((nx, ny))
print(dist[n - 1][m - 1] or -1)
