# Skill 대조 풀이(ADR-0033). 케이블을 인접 리스트에 쌓아 두고 2 명령마다 a 에서 BFS 로 b 를 찾는다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys
from collections import deque


def main():
    data = sys.stdin.buffer.read().split()
    n, q = int(data[0]), int(data[1])
    adj = [[] for _ in range(n + 1)]
    seen = [0] * (n + 1)
    stamp = 0
    out = []
    idx = 2
    for _ in range(q):
        t = data[idx]
        a = int(data[idx + 1])
        b = int(data[idx + 2])
        idx += 3
        if t == b"1":
            adj[a].append(b)
            adj[b].append(a)
            continue
        if a == b:
            out.append("YES")
            continue
        stamp += 1
        seen[a] = stamp
        dq = deque([a])
        found = False
        while dq and not found:
            x = dq.popleft()
            for y in adj[x]:
                if seen[y] != stamp:
                    if y == b:
                        found = True
                        break
                    seen[y] = stamp
                    dq.append(y)
        out.append("YES" if found else "NO")
    sys.stdout.write("\n".join(out) + "\n")


main()
