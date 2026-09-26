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
            if 0 <= nr < n and 0 <= nc < m and grid[nr][nc] != '#' and dist[nr][nc] == -1:
                dist[nr][nc] = nd
                q.append((nr, nc))
    sys.stdout.write('\n'.join(' '.join(map(str, row)) for row in dist) + '\n')


main()
