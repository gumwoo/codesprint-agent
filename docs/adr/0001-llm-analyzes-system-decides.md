# ADR-0001 · LLM은 분석하고, 시스템이 판정한다

- 상태: 채택
- 날짜: 2026-09-05
- 정본 근거: PRD §3.3, Addendum §19~21, §79~81

## 맥락

CodeSprint Agent는 사용자의 코드를 읽고 오답 원인을 분석한다. 이 분석 결과는
`mastery_score` 갱신과 다음 학습 행동 결정으로 이어진다.

LLM에게 어디까지 맡길 것인가를 정하지 않으면, 구현 과정에서 가장 편한 방향으로
흘러간다. 즉 "한 번의 호출로 분석부터 다음 행동까지 전부 받는" 형태가 된다.
그렇게 만들면 다음이 발생한다.

- 모델이 `mastery: 0.82` 같은 값을 **만들어낸다.** 산식이 있는데도 산식을 거치지 않는다.
- 같은 코드에 대해 호출할 때마다 다른 점수가 나온다. 학습 이력이 비결정적이 된다.
- 점수가 왜 그렇게 됐는지 설명할 수 없다. 사용자에게도, 개발자에게도.
- Judge가 이미 결정론적으로 알고 있는 사실(AC/WA, 실행 시간)을 모델이 추측한다.

## 결정

**LLM은 관찰과 분석만 담당하고, 값의 계산과 판정은 전부 시스템이 한다.**

| | LLM | 시스템 |
| --- | --- | --- |
| AC / WA 판정 | | Judge |
| 실행 시간 · 메모리 | | Judge |
| 오답 원인 후보 도출 | Reviewer | |
| 분석의 확신도(confidence) | Reviewer | |
| Mistake **확정** 여부 | | Rule (Addendum §21) |
| dimension score 관측값 매핑 | | MasteryService |
| `mastery_score` / `confidence_score` | | MasteryService |
| Skill status 전환 | | MasteryService |
| 개념 설명 · 힌트 · 문제 변형 | Tutor / Hint / Problem | |

이 경계를 **프롬프트가 아니라 스키마로 강제한다.**

`contracts/reviewer-output.llm.schema.json`에는 `score`, `mastery`, `nextAction`
필드가 존재하지 않는다. 물어보지 않으므로 만들어낼 수 없다.

`tools/check_curriculum.py`의 `check_llm_schema_owns_nothing_systemic()`이 이 불변식을
CI에서 검증하고, `tools/meta_test_curriculum.py`가 그 검사 자체가 살아 있는지 검증한다.

## 왜 프롬프트로 하지 않는가

"점수를 만들지 마세요"라는 지시는 지켜질 때도 있고 아닐 때도 있다. 프롬프트를 고치면
동작이 바뀌고, 모델을 바꾸면 또 바뀐다. **보장이 아니라 경향이다.**

스키마에서 필드를 제거하는 것은 다르다. Structured Output이 그 필드를 낼 수 없고,
누군가 실수로 필드를 되살리면 CI가 막는다. 코드 리뷰에 의존하지 않는다.

## 결과

**얻는 것**

- 같은 제출에 대해 점수 갱신이 결정론적이다. 재현·디버깅·회귀 테스트가 가능하다.
- 산식을 바꾸면 그 효과를 격리해 측정할 수 있다. 모델 성능과 섞이지 않는다.
- Reviewer 프롬프트를 교체해도 점수 체계가 흔들리지 않는다.
- 사용자에게 "왜 이 점수인가"를 설명할 수 있다.

**치르는 비용**

- 호출이 한 번으로 끝나지 않는다. Reviewer → Evaluator → Decision의 3단 흐름이 된다.
- 시스템이 계산해야 하므로 산식을 미리 확정해야 한다. Addendum PART I이 그 값이다.
- 스키마 두 벌(`*.llm.schema.json` / `*.schema.json`)을 유지해야 한다.

## 대안

**대안 A — 한 번의 호출로 전부 받는다.** 구현이 가장 빠르다. 기각한 이유는 위 맥락 그대로다.
학습 서비스에서 점수가 비결정적이면 "복습 성공 전에는 Mastered로 처리하지 않는다"
(PRD §143-5) 같은 규칙이 성립하지 않는다. 제품의 핵심 주장이 무너진다.

**대안 B — LLM이 점수를 내되 시스템이 보정한다.** 보정 로직이 결국 산식이므로
LLM 호출이 순수한 낭비가 된다. 게다가 "모델이 낸 값을 얼마나 믿을지"라는 파라미터가
하나 더 생긴다.

## 관련

- [ADR-0002](0002-next-action-decided-by-rule-engine.md) — 이 경계를 `nextAction`에 적용
- [ADR-0003](0003-skill-id-canonical-uppercase.md)
