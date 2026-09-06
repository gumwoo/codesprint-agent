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


def failed_ids(result: dict) -> set[int]:
    """통과하지 못한 case. **실행되지 않은 case 는 여기 없다.**

    "실패했다" 와 "거기까지 가지 못했다" 를 섞으면, 첫 case 에서 멈춘 제출이
    뒤쪽 태그까지 만족한 것으로 읽힌다.
    """
    return {c["id"] for c in result.get("cases", []) if c["status"] != "ACCEPTED"}


def passed_ids(result: dict) -> set[int]:
    return {c["id"] for c in result.get("cases", []) if c["status"] == "ACCEPTED"}


def satisfies(result: dict, probed: set[int], all_ids: set[int]) -> bool:
    """백엔드가 런타임에 쓰는 조건과 **같은 조건**이다(ADR-0015).

    CaseCorroboration.java 와 여기가 갈리면, CI 는 통과하는데 실제로는 확정되지
    않는(또는 그 반대의) 상태가 된다.
    """
    if not probed:
        return False
    return probed <= failed_ids(result) and bool((all_ids - probed) & passed_ids(result))


def competing_solutions(d: pathlib.Path, problem: dict) -> dict[str, pathlib.Path]:
    """이 문제의 commonMistakes 를 담은 풀이들.

    negativeControl 로 심어둔 실수는 wrong.py 가 그 풀이다(ADR-0007). 나머지는
    probes/<MISTAKE>.py 에 있다.
    """
    control = (problem.get("negativeControl") or {}).get("mistake")
    solutions: dict[str, pathlib.Path] = {}
    for mistake in problem.get("commonMistakes") or []:
        solutions[mistake] = (
            d / "wrong.py" if mistake == control else d / "probes" / f"{mistake}.py")
    return solutions


def verify_probes(d: pathlib.Path, cases_doc: dict, problem: dict, job: dict) -> list[str]:
    """probes 태그가 실제 채점으로 성립하는지 본다.

    태그는 "그 실수가 있으면 이 case 는 반드시 실패한다" 는 주장이고, 그 주장이
    Reviewer 밖의 독립 근거로 쓰인다. **주장을 검사하지 않으면 근거가 아니다.**

    두 가지를 함께 본다. 한쪽만 보면 아무것도 거르지 못한다.

      민감도  그 실수를 담은 풀이가 태그된 case 를 **전부** 실패시키는가
      특이도  이 문제에서 **경쟁하는 다른 모든 실수**가 그 조건을 만족하지 **않는가**

    특이도가 하나의 오답(wrong.py)만으로는 부족하다. 실제로 그랬다 - P05 와 P10 은
    BOUNDARY_CHECK 태그를 OUTPUT_FORMAT 오답이 그대로 만족했고, 그 상태로 두면
    출력 형식만 틀린 사용자가 경계 검사 드릴을 받는다. 그래서 commonMistakes 전체와
    대조한다.
    """
    problems_msgs: list[str] = []
    all_ids = {c["id"] for c in cases_doc["cases"]}
    tagged: dict[str, set[int]] = {}
    for c in cases_doc["cases"]:
        for mc in c.get("probes") or []:
            tagged.setdefault(mc, set()).add(c["id"])
    if not tagged:
        return problems_msgs

    solutions = competing_solutions(d, problem)
    results: dict[str, dict] = {}
    for mistake, path in sorted(solutions.items()):
        if not path.exists():
            # check_problems.py 가 먼저 막지만, 그쪽만 믿고 여기서 조용히
            # 건너뛰면 파일이 사라졌을 때 검증이 통과한다.
            problems_msgs.append(f"{path.relative_to(d)} 가 없다")
            continue
        result = judge(path, job)
        if result["status"] == "ACCEPTED":
            # 통과하는 풀이는 대조군이 아니다. 이 문제로는 그 실수를 잡을 수
            # 없다는 뜻이므로, 태그를 붙일 자격도 없고 힌트로 줄 자격도 없다.
            problems_msgs.append(
                f"{mistake}: 그 실수를 담은 풀이가 ACCEPTED 다 - 이 문제는 그 실수를 "
                f"잡지 못한다. commonMistakes 에서 빼거나 case 를 보강한다 [VACUOUS]")
            continue
        results[mistake] = result

    for mistake, probed in sorted(tagged.items()):
        if mistake not in results:
            continue

        # 민감도
        if not satisfies(results[mistake], probed, all_ids):
            r = results[mistake]
            problems_msgs.append(
                f"{mistake}: 태그된 case {sorted(probed)} 를 그 실수를 담은 풀이가 "
                f"만족시키지 못한다 "
                f"(실패 {sorted(failed_ids(r))}, 통과 {sorted(passed_ids(r))})")
            continue

        # 특이도. **경쟁하는 실수 전부**와 대조한다.
        for other, result in sorted(results.items()):
            if other == mistake:
                continue
            if satisfies(result, probed, all_ids):
                problems_msgs.append(
                    f"{mistake}: **{other} 오답도 같은 조건을 만족한다** — "
                    f"{other} 를 낸 사용자가 {mistake} 로 확정될 수 있다. "
                    f"이 태그는 두 실수를 구별하지 못한다 "
                    f"(실패 {sorted(failed_ids(result))}, "
                    f"통과 {sorted(passed_ids(result))}) [VACUOUS]")
    return problems_msgs


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

        probe_msgs = verify_probes(d, cases_doc, problem, job)
        if probe_msgs:
            failed += 1
            print(f"[X] {d.name}: case 성격 태그가 성립하지 않는다")
            for msg in probe_msgs:
                print(f"    {msg}")
            continue

        print(f"[O] {d.name}: reference ACCEPTED / "
              f"wrong {wrong['status']} (case {wrong['failedCaseId']}, "
              f"심어둔 실수 {control['mistake']})"
              + (f" / probes {sorted({m for c in cases_doc['cases'] for m in c['probes']})}"
                 if any(c["probes"] for c in cases_doc["cases"]) else ""))

    if failed:
        print(f"\n[FAIL] {failed}건 실패")
        return 1
    print(f"\n[OK] 문제 {len(dirs)}개 — 정답은 통과하고 오답은 걸린다")
    return 0


if __name__ == "__main__":
    sys.exit(main())
