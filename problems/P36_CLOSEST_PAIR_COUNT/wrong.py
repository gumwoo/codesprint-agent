# 최소 차이가 0일 때도 정렬 후 인접한 쌍만 세어, 같은 값이 3개 이상인 묶음의 모든 쌍을 세지 못한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    a = sorted(map(int, data[1:1 + n]))
    best = min(a[i] - a[i - 1] for i in range(1, n))
    count = sum(1 for i in range(1, n) if a[i] - a[i - 1] == best)
    print(best, count)


main()
