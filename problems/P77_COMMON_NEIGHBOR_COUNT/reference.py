import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, m = int(data[0]), int(data[1])
    adj = [set() for _ in range(n + 1)]
    p = 2
    for _ in range(m):
        a, b = int(data[p]), int(data[p + 1])
        p += 2
        adj[a].add(b)
        adj[b].add(a)
    q = int(data[p])
    p += 1
    out = []
    for _ in range(q):
        u, v = int(data[p]), int(data[p + 1])
        p += 2
        out.append(len(adj[u] & adj[v]))
    sys.stdout.write("\n".join(map(str, out)) + "\n")


main()
