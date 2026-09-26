import sys


def main():
    data = sys.stdin.read().split()
    a, b = data[0], data[1]
    n, m = len(a), len(b)
    prev = list(range(m + 1))
    for i in range(1, n + 1):
        cur = [i] + [0] * m
        ca = a[i - 1]
        for j in range(1, m + 1):
            if ca == b[j - 1]:
                cur[j] = prev[j - 1]
            else:
                cur[j] = 1 + min(prev[j - 1], prev[j], cur[j - 1])
        prev = cur
    print(prev[m])


main()
