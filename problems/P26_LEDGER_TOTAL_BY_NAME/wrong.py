# 집계는 맞지만 한 줄에 거래횟수와 금액합계의 순서를 바꿔 '이름 금액합계 거래횟수' 로 출력한다.
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
    out = [f"{name} {total[name]} {count[name]}" for name in count]
    sys.stdout.write("\n".join(out) + "\n")


main()
