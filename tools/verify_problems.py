#!/usr/bin/env python3
"""문제 데이터를 **실제로 채점해서** 검증한다. Docker 가 필요하다.

    python tools/verify_problems.py
    python tools/verify_problems.py --build     # 이미지부터 다시 굽는다
    python tools/verify_problems.py P05_SHORTEST_PATH   # 하나만

두 가지를 본다.

  reference.py 가 ACCEPTED 를 받는가
      받지 못하면 정답이 아니거나, Test Case 의 expectedOutput 이 틀렸다.
      어느 쪽이든 그 문제로는 아무도 AC 를 받을 수 없다.

  wrong.py 가 걸리는가
      **이쪽이 더 중요하다.** reference 만 검사하면 "모든 출력을 통과시키는
      Test Case 집합" 도 통과한다. 아무것도 거르지 못하는 문제가 초록불을 받는다.
      계약 하네스의 메타테스트, Judge 의 격리 대조군과 같은 논리다.

      틀린 풀이가 통과한다는 것은 그 문제가 Skill 을 측정하지 못한다는 뜻이고,
      측정하지 못하는 문제로 쌓은 Evidence 는 mastery 를 오염시킨다.

wrong.py 는 아무렇게나 틀린 코드가 아니라 **그 문제에서 실제로 자주 나오는 실수**다.
무엇을 틀리게 했는지 파일 첫 줄 주석에 적어둔다.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import subprocess
import sys
import tempfile

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parent.parent
PROBLEMS = ROOT / "problems"

sys.path.insert(0, str(ROOT / "judge"))
import run_submission  # noqa: E402


def build_job(problem: dict, cases_doc: dict) -> dict:
    """problem.yaml + cases.json 을 Judge 가 받는 job 형태로 옮긴다."""
    return {
        "problemId": problem["code"],
        "timeLimitMs": problem["timeLimitMs"],
        "memoryLimitMb": problem["memoryLimitMb"],
        "cases": [
            {"id": c["id"], "input": c["input"], "expectedOutput": c["expectedOutput"]}
            for c in cases_doc["cases"]
        ],
    }


def judge(solution: pathlib.Path, job: dict) -> dict:
    with tempfile.TemporaryDirectory() as d:
        job_path = pathlib.Path(d) / "job.json"
        job_path.write_text(json.dumps(job, ensure_ascii=False), encoding="utf-8", newline="")
        return run_submission.run(solution, job_path)


def main() -> int:
    import yaml

    parser = argparse.ArgumentParser()
    parser.add_argument("only", nargs="?", help="특정 문제 code 만 검증한다")
    parser.add_argument("--build", action="store_true")
    args = parser.parse_args()

    if args.build:
        print("이미지 빌드 중...")
        build = subprocess.run(
            ["docker", "build", "-q", "-t", run_submission.IMAGE, "-f", "judge/Dockerfile", "."],
            cwd=ROOT, capture_output=True, text=True,
        )
        if build.returncode != 0:
            print("[X] 이미지 빌드 실패:\n" + build.stderr[-800:])
            return 1

    dirs = sorted(d for d in PROBLEMS.iterdir() if d.is_dir())
    if args.only:
        dirs = [d for d in dirs if d.name == args.only]
        if not dirs:
            print(f"[X] 그런 문제가 없다: {args.only}")
            return 1

    failed = 0
    for d in dirs:
        problem = yaml.safe_load((d / "problem.yaml").read_text(encoding="utf-8"))
        cases_doc = json.loads((d / "cases.json").read_text(encoding="utf-8"))
        job = build_job(problem, cases_doc)

        ref = judge(d / "reference.py", job)
        if ref["status"] != "ACCEPTED":
            failed += 1
            detail = (ref.get("stderr") or "").strip().splitlines()[-1:] or [""]
            print(f"[X] {d.name}: reference 가 {ref['status']} "
                  f"(case {ref['failedCaseId']}) {detail[0][:80]}")
            continue

        control = problem["negativeControl"]
        expected = control["expectedStatus"]
        wrong = judge(d / "wrong.py", job)

        if wrong["status"] == "ACCEPTED":
            failed += 1
            print(f"[X] {d.name}: **틀린 풀이가 통과한다** — Test Case 가 아무것도 "
                  f"거르지 못한다 [VACUOUS]")
            continue

        # 실패했다는 것만으로는 부족하다. wrong.py 의 문법이 깨져 COMPILE_ERROR 가
        # 나도 "걸렸다" 로 읽히기 때문이다. 실제로 그런 구멍이 있었다 -
        # wrong.py 를 문법 오류로 바꿔도 검증이 통과했다.
        # 심어둔 실수가 드러나야 하는 판정을 데이터에 적어두고 그것과 대조한다.
        if wrong["status"] != expected:
            failed += 1
            hint = ""
            if wrong["status"] in ("COMPILE_ERROR", "SYSTEM_ERROR"):
                hint = " — wrong.py 자체가 깨진 것으로 보인다"
            detail = (wrong.get("stderr") or "").strip().splitlines()[-1:] or [""]
            print(f"[X] {d.name}: wrong 이 {expected} 여야 하는데 {wrong['status']}{hint}"
                  f" {detail[0][:70]}")
            continue

        print(f"[O] {d.name}: reference ACCEPTED / "
              f"wrong {wrong['status']} (case {wrong['failedCaseId']}, "
              f"심어둔 실수 {control['mistake']})")

    if failed:
        print(f"\n[FAIL] {failed}건 실패")
        return 1
    print(f"\n[OK] 문제 {len(dirs)}개 — 정답은 통과하고 오답은 걸린다")
    return 0


if __name__ == "__main__":
    sys.exit(main())
