# Skill 대조 풀이(ADR-0033). 계획마다 해당 도로만 빼고 한쪽 끝에서 BFS 를 돌려 다른 끝에 닿는지 확인한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys
from collections import deque


def main():
    data = sys.stdin.buffer.read().split()
    n, m = int(data[0]), int(data[1])
    adj = [[] for _ in range(n + 1)]
    ends = []
    p = 2
    for i in range(m):
        u, v = int(data[p]), int(data[p + 1])
        p += 2
        ends.append((u, v))
        adj[u].append((v, i))
        adj[v].append((u, i))
    q = int(data[p])
    p += 1
    out = []
    for j in range(q):
        k = int(data[p + j]) - 1
        s, t = ends[k]
        seen = [False] * (n + 1)
        seen[s] = True
        dq = deque([s])
        found = False
        while dq and not found:
            x = dq.popleft()
            for y, e in adj[x]:
                if e == k or seen[y]:
                    continue
                if y == t:
                    found = True
                    break
                seen[y] = True
                dq.append(y)
        out.append('NO' if found else 'YES')
    print('\n'.join(out))


main()
