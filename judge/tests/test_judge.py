#!/usr/bin/env python3
"""Judge / Sandbox 통합 테스트.

두 종류를 함께 돌린다.

  판정     fixture 코드가 의도한 status 로 판정되는가
  격리     Addendum 87 의 보안 항목이 실제로 막히는가
  기밀성   채점 데이터(정답표)가 컨테이너 안으로 새지 않는가 (ADR-0006)

뒤의 둘이 이 파일의 존재 이유다. `--network none` 을 옵션에 적어두는 것과
**네트워크가 실제로 안 되는 것**은 다르다. 옵션을 지우거나 오타를 내도 채점은
정상으로 보이고, 아무도 모른 채 신뢰 경계가 사라진다.

    python judge/tests/test_judge.py
    python judge/tests/test_judge.py --build   # 이미지부터 다시 굽는다
"""
from __future__ import annotations

import argparse
import json
import pathlib
import subprocess
import sys
import tempfile

from jsonschema import Draft202012Validator

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
FIXTURES = ROOT / "judge" / "fixtures"
JOB = FIXTURES / "job-grid-area.json"
RUNNER = ROOT / "judge" / "run_submission.py"

sys.path.insert(0, str(ROOT / "judge"))
import run_submission  # noqa: E402

# 채점 결과는 계약을 지켜야 한다. 지키지 않으면 backend 가 받아 파싱할 때 터지거나,
# 더 나쁘게는 null 이어야 할 자리가 비어 있는 채로 흘러간다.
RESULT_SCHEMA = Draft202012Validator(
    json.loads((ROOT / "contracts" / "judge-result.schema.json").read_text(encoding="utf-8"))
)


def contract_errors(result: dict) -> list[str]:
    return [f"{list(e.path)}: {e.message}" for e in RESULT_SCHEMA.iter_errors(result)]


# -- 판정 -----------------------------------------------------------------
# (fixture, 기대 status, 실패 case 를 특정해야 하는가)
#
# 마지막 열이 중요하다. Reviewer 출력의 failedCaseRefs 는 minItems 1 이라,
# Reviewer 를 호출하는 판정에서 failedCaseId 가 null 이면 근거를 만들 수 없다.
# ADR-0004 가 계약으로 정한 것을 여기서 실제로 확인한다.
VERDICTS = [
    ("sol-accepted.py", "ACCEPTED", False),
    ("sol-wrong.py", "WRONG_ANSWER", True),
    ("sol-runtime-error.py", "RUNTIME_ERROR", True),
    ("sol-timeout.py", "TIME_LIMIT", True),
    ("sol-memory.py", "MEMORY_LIMIT", True),
    ("sol-output-flood.py", "OUTPUT_LIMIT", True),
    ("sol-syntax-error.py", "COMPILE_ERROR", False),
    # 경계 검사 누락은 IndexError 로 터지기도 하고, Python 의 음수 인덱싱 때문에
    # 조용히 틀린 답을 내기도 한다(curriculum/mistakes.yaml BOUNDARY_CHECK).
    # 이 fixture 는 후자다 - 크래시 없이 WA 가 된다.
    ("sol-boundary-missing.py", "WRONG_ANSWER", True),
    # 정답표를 찾아 그대로 출력하려는 제출. 컨테이너 안에 expectedOutput 이 없으므로
    # 찾지 못하고 빈 출력을 낸다(ADR-0006). 아래 CONFIDENTIALITY 가 더 강하게 검사한다.
    ("sol-answer-leak.py", "WRONG_ANSWER", True),
]

# -- 실패의 모양 (ADR-0015) -----------------------------------------------
# 첫 실패에서 멈추면 "무엇이 통과했는가" 를 알 수 없고, 그러면 Reviewer 주장을
# 뒷받침할 독립 근거를 만들 수 없다. 그래서 싼 실패에서는 끝까지 돌린다.
#
# 반대로 비싼 실패(제한에 걸릴 때까지 기다린 것)에서 계속 돌리면 무한 루프 하나가
# case 수만큼의 timeout 을 먹는다 - ADR-0005 가 조기 종료를 택한 이유이며 그 비용
# 특성은 지켜야 한다. **두 방향을 함께 본다.**
#
# (이름, 남은 case 를 계속 돌려야 하는가)
PROFILE = [
    ("sol-wrong.py", True),
    ("sol-runtime-error.py", True),
    ("sol-boundary-missing.py", True),
    ("sol-timeout.py", False),
    ("sol-memory.py", False),
    ("sol-output-flood.py", False),
]

