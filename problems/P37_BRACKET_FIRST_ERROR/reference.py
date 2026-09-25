import sys


def main():
    s = sys.stdin.readline().strip()
    pair = {')': '(', ']': '[', '}': '{', '>': '<'}
    st = []
    for i, c in enumerate(s):
        if c in pair:
            if not st or st[-1] != pair[c]:
                print(i)
                return
            st.pop()
        else:
            st.append(c)
    print(-1 if not st else len(s))


main()
