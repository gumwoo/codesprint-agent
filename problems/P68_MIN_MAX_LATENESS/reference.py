import sys

def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    jobs = []
    for i in range(n):
        t = int(data[1 + 2 * i])
        d = int(data[2 + 2 * i])
        jobs.append((d, t))
    jobs.sort()
    time = 0
    best = 0
    for d, t in jobs:
        time += t
        if time - d > best:
            best = time - d
    print(best)

main()
