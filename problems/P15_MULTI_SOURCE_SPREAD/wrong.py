# 남은 칸을 세지 않는다. 큐가 비면 다 채워진 것으로 본다.
import sys
from collections import deque

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
g = [[int(data[2 + i * m + j]) for j in range(m)] for i in range(n)]

q = deque()
for x in range(n):
    for y in range(m):
        if g[x][y] == 1:
            q.append((x, y))

step = 0
while q:
    moved = False
    for _ in range(len(q)):
        x, y = q.popleft()
        for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < n and 0 <= ny < m and g[nx][ny] == 0:
                g[nx][ny] = 1
                q.append((nx, ny))
                moved = True
    if moved:
        step += 1

print(step)
