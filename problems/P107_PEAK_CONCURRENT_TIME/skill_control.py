# Skill 대조 풀이(ADR-0033). 각 기록의 시작 시각마다 모든 기록을 다시 훑어 그 시각에 접속 중인 기록 수를 센다 (O(N^2)).
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    iv = [(int(data[1 + 2 * i]), int(data[2 + 2 * i])) for i in range(n)]
    best = -1
    at = 0
    for i in range(n):
        p = iv[i][0]
        c = 0
        for s, e in iv:
            if s <= p < e:
                c += 1
        if c > best or (c == best and p < at):
            best = c
            at = p
    print(best, at)


main()
