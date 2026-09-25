# Skill 대조 풀이(ADR-0033). 메모 없이 (i, j) 위치에서 두 갈래로 재귀하며 모든 경로를 따라간다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys

sys.setrecursionlimit(10000)


def main():
    data = sys.stdin.read().split()
    n, m = int(data[0]), int(data[1])
    a = list(map(int, data[2:2 + n]))
    b = list(map(int, data[2 + n:2 + n + m]))

    def solve(i, j):
        if i == n or j == m:
            return 0
        if a[i] == b[j]:
            return 1 + solve(i + 1, j + 1)
        return max(solve(i + 1, j), solve(i, j + 1))

    print(solve(0, 0))


main()
