#!/usr/bin/env python3
"""Mastery 단위 테스트.

Addendum 84 가 "반드시 테스트한다" 고 나열한 9항목이 그대로 목록이다.

    신규 Skill null 처리 / 첫 Evidence / 힌트 없는 AC / 힌트 5 AC / WA /
    Review AC / confidence 누적 / Mastered 전환 / Weakened 전환

여기에 세 가지를 더 넣었다.

    계약 준수   계산 결과가 user-skill.schema.json 을 지키는가
    결정론      같은 Evidence 목록이면 항상 같은 결과가 나오는가 (ADR-0009)
    재정규화    평가되지 않은 차원을 0 으로 두지 않는가 (Addendum 6)

    python learning/tests/test_mastery.py
"""
from __future__ import annotations

import json
import pathlib
import random
import sys

from jsonschema import Draft202012Validator

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT))

from learning import evidence as ev  # noqa: E402
from learning import mastery as ms  # noqa: E402

STATE_SCHEMA = Draft202012Validator(
    json.loads((ROOT / "contracts" / "user-skill.schema.json").read_text(encoding="utf-8"))
)
EVIDENCE_SCHEMA = Draft202012Validator(
    json.loads((ROOT / "contracts" / "skill-evidence.schema.json").read_text(encoding="utf-8"))
)

SKILL = "BFS_GRID_TRAVERSAL"
failures: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    if condition:
        print(f"[O] {name}")
    else:
        failures.append(f"{name}: {detail}")
        print(f"[X] {name}  {detail}")


def sub(day: int, status: str, **kw) -> ev.Evidence:
    """제출 Evidence 하나. day 가 곧 발생 순서다."""
    return ev.from_submission(
        source_event_id=kw.pop("event", f"submission:{day}"),
        skill_code=SKILL,
        skill_weight=kw.pop("skill_weight", 1.0),
        judge_status=status,
        hint_level=kw.pop("hint", 0),
        solution_viewed=kw.pop("viewed", False),
        occurred_at=kw.pop("at", f"2026-09-{day:02d}T10:00:00Z"),
        **kw,
    )


def state_of(evidences: list[ev.Evidence]) -> ms.SkillState:
    return ms.recompute(evidences, SKILL)


# -- 1. 신규 Skill 은 0 이 아니라 null ------------------------------------
def test_unassessed() -> None:
    s = state_of([])
    check("신규 Skill 은 mastery 가 null 이다 (0.0 이 아니다)",
          s.mastery is None and s.status == "UNASSESSED",
          f"mastery={s.mastery} status={s.status}")
    check("신규 Skill 의 모든 차원이 null 이다", all(v is None for v in s.scores.values()))
    check("신규 Skill 의 confidence 는 0.0 이다", s.confidence == 0.0)


# -- 2. 첫 Evidence 는 EMA 를 거치지 않는다 -------------------------------
def test_first_evidence() -> None:
    s = state_of([sub(1, "ACCEPTED")])
    check("첫 관측은 EMA 없이 그대로 들어간다",
          s.scores["implementation"] == 0.90 and s.scores["independent"] == 0.95,
          f"{s.scores}")
    check("첫 Evidence 만으로는 confidence 가 낮다",
          s.confidence < ms.CONFIDENCE_THRESHOLD, f"confidence={s.confidence}")
    check("관측하지 못한 차원은 여전히 null 이다",
          s.scores["concept"] is None and s.scores["retention"] is None)

    # 평가된 두 차원만으로 재정규화한다.
    # 0 으로 채우면 0.90*0.25 + 0.95*0.25 = 0.4625 로 부당하게 낮게 나온다.
    expected = round((0.90 * 0.25 + 0.95 * 0.25) / 0.50, 4)
    check("평가된 차원만으로 재정규화한다 (Addendum 6)",
          s.mastery == expected, f"mastery={s.mastery} 기대={expected}")
    check("0 으로 채우는 것과 결과가 다르다", s.mastery > 0.4625, f"{s.mastery}")


