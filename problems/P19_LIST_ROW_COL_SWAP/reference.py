import sys

def main():
    data = sys.stdin.read().split()
    pos = 0
    n = int(data[pos]); pos += 1
    m = int(data[pos]); pos += 1
    grid = []
    for _ in range(n):
        row = [int(data[pos + j]) for j in range(m)]
        pos += m
        grid.append(row)
    q = int(data[pos]); pos += 1
    for _ in range(q):
        cmd = data[pos]; pos += 1
        a = int(data[pos]); pos += 1
        b = int(data[pos]); pos += 1
        if cmd == 'row':
            grid[a], grid[b] = grid[b], grid[a]
        else:
            for row in grid:
                row[a], row[b] = row[b], row[a]
    print('\n'.join(' '.join(map(str, row)) for row in grid))

main()
