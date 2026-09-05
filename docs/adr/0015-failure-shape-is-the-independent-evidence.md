# ADR-0015 · 실패의 모양이 Reviewer 밖의 근거다

- 상태: 채택
- 날짜: 2026-09-06
- 정본 근거: Addendum §19, §21, §66 / [ADR-0014](0014-reviewer-output-is-a-claim-until-judge-evidence-agrees.md) / [ADR-0005](0005-judging-stops-at-first-failure.md)

## 맥락

[ADR-0014](0014-reviewer-output-is-a-claim-until-judge-evidence-agrees.md)에서 §21-A를
껐다. 조건이 요구하는 "Reviewer 밖의 독립 근거"가 저장소에 존재하지 않았기 때문이다.
그때 유일한 후보였던 "Reviewer가 실패 case를 인용했는가"는 근거가 아니다 — 그 번호를
요청으로 알려주므로 되돌려주기만 하면 된다.

그래서 지금 확정은 §21-B(재발)로만 일어난다. **처음 보는 실수는 확정되지 않는다.**
사용자는 같은 실수를 두 문제에서 반복해야 드릴을 받는다.

독립 근거가 되려면 두 조건을 만족해야 한다.

- **Reviewer가 만들지 않은 것** — 관측이거나, 분석 이전에 정해져 있던 데이터
- **그 Mistake를 구별하는 것** — 무엇이 틀렸든 참이 되는 신호는 아무것도 확인하지 않는다

## 결정

**실패의 모양을 근거로 쓴다.** 두 조각을 맞춘다.

```text
문제 데이터   cases.json 의 probes   "그 실수가 있으면 이 case 는 반드시 실패한다"
Judge 관측    실행한 case 의 결과    어떤 case 가 실패하고 어떤 case 가 통과했는가
```

둘 다 Reviewer가 건드리지 않는다. 앞쪽은 제출 이전부터 저장소에 있고, 뒤쪽은 Judge가
결정론적으로 만든다(ADR-0001).

### 뒷받침 조건

```text
겨냥한 case 가 **전부** 실패했다          필요조건
겨냥하지 않은 case 중 통과한 것이 있다     대조군
```

대조군이 핵심이다. 없으면 **전부 실패한 제출이 어떤 태그든 만족한다** — "무엇이
틀렸든 그 Mistake"가 되어 아무것도 확인하지 못한다. 검사가 통과하는 것과 검사가
일하는 것은 다르다.

정본은 `CaseCorroboration.java`이고, `tools/verify_problems.py`의 `satisfies()`가
**같은 조건**을 쓴다. 갈리면 CI는 통과하는데 실제로는 확정되지 않는(또는 그 반대의)
상태가 된다.

### 이것이 증명하지 않는 것

**그 실수가 있었음을 증명하지 않는다.** 겨냥한 case를 실패시키는 다른 원인은 얼마든지
있다. 배제하는 것은 "실패가 그 실수와 아무 관계도 없는" 경우뿐이다.

그래서 §21-A는 이 근거 하나로 확정하지 않는다. `confidence >= 0.90`을 함께 요구한다.
약한 독립 근거와 높은 확신을 곱하는 것이지, 어느 한쪽이 단독으로 확정하지 않는다.

## 태그도 주장이다 — 그래서 검증한다

`probes`를 데이터에 적는 것만으로는 ADR-0014가 지적한 문제가 한 칸 옮겨갈 뿐이다.
아무도 확인하지 않은 태그가 확정 근거로 쓰이면 똑같다.

`problems/<CODE>/probes/<MISTAKE>.py`가 **그 실수를 담은 풀이**이고,
`tools/verify_problems.py`가 실제로 채점해서 두 방향을 본다.

```text
probes/<M>.py    태그된 case 를 **전부** 실패시키는가        (태그가 성립하는가)
wrong.py         **다른** 실수인데 같은 조건을 만족하는가     (태그가 구별하는가)
```

