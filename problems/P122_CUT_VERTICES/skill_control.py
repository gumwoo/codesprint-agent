# Skill 대조 풀이(ADR-0033). 정점마다 그 정점을 빼고 유니온 파인드로 연결 요소 개수를 다시 세어 원래 개수와 비교한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, m = int(data[0]), int(data[1])
    es = [(int(data[2 + 2 * i]), int(data[3 + 2 * i])) for i in range(m)]

    def comps(skip):
        par = list(range(n + 1))

        def find(x):
            while par[x] != x:
                par[x] = par[par[x]]
                x = par[x]
            return x

        c = n - (1 if skip else 0)
        for a, b in es:
            if a == skip or b == skip:
                continue
            ra = find(a)
            rb = find(b)
            if ra != rb:
                par[ra] = rb
                c -= 1
        return c

    base = comps(0)
    res = [v for v in range(1, n + 1) if comps(v) > base]
    print(len(res))
    if res:
        print(' '.join(map(str, res)))


main()
