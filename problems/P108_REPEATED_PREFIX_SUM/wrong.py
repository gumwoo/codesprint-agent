# 가장 짧은 반복 후보 길이가 접두사 길이를 나누는지 확인하지 않고 항상 몫을 더한다.
import sys


def main():
    s = sys.stdin.readline().strip()
    n = len(s)
    pi = [0] * n
    total = 1
    k = 0
    for i in range(1, n):
        c = s[i]
        while k and s[k] != c:
            k = pi[k - 1]
        if s[k] == c:
            k += 1
        pi[i] = k
        length = i + 1
        p = length - k
        total += length // p
    print(total)


main()
