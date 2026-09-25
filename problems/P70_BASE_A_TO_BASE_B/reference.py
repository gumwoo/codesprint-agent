import sys

DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"


def main():
    data = sys.stdin.read().split()
    a, b, s = int(data[0]), int(data[1]), data[2]
    value = 0
    for ch in s:
        value = value * a + DIGITS.index(ch)
    if value == 0:
        print("0")
        return
    out = []
    while value > 0:
        value, r = divmod(value, b)
        out.append(DIGITS[r])
    print("".join(reversed(out)))


main()
