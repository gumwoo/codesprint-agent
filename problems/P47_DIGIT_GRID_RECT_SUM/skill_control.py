# Skill 대조 풀이(ADR-0033). 질의마다 직사각형에 걸친 각 행의 구간을 잘라 sum 으로 더한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    m = int(data[1])
    grid = []
    for i in range(n):
        grid.append([b - 48 for b in data[2 + i]])
    q = int(data[n + 2])
    idx = n + 3
    out = []
    for _ in range(q):
        r1 = int(data[idx])
        c1 = int(data[idx + 1])
        r2 = int(data[idx + 2])
        c2 = int(data[idx + 3])
        idx += 4
        total = 0
        for r in range(r1 - 1, r2):
            total += sum(grid[r][c1 - 1:c2])
        out.append(total)
    sys.stdout.write("\n".join(map(str, out)) + "\n")


main()
