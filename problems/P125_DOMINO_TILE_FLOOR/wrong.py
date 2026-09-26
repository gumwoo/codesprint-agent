# 이웃 칸을 볼 때 행 번호의 아래쪽 상한(nr < n)을 검사하지 않아 마지막 행에서 아래 칸을 읽다가 IndexError 가 난다.
import sys


def main():
    data = sys.stdin.read().split()
    n, m = int(data[0]), int(data[1])
    grid = data[2:2 + n]
    idx = {}
    for r in range(n):
        for c in range(m):
            if grid[r][c] == '.' and (r + c) % 2 == 1:
                idx[(r, c)] = len(idx)
    adj = []
    for r in range(n):
        for c in range(m):
            if grid[r][c] == '.' and (r + c) % 2 == 0:
                nb = []
                for dr, dc in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nr, nc = r + dr, c + dc
                    if 0 <= nr and 0 <= nc < m and grid[nr][nc] == '.':
                        nb.append(idx[(nr, nc)])
                adj.append(nb)
    left = len(adj)
    match_l = [-1] * left
    match_r = [-1] * len(idx)
    sys.setrecursionlimit(10000)
    seen = []

    def augment(u):
        for v in adj[u]:
            if not seen[v]:
                seen[v] = True
                if match_r[v] == -1 or augment(match_r[v]):
                    match_l[u] = v
                    match_r[v] = u
                    return True
        return False

    result = 0
    while True:
        seen = [False] * len(idx)
        found = False
        for u in range(left):
            if match_l[u] == -1 and augment(u):
                result += 1
                found = True
        if not found:
            break
    print(result)


main()
