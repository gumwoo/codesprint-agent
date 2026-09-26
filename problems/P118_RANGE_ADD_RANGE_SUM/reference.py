import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, q = int(data[0]), int(data[1])
    size = 1
    h = 0
    while size < n:
        size <<= 1
        h += 1
    tree = [0] * (2 * size)
    lazy = [0] * size
    length = [1] * (2 * size)
    tree[size:size + n] = map(int, data[2:2 + n])
    for i in range(size - 1, 0, -1):
        tree[i] = tree[2 * i] + tree[2 * i + 1]
        length[i] = length[2 * i] * 2

    def push(p):
        for s in range(h, 0, -1):
            i = p >> s
            v = lazy[i]
            if v:
                c = 2 * i
                half = length[c]
                tree[c] += v * half
                tree[c + 1] += v * half
                if c < size:
                    lazy[c] += v
                    lazy[c + 1] += v
                lazy[i] = 0

    def pull(p):
        p >>= 1
        while p:
            tree[p] = tree[2 * p] + tree[2 * p + 1] + lazy[p] * length[p]
            p >>= 1

    out = []
    pos = 2 + n
    for _ in range(q):
        l = int(data[pos + 1]) - 1 + size
        r = int(data[pos + 2]) + size
        push(l)
        push(r - 1)
        a, b = l, r
        if data[pos] == b"1":
            v = int(data[pos + 3])
            pos += 4
            while a < b:
                if a & 1:
                    tree[a] += v * length[a]
                    if a < size:
                        lazy[a] += v
                    a += 1
                if b & 1:
                    b -= 1
                    tree[b] += v * length[b]
                    if b < size:
                        lazy[b] += v
                a >>= 1
                b >>= 1
            pull(l)
            pull(r - 1)
        else:
            pos += 3
            res = 0
            while a < b:
                if a & 1:
                    res += tree[a]
                    a += 1
                if b & 1:
                    b -= 1
                    res += tree[b]
                a >>= 1
                b >>= 1
            out.append(res)
    sys.stdout.write("\n".join(map(str, out)) + "\n")


main()
