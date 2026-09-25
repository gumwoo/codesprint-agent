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
        while total > s:
            total -= a[left]
            left += 1
        count += right - left + 1
    print(count)


main()
