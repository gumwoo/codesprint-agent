# Skill 대조 풀이(ADR-0033). 0 부터 n 까지 하나씩 올라가며 두 값을 갱신한다.
# 답은 reference 와 같고, n 이 크면 시간 안에 끝나지 않아야 한다.
import sys

n, m = map(int, sys.stdin.read().split())
a, b = 0, 1 % m
for _ in range(n):
    a, b = b, (a + b) % m
print(a)
