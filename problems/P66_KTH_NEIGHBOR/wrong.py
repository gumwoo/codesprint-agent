# 무방향 간선 u v 를 adj[u] 에만 기록하고 adj[v] 에는 기록하지 않아, 두 번째 끝점 쪽 정점의 이웃이 빠진다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, m = int(data[0]), int(data[1])
    idx = 2
    adj = [[] for _ in range(n + 1)]
    for _ in range(m):
        u, v = int(data[idx]), int(data[idx + 1])
        idx += 2
        adj[u].append(v)
    for lst in adj:
        lst.sort()
    q = int(data[idx])
    idx += 1
    out = []
    for _ in range(q):
        v, k = int(data[idx]), int(data[idx + 1])
        idx += 2
        out.append(adj[v][k - 1] if k <= len(adj[v]) else -1)
    sys.stdout.write('\n'.join(map(str, out)) + '\n')


main()
