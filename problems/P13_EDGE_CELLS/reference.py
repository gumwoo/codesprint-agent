import sys

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
g = [[int(data[2 + i * m + j]) for j in range(m)] for i in range(n)]

count = 0
for x in range(n):
    for y in range(m):
        if g[x][y] != 1:
            continue
        ok = True
        for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            nx, ny = x + dx, y + dy
            if not (0 <= nx < n and 0 <= ny < m) or g[nx][ny] != 1:
                ok = False
                break
        if ok:
            count += 1
print(count)
