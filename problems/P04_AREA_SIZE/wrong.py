import sys
from collections import deque

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
g = [[int(data[2 + i * m + j]) for j in range(m)] for i in range(n)]

# 첫 영역을 찾으면 거기서 멈춘다 - 더 큰 영역이 뒤에 있으면 틀린다.
visited = [[False] * m for _ in range(n)]
for si in range(n):
    for sj in range(m):
        if g[si][sj] != 1:
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
        print(size)
        raise SystemExit
print(0)
