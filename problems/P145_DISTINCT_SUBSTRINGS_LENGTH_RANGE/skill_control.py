# Skill 대조 풀이(ADR-0033). 길이 L 부터 R 까지 각 길이마다 그 길이의 부분 문자열을 모두 잘라 집합에 넣어 개수를 더한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys

data = sys.stdin.read().split()
s = data[0]
lo_len = int(data[1])
hi_len = int(data[2])
n = len(s)
total = 0
for length in range(lo_len, hi_len + 1):
    total += len({s[i:i + length] for i in range(n - length + 1)})
print(total)
