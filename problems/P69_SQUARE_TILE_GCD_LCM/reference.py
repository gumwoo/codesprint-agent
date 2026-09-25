import sys


def gcd(a, b):
    while b:
        a, b = b, a % b
    return a


def main():
    data = sys.stdin.read().split()
    q = int(data[0])
    out = []
    for i in range(q):
        a = int(data[1 + 2 * i])
        b = int(data[2 + 2 * i])
        g = gcd(a, b)
        out.append(f"{g} {a // g * b}")
    print("\n".join(out))


main()
