# 탐색 시작 정점을 range(1, n) 으로 돌려 정점 N 에서 시작하는 탐색을 빠뜨린다. 정점 N 만 있는 연결 요소의 사이클을 세지 못한다.
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
    for s in range(1, n):
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
