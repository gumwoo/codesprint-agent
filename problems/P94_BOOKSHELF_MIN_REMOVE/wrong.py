# 같은 권 번호가 연달아 남는 경우를 허용하지 않고, 권 번호가 엄격하게 커지는 경우만 세어 제거 권수를 크게 낸다.
import sys
from bisect import bisect_left


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    tails = []
    for x in map(int, data[1:1 + n]):
        pos = bisect_left(tails, x)
        if pos == len(tails):
            tails.append(x)
        else:
            tails[pos] = x
    print(n - len(tails))


main()
