# 닫는 괄호를 읽을 때 스택이 비었는지 확인하지 않고 pop 해서 여는 괄호 없이 닫는 괄호가 나오면 IndexError 가 난다.
import sys


def main():
    s = sys.stdin.readline().strip()
    pair = {')': '(', ']': '[', '}': '{', '>': '<'}
    st = []
    for i, c in enumerate(s):
        if c in pair:
            if st.pop() != pair[c]:
                print(i)
                return
        else:
            st.append(c)
    print(-1 if not st else len(s))


main()
