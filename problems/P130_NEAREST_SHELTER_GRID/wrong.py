# 이웃 좌표의 하한(0 이상)만 검사하고 상한(N, M 미만)을 검사하지 않아 아래쪽 · 오른쪽 끝 칸에서 격자 밖을 참조한다.
import sys
from collections import deque


def main():
    data = sys.stdin.read().split()
    n, m = int(data[0]), int(data[1])
    grid = data[2:2 + n]
    dist = [[-1] * m for _ in range(n)]
    q = deque()
    for r in range(n):
        for c in range(m):
            if grid[r][c] == 'S':
                dist[r][c] = 0
                q.append((r, c))
    while q:
        r, c = q.popleft()
        nd = dist[r][c] + 1
        for dr, dc in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nr, nc = r + dr, c + dc
            if nr >= 0 and nc >= 0 and grid[nr][nc] != '#' and dist[nr][nc] == -1:
                dist[nr][nc] = nd
                q.append((nr, nc))
    sys.stdout.write('\n'.join(' '.join(map(str, row)) for row in dist) + '\n')


main()
