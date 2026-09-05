# 방문 처리를 꺼낼 때 한다. 같은 칸이 큐에 여러 번 들어가 있으므로, 꺼낼 때마다
# 거리를 더하면 중복만큼 합이 커진다. 이 드릴이 노리는 실수다.
#
# 평범한 BFS 에서 방문 시점 오류는 보통 "느려질 뿐" 답이 바뀌지 않는다.
# 이 문제는 꺼내는 횟수가 답에 직접 반영되도록 설계해 그 차이를 드러낸다.
import sys
from collections import deque

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
g = [[int(data[2 + i * m + j]) for j in range(m)] for i in range(n)]

if g[0][0] == 0:
    print(0)
    raise SystemExit

INF = -1
dist = [[INF] * m for _ in range(n)]
visited = [[False] * m for _ in range(n)]
dist[0][0] = 0
q = deque([(0, 0)])
total = 0
while q:
    x, y = q.popleft()
    total += dist[x][y]
    if visited[x][y]:
        continue
    visited[x][y] = True
    for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        nx, ny = x + dx, y + dy
        if 0 <= nx < n and 0 <= ny < m and g[nx][ny] == 1 and not visited[nx][ny]:
            dist[nx][ny] = dist[x][y] + 1
            q.append((nx, ny))
print(total)
