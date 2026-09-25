import sys


def main():
    data = sys.stdin.read().split()
    n, m = int(data[0]), int(data[1])
    INF = float('inf')
    dist = [[INF] * n for _ in range(n)]
    for i in range(n):
        dist[i][i] = 0
    pos = 2
    for _ in range(m):
        u, v, w = int(data[pos]) - 1, int(data[pos + 1]) - 1, int(data[pos + 2])
        pos += 3
        if w < dist[u][v]:
            dist[u][v] = w
            dist[v][u] = w
    for k in range(n):
        dk = dist[k]
        for i in range(n):
            di = dist[i]
            dik = di[k]
            if dik == INF:
                continue
            for j in range(n):
                cand = dik + dk[j]
                if cand < di[j]:
                    di[j] = cand
    best_room, best_ecc = 0, INF
    for i in range(n):
        ecc = max(dist[i])
        if ecc < best_ecc:
            best_room, best_ecc = i, ecc
    print(best_room + 1, best_ecc)


main()
