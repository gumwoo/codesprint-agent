import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    meetings = []
    for i in range(n):
        s = int(data[1 + 2 * i])
        e = int(data[2 + 2 * i])
        meetings.append((e, s))
    meetings.sort()
    count = 0
    last_end = -1
    for e, s in meetings:
        if s >= last_end:
            count += 1
            last_end = e
    print(count)


main()
