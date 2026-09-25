# Skill 대조 풀이(ADR-0033). 질의마다 수열을 앞에서부터 훑어 R 초과 원소를 만나면 멈추며 L 이상인 원소를 센다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, q = int(data[0]), int(data[1])
    a = [int(x) for x in data[2:2 + n]]
    p = 2 + n
    out = []
    for _ in range(q):
        l, r = int(data[p]), int(data[p + 1])
        p += 2
        cnt = 0
        for x in a:
            if x > r:
                break
            if x >= l:
                cnt += 1
        out.append(cnt)
    sys.stdout.write("\n".join(map(str, out)) + "\n")


main()
