import sys
import heapq


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    i = 1
    heap = []
    out = []
    for _ in range(n):
        if data[i] == b"1":
            heapq.heappush(heap, int(data[i + 1]))
            i += 2
        else:
            i += 1
            if heap:
                out.append(str(heapq.heappop(heap)))
            else:
                out.append("-1")
    sys.stdout.write("\n".join(out) + "\n")


main()
