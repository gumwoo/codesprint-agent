# 스택에서 점을 뺄 때 세 점이 한 직선 위인 경우(방향 값 0)를 빼지 않아, 곧은 변 위의 말뚝까지 모서리로 센다.
import sys


def cross(o, a, b):
    return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    pts = sorted(set((int(data[1 + 2 * i]), int(data[2 + 2 * i])) for i in range(n)))
    if len(pts) == 1:
        print(1, 0)
        return
    lower = []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) < 0:
            lower.pop()
        lower.append(p)
    upper = []
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) < 0:
            upper.pop()
        upper.append(p)
    hull = lower[:-1] + upper[:-1]
    area2 = 0
    for i in range(len(hull)):
        x1, y1 = hull[i]
        x2, y2 = hull[(i + 1) % len(hull)]
        area2 += x1 * y2 - x2 * y1
    print(len(hull), abs(area2))


main()
