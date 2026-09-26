import sys


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    pts = [(int(data[1 + 2 * i]), int(data[2 + 2 * i])) for i in range(n)]
    xs = sorted(set(p[0] for p in pts))
    ys = sorted(set(p[1] for p in pts))
    xr = {v: i for i, v in enumerate(xs)}
    yr = {v: i for i, v in enumerate(ys)}
    h, w = len(ys), len(xs)
    grid = [[0] * w for _ in range(h)]
    for x, y in pts:
        grid[yr[y]][xr[x]] += 1
    out = [f'{h} {w}']
    out.extend(' '.join(map(str, row)) for row in grid)
    print('\n'.join(out))


main()
