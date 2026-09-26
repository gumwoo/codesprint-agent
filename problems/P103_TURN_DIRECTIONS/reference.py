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
        if cr > 0:
            out.append('L')
        elif cr < 0:
            out.append('R')
        else:
            out.append('S')
    print(''.join(out))


main()