# -- 3. 힌트 단계 ----------------------------------------------------------
def test_hint_levels() -> None:
    clean = state_of([sub(1, "ACCEPTED", hint=0)])
    hinted = state_of([sub(1, "ACCEPTED", hint=5)])
    check("힌트 없는 AC 가 힌트 5 AC 보다 독립 풀이 점수가 높다",
          clean.scores["independent"] > hinted.scores["independent"],
          f"{clean.scores['independent']} vs {hinted.scores['independent']}")
    check("힌트 5 AC 의 독립 풀이는 0.25 다 (Addendum 11.6)",
          hinted.scores["independent"] == 0.25, f"{hinted.scores['independent']}")

    viewed = state_of([sub(1, "ACCEPTED", viewed=True)])
    check("정답을 본 뒤의 AC 는 독립 풀이 0.10 이다 (Addendum 11.7)",
          viewed.scores["independent"] == 0.10, f"{viewed.scores['independent']}")

    e4 = sub(1, "ACCEPTED", hint=4)
    e3 = sub(1, "ACCEPTED", hint=3)
    check("힌트 4 이상 AC 는 독립 풀이로 세지 않는다 (Addendum 22)",
          e4.context["independentAttempt"] is False
          and e3.context["independentAttempt"] is True,
          f"hint4={e4.context['independentAttempt']} hint3={e3.context['independentAttempt']}")


# -- 4. 실패 판정 ----------------------------------------------------------
def test_wrong_answer() -> None:
    s = state_of([sub(1, "ACCEPTED"), sub(2, "WRONG_ANSWER")])
    alpha = ev.ALPHA["PROBLEM_SUBMISSION"]
    expected = round(0.90 * (1 - alpha) + 0.30 * alpha, 4)
    check("WA 는 EMA 로 구현 점수를 끌어내린다",
          s.scores["implementation"] == expected,
          f"{s.scores['implementation']} 기대={expected}")
    check("WA 는 개념 점수를 건드리지 않는다", s.scores["concept"] is None)


def test_runtime_error_spares_concept() -> None:
    s = state_of([sub(1, "RUNTIME_ERROR")])
    check("RUNTIME_ERROR 는 구현만 본다 (Addendum 12)",
          s.scores["implementation"] == 0.30
          and s.scores["independent"] is None
          and s.scores["concept"] is None, f"{s.scores}")


def test_tle_distinguishes_cause() -> None:
    complexity = state_of([sub(1, "TIME_LIMIT", tle_cause="COMPLEXITY")])
    constant = state_of([sub(1, "TIME_LIMIT", tle_cause="CONSTANT_FACTOR")])
    check("복잡도가 틀린 TLE 는 알고리즘 선택도 낮춘다 (Addendum 12)",
          complexity.scores["recognition"] == 0.35, f"{complexity.scores['recognition']}")
    check("상수 최적화 TLE 는 알고리즘 선택을 건드리지 않는다",
          constant.scores["recognition"] is None, f"{constant.scores['recognition']}")


def test_compile_error_is_not_evidence() -> None:
    check("COMPILE_ERROR 는 알고리즘 Skill 에 Evidence 를 남기지 않는다 (Addendum 12)",
          sub(1, "COMPILE_ERROR") is None)
    check("SYSTEM_ERROR 도 사용자 Skill 에 반영하지 않는다", sub(1, "SYSTEM_ERROR") is None)


# -- 5. 복습 ---------------------------------------------------------------
def test_review() -> None:
    ok = state_of([
        sub(1, "ACCEPTED"),
        ev.from_review(source_event_id="review:1", skill_code=SKILL, days_since_last=7, succeeded=True,
                       occurred_at="2026-09-08T10:00:00Z"),
    ])
    check("7일 뒤 복습 성공은 retention 0.90 이다 (Addendum 16)",
          ok.scores["retention"] == 0.90, f"{ok.scores['retention']}")

    late = state_of([
        sub(1, "ACCEPTED"),
        ev.from_review(source_event_id="review:late", skill_code=SKILL,
                       days_since_last=30, succeeded=True,
                       occurred_at="2026-10-01T10:00:00Z"),
    ])
    check("더 오래 지나서 성공하면 retention 이 더 높다",
          late.scores["retention"] > ok.scores["retention"],
          f"{late.scores['retention']} vs {ok.scores['retention']}")

    bad = state_of([
        sub(1, "ACCEPTED"),
        ev.from_review(source_event_id="review:fail", skill_code=SKILL,
                       days_since_last=7, succeeded=False,
                       occurred_at="2026-09-08T10:00:00Z"),
    ])
    check("복습 실패는 retention 을 크게 낮춘다",
          bad.scores["retention"] < 0.5, f"{bad.scores['retention']}")


