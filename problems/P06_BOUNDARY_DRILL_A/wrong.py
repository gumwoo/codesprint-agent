# 위/왼쪽만 검사한다. 아래/오른쪽 경계를 빠뜨렸다.
import sys

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
r, c = int(data[2]), int(data[3])
k = int(data[4])
moves = data[5:5 + k]
delta = {"U": (-1, 0), "D": (1, 0), "L": (0, -1), "R": (0, 1)}

for mv in moves:
    dr, dc = delta[mv]
    nr, nc = r + dr, c + dc
    if nr >= 0 and nc >= 0:
        r, c = nr, nc
print(r, c)
