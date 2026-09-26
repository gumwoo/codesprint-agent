import sys


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    words = data[1:1 + n]
    children = [{}]
    cnt = [0]
    for w in words:
        node = 0
        for ch in w:
            nxt = children[node].get(ch)
            if nxt is None:
                nxt = len(children)
                children[node][ch] = nxt
                children.append({})
                cnt.append(0)
            node = nxt
            cnt[node] += 1
    out = []
    for w in words:
        node = 0
        ans = -1
        for i, ch in enumerate(w):
            node = children[node][ch]
            if cnt[node] == 1:
                ans = i + 1
                break
        out.append(str(ans))
    sys.stdout.write('\n'.join(out) + '\n')


main()
