# BOUNDARY_CHECK 를 담은 풀이. **의도적으로 틀렸다.**
#
# 이동 후 좌표가 격자 밖인지 검사하지 않는다. Python 의 음수 인덱싱 때문에
# 위/왼쪽으로 나가면 반대쪽 끝을 읽어 조용히 틀린 답이 되고, 아래/오른쪽으로
# 나가면 IndexError 로 터진다.
#
# cases.json 의 probes 태그가 이 풀이로 검증된다(tools/verify_problems.py).
import sys
from collections import deque

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
g = [[int(data[2 + i * m + j]) for j in range(m)] for i in range(n)]

visited = [[False] * m for _ in range(n)]
best = 0
for si in range(n):
    for sj in range(m):
        if g[si][sj] != 1 or visited[si][sj]:
            continue
        visited[si][sj] = True
        q = deque([(si, sj)])
        size = 0
        while q:
            x, y = q.popleft()
            size += 1
            for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                nx, ny = x + dx, y + dy
                if not visited[nx][ny] and g[nx][ny] == 1:
                    visited[nx][ny] = True
                    q.append((nx, ny))
        best = max(best, size)
print(best)
