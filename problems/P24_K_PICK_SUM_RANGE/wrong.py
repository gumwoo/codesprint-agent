# 다음 원소를 고를 때 현재 인덱스부터 다시 시작해 같은 인덱스를 여러 번 고르는 중복 조합을 센다.
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
        for i in range(start, n):
            dfs(i, picked + 1, total + a[i])

    dfs(0, 0, 0)
    print(count)


main()