# -- 6. confidence 누적 ----------------------------------------------------
def test_confidence_accumulates() -> None:
    seq: list[ev.Evidence] = []
    prev, monotonic = 0.0, True
    for day in range(1, 12):
        seq.append(sub(day, "ACCEPTED"))
        c = state_of(seq).confidence
        if c < prev:
            monotonic = False
        prev = c
    check("confidence 는 Evidence 가 쌓일수록 오른다", monotonic)
    check("confidence 는 1.0 을 넘지 않는다", prev <= 1.0, f"{prev}")

    normal = state_of([sub(1, "ACCEPTED")]).confidence
    drill = state_of([sub(1, "ACCEPTED", evidence_type="MICRO_DRILL_RESULT")]).confidence
    review = state_of([ev.from_review(source_event_id="review:1", skill_code=SKILL, days_since_last=7, succeeded=True,
                                      occurred_at="2026-09-01T10:00:00Z")]).confidence
    check("드릴은 일반 문제보다 confidence 를 덜 올린다 (Addendum 18)",
          drill < normal, f"{drill} vs {normal}")
    check("복습은 일반 문제보다 confidence 를 더 올린다", review > normal, f"{review} vs {normal}")

    secondary = state_of([sub(1, "ACCEPTED", skill_weight=0.25)]).confidence
    check("SECONDARY Skill 은 confidence 를 덜 올린다", secondary < normal,
          f"{secondary} vs {normal}")


# -- 7. MASTERED 전환 ------------------------------------------------------
def mastered_sequence() -> list[ev.Evidence]:
    """Addendum 22 의 네 조건을 모두 채우는 흐름."""
    items = [sub(d, "ACCEPTED") for d in range(1, 11)]
    items.append(ev.from_concept_check(source_event_id="concept:1", skill_code=SKILL, verdict="CORRECT",
                                       occurred_at="2026-09-11T10:00:00Z"))
    items.append(ev.from_review(source_event_id="review:1", skill_code=SKILL, days_since_last=7, succeeded=True,
                                occurred_at="2026-09-12T10:00:00Z"))
    return items


def test_mastered() -> None:
    s = state_of(mastered_sequence())
    check("네 조건을 채우면 MASTERED 로 간다", s.status == "MASTERED",
          f"status={s.status} mastery={s.mastery} confidence={s.confidence}")

    no_review = [e for e in mastered_sequence() if e.evidence_type != "REVIEW_RESULT"]
    check("복습 성공이 없으면 MASTERED 가 아니다 (Addendum 22)",
          state_of(no_review).status != "MASTERED", f"{state_of(no_review).status}")

    few = [sub(1, "ACCEPTED"),
           ev.from_review(source_event_id="review:1", skill_code=SKILL, days_since_last=7, succeeded=True,
                          occurred_at="2026-09-02T10:00:00Z")]
    s2 = state_of(few)
    check("confidence 가 낮으면 mastery 가 높아도 MASTERED 가 아니다",
          s2.status != "MASTERED", f"status={s2.status} confidence={s2.confidence}")

    viewed = [sub(d, "ACCEPTED", viewed=True) for d in range(1, 11)]
    viewed.append(ev.from_review(source_event_id="review:1", skill_code=SKILL, days_since_last=7, succeeded=True,
                                 occurred_at="2026-09-12T10:00:00Z"))
    check("정답을 보고 맞힌 것만으로는 MASTERED 가 되지 않는다 (PRD 143-4)",
          state_of(viewed).status != "MASTERED", f"{state_of(viewed).status}")


