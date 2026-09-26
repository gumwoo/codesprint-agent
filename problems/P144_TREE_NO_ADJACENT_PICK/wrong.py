# 정점을 고르지 않은 경우에 자식의 두 값 중 큰 쪽이 아니라 항상 자식을 고른 값을 더해, 연속한 두 정점을 모두 건너뛰는 선택을 놓친다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    w = [0] + [int(x) for x in data[1:1 + n]]
    adj = [[] for _ in range(n + 1)]
    idx = 1 + n
    for _ in range(n - 1):
        a = int(data[idx])
        b = int(data[idx + 1])
        idx += 2
        adj[a].append(b)
        adj[b].append(a)
    parent = [0] * (n + 1)
    parent[1] = -1
    order = []
    stack = [1]
    while stack:
        v = stack.pop()
        order.append(v)
        for u in adj[v]:
            if u != parent[v]:
                parent[u] = v
                stack.append(u)
    take = [0] * (n + 1)
    skip = [0] * (n + 1)
    for v in reversed(order):
        take[v] += w[v]
        p = parent[v]
        if p > 0:
            take[p] += skip[v]
            skip[p] += take[v]
    print(max(take[1], skip[1]))


main()
