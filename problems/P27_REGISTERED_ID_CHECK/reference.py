import sys

def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    registered = set(int(x) for x in data[1:1 + n])
    q = int(data[1 + n])
    queries = data[2 + n:2 + n + q]
    out = []
    for x in queries:
        out.append("1" if int(x) in registered else "0")
    sys.stdout.write("\n".join(out) + "\n")

main()
