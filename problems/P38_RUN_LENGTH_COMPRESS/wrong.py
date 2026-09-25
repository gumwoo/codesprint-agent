# 반복문이 끝난 뒤 마지막 구간을 결과에 추가하지 않는다.
import sys


def main():
    s = sys.stdin.readline().strip()
    out = []
    prev = s[0]
    cnt = 1
    for c in s[1:]:
        if c == prev:
            cnt += 1
        else:
            out.append(prev + (str(cnt) if cnt > 1 else ''))
            prev = c
            cnt = 1
    print(''.join(out))


main()
