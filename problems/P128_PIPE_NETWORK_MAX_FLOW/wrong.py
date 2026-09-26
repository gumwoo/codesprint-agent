# 경로를 찾아 물을 보낼 때 역방향 잔여 용량을 만들지 않아, 먼저 고른 경로를 되돌리지 못하고 최댓값보다 작은 값을 낸다.
import sys


def main():
    data = sys.stdin.read().split()
    n, m = int(data[0]), int(data[1])
    cap = [[0] * (n + 1) for _ in range(n + 1)]
    order = [[] for _ in range(n + 1)]
    for i in range(m):
        u, v, c = int(data[2 + 3 * i]), int(data[3 + 3 * i]), int(data[4 + 3 * i])
        if cap[u][v] == 0:
            order[u].append(v)
        cap[u][v] += c

    def dfs(x, limit, seen):
        if x == n:
            return limit
        seen[x] = True
        for y in order[x]:
            if not seen[y] and cap[x][y] > 0:
                f = dfs(y, min(limit, cap[x][y]), seen)
                if f > 0:
                    cap[x][y] -= f
                    return f
        return 0

    total = 0
    while True:
        f = dfs(1, float('inf'), [False] * (n + 1))
        if f == 0:
            break
        total += f
    print(total)


main()
