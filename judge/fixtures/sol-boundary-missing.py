from collections import deque
import sys

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
grid = [[int(data[2 + i * m + j]) for j in range(m)] for i in range(n)]
visited = [[False] * m for _ in range(n)]
dx = [-1, 1, 0, 0]
dy = [0, 0, -1, 1]

best = 0
for si in range(n):
    for sj in range(m):
        if grid[si][sj] != 1 or visited[si][sj]:
            continue
        visited[si][sj] = True
        q = deque([(si, sj)])
        size = 0
        while q:
            x, y = q.popleft()
            size += 1
            for k in range(4):
                nx, ny = x + dx[k], y + dy[k]
                # 경계 검사가 없다 -> IndexError
                if not visited[nx][ny] and grid[nx][ny] == 1:
                    visited[nx][ny] = True
                    q.append((nx, ny))
        best = max(best, size)
print(best)
