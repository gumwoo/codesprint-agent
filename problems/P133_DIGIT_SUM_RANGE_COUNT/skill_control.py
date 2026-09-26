# Skill 대조 풀이(ADR-0033). L 부터 R 까지 모든 정수의 자리 숫자 합을 직접 구해 S 와 같은지 센다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.read().split()
    q = int(data[0])
    out = []
    idx = 1
    for _ in range(q):
        l = int(data[idx])
        r = int(data[idx + 1])
        s = int(data[idx + 2])
        idx += 3
        total = 0
        x = l
        while x <= r:
            y = x
            ds = 0
            while y:
                ds += y % 10
                y //= 10
            if ds == s:
                total += 1
            x += 1
        out.append(total)
    print("\n".join(map(str, out)))


main()
