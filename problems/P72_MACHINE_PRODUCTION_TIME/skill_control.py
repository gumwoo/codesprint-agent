# Skill 대조 풀이(ADR-0033). 시각을 1 부터 1 씩 늘리며 그 시각까지 만든 개수의 합이 M 이상이 되는 첫 시각을 찾는다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, m = int(data[0]), int(data[1])
    t = list(map(int, data[2:2 + n]))
    now = 0
    while True:
        now += 1
        made = 0
        for x in t:
            made += now // x
        if made >= m:
            print(now)
            return


main()
