# Skill 대조 풀이(ADR-0033). 행 우선 순서로 첫 빈 칸을 골라 오른쪽 타일 · 아래쪽 타일 · 비워 두기 세 경우를 모두 되추적으로 시도한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.read().split()
    n, m = int(data[0]), int(data[1])
    g = [list(row) for row in data[2:2 + n]]
    cells = n * m
    best = 0
    sys.setrecursionlimit(10000)

    def go(pos, cnt):
        nonlocal best
        while pos < cells and g[pos // m][pos % m] != '.':
            pos += 1
        if pos == cells:
            if cnt > best:
                best = cnt
            return
        r, c = divmod(pos, m)
        g[r][c] = 'x'
        if c + 1 < m and g[r][c + 1] == '.':
            g[r][c + 1] = 'x'
            go(pos + 1, cnt + 1)
            g[r][c + 1] = '.'
        if r + 1 < n and g[r + 1][c] == '.':
            g[r + 1][c] = 'x'
            go(pos + 1, cnt + 1)
            g[r + 1][c] = '.'
        go(pos + 1, cnt)
        g[r][c] = '.'

    go(0, 0)
    print(best)


main()
