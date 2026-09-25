# Skill 대조 풀이(ADR-0033). 각 날마다 일한다 / 쉰다 두 갈래로 나누는 재귀를 결과 저장 없이 그대로 수행한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    a = list(map(int, data[1:1 + n]))

    def best(i):
        if i >= n:
            return 0
        return max(best(i + 1), a[i] + best(i + 3))

    print(best(0))


main()
