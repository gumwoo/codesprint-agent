# Skill 대조 풀이(ADR-0033). 저장 없이 i 번 돌까지의 방법 수를 i-1, i-2, i-3 에 대한 재귀 호출의 합으로 매번 다시 계산한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys

sys.setrecursionlimit(10000)


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    a = [int(x) for x in data[1:n + 2]]
    MOD = 1000000007

    def ways(i):
        if i < 0:
            return 0
        if a[i] == 1:
            return 0
        if i == 0:
            return 1
        return (ways(i - 1) + ways(i - 2) + ways(i - 3)) % MOD

    print(ways(n))


main()
