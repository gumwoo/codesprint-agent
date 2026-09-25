# 명령 R 을 시계 방향 회전 대신 반시계 방향 공식으로 계산한다. 행 인덱스를 뒤집어야 할 자리에서 열 인덱스를 뒤집어, R 이 L 과 같은 동작을 한다.
import sys


def main():
    tok = sys.stdin.read().split()
    h = int(tok[0])
    w = int(tok[1])
    g = [list(tok[2 + i]) for i in range(h)]
    q = int(tok[2 + h])
    ops = tok[3 + h] if q > 0 else ''
    for ch in ops:
        n = len(g)
        m = len(g[0])
        if ch == 'T':
            ng = [[g[i][j] for i in range(n)] for j in range(m)]
        elif ch == 'R':
            ng = [[g[i][m - 1 - j] for i in range(n)] for j in range(m)]
        elif ch == 'L':
            ng = [[g[i][m - 1 - j] for i in range(n)] for j in range(m)]
        elif ch == 'V':
            ng = [[g[n - 1 - i][j] for j in range(m)] for i in range(n)]
        else:
            ng = [[g[i][m - 1 - j] for j in range(m)] for i in range(n)]
        g = ng
    out = []
    for row in g:
        out.append(''.join(row))
    sys.stdout.write('\n'.join(out) + '\n')


main()
