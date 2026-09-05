import sys

data = sys.stdin.read().split()
n, m = int(data[0]), int(data[1])
q = int(data[2])
for i in range(q):
    r, c = int(data[3 + i * 2]), int(data[4 + i * 2])
    count = 0
    for dr, dc in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        nr, nc = r + dr, c + dc
        if 0 <= nr < n and 0 <= nc < m:
            count += 1
    print(count)
