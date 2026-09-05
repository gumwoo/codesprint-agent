#!/usr/bin/env python3
"""관측을 Evidence 로 옮긴다. Addendum PART I 의 11~16 을 코드로 옮긴 것이다.

여기 있는 표는 전부 **정본 문서에서 온 상수**다. 임의로 고르지 않았고, 고칠 때는
Addendum 을 함께 고친다. 값 하나가 mastery 를 통해 학습 경로를 바꾸므로
"대충 이 정도" 로 정하면 안 된다.

이 모듈은 LLM 을 부르지 않는다. Judge 결과와 학습 이벤트만 본다(ADR-0001).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Literal

DIMENSIONS = ("concept", "recognition", "implementation", "independent", "retention", "speed")

# Addendum 9. EMA 의 alpha. 진단과 모의고사가 큰 이유는 유형을 알려주지 않은 상태의
# 관측이라 정보량이 크기 때문이다. 드릴이 작은 이유는 좁은 범위만 보기 때문이다.
ALPHA = {
    "DIAGNOSTIC_RESULT": 0.25,
    "PROBLEM_SUBMISSION": 0.18,
    "MICRO_DRILL_RESULT": 0.12,
    "REVIEW_RESULT": 0.22,
    "CONCEPT_CHECK": 0.18,
    "EXPLAIN_BACK": 0.18,
    "MOCK_TEST_RESULT": 0.28,
}

# Addendum 18. confidence 누적 가중치. 복습과 모의고사가 큰 이유는
# "시간이 지난 뒤에도 되는가" 와 "유형을 모르는 상태에서도 되는가" 를 보기 때문이다.
EVIDENCE_WEIGHT = {
    "DIAGNOSTIC_RESULT": 1.0,
    "PROBLEM_SUBMISSION": 1.0,
    "MICRO_DRILL_RESULT": 0.5,
    "REVIEW_RESULT": 1.3,
    "CONCEPT_CHECK": 0.5,
    "EXPLAIN_BACK": 0.5,
    "MOCK_TEST_RESULT": 1.5,
}

# Addendum 11. 힌트 단계별 AC 의 관측값 (implementation, independent).
# 같은 AC 라도 힌트를 몇 단계까지 썼는지에 따라 독립 풀이 점수가 크게 갈린다.
# 6 은 "전체 풀이를 본 뒤 AC" 이며 Mastered 판정 근거로 쓸 수 없다(11.7).
AC_BY_HINT = {
    0: (0.90, 0.95),
    1: (0.88, 0.78),
    2: (0.84, 0.68),
    3: (0.80, 0.55),
    4: (0.74, 0.42),
    5: (0.65, 0.25),
    6: (0.55, 0.10),
}

# Addendum 22. 힌트를 이만큼 이상 쓴 AC 는 "최근 독립 풀이" 로 세지 않는다.
INDEPENDENT_HINT_CEILING = 4

# Addendum 13. 풀이 시간 비율 -> speed 관측값. 정답이 아니면 갱신하지 않는다.
SPEED_BY_RATIO = ((0.70, 1.00), (1.00, 0.90), (1.25, 0.75), (1.50, 0.60), (2.00, 0.40))
SPEED_FLOOR = 0.20

# Addendum 14. Concept 은 제출만으로 강하게 갱신하지 않는다.
CONCEPT_BY_VERDICT = {"CORRECT": 0.90, "PARTIAL": 0.60, "INCORRECT": 0.20}

# Addendum 15. Recognition 은 유형을 미리 알려주지 않은 상태의 선택에서만 관측한다.
RECOGNITION_BY_VERDICT = {"CORRECT": 0.90, "PARTIAL": 0.55, "WRONG": 0.20}
RECOGNITION_MODES = {"EXAM", "MIXED", "DIAGNOSTIC"}

# Addendum 16. 복습 간격별 성공 시 retention 관측값.
RETENTION_BY_DAYS = ((1, 0.75), (3, 0.82), (7, 0.90), (14, 0.95), (30, 1.00))
RETENTION_FAIL = 0.35  # 25~45 구간의 중앙


def _empty() -> dict[str, float | None]:
    return {d: None for d in DIMENSIONS}


# context 는 모든 키가 required 이고 nullable 이다. 해당 없는 항목을 생략하지 않고
# null 로 명시한다 - "기록하지 않았다" 와 "해당 없다" 를 구분하기 위해서다.
CONTEXT_KEYS = ("problemCode", "judgeStatus", "hintLevel", "solutionViewed",
                "independentAttempt", "reviewSucceeded", "daysSinceLast", "verdict")


def _context(**kw) -> dict:
    return {k: kw.get(k) for k in CONTEXT_KEYS}


@dataclass(frozen=True)
class Evidence:
    """contracts/skill-evidence.schema.json 과 같은 모양."""

    skill_code: str
    evidence_type: str
    occurred_at: str
    weight: float
    observed: dict[str, float | None]
    source_confidence: float | None = None
    context: dict = field(default_factory=dict)

    def to_dict(self) -> dict:
        return {
            "skillCode": self.skill_code,
            "evidenceType": self.evidence_type,
            "occurredAt": self.occurred_at,
            "weight": self.weight,
            "observed": dict(self.observed),
            "sourceConfidence": self.source_confidence,
            "context": dict(self.context),
        }


def speed_score(actual_seconds: float, expected_seconds: float) -> float:
    """Addendum 13. 느릴수록 낮다."""
    if expected_seconds <= 0:
        return SPEED_FLOOR
    ratio = actual_seconds / expected_seconds
    for bound, value in SPEED_BY_RATIO:
        if ratio <= bound:
            return value
    return SPEED_FLOOR


def retention_score(days_since_last: int, succeeded: bool) -> float:
    """Addendum 16. 오래 지나서도 성공하면 더 높다."""
    if not succeeded:
        return RETENTION_FAIL
    value = RETENTION_BY_DAYS[0][1]
    for days, score in RETENTION_BY_DAYS:
        if days_since_last >= days:
            value = score
    return value


def from_submission(
    *,
    skill_code: str,
    skill_weight: float,
    judge_status: str,
    hint_level: int,
    solution_viewed: bool,
    occurred_at: str,
    evidence_type: str = "PROBLEM_SUBMISSION",
    solve_seconds: float | None = None,
    expected_solve_seconds: int | None = None,
    algorithm_selection: str | None = None,
    mode: str = "NORMAL",
    problem_code: str | None = None,
    tle_cause: Literal["COMPLEXITY", "CONSTANT_FACTOR"] | None = None,
) -> Evidence | None:
    """제출 하나를 Evidence 로 옮긴다. 관측하지 못한 차원은 null 로 둔다.

    돌려주는 값이 None 이면 **이 Skill 에는 아무것도 기록하지 않는다.**
    COMPILE_ERROR 가 그렇다 - 문법 오류를 알고리즘 Skill 의 penalty 로 쓰면 안 된다
    (Addendum 12). 별도 Language Skill 로 따로 기록한다.
    """
    observed = _empty()

    if judge_status in ("COMPILE_ERROR", "SYSTEM_ERROR"):
        # SYSTEM_ERROR 는 우리 잘못이라 사용자의 Skill 에 반영하지 않는다.
        return None

    if judge_status == "ACCEPTED":
        level = 6 if solution_viewed else min(max(hint_level, 0), 5)
        impl, indep = AC_BY_HINT[level]
        observed["implementation"] = impl
        observed["independent"] = indep
        # 정답일 때만 speed 를 갱신한다(Addendum 13).
        if solve_seconds is not None and expected_solve_seconds:
            observed["speed"] = speed_score(solve_seconds, expected_solve_seconds)

    elif judge_status == "TIME_LIMIT":
        # Addendum 12. 복잡도 자체가 틀렸으면 알고리즘 선택도 흔들린 것이고,
        # 상수 최적화 문제면 구현만 본다.
        observed["implementation"] = 0.45
        if tle_cause == "COMPLEXITY":
            observed["recognition"] = 0.35

    elif judge_status in ("WRONG_ANSWER", "OUTPUT_LIMIT"):
        observed["implementation"] = 0.30
        observed["independent"] = 0.25

    elif judge_status in ("RUNTIME_ERROR", "MEMORY_LIMIT"):
        # Addendum 12. 개념 이해도를 바로 낮추지 않는다. 구현 쪽만 본다.
        observed["implementation"] = 0.30

    # Recognition 은 유형을 미리 알려주지 않은 모드에서만 관측한다(Addendum 15).
    if algorithm_selection and mode in RECOGNITION_MODES:
        observed["recognition"] = RECOGNITION_BY_VERDICT.get(algorithm_selection)

    return Evidence(
        skill_code=skill_code,
        evidence_type=evidence_type,
        occurred_at=occurred_at,
        # 문제 안에서의 Skill 비중이 곱해진다. SECONDARY Skill 은 덜 확신하게 된다.
        weight=EVIDENCE_WEIGHT[evidence_type] * skill_weight,
        observed=observed,
        source_confidence=1.0,  # Judge 결과는 결정론적이다
        context=_context(
            problemCode=problem_code,
            judgeStatus=judge_status,
            hintLevel=hint_level,
            solutionViewed=solution_viewed,
            # 힌트를 많이 쓴 AC 는 독립 풀이로 세지 않는다(Addendum 22).
            independentAttempt=(
                judge_status == "ACCEPTED"
                and not solution_viewed
                and hint_level < INDEPENDENT_HINT_CEILING
            )
            or judge_status in ("WRONG_ANSWER", "RUNTIME_ERROR", "TIME_LIMIT",
                                "MEMORY_LIMIT", "OUTPUT_LIMIT"),
        ),
    )


def from_concept_check(*, skill_code: str, verdict: str, occurred_at: str) -> Evidence:
    """Addendum 14. 개념을 설명할 수 있는가."""
    observed = _empty()
    observed["concept"] = CONCEPT_BY_VERDICT[verdict]
    return Evidence(
        skill_code=skill_code,
        evidence_type="CONCEPT_CHECK",
        occurred_at=occurred_at,
        weight=EVIDENCE_WEIGHT["CONCEPT_CHECK"],
        observed=observed,
        source_confidence=None,
        context=_context(verdict=verdict),
    )


def from_review(
    *, skill_code: str, days_since_last: int, succeeded: bool, occurred_at: str
) -> Evidence:
    """Addendum 16. 며칠 뒤에도 되는가."""
    observed = _empty()
    observed["retention"] = retention_score(days_since_last, succeeded)
    if succeeded:
        observed["independent"] = 0.85
    else:
        observed["independent"] = 0.30
    return Evidence(
        skill_code=skill_code,
        evidence_type="REVIEW_RESULT",
        occurred_at=occurred_at,
        weight=EVIDENCE_WEIGHT["REVIEW_RESULT"],
        observed=observed,
        source_confidence=1.0,
        context=_context(daysSinceLast=days_since_last, reviewSucceeded=succeeded,
                         independentAttempt=True),
    )
