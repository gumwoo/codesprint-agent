# Skill 대조 풀이(ADR-0033). 구간마다 슬라이스를 set 으로 만들어 크기를 처음부터 다시 센다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, k = int(data[0]), int(data[1])
    a = list(map(int, data[2:2 + n]))
    res = []
    for s in range(n - k + 1):
        res.append(len(set(a[s:s + k])))
    print(' '.join(map(str, res)))


main()
