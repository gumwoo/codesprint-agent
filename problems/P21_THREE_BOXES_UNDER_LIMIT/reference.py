import sys


def main():
    data = sys.stdin.read().split()
    n, m = int(data[0]), int(data[1])
    a = [int(x) for x in data[2:2 + n]]
    best = -1
    for i in range(n):
        for j in range(i + 1, n):
            for k in range(j + 1, n):
                s = a[i] + a[j] + a[k]
                if best < s <= m:
                    best = s
    print(best)


main()
