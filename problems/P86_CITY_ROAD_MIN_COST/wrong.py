# 모든 도시가 이어졌는지 확인하지 않고, 고른 도로 비용의 합을 그대로 출력한다.
import sys


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
    parent = list(range(n))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    total = 0
    used = 0
    for w, u, v in edges:
        if used == n - 1:
            break
        ru = find(u)
        rv = find(v)
        if ru != rv:
            parent[ru] = rv
            total += w
            used += 1
    print(total)


main()
