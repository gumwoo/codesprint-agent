# ADR-0022 · `UNLOCK_NEXT` 는 갈 곳을 함께 말한다

- 상태: 채택
- 날짜: 2026-09-08
- 정본 근거: PRD §75, Addendum §22 · §33,
  [ADR-0002](0002-next-action-decided-by-rule-engine.md),
  [ADR-0019](0019-the-diagnostic-is-an-input-to-the-decision-engine.md),
  [ADR-0021](0021-a-review-is-what-the-schedule-says-it-is.md)

## 맥락

[ADR-0021](0021-a-review-is-what-the-schedule-says-it-is.md) 로 `MASTERED` 에 실제로
도달할 수 있게 됐다. 그런데 그 다음이 막혀 있었다.

```java
case UNLOCK_NEXT -> none("다음 Skill 을 고르는 규칙이 아직 없다");
```

**갈 곳 없는 액션이다.** `CHANGE_SKILL`(PR #17)과 `SCHEDULE_REVIEW`(PR #27)에서 이미
두 번 닫은 것과 같은 종류이고, 이번 것은 **가장 잘한 사용자만 만난다.**

그리고 행동 자체에 대상이 없었다 — `NextAction.of(UNLOCK_NEXT, ...)`. "다음으로
넘어가라" 고만 말하고 어디로 가는지는 말하지 않았다.

**PRD §75 는 이 행동의 이름만 정하고 무엇을 고를지는 정하지 않는다.** 그래서 규칙을
여기서 세운다.

## 결정

### 1. 이번 숙달이 **실제로 연** Skill 로 간다

그 Skill 을 선수로 요구하면서, 이제 모든 선수를 채운 것. 이것이 `UNLOCK_NEXT` 라는
이름 그대로의 뜻이다.

선수가 하나라도 남아 있으면 열린 것이 아니다. `BFS_GRID_TRAVERSAL` 은 선수가 셋이라
`BFS_BASIC` 하나를 숙달해도 열리지 않는다.

여럿이 동시에 열리면 **code 순**으로 고른다. 여기서 정보 이득 같은 것을 따지지 않는다 —
진단은 모르는 것을 채우는 일이라 그 기준이 맞지만(ADR-0018), 여기서는 어느 쪽이든
배워야 하는 것이라 순서에 의미를 부여하면 **없는 근거를 만드는 셈이다.**

### 2. 연 것이 없으면 **지금 할 수 있는 것 중 가장 뒤처진 것**

그래프의 끝을 숙달했거나(`BFS_SHORTEST_PATH` 를 요구하는 Skill 은 없다), 열린 것이
이미 다 숙달됐을 때다.

"가장 뒤처진 것" 은 `PrerequisiteEvaluator` 가 막힌 선수를 고를 때 쓰는 기준과 같다 —
잘하는 것을 더 잘하게 만드는 것보다 못하는 것을 끌어올리는 편이 전체 잠금 해제까지의
거리를 줄인다.

### 3. 그것도 없으면 `END_SESSION`

커리큘럼에 남은 Skill 이 없다. 슬라이스 1 은 Skill 이 8개뿐이라 **실제로 도달한다.**

없는 것을 가리키는 `UNLOCK_NEXT` 를 내느니 끝났다고 말하는 편이 정직하다. `END_SESSION`
은 PRD §75 의 목록에 이미 있다.

### 선택은 Decision Engine 바깥에서 한다

진단(ADR-0019)·복습(ADR-0021)과 같은 자리다. `NextSkillSelector` 가 전체 Skill 상태를
보고 후보를 고르고, Decision Engine 은 그것을 **입력으로** 받는다.

이유는 순수성이다. Decision Engine 은 상태도 시간도 보지 않아야 규칙 하나하나가 단위
테스트 대상이 된다(Addendum §86). "이미 숙달했는가" 는 mastery 값만으로 알 수 없고
전체 상태가 필요하다.

## 결과

- `MASTERED` 다음에 실제로 문제가 나온다. 학습 루프에 **끝이 생겼다**
- 결과 반영 한 번에 **상태를 한 번만 계산한다.** 진단과 다음 Skill 선택이 각각 다시
  계산하면 조회가 두 배가 되고, 더 나쁘게는 한 결정 안에서 서로 다른 시점을 본다

### 남는 위험

- **`END_SESSION` 뒤가 없다.** 슬라이스 1 의 8개를 다 숙달하면 화면은 "남은 Skill 이
  없다" 를 보여줄 뿐이다. 커리큘럼이 늘어나기 전까지는 그것이 사실이다
- 2번 규칙은 mastery 값만 본다. `WEAKENED` 처럼 "됐었는데 지금은 아니다" 인 Skill 이
  점수만으로는 새로 시작하는 Skill 과 구분되지 않는다 — 복습 일정이 그쪽을 따로
  잡으므로 지금은 겹치지 않지만, 두 규칙이 같은 것을 다르게 볼 여지는 남는다

## 관련

- [ADR-0019](0019-the-diagnostic-is-an-input-to-the-decision-engine.md) — 같은 자리에 들어가는 입력
- [ADR-0021](0021-a-review-is-what-the-schedule-says-it-is.md) — 여기까지 오는 길
- [ADR-0002](0002-next-action-decided-by-rule-engine.md) — 결정 주체는 하나다