# -- 8. WEAKENED 전환 ------------------------------------------------------
def test_weakened() -> None:
    seq = mastered_sequence() + [sub(13, "WRONG_ANSWER"), sub(14, "WRONG_ANSWER")]
    check("MASTERED 이후 독립 풀이 2회 실패하면 WEAKENED 로 간다 (Addendum 23)",
          state_of(seq).status == "WEAKENED", f"{state_of(seq).status}")

    seq2 = mastered_sequence()
    seq2.append(ev.from_review(source_event_id="review:2", skill_code=SKILL,
                               days_since_last=7, succeeded=False,
                               occurred_at="2026-09-20T10:00:00Z"))
    check("MASTERED 이후 복습에 실패하면 WEAKENED 로 간다",
          state_of(seq2).status == "WEAKENED", f"{state_of(seq2).status}")

    recovering = seq + [sub(15, "ACCEPTED")]
    check("WEAKENED 는 한 번의 성공으로 곧바로 풀리지 않는다",
          state_of(recovering).status == "WEAKENED", f"{state_of(recovering).status}")


# -- 9. 계약 준수 ----------------------------------------------------------
def test_contracts() -> None:
    for label, evs in (("신규", []), ("AC 하나", [sub(1, "ACCEPTED")]),
                       ("MASTERED", mastered_sequence())):
        errs = [f"{list(e.path)}: {e.message}"
                for e in STATE_SCHEMA.iter_errors(state_of(evs).to_dict())]
        check(f"계산 결과가 user-skill.schema.json 을 지킨다 ({label})", not errs, str(errs[:2]))

    samples = [
        sub(1, "ACCEPTED", hint=2, solve_seconds=200, expected_solve_seconds=300,
            problem_code="P03_CONNECTED_COMPONENT"),
        sub(2, "WRONG_ANSWER"),
        ev.from_review(source_event_id="review:1", skill_code=SKILL, days_since_last=7, succeeded=True,
                       occurred_at="2026-09-08T10:00:00Z"),
        ev.from_concept_check(source_event_id="concept:1", skill_code=SKILL, verdict="PARTIAL",
                              occurred_at="2026-09-09T10:00:00Z"),
    ]
    for e in samples:
        errs = [f"{list(x.path)}: {x.message}" for x in EVIDENCE_SCHEMA.iter_errors(e.to_dict())]
        check(f"Evidence 가 계약을 지킨다 ({e.evidence_type})", not errs, str(errs[:2]))


# -- 10. 결정론 (ADR-0009) -------------------------------------------------
def test_deterministic() -> None:
    seq = mastered_sequence()
    first = state_of(seq).to_dict()
    check("같은 Evidence 목록이면 항상 같은 결과가 나온다",
          all(state_of(seq).to_dict() == first for _ in range(5)))

    shuffled = list(seq)
    random.Random(7).shuffle(shuffled)
    check("입력 순서가 섞여도 occurredAt 으로 정렬해 같은 결과를 낸다",
          state_of(shuffled).to_dict() == first, "재계산이 입력 순서에 의존한다")

    other = ev.from_submission(source_event_id="submission:99", skill_code="BFS_BASIC",
                               skill_weight=1.0,
                               judge_status="ACCEPTED", hint_level=0,
                               solution_viewed=False, occurred_at="2026-09-01T10:00:00Z")
    try:
        ms.recompute([other], SKILL)
        check("다른 Skill 의 Evidence 가 섞이면 거부한다", False, "그냥 통과했다")
    except ValueError:
        check("다른 Skill 의 Evidence 가 섞이면 거부한다", True)


# -- 11. speed 는 정답일 때만 -----------------------------------------------
def test_speed_only_on_success() -> None:
    fast = state_of([sub(1, "ACCEPTED", solve_seconds=100, expected_solve_seconds=300)])
    slow = state_of([sub(1, "ACCEPTED", solve_seconds=900, expected_solve_seconds=300)])
    check("빠르게 풀면 speed 가 높다",
          fast.scores["speed"] == 1.00 and slow.scores["speed"] == 0.20,
          f"{fast.scores['speed']} / {slow.scores['speed']}")

    failed = state_of([sub(1, "WRONG_ANSWER", solve_seconds=100, expected_solve_seconds=300)])
    check("정답이 아니면 speed 를 갱신하지 않는다 (Addendum 13)",
          failed.scores["speed"] is None, f"{failed.scores['speed']}")


