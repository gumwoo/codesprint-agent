# Skill 대조 풀이(ADR-0033). (0, 0) 에서 재귀로 오른쪽 · 아래쪽 경로를 모두 따라가며 도착할 때마다 합의 최댓값을 갱신한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys

sys.setrecursionlimit(10000)


def main():
    data = sys.stdin.read().split()
    n, m = int(data[0]), int(data[1])
    g = [[int(data[2 + i * m + j]) for j in range(m)] for i in range(n)]
    best = -1

    def go(i, j, s):
        nonlocal best
        if i >= n or j >= m or g[i][j] < 0:
            return
        s += g[i][j]
        if i == n - 1 and j == m - 1:
            if s > best:
                best = s
            return
        go(i + 1, j, s)
        go(i, j + 1, s)

    go(0, 0, 0)
    print(best)


main()
