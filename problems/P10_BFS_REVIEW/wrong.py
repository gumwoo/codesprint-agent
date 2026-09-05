# 도달 여부를 따지지 않고 격자 전체에서 맨해튼 거리 최대를 고른다.
import sys

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
g = [[int(data[2 + i * m + j]) for j in range(m)] for i in range(n)]

if g[0][0] == 0:
    print(-1)
    raise SystemExit

best = 0
for i in range(n):
    for j in range(m):
        if g[i][j] == 1:
            best = max(best, i + j)
print(best)
