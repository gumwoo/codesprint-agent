# 공연을 끝나는 날이 아니라 시작하는 날 순으로 정렬한 뒤 같은 탐욕 선택을 한다. 일찍 시작하지만 오래 가는 공연을 먼저 골라 답이 작아진다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    shows = []
    for i in range(n):
        s = int(data[1 + 2 * i])
        e = int(data[2 + 2 * i])
        shows.append((s, e))
    shows.sort()
    count = 0
    last_end = 0
    for s, e in shows:
        if s > last_end:
            count += 1
            last_end = e
    print(count)


main()
