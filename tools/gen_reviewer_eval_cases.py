#!/usr/bin/env python3
"""Reviewer 정확도를 재기 위한 **라벨된 오답** 집합을 만든다. Docker 가 필요하다.

    python tools/gen_reviewer_eval_cases.py           # 검증 (지금 파일이 맞는가)
    python tools/gen_reviewer_eval_cases.py --write   # 다시 생성

정본: ADR-0016.

라벨은 새로 만드는 것이 아니다. 저장소에는 이미 **무슨 실수를 심었는지 선언되고
CI 가 실제 채점으로 검증한** 오답이 있다.

    wrong.py              problem.yaml 의 negativeControl.mistake  (ADR-0007)
    probes/<MISTAKE>.py   그 이름의 실수                            (ADR-0015)

여기서는 그것들을 실제로 채점해서 Reviewer 가 받게 될 입력과 함께 묶어 둔다.
평가할 때 Docker 없이 돌리기 위해서다 - 평가는 모델을 부르는 것만으로도 느리고
비싸므로, 채점까지 매번 다시 하지 않는다.

⚠️ 정확도를 재는 것이지 정확도를 **높이는** 것이 아니다. 이 파일을 보고 프롬프트를
고치면 평가 집합에만 맞춘 프롬프트가 된다.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

import yaml
from jsonschema import Draft202012Validator

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parent.parent
PROBLEMS = ROOT / "problems"
OUT = ROOT / "tests" / "eval" / "reviewer"

sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "judge"))
import verify_problems as V  # noqa: E402

SCHEMA = Draft202012Validator(
    json.loads((ROOT / "contracts" / "reviewer-eval-case.schema.json")
               .read_text(encoding="utf-8")))

# Reviewer 를 호출하는 판정만 평가 대상이다(ADR-0004).
REVIEWED = {"WRONG_ANSWER", "TIME_LIMIT", "MEMORY_LIMIT", "RUNTIME_ERROR", "OUTPUT_LIMIT"}


def strip_label_comment(source: str) -> str:
    """맨 앞 주석 블록을 지운다.

    오답 파일 첫머리에는 무엇을 틀리게 했는지 적혀 있다(ADR-0007). 그대로 보내면
    **모델은 분석하지 않고 읽기만 하면 된다** - 정확도가 아니라 독해력을 재게 된다.
    """
    lines = source.splitlines(keepends=True)
    i = 0
    while i < len(lines) and (lines[i].lstrip().startswith("#") or not lines[i].strip()):
        i += 1
    return "".join(lines[i:])


def labelled_solutions(d: pathlib.Path, problem: dict) -> list[tuple[str, str, pathlib.Path]]:
    """(label, labelSource, path). 라벨이 선언돼 있고 검증된 것만."""
    found: list[tuple[str, str, pathlib.Path]] = []
    control = (problem.get("negativeControl") or {}).get("mistake")
    if control:
        found.append((control, "negativeControl", d / "wrong.py"))
    probe_dir = d / "probes"
    if probe_dir.is_dir():
        for path in sorted(probe_dir.glob("*.py")):
            found.append((path.stem, "probes", path))
    return found


def build(only: str | None) -> tuple[list[dict], list[str]]:
    mistakes = {m["code"]: m for m in
                yaml.safe_load((ROOT / "curriculum" / "mistakes.yaml")
                               .read_text(encoding="utf-8"))["mistakes"]}
    cases: list[dict] = []
    skipped: list[str] = []

    dirs = sorted(p for p in PROBLEMS.iterdir() if p.is_dir())
    if only:
        dirs = [p for p in dirs if p.name == only]

    for d in dirs:
        problem = yaml.safe_load((d / "problem.yaml").read_text(encoding="utf-8"))
        cases_doc = json.loads((d / "cases.json").read_text(encoding="utf-8"))
        job = V.build_job(problem, cases_doc)

        probes: dict[str, list[int]] = {}
        for c in cases_doc["cases"]:
            for mc in c["probes"]:
                probes.setdefault(mc, []).append(c["id"])

        for label, label_source, path in labelled_solutions(d, problem):
            if mistakes.get(label, {}).get("assigned_by") != "REVIEWER":
                # 시스템이 부여하는 Mistake 는 Reviewer 에게 묻지 않는다. 물어보지
                # 않는 것의 정확도를 재는 것은 의미가 없다.
                skipped.append(f"{d.name}/{path.name}: {label} 은 REVIEWER 가 붙이지 않는다")
                continue

            code_text = strip_label_comment(path.read_text(encoding="utf-8"))
            leaked = sorted(code for code in mistakes if code in code_text)
            if leaked:
                # 라벨이 코드 안에 남아 있으면 모델은 분석하지 않고 읽기만 하면
                # 된다. 정확도가 아니라 독해력을 재게 되므로 만들지 않는다.
                skipped.append(f"{d.name}/{path.name}: 코드에 라벨 {leaked} 이 남아 있다")
                continue

            result = V.judge(path, job)
            if result["status"] not in REVIEWED:
                # 이 판정에서는 Reviewer 를 부르지 않으므로 평가할 것이 없다.
                skipped.append(f"{d.name}/{path.name}: {result['status']} 은 Reviewer 를 부르지 않는다")
                continue

            cases.append({
                "problemCode": problem["code"],
                "problemTitle": problem["title"],
                "problemSource": problem["source"],
                "label": label,
                "labelSource": label_source,
                "skillCodes": [s["code"] for s in problem["skills"]],
                "sourceCode": code_text,
                "judge": {
                    "status": result["status"],
                    "failedCaseId": result["failedCaseId"],
                    "stderr": result["stderr"],
                    "passedCaseIds": [c["id"] for c in result["cases"]
                                      if c["status"] == "ACCEPTED"],
                    "failedCaseIds": [c["id"] for c in result["cases"]
                                      if c["status"] != "ACCEPTED"],
                },
                "probes": probes,
            })
    return cases, skipped


def name_of(case: dict) -> str:
    return f"{case['problemCode']}__{case['label']}.json"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("only", nargs="?", help="특정 문제만")
    parser.add_argument("--write", action="store_true",
                        help="파일을 다시 만든다. 붙이지 않으면 검증만 한다.")
    args = parser.parse_args()

    cases, skipped = build(args.only)
    for line in skipped:
        print(f"[-] 건너뜀 - {line}")

    failed = 0
    for case in cases:
        for err in SCHEMA.iter_errors(case):
            failed += 1
            print(f"[X] {name_of(case)}{list(err.path)}: {err.message}")

    OUT.mkdir(parents=True, exist_ok=True)
    if args.write:
        for stale in OUT.glob("*.json"):
            stale.unlink()
        for case in cases:
            (OUT / name_of(case)).write_text(
                json.dumps(case, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8", newline="")
        print(f"\n[OK] 평가 케이스 {len(cases)}건을 다시 만들었다: "
              f"{OUT.relative_to(ROOT)}")
        return 1 if failed else 0

    # 검증. 지금 파일이 실제 채점과 같은지 본다.
    existing = {p.name for p in OUT.glob("*.json")}
    expected = {name_of(c) for c in cases}
    for missing in sorted(expected - existing):
        failed += 1
        print(f"[X] {missing} 이 없다 - --write 로 다시 만든다")
    for extra in sorted(existing - expected):
        failed += 1
        print(f"[X] {extra} 는 지금 데이터로 만들어지지 않는다 - --write 로 다시 만든다")
    for case in cases:
        path = OUT / name_of(case)
        if not path.exists():
            continue
        if json.loads(path.read_text(encoding="utf-8")) != case:
            failed += 1
            print(f"[X] {name_of(case)} 이 실제 채점 결과와 다르다 - --write 로 다시 만든다")

    if failed:
        print(f"\n[FAIL] 평가 케이스 검증 실패 ({failed}건)")
        return 1
    print(f"\n[OK] 평가 케이스 {len(cases)}건이 실제 채점 결과와 일치한다")
    return 0


if __name__ == "__main__":
    sys.exit(main())
