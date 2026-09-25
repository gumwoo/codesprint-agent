# 안쪽 반복의 시작 인덱스를 i+1, j+1 이 아니라 i, j 로 잡아 같은 상자를 두 번 이상 고르는 경우까지 센다.
import sys


def main():
    data = sys.stdin.read().split()
    n, m = int(data[0]), int(data[1])
    a = [int(x) for x in data[2:2 + n]]
    best = -1
    for i in range(n):
        for j in range(i, n):
            for k in range(j, n):
                s = a[i] + a[j] + a[k]
                if best < s <= m:
                    best = s
    print(best)


main()
