import sys
from collections import deque


def main():
    data = sys.stdin.buffer.read().split()
    n, m = int(data[0]), int(data[1])
    head = [-1] * (n + 1)
    to = []
    cap = []
    nxt = []

    def add(u, v, c):
        to.append(v); cap.append(c); nxt.append(head[u]); head[u] = len(to) - 1
        to.append(u); cap.append(0); nxt.append(head[v]); head[v] = len(to) - 1

    p = 2
    for _ in range(m):
        u, v, c = int(data[p]), int(data[p + 1]), int(data[p + 2])
        p += 3
        add(u, v, c)

    s, t = 1, n
    total = 0
    while True:
        level = [-1] * (n + 1)
        level[s] = 0
        q = deque([s])
        while q:
            x = q.popleft()
            e = head[x]
            while e != -1:
                if cap[e] > 0 and level[to[e]] < 0:
                    level[to[e]] = level[x] + 1
                    q.append(to[e])
                e = nxt[e]
        if level[t] < 0:
            break
        it = head[:]
        while True:
            stack = [s]
            path = []
            found = False
            while stack:
                x = stack[-1]
                if x == t:
                    found = True
                    break
                e = it[x]
                while e != -1 and not (cap[e] > 0 and level[to[e]] == level[x] + 1):
                    e = nxt[e]
                it[x] = e
                if e == -1:
                    stack.pop()
                    if path:
                        pe = path.pop()
                        it[to[pe ^ 1]] = nxt[pe]
                    level[x] = -1
                    continue
                path.append(e)
                stack.append(to[e])
            if not found:
                break
            f = min(cap[e] for e in path)
            for e in path:
                cap[e] -= f
                cap[e ^ 1] += f
            total += f
    print(total)


main()
