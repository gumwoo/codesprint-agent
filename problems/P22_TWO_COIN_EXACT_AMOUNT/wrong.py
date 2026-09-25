# x 를 range(P) 로 열거해 A원 동전을 정확히 P개 쓰는 경우를 빠뜨린다.
import sys


def main():
    a, b, c, p, q = map(int, sys.stdin.read().split())
    count = 0
    for x in range(p):
        rest = c - a * x
        if rest < 0:
            break
        if rest % b == 0 and rest // b <= q:
            count += 1
    print(count)


main()
