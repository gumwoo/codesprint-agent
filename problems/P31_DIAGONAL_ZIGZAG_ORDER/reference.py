import sys


def main():
    data = sys.stdin.read().split()
    pos = 0
    r = int(data[pos]); pos += 1
    c = int(data[pos]); pos += 1
    grid = []
    for _ in range(r):
        grid.append([int(data[pos + j]) for j in range(c)])
        pos += c

    out = []
    for d in range(r + c - 1):
        lo = max(0, d - c + 1)
        hi = min(r - 1, d)
        diag = [grid[i][d - i] for i in range(lo, hi + 1)]
        if d % 2 == 0:
            diag.reverse()
        out.extend(diag)

    print(" ".join(str(v) for v in out))


main()
