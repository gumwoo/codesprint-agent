# 방문 여부를 부순 벽 개수 없이 좌표만으로 기록한다. 벽을 먼저 부수고 도착한 경로가 칸을 선점해, 벽을 아끼고 늦게 도착하는 경로를 막는다.
import sys
from collections import deque


def main():
    data = sys.stdin.read().split()
    n, m, k = int(data[0]), int(data[1]), int(data[2])
    grid = data[3:3 + n]
    seen = [[False] * m for _ in range(n)]
    seen[0][0] = True
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
                if nb <= k and not seen[nr][nc]:
                    seen[nr][nc] = True
                    q.append((nr, nc, nb, d + 1))
    print(-1)


main()
