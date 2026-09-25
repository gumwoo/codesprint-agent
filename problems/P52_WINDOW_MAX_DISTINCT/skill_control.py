# Skill 대조 풀이(ADR-0033). 시작 위치마다 구간을 잘라 set 으로 종류 수를 처음부터 다시 센다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, k = int(data[0]), int(data[1])
    a = [int(x) for x in data[2:2 + n]]
    best = -1
    pos = -1
    for s in range(n - k + 1):
        d = len(set(a[s:s + k]))
        if d > best:
            best = d
            pos = s
    print(best, pos)


main()
