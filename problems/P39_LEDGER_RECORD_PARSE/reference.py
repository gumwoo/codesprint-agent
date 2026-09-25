import sys


def main():
    s = sys.stdin.readline().strip()
    bal = {}
    for rec in s.split(','):
        rec = rec.strip()
        i = 0
        while rec[i].isalpha():
            i += 1
        name = rec[:i]
        num = int(rec[i + 1:])
        if rec[i] == '-':
            num = -num
        bal[name] = bal.get(name, 0) + num
    out = [f'{k} {v}' for k, v in bal.items()]
    print('\n'.join(out))


main()
