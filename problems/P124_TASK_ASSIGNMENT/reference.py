import sys

sys.setrecursionlimit(10000)


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    m = int(data[1])
    adj = []
    p = 2
    for _ in range(n):
        k = int(data[p])
        p += 1
        adj.append([int(x) - 1 for x in data[p:p + k]])
        p += k
    match = [-1] * m

    def try_assign(u, seen):
        for t in adj[u]:
            if not seen[t]:
                seen[t] = True
                if match[t] == -1 or try_assign(match[t], seen):
                    match[t] = u
                    return True
        return False

    res = 0
    for u in range(n):
        if try_assign(u, [False] * m):
            res += 1
    print(res)


main()
