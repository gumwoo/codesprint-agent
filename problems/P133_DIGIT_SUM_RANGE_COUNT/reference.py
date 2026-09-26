import sys

MAXD = 19
MAXS = 171


def build():
    cnt = [[0] * (MAXS + 1) for _ in range(MAXD + 1)]
    cnt[0][0] = 1
    for d in range(1, MAXD + 1):
        prev = cnt[d - 1]
        cur = cnt[d]
        for s in range(MAXS + 1):
            if prev[s]:
                for c in range(10):
                    if s + c <= MAXS:
                        cur[s + c] += prev[s]
    return cnt


def count_upto(n, target, cnt):
    if n <= 0:
        return 0
    ds = [int(ch) for ch in str(n)]
    length = len(ds)
    prefix = 0
    res = 0
    for i, dig in enumerate(ds):
        rem = length - 1 - i
        for c in range(dig):
            t = target - prefix - c
            if 0 <= t <= MAXS:
                res += cnt[rem][t]
        prefix += dig
        if prefix > target:
            return res
    if prefix == target:
        res += 1
    return res


def main():
    data = sys.stdin.buffer.read().split()
    q = int(data[0])
    cnt = build()
    out = []
    idx = 1
    for _ in range(q):
        l = int(data[idx])
        r = int(data[idx + 1])
        s = int(data[idx + 2])
        idx += 3
        out.append(count_upto(r, s, cnt) - count_upto(l - 1, s, cnt))
    print("\n".join(map(str, out)))


main()
