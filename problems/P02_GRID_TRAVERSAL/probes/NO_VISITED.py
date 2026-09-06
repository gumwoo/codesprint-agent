# NO_VISITED 를 담은 풀이. **의도적으로 틀렸다.**
#
# 방문 상태를 아예 기록하지 않는다. 같은 칸을 무한히 다시 넣는다.
#
# cases.json 의 probes 태그가 이 풀이들과 대조된다(tools/verify_problems.py).
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
        if 0 <= nx < n and 0 <= ny < m and g[nx][ny] == 1:
            visited[nx][ny] = True
            q.append((nx, ny))
print(count)
