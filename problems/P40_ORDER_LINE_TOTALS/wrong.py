# 품목 이름의 앞뒤 공백을 제거하지 않아 ' pear ' 와 'pear' 를 서로 다른 이름으로 센다.
import sys


def main():
    lines = sys.stdin.read().splitlines()
    n = int(lines[0])
    totals = {}
    for line in lines[1:1 + n]:
        for item in line.split(','):
            name, rest = item.split(':')
            qty, price = rest.split('*')
            totals[name] = totals.get(name, 0) + int(qty) * int(price)
    for name, total in totals.items():
        print(name, total)


main()
