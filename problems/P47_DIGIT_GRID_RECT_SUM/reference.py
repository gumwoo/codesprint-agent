import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    m = int(data[1])
    pre = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        row = data[1 + i]
        above = pre[i - 1]
        cur = pre[i]
        run = 0
        for j in range(1, m + 1):
            run += row[j - 1] - 48
            cur[j] = above[j] + run
    q = int(data[n + 2])
    idx = n + 3
    out = []
    for _ in range(q):
        r1 = int(data[idx])
        c1 = int(data[idx + 1])
        r2 = int(data[idx + 2])
        c2 = int(data[idx + 3])
        idx += 4
        out.append(pre[r2][c2] - pre[r1 - 1][c2] - pre[r2][c1 - 1] + pre[r1 - 1][c1 - 1])
    sys.stdout.write("\n".join(map(str, out)) + "\n")


main()
