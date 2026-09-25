import sys


def main():
    tok = sys.stdin.read().split()
    h = int(tok[0])
    w = int(tok[1])
    grid = [list(tok[2 + i]) for i in range(h)]
    q = int(tok[2 + h])
    ops = tok[3 + h] if q > 0 else ''
    # (t, r, c): transpose, then reverse row order, then reverse column order
    base = {'T': (1, 0, 0), 'V': (0, 1, 0), 'M': (0, 0, 1), 'R': (1, 0, 1), 'L': (1, 1, 0)}
    t = 0
    r = 0
    c = 0
    for ch in ops:
        t2, r2, c2 = base[ch]
        if t2 == 0:
            r ^= r2
            c ^= c2
        else:
            t ^= 1
            r, c = r2 ^ c, c2 ^ r
    if t:
        res = [[grid[i][j] for i in range(h)] for j in range(w)]
    else:
        res = [row[:] for row in grid]
    if r:
        res.reverse()
    if c:
        for row in res:
            row.reverse()
    sys.stdout.write('\n'.join(''.join(row) for row in res) + '\n')


main()
