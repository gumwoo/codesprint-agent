# Skill 대조 풀이(ADR-0033). 우선순위 큐 없이, 매 단계 모든 정점을 선형으로 훑어 확정되지 않은 정점 중 비용이 가장 작은 것을 고른다 (O(N^2)).
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, m, s = int(data[0]), int(data[1]), int(data[2])
    adj = [[] for _ in range(n + 1)]
    idx = 3
    for _ in range(m):
        u = int(data[idx])
        v = int(data[idx + 1])
        w = int(data[idx + 2])
        idx += 3
        adj[u].append((v, w))
    INF = 1 << 60
    dist = [INF] * (n + 1)
    dist[s] = 0
    done = [False] * (n + 1)
    for _ in range(n):
        u = -1
        best = INF
        for v in range(1, n + 1):
            if not done[v] and dist[v] < best:
                best = dist[v]
                u = v
        if u == -1:
            break
        done[u] = True
        for v, w in adj[u]:
            if best + w < dist[v]:
                dist[v] = best + w
    out = [str(dist[i]) if dist[i] != INF else '-1' for i in range(1, n + 1)]
    sys.stdout.write('\n'.join(out) + '\n')


main()
