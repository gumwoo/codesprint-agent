# 구간의 끝을 하나 적게 잡아 마지막 구간을 보지 않는다.
import sys

data = sys.stdin.read().split()
n, k = int(data[0]), int(data[1])
values = [int(x) for x in data[2:2 + n]]

window = sum(values[:k])
best = window
for i in range(k, n - 1):
    window += values[i] - values[i - k]
    best = max(best, window)
print(best)
