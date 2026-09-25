import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    a = list(map(int, data[1:1 + n]))
    ans = [0] * n
    st = []
    for i, x in enumerate(a):
        while st and a[st[-1]] < x:
            j = st.pop()
            ans[j] = i - j
        st.append(i)
    print(' '.join(map(str, ans)))


main()
