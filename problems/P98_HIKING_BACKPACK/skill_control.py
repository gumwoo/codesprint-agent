# Skill 대조 풀이(ADR-0033). 물건마다 넣는 경우와 넣지 않는 경우를 재귀로 모두 시도하고, 무게 한도를 넘는 가지만 잘라낸다(메모이제이션 없음).
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    sys.setrecursionlimit(10000)
    data = sys.stdin.read().split()
    n = int(data[0])
    cap = int(data[1])
    ws = [int(data[2 + 2 * i]) for i in range(n)]
    vs = [int(data[3 + 2 * i]) for i in range(n)]

    def go(i, left):
        if i == n:
            return 0
        result = go(i + 1, left)
        if ws[i] <= left:
            with_item = vs[i] + go(i + 1, left - ws[i])
            if with_item > result:
                result = with_item
        return result

    print(go(0, cap))


main()