# judge-result.schema.json 의 status 중 위에서 다루지 않는 것.
# SYSTEM_ERROR 는 사용자 코드로 재현할 수 없어 별도 경로로 확인한다(아래 main).
STATUS_COVERED_ELSEWHERE = {"SYSTEM_ERROR"}

# -- 격리 (Addendum 87) ---------------------------------------------------
# (이름, 사용자 코드, 이 코드가 성공하면 안 되는 이유)
ISOLATION = [
    (
        "네트워크 차단",
        "import socket\n"
        "socket.setdefaulttimeout(3)\n"
        "socket.create_connection(('1.1.1.1', 53))\n"
        "print('연결됨')\n",
        "외부로 데이터를 보내거나 도구를 받아올 수 있다",
    ),
    (
        "DNS 조회 차단",
        "import socket\nprint(socket.gethostbyname('example.com'))\n",
        "이름 해석만으로도 데이터를 밖으로 실어 보낼 수 있다",
    ),
    (
        "루트 파일시스템 쓰기 차단",
        "open('/evil', 'w').write('x')\nprint('썼다')\n",
        "이미지를 변조해 다음 제출의 채점에 영향을 줄 수 있다",
    ),
    (
        "채점 하네스 변조 차단",
        "open('/opt/judge/harness.py', 'a').write('\\n')\nprint('썼다')\n",
        "채점 로직 자체를 바꿔 판정을 조작할 수 있다",
    ),
    (
        "마운트 읽기 전용",
        "open('/job/job.json', 'w').write('{}')\nprint('썼다')\n",
        "Test Case 를 바꿔 오답을 정답으로 만들 수 있다",
    ),
    (
        "root 아님",
        "import os\nassert os.geteuid() == 0, '루트 아님'\nprint('루트다')\n",
        "컨테이너 탈출 시도의 난이도가 크게 낮아진다",
    ),
    (
        "fork bomb 제한",
        "import os\n"
        "for _ in range(500):\n"
        "    try:\n"
        "        if os.fork() == 0:\n"
        "            os._exit(0)\n"
        "    except OSError:\n"
        "        raise SystemExit('제한됨')\n"
        "print('전부 fork 됨')\n",
        "호스트의 프로세스 테이블을 고갈시킬 수 있다",
    ),
    (
        "tmpfs 실행 차단",
        "import os, stat, subprocess\n"
        "p = '/tmp/x.sh'\n"
        "open(p, 'w').write('#!/bin/sh\\necho hi\\n')\n"
        "os.chmod(p, stat.S_IRWXU)\n"
        "subprocess.run([p], check=True)\n"
        "print('실행됨')\n",
        "받아온 바이너리를 실행할 발판이 된다",
    ),
]


# 컨테이너 안을 훑어 채점 데이터가 새어 들어왔는지 보는 프로브들.
LEAK_PROBE_CASES = """
import glob, json
found = []
for p in glob.glob('/job/**/*', recursive=True) + glob.glob('/tmp/**/*', recursive=True):
    try:
        d = json.load(open(p))
    except Exception:
        continue
    if isinstance(d, dict) and 'cases' in d:
        found.append(p)
print('LEAK' if found else 'CLEAN')
"""

# 자기 자신(/job/solution.py)은 제외한다. 이 프로브의 소스에 'expectedOutput' 이라는
# 문자열이 들어 있어서, 빼지 않으면 자기를 읽고 LEAK 로 오탐한다. 실제로 그랬다.
# 사용자가 자기 제출 코드를 읽는 것은 유출이 아니다.
LEAK_PROBE_KEYS = """
import glob, os
needles = ('expectedOutput', '"cases"')
hay = ' '.join(f'{k}={v}' for k, v in os.environ.items())
for p in glob.glob('/job/**/*', recursive=True) + glob.glob('/tmp/**/*', recursive=True):
    if os.path.realpath(p) == os.path.realpath('/job/solution.py'):
        continue
    try:
        hay += open(p, errors='ignore').read()
    except Exception:
        pass
print('LEAK' if any(n in hay for n in needles) else 'CLEAN')
"""

