# 합칠 때 a 의 대표가 아니라 a 자신을 b 의 대표 밑에 붙여, a 가 원래 속한 집합의 나머지 원소가 떨어져 나간다.
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
                parent[a] = rb
        else:
            out.append("YES" if ra == rb else "NO")
    sys.stdout.write("\n".join(out) + "\n")


main()
