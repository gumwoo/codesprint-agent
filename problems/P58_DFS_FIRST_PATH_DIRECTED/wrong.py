# 탐색은 맞지만 경로를 T 에서 S 방향으로 뒤집어 출력한다.
import sys


def main():
    data = sys.stdin.read().split()
    n, m, s, t = int(data[0]), int(data[1]), int(data[2]), int(data[3])
    adj = [[] for _ in range(n + 1)]
    idx = 4
    for _ in range(m):
        u = int(data[idx])
        v = int(data[idx + 1])
        idx += 2
        adj[u].append(v)
    for lst in adj:
        lst.sort()
    visited = [False] * (n + 1)
    visited[s] = True
    ptr = [0] * (n + 1)
    stack = [s]
    while stack:
        u = stack[-1]
        if u == t:
            print(' '.join(map(str, reversed(stack))))
            return
        if ptr[u] < len(adj[u]):
            v = adj[u][ptr[u]]
            ptr[u] += 1
            if not visited[v]:
                visited[v] = True
                stack.append(v)
        else:
            stack.pop()
    print(-1)


main()
