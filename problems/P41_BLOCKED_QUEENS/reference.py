import sys


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    grid = data[1:1 + n]
    used_col = [False] * n
    used_d1 = [False] * (2 * n)
    used_d2 = [False] * (2 * n)
    count = 0

    def place(r):
        nonlocal count
        if r == n:
            count += 1
            return
        row = grid[r]
        for c in range(n):
            if row[c] == '#' or used_col[c] or used_d1[r + c] or used_d2[r - c + n]:
                continue
            used_col[c] = used_d1[r + c] = used_d2[r - c + n] = True
            place(r + 1)
            used_col[c] = used_d1[r + c] = used_d2[r - c + n] = False

    place(0)
    print(count)


main()
