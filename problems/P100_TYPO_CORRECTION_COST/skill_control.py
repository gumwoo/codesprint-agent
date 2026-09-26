# Skill 대조 풀이(ADR-0033). 표에 저장하지 않고 글자가 다를 때마다 교체 · 삭제 · 삽입 세 갈래로 다시 재귀한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def dist(a, b, i, j):
    if i == len(a):
        return len(b) - j
    if j == len(b):
        return len(a) - i
    if a[i] == b[j]:
        return dist(a, b, i + 1, j + 1)
    return 1 + min(dist(a, b, i + 1, j + 1), dist(a, b, i + 1, j), dist(a, b, i, j + 1))


def main():
    sys.setrecursionlimit(10000)
    data = sys.stdin.read().split()
    print(dist(data[0], data[1], 0, 0))


main()
