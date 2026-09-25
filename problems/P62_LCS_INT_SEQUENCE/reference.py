import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, m = int(data[0]), int(data[1])
    a = list(map(int, data[2:2 + n]))
    b = list(map(int, data[2 + n:2 + n + m]))
    prev = [0] * (m + 1)
    for x in a:
        cur = [0] * (m + 1)
        for j in range(m):
            if x == b[j]:
                cur[j + 1] = prev[j] + 1
            else:
                left = cur[j]
                up = prev[j + 1]
                cur[j + 1] = left if left > up else up
        prev = cur
    print(prev[m])


main()
