# Skill 대조 풀이(ADR-0033). 아직 묶이지 않은 정점마다 정방향 BFS 와 역방향 BFS 를 따로 돌려 두 도달 집합의 교집합을 한 묶음으로 삼는다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys
from collections import deque


def bfs(s, g, n):
    seen = [False] * n
    seen[s] = True
    q = deque([s])
    while q:
        v = q.popleft()
        for w in g[v]:
            if not seen[w]:
                seen[w] = True
                q.append(w)
    return seen


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    m = int(data[1])
    adj = [[] for _ in range(n)]
    radj = [[] for _ in range(n)]
    for i in range(m):
        u = int(data[2 + 2 * i]) - 1
        v = int(data[3 + 2 * i]) - 1
        adj[u].append(v)
        radj[v].append(u)
    comp = [-1] * n
    c = 0
    sinks = 0
    for s in range(n):
        if comp[s] != -1:
            continue
        fw = bfs(s, adj, n)
        bw = bfs(s, radj, n)
        size = 0
        for v in range(n):
            if fw[v] and bw[v]:
                comp[v] = c
                size += 1
        if sum(fw) == size:
            sinks += 1
        c += 1
    print(c)
    print(sinks)


main()
