# 용량 배열을 작은 무게부터 큰 무게 방향으로 갱신해, 이번 물건으로 이미 갱신된 값을 다시 사용하므로 같은 물건을 여러 번 넣은 답이 나온다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    cap = int(data[1])
    best = [0] * (cap + 1)
    pos = 2
    for _ in range(n):
        w = int(data[pos])
        v = int(data[pos + 1])
        pos += 2
        for j in range(w, cap + 1):
            if best[j - w] + v > best[j]:
                best[j] = best[j - w] + v
    print(best[cap])


main()
