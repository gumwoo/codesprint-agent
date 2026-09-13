# ADR-0030 · 에이전트는 같은 말을 되풀이하지 않는다

- 상태: 채택
- 날짜: 2026-09-13
- 정본 근거: Addendum §43(Decision Engine MVP Rule),
  [ADR-0024](0024-review-concept-resolves-to-curriculum-material.md),
  [ADR-0028](0028-the-guided-path-is-walked-end-to-end.md)

## 맥락

[ADR-0028](0028-the-guided-path-is-walked-end-to-end.md) 이 남긴 위험이 이것이었다.

> **한 갈래만 걷는다.** 전부 `ACCEPTED` 로 푸는 길이다. 틀리는 길은 다른 테스트가
> 조각으로 보고 있고, 여기서 이어 붙이지 않았다.

그래서 틀리면서 걸었다(`FailingJourneyTest`). 여기서도 아무것도 고르지 않는다 —
진단이 준 첫 문제에서 출발해 매번 `nextAction` 이 가리키는 곳으로만 간다.

**서른 걸음 중 스물여섯 걸음이 제자리였다.**

```
 1. 진단 -> P05_SHORTEST_PATH
 4. CHANGE_SKILL -> P11_LIST_BASIC
 5. RETRY_VARIANT -> (문제 없음: 방금 푼 문제 말고는 PYTHON_LIST_BASIC 문제가 없다)
 6. RETRY_VARIANT -> (문제 없음: 같음)
 7. REVIEW_CONCEPT -> 자료(PYTHON_LIST_BASIC)
 8. REVIEW_CONCEPT -> 자료(PYTHON_LIST_BASIC)
 ...
30. REVIEW_CONCEPT -> 자료(PYTHON_LIST_BASIC)      <- 스물네 번째 같은 자료
```

## 두 가지가 드러났다

### ① 문제가 하나뿐인 Skill 에서는 빈손으로 돌려보냈다

`PYTHON_LIST_BASIC` 의 `NORMAL` 문제는 `P11_LIST_BASIC` 하나다. `pick` 은 방금 푼
문제를 후보에서 빼므로 남는 것이 없고, `none("방금 푼 문제 말고는 ... 문제가 없다")`
가 나왔다.

**틀린 사용자를 목록으로 돌려보내는 것이 이 Engine 이 가장 피해야 할 일이다.**
그 Skill 에 문제가 하나뿐이면 방금 그것이 유일한 답이고, 다시 주는 편이 빈 화면보다
낫다. 이유에 그렇게 적는다 — "이 Skill 에는 이 문제뿐이다".

### ② 개념 자료가 무한히 반복됐다

Addendum §43 의 MVP 규칙은 이렇다.

```text
elif sameProblemAttempts >= 3:
    REVIEW_CONCEPT
```

**그 뒤를 정하지 않았다.** 자료를 읽고 다시 풀어도 틀리면 `sameProblemAttempts` 는
계속 늘고, 조건은 계속 맞는다. 그래서 같은 자료가 계속 나온다.

이것은 규칙 위반이 아니다 — **명세가 닿지 않은 자리**다. 그리고 조각 검사로는
보이지 않는다. `REVIEW_CONCEPT` 가 자료를 제대로 주는지는 테스트가 있고 통과한다.
**한 번 주는 것은 맞고, 스물네 번 주는 것이 틀린 것이다.**

## 결정

**개념 자료는 그 문제에서 한 번만 준다.**

이미 보여준 뒤의 실패는 아래로 흘러 Addendum §43 의 `else` 인 `RETRY_VARIANT` 가
된다 — 같은 Skill 의 다른 문제로 옮긴다.

```java
if (context.sameProblemAttempts() >= 3 && !context.conceptAlreadyShown()) {
```

**새 교수법을 지어내지 않았다.** `EASIER` 같은 행동을 새로 내는 것도 생각했지만,
그것은 명세에 없는 판단이고 이 저장소는 점수와 액션을 임의로 만들지 않는다. 여기서
한 것은 **이미 있는 규칙이 스스로를 되풀이하지 않게 한 것**뿐이다.

확정된 Mistake 는 여전히 개념보다 먼저다. 자료를 이미 봤어도 드릴이 있으면 드릴로
간다 — 순서는 §43 그대로다.

### "이미 보여줬는가" 는 제출 기록에서 읽는다

`submissions.next_action_type` 이 그 문제에서 `REVIEW_CONCEPT` 였던 적이 있는지 본다.
별도 상태를 두지 않는 이유는, 그 기록이 이미 정본이기 때문이다 — 화면이 보여준 것과
같은 값을 읽는다.

## 결과

- 서른 걸음 중 갈 곳 없는 걸음이 **0** 이 됐다
- 문제가 하나뿐인 Skill 에서도 다음 문제가 나온다
- 대조군: 두 고침을 각각 되돌리면 그 걸음이 다시 "문제 없음" 으로 나타난다

### 남는 위험

- **되풀이가 완전히 사라진 것은 아니다.** 문제가 하나뿐인 Skill 에서는 같은 문제를
  계속 받게 된다. 그것은 규칙이 아니라 **데이터의 한계**이고, 그 Skill 에 문제를
  더 넣으면 사라진다. 빈 화면보다 낫다는 판단이지 좋은 상태라는 뜻은 아니다
- **자료를 한 번 본 문제에서는 다시 볼 수 없다.** 한참 뒤에 같은 문제로 돌아와도
  그렇다. 스트릭 단위로 다시 열어 주는 편이 더 맞을 수 있지만, 그러려면 "스트릭이
  언제 끝났는가" 를 정의해야 해서 지금은 단순한 쪽을 골랐다
- 이 여정도 **한 갈래**다. 전부 같은 방식으로 틀린다 — `WRONG_ANSWER` 하나이고,
  시간 초과나 런타임 오류로 틀리는 길은 걷지 않았다

## 관련

- [ADR-0028](0028-the-guided-path-is-walked-end-to-end.md) — 정답으로 가는 반대편 갈래
- [ADR-0024](0024-review-concept-resolves-to-curriculum-material.md) — 그 자료가 어디서 오는가
