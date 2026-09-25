import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    m = int(data[1])
    p = 2
    INF = 10 ** 18
    d = [[INF] * n for _ in range(n)]
    for i in range(n):
        d[i][i] = 0
    for _ in range(m):
        u = int(data[p]) - 1
        v = int(data[p + 1]) - 1
        w = int(data[p + 2])
        p += 3
        if w < d[u][v]:
            d[u][v] = w
    for k in range(n):
        dk = d[k]
        for i in range(n):
            dik = d[i][k]
            if dik == INF:
                continue
            di = d[i]
            for j in range(n):
                nd = dik + dk[j]
                if nd < di[j]:
                    di[j] = nd
    q = int(data[p])
    p += 1
    out = []
    for _ in range(q):
        a = int(data[p]) - 1
        b = int(data[p + 1]) - 1
        p += 2
        x = d[a][b]
        out.append(str(x) if x < INF else '-1')
    sys.stdout.write('\n'.join(out) + '\n')


main()
