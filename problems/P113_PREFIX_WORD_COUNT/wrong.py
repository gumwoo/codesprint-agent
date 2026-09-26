# 단어가 끝나는 노드에서만 개수를 올려, P 로 시작하는 단어가 아니라 P 와 정확히 같은 단어의 개수를 센다.
import sys


def main():
    data = sys.stdin.read().split()
    n, q = int(data[0]), int(data[1])
    children = [{}]
    ending = [0]
    for i in range(n):
        node = 0
        for ch in data[2 + i]:
            nxt = children[node].get(ch)
            if nxt is None:
                nxt = len(children)
                children[node][ch] = nxt
                children.append({})
                ending.append(0)
            node = nxt
        ending[node] += 1
    out = []
    for j in range(q):
        node = 0
        for ch in data[2 + n + j]:
            node = children[node].get(ch, -1)
            if node == -1:
                break
        out.append(ending[node] if node != -1 else 0)
    print("\n".join(map(str, out)))


main()
