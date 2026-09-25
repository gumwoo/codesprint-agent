import sys


def solve(n, k):
    if n == 1:
        return 'a'
    half = (1 << (n - 1)) - 1
    if k < half:
        return solve(n - 1, k)
    if k == half:
        return chr(ord('a') + n - 1)
    return solve(n - 1, k - half - 1)


n, k = map(int, sys.stdin.read().split())
print(solve(n, k))
