# 창고가 비어 있는지 확인하지 않고 힙에서 꺼내 빈 힙에서 IndexError 가 난다.
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
            out.append(str(heapq.heappop(heap)))
    sys.stdout.write("\n".join(out) + "\n")


main()
