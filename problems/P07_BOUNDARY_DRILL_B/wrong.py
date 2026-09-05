# 상한만 보고 하한(음수)을 보지 않는다. Python 에서는 음수 인덱스가 유효해 보이므로
# 터지지도 않고 조용히 틀린 답을 낸다 - 이 드릴이 노리는 바로 그 실수다.
import sys

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
q = int(data[2])
for i in range(q):
    r, c = int(data[3 + i * 2]), int(data[4 + i * 2])
    count = 0
    for dr, dc in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        nr, nc = r + dr, c + dc
        if nr < n and nc < m:
            count += 1
    print(count)
