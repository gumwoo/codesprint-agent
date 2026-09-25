# Skill 대조 풀이(ADR-0033). 갱신마다 l 부터 r 까지 모든 칸에 v 를 직접 더한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, m = int(data[0]), int(data[1])
    a = [0] * (n + 1)
    idx = 2
    for _ in range(m):
        l = int(data[idx])
        r = int(data[idx + 1])
        v = int(data[idx + 2])
        idx += 3
        for i in range(l, r + 1):
            a[i] += v
    print(' '.join(map(str, a[1:])))


main()
