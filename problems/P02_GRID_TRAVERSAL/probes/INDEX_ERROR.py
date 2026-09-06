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
        if 0 <= nx < n and 0 <= ny < m and not visited[nx][ny] and g[ny][nx] == 1:
            visited[nx][ny] = True
            q.append((nx, ny))
print(count)
