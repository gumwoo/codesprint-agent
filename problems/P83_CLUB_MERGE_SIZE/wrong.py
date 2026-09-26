# 합칠 때 두 학생의 대표가 이미 같은지 확인하지 않아, 같은 동아리를 다시 합치면 인원 수가 두 배로 불어난다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    q = int(data[1])
    parent = list(range(n + 1))
    size = [1] * (n + 1)

    def find(x):
        root = x
        while parent[root] != root:
            root = parent[root]
        while parent[x] != root:
            parent[x], x = root, parent[x]
        return root

    out = []
    idx = 2
    for _ in range(q):
        if data[idx] == b'1':
            a = int(data[idx + 1])
            b = int(data[idx + 2])
            idx += 3
            ra = find(a)
            rb = find(b)
            if size[ra] < size[rb]:
                ra, rb = rb, ra
            parent[rb] = ra
            size[ra] += size[rb]
        else:
            a = int(data[idx + 1])
            idx += 2
            out.append(str(size[find(a)]))
    sys.stdout.write('\n'.join(out) + '\n')


main()
