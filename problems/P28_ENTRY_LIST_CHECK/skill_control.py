# Skill 대조 풀이(ADR-0033). 명단을 리스트로 두고 질의마다 리스트를 처음부터 훑어 존재를 확인한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    registered = list(map(int, data[1:1 + n]))
    q = int(data[1 + n])
    queries = map(int, data[2 + n:2 + n + q])
    out = ["YES" if x in registered else "NO" for x in queries]
    sys.stdout.write("\n".join(out) + "\n")


main()
