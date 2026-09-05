#!/usr/bin/env python3
"""mastery / confidence / status 를 계산한다. Addendum PART I 을 코드로 옮긴 것이다.

**LLM 은 여기 관여하지 않는다**(ADR-0001). 입력은 Evidence 목록뿐이고, 같은 입력에는
같은 출력이 나온다. 모델을 바꾸거나 프롬프트를 고쳐도 이 숫자는 흔들리지 않는다.

mastery 는 저장된 값이 아니라 **Evidence 로부터 재계산되는 값**이다
(ADR-0009). 그래서 이 모듈의 함수는 전부 순수 함수이며 상태를 갖지 않는다.

    from learning.mastery import recompute
    state = recompute(evidences)
"""
from __future__ import annotations

from dataclasses import dataclass

from .evidence import ALPHA, DIMENSIONS, Evidence

# Addendum 6. 차원별 가중치.
# independent 와 implementation 이 가장 큰 이유는 제품의 목표가 "설명할 수 있다" 가
# 아니라 "혼자 풀 수 있다" 이기 때문이다(PRD 163).
WEIGHTS = {
    "concept": 0.15,
    "recognition": 0.20,
    "implementation": 0.25,
    "independent": 0.25,
    "retention": 0.10,
    "speed": 0.05,
}

# Addendum 18. MVP 는 단순식을 쓴다. 지수식(1 - exp(-W/5))도 문서에 있지만
# 값의 해석이 어렵고 MVP 에서 얻는 것이 없다.
CONFIDENCE_DIVISOR = 8.0

# Addendum 22. MASTERED 판정 문턱.
MASTERY_THRESHOLD = 0.80
CONFIDENCE_THRESHOLD = 0.60
RECENT_INDEPENDENT_WINDOW = 3
RECENT_INDEPENDENT_REQUIRED = 2

# 상태 구간. Addendum 이 MASTERED / WEAKENED 만 정의하므로 그 사이는 여기서 정한다.
# LEARNING 은 "아직 형태를 익히는 중", PRACTICING 은 "형태는 알고 안정성을 쌓는 중".
PRACTICING_THRESHOLD = 0.50


@dataclass(frozen=True)
class SkillState:
    """contracts/user-skill.schema.json 과 같은 모양."""

    skill_code: str
    scores: dict[str, float | None]
    mastery: float | None
    confidence: float
    evidence_count: int
    status: str

    def to_dict(self) -> dict:
        return {
            "skillCode": self.skill_code,
            **{d: self.scores[d] for d in DIMENSIONS},
            "mastery": self.mastery,
            "confidence": self.confidence,
            "evidenceCount": self.evidence_count,
            "status": self.status,
        }


def calculate_mastery(scores: dict[str, float | None]) -> float | None:
    """평가된 차원만으로 계산하고 가중치를 재정규화한다(Addendum 6).

    평가되지 않은 차원을 0 으로 두면 안 된다. 그러면 "아직 안 봤다" 가
    "못한다" 로 둔갑하고, 초반에 mastery 가 부당하게 낮게 나온다.
    아무 차원도 평가되지 않았으면 null 이다 - 0.0 과 다르다(Addendum 4).
    """
    evaluated = {d: v for d, v in scores.items() if v is not None}
    if not evaluated:
        return None
    total_weight = sum(WEIGHTS[d] for d in evaluated)
    if total_weight == 0:
        return None
    return round(sum(v * WEIGHTS[d] for d, v in evaluated.items()) / total_weight, 4)


def calculate_confidence(total_weight: float) -> float:
    """Addendum 18. mastery 와 별개다.

    "0.85 인데 confidence 0.20" 은 잘할 가능성은 높아 보이지만 증거가 적다는 뜻이다.
    이 값이 낮으면 MASTERED 로 넘어가지 않는다.
    """
    return round(min(1.0, total_weight / CONFIDENCE_DIVISOR), 4)


def apply(scores: dict[str, float | None], evidence: Evidence) -> dict[str, float | None]:
    """Evidence 하나를 EMA 로 반영한다(Addendum 9~10).

    첫 관측은 EMA 를 거치지 않고 그대로 들어간다. 이전 값이 없으므로 섞을 것이 없다.
    대신 confidence 가 낮게 시작해 "값은 있지만 아직 못 믿는다" 를 표현한다.
    """
    alpha = ALPHA[evidence.evidence_type]
    updated = dict(scores)
    for dim in DIMENSIONS:
        observed = evidence.observed.get(dim)
        if observed is None:
            continue  # 관측하지 못한 차원은 건드리지 않는다
        current = updated[dim]
        updated[dim] = round(
            observed if current is None else current * (1 - alpha) + observed * alpha, 4
        )
    return updated


