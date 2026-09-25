import sys

def main():
    data = sys.stdin.read().split()
    pos = 0
    n = int(data[pos]); pos += 1
    m = int(data[pos]); pos += 1
    q = int(data[pos]); pos += 1
    out = []
    for _ in range(q):
        t = data[pos]; pos += 1
        if t == 'R':
            r = int(data[pos]); pos += 1
            c = int(data[pos]); pos += 1
            out.append(str(r * m + c))
        else:
            i = int(data[pos]); pos += 1
            row = i // m
            col = i % m
            out.append(f"{row} {col}")
    print("\n".join(out))

main()
