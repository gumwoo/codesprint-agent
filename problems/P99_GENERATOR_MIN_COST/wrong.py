# 출력 합 배열을 작은 값에서 큰 값 방향으로 갱신해 같은 발전기를 여러 번 켠 것으로 계산한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    k = int(data[1])
    INF = float("inf")
    best = [INF] * (k + 1)
    best[0] = 0
    idx = 2
    for _ in range(n):
        s = int(data[idx])
        c = int(data[idx + 1])
        idx += 2
        for j in range(1, k + 1):
            p = j - s
            if p < 0:
                p = 0
            v = best[p] + c
            if v < best[j]:
                best[j] = v
    print(best[k] if best[k] != INF else -1)


main()
