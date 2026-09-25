# 좌표 값을 정렬할 때 중복을 제거하지 않아, 같은 값이 여러 번 나오면 지도 크기와 순위가 어긋난다.
import sys


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    pts = [(int(data[1 + 2 * i]), int(data[2 + 2 * i])) for i in range(n)]
    xs = sorted(p[0] for p in pts)
    ys = sorted(p[1] for p in pts)
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
