# 같은 시각에 끝나는 기록과 시작하는 기록이 있을 때 시작을 먼저 처리해, 이미 끝난 기록까지 동시 접속자로 센다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    events = []
    for i in range(n):
        s = int(data[1 + 2 * i])
        e = int(data[2 + 2 * i])
        events.append((s, 1))
        events.append((e, -1))
    events.sort(key=lambda t: (t[0], -t[1]))
    cur = 0
    best = 0
    at = 0
    for x, d in events:
        cur += d
        if cur > best:
            best = cur
            at = x
    print(best, at)


main()
