import sys


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    names = []
    scores = []
    ages = []
    for i in range(n):
        names.append(data[1 + 3 * i])
        scores.append(int(data[2 + 3 * i]))
        ages.append(int(data[3 + 3 * i]))
    order = sorted(range(n), key=lambda i: (-scores[i], ages[i], names[i], i))
    print(" ".join(map(str, order)))


main()
