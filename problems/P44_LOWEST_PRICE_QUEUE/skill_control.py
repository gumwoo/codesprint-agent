# Skill 대조 풀이(ADR-0033). 가격을 일반 리스트에 두고 명령 2 마다 min() 으로 최솟값을 찾아 remove() 로 지운다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    i = 1
    items = []
    out = []
    for _ in range(n):
        if data[i] == b"1":
            items.append(int(data[i + 1]))
            i += 2
        else:
            i += 1
            if items:
                m = min(items)
                items.remove(m)
                out.append(str(m))
            else:
                out.append("-1")
    sys.stdout.write("\n".join(out) + "\n")


main()
