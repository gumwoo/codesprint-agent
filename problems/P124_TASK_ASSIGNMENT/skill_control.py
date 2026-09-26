# Skill 대조 풀이(ADR-0033). 각 작업자마다 아무것도 맡지 않는 경우와 비어 있는 작업 하나를 맡는 경우를 모두 재귀로 시도해 최댓값을 구한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys

sys.setrecursionlimit(10000)


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    m = int(data[1])
    adj = []
    p = 2
    for _ in range(n):
        k = int(data[p])
        p += 1
        adj.append([int(x) - 1 for x in data[p:p + k]])
        p += k
    used = [False] * m
    best = 0

    def go(i, cnt):
        nonlocal best
        if i == n:
            if cnt > best:
                best = cnt
            return
        go(i + 1, cnt)
        for t in adj[i]:
            if not used[t]:
                used[t] = True
                go(i + 1, cnt + 1)
                used[t] = False

    go(0, 0)
    print(best)


main()
