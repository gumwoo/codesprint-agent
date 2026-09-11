# ADR-0024 · `REVIEW_CONCEPT`는 검증된 커리큘럼 자료를 가리킨다

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

## 관련

- [ADR-0002](0002-next-action-decided-by-rule-engine.md)
- [ADR-0012](0012-curriculum-is-packaged-from-one-source.md)
- [ADR-0017](0017-the-web-client-has-no-build-step.md)
