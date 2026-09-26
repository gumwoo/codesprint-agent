# Skill 대조 풀이(ADR-0033). 질의마다 math.comb 로 큰 정수 이항 계수를 구한 뒤 나머지를 취한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys
import math

MOD = 1_000_000_007


def main():
    data = sys.stdin.buffer.read().split()
    q = int(data[0])
    out = []
    for j in range(q):
        n = int(data[1 + 2 * j])
        k = int(data[2 + 2 * j])
        out.append(math.comb(n, k) % MOD)
    sys.stdout.write("\n".join(map(str, out)) + "\n")


main()
