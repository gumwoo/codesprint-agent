# 윈도우 크기를 초과해도 popleft 를 호출하지 않는다.
import sys
from collections import deque

def main():
    data = sys.stdin.read().split()
    idx = 0
    n = int(data[idx]); idx += 1
    k = int(data[idx]); idx += 1
    values = [int(data[idx + i]) for i in range(n)]
    dq = deque()
    total = 0
    out = []
    for v in values:
        dq.append(v)
        total += v
        out.append(str(total))
    print("\n".join(out))

if __name__ == "__main__":
    main()
