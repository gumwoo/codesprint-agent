import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    cap = int(data[1])
    best = [0] * (cap + 1)
    pos = 2
    for _ in range(n):
        w = int(data[pos])
        v = int(data[pos + 1])
        pos += 2
        if w > cap:
            continue
        taken = [x + v for x in best[:cap + 1 - w]]
        best = best[:w] + list(map(max, best[w:], taken))
    print(best[cap])


main()