# -- 12. recognition 은 유형을 모르는 모드에서만 ----------------------------
def test_recognition_mode_gated() -> None:
    guided = state_of([sub(1, "ACCEPTED", algorithm_selection="CORRECT", mode="GUIDED")])
    exam = state_of([sub(1, "ACCEPTED", algorithm_selection="CORRECT", mode="EXAM")])
    check("유형을 알려준 모드에서는 recognition 을 관측하지 않는다 (Addendum 15)",
          guided.scores["recognition"] is None, f"{guided.scores['recognition']}")
    check("유형을 숨긴 모드에서만 recognition 을 관측한다",
          exam.scores["recognition"] == 0.90, f"{exam.scores['recognition']}")


# -- 13. 독립 풀이 판정은 성공/실패에 같은 기준 (BLOCKER 였던 버그) ---------
def test_independent_attempt_applies_to_failures() -> None:
    cases = [
        ("H0 + AC", sub(1, "ACCEPTED", hint=0), True),
        ("H3 + AC", sub(1, "ACCEPTED", hint=3), True),
        ("H4 + AC", sub(1, "ACCEPTED", hint=4), False),
        ("H0 + WA", sub(1, "WRONG_ANSWER", hint=0), True),
        ("H3 + WA", sub(1, "WRONG_ANSWER", hint=3), True),
        # 힌트를 다 보고 틀린 것은 독립 풀이에 실패한 것이 아니라
        # 애초에 독립 풀이가 아니다.
        ("H4 + WA", sub(1, "WRONG_ANSWER", hint=4), False),
        ("H5 + WA", sub(1, "WRONG_ANSWER", hint=5), False),
        ("정답 보고 + WA", sub(1, "WRONG_ANSWER", viewed=True), False),
        ("정답 보고 + TLE", sub(1, "TIME_LIMIT", viewed=True), False),
        ("H5 + RE", sub(1, "RUNTIME_ERROR", hint=5), False),
    ]
    for label, e, expected in cases:
        check(f"독립 풀이 판정: {label} -> {expected}",
              e.context["independentAttempt"] is expected,
              f"{e.context['independentAttempt']}")


def test_hinted_failures_do_not_weaken() -> None:
    """MASTERED 인 사용자가 힌트를 다 보고 틀렸다고 WEAKENED 가 되면 안 된다.

    독립 풀이를 시도한 적이 없기 때문이다. 이 값은 Decision Engine 의 입력이므로
    틀리면 잘못된 다음 문제를 추천하게 된다.
    """
    base = mastered_sequence()
    check("사전 조건: MASTERED 다", state_of(base).status == "MASTERED",
          f"{state_of(base).status}")

    hinted = base + [sub(13, "WRONG_ANSWER", hint=5), sub(14, "WRONG_ANSWER", viewed=True)]
    check("힌트를 다 보고 두 번 틀려도 WEAKENED 가 아니다",
          state_of(hinted).status == "MASTERED", f"{state_of(hinted).status}")

    independent = base + [sub(13, "WRONG_ANSWER", hint=0), sub(14, "WRONG_ANSWER", hint=1)]
    check("힌트 없이 두 번 틀리면 WEAKENED 가 맞다",
          state_of(independent).status == "WEAKENED", f"{state_of(independent).status}")


# -- 14. 멱등성 - 같은 원천 이벤트는 한 번만 센다 --------------------------
def test_idempotent_on_duplicate_source_event() -> None:
    """Judge Worker 재시도로 같은 제출이 두 번 처리될 수 있다(ADR-0009)."""
    once = [sub(1, "ACCEPTED"), sub(2, "WRONG_ANSWER")]
    twice = once + [sub(1, "ACCEPTED"), sub(2, "WRONG_ANSWER")]
    check("같은 원천 이벤트가 두 번 들어와도 결과가 같다",
          state_of(twice).to_dict() == state_of(once).to_dict(),
          f"{state_of(twice).evidence_count} vs {state_of(once).evidence_count}")
    check("중복은 evidenceCount 에도 세지 않는다",
          state_of(twice).evidence_count == 2, f"{state_of(twice).evidence_count}")

    # 원천이 다르면 중복이 아니다.
    distinct = once + [sub(3, "ACCEPTED", event="submission:3")]
    check("다른 원천 이벤트는 그대로 센다",
          state_of(distinct).evidence_count == 3, f"{state_of(distinct).evidence_count}")

    # 한 제출이 여러 Skill 에 Evidence 를 남기는 경우 - sourceEventId 는 같고
    # skillCode 가 다르므로 서로 다른 Evidence 다.
    a = sub(1, "ACCEPTED")
    b = ev.from_submission(source_event_id="submission:1", skill_code="BFS_BASIC",
                           skill_weight=0.3, judge_status="ACCEPTED", hint_level=0,
                           solution_viewed=False, occurred_at="2026-09-01T10:00:00Z")
    check("같은 제출이라도 Skill 이 다르면 다른 Evidence 다",
          a.dedupe_key != b.dedupe_key and a.evidence_id != b.evidence_id)

    check("evidenceId 는 같은 원천에서 항상 같다",
          sub(1, "ACCEPTED").evidence_id == sub(1, "ACCEPTED").evidence_id)


