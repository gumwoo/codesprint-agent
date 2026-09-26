# 중복을 제거하지 않은 정렬 목록에서 각 값이 처음 나타나는 위치를 순위로 써서, 앞에 중복 값이 있으면 순위가 커진다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    a = list(map(int, data[1:1 + n]))
    rank = {}
    for i, v in enumerate(sorted(a)):
        if v not in rank:
            rank[v] = i
    sys.stdout.write(" ".join(str(rank[x]) for x in a) + "\n")


main()
