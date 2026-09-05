# 비어 있을 때 -1 을 내지 않고 그냥 건너뛴다.
import sys
from collections import deque

lines = sys.stdin.read().splitlines()
n = int(lines[0])
q = deque()
for i in range(1, n + 1):
    parts = lines[i].split()
    if parts[0] == "push":
        q.append(int(parts[1]))
    elif q:
        print(q.popleft())
