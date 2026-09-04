# ADR-0002 · 다음 학습 행동은 Rule Engine이 결정한다 (LLM에게 묻지 않는다)

- 상태: 채택
- 날짜: 2026-09-05
- 정본 근거: Addendum §80, §43 / PRD §75~76

## 맥락

**두 정본 문서가 충돌한다.**

PRD §71의 Reviewer 출력 예시에는 다음이 들어 있다.

```json
{
  "primaryMistake": "BINARY_SEARCH_BOUND",
  "confidence": 0.93,
  "recommendedAction": "MICRO_DRILL"
}
```

반면 Addendum §80은 `DecisionService`의 책임을 이렇게 정의한다.

```text
UserSkill + Mistakes + Current Problem + Session Context
    ↓
NextAction
```

그리고 §79는 "ReviewerService는 Score를 직접 수정하지 않는다"고 명시한다.

즉 PRD는 Reviewer가 액션을 추천하게 두었고, Addendum은 Decision을 별도 서비스로
분리했다. 어느 쪽인지 정하지 않으면 두 곳에서 액션이 나오고, 서로 다를 때 누가
이기는지가 구현 시점의 우연으로 결정된다.

## 결정

**`nextAction`을 LLM에게 묻지 않는다.** Reviewer 출력 스키마에서 제거한다.

Addendum §43의 Rule Engine이 유일한 결정 주체다.

```text
if submission == ACCEPTED:
    if mastery >= 0.80 and confidence >= 0.60:
        UNLOCK_NEXT if review_completed else SCHEDULE_REVIEW
    else:
        CONTINUE
else:
    if confirmedMistake == BOUNDARY_CHECK:   MICRO_DRILL(GRID_BOUNDARY_CHECK)
    elif confirmedMistake == VISITED_TIMING: MICRO_DRILL(BFS_VISITED_MANAGEMENT)
    elif sameProblemAttempts >= 3:           REVIEW_CONCEPT
    else:                                    RETRY_VARIANT
```

Reviewer가 기여하는 것은 `primaryMistake`와 `confidence`까지이며, 그 값이
**확정(CONFIRMED)될지**조차 시스템이 §21의 조건으로 판단한다.

## 왜 Addendum이 이기는가

세 가지 이유다.

**1. Reviewer는 결정에 필요한 정보를 갖고 있지 않다.**

`MICRO_DRILL`과 `RETRY_VARIANT` 중 무엇이 맞는지는 이번 제출만 봐서는 알 수 없다.
같은 문제를 몇 번째 시도하는지, 같은 Mistake가 최근 3문제에서 몇 번 나왔는지,
현재 세션에 시간이 얼마나 남았는지, 시험이 며칠 남았는지가 필요하다.
Reviewer에게 그걸 전부 넘기면 Reviewer가 곧 Decision Engine이 된다.

**2. 물어보면 만들어내고, 만들어낸 것을 버리게 된다.**

시스템이 어차피 규칙으로 액션을 정한다면 모델의 `recommendedAction`은 폐기된다.
그런데 **폐기할 값의 형식이 틀렸다는 이유로 멀쩡한 오답 분석 전체가 거부될 수 있다.**
쓰지 않을 값을 요구하는 것은 순수한 실패 지점 추가다.

**3. 자동 액션은 오분류의 증폭기다.**

Mistake 오분류는 잘못된 라벨 하나로 끝나지만, 잘못된 액션은 사용자의 학습 시간을
엉뚱한 드릴에 쓰게 만든다. Reviewer 정확도가 측정되기 전(→ 평가 하네스는 슬라이스 1
이후)에 액션까지 모델에 맡기면, 정확도가 낮다는 사실조차 사용자 이탈로만 관측된다.

같은 이유로 `curriculum/mistakes.yaml`의 `auto_drill`은 슬라이스 1에서 2개만 켠다.

## 결과

- PRD §71의 `recommendedAction` 필드는 **폐기한다.** PRD 본문 갱신 시 반영한다.
- `contracts/reviewer-output.llm.schema.json`에 `nextAction` / `recommendedAction`이
  나타나면 CI가 실패한다 (`check_llm_schema_owns_nothing_systemic`).
- `nextAction`은 `contracts/submit-response.schema.json`에만 존재하며,
  `reason` 필드로 **어떤 규칙이 골랐는지**를 감사 로그로 남긴다.
- Rule Engine의 각 분기는 단위 테스트 대상이다 (Addendum §86).

## 나중에 뒤집힐 수 있는가

있다. 데이터가 쌓이면 §157의 Contextual Bandit / Learning-to-Rank로 액션 선택을
학습시키는 것이 자연스러운 다음 단계다.

다만 그때도 **LLM에게 자연어로 묻는 형태가 아니라**, 시스템이 관리하는 모델이
결정론적 입력을 받아 액션을 내는 형태다. 이 ADR이 막는 것은 "학습된 정책"이 아니라
"매 호출마다 달라지는 즉흥 판단"이다.

## 관련

- [ADR-0001](0001-llm-analyzes-system-decides.md) — 이 결정의 상위 원칙
