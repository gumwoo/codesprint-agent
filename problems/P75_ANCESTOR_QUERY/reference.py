import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    r = int(data[1])
    idx = 2
    adj = [[] for _ in range(n + 1)]
    for _ in range(n - 1):
        a = int(data[idx])
        b = int(data[idx + 1])
        idx += 2
        adj[a].append(b)
        adj[b].append(a)
    tin = [0] * (n + 1)
    tout = [0] * (n + 1)
    visited = [False] * (n + 1)
    it = [0] * (n + 1)
    timer = 1
    tin[r] = timer
    visited[r] = True
    stack = [r]
    while stack:
        v = stack[-1]
        if it[v] < len(adj[v]):
            w = adj[v][it[v]]
            it[v] += 1
            if not visited[w]:
                visited[w] = True
                timer += 1
                tin[w] = timer
                stack.append(w)
        else:
            tout[v] = timer
            stack.pop()
    q = int(data[idx])
    idx += 1
    out = []
    for _ in range(q):
        u = int(data[idx])
        v = int(data[idx + 1])
        idx += 2
        out.append('YES' if tin[u] <= tin[v] <= tout[u] else 'NO')
    sys.stdout.write('\n'.join(out) + '\n')


main()
