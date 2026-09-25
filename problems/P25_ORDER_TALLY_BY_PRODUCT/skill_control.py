# Skill 대조 풀이(ADR-0033). 등장 순서를 list 로 기록하고, 상품마다 전체 기록을 다시 훑어 횟수와 합계를 센다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    ids = [int(data[1 + 2 * i]) for i in range(n)]
    qs = [int(data[2 + 2 * i]) for i in range(n)]
    order = []
    for k in ids:
        if k not in order:
            order.append(k)
    lines = []
    for k in order:
        c = 0
        t = 0
        for j in range(n):
            if ids[j] == k:
                c += 1
                t += qs[j]
        lines.append(f"{k} {c} {t}")
    print("\n".join(lines))


main()
