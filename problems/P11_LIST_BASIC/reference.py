import sys

data = sys.stdin.read().split()
n, k = int(data[0]), int(data[1])
values = [int(x) for x in data[2:2 + n]]

window = sum(values[:k])
best = window
for i in range(k, n):
    window += values[i] - values[i - k]
    best = max(best, window)
print(best)
