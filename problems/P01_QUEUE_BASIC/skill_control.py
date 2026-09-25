# Skill 대조 풀이(ADR-0033). deque 대신 list 의 pop(0) 으로 앞에서 꺼낸다.
# 답은 reference 와 같다. 줄이 길면 꺼낼 때마다 나머지 원소를 옮기느라 시간 안에 끝나지 않는다.
import sys

lines = sys.stdin.read().splitlines()
m, n = map(int, lines[0].split())
q = list(range(1, m + 1))
out = []
for i in range(1, n + 1):
    parts = lines[i].split()
    if parts[0] == "push":
        q.append(int(parts[1]))
    else:
        out.append(str(q.pop(0)) if q else "-1")
print("\n".join(out))
