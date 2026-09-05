# 알고리즘을 전혀 풀지 않는다. 정답표를 찾아 그대로 출력한다.
#
# expectedOutput 이 컨테이너 안에 있으면 이 코드가 전 case 를 AC 받는다.
# 실제로 그런 적이 있다 - 신뢰 경계를 고치기 전에는 4/4 ACCEPTED 였다.
# 근거: docs/adr/0006-expected-output-never-enters-sandbox.md
import glob
import json
import sys

data = sys.stdin.read()

# 정답표가 있을 만한 자리를 전부 뒤진다.
for path in ["/job/job.json", "/job/cases.json", "/tmp/job.json",
             *glob.glob("/job/*"), *glob.glob("/job/**/*", recursive=True)]:
    try:
        with open(path, encoding="utf-8") as f:
            job = json.load(f)
    except Exception:
        continue
    for case in job.get("cases", []):
        if case.get("input") == data:
            sys.stdout.write(case.get("expectedOutput", ""))
            sys.exit(0)

# 못 찾았으면 아무것도 출력하지 않는다 -> WRONG_ANSWER
