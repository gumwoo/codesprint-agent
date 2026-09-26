# Skill 대조 풀이(ADR-0033). 가능한 모든 완료 순서를 재귀로 하나씩 만들어 총 소요 시간을 계산한다.
# 답은 reference 와 같고, 큰 case 에서 시간 안에 끝나지 않아야 한다.
import sys


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    t = [int(x) for x in data[1:1 + n]]
    p = 1 + n
    d = [[int(x) for x in data[p + i * n:p + (i + 1) * n]] for i in range(n)]
    done = []
    used = [False] * n
    best = [None]

    def go(total):
        if len(done) == n:
            if best[0] is None or total < best[0]:
                best[0] = total
            return
        for i in range(n):
            if used[i]:
                continue
            s = 0
            for j in done:
                s += d[j][i]
            used[i] = True
            done.append(i)
            go(total + max(1, t[i] - s))
            done.pop()
            used[i] = False

    go(0)
    print(best[0])


main()
