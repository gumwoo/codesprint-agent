# Skill 대조 풀이(ADR-0033). 시작 위치마다 오른쪽으로 합을 누적하다가 S 를 넘으면 멈추는 이중 반복문.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    s = int(data[1])
    a = list(map(int, data[2:2 + n]))
    count = 0
    for i in range(n):
        total = 0
        for j in range(i, n):
            total += a[j]
            if total > s:
                break
            count += 1
    print(count)


main()
