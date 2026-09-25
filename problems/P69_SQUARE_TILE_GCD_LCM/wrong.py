# 최소공배수를 A*B 로 계산하고 최대공약수로 나누지 않았다. 최대공약수가 1 보다 큰 입력에서 L 이 틀린다.
import sys


def gcd(a, b):
    while b:
        a, b = b, a % b
    return a


def main():
    data = sys.stdin.read().split()
    q = int(data[0])
    out = []
    for i in range(q):
        a = int(data[1 + 2 * i])
        b = int(data[2 + 2 * i])
        g = gcd(a, b)
        out.append(f"{g} {a * b}")
    print("\n".join(out))


main()
