# Skill 대조 풀이(ADR-0033). 대피소마다 격자 BFS 를 따로 돌리고 각 칸에서 그 결과들의 최솟값을 취한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys
from collections import deque


def main():
    data = sys.stdin.read().split()
    n, m = int(data[0]), int(data[1])
    grid = data[2:2 + n]
    best = [[-1] * m for _ in range(n)]
    for sr in range(n):
        for sc in range(m):
            if grid[sr][sc] != 'S':
                continue
            seen = [[-1] * m for _ in range(n)]
            seen[sr][sc] = 0
            q = deque([(sr, sc)])
            while q:
                r, c = q.popleft()
                for nr, nc in ((r + 1, c), (r - 1, c), (r, c + 1), (r, c - 1)):
                    if 0 <= nr < n and 0 <= nc < m and grid[nr][nc] != '#' and seen[nr][nc] == -1:
                        seen[nr][nc] = seen[r][c] + 1
                        q.append((nr, nc))
            for r in range(n):
                for c in range(m):
                    d = seen[r][c]
                    if d != -1 and (best[r][c] == -1 or d < best[r][c]):
                        best[r][c] = d
    print('\n'.join(' '.join(map(str, row)) for row in best))


main()
