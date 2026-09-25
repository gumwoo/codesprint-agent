# Skill 대조 풀이(ADR-0033). a^0 부터 a^(n-1) 까지 항을 하나씩 곱해 가며 n 번 더한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    a, n, m = map(int, sys.stdin.read().split())
    total = 0
    value = 1 % m
    for _ in range(n):
        total = (total + value) % m
        value = value * a % m
    print(total % m)


main()
