# 대각선 d 를 훑을 때 행 번호의 상한을 min(R-1, d) 로 자르지 않고 d 까지 올려, 격자 범위를 벗어난 행에 접근한다.
import sys


def main():
    data = sys.stdin.read().split()
    pos = 0
    r = int(data[pos]); pos += 1
    c = int(data[pos]); pos += 1
    grid = []
    for _ in range(r):
        grid.append([int(data[pos + j]) for j in range(c)])
        pos += c

    out = []
    for d in range(r + c - 1):
        lo = max(0, d - c + 1)
        hi = d
        diag = [grid[i][d - i] for i in range(lo, hi + 1)]
        if d % 2 == 0:
            diag.reverse()
        out.extend(diag)

    print(" ".join(str(v) for v in out))


main()
