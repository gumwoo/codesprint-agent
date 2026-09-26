# 재고를 x로 덮어써야 하는데, 기존 값과의 차이 대신 x 자체를 트리에 더해 재고를 x만큼 늘린 것으로 처리한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    q = int(data[1])
    a = [0] * (n + 1)
    tree = [0] * (n + 1)
    for i in range(1, n + 1):
        v = int(data[1 + i])
        a[i] = v
        tree[i] += v
        j = i + (i & -i)
        if j <= n:
            tree[j] += tree[i]
    pos = 2 + n
    out = []
    for _ in range(q):
        t = data[pos]
        x = int(data[pos + 1])
        y = int(data[pos + 2])
        pos += 3
        if t == b"1":
            d = y
            a[x] = y
            i = x
            while i <= n:
                tree[i] += d
                i += i & -i
        else:
            s = 0
            i = y
            while i > 0:
                s += tree[i]
                i -= i & -i
            i = x - 1
            while i > 0:
                s -= tree[i]
                i -= i & -i
            out.append(s)
    sys.stdout.write("\n".join(map(str, out)) + "\n")


main()
