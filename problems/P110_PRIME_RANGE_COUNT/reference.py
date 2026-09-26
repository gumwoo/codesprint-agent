import sys
from itertools import accumulate


def main():
    data = sys.stdin.buffer.read().split()
    q = int(data[0])
    qs = [(int(data[1 + 2 * k]), int(data[2 + 2 * k])) for k in range(q)]
    m = max(r for _, r in qs)
    is_p = bytearray([1]) * (m + 1)
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
