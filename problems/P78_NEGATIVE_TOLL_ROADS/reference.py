import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, m = int(data[0]), int(data[1])
    edges = []
    for i in range(m):
        u = int(data[2 + 3 * i])
        v = int(data[3 + 3 * i])
        w = int(data[4 + 3 * i])
        edges.append((u, v, w))
    INF = float("inf")
    dist = [INF] * (n + 1)
    dist[1] = 0
    for _ in range(n - 1):
        changed = False
        for u, v, w in edges:
            if dist[u] != INF and dist[u] + w < dist[v]:
                dist[v] = dist[u] + w
                changed = True
        if not changed:
            break
    for _ in range(n):
        for u, v, w in edges:
            if dist[u] != INF and dist[u] + w < dist[v]:
                dist[v] = -INF
    out = []
    for v in range(1, n + 1):
        if dist[v] == INF:
            out.append("INF")
        elif dist[v] == -INF:
            out.append("-INF")
        else:
            out.append(str(dist[v]))
    sys.stdout.write("\n".join(out) + "\n")


main()
