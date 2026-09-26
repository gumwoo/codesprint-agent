# Skill 대조 풀이(ADR-0033). 우선순위 큐 없이 매 단계 모든 교차로를 훑어 아직 확정되지 않은 것 중 거리가 가장 작은 교차로를 고르는 O(N^2) 방식.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, m, k = int(data[0]), int(data[1]), int(data[2])
    pos = 3
    shelters = [int(x) - 1 for x in data[pos:pos + k]]
    pos += k
    adj = [[] for _ in range(n)]
    for _ in range(m):
        u = int(data[pos]) - 1
        v = int(data[pos + 1]) - 1
        w = int(data[pos + 2])
        pos += 3
        adj[u].append((v, w))
        adj[v].append((u, w))
    INF = 10 ** 18
    dist = [INF] * n
    for s in shelters:
        dist[s] = 0
    done = [False] * n
    for _ in range(n):
        best = -1
        bd = INF
        for i in range(n):
            if not done[i] and dist[i] < bd:
                bd = dist[i]
                best = i
        if best == -1:
            break
        done[best] = True
        for v, w in adj[best]:
            if bd + w < dist[v]:
                dist[v] = bd + w
    print(' '.join(str(x) if x < INF else '-1' for x in dist))


main()
