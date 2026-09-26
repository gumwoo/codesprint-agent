# Skill 대조 풀이(ADR-0033). 정점마다 그 정점을 루트로 트리 전체를 다시 DFS 해서 아래쪽 DP만 계산한다 (O(N^2)).
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys

def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    MOD = 10**9 + 7
    adj = [[] for _ in range(n + 1)]
    for i in range(n - 1):
        u = int(data[1 + 2 * i]); v = int(data[2 + 2 * i])
        adj[u].append(v); adj[v].append(u)
    res = []
    for r in range(1, n + 1):
        parent = [0] * (n + 1)
        parent[r] = -1
        order = []
        stack = [r]
        while stack:
            v = stack.pop()
            order.append(v)
            for w in adj[v]:
                if w != parent[v]:
                    parent[w] = v
                    stack.append(w)
        down = [1] * (n + 1)
        for v in reversed(order):
            p = parent[v]
            if p > 0:
                down[p] = down[p] * (down[v] + 1) % MOD
        res.append(str(down[r]))
    sys.stdout.write('\n'.join(res) + '\n')

main()
