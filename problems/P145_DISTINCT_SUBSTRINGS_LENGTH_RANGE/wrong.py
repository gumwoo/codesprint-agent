# 사전순으로 이웃한 접미사와 공통 접두사 길이가 h 일 때 새로 생기는 부분 문자열의 최소 길이를 h+1 이 아니라 h 로 잡아, 이미 센 길이 h 짜리 접두사를 한 번 더 센다.
import sys


def build_suffix_array(s):
    n = len(s)
    rank = [ord(c) for c in s]
    sa = sorted(range(n), key=rank.__getitem__)
    k = 1
    while True:
        shifted = rank[k:] + [-1] * min(k, n)
        key = [rank[i] * (n + 2) + shifted[i] + 1 for i in range(n)]
        sa.sort(key=key.__getitem__)
        new_rank = [0] * n
        r = 0
        prev = key[sa[0]]
        for i in sa:
            if key[i] != prev:
                r += 1
                prev = key[i]
            new_rank[i] = r
        rank = new_rank
        if r == n - 1 or k >= n:
            break
        k *= 2
    return sa, rank


def build_lcp(s, sa, rank):
    n = len(s)
    lcp = [0] * n
    h = 0
    for i in range(n):
        if rank[i] == 0:
            h = 0
            continue
        j = sa[rank[i] - 1]
        while i + h < n and j + h < n and s[i + h] == s[j + h]:
            h += 1
        lcp[rank[i]] = h
        if h:
            h -= 1
    return lcp


def main():
    data = sys.stdin.read().split()
    s = data[0]
    lo_len = int(data[1])
    hi_len = int(data[2])
    n = len(s)
    sa, rank = build_suffix_array(s)
    lcp = build_lcp(s, sa, rank)
    total = 0
    for idx in range(n):
        length = n - sa[idx]
        lo = max(lcp[idx], lo_len)
        hi = min(length, hi_len)
        if hi >= lo:
            total += hi - lo + 1
    print(total)


main()
