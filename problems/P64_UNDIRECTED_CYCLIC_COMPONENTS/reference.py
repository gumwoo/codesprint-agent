import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    m = int(data[1])
    adj = [[] for _ in range(n + 1)]
    for i in range(m):
        u = int(data[2 + 2 * i])
        v = int(data[3 + 2 * i])
        adj[u].append((v, i))
        adj[v].append((u, i))
    visited = [False] * (n + 1)
    count = 0
    for s in range(1, n + 1):
        if visited[s]:
            continue
        visited[s] = True
        has_cycle = False
        stack = [(s, -1)]
        while stack:
            x, parent_edge = stack.pop()
            for y, e in adj[x]:
                if e == parent_edge:
                    continue
                if visited[y]:
                    has_cycle = True
                else:
                    visited[y] = True
                    stack.append((y, e))
        if has_cycle:
            count += 1
    print(count)


main()
