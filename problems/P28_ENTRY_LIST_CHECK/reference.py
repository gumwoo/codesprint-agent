import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    registered = set(map(int, data[1:1 + n]))
    q = int(data[1 + n])
    queries = map(int, data[2 + n:2 + n + q])
    out = ["YES" if x in registered else "NO" for x in queries]
    sys.stdout.write("\n".join(out) + "\n")


main()
