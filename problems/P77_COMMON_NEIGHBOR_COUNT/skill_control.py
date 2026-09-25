# Skill 대조 풀이(ADR-0033). 이웃 목록 없이 간선 목록만 두고, 질의마다 간선 전체를 훑어
# u 의 이웃과 v 의 이웃을 모은다. 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, m = int(data[0]), int(data[1])
    edges = []
    p = 2
    for _ in range(m):
        edges.append((int(data[p]), int(data[p + 1])))
        p += 2
    q = int(data[p])
    p += 1
    out = []
    for _ in range(q):
        u, v = int(data[p]), int(data[p + 1])
        p += 2
        nu, nv = set(), set()
        for a, b in edges:
            if a == u:
                nu.add(b)
            elif b == u:
                nu.add(a)
            if a == v:
                nv.add(b)
            elif b == v:
                nv.add(a)
        out.append(len(nu & nv))
    sys.stdout.write("\n".join(map(str, out)) + "\n")


main()