def determine_status(
    *,
    mastery: float | None,
    confidence: float,
    recent_independent: list[bool],
    review_succeeded: bool,
    previous_status: str = "UNASSESSED",
) -> str:
    """상태를 정한다(Addendum 22~23).

    recent_independent 는 최근 독립 풀이 결과를 최신순으로 담는다.
    힌트를 4단계 이상 쓴 AC 와 정답을 본 뒤의 AC 는 여기 들어오지 않는다.

    LOCKED / READY 는 선수 관계에서 나오므로 여기서 다루지 않는다 -
    Evidence 만으로는 "앞 Skill 을 충분히 했는가" 를 알 수 없다.
    """
    if mastery is None:
        return "UNASSESSED"

    window = recent_independent[:RECENT_INDEPENDENT_WINDOW]
    failures = window.count(False)

    # Addendum 23. 한번 올라갔다가 흔들리는 것은 처음부터 낮은 것과 다르다.
    # WEAKENED 는 "됐었는데 지금은 아니다" 라서 복습 우선순위가 높다.
    if previous_status == "MASTERED":
        if not review_succeeded or failures >= RECENT_INDEPENDENT_REQUIRED:
            return "WEAKENED"

    # Addendum 22. 네 조건을 모두 만족해야 한다.
    if (
        mastery >= MASTERY_THRESHOLD
        and confidence >= CONFIDENCE_THRESHOLD
        and len(window) >= RECENT_INDEPENDENT_WINDOW
        and window.count(True) >= RECENT_INDEPENDENT_REQUIRED
        and review_succeeded
    ):
        return "MASTERED"

    if previous_status == "WEAKENED" and mastery < MASTERY_THRESHOLD:
        # 아직 회복되지 않았다. PRACTICING 으로 되돌리면 복습 우선순위를 잃는다.
        return "WEAKENED"

    return "PRACTICING" if mastery >= PRACTICING_THRESHOLD else "LEARNING"


def recompute(evidences: list[Evidence], skill_code: str) -> SkillState:
    """Evidence 목록 전체로부터 상태를 다시 계산한다.

    EMA 는 순서에 의존하므로 occurredAt 으로 정렬한 뒤 접는다. 같은 목록이면 항상
    같은 결과가 나온다 - 산식을 고치면 과거 Evidence 를 다시 접어 값을 바로잡을 수
    있다(ADR-0009).
    """
    ordered = sorted(evidences, key=lambda e: e.occurred_at)
    # skill_code 를 인자로 받는다. Evidence 가 하나도 없을 때도 상태를 내야 하는데
    # (UNASSESSED) 그때는 목록에서 code 를 알 수 없다.
    mismatched = {e.skill_code for e in ordered} - {skill_code}
    if mismatched:
        raise ValueError(f"다른 Skill 의 Evidence 가 섞였다: {sorted(mismatched)}")

    scores: dict[str, float | None] = {d: None for d in DIMENSIONS}
    total_weight = 0.0
    independent_log: list[tuple[str, bool]] = []
    review_succeeded = False
    status = "UNASSESSED"

    for ev in ordered:
        scores = apply(scores, ev)
        total_weight += ev.weight

        ctx = ev.context or {}
        if ctx.get("independentAttempt"):
            # 성공 여부는 judgeStatus 로 판단한다. 복습은 succeeded 로 온다.
            ok = ctx.get("judgeStatus") == "ACCEPTED" or ctx.get("reviewSucceeded") is True
            independent_log.append((ev.occurred_at, ok))
        if ev.evidence_type == "REVIEW_RESULT":
            review_succeeded = bool(ctx.get("reviewSucceeded"))

        mastery = calculate_mastery(scores)
        confidence = calculate_confidence(total_weight)
        recent = [ok for _, ok in reversed(independent_log)]
        status = determine_status(
            mastery=mastery,
            confidence=confidence,
            recent_independent=recent,
            review_succeeded=review_succeeded,
            previous_status=status,
        )

    return SkillState(
        skill_code=skill_code,
        scores=scores,
        mastery=calculate_mastery(scores),
        confidence=calculate_confidence(total_weight),
        evidence_count=len(ordered),
        status=status,
    )
