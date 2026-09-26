# 방문 기록을 좌표 (행, 열) 로만 해서, 벽을 더 많이 부수고 먼저 도착한 경로가 같은 칸을 차지하면 벽을 덜 부순 뒤늦은 경로를 버린다.
import sys
from collections import deque

def main():
    data = sys.stdin.read().split()
    n, m, k = int(data[0]), int(data[1]), int(data[2])
    grid = data[3:3 + n]
    dist = [[-1] * m for _ in range(n)]
    dist[0][0] = 0
    q = deque([(0, 0, 0)])
    while q:
        r, c, b = q.popleft()
        if r == n - 1 and c == m - 1:
            print(dist[r][c])
            return
        for dr, dc in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nr, nc = r + dr, c + dc
            if 0 <= nr < n and 0 <= nc < m:
                nb = b + (grid[nr][nc] == '#')
                if nb <= k and dist[nr][nc] == -1:
                    dist[nr][nc] = dist[r][c] + 1
                    q.append((nr, nc, nb))
    print(-1)

main()
