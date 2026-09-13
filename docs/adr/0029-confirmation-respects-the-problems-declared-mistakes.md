# ADR-0029 · 그 문제에서 일어날 수 없다고 선언된 실수는 확정하지 않는다

- 상태: 채택
- 날짜: 2026-09-13
- 정본 근거: Addendum §19 · §21, PRD §143,
  [ADR-0014](0014-reviewer-output-is-a-claim-until-judge-evidence-agrees.md),
  [ADR-0015](0015-failure-shape-is-the-independent-evidence.md),
  [ADR-0016](0016-reviewer-accuracy-is-measured-with-labelled-wrong-answers.md)

## 맥락

`gradle evalReviewer` 를 **처음으로 실제 모델에 대고 돌렸다**(ADR-0016 의 하네스).
그때까지 이 저장소에서 Reviewer 는 코드만 있고 한 번도 켜진 적이 없었다.

```
케이스 25건 · 쓸 수 있는 분석 25건 (분석이 오지 않음 0 · 검증 탈락 0)
primary 일치  24/25
한 번의 분석만으로 확정(§21-A)  4건 — 전부 라벨과 일치
[OK] 오확정 0건
```

유일한 불일치가 이것이었다.

```
P01_QUEUE_BASIC   심어둔 실수 OUTPUT_FORMAT   모델의 답 BOUNDARY_CHECK (0.85)
```

P01 은 큐 문제다. **격자가 없다.** `BOUNDARY_CHECK` 는 격자 경계를 놓치는 실수이고,
P01 의 `commonMistakes` 는 `INPUT_PARSE` 와 `OUTPUT_FORMAT` 둘뿐이다.

### 이번에는 막혔다. 그런데 그 이유가 우연이었다

§21-A 가 막았다 — P01 의 case 에 `BOUNDARY_CHECK` 를 겨냥한 `probes` 태그가 없으니
실패의 모양이 뒷받침하지 못했고, PROBABLE 에 머물렀다.

**§21-B 는 그 검사를 거치지 않는다.** confidence 0.80 이상에 최근 3문제에서 2회면
확정된다. 같은 오분류가 두 번 나오면 그대로 확정되고, `BOUNDARY_CHECK` 는
`auto_drill` 이 켜져 있어 **하지도 않은 실수로 드릴에 보내진다.**

확인해 보니 `commonMistakes` 는 **백엔드 코드 어디에서도 읽히지 않고 있었다.**
CI 는 `cases.json` 의 `probes` 가 그 목록 밖을 겨냥하면 막는데, Reviewer 의 런타임
주장은 같은 검사를 받지 않았다 — **데이터에는 있고 런타임에는 없는 규칙**이다.

## 결정

**그 문제의 `commonMistakes` 에 없는 실수는 확정하지 않는다.**

`commonMistakes` 는 문제를 만든 사람이 "여기서는 이런 것이 틀린다" 고 적은 것이고,
빈말이 아니다 — `wrong.py` 와 `probes/<MISTAKE>.py` 가 실제로 그 실수를 담고 있는지
CI 가 채점해서 확인한다(ADR-0007, ADR-0015). 그 목록 밖의 주장은 **일어날 수 없다고
선언된 것**이다.

이것은 `CaseCorroboration` 과 같은 자리에 놓인다. 둘 다 Reviewer 가 만들지 않았고,
이 분석 이전에 정해져 있다(ADR-0014).

### 기록은 남긴다

지우지 않는다. 재발 집계와 **Reviewer 정확도를 재는 라벨**이 거기서 나온다.
상태는 confidence 구간에 따라 `POSSIBLE` 또는 `LOGGED_ONLY` 로 남는다 — 자동 드릴은
`CONFIRMED` 에서만 나가므로 학습 경로에는 닿지 않는다.

### 재발 집계에서도 뺀다

