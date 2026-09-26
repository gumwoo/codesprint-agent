# Skill 대조 풀이(ADR-0033). 합칠 때 두 학생 사이에 간선만 추가하고, 조회할 때마다 그 학생에서 BFS 로 연결된 학생 수를 센다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys
from collections import deque


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    q = int(data[1])
    adj = [[] for _ in range(n + 1)]
    out = []
    idx = 2
    for _ in range(q):
        if data[idx] == b'1':
            a = int(data[idx + 1])
            b = int(data[idx + 2])
            idx += 3
            adj[a].append(b)
            adj[b].append(a)
        else:
            a = int(data[idx + 1])
            idx += 2
            seen = bytearray(n + 1)
            seen[a] = 1
            dq = deque([a])
            cnt = 1
            while dq:
                v = dq.popleft()
                for w in adj[v]:
                    if not seen[w]:
                        seen[w] = 1
                        cnt += 1
                        dq.append(w)
            out.append(str(cnt))
    sys.stdout.write('\n'.join(out) + '\n')


main()
