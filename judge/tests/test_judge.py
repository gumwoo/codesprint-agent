#!/usr/bin/env python3
"""Judge / Sandbox 통합 테스트.

두 종류를 함께 돌린다.

  판정   fixture 코드가 의도한 status 로 판정되는가
  격리   Addendum 87 의 보안 항목이 실제로 막히는가

두 번째가 이 파일의 존재 이유다. `--network none` 을 옵션에 적어두는 것과
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
]

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


def judge(code: str) -> dict:
    """임시 solution 을 만들어 채점한다."""
    with tempfile.TemporaryDirectory() as d:
        path = pathlib.Path(d) / "solution.py"
        path.write_text(code, encoding="utf-8", newline="")
        return run_submission.run(path, JOB)


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

    print("\n== 격리 (Addendum 87) ==")
    for name, code, why in ISOLATION:
        result = judge(code)
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

    if failed:
        print(f"\n[FAIL] {failed}건 실패")
        return 1
    print(f"\n[OK] 판정 {len(VERDICTS)}건 · 격리 {len(ISOLATION)}건 모두 통과")
    return 0


if __name__ == "__main__":
    sys.exit(main())
