# ADR-0024 · `REVIEW_CONCEPT`는 커리큘럼 자료를 가리킨다

- 상태: 채택
- 날짜: 2026-09-12
- 정본 근거: PRD §75, Addendum §43,
  [ADR-0001](0001-llm-analyzes-system-decides.md),
  [ADR-0002](0002-next-action-decided-by-rule-engine.md),
  [ADR-0012](0012-curriculum-is-packaged-from-one-source.md)

## 맥락

Decision Engine은 같은 문제를 세 번 연속 실패하면 `REVIEW_CONCEPT`를 결정한다.
그러나 이 행동을 실제 자료로 옮기는 규칙이 없으면 사용자는 다음 학습 단계로 진행할 수 없다.
화면이나 Java 조건문에 설명을 직접 넣으면 Skill 정본과 내용이 갈라지고, LLM이 매번 생성하면
같은 Skill에도 결과가 달라지며 LLM이 학습 행동을 사실상 소유하게 된다.

## 결정

1. `curriculum/concepts.yaml`을 Skill별 기본 개념 복습 자료의 정본으로 둔다.
2. 검증된 모든 Skill은 정확히 하나의 자료를 가져야 한다. 누락, 중복, 존재하지 않는 Skill 참조,
   빈 필드는 `tools/check_curriculum.py`가 막고 메타테스트가 해당 검사를 검증한다.
3. 저장된 다음 행동이 `REVIEW_CONCEPT`일 때만 `next-problem` 응답의 `concept`에 대상 Skill의
   자료를 넣는다. 다른 행동에서는 `concept`가 명시적으로 `null`이다.
4. 화면은 서버가 준 `problem` 또는 `concept`를 표시할 뿐, `action`을 근거로 행동을 다시 결정하거나
   자료를 만들지 않는다. 행동의 유일한 결정자는 계속 Decision Engine이다.
5. 이 기본 자료는 LLM과 무관하게 사용할 수 있다. 향후 LLM 설명을 붙이더라도 선택 기능일 뿐,
   실패가 기본 학습 경로를 막아서는 안 된다.

## 결과

반복 실패는 막다른 행동이 아니라 설명, 예시, 핵심 항목, 자기 확인 질문이 있는 학습 단계가 된다.
새 Skill 승격에는 자료 작성 비용이 따르지만 불완전한 학습 경로가 공개되는 것을 CI가 차단한다.

이것으로 갈 곳 없는 액션 네 개가 모두 닫혔다 — `CHANGE_SKILL`(PR #17) ·
`SCHEDULE_REVIEW`(PR #27) · `UNLOCK_NEXT`(PR #28) · `REVIEW_CONCEPT`.

### 남는 위험

- **자료의 내용이 맞는지는 아무도 검사하지 않는다.** `check_curriculum.py` 가 보는 것은
  "비어 있지 않은 문자열인가" 뿐이다. `example` 코드가 실제로 도는지, 설명이 그 Skill 을
  제대로 말하는지는 확인되지 않는다 — 문제 본문이 풀이 전략을 흘린 것을 기계가 잡지
  못했던 것과 같은 부류이고, **이 종류는 사람이 읽어야 한다**
- **Skill 하나에 자료 하나다.** 같은 Skill 을 두 번 반복 실패한 사용자는 같은 설명을 다시
  본다. 실패의 양상(경계 조건인가 방문 처리인가)에 따라 다른 자료를 주려면 자료를
  Mistake 단위로 쪼개야 하는데, 그때 이 ADR 을 갈음한다
- **화면에 자동 검사가 없다.** 자료가 실제로 그려지는지는 사람이 눌러 봐야 안다
  (ADR-0017). `WebClientTest` 가 막는 것은 "화면이 행동을 다시 결정하지 않는가" 까지다

## 관련

- [ADR-0002](0002-next-action-decided-by-rule-engine.md) — 행동의 결정자는 하나다
- [ADR-0012](0012-curriculum-is-packaged-from-one-source.md) — 커리큘럼 복사본을 만들지 않는다
- [ADR-0017](0017-the-web-client-has-no-build-step.md) — 화면에 자동 검사가 없는 이유
- [ADR-0023](0023-the-screen-checks-ownership-not-behaviour.md) — 화면이 검사받는 범위
