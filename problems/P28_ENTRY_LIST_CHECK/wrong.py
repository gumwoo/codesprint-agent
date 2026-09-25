# 존재 판정은 맞지만 YES/NO 대신 소문자 yes/no를 출력한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    registered = set(map(int, data[1:1 + n]))
    q = int(data[1 + n])
    queries = map(int, data[2 + n:2 + n + q])
    out = ["yes" if x in registered else "no" for x in queries]
    sys.stdout.write("\n".join(out) + "\n")


main()
