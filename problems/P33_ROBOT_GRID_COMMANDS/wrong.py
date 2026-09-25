# F 명령에서 다음 칸이 격자 안인지 검사하지 않고 바로 grid[nr][nc] 를 읽는다.
import sys

def main():
    data = sys.stdin.read().split()
    n, m = int(data[0]), int(data[1])
    grid = data[2:2 + n]
    r, c = int(data[2 + n]), int(data[3 + n])
    d = "NESW".index(data[4 + n])
    cmds = data[6 + n]
    dr = [-1, 0, 1, 0]
    dc = [0, 1, 0, -1]
    for ch in cmds:
        if ch == 'L':
            d = (d + 3) % 4
        elif ch == 'R':
            d = (d + 1) % 4
        else:
            nr, nc = r + dr[d], c + dc[d]
            if grid[nr][nc] == '.':
                r, c = nr, nc
    print(r, c, "NESW"[d])

main()
