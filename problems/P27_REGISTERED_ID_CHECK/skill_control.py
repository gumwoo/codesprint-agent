# Skill 대조 풀이(ADR-0033). 등록 번호를 리스트로 두고 조회마다 리스트 전체를 처음부터 선형 탐색한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys

def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    registered = [int(x) for x in data[1:1 + n]]
    q = int(data[1 + n])
    queries = data[2 + n:2 + n + q]
    out = []
    for x in queries:
        out.append("1" if int(x) in registered else "0")
    sys.stdout.write("\n".join(out) + "\n")

main()
