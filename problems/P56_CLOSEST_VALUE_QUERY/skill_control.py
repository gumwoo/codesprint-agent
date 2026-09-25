# Skill 대조 풀이(ADR-0033). 질의마다 수열을 앞에서부터 훑어 x 이상인 첫 원소를 찾고 그 앞뒤를 비교한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, q = int(data[0]), int(data[1])
    a = list(map(int, data[2:2 + n]))
    xs = data[2 + n:2 + n + q]
    out = []
    for t in xs:
        x = int(t) ^ (out[-1] if out else 0)  # 직전 답과 xor
        i = 0
        while i < n and a[i] < x:
            i += 1
        if i == n:
            out.append(a[n - 1])
        elif i == 0:
            out.append(a[0])
        else:
            lo = a[i - 1]
            hi = a[i]
            out.append(lo if x - lo <= hi - x else hi)
    sys.stdout.write("\n".join(map(str, out)) + "\n")


main()
