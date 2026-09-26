import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    a = list(map(int, data[1:1 + n]))
    rank = {}
    for i, v in enumerate(sorted(set(a))):
        rank[v] = i
    sys.stdout.write(" ".join(str(rank[x]) for x in a) + "\n")


main()
