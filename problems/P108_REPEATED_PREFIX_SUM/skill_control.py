# Skill 대조 풀이(ADR-0033). 접두사마다 길이의 약수를 작은 것부터 반복 단위로 두고 이어 붙인 문자열을 접두사와 직접 비교한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys

s = sys.stdin.readline().strip()
n = len(s)
total = 0
for length in range(1, n + 1):
    best = 1
    prefix = s[:length]
    for d in range(1, length):
        if length % d == 0 and s[:d] * (length // d) == prefix:
            best = length // d
            break
    total += best
print(total)
