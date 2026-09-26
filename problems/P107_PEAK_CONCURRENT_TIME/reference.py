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
    events.sort()
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
