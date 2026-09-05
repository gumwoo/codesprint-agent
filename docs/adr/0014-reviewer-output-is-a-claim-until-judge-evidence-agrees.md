# ADR-0014 · Reviewer 출력은 Judge 근거와 맞을 때까지 주장일 뿐이다

- 상태: 채택
- 날짜: 2026-09-05
- 정본 근거: Addendum §19~21 / [ADR-0001](0001-llm-analyzes-system-decides.md) / [ADR-0004](0004-reviewer-invocation-requires-case-evidence.md) / [ADR-0005](0005-judge-stops-at-first-failure.md)

## 맥락

Reviewer 를 붙이면서 처음으로 **LLM 이 만든 값이 학습 경로에 영향을 준다.** 확정된
Mistake 하나가 `MICRO_DRILL` 을 유발하고, 그 드릴 결과가 Evidence 로 쌓여 mastery 를
바꾸고, 그 mastery 가 다음 문제를 고른다.

ADR-0001 이 "LLM 은 분석, 시스템은 결정" 을 정했고 스키마가 그것을 강제한다 -
Reviewer 출력에는 `score` / `mastery` / `nextAction` 이 아예 없다. 그런데 남은 구멍이
하나 있다. **Mistake code 자체는 LLM 이 고른다.** 그 값이 그대로 확정되면, ADR-0001 을
지키면서도 오분류가 학습 경로를 오염시킨다.

Addendum §20 은 이것을 "Judge Evidence 우선 원칙" 으로 부르고 우선순위를 정해 뒀다.

```text
1. 실제 실행 결과
2. 테스트케이스 차이
3. 정적 Rule
4. LLM Reviewer
```

문제는 이 문장이 **코드로 옮기면 무엇을 검사하는 것인가** 가 정해져 있지 않다는 것이다.
"실패 Test Case 특성이 해당 Mistake 와 일치" (§21-A) 를 그대로 구현하려면 case 마다
"이 case 는 경계 조건을 본다" 같은 라벨이 있어야 하는데, 지금 `cases.json` 에는 없다.

## 결정

### 1. Reviewer 출력은 검증을 통과해야 저장된다

스키마만으로는 부족하다. 다음을 시스템이 확인하고, 하나라도 어긋나면 **그 분석은
버린다** - 고쳐서 쓰지 않는다.

| 검사 | 어긋나면 생기는 일 |
| --- | --- |
| `primaryMistake` 가 `assigned_by: REVIEWER` 인가 | `SYNTAX_ERROR` 처럼 시스템이 부여하는 code 를 LLM 이 주장한다 |
| `affectedSkills` 가 커리큘럼에 실재하는가 | 없는 Skill 에 penalty 가 걸린다 |
| `failedCaseRefs` 가 비어 있지 않은가 | 근거를 대지 않은 분석이 기록된다. <b>다만 이 값은 확정의 근거가 아니다</b> |
| `secondaryMistakes` 에 `primaryMistake` 가 중복되지 않는가 | 같은 원인이 두 번 세어진다 |

### 2. §21-A 는 **독립적인 근거가 생길 때까지 확정하지 않는다**

처음에는 §21-A 를 "Reviewer 가 Judge 의 실패 case 를 인용했는가" 로 구현했다.
**그것은 근거가 아니다.**

```text
Judge      case 4 실패
요청       failedCaseId = 4          ← 모델에게 알려준다
Reviewer   failedCaseRefs = [4]      ← 그대로 되돌려준다
시스템     "번호가 일치한다" → 확정   ← 아무것도 확인하지 않았다
```

case 4 가 실제로 그 Mistake 를 보여주는 case 인지는 아무도 확인하지 않았다. 요청에
그 번호를 넣어 보내므로 모델은 받은 값을 돌려주기만 하면 된다 - **결국 confidence 만
보는 것과 같다.** "확신만으로 확정하지 않는다" 고 적어놓고 실제로는 그러고 있었다.

그래서 §21-A 의 `corroborated` 는 **Reviewer 밖에서 온 신호만** 받는다. 결정론적 Rule
탐지(§21-C)나 실패 case 의 성격 태그 대조가 거기 들어간다.

**지금은 그런 신호가 하나도 없다.** `cases.json` 에 기계가 읽을 수 있는 성격 태그가
없고 Rule 탐지기도 없다. 그러므로 **현재 확정은 §21-B(실제 재발)로만 일어난다.**
조건 A 는 코드에 남겨 두되 발동하지 않는다 - 데이터가 생기면 그 자리에 꽂는다.

