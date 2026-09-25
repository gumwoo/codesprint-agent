# 구간을 한 칸 옮길 때 빠져나가는 원소를 A[i-K] 가 아니라 A[i-K+1] 로 잡아, 아직 구간 안에 있는 원소를 뺀다.
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
        y = a[i - k + 1]
        c = cnt[y] - 1
        if c:
            cnt[y] = c
        else:
            del cnt[y]
        res.append(len(cnt))
    print(' '.join(map(str, res)))


main()
