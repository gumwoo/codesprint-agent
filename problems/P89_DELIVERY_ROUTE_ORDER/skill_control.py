# Skill 대조 풀이(ADR-0033). 0 번에서 시작해 방문하지 않은 장소를 하나씩 고르는 DFS 로 모든 방문 순서를 끝까지 시도한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    t = [[int(data[1 + i * n + j]) for j in range(n)] for i in range(n)]
    used = [False] * n
    used[0] = True
    best = [float('inf')]

    def dfs(cur, count, cost):
        if count == n:
            if cost < best[0]:
                best[0] = cost
            return
        for nxt in range(n):
            if not used[nxt]:
                used[nxt] = True
                dfs(nxt, count + 1, cost + t[cur][nxt])
                used[nxt] = False

    dfs(0, 1, 0)
    print(best[0])


main()
