import sys


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    s = data[1]
    t = '^#' + '#'.join(s) + '#$'
    m = len(t)
    p = [0] * m
    c = r = 0
    for i in range(1, m - 1):
        if i < r:
            p[i] = min(r - i, p[2 * c - i])
        while t[i + p[i] + 1] == t[i - p[i] - 1]:
            p[i] += 1
        if i + p[i] > r:
            c = i
            r = i + p[i]
    ans = [max(p[2 * i + 2], p[2 * i + 3]) for i in range(n)]
    sys.stdout.write(' '.join(map(str, ans)) + '\n')


main()
