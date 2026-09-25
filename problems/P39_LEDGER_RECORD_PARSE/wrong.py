# 수가 항상 한 자리라고 가정해 기록의 끝 두 글자를 부호와 수로 읽는다. 두 자리 이상의 수에서 이름과 수를 잘못 나눈다.
import sys


def main():
    s = sys.stdin.readline().strip()
    bal = {}
    for rec in s.split(','):
        rec = rec.strip()
        name = rec[:-2]
        num = int(rec[-1])
        if rec[-2] == '-':
            num = -num
        bal[name] = bal.get(name, 0) + num
    out = [f'{k} {v}' for k, v in bal.items()]
    print('\n'.join(out))


main()
