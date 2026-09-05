#!/usr/bin/env python3
"""제출 -> Evidence 매핑의 golden fixture 를 만들고 검증한다.

    python tools/gen_evidence_golden.py           # 검증 (CI 에서 도는 것)
    python tools/gen_evidence_golden.py --write   # 다시 생성

`gen_mastery_golden.py` 는 **이미 만들어진 Evidence 로부터의 계산**을 고정한다.
그 앞단 - Judge 결과를 Evidence 로 옮기는 매핑(Addendum 11~16) - 은 그동안 Python
에만 있어서 고정할 것이 없었다. 서비스 계층이 붙으면서 같은 매핑이 Java 에도
생겼으므로 여기서 대조한다(ADR-0010).

기본 동작이 "생성" 이 아니라 "검증" 인 이유는 gen_mastery_golden.py 와 같다.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

from jsonschema import Draft202012Validator, RefResolver

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parent.parent
GOLDEN = ROOT / "tests" / "golden" / "evidence"
sys.path.insert(0, str(ROOT))

from learning import evidence as ev  # noqa: E402


def _schema(name: str) -> dict:
    return json.loads((ROOT / "contracts" / name).read_text(encoding="utf-8"))


CASE_SCHEMA = _schema("evidence-golden.schema.json")
SKILL_EVIDENCE = _schema("skill-evidence.schema.json")
VALIDATOR = Draft202012Validator(
    CASE_SCHEMA,
    resolver=RefResolver(base_uri=CASE_SCHEMA["$id"], referrer=CASE_SCHEMA,
                         store={SKILL_EVIDENCE["$id"]: SKILL_EVIDENCE}),
)

SKILL = "BFS_GRID_TRAVERSAL"

# from_submission 의 인자 전부. 케이스는 이 위에 덮어쓴다.
BASE = {
    "sourceEventId": "submission:1",
    "skillCode": SKILL,
    "skillWeight": 1.0,
    "judgeStatus": "ACCEPTED",
    "hintLevel": 0,
    "solutionViewed": False,
    "occurredAt": "2026-09-05T10:00:00Z",
    "evidenceType": "PROBLEM_SUBMISSION",
    "solveSeconds": None,
    "expectedSolveSeconds": None,
    "algorithmSelection": None,
    "mode": "NORMAL",
    "problemCode": "P02_GRID_TRAVERSAL",
    "tleCause": None,
}

# (이름, 설명, 덮어쓸 인자)
# 매핑의 **분기마다** 하나씩 둔다. 하나만 고정하면 다른 분기가 갈려도 알 수 없다.
CASES: list[tuple[str, str, dict]] = [
    ("accepted-no-hint", "힌트 없는 AC. 독립 풀이 0.95 로 가장 높다.", {}),
    ("accepted-hint-1", "힌트 1단계. 구현은 거의 그대로지만 독립 풀이가 떨어진다.",
     {"hintLevel": 1}),
    ("accepted-hint-3", "힌트 3단계.", {"hintLevel": 3}),
    ("accepted-hint-5", "힌트 5단계. 독립 풀이 0.25 이며 '최근 독립 풀이' 로 세지 않는다.",
     {"hintLevel": 5}),
    ("accepted-solution-viewed",
     "정답을 본 뒤의 AC 는 힌트 단계와 무관하게 6단계로 본다(Addendum 11.7).",
     {"hintLevel": 0, "solutionViewed": True}),
    ("accepted-speed-fast", "기대 시간의 1/3 이면 speed 1.00.",
     {"solveSeconds": 100, "expectedSolveSeconds": 300}),
    ("accepted-speed-slow", "기대의 2배를 넘으면 바닥값 0.20.",
     {"solveSeconds": 900, "expectedSolveSeconds": 300}),
    ("accepted-speed-without-expected",
     "기대 시간을 모르면 speed 를 관측하지 않는다. 0 으로 채우지 않는다.",
     {"solveSeconds": 100, "expectedSolveSeconds": None}),
    ("wrong-answer", "WA 는 구현과 독립 풀이를 함께 낮춘다.", {"judgeStatus": "WRONG_ANSWER"}),
    ("output-limit", "출력 초과는 WA 와 같은 관측이다.", {"judgeStatus": "OUTPUT_LIMIT"}),
    ("runtime-error", "런타임 오류는 개념을 낮추지 않는다(Addendum 12).",
     {"judgeStatus": "RUNTIME_ERROR"}),
    ("memory-limit", "메모리 초과도 구현 쪽만 본다.", {"judgeStatus": "MEMORY_LIMIT"}),
    ("tle-complexity", "복잡도가 틀린 TLE 는 알고리즘 선택도 흔들린 것이다.",
     {"judgeStatus": "TIME_LIMIT", "tleCause": "COMPLEXITY"}),
    ("tle-constant-factor", "상수 최적화 문제면 recognition 은 건드리지 않는다.",
     {"judgeStatus": "TIME_LIMIT", "tleCause": "CONSTANT_FACTOR"}),
    ("compile-error", "문법 오류로는 알고리즘 Skill 에 아무것도 기록하지 않는다. "
                      "'관측값이 전부 null 인 Evidence' 가 아니라 Evidence 자체가 없다.",
     {"judgeStatus": "COMPILE_ERROR"}),
    ("system-error", "채점 실패는 우리 잘못이라 사용자 점수를 건드리지 않는다.",
     {"judgeStatus": "SYSTEM_ERROR"}),
    ("recognition-exam-mode", "유형을 숨긴 모드에서만 recognition 을 관측한다.",
     {"algorithmSelection": "CORRECT", "mode": "EXAM"}),
    ("recognition-ignored-in-normal-mode",
     "유형을 알려준 상태의 선택은 관측이 아니다. 같은 인자라도 mode 가 다르면 null 이다.",
     {"algorithmSelection": "CORRECT", "mode": "NORMAL"}),
    ("recognition-wrong-in-exam-mode", "모의고사에서 유형을 잘못 고르면 0.20.",
     {"algorithmSelection": "WRONG", "mode": "MIXED", "judgeStatus": "WRONG_ANSWER"}),
    ("secondary-skill-weight",
     "SECONDARY Skill 은 문제 안에서의 비중이 곱해져 weight 가 작다.",
     {"skillWeight": 0.3}),
    ("micro-drill", "드릴은 confidence 가중치가 절반이다.",
     {"evidenceType": "MICRO_DRILL_RESULT"}),
    ("timezone-offset", "오프셋이 붙은 시각도 그대로 보존한다. 재계산이 실제 시각으로 정렬한다.",
     {"occurredAt": "2026-09-05T19:00:00+09:00"}),
]


def build(name: str, description: str, overrides: dict) -> dict:
    args = dict(BASE)
    args.update(overrides)
    result = ev.from_submission(
        source_event_id=args["sourceEventId"],
        skill_code=args["skillCode"],
        skill_weight=args["skillWeight"],
        judge_status=args["judgeStatus"],
        hint_level=args["hintLevel"],
        solution_viewed=args["solutionViewed"],
        occurred_at=args["occurredAt"],
        evidence_type=args["evidenceType"],
        solve_seconds=args["solveSeconds"],
        expected_solve_seconds=args["expectedSolveSeconds"],
        algorithm_selection=args["algorithmSelection"],
        mode=args["mode"],
        problem_code=args["problemCode"],
        tle_cause=args["tleCause"],
    )
    return {
        "name": name,
        "description": description,
        "input": args,
        "expected": result.to_dict() if result is not None else None,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true", help="golden 을 다시 생성한다")
    args = parser.parse_args()

    GOLDEN.mkdir(parents=True, exist_ok=True)
    failed = 0
    names = set()

    for name, description, overrides in CASES:
        case = build(name, description, overrides)
        errs = [f"{list(e.path)}: {e.message}" for e in VALIDATOR.iter_errors(case)]
        if errs:
            failed += 1
            print(f"[X] {name}: golden 계약 위반 {errs[:2]}")
            continue
        names.add(name)

        path = GOLDEN / f"{name}.json"
        payload = json.dumps(case, ensure_ascii=False, indent=2) + "\n"

        if args.write:
            path.write_text(payload, encoding="utf-8", newline="")
            continue
        if not path.exists():
            failed += 1
            print(f"[X] {name}: golden 파일이 없다 - --write 로 만든다")
            continue
        stored = json.loads(path.read_text(encoding="utf-8"))
        if stored["expected"] != case["expected"]:
            failed += 1
            print(f"[X] {name}: 매핑 결과가 golden 과 다르다")
            print(f"    golden  {json.dumps(stored['expected'], ensure_ascii=False)}")
            print(f"    현재    {json.dumps(case['expected'], ensure_ascii=False)}")
            continue
        if stored["input"] != case["input"]:
            failed += 1
            print(f"[X] {name}: 입력이 golden 과 다르다")
            continue
        print(f"[O] {name}")

    for path in sorted(GOLDEN.glob("*.json")):
        if path.stem not in names:
            failed += 1
            print(f"[X] {path.name}: CASES 에 없는 golden 파일이 남아 있다")

    if args.write:
        print(f"\n[OK] evidence golden {len(names)}건 생성")
        return 0
    if failed:
        print(f"\n[FAIL] {failed}건 - 매핑을 의도적으로 바꿨다면 --write 로 갱신하고,")
        print("       무엇이 왜 달라졌는지 커밋 메시지에 남긴다")
        return 1
    print(f"\n[OK] evidence golden {len(names)}건이 현재 매핑과 일치한다")
    return 0


if __name__ == "__main__":
    sys.exit(main())
