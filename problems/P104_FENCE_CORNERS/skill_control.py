# Skill 대조 풀이(ADR-0033). 가장 왼쪽 아래 점에서 시작해 매번 모든 점을 훑어 다음 모서리를 고르는 선물 포장(Jarvis march), O(N·H).
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    pts = list(set((int(data[1 + 2 * i]), int(data[2 + 2 * i])) for i in range(n)))
    if len(pts) == 1:
        print(1, 0)
        return
    start = min(pts)
    p = start
    count = 0
    area2 = 0
    while True:
        q = pts[0] if pts[0] != p else pts[1]
        for r in pts:
            if r == p:
                continue
            c = (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0])
            if c < 0:
                q = r
            elif c == 0:
                if (r[0] - p[0]) ** 2 + (r[1] - p[1]) ** 2 > (q[0] - p[0]) ** 2 + (q[1] - p[1]) ** 2:
                    q = r
        count += 1
        area2 += p[0] * q[1] - q[0] * p[1]
        p = q
        if p == start:
            break
    print(count, abs(area2))


main()
