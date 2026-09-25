import sys
from collections import deque


def main():
    data = sys.stdin.read().split()
    n, m, k = int(data[0]), int(data[1]), int(data[2])
    grid = data[3:3 + n]
    seen = [[[False] * (k + 1) for _ in range(m)] for _ in range(n)]
    seen[0][0][0] = True
    q = deque([(0, 0, 0, 0)])
    while q:
        r, c, b, d = q.popleft()
        if r == n - 1 and c == m - 1:
            print(d)
            return
        for dr, dc in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nr, nc = r + dr, c + dc
            if 0 <= nr < n and 0 <= nc < m:
                nb = b + (grid[nr][nc] == '1')
                if nb <= k and not seen[nr][nc][nb]:
                    seen[nr][nc][nb] = True
                    q.append((nr, nc, nb, d + 1))
    print(-1)


main()
