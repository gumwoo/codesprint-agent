import sys
from collections import deque

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
g = [[int(data[2 + i * m + j]) for j in range(m)] for i in range(n)]

# 도달 가능 여부를 따지지 않고 전체 1 의 개수를 센다.
print(sum(row.count(1) for row in g))
