import sys

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
# 격자 범위를 벗어난 접근 - 음수 인덱싱으로 감춰지지 않는 방향
grid = [[0] * m for _ in range(n)]
print(grid[n][0])
