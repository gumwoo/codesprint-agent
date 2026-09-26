import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    w = [0] + [int(x) for x in data[1:1 + n]]
    adj = [[] for _ in range(n + 1)]
    idx = 1 + n
    for _ in range(n - 1):
        a = int(data[idx])
        b = int(data[idx + 1])
        idx += 2
        adj[a].append(b)
        adj[b].append(a)
    parent = [0] * (n + 1)
    parent[1] = -1
    order = []
    stack = [1]
    while stack:
        v = stack.pop()
        order.append(v)
        for u in adj[v]:
            if u != parent[v]:
                parent[u] = v
                stack.append(u)
    take = [0] * (n + 1)
    skip = [0] * (n + 1)
    for v in reversed(order):
        take[v] += w[v]
        p = parent[v]
        if p > 0:
            take[p] += skip[v]
            skip[p] += max(take[v], skip[v])
    print(max(take[1], skip[1]))


main()
