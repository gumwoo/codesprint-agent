import sys
from collections import deque

lines = sys.stdin.read().splitlines()
m, n = map(int, lines[0].split())
q = deque(range(1, m + 1))
out = []
for i in range(1, n + 1):
    parts = lines[i].split()
    if parts[0] == "push":
        q.append(int(parts[1]))
    else:
        out.append(str(q.popleft()) if q else "-1")
print("\n".join(out))
