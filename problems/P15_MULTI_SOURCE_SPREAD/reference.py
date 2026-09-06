import sys
from collections import deque

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
g = [[int(data[2 + i * m + j]) for j in range(m)] for i in range(n)]

q = deque()
remaining = 0
for x in range(n):
    for y in range(m):
        if g[x][y] == 1:
            q.append((x, y))
        elif g[x][y] == 0:
            remaining += 1

step = 0
while q and remaining:
    for _ in range(len(q)):
        x, y = q.popleft()
        for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < n and 0 <= ny < m and g[nx][ny] == 0:
                # 큐에 넣는 시점에 1 로 바꾼다. 꺼낼 때 바꾸면 같은 칸이 여러 번 들어간다.
                g[nx][ny] = 1
                remaining -= 1
                q.append((nx, ny))
    step += 1

print(-1 if remaining else step)
