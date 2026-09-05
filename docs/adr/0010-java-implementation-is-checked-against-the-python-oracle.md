# ADR-0010 · Java 구현은 Python oracle과 golden fixture로 대조한다

- 상태: 채택
- 날짜: 2026-09-05
- 정본 근거: [ADR-0009](0009-mastery-is-recomputed-from-evidence.md) / PRD §98

## 맥락

[ADR-0009](0009-mastery-is-recomputed-from-evidence.md)에서 Mastery 산식을 Python으로
구현하며 "나중에 Java 구현이 생기면 이쪽을 기준 oracle로 삼는다"고 적었다. 그 시점이
왔다.

문제는 **옮긴 것이 맞는지 어떻게 아는가**다. Addendum PART I을 사람이 읽고 Java로
다시 쓰면 확인할 방법이 없다. 산식이 단순 CRUD가 아니라 규칙이 여럿 얽혀 있어서 더 그렇다.

```text
Evidence 정렬 (시간대까지)
EMA (첫 관측은 예외)
평가되지 않은 차원의 재정규화
confidence 누적
독립 풀이 판정 (힌트 4단계 / 정답 확인)
MASTERED / WEAKENED 전이 (직전 상태에 의존)
같은 원천 이벤트의 멱등성
```

이 중 하나만 어긋나도 **점수는 그럴듯하게 나온다.** 틀렸다는 것을 알아차릴 방법이 없다.

## 결정

**두 구현이 같은 golden fixture를 읽고 같은 값을 내는지 CI가 대조한다.**

```text
tests/golden/*.json        ← 두 구현이 같은 파일을 읽는다
  ├─ evidences   입력
  └─ expected    Python oracle이 낸 값

tools/gen_mastery_golden.py       Python 산식이 golden과 일치하는가
backend/.../MasteryGoldenTest     Java 산식이 golden과 일치하는가
```

형식은 `contracts/mastery-golden.schema.json`으로 고정한다. 저장소의 다른 계약과 같은
취급이다 — 문서가 아니라 검증되는 데이터다.

## 생성이 아니라 검증이 기본이다

`gen_mastery_golden.py`는 기본 동작이 **검증**이고, 생성은 `--write`를 붙여야 한다.

산식을 고치면 golden이 조용히 따라 바뀌어서는 안 된다. 그러면 golden은 "현재 구현이
내는 값"을 받아적기만 하는 파일이 되어 아무것도 고정하지 못한다. 값이 달라지면 CI가
멈추고, 사람이 그 변화가 의도한 것인지 보고 갱신한다.

## 케이스는 분기마다 하나씩 둔다

값 하나만 고정하면 다른 분기가 갈려도 알 수 없다. 지금 17건이며 산식의 각 갈림길에
대응한다 — 재정규화, 힌트 단계, 정답 확인, TLE 원인 구분, recognition 모드 게이팅,
복습 retention, MASTERED 진입/유지/이탈, Skill 비중, 드릴 가중치, 시간대 섞임, 재시도 중복.

**이 대조가 실제로 일하는지도 확인했다.** `Dimension.INDEPENDENT`의 가중치를
0.25 → 0.30으로 바꾸자 17건 중 12건이 실패했다. 두 구현이 갈리면 잡힌다.

## 반올림을 맞춰야 했다

Python의 `round()`는 banker's rounding이다. Java의 기본 반올림과 붙으면 마지막 자리가
갈릴 수 있어 `BigDecimal.setScale(4, HALF_EVEN)`으로 맞췄다.

이건 ADR-0009에서 "Java로 옮길 때 부동소수 반올림 때문에 값이 갈릴 수 있다"고 적어둔
리스크가 실제로 나타난 지점이다. golden 대조가 없었으면 미세하게 다른 값이 그대로
쌓였을 것이다.

## 결과

**얻는 것**

- Java 구현이 명세를 지키는지 기계가 확인한다. "읽고 옮겼다"가 아니라 대조된다.
- 산식을 고칠 때 두 구현을 함께 고치도록 강제된다. 한쪽만 고치면 CI가 막는다.
- Python oracle이 버려지지 않는다. Addendum PART I의 실행 가능한 사본으로 남는다.

**치르는 비용**

- 산식을 고칠 때 세 곳을 만진다 — Python, Java, golden 재생성.
- golden이 커지면 재생성 diff를 사람이 읽기 어려워진다. 지금은 17건이라 괜찮다.
- 두 구현을 유지하는 비용이 계속 든다. **언젠가 Python 쪽을 접는 판단이 필요하다** —
  Java 구현이 충분히 안정되고 Decision Engine까지 Java로 옮겨간 뒤가 그 시점이다.

## 언제 Python oracle을 접는가

지금은 접지 않는다. 하지만 영원히 두 벌을 유지할 이유도 없다.

접어도 되는 조건은 "golden fixture가 산식의 분기를 충분히 덮고, 그것이 Java 테스트로
그대로 남을 때"다. 그때 `learning/`은 지우고 `tests/golden/`은 남긴다 — 고정하려던 것은
Python 코드가 아니라 **값**이었기 때문이다.

## 관련

- [ADR-0009](0009-mastery-is-recomputed-from-evidence.md) — 이 대조가 필요한 이유
- [ADR-0011](0011-language-boundary.md) — 어느 쪽이 무엇을 맡는가
