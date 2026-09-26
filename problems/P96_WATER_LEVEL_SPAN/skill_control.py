# Skill 대조 풀이(ADR-0033). 날마다 왼쪽으로 한 칸씩 이동하며 더 큰 수위를 만날 때까지 센다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    a = list(map(int, data[1:1 + n]))
    res = [0] * n
    for i in range(n):
        x = a[i]
        j = i - 1
        while j >= 0 and a[j] <= x:
            j -= 1
        res[i] = i - j
    print(" ".join(map(str, res)))


main()