LEAK_PROBE_LISTING = """
import os
print(','.join(sorted(os.listdir('/job'))))
"""


# -- 채점 데이터 기밀성 (ADR-0006) ---------------------------------------
# 실행 격리와 별개의 축이다. 코드가 갇혀 있어도 정답표를 읽을 수 있으면
# 알고리즘을 하나도 풀지 않고 전 case 를 AC 받을 수 있다. 실제로 그랬다.
#
# 프로브는 정답과 비교하지 않고 **사용자 출력 자체**를 확인한다.
CONFIDENTIALITY = [
    (
        "정답표가 컨테이너 안에 없다",
        LEAK_PROBE_CASES,
        "CLEAN",
    ),
    (
        "expectedOutput 이라는 키가 어디에도 없다",
        LEAK_PROBE_KEYS,
        "CLEAN",
    ),
    (
        "마운트에는 제출 코드만 있다",
        LEAK_PROBE_LISTING,
        "solution.py",
    ),
]


def judge(code: str) -> dict:
    """임시 solution 을 만들어 채점한다."""
    with tempfile.TemporaryDirectory() as d:
        path = pathlib.Path(d) / "solution.py"
        path.write_text(code, encoding="utf-8", newline="")
        return run_submission.run(path, JOB)


def judge_with_job(code: str, job: dict) -> dict:
    """job 을 직접 지정해 채점한다."""
    with tempfile.TemporaryDirectory() as d:
        base = pathlib.Path(d)
        (base / "solution.py").write_text(code, encoding="utf-8", newline="")
        (base / "job.json").write_text(
            json.dumps(job, ensure_ascii=False), encoding="utf-8", newline="")
        return run_submission.run(base / "solution.py", base / "job.json")


def _judge_containers() -> set[str]:
    """지금 살아 있는 채점 컨테이너 이름들."""
    proc = subprocess.run(
        ["docker", "ps", "-a", "--filter", "name=codesprint-judge-", "--format", "{{.Names}}"],
        capture_output=True, text=True, errors="replace",
    )
    return {n for n in proc.stdout.split() if n}


def judge_probe(code: str, expected_stdout: str) -> str | None:
    """프로브 코드를 돌리고 **사용자 출력 자체**를 돌려준다.

    판정이 아니라 출력을 봐야 한다. 채점 결과는 ACCEPTED/WRONG_ANSWER 로만 말하므로
    "무엇이 보였는가" 를 알 수 없다. expectedOutput 을 프로브가 낼 값으로 두고
    1-case job 을 만들어, ACCEPTED 면 그 값이 나온 것으로 읽는다.

    이 job 의 expectedOutput 은 프로브의 기대 출력("CLEAN" 등)이라 정답표가 아니다 -
    프로브가 그걸 읽어도 의미가 없다.
    """
    with tempfile.TemporaryDirectory() as d:
        base = pathlib.Path(d)
        (base / "solution.py").write_text(code, encoding="utf-8", newline="")
        job = {
            "problemId": 0,
            "timeLimitMs": 5000,
            "cases": [{"id": 1, "input": "", "expectedOutput": expected_stdout}],
        }
        (base / "job.json").write_text(json.dumps(job, ensure_ascii=False), encoding="utf-8", newline="")
        result = run_submission.run(base / "solution.py", base / "job.json")

    if result["status"] == "ACCEPTED":
        return expected_stdout
    if result["status"] == "WRONG_ANSWER":
        return "(기대와 다른 출력)"
    return None


