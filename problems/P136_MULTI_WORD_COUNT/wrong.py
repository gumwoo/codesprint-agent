# 본문을 훑으며 도달한 상태에만 횟수를 더하고, 실패 링크를 따라 더 짧은 접미사 상태로 횟수를 넘겨주지 않아 다른 단어의 접미사로 끝나는 등장을 놓친다.
import sys
from collections import deque


def main():
    data = sys.stdin.read().split()
    text = data[0]
    n = int(data[1])
    pats = data[2:2 + n]

    children = [{}]
    fail = [0]
    ends = []
    for p in pats:
        cur = 0
        for ch in p:
            nxt = children[cur].get(ch)
            if nxt is None:
                nxt = len(children)
                children[cur][ch] = nxt
                children.append({})
                fail.append(0)
            cur = nxt
        ends.append(cur)

    order = []
    q = deque(children[0].values())
    while q:
        u = q.popleft()
        order.append(u)
        for ch, v in children[u].items():
            f = fail[u]
            while f and ch not in children[f]:
                f = fail[f]
            fail[v] = children[f].get(ch, 0)
            q.append(v)

    hit = [0] * len(children)
    cur = 0
    for ch in text:
        while cur and ch not in children[cur]:
            cur = fail[cur]
        cur = children[cur].get(ch, 0)
        hit[cur] += 1

    sys.stdout.write('\n'.join(str(hit[e]) for e in ends) + '\n')


main()
