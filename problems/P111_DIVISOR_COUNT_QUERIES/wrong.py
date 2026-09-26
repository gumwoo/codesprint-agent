# 가장 작은 소인수 표를 최댓값 M 까지가 아니라 M-1 까지만 만들어, 최댓값 자신을 조회할 때 IndexError 가 난다.
import sys
from math import isqrt


def main():
    data = sys.stdin.buffer.read().split()
    q = int(data[0])
    xs = list(map(int, data[1:1 + q]))
    m = max(xs)
    spf = list(range(m))
    composite = bytearray(m)
    primes = []
    for i in range(2, isqrt(m) + 1):
        if not composite[i]:
            primes.append(i)
            composite[i * i::i] = b"\x01" * len(range(i * i, m, i))
    for p in reversed(primes):
        spf[p * p::p] = [p] * len(range(p * p, m, p))
    out = []
    for x in xs:
        cnt = 1
        while x > 1:
            p = spf[x]
            e = 0
            while x % p == 0:
                x //= p
                e += 1
            cnt *= e + 1
        out.append(cnt)
    sys.stdout.write("\n".join(map(str, out)) + "\n")


main()
