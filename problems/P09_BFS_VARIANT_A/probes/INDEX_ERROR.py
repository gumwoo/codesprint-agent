# INDEX_ERROR 를 담은 풀이. **의도적으로 틀렸다.**
#
# 경계 검사와 무관한 인덱싱 실수. 격자를 읽을 때 행/열을 뒤바꿨다.
#
# cases.json 의 probes 태그가 이 풀이들과 대조된다(tools/verify_problems.py).
import sys
from collections import deque

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
# 행/열을 뒤바꿔 읽는다.
g = [[int(data[2 + j * m + i]) for j in range(m)] for i in range(n)]

if g[0][0] == 0 or g[n - 1][m - 1] == 0:
    print(-1)
    raise SystemExit

dirs = [(-1, 0), (1, 0), (0, -1), (0, 1), (-1, -1), (-1, 1), (1, -1), (1, 1)]
dist = [[0] * m for _ in range(n)]
dist[0][0] = 1
q = deque([(0, 0)])
while q:
    x, y = q.popleft()
    for dx, dy in dirs:
        nx, ny = x + dx, y + dy
        if 0 <= nx < n and 0 <= ny < m and dist[nx][ny] == 0 and g[nx][ny] == 1:
            dist[nx][ny] = dist[x][y] + 1
            q.append((nx, ny))
print(dist[n - 1][m - 1] or -1)
