import sys

data = sys.stdin.read().split()
n = int(data[0])
values = [int(x) for x in data[1:1 + n]]

at = 1 + n
q = int(data[at])
at += 1
for _ in range(q):
    command = data[at]
    if command == "get":
        print(values[int(data[at + 1])])
        at += 2
    else:
        values[int(data[at + 1])] = int(data[at + 2])
        at += 3
