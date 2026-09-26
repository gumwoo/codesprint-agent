# Skill 대조 풀이(ADR-0033). 값 배열만 두고 질의마다 l..r 구간을 슬라이스해 sum 으로 직접 더한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    q = int(data[1])
    a = [int(v) for v in data[2:2 + n]]
    pos = 2 + n
    out = []
    for _ in range(q):
        t = data[pos]
        x = int(data[pos + 1])
        y = int(data[pos + 2])
        pos += 3
        if t == b"1":
            a[x - 1] = y
        else:
            out.append(sum(a[x - 1:y]))
    sys.stdout.write("\n".join(map(str, out)) + "\n")


main()
