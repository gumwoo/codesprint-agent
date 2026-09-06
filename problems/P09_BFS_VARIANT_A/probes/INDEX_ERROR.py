# INDEX_ERROR 를 담은 풀이. **의도적으로 틀렸다.**
#
# 입력은 제대로 읽는다. 격자를 **참조할 때** 행과 열을 뒤바꾼다 -
# 경계 검사는 (행, 열) 로 하고 값은 (열, 행) 으로 읽는다.
#
# cases.json 의 probes 태그가 이 풀이들과 대조된다(tools/verify_problems.py).
import sys
from collections import deque

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
g = [[int(data[2 + i * m + j]) for j in range(m)] for i in range(n)]

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
        if 0 <= nx < n and 0 <= ny < m and dist[nx][ny] == 0 and g[ny][nx] == 1:
            dist[nx][ny] = dist[x][y] + 1
            q.append((nx, ny))
print(dist[n - 1][m - 1] or -1)
