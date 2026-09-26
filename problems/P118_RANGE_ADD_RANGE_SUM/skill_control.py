# Skill 대조 풀이(ADR-0033). 리스트에서 1 번 연산마다 l..r 의 원소를 하나씩 고치고, 2 번 연산마다 l..r 를 직접 더한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.read().split()
    n, q = int(data[0]), int(data[1])
    a = [int(x) for x in data[2:2 + n]]
    out = []
    pos = 2 + n
    for _ in range(q):
        if data[pos] == "1":
            l, r, x = int(data[pos + 1]), int(data[pos + 2]), int(data[pos + 3])
            pos += 4
            for i in range(l - 1, r):
                a[i] += x
        else:
            l, r = int(data[pos + 1]), int(data[pos + 2])
            pos += 3
            out.append(sum(a[l - 1:r]))
    print("\n".join(map(str, out)))


main()