# -- 15. 같은 원천, 다른 내용 -> 충돌 (BLOCKER 였던 구멍) -----------------
def test_conflicting_evidence_is_rejected() -> None:
    """중복 제거가 "조용히 하나를 버리는" 방식이면 결정론이 깨진다.

    같은 원천에서 서로 다른 관측이 나왔다면 재시도가 아니라 데이터가 깨진 것이다.
    하나를 골라 버리면 어느 것이 남는지가 입력 순서에 달린다 - 실제로 mastery 가
    0.925 와 0.275 로 갈렸다.
    """
    a = sub(1, "ACCEPTED", event="submission:100")
    b = sub(1, "WRONG_ANSWER", event="submission:100")
    check("같은 원천에서 나온 두 Evidence 는 evidenceId 가 같다",
          a.evidence_id == b.evidence_id)

    for order, label in (([a, b], "[a, b]"), ([b, a], "[b, a]")):
        try:
            state_of(order)
            check(f"내용이 다른 중복은 거부한다 {label}", False, "그냥 통과했다")
        except ValueError:
            check(f"내용이 다른 중복은 거부한다 {label}", True)

    # 내용이 같으면 정상적인 재시도다.
    same = sub(1, "ACCEPTED", event="submission:100")
    try:
        s = state_of([a, same])
        check("내용이 같은 중복은 재시도로 보고 통과시킨다", s.evidence_count == 1,
              f"evidenceCount={s.evidence_count}")
    except ValueError as e:
        check("내용이 같은 중복은 재시도로 보고 통과시킨다", False, str(e))


# -- 16. 알 수 없는 judge status 는 상류에서 막는다 ------------------------
def test_unknown_judge_status_is_rejected() -> None:
    """오타 난 status 가 통과하면 관측값 없는 Evidence 가 만들어지고,
    측정한 것 없이 confidence 와 evidenceCount 만 오른다."""
    for bad in ("WRONGANSWER", "AC", "", "accepted"):
        try:
            sub(1, bad)
            check(f"알 수 없는 status 를 거부한다 ({bad!r})", False, "Evidence 가 만들어졌다")
        except ValueError:
            check(f"알 수 없는 status 를 거부한다 ({bad!r})", True)

    # 계약과 목록이 갈라지면 Judge 가 내는 값을 여기서 못 알아본다.
    judge_enum = set(
        json.loads((ROOT / "contracts" / "judge-result.schema.json")
                   .read_text(encoding="utf-8"))["properties"]["status"]["enum"]
    )
    check("아는 status 목록이 judge-result.schema.json 과 같다",
          ev.KNOWN_JUDGE_STATUSES == judge_enum,
          f"차이={sorted(judge_enum ^ ev.KNOWN_JUDGE_STATUSES)}")

    check("알 수 없는 개념 확인 결과도 거부한다",
          _raises(lambda: ev.from_concept_check(source_event_id="c:9", skill_code=SKILL,
                                                verdict="MAYBE", occurred_at="2026-09-01T10:00:00Z")))


