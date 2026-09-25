# 이분 탐색의 상한을 M 으로 잡아, 답이 M 보다 큰 입력에서 상한 M 을 그대로 출력한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, m = int(data[0]), int(data[1])
    t = list(map(int, data[2:2 + n]))
    lo, hi = 1, m
    while lo < hi:
        mid = (lo + hi) // 2
        made = 0
        for x in t:
            made += mid // x
            if made >= m:
                break
        if made >= m:
            hi = mid
        else:
            lo = mid + 1
    print(lo)


main()
