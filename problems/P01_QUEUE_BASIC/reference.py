import sys
from collections import deque

lines = sys.stdin.read().splitlines()
n = int(lines[0])
q = deque()
for i in range(1, n + 1):
    parts = lines[i].split()
    if parts[0] == "push":
        q.append(int(parts[1]))
    else:
        print(q.popleft() if q else -1)
