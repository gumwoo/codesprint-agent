# Skill 대조 풀이(ADR-0033). 질의마다 2부터 제곱근까지 차례로 나누어 보며 소인수분해한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.read().split()
    q = int(data[0])
    out = []
    for k in range(1, q + 1):
        x = int(data[k])
        cnt = 1
        d = 2
        while d * d <= x:
            if x % d == 0:
                e = 0
                while x % d == 0:
                    x //= d
                    e += 1
                cnt *= e + 1
            d += 1
        if x > 1:
            cnt *= 2
        out.append(cnt)
    print("\n".join(map(str, out)))


main()
