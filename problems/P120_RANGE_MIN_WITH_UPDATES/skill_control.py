# Skill 대조 풀이(ADR-0033). 질의마다 수열의 l..r 부분을 잘라 min 으로 최솟값을 구하고, 갱신은 리스트 값을 바로 바꾼다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    q = int(data[1])
    arr = [int(v) for v in data[2:2 + n]]
    pos = 2 + n
    out = []
    for _ in range(q):
        op = data[pos]
        x = int(data[pos + 1])
        y = int(data[pos + 2])
        pos += 3
        if op == b'1':
            arr[x - 1] = y
        else:
            out.append(min(arr[x - 1:y]))
    sys.stdout.write('\n'.join(map(str, out)) + '\n')


main()
