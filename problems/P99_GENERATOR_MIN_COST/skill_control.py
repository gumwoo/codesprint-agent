# Skill 대조 풀이(ADR-0033). 발전기마다 켜는 경우와 끄는 경우를 재귀로 모두 따라가 2^N 개 조합을 전부 확인한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys

sys.setrecursionlimit(10000)


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    k = int(data[1])
    items = [(int(data[2 + 2 * i]), int(data[3 + 2 * i])) for i in range(n)]
    best = [-1]

    def go(i, score, cost):
        if i == n:
            if score >= k and (best[0] == -1 or cost < best[0]):
                best[0] = cost
            return
        go(i + 1, score + items[i][0], cost + items[i][1])
        go(i + 1, score, cost)

    go(0, 0, 0)
    print(best[0])


main()
