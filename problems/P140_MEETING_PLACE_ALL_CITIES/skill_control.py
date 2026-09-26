# Skill 대조 풀이(ADR-0033). 모든 도시를 각각 출발점으로 BFS 를 새로 돌려 거리와 인구를 곱해 더한다 (O(N^2)).
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys
from collections import deque

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
    out = []
    for s in range(1, n + 1):
        dist = [-1] * (n + 1)
        dist[s] = 0
        q = deque([s])
        cost = 0
        while q:
            x = q.popleft()
            cost += a[x] * dist[x]
            for y, w in adj[x]:
                if dist[y] < 0:
                    dist[y] = dist[x] + w
                    q.append(y)
        out.append(cost)
    sys.stdout.write('\n'.join(map(str, out)) + '\n')

main()
