import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, m, s = int(data[0]), int(data[1]), int(data[2])
    adj = [[] for _ in range(n + 1)]
    idx = 3
    for _ in range(m):
        u = int(data[idx])
        v = int(data[idx + 1])
        idx += 2
        adj[u].append(v)
        adj[v].append(u)
    for a in adj:
        a.sort()
    visited = [False] * (n + 1)
    ptr = [0] * (n + 1)
    pre = [s]
    post = []
    visited[s] = True
    stack = [s]
    while stack:
        u = stack[-1]
        a = adj[u]
        i = ptr[u]
        while i < len(a) and visited[a[i]]:
            i += 1
        ptr[u] = i
        if i < len(a):
            v = a[i]
            ptr[u] = i + 1
            visited[v] = True
            pre.append(v)
            stack.append(v)
        else:
            stack.pop()
            post.append(u)
    print(' '.join(map(str, pre)))
    print(' '.join(map(str, post)))


main()
