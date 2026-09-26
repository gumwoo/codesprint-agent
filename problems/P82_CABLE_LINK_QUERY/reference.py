import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, q = int(data[0]), int(data[1])
    parent = list(range(n + 1))

    def find(x):
        root = x
        while parent[root] != root:
            root = parent[root]
        while parent[x] != root:
            nxt = parent[x]
            parent[x] = root
            x = nxt
        return root

    out = []
    idx = 2
    for _ in range(q):
        t = data[idx]
        a = int(data[idx + 1])
        b = int(data[idx + 2])
        idx += 3
        ra = find(a)
        rb = find(b)
        if t == b"1":
            if ra != rb:
                parent[ra] = rb
        else:
            out.append("YES" if ra == rb else "NO")
    sys.stdout.write("\n".join(out) + "\n")


main()
