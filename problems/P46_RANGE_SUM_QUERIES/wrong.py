# 구간 합을 S[r] - S[l-1] 이 아니라 S[r] - S[l] 로 계산해 A_l 을 빠뜨린다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n, q = int(data[0]), int(data[1])
    pre = [0] * (n + 1)
    for i in range(n):
        pre[i + 1] = pre[i] + int(data[2 + i])
    out = []
    idx = 2 + n
    for _ in range(q):
        l = int(data[idx])
        r = int(data[idx + 1])
        idx += 2
        out.append(pre[r] - pre[l])
    sys.stdout.write('\n'.join(map(str, out)) + '\n')


main()
