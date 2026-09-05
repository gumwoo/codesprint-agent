import sys
from collections import deque

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
g = [[int(data[2 + i * m + j]) for j in range(m)] for i in range(n)]

# 대각선까지 이어진 것으로 본다 (8방향) - ADVERSARIAL case 에서 갈린다.
visited = [[False] * m for _ in range(n)]
count = 0
dirs = [(-1, 0), (1, 0), (0, -1), (0, 1), (-1, -1), (-1, 1), (1, -1), (1, 1)]
for si in range(n):
    for sj in range(m):
        if g[si][sj] != 1 or visited[si][sj]:
            continue
        count += 1
        visited[si][sj] = True
        q = deque([(si, sj)])
        while q:
            x, y = q.popleft()
            for dx, dy in dirs:
                nx, ny = x + dx, y + dy
                if 0 <= nx < n and 0 <= ny < m and not visited[nx][ny] and g[nx][ny] == 1:
                    visited[nx][ny] = True
                    q.append((nx, ny))
print(count)
