# 결과 배열을 0 으로 초기화해 끝내 처리되지 못한 작업(사이클과 그 뒤에 걸린 작업)에 -1 대신 0 을 출력한다.
import sys
from collections import deque


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    m = int(data[1])
    adj = [[] for _ in range(n + 1)]
    indeg = [0] * (n + 1)
    idx = 2
    for _ in range(m):
        a = int(data[idx])
        b = int(data[idx + 1])
        idx += 2
        adj[a].append(b)
        indeg[b] += 1
    best = [1] * (n + 1)
    res = [0] * (n + 1)
    q = deque(v for v in range(1, n + 1) if indeg[v] == 0)
    while q:
        u = q.popleft()
        res[u] = best[u]
        for w in adj[u]:
            if best[u] + 1 > best[w]:
                best[w] = best[u] + 1
            indeg[w] -= 1
            if indeg[w] == 0:
                q.append(w)
    sys.stdout.write(' '.join(map(str, res[1:])) + '\n')


main()
