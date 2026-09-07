# 이웃이 격자 안에 있는지 보지 않는다. 음수 인덱스가 반대쪽 끝을 가리킨다.
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
            nx, ny = (x + dx) % n, (y + dy) % m
            if g[nx][ny] != 1:
                ok = False
                break
        if ok:
            count += 1
print(count)
