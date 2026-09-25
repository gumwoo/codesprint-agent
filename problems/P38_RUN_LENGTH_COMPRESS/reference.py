import sys


def main():
    s = sys.stdin.readline().strip()
    n = len(s)
    out = []
    i = 0
    while i < n:
        j = i
        while j < n and s[j] == s[i]:
            j += 1
        out.append(s[i])
        if j - i > 1:
            out.append(str(j - i))
        i = j
    print(''.join(out))


main()
