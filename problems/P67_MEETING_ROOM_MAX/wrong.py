# 끝나는 시각이 아니라 시작 시각 순으로 정렬해 앞에서부터 고른다. 일찍 시작하지만 길게 이어지는 회의가 뒤의 짧은 회의들을 막는다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    meetings = []
    for i in range(n):
        s = int(data[1 + 2 * i])
        e = int(data[2 + 2 * i])
        meetings.append((s, e))
    meetings.sort()
    count = 0
    last_end = -1
    for s, e in meetings:
        if s >= last_end:
            count += 1
            last_end = e
    print(count)


main()
