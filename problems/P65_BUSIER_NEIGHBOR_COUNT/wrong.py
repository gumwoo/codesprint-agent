# 1-based 정점 번호를 그대로 쓰면서 인접 리스트를 N칸으로만 만들어 정점 N에서 IndexError가 난다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, m = int(data[0]), int(data[1])
    adj = [[] for _ in range(n)]
    idx = 2
    for _ in range(m):
        a = int(data[idx])
        b = int(data[idx + 1])
        idx += 2
        adj[a].append(b)
        adj[b].append(a)
    deg = [len(nb) for nb in adj]
    out = []
    for v in range(1, n + 1):
        d = deg[v]
        c = 0
        for w in adj[v]:
            if deg[w] > d:
                c += 1
        out.append(c)
    print(' '.join(map(str, out)))


main()
