# Skill 대조 풀이(ADR-0033). 단어마다 str.find 로 본문을 따로 훑으며 겹치는 등장을 하나씩 센다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.read().split()
    text = data[0]
    n = int(data[1])
    pats = data[2:2 + n]
    out = []
    for p in pats:
        count = 0
        i = text.find(p)
        while i != -1:
            count += 1
            i = text.find(p, i + 1)
        out.append(str(count))
    print('\n'.join(out))


main()