확정을 그 자리에서 막는 것만으로는 부족하다. **행이 남아 있으면 나중에 다른 문제에서
같은 실수가 나올 때 §21-B 의 재발 횟수를 채워 준다** — 일어날 수 없다고 선언된 탐지가
남의 확정을 앞당기는 셈이다.

그래서 `mistake_detections.declared_for_problem` 을 두고(V10), 재발 질의가 그것을
본다. 기존 행은 `true` 로 둔다 — 그때는 이 구분이 없었고, 소급해서 "선언되지
않았다" 고 말할 근거가 없다. **없는 값을 지어내지 않는다.**

### 평가 하네스도 같은 규칙을 쓴다

`ReviewerEvaluation` 이 `MistakeConfirmation` 을 그대로 부른다. 여기서만 빼면
**하네스가 재는 확정이 실제 경로와 달라진다** — 설정을 한 파일에서 읽는 것과 같은
이유다(ADR-0016).

## 무엇을 하지 않았는가

**분석을 통째로 버리지 않는다.** 그것은 `ReviewerOutputValidator` 의 몫이고, 거기
걸리면 secondary 까지 함께 버린다(ADR-0014). 여기서 다루는 것은 "형식은 맞지만 이
문제에서는 일어날 수 없는 주장" 이며, 그것은 버릴 것이 아니라 **믿지 않을 것**이다.

**`commonMistakes` 가 완전하다고 가정하지 않는다.** 빠진 실수가 있을 수 있다. 그래서
확정만 막고 기록은 남긴다 — 나중에 정확도를 재면서 "목록 밖인데 실제로 맞았다" 가
쌓이면 그때 목록을 고치면 된다. 그 판단의 근거가 사라지지 않게 두는 것이 이 설계다.

## 결과

- 확정이 처음으로 "그 문제에서 가능한 일인가" 를 본다
- `ProblemCatalog` 가 `commonMistakes` 를 읽는다. 백엔드가 이 데이터를 쓰는 첫 자리다
- 대조군: 이 검사를 되돌리면 B 경로 테스트가 실패한다

## 곁가지로 드러난 것

이 PR 의 `V10` 이 **처음으로 두 자리 버전**이었고, Judge Worker 테스트가 그 자리에서
깨졌다.

```python
for path in sorted(MIGRATIONS.glob("V*.sql")):   # V10 -> V1 -> V2 ...
```

문자열로 정렬하고 있었다. V9 까지는 문자열 순서와 숫자 순서가 **우연히 같았고**,
V10 이 생기자 갈라져 아직 만들어지지 않은 표를 `ALTER` 했다.

그 하네스가 백엔드와 같은 마이그레이션을 쓰는 이유는 "정본이 둘이 되지 않게" 인데,
순서가 다르면 그 목적 자체가 무너진다. 숫자로 정렬하도록 고치고, 그 사실을 보는
검사를 함께 넣었다 — **우연히 맞던 것이 언제 틀리기 시작하는지는 그때가 되어야
보인다.**

### 남는 위험

- **secondary 는 원래 확정 대상이 아니다.** 이 규칙은 primary 에만 의미가 있다
- 목록이 빈 문제가 있으면 그 문제에서는 아무것도 확정되지 않는다. 지금은 모든 문제가
  `commonMistakes` 를 갖고 CI 가 그것을 요구하지만, 그 요구가 사라지면 조용히
  확정이 멈춘다
- **한 번의 평가로 정한 규칙이다.** 25건 중 1건이 근거이고, 그 1건이 대표적인지는
  더 재 봐야 안다. 다만 이 방향의 오류(없는 실수를 확정)는 되돌릴 수 없는 쪽이라
  먼저 닫았다

## 관련

- [ADR-0015](0015-failure-shape-is-the-independent-evidence.md) — 같은 자리의 다른 근거
- [ADR-0016](0016-reviewer-accuracy-is-measured-with-labelled-wrong-answers.md) — 이 결정을 만든 평가
