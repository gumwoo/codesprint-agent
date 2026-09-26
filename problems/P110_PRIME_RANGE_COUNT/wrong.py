# 소수 표시 배열을 (가장 큰 R)+1 이 아니라 (가장 큰 R) 크기로 만들어, R 이 최댓값인 질의에서 누적 배열을 R 번 칸으로 읽을 때 IndexError 가 난다.
import sys
from itertools import accumulate


def main():
    data = sys.stdin.buffer.read().split()
    q = int(data[0])
    qs = [(int(data[1 + 2 * k]), int(data[2 + 2 * k])) for k in range(q)]
    m = max(r for _, r in qs)
    is_p = bytearray([1]) * m
    is_p[0] = 0
    is_p[1] = 0
    i = 2
    while i * i <= m:
        if is_p[i]:
            is_p[i * i::i] = bytes(len(is_p[i * i::i]))
        i += 1
    pref = list(accumulate(is_p))
    out = [str(pref[r] - pref[l - 1]) for l, r in qs]
    sys.stdout.write('\n'.join(out) + '\n')


main()
