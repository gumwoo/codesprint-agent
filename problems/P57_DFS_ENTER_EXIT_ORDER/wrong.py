# 방문 표시를 하지 않아 무방향 간선을 따라 두 정점 사이를 끝없이 재귀 호출하다 RecursionError 가 난다.
import sys


def main():
    data = sys.stdin.read().split()
    n, m, s = int(data[0]), int(data[1]), int(data[2])
    adj = [[] for _ in range(n + 1)]
    for k in range(m):
        u = int(data[3 + 2 * k])
        v = int(data[4 + 2 * k])
        adj[u].append(v)
        adj[v].append(u)
    for a in adj:
        a.sort()
    pre = []
    post = []

    def dfs(u):
        pre.append(u)
        for v in adj[u]:
            dfs(v)
        post.append(u)

    dfs(s)
    print(' '.join(map(str, pre)))
    print(' '.join(map(str, post)))


main()
