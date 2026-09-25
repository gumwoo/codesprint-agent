# Skill 대조 풀이(ADR-0033). 인접 리스트 없이 질의마다 간선 목록 전체를 훑어 v 의 이웃을 모은 뒤 정렬한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, m = int(data[0]), int(data[1])
    idx = 2
    edges = []
    for _ in range(m):
        edges.append((int(data[idx]), int(data[idx + 1])))
        idx += 2
    q = int(data[idx])
    idx += 1
    out = []
    for _ in range(q):
        v, k = int(data[idx]), int(data[idx + 1])
        idx += 2
        nb = []
        for a, b in edges:
            if a == v:
                nb.append(b)
            elif b == v:
                nb.append(a)
        nb.sort()
        out.append(nb[k - 1] if k <= len(nb) else -1)
    sys.stdout.write('\n'.join(map(str, out)) + '\n')


main()
