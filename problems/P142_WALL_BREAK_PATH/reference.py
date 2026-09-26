import sys
from collections import deque

def main():
    data = sys.stdin.read().split()
    n, m, k = int(data[0]), int(data[1]), int(data[2])
    grid = data[3:3 + n]
    dist = [[[-1] * (k + 1) for _ in range(m)] for _ in range(n)]
    dist[0][0][0] = 0
    q = deque([(0, 0, 0)])
    while q:
        r, c, b = q.popleft()
        if r == n - 1 and c == m - 1:
            print(dist[r][c][b])
            return
        for dr, dc in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nr, nc = r + dr, c + dc
            if 0 <= nr < n and 0 <= nc < m:
                nb = b + (grid[nr][nc] == '#')
                if nb <= k and dist[nr][nc][nb] == -1:
                    dist[nr][nc][nb] = dist[r][c][b] + 1
                    q.append((nr, nc, nb))
    print(-1)

main()
