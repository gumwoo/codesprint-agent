# 정렬 key 에 세 번째 기준(이름 오름차순)을 빠뜨려 점수와 나이가 같은 참가자가 입력 순서대로 남는다.
import sys


def main():
    data = sys.stdin.read().split()
    n = int(data[0])
    people = []
    for i in range(n):
        name = data[1 + 3 * i]
        score = int(data[2 + 3 * i])
        age = int(data[3 + 3 * i])
        people.append((name, score, age))
    people.sort(key=lambda p: (-p[1], p[2]))
    sys.stdout.write("\n".join(p[0] for p in people) + "\n")


main()
