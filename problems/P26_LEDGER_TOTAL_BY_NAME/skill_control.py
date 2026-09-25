# Skill 대조 풀이(ADR-0033). 서로 다른 이름을 리스트에 모은 뒤(in 으로 중복 확인), 이름마다 기록 전체를 다시 훑어 횟수와 합계를 센다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    names = data[1:1 + 2 * n:2]
    amounts = [int(x) for x in data[2:2 + 2 * n:2]]
    distinct = []
    for name in names:
        if name not in distinct:
            distinct.append(name)
    lines = []
    for name in distinct:
        c = 0
        s = 0
        for j in range(n):
            if names[j] == name:
                c += 1
                s += amounts[j]
        lines.append(f"{name} {c} {s}")
    print("\n".join(lines))


main()
