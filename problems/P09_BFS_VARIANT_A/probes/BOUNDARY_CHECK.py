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
        if dist[nx][ny] == 0 and g[nx][ny] == 1:
            dist[nx][ny] = dist[x][y] + 1
            q.append((nx, ny))
print(dist[n - 1][m - 1] or -1)
