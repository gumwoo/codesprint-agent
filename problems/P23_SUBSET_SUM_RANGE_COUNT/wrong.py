# 조합 크기를 1 부터 N-1 까지만 만들어 N 개를 모두 고르는 부분집합을 빠뜨린다.
import sys
from itertools import combinations


def main():
    data = sys.stdin.read().split()
    n, l, r = int(data[0]), int(data[1]), int(data[2])
    a = list(map(int, data[3:3 + n]))
    count = 0
    for k in range(1, n):
        for comb in combinations(a, k):
            s = sum(comb)
            if l <= s <= r:
                count += 1
    print(count)


main()
