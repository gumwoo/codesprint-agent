# 구간에서 나간 값의 개수가 0 이 되어도 딕셔너리에서 지우지 않아 len 으로 센 종류 수가 실제보다 커진다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, k = int(data[0]), int(data[1])
    a = [int(x) for x in data[2:2 + n]]
    cnt = {}
    for i in range(k):
        cnt[a[i]] = cnt.get(a[i], 0) + 1
    best = len(cnt)
    pos = 0
    for i in range(k, n):
        x = a[i]
        cnt[x] = cnt.get(x, 0) + 1
        y = a[i - k]
        cnt[y] = cnt[y] - 1
        if len(cnt) > best:
            best = len(cnt)
            pos = i - k + 1
    print(best, pos)


main()
