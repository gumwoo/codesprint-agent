import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    q = int(data[1])
    size = 1
    while size < n:
        size *= 2
    inf = 1 << 62
    tree = [inf] * (2 * size)
    for i in range(n):
        tree[size + i] = int(data[2 + i])
    for i in range(size - 1, 0, -1):
        a = tree[2 * i]
        b = tree[2 * i + 1]
        tree[i] = a if a < b else b
    pos = 2 + n
    out = []
    for _ in range(q):
        op = data[pos]
        x = int(data[pos + 1])
        y = int(data[pos + 2])
        pos += 3
        if op == b'1':
            i = size + x - 1
            tree[i] = y
            i >>= 1
            while i:
                a = tree[2 * i]
                b = tree[2 * i + 1]
                tree[i] = a if a < b else b
                i >>= 1
        else:
            lo = size + x - 1
            hi = size + y
            res = inf
            while lo < hi:
                if lo & 1:
                    if tree[lo] < res:
                        res = tree[lo]
                    lo += 1
                if hi & 1:
                    hi -= 1
                    if tree[hi] < res:
                        res = tree[hi]
                lo >>= 1
                hi >>= 1
            out.append(res)
    sys.stdout.write('\n'.join(map(str, out)) + '\n')


main()
