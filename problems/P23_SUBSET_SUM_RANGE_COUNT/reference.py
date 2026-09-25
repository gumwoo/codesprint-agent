import sys
from itertools import combinations


def main():
    data = sys.stdin.read().split()
    n, l, r = int(data[0]), int(data[1]), int(data[2])
    a = list(map(int, data[3:3 + n]))
    count = 0
    for k in range(1, n + 1):
        for comb in combinations(a, k):
            s = sum(comb)
            if l <= s <= r:
                count += 1
    print(count)


main()
