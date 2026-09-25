# 스택에서 꺼내는 조건을 '작다'로 써서 수위가 같은 날에서 구간이 끊긴다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    a = list(map(int, data[1:1 + n]))
    stack = []
    res = [0] * n
    for i in range(n):
        x = a[i]
        while stack and a[stack[-1]] < x:
            stack.pop()
        res[i] = i - stack[-1] if stack else i + 1
        stack.append(i)
    print(" ".join(map(str, res)))


main()
