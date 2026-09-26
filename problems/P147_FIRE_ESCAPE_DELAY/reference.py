import sys
from collections import deque


def main():
    data = sys.stdin.read().split()
    n, m = int(data[0]), int(data[1])
    grid = data[2:2 + n]
    INF = float('inf')
    fire = [[INF] * m for _ in range(n)]
    q = deque()
    for r in range(n):
        for c in range(m):
            ch = grid[r][c]
            if ch == 'F':
                fire[r][c] = 0
                q.append((r, c))
            elif ch == 'S':
                sr, sc = r, c
            elif ch == 'E':
                er, ec = r, c
    moves = ((1, 0), (-1, 0), (0, 1), (0, -1))
    while q:
        r, c = q.popleft()
        for dr, dc in moves:
            nr, nc = r + dr, c + dc
            if 0 <= nr < n and 0 <= nc < m and grid[nr][nc] != '#' and fire[nr][nc] == INF:
                fire[nr][nc] = fire[r][c] + 1
                q.append((nr, nc))

    def can_escape(wait):
        if wait >= fire[sr][sc]:
            return False
        seen = [[False] * m for _ in range(n)]
        seen[sr][sc] = True
        q = deque([(sr, sc, wait)])
        while q:
            r, c, t = q.popleft()
            if r == er and c == ec:
                return True
            for dr, dc in moves:
                nr, nc = r + dr, c + dc
                if 0 <= nr < n and 0 <= nc < m and not seen[nr][nc] and grid[nr][nc] != '#' and t + 1 < fire[nr][nc]:
                    seen[nr][nc] = True
                    q.append((nr, nc, t + 1))
        return False

    limit = n * m
    if can_escape(limit):
        print('INF')
        return
    if not can_escape(0):
        print(-1)
        return
    lo, hi = 0, limit
    while hi - lo > 1:
        mid = (lo + hi) // 2
        if can_escape(mid):
            lo = mid
        else:
            hi = mid
    print(lo)


main()
