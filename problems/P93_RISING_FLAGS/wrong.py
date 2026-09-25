# 같은 높이를 이어 고를 수 없다는 조건을 놓쳐, 같은 값이 들어올 때 자리를 찾는 기준을 오른쪽 경계로 잡아 같은 높이가 이어지는 수열의 길이를 센다.
import sys
from bisect import bisect_right


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    a = list(map(int, data[1:1 + n]))
    tails = []
    for x in a:
        i = bisect_right(tails, x)
        if i == len(tails):
            tails.append(x)
        else:
            tails[i] = x
    print(len(tails))


main()
