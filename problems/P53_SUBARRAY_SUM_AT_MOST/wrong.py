# 왼쪽 포인터를 당기는 조건을 total > s 가 아니라 total >= s 로 써서 합이 정확히 S 인 구간을 세지 않는다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    s = int(data[1])
    a = list(map(int, data[2:2 + n]))
    left = 0
    total = 0
    count = 0
    for right in range(n):
        total += a[right]
        while total >= s:
            total -= a[left]
            left += 1
        count += right - left + 1
    print(count)


main()
