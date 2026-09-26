# 세 점이 일직선인 경우(방향 값이 0)를 S 가 아니라 L 로 판정한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    pts = [(int(data[1 + 2 * i]), int(data[2 + 2 * i])) for i in range(n)]
    out = []
    for i in range(1, n - 1):
        ax, ay = pts[i - 1]
        bx, by = pts[i]
        cx, cy = pts[i + 1]
        cr = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        if cr >= 0:
            out.append('L')
        else:
            out.append('R')
    print(''.join(out))


main()
