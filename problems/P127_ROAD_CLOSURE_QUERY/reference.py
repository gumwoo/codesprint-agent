import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, m = int(data[0]), int(data[1])
    adj = [[] for _ in range(n + 1)]
    p = 2
    for i in range(m):
        u, v = int(data[p]), int(data[p + 1])
        p += 2
        adj[u].append((v, i))
        adj[v].append((u, i))
    q = int(data[p])
    p += 1
    tin = [0] * (n + 1)
    low = [0] * (n + 1)
    it = [0] * (n + 1)
    cut = [False] * m
    timer = 1
    for s in range(1, n + 1):
        if tin[s]:
            continue
        tin[s] = low[s] = timer
        timer += 1
        stack = [(s, -1)]
        while stack:
            u, pe = stack[-1]
            if it[u] < len(adj[u]):
                v, e = adj[u][it[u]]
                it[u] += 1
                if e == pe:
                    continue
                if tin[v]:
                    if tin[v] < low[u]:
                        low[u] = tin[v]
                else:
                    tin[v] = low[v] = timer
                    timer += 1
                    stack.append((v, e))
            else:
                stack.pop()
                if stack:
                    w = stack[-1][0]
                    if low[u] < low[w]:
                        low[w] = low[u]
                    if low[u] > tin[w]:
                        cut[pe] = True
    out = []
    for j in range(q):
        k = int(data[p + j])
        out.append('YES' if cut[k - 1] else 'NO')
    sys.stdout.write('\n'.join(out) + '\n')


main()
