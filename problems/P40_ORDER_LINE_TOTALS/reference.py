import sys


def main():
    lines = sys.stdin.read().splitlines()
    n = int(lines[0])
    totals = {}
    for line in lines[1:1 + n]:
        for item in line.split(','):
            name, rest = item.split(':')
            qty, price = rest.split('*')
            name = name.strip()
            totals[name] = totals.get(name, 0) + int(qty) * int(price)
    for name, total in totals.items():
        print(name, total)


main()
