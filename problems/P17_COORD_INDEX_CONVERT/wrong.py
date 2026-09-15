# 행 우선 인덱스 변환에 열의 개수 M 대신 행의 개수 N 을 곱해서 사용하는 실수를 심었다.
import sys

def main():
    data = sys.stdin.read().split()
    pos = 0
    n = int(data[pos]); pos += 1
    m = int(data[pos]); pos += 1
    q = int(data[pos]); pos += 1
    out = []
    for _ in range(q):
        t = data[pos]; pos += 1
        if t == 'R':
            r = int(data[pos]); pos += 1
            c = int(data[pos]); pos += 1
            out.append(str(r * n + c))
        else:
            i = int(data[pos]); pos += 1
            row = i // n
            col = i % n
            out.append(f"{row} {col}")
    print("\n".join(out))

main()
