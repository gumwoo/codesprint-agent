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
        if cur >= k:
            total += x - prev
        cur += d
        prev = x
    print(total)


main()
