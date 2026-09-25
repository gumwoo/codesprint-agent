# Skill 대조 풀이(ADR-0033). 열의 모든 순열(N!개)을 먼저 만든 뒤 막힌 칸과 대각선 충돌을 검사해 거른다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys
from itertools import permutations


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    grid = data[1:1 + n]
    count = 0
    for perm in permutations(range(n)):
        if (all(grid[r][perm[r]] == '.' for r in range(n))
                and len({r + perm[r] for r in range(n)}) == n
                and len({r - perm[r] for r in range(n)}) == n):
            count += 1
    print(count)


main()
