import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    a = sorted(map(int, data[1:1 + n]))
    best = min(a[i] - a[i - 1] for i in range(1, n))
    if best > 0:
        count = sum(1 for i in range(1, n) if a[i] - a[i - 1] == best)
    else:
        count = 0
        run = 1
        for i in range(1, n):
            if a[i] == a[i - 1]:
                run += 1
            else:
                count += run * (run - 1) // 2
                run = 1
        count += run * (run - 1) // 2
    print(best, count)


main()
