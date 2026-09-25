import sys
from bisect import bisect_right


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    tails = []
    for x in map(int, data[1:1 + n]):
        pos = bisect_right(tails, x)
        if pos == len(tails):
            tails.append(x)
        else:
            tails[pos] = x
    print(n - len(tails))


main()
