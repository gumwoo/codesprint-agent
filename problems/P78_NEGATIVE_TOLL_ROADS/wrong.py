# 거리 확정 뒤 간선을 한 번만 더 검사해 그때 줄어드는 도시만 -INF 로 표시하고, 그 도시에서 이어지는 도시로 -INF 를 퍼뜨리지 않는다.
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
    neg = [False] * (n + 1)
    for u, v, w in edges:
        if dist[u] != INF and dist[u] + w < dist[v]:
            neg[v] = True
    out = []
    for v in range(1, n + 1):
        if dist[v] == INF:
            out.append("INF")
        elif neg[v]:
            out.append("-INF")
        else:
            out.append(str(dist[v]))
    sys.stdout.write("\n".join(out) + "\n")


main()
