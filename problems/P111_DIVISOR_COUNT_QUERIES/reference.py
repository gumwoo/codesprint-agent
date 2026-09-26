import sys
from math import isqrt


def main():
    data = sys.stdin.buffer.read().split()
    q = int(data[0])
    xs = list(map(int, data[1:1 + q]))
    m = max(xs)
    spf = list(range(m + 1))
    composite = bytearray(m + 1)
    primes = []
    for i in range(2, isqrt(m) + 1):
        if not composite[i]:
            primes.append(i)
            composite[i * i::i] = b"\x01" * len(range(i * i, m + 1, i))
    for p in reversed(primes):
        spf[p * p::p] = [p] * len(range(p * p, m + 1, p))
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
