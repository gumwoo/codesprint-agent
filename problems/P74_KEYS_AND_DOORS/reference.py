import sys
from collections import deque


def main():
    data = sys.stdin.read().split()
    n, m = int(data[0]), int(data[1])
    grid = data[2:2 + n]
    sr = sc = -1
    for r in range(n):
        for c in range(m):
            if grid[r][c] == 'S':
                sr, sc = r, c
    dist = [[[-1] * 64 for _ in range(m)] for _ in range(n)]
    dist[sr][sc][0] = 0
    q = deque([(sr, sc, 0)])
    while q:
        r, c, mask = q.popleft()
        d = dist[r][c][mask]
        if grid[r][c] == 'T':
            print(d)
            return
        for dr, dc in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nr, nc = r + dr, c + dc
            if not (0 <= nr < n and 0 <= nc < m):
                continue
            ch = grid[nr][nc]
            if ch == '#':
                continue
            if 'A' <= ch <= 'F' and not (mask >> (ord(ch) - ord('A'))) & 1:
                continue
            nmask = mask
            if 'a' <= ch <= 'f':
                nmask = mask | (1 << (ord(ch) - ord('a')))
            if dist[nr][nc][nmask] == -1:
                dist[nr][nc][nmask] = d + 1
                q.append((nr, nc, nmask))
    print(-1)


main()
