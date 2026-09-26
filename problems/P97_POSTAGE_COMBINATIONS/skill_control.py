# Skill 대조 풀이(ADR-0033). 금액 표 없이 각 종류를 몇 장 쓸지 명시적 스택으로 모두 나열하며 하나씩 센다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys

MOD = 1_000_000_007


def main():
    data = sys.stdin.read().split()
    n, m = int(data[0]), int(data[1])
    coins = [int(x) for x in data[2:2 + n]]
    count = 0
    stack = [(0, m)]
    while stack:
        i, rem = stack.pop()
        if rem == 0:
            count += 1
            continue
        if i == n:
            continue
        stack.append((i + 1, rem))
        if rem >= coins[i]:
            stack.append((i, rem - coins[i]))
    print(count % MOD)


main()
