# NO_VISITED 를 담은 풀이. **의도적으로 틀렸다.**
#
# 방문 배열이 아예 없다. 같은 칸이 큐에 무한히 다시 들어가 탐색이 끝나지 않는다.
#
# cases.json 의 probes 태그가 이 풀이들과 대조된다(tools/verify_problems.py).
import sys
from collections import deque

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
g = [[int(data[2 + i * m + j]) for j in range(m)] for i in range(n)]

count = 0
for si in range(n):
    for sj in range(m):
        if g[si][sj] != 1:
            continue
        count += 1
        q = deque([(si, sj)])
        while q:
            x, y = q.popleft()
            for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < n and 0 <= ny < m and g[nx][ny] == 1:
                    q.append((nx, ny))
print(count)
