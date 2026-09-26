# Skill 대조 풀이(ADR-0033). 봉우리마다 오른쪽으로 한 칸씩 훑어 처음으로 더 높은 봉우리를 찾는다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.buffer.read().split()
    n = int(data[0])
    a = list(map(int, data[1:1 + n]))
    ans = [0] * n
    for i in range(n):
        h = a[i]
        j = i + 1
        while j < n and a[j] <= h:
            j += 1
        if j < n:
            ans[i] = j - i
    print(' '.join(map(str, ans)))


main()
