# 스택에서 꺼내는 조건을 '엄격히 작은 값'이 아니라 '작거나 같은 값'으로 써서, 높이가 같은 봉우리를 더 높은 봉우리로 취급한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    a = list(map(int, data[1:1 + n]))
    ans = [0] * n
    st = []
    for i, x in enumerate(a):
        while st and a[st[-1]] <= x:
            j = st.pop()
            ans[j] = i - j
        st.append(i)
    print(' '.join(map(str, ans)))


main()