def judge_unrestricted(code: str) -> dict:
    """격리 옵션을 **걷어내고** 같은 코드를 돌린다. 대조군이다.

    격리 테스트가 통과하는 것만으로는 부족하다. 사용자 코드에 오타가 있어도
    RUNTIME_ERROR 가 나므로 막힌 것처럼 보인다 - 아무것도 검증하지 않는 테스트가
    초록불을 내는 상태다.

    그래서 제한 없이 한 번 더 돌려 **그때는 실행에 성공하는지** 확인한다.
    제한이 있을 때만 실패해야 그 실패가 격리 덕분이라고 말할 수 있다.
    """
    limits, mount = run_submission.DOCKER_LIMITS, run_submission.MOUNT_MODE
    try:
        run_submission.DOCKER_LIMITS = ["--memory", "512m", "--user", "root"]
        # 마운트 읽기 전용은 DOCKER_LIMITS 가 아니라 -v 의 :ro 가 막는다.
        # 이것도 함께 뒤집지 않으면 대조군에서도 막혀 "제한을 걷어내도 실패한다" 로
        # 오판한다. 실제로 그렇게 나왔고, 대조군이 그것을 잡아줬다.
        run_submission.MOUNT_MODE = "rw"
        return judge(code)
    finally:
        run_submission.DOCKER_LIMITS, run_submission.MOUNT_MODE = limits, mount


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--build", action="store_true", help="이미지를 다시 빌드한다")
    args = parser.parse_args()

    if args.build:
        print("이미지 빌드 중...")
        build = subprocess.run(
            ["docker", "build", "-q", "-t", run_submission.IMAGE,
             "-f", "judge/Dockerfile", "."],
            cwd=ROOT, capture_output=True, text=True,
        )
        if build.returncode != 0:
            print("[X] 이미지 빌드 실패:\n" + build.stderr[-800:])
            return 1

    failed = 0

    print("== 판정 ==")
    for name, expected, needs_case in VERDICTS:
        result = judge((FIXTURES / name).read_text(encoding="utf-8"))

        violations = contract_errors(result)
        if violations:
            failed += 1
            print(f"[X] {name}: 결과가 judge-result.schema.json 을 어긴다")
            for v in violations[:3]:
                print(f"    {v}")
            continue

        actual = result["status"]
        if actual != expected:
            failed += 1
            print(f"[X] {name}: {expected} 를 기대했는데 {actual}")
            print(f"    {json.dumps(result, ensure_ascii=False)[:200]}")
            continue
        # Reviewer 를 호출하는 판정은 근거가 될 case 를 반드시 특정해야 한다(ADR-0004).
        if needs_case and result["failedCaseId"] is None:
            failed += 1
            print(f"[X] {name}: {actual} 인데 failedCaseId 가 null 이다 "
                  f"(Reviewer 가 failedCaseRefs 를 채울 수 없다)")
            continue
        if not needs_case and result["failedCaseId"] is not None:
            failed += 1
            print(f"[X] {name}: {actual} 인데 failedCaseId 가 있다")
            continue
        print(f"[O] {name} -> {actual}")

    print("\n== 실패의 모양 (ADR-0015) ==")
    total_cases = len(json.loads(JOB.read_text(encoding="utf-8"))["cases"])
    for name, run_all in PROFILE:
        result = judge((FIXTURES / name).read_text(encoding="utf-8"))
        executed = result["cases"]
        if result["status"] == "SYSTEM_ERROR":
            failed += 1
            print(f"[X] {name}: 채점 자체가 실패했다 - {result.get('stderr')}")
            continue

        if run_all and len(executed) != total_cases:
            failed += 1
            print(f"[X] {name}: {len(executed)}/{total_cases} case 에서 멈췄다 "
                  f"- 통과한 case 를 알 수 없으면 독립 근거를 만들 수 없다")
            continue
        if not run_all and len(executed) >= total_cases:
            failed += 1
            print(f"[X] {name}: {result['status']} 인데 끝까지 돌았다 "
                  f"- 무한 루프 하나가 case 수만큼의 timeout 을 먹는다")
            continue

        # 판정과 근거는 **첫** 실패가 정한다. 뒤의 case 가 이 값을 덮으면
        # Reviewer 에게 주는 case 가 제출마다 흔들린다.
        first_failed = next((c for c in executed if c["status"] != "ACCEPTED"), None)
        if first_failed is None:
            failed += 1
            print(f"[X] {name}: 실패한 case 가 하나도 없다")
            continue
        if (result["status"], result["failedCaseId"]) != (
                first_failed["status"], first_failed["id"]):
            failed += 1
            print(f"[X] {name}: 판정이 첫 실패({first_failed['status']} "
                  f"case {first_failed['id']})와 다르다 - "
                  f"{result['status']} case {result['failedCaseId']}")
            continue

        passed_ids = [c["id"] for c in executed if c["status"] == "ACCEPTED"]
        print(f"[O] {name} -> 실행 {len(executed)}/{total_cases}, "
              f"통과 {passed_ids}, 첫 실패 case {result['failedCaseId']}")

    print("\n== 진단 예산 (ADR-0015) ==")
    # 싼 실패라고 case 가 빠른 것은 아니다. 제한 직전까지 돌다 WA 가 나는 case 가
    # 이어지면 최악은 여전히 `case 수 x timeLimit` 이고, 그러면 hard timeout 에 걸려
    # **사용자 코드가 느린 것이 SYSTEM_ERROR(우리 잘못)로 둔갑한다.**
    slow = (FIXTURES / "sol-slow-wrong.py").read_text(encoding="utf-8")
    original_budget = run_submission.DIAGNOSTIC_BUDGET_MS
    try:
        run_submission.DIAGNOSTIC_BUDGET_MS = 500
        limited = judge(slow)
    finally:
        run_submission.DIAGNOSTIC_BUDGET_MS = original_budget
    full = judge(slow)

    if limited["status"] != "WRONG_ANSWER" or full["status"] != "WRONG_ANSWER":
        failed += 1
        print(f"[X] 진단 예산: 판정이 WRONG_ANSWER 가 아니다 "
              f"({limited['status']} / {full['status']})")
    elif len(limited["cases"]) >= len(full["cases"]):
        # 대조군: 예산을 넉넉히 주면 더 돌아야 한다. 양쪽이 같으면 이 테스트는
        # 예산이 아니라 다른 이유로 멈춘 것을 보고 있는 것이다.
        failed += 1
        print(f"[X] 진단 예산: 예산을 줄여도 실행한 case 가 줄지 않았다 "
              f"({len(limited['cases'])} vs {len(full['cases'])}) [VACUOUS]")
    elif limited["failedCaseId"] != full["failedCaseId"]:
        failed += 1
        print(f"[X] 진단 예산: 예산이 판정 근거를 바꿨다 "
              f"(case {limited['failedCaseId']} vs {full['failedCaseId']})")
    else:
        print(f"[O] 진단 예산 -> 예산 500ms 에서 {len(limited['cases'])}개, "
              f"기본 예산에서 {len(full['cases'])}개 실행, 판정은 그대로")

    print("\n== 격리 (Addendum 87) ==")
    for name, code, why in ISOLATION:
        result = judge(code)
        # SYSTEM_ERROR 는 우리 인프라가 고장난 것이지 격리가 뚫린 것이 아니다.
        # 둘을 같은 메시지로 보고하면 원인을 엉뚱한 곳에서 찾게 된다.
        if result["status"] == "SYSTEM_ERROR":
            failed += 1
            print(f"[X] {name}: 채점 자체가 실패했다 (격리와 무관) - {result.get('stderr')}")
            continue
        # 격리가 동작하면 사용자 코드는 실행에 실패한다.
        if result["status"] != "RUNTIME_ERROR":
            failed += 1
            print(f"[X] {name}: 막히지 않았다 ({result['status']}) - {why}")
            continue

        # 대조군: 제한을 걷어내면 같은 코드가 실행에 성공해야 한다.
        # 여기서도 RUNTIME_ERROR 면 위의 실패는 격리 때문이 아니라 코드가 원래
        # 잘못된 것이다 - 아무것도 검증하지 못하는 테스트다.
        control = judge_unrestricted(code)
        if control["status"] == "RUNTIME_ERROR":
            failed += 1
            print(f"[X] {name}: 제한을 걷어내도 실패한다 - 이 테스트는 격리를 "
                  f"검증하지 못한다 [VACUOUS]")
            print(f"    {(control.get('stderr') or '').strip().splitlines()[-1:]}")
            continue
        print(f"[O] {name} -> 제한 있음 RUNTIME_ERROR / 제한 없음 {control['status']}")

    print("\n== 채점 데이터 기밀성 (ADR-0006) ==")
    for name, code, expected_stdout in CONFIDENTIALITY:
        seen = judge_probe(code, expected_stdout)
        if seen is None:
            failed += 1
            print(f"[X] {name}: 프로브가 실행되지 않았다")
            continue
        if seen != expected_stdout:
            failed += 1
            print(f"[X] {name}: '{expected_stdout}' 를 기대했는데 '{seen}' [정답표 유출]")
            continue
        print(f"[O] {name} -> {seen}")

    print("\n== SYSTEM_ERROR 경로 ==")
    # 사용자 코드로는 재현할 수 없다. 우리 인프라가 고장난 상황을 직접 만든다.
    broken = run_submission.run(FIXTURES / "sol-accepted.py", FIXTURES / "does-not-exist.json")
    if broken["status"] != "SYSTEM_ERROR":
        failed += 1
        print(f"[X] job 이 없으면 SYSTEM_ERROR 여야 하는데 {broken['status']}")
    elif contract_errors(broken):
        failed += 1
        print("[X] SYSTEM_ERROR 결과가 계약을 어긴다")
    else:
        print("[O] job 을 읽지 못하면 -> SYSTEM_ERROR")

    print("\n== hard timeout 후 컨테이너 회수 ==")
    # --rm 은 컨테이너가 스스로 종료했을 때만 지워준다. hard timeout 으로 docker CLI 를
    # 끊으면 컨테이너는 계속 돌 수 있고, 그러면 CPU/메모리를 계속 먹는다.
    # 짧은 hard timeout 을 걸고 무한 루프를 돌려 잔존 컨테이너가 없는지 확인한다.
    before = _judge_containers()
    original_timeout = run_submission.SUBMISSION_HARD_TIMEOUT_S
    try:
        run_submission.SUBMISSION_HARD_TIMEOUT_S = 3
        # case timeout(60s)이 hard timeout(3s)보다 훨씬 길어야 하네스가 붙잡혀 있다.
        stuck = judge_with_job(
            "while True:\n    pass\n",
            {"problemId": 0, "timeLimitMs": 60000,
             "cases": [{"id": 1, "input": "", "expectedOutput": "x"}]},
        )
    finally:
        run_submission.SUBMISSION_HARD_TIMEOUT_S = original_timeout

    if stuck["status"] != "SYSTEM_ERROR":
        failed += 1
        print(f"[X] hard timeout 인데 {stuck['status']} 가 나왔다")
    else:
        leftover = _judge_containers() - before
        if leftover:
            failed += 1
            print(f"[X] 컨테이너가 남았다: {sorted(leftover)}")
            for name in leftover:
                subprocess.run(["docker", "rm", "-f", name], capture_output=True)
        else:
            print("[O] hard timeout -> SYSTEM_ERROR, 컨테이너 잔존 없음")

    print("\n== status 커버리지 ==")
    # 판정 fixture 개수와 status 종류 수는 다르다(WRONG_ANSWER 가 여러 번 나온다).
    # "8종 전부 검증한다" 고 말하려면 근거가 있어야 한다.
    all_status = set(
        json.loads((ROOT / "contracts" / "judge-result.schema.json").read_text(encoding="utf-8"))
        ["properties"]["status"]["enum"]
    )
    tested = {expected for _, expected, _ in VERDICTS} | STATUS_COVERED_ELSEWHERE
    missing = sorted(all_status - tested)
    if missing:
        failed += 1
        print(f"[X] 한 번도 검사하지 않는 status: {missing}")
    else:
        print(f"[O] judge-result.schema.json 의 status {len(all_status)}종 전부 검사한다")

    if failed:
        print(f"\n[FAIL] {failed}건 실패")
        return 1
    print(f"\n[OK] 판정 {len(VERDICTS)}건 · 실패의 모양 {len(PROFILE)}건 · "
          f"격리 {len(ISOLATION)}건 · 기밀성 {len(CONFIDENTIALITY)}건 · "
          f"컨테이너 회수 · status 8종 커버 — 모두 통과")
    return 0


if __name__ == "__main__":
    sys.exit(main())
