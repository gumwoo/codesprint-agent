# 길이별 개수를 짧은 길이 쪽으로 내려보낼 때 긴 길이부터가 아니라 짧은 길이부터 처리해, 긴 접두사의 개수가 이미 전달된 뒤 쌓인 값을 다시 전달하지 못한다.
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
    for length in range(1, n + 1):
        cnt[pi[length - 1]] += cnt[length]
    print(" ".join(str(cnt[length] + 1) for length in range(1, n + 1)))


main()
