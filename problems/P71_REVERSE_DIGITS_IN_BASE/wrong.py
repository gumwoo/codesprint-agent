# 자릿수를 올바르게 뒤집었지만 결과를 10 진법 값이 아니라 B 진법 문자열 그대로 출력한다.
import sys

D = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"


def main():
    n, b = map(int, sys.stdin.read().split())
    if n == 0:
        print(0)
        return
    s = []
    while n > 0:
        s.append(D[n % b])
        n //= b
    print("".join(s))


main()
