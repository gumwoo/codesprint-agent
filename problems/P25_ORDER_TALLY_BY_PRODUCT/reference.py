import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    cnt = {}
    tot = {}
    for i in range(n):
        k = int(data[1 + 2 * i])
        q = int(data[2 + 2 * i])
        if k in cnt:
            cnt[k] += 1
            tot[k] += q
        else:
            cnt[k] = 1
            tot[k] = q
    out = [f"{k} {cnt[k]} {tot[k]}" for k in cnt]
    sys.stdout.write("\n".join(out) + "\n")


main()
