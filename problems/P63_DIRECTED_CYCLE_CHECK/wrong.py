# 방문 중 상태와 방문 완료 상태를 구분하지 않고, 이미 방문한 정점으로 가는 간선을 만나면 모두 사이클로 판정한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    m = int(data[1])
    adj = [[] for _ in range(n + 1)]
    idx = 2
    for _ in range(m):
        u = int(data[idx])
        v = int(data[idx + 1])
        idx += 2
        adj[u].append(v)
    state = [0] * (n + 1)
    for s in range(1, n + 1):
        if state[s]:
            continue
        state[s] = 1
        stack = [(s, 0)]
        while stack:
            u, i = stack[-1]
            if i < len(adj[u]):
                stack[-1] = (u, i + 1)
                v = adj[u][i]
                if state[v] != 0:
                    print('YES')
                    return
                if state[v] == 0:
                    state[v] = 1
                    stack.append((v, 0))
            else:
                state[u] = 2
                stack.pop()
    print('NO')


main()
