# 기저 조건에서 칸을 칠할 때 행과 열 좌표를 뒤바꿔 전치된 패턴을 출력한다.
import sys


def draw(grid, k, r, c):
    if k == 0:
        grid[c][r] = '#'
        return
    h = 1 << (k - 1)
    draw(grid, k - 1, r, c)
    draw(grid, k - 1, r, c + h)
    draw(grid, k - 1, r + h, c + h)


def main():
    k = int(sys.stdin.readline())
    n = 1 << k
    grid = [['.'] * n for _ in range(n)]
    draw(grid, k, 0, 0)
    sys.stdout.write('\n'.join(''.join(row) for row in grid) + '\n')


main()