뒤쪽이 대조군이다. 다른 실수의 오답이 같은 조건을 만족하면 그 태그는 실수를 구별하지
못하므로 `[VACUOUS]`로 실패시킨다. `wrong.py`(ADR-0007)와 같은 논리이며, 그래서 태그는
`negativeControl.mistake`와 같은 실수에는 붙일 수 없다 — 붙이면 대조군이 사라진다.

정적 검사(`tools/check_problems.py`)는 주장이 확인 가능한 형태인지만 본다. 실재하는
Mistake인지, `assigned_by: REVIEWER`인지, 그 실수의 풀이가 저장소에 있는지, 대조군이
될 case가 남아 있는지.

## ADR-0005를 일부 뒤집는다 — 싼 실패에서는 멈추지 않는다

이 근거는 **통과한 case를 알아야** 만들 수 있다. 첫 실패에서 멈추면 대조군이
없다.

[ADR-0005](0005-judging-stops-at-first-failure.md)가 조기 종료를 택한 이유는 비용이었다
— 무한 루프 하나가 case 수만큼의 timeout을 먹는다. 그 근거는 지금도 유효하지만,
**모든 실패에 해당하지는 않는다.**

```text
WRONG_ANSWER / RUNTIME_ERROR     프로세스가 이미 끝났다. 다음 case 비용 = 정상 case
TIME_LIMIT / MEMORY_LIMIT        제한에 걸릴 때까지 기다린 것이다. 계속 돌리면 N 배
OUTPUT_LIMIT
```

**싼 실패에서는 남은 case를 계속 돌리고, 비싼 실패에서는 그대로 멈춘다.** ADR-0005가
지키려던 비용 특성("무한 루프 제출의 최악 소요가 1 × timeout")은 그대로다.

`status`와 `failedCaseId`는 **첫** 실패가 정한다. 뒤의 case가 덮으면 Reviewer에게 주는
근거가 제출마다 흔들린다.

ADR-0005는 이 경우를 예상하고 "필요해지면 전부 돌리는 모드를 두는 편이 낫다"고 적어
뒀다. 실제로 필요해졌고, 부분 점수를 위해서가 아니라 **진단을 위해서** 필요해졌다.

## 결과

- 처음 보는 실수도 확정될 수 있다. 재발을 기다리지 않아도 드릴이 나온다.
- 확정되지 않는 쪽으로 틀린다. 태그를 좁게 잡을수록 조건은 느슨해지고 넓게 잡을수록
  엄격해지는데, 태그된 case 하나만 통과해도 뒷받침이 무너지므로 **의심스러우면
  PROBABLE에 머문다.** 잘못된 확정은 곧바로 학습 경로 오염이다.
- `cases` 배열의 의미가 하나 늘었다. 여기 없는 case는 "실패했다"가 아니라 "실행되지
  않았다"다. 둘을 섞으면 앞에서 멈춘 제출이 뒤쪽 태그까지 만족한 것으로 읽힌다.
- §21-C(결정론적 Rule 탐지)는 아직 없다. 생기면 같은 자리에 꽂는다 —
  `MistakeConfirmation.decide` 의 `corroborated` 는 근거의 종류를 묻지 않는다.
- 태그는 슬라이스 1에서 `BOUNDARY_CHECK` 하나뿐이다. `VISITED_TIMING`은 그 실수를
  담은 풀이를 가진 문제가 드릴뿐이라 대조군이 없다 — 일반 문제에 그 실수의 오답을
  넣으면서 함께 연다.

## 관련

- [ADR-0014](0014-reviewer-output-is-a-claim-until-judge-evidence-agrees.md) — 이 ADR이 채우는 빈자리
- [ADR-0005](0005-judging-stops-at-first-failure.md) — 조기 종료. 싼 실패에 한해 뒤집힌다
- [ADR-0007](0007-problems-are-verified-by-a-wrong-solution.md) — 오답으로 문제를 검증하는 같은 논리
- [ADR-0004](0004-reviewer-invocation-requires-case-evidence.md) — `failedCaseId`를 요구하는 쪽
