# Skill 대조 풀이(ADR-0033). 각 위치에서 글자 중심과 오른쪽 틈 중심마다 양쪽으로 한 칸씩 넓혀 보며 회문 길이를 잰다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    s = data[1]
    ans = []
    for i in range(n):
        k = 0
        while i - k - 1 >= 0 and i + k + 1 < n and s[i - k - 1] == s[i + k + 1]:
            k += 1
        a = 2 * k + 1
        b = 0
        if i + 1 < n:
            k = 0
            while i - k >= 0 and i + 1 + k < n and s[i - k] == s[i + 1 + k]:
                k += 1
            b = 2 * k
        ans.append(max(a, b))
    print(' '.join(map(str, ans)))


main()