`failedCaseRefs` 자체는 계속 요구한다. 근거를 대게 하는 것은 분석 품질에 도움이 되고,
나중에 평가 데이터셋의 라벨이 된다. 다만 **그것을 확정의 근거로 쓰지 않는다.**

### 3. §21-B 의 "최근 3문제" 는 **제출 이력**에서 정한다

탐지 기록에서 창을 뽑으면 실수가 없었던 문제가 창에 들어오지 않는다.

```text
P1  BOUNDARY_CHECK 탐지
P2  깨끗
P3  깨끗
P4  깨끗
P5  BOUNDARY_CHECK 탐지   ← 지금
```

실제 최근 3문제는 P3·P4·P5 이고 그 안에서는 1회다. 그런데 탐지 기록만 보면 P1 과 P5 가
나란히 보여 2회가 되고, **몇 달 전 실수가 현재 실수와 묶여 확정된다.**

### 4. 확정 상태는 네 가지이고, 시스템이 정한다

Addendum §19 의 confidence 구간을 그대로 쓴다.

```text
confidence < 0.60           LOGGED_ONLY  참고 로그만. mastery 반영 금지
0.60 <= c < 0.80            POSSIBLE     자동 드릴 금지. 재발하면 확정 가능
0.80 <= c < 0.90            PROBABLE     약한 penalty 까지
c >= 0.90                   CONFIRMED 후보
```

여기에 §21 의 확정 조건을 얹는다.

```text
A.  confidence >= 0.90  AND  Reviewer 밖의 독립적인 근거가 뒷받침한다   (지금은 발동하지 않는다)
B.  confidence >= 0.80  AND  같은 Mistake 가 최근 3문제에서 2회 이상 탐지됐다
```

**LLM 이 스스로 `CONFIRMED` 를 선언할 수 없다.** 그 필드는 요청 스키마에 없다.

### 5. 확정된 Mistake 만 Decision Engine 에 넘어간다

`DecisionEngine.Context.confirmedMistake` 는 이미 "확정된 것만 들어온다" 고 적혀 있었고,
지금까지는 언제나 `null` 이었다. 그 자리에 들어가는 값이 **A 또는 B 를 통과한 것**이다.

그리고 자동 드릴은 `auto_drill: true` 인 두 종류만 연결된다(`curriculum/mistakes.yaml`).
확정됐다고 전부 드릴로 가지 않는다 - Reviewer 정확도가 실제 제출 데이터로 측정되기
전에 자동 액션을 넓히면 오분류가 곧바로 학습 경로 오염이 된다.

## 결과

**좋아지는 것**

- 오분류의 영향이 한 단계 늦춰진다. 낮은 confidence 는 기록만 되고, 높아도 Judge 근거와
  맞아야 확정된다.
- 확정되지 않은 탐지도 **버리지 않는다.** §21-B 의 "재발" 을 세려면 남아 있어야 하고,
  나중에 Reviewer 정확도를 측정할 라벨이 되기도 한다.
- `WEAKENED` 의 세 번째 조건(동일 핵심 Mistake 2회 반복)이 판단 가능해진다.

**나빠지는 것**

- **확정되기까지 시간이 걸린다.** 조건 A 가 발동하지 않으므로 <b>처음 보는 실수는
  절대 확정되지 않는다</b> - 서로 다른 문제에서 다시 나와야 한다.
  사용자 입장에서는 "알아챘는데 아무것도 안 해준다" 로 보일 수 있다. 그 대가로
  오분류로 엉뚱한 드릴을 주는 일을 막는다.
- 검증에 걸린 분석은 통째로 버린다. 부분적으로 맞는 분석도 사라진다.

**바뀌지 않는 것**

- 점수와 액션은 여전히 LLM 에게 묻지 않는다(ADR-0001, ADR-0002).
- `ACCEPTED` / `COMPILE_ERROR` / `SYSTEM_ERROR` 에서는 Reviewer 를 부르지 않는다(ADR-0004).

## 대안

**confidence 만으로 확정한다.** 구현이 가장 단순하지만, LLM 이 자기 확신도를 스스로
매긴다는 점에서 순환이다. 확신에 찬 오분류를 막을 방법이 없다.

**확정 전까지 아무것도 저장하지 않는다.** 그러면 §21-B 의 재발을 셀 수 없다.
