import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    shows = []
    for i in range(n):
        s = int(data[1 + 2 * i])
        e = int(data[2 + 2 * i])
        shows.append((e, s))
    shows.sort()
    count = 0
    last_end = 0
    for e, s in shows:
        if s > last_end:
            count += 1
            last_end = e
    print(count)


main()
