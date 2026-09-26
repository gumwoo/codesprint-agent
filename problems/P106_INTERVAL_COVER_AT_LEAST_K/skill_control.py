# Skill 대조 풀이(ADR-0033). 끝점 좌표를 모아 정렬한 뒤, 각 구간이 걸치는 모든 조각의 덮는 수를 하나씩 1 늘리고 마지막에 K 이상인 조각 길이를 더한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.read().split()
    n, k = int(data[0]), int(data[1])
    intervals = []
    for i in range(n):
        intervals.append((int(data[2 + 2 * i]), int(data[3 + 2 * i])))
    points = sorted(set(p for iv in intervals for p in iv))
    pos = {p: i for i, p in enumerate(points)}
    cover = [0] * len(points)
    for s, e in intervals:
        for j in range(pos[s], pos[e]):
            cover[j] += 1
    total = 0
    for j in range(len(points) - 1):
        if cover[j] >= k:
            total += points[j + 1] - points[j]
    print(total)


main()
