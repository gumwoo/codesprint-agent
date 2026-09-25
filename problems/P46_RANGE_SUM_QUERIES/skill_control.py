# Skill 대조 풀이(ADR-0033). 질의마다 리스트 슬라이스 a[l-1:r] 를 만들어 sum 으로 더한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, q = int(data[0]), int(data[1])
    a = [int(x) for x in data[2:2 + n]]
    out = []
    idx = 2 + n
    for _ in range(q):
        l = int(data[idx])
        r = int(data[idx + 1])
        idx += 2
        out.append(sum(a[l - 1:r]))
    sys.stdout.write('\n'.join(map(str, out)) + '\n')


main()
