# Skill 대조 풀이(ADR-0033). min(A, B) 부터 1 까지 내려가며 A 와 B 를 모두 나누는 첫 수를 최대공약수로 삼는다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.read().split()
    q = int(data[0])
    out = []
    for i in range(q):
        a = int(data[1 + 2 * i])
        b = int(data[2 + 2 * i])
        g = min(a, b)
        while a % g != 0 or b % g != 0:
            g -= 1
        out.append(f"{g} {a // g * b}")
    print("\n".join(out))


main()
