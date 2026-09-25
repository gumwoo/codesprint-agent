# 오른쪽 절반으로 내려갈 때 가운데 글자 한 칸을 빼지 않아 인덱스가 1 밀린다.
import sys


def solve(n, k):
    if n == 1:
        return 'a'
    half = (1 << (n - 1)) - 1
    if k < half:
        return solve(n - 1, k)
    if k == half:
        return chr(ord('a') + n - 1)
    return solve(n - 1, k - half)


n, k = map(int, sys.stdin.read().split())
print(solve(n, k))
