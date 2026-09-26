# Skill 대조 풀이(ADR-0033). 길이 L 마다 모든 시작 위치에서 접두사와 같은지 직접 비교해 센다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    s = sys.stdin.readline().strip()
    n = len(s)
    result = []
    for length in range(1, n + 1):
        prefix = s[:length]
        count = 0
        for i in range(n - length + 1):
            if s.startswith(prefix, i):
                count += 1
        result.append(count)
    print(" ".join(map(str, result)))


main()
