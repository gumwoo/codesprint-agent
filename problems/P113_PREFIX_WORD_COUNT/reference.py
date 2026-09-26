import sys


def main():
    data = sys.stdin.read().split()
    n, q = int(data[0]), int(data[1])
    children = [{}]
    passing = [0]
    for i in range(n):
        node = 0
        for ch in data[2 + i]:
            nxt = children[node].get(ch)
            if nxt is None:
                nxt = len(children)
                children[node][ch] = nxt
                children.append({})
                passing.append(0)
            node = nxt
            passing[node] += 1
    out = []
    for j in range(q):
        node = 0
        for ch in data[2 + n + j]:
            node = children[node].get(ch, -1)
            if node == -1:
                break
        out.append(passing[node] if node != -1 else 0)
    print("\n".join(map(str, out)))


main()
