# Q 값을 건너뛰지 않고 조회 번호로 읽어 출력 줄이 하나 더 많고 순서가 밀린다.
import sys

def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    registered = set(int(x) for x in data[1:1 + n])
    queries = data[1 + n:]
    out = []
    for x in queries:
        out.append("1" if int(x) in registered else "0")
    sys.stdout.write("\n".join(out) + "\n")

main()
