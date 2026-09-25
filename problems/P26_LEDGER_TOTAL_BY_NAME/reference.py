import sys


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    count = {}
    total = {}
    for i in range(n):
        name = data[1 + 2 * i]
        amount = int(data[2 + 2 * i])
        if name in count:
            count[name] += 1
            total[name] += amount
        else:
            count[name] = 1
            total[name] = amount
    out = [f"{name} {count[name]} {total[name]}" for name in count]
    sys.stdout.write("\n".join(out) + "\n")


main()
