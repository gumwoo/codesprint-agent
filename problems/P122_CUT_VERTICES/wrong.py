# DFS 를 시작한 정점도 다른 정점과 같은 조건으로 판정해서, 자식이 하나뿐인 시작 정점을 급소로 잘못 센다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, m = int(data[0]), int(data[1])
    adj = [[] for _ in range(n + 1)]
    idx = 2
    for _ in range(m):
        a = int(data[idx])
        b = int(data[idx + 1])
        idx += 2
        adj[a].append(b)
        adj[b].append(a)
    disc = [0] * (n + 1)
    low = [0] * (n + 1)
    parent = [0] * (n + 1)
    ptr = [0] * (n + 1)
    cut = [False] * (n + 1)
    timer = 0
    for s in range(1, n + 1):
        if disc[s]:
            continue
        timer += 1
        disc[s] = low[s] = timer
        stack = [s]
        while stack:
            v = stack[-1]
            if ptr[v] < len(adj[v]):
                u = adj[v][ptr[v]]
                ptr[v] += 1
                if disc[u] == 0:
                    parent[u] = v
                    timer += 1
                    disc[u] = low[u] = timer
                    stack.append(u)
                elif u != parent[v]:
                    if disc[u] < low[v]:
                        low[v] = disc[u]
            else:
                stack.pop()
                if stack:
                    p = stack[-1]
                    if low[v] < low[p]:
                        low[p] = low[v]
                    if low[v] >= disc[p]:
                        cut[p] = True
    res = [v for v in range(1, n + 1) if cut[v]]
    out = [str(len(res))]
    if res:
        out.append(' '.join(map(str, res)))
    sys.stdout.write('\n'.join(out) + '\n')


main()
