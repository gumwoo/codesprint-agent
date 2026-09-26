# Skill 대조 풀이(ADR-0033). 비용 순으로 도로를 보되, 고를 때마다 지금까지 고른 도로만으로 BFS 를 새로 돌려 두 도시가 이미 이어졌는지 확인한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys
from collections import deque


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    m = int(data[1])
    edges = []
    for i in range(m):
        u = int(data[2 + 3 * i]) - 1
        v = int(data[3 + 3 * i]) - 1
        w = int(data[4 + 3 * i])
        edges.append((w, u, v))
    edges.sort()
    adj = [[] for _ in range(n)]
    total = 0
    used = 0
    for w, u, v in edges:
        if used == n - 1:
            break
        seen = [False] * n
        seen[u] = True
        queue = deque([u])
        while queue:
            x = queue.popleft()
            for y in adj[x]:
                if not seen[y]:
                    seen[y] = True
                    queue.append(y)
        if not seen[v]:
            adj[u].append(v)
            adj[v].append(u)
            total += w
            used += 1
    print(total if used == n - 1 else -1)


main()
