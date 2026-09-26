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
        if length % p == 0:
            total += length // p
        else:
            total += 1
    print(total)


main()
