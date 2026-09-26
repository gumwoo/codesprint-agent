# Skill 대조 풀이(ADR-0033). 질의마다 L 부터 R 까지 모든 수를 2 부터 제곱근까지 나누어 보며 소수인지 하나씩 판정한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def is_prime(n):
    if n < 2:
        return False
    if n % 2 == 0:
        return n == 2
    d = 3
    while d * d <= n:
        if n % d == 0:
            return False
        d += 2
    return True


def main():
    data = sys.stdin.buffer.read().split()
    q = int(data[0])
    out = []
    for k in range(q):
        l = int(data[1 + 2 * k])
        r = int(data[2 + 2 * k])
        cnt = 0
        for x in range(l, r + 1):
            if is_prime(x):
                cnt += 1
        out.append(str(cnt))
    sys.stdout.write('\n'.join(out) + '\n')


main()
