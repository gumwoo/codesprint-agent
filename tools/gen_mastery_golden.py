#!/usr/bin/env python3
"""Mastery golden fixture 를 만들고 검증한다.

    python tools/gen_mastery_golden.py           # 검증 (CI 에서 도는 것)
    python tools/gen_mastery_golden.py --write   # 다시 생성

이 파일들이 Python oracle 과 Java production 구현을 잇는 **유일한 계약**이다
(ADR-0010). 두 구현이 같은 파일을 읽고 같은 값을 내야 한다.

기본 동작이 "생성" 이 아니라 "검증" 인 이유는, 산식을 고치면 golden 이 조용히 따라
바뀌어서는 안 되기 때문이다. 값이 달라지면 CI 가 멈추고, 사람이 그 변화가 의도한
것인지 보고 --write 로 갱신한다. 그러지 않으면 golden 은 "현재 구현이 내는 값" 을
받아적기만 하는 파일이 되어 아무것도 고정하지 못한다.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

from jsonschema import Draft202012Validator

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parent.parent
GOLDEN = ROOT / "tests" / "golden"
sys.path.insert(0, str(ROOT))

from learning import evidence as ev  # noqa: E402
from learning import mastery as ms  # noqa: E402

SCHEMA = Draft202012Validator(
    json.loads((ROOT / "contracts" / "mastery-golden.schema.json").read_text(encoding="utf-8"))
)
SKILL = "BFS_GRID_TRAVERSAL"


def sub(day: int, status: str, **kw) -> ev.Evidence:
    return ev.from_submission(
        source_event_id=kw.pop("event", f"submission:{day}"),
        skill_code=kw.pop("skill", SKILL),
        skill_weight=kw.pop("skill_weight", 1.0),
        judge_status=status,
        hint_level=kw.pop("hint", 0),
        solution_viewed=kw.pop("viewed", False),
        occurred_at=kw.pop("at", f"2026-09-{day:02d}T10:00:00Z"),
        **kw,
    )


def review(day: int, days_since: int, ok: bool, event: str = "review:1") -> ev.Evidence:
    return ev.from_review(source_event_id=event, skill_code=SKILL,
                          days_since_last=days_since, succeeded=ok,
                          occurred_at=f"2026-09-{day:02d}T10:00:00Z")


def concept(day: int, verdict: str) -> ev.Evidence:
    return ev.from_concept_check(source_event_id=f"concept:{day}", skill_code=SKILL,
                                 verdict=verdict, occurred_at=f"2026-09-{day:02d}T10:00:00Z")


def mastered() -> list[ev.Evidence]:
    return ([sub(d, "ACCEPTED") for d in range(1, 11)]
            + [concept(11, "CORRECT"), review(12, 7, True)])


# (이름, 설명, Evidence 목록)
# 여기 있는 케이스가 두 구현을 묶는 계약이다. 산식의 **분기마다 하나씩** 둔다 -
# 값 하나만 고정하면 다른 분기가 갈려도 알 수 없다.
CASES: list[tuple[str, str, list]] = [
    ("unassessed",
     "Evidence 가 없으면 mastery 는 null 이다. 0.0(평가했고 못함)과 구분한다.",
     []),

    ("single-accepted",
     "첫 관측은 EMA 없이 들어가고, 평가된 두 차원만으로 재정규화한다. "
     "0 으로 채우면 0.4625 가 나오지만 정답은 0.925 다.",
     [sub(1, "ACCEPTED")]),

    ("accepted-then-wrong",
     "WA 가 EMA 로 구현 점수를 끌어내린다. 개념 점수는 건드리지 않는다.",
     [sub(1, "ACCEPTED"), sub(2, "WRONG_ANSWER")]),

    ("hint-five-accepted",
     "힌트를 5단계까지 쓴 AC. 구현은 인정하되 독립 풀이는 0.25 다.",
     [sub(1, "ACCEPTED", hint=5)]),

    ("solution-viewed",
     "정답을 본 뒤의 AC. 독립 풀이 0.10 이며 Mastered 근거로 쓸 수 없다.",
     [sub(1, "ACCEPTED", viewed=True)]),

    ("speed-fast-and-slow",
     "정답일 때만 speed 를 관측한다. 기대 시간의 1/3 이면 1.00.",
     [sub(1, "ACCEPTED", solve_seconds=100, expected_solve_seconds=300)]),

    ("tle-complexity",
     "복잡도가 틀린 TLE 는 알고리즘 선택(recognition)도 낮춘다. "
     "상수 최적화 문제였다면 recognition 은 건드리지 않는다.",
     [sub(1, "TIME_LIMIT", tle_cause="COMPLEXITY")]),

    ("recognition-exam-mode",
     "유형을 숨긴 모드에서만 recognition 을 관측한다. GUIDED 였다면 null 이다.",
     [sub(1, "ACCEPTED", algorithm_selection="CORRECT", mode="EXAM")]),

    ("review-retention",
     "7일 뒤 복습 성공은 retention 0.90.",
     [sub(1, "ACCEPTED"), review(8, 7, True)]),

    ("mastered",
     "Addendum 22 의 네 조건을 모두 채운 상태. mastery/confidence 문턱, "
     "최근 독립 풀이 3개 중 2개 이상 성공, 복습 1회 성공.",
     mastered()),

    ("weakened-by-independent-failures",
     "MASTERED 이후 힌트 없이 두 번 실패하면 WEAKENED.",
     mastered() + [sub(13, "WRONG_ANSWER"), sub(14, "WRONG_ANSWER", hint=1)]),

    ("mastered-survives-hinted-failures",
     "MASTERED 이후 힌트를 다 보고 틀린 것은 독립 풀이 실패가 아니다. "
     "Addendum 23 은 MASTERED 에서 나가는 길을 열거하므로 강등하지 않는다.",
     mastered() + [sub(13, "WRONG_ANSWER", hint=5), sub(14, "WRONG_ANSWER", viewed=True)]),

    ("weakened-by-review-failure",
     "MASTERED 이후 복습에 실패하면 WEAKENED.",
     mastered() + [review(20, 7, False, event="review:2")]),

    ("secondary-skill-weight",
     "SECONDARY Skill 로 참여한 문제는 confidence 를 덜 올린다. "
     "그 문제가 그 Skill 을 덜 봤기 때문이다.",
     [sub(1, "ACCEPTED", skill_weight=0.25)]),

    ("micro-drill-weight",
     "드릴은 좁은 범위만 보므로 alpha 와 confidence 가중치가 모두 작다.",
     [sub(1, "ACCEPTED", evidence_type="MICRO_DRILL_RESULT")]),

    ("timezone-mixed-order",
     "입력 순서와 시간대가 섞여 있다. 문자열이 아니라 실제 시각으로 정렬해야 "
     "accepted-then-wrong 과 같은 값이 나온다.",
     [sub(5, "WRONG_ANSWER", at="2026-09-05T01:30:00Z", event="submission:B"),
      sub(5, "ACCEPTED", at="2026-09-05T10:00:00+09:00", event="submission:A")]),

    ("duplicate-retry",
     "같은 원천 이벤트가 두 번 들어와도 한 번만 센다. Worker 재시도가 점수를 "
     "바꾸면 안 된다.",
     [sub(1, "ACCEPTED"), sub(2, "WRONG_ANSWER"),
      sub(1, "ACCEPTED"), sub(2, "WRONG_ANSWER")]),
]


def build(name: str, description: str, evidences: list) -> dict:
    return {
        "name": name,
        "description": description,
        "skillCode": SKILL,
        "evidences": [e.to_dict() for e in evidences],
        "expected": ms.recompute(evidences, SKILL).to_dict(),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true", help="golden 을 다시 생성한다")
    args = parser.parse_args()

    GOLDEN.mkdir(parents=True, exist_ok=True)
    failed = 0
    written = 0
    names = set()

    for name, description, evidences in CASES:
        case = build(name, description, evidences)
        errs = [f"{list(e.path)}: {e.message}" for e in SCHEMA.iter_errors(case)]
        if errs:
            failed += 1
            print(f"[X] {name}: golden 계약 위반 {errs[:2]}")
            continue
        names.add(name)

        path = GOLDEN / f"{name}.json"
        payload = json.dumps(case, ensure_ascii=False, indent=2) + "\n"

        if args.write:
            path.write_text(payload, encoding="utf-8", newline="")
            written += 1
            continue

        if not path.exists():
            failed += 1
            print(f"[X] {name}: golden 파일이 없다 - --write 로 만든다")
            continue
        stored = json.loads(path.read_text(encoding="utf-8"))
        if stored["expected"] != case["expected"]:
            failed += 1
            print(f"[X] {name}: 산식 결과가 golden 과 다르다")
            print(f"    golden  {json.dumps(stored['expected'], ensure_ascii=False)}")
            print(f"    현재    {json.dumps(case['expected'], ensure_ascii=False)}")
            continue
        if stored["evidences"] != case["evidences"]:
            failed += 1
            print(f"[X] {name}: 입력 Evidence 가 golden 과 다르다")
            continue
        print(f"[O] {name}")

    # golden 디렉터리에 케이스로 만들어지지 않는 파일이 남아 있으면 Java 쪽이
    # 낡은 기대값을 검증하게 된다.
    for path in sorted(GOLDEN.glob("*.json")):
        if path.stem not in names:
            failed += 1
            print(f"[X] {path.name}: CASES 에 없는 golden 파일이 남아 있다")

    if args.write:
        print(f"\n[OK] golden {written}건 생성")
        return 0
    if failed:
        print(f"\n[FAIL] {failed}건 - 산식을 의도적으로 바꿨다면 --write 로 갱신하고,")
        print("       무엇이 왜 달라졌는지 커밋 메시지에 남긴다")
        return 1
    print(f"\n[OK] golden {len(names)}건이 현재 산식과 일치한다")
    return 0


if __name__ == "__main__":
    sys.exit(main())
