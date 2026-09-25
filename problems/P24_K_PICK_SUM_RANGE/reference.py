import sys


def main():
    data = sys.stdin.read().split()
    n, k, lo, hi = map(int, data[:4])
    a = list(map(int, data[4:4 + n]))
    count = 0

    def dfs(start, picked, total):
        nonlocal count
        if picked == k:
            if lo <= total <= hi:
                count += 1
            return
        if n - start < k - picked:
            return
        for i in range(start, n):
            dfs(i + 1, picked + 1, total + a[i])

    dfs(0, 0, 0)
    print(count)


main()