def test_non_positive_solve_seconds_is_rejected() -> None:
    """speed 는 비율로 매기므로 음수 시간이 오히려 최고 점수를 받는다.

    실제로 확인했다 - solve_seconds=-100, expected=300 이면 speed 가 1.0 이었다.
    Evidence 는 append-only 정본이라 그렇게 저장되면 지울 수 없다.
    """
    for bad in (-100, -1, 0):
        check(f"solveSeconds {bad} 를 거부한다",
              _raises(lambda b=bad: sub(1, "ACCEPTED", solve_seconds=b,
                                        expected_solve_seconds=300)))

    check("None 은 허용한다 (재지 않았다)",
          sub(1, "ACCEPTED", solve_seconds=None).observed["speed"] is None)
    check("양수는 그대로 관측한다",
          sub(1, "ACCEPTED", solve_seconds=100,
              expected_solve_seconds=300).observed["speed"] == 1.00)


def _raises(fn) -> bool:
    try:
        fn()
        return False
    except ValueError:
        return True


# -- 17. 시간대까지 반영한 정렬 -------------------------------------------
def test_orders_by_actual_instant_not_string() -> None:
    """ISO-8601 문자열의 사전순 정렬은 실제 시간순과 다르다.

        A = 2026-09-05T10:00:00+09:00   실제 UTC 01:00
        B = 2026-09-05T01:30:00Z        실제 UTC 01:30

    실제 순서는 A -> B 인데 문자열로 정렬하면 B -> A 가 된다.
    EMA 는 순서 계산이라 나중 일이 먼저 접히면 값이 달라진다.
    """
    a = sub(5, "ACCEPTED", at="2026-09-05T10:00:00+09:00", event="submission:A")
    b = sub(5, "WRONG_ANSWER", at="2026-09-05T01:30:00Z", event="submission:B")
    check("문자열로 정렬하면 순서가 뒤집힌다 (전제 확인)",
          sorted([a.occurred_at, b.occurred_at])[0] == b.occurred_at)

    # 같은 두 사건을 같은 시간대로 쓴 것과 결과가 같아야 한다.
    a_utc = sub(5, "ACCEPTED", at="2026-09-05T01:00:00Z", event="submission:A")
    b_utc = sub(5, "WRONG_ANSWER", at="2026-09-05T01:30:00Z", event="submission:B")
    expected = state_of([a_utc, b_utc]).mastery

    for order, label in (([a, b], "[a, b]"), ([b, a], "[b, a]")):
        check(f"시간대가 섞여도 실제 시각 순으로 접는다 {label}",
              state_of(order).mastery == expected,
              f"{state_of(order).mastery} 기대={expected}")


def test_naive_timestamp_is_rejected() -> None:
    """시간대가 없으면 어느 지역 시간인지 알 수 없다."""
    for bad in ("2026-09-05T10:00:00", "2026-09-05", "어제", ""):
        check(f"시간대 없는 occurredAt 을 거부한다 ({bad!r})",
              _raises(lambda b=bad: sub(1, "ACCEPTED", at=b)))

    check("복습 Evidence 도 같은 검사를 받는다",
          _raises(lambda: ev.from_review(source_event_id="r:9", skill_code=SKILL,
                                         days_since_last=7, succeeded=True,
                                         occurred_at="2026-09-05T10:00:00")))


def main() -> int:
    for fn in (
        test_unassessed, test_first_evidence, test_hint_levels, test_wrong_answer,
        test_runtime_error_spares_concept, test_tle_distinguishes_cause,
        test_compile_error_is_not_evidence, test_review, test_confidence_accumulates,
        test_mastered, test_weakened, test_contracts, test_deterministic,
        test_speed_only_on_success, test_recognition_mode_gated,
        test_independent_attempt_applies_to_failures, test_hinted_failures_do_not_weaken,
        test_idempotent_on_duplicate_source_event,
        test_conflicting_evidence_is_rejected, test_unknown_judge_status_is_rejected,
        test_non_positive_solve_seconds_is_rejected,
        test_orders_by_actual_instant_not_string, test_naive_timestamp_is_rejected,
    ):
        print(f"\n== {fn.__name__} ==")
        fn()

    if failures:
        print(f"\n[FAIL] {len(failures)}건 실패")
        for f in failures:
            print("  - " + f)
        return 1
    print("\n[OK] Mastery 단위 테스트 전부 통과")
    return 0


if __name__ == "__main__":
    sys.exit(main())
