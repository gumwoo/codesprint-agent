# 집계는 맞지만 첫 등장 순서가 아니라 상품 번호 오름차순으로 출력한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    cnt = {}
    tot = {}
    for i in range(n):
        k = int(data[1 + 2 * i])
        q = int(data[2 + 2 * i])
        if k in cnt:
            cnt[k] += 1
            tot[k] += q
        else:
            cnt[k] = 1
            tot[k] = q
    out = [f"{k} {cnt[k]} {tot[k]}" for k in sorted(cnt)]
    sys.stdout.write("\n".join(out) + "\n")


main()
