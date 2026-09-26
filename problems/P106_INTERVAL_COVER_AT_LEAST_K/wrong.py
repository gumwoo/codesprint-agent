# 사건 좌표에서 덮는 개수를 먼저 갱신한 뒤 직전 좌표부터의 길이를 판단해, 각 조각을 그 조각의 개수가 아니라 다음 조각의 개수로 판정한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, k = int(data[0]), int(data[1])
    events = []
    for i in range(n):
        s = int(data[2 + 2 * i])
        e = int(data[3 + 2 * i])
        events.append((s, 1))
        events.append((e, -1))
    events.sort()
    cur = 0
    prev = 0
    total = 0
    for x, d in events:
        cur += d
        if cur >= k:
            total += x - prev
        prev = x
    print(total)


main()
