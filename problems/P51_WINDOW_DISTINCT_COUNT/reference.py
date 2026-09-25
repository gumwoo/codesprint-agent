import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, k = int(data[0]), int(data[1])
    a = list(map(int, data[2:2 + n]))
    cnt = {}
    for x in a[:k]:
        cnt[x] = cnt.get(x, 0) + 1
    res = [len(cnt)]
    for i in range(k, n):
        x = a[i]
        cnt[x] = cnt.get(x, 0) + 1
        y = a[i - k]
        c = cnt[y] - 1
        if c:
            cnt[y] = c
        else:
            del cnt[y]
        res.append(len(cnt))
    print(' '.join(map(str, res)))


main()
