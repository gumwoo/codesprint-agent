# 거쳐 가는 정점 k 반복을 가장 바깥이 아니라 가장 안쪽에 두어, 아직 확정되지 않은 거리로 갱신하는 바람에 일부 최단 거리를 찾지 못한다.
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
    for i in range(n):
        di = dist[i]
        for j in range(n):
            for k in range(n):
                cand = di[k] + dist[k][j]
                if cand < di[j]:
                    di[j] = cand
    best_room, best_ecc = 0, INF
    for i in range(n):
        ecc = max(dist[i])
        if ecc < best_ecc:
            best_room, best_ecc = i, ecc
    print(best_room + 1, best_ecc)


main()
