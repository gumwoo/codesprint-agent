import sys

def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    a = [0] + [int(x) for x in data[1:1 + n]]
    adj = [[] for _ in range(n + 1)]
    pos = 1 + n
    for _ in range(n - 1):
        u = int(data[pos]); v = int(data[pos + 1]); w = int(data[pos + 2])
        pos += 3
        adj[u].append((v, w))
        adj[v].append((u, w))
    parent = [0] * (n + 1)
    pw = [0] * (n + 1)
    dist = [0] * (n + 1)
    order = []
    seen = [False] * (n + 1)
    seen[1] = True
    stack = [1]
    while stack:
        x = stack.pop()
        order.append(x)
        for y, w in adj[x]:
            if not seen[y]:
                seen[y] = True
                parent[y] = x
                pw[y] = w
                dist[y] = dist[x] + w
                stack.append(y)
    sub = a[:]
    for x in reversed(order):
        if x != 1:
            sub[parent[x]] += sub[x]
    total = sub[1]
    ans = [0] * (n + 1)
    ans[1] = sum(a[x] * dist[x] for x in range(1, n + 1))
    for x in order:
        if x != 1:
            ans[x] = ans[parent[x]] + pw[x] * (total - 2 * sub[x])
    sys.stdout.write('\n'.join(map(str, ans[1:])) + '\n')

main()
