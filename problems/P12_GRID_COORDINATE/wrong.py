# 질의의 행과 열을 뒤바꿔 읽는다.
import sys

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
g = [[int(data[2 + i * m + j]) for j in range(m)] for i in range(n)]

at = 2 + n * m
q = int(data[at])
for i in range(q):
    r, c = int(data[at + 1 + i * 2]), int(data[at + 2 + i * 2])
    print(g[c][r])
