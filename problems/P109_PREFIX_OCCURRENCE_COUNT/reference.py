import sys


def main():
    s = sys.stdin.readline().strip()
    n = len(s)
    pi = [0] * n
    k = 0
    for i in range(1, n):
        while k and s[i] != s[k]:
            k = pi[k - 1]
        if s[i] == s[k]:
            k += 1
        pi[i] = k
    cnt = [0] * (n + 1)
    for v in pi:
        cnt[v] += 1
    for length in range(n, 0, -1):
        cnt[pi[length - 1]] += cnt[length]
    print(" ".join(str(cnt[length] + 1) for length in range(1, n + 1)))


main()
