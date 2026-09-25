# 정렬 기준을 마감이 아니라 처리 시간으로 잡아 처리 시간이 짧은 작업부터 처리한다.
import sys

def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    jobs = []
    for i in range(n):
        t = int(data[1 + 2 * i])
        d = int(data[2 + 2 * i])
        jobs.append((t, d))
    jobs.sort()
    time = 0
    best = 0
    for t, d in jobs:
        time += t
        if time - d > best:
            best = time - d
    print(best)

main()
