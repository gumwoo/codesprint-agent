# ADR-0009 · mastery는 저장된 값이 아니라 Evidence로부터 재계산되는 값이다

- 상태: 채택
- 날짜: 2026-09-05
- 정본 근거: Addendum §7~10, §24, §73~74 / [ADR-0001](0001-llm-analyzes-system-decides.md)

## 맥락

Addendum §24의 pseudocode는 이렇게 되어 있다.

```text
function updateSkill(skill, evidence):
    skill.dimension = skill.dimension * (1 - alpha) + observed * alpha
    skill.evidenceCount += 1
    save(skill)
```

읽는 그대로 구현하면 `user_skills` 행이 정본이 된다. Evidence가 들어올 때마다 그 행을
제자리에서 갱신하고, 과거 Evidence는 참고 기록으로 남는다.

문제는 EMA가 **되돌릴 수 없다**는 점이다.

`0.90 * 0.82 + 0.30 * 0.18 = 0.792` 에서 원래 값 `0.90`을 복원할 수 없다. 그래서
저장된 값이 정본이면 다음이 전부 불가능해진다.

- **산식을 고쳐도 과거에 적용할 수 없다.** 가중치 하나를 바꾸면 그날 이후 사용자만
  새 산식을 적용받고, 기존 사용자는 옛 산식으로 만들어진 값을 계속 들고 간다.
- **버그를 고쳐도 오염된 값이 남는다.** PR #3에서 이미 겪었다 — 틀린 문제 하나가
  `independent_score`를 0.95로 올려놓으면 그 흔적을 지울 방법이 없다.
- **왜 이 점수인지 설명할 수 없다.** 사용자에게도, 우리에게도.

이 저장소는 [ADR-0001](0001-llm-analyzes-system-decides.md)에서 "점수는 결정론적이어야
한다"를 근거로 LLM에게 점수를 묻지 않기로 했다. 그런데 시스템이 계산하더라도 **되돌릴 수
없는 방식으로 누적하면** 결정론의 이득 절반을 잃는다. 재현할 수 없기 때문이다.

## 결정

**Evidence가 정본이고, `mastery`는 그로부터 재계산된 파생값이다.**

```text
skill_evidence  (append-only, 불변)        ← 정본
        ↓ occurredAt 정렬 후 EMA 로 접는다
user_skills     (재계산 가능한 캐시)        ← 파생
```

`learning/mastery.py`의 함수는 전부 순수 함수이고 상태를 갖지 않는다.

```python
state = recompute(evidences, skill_code)
```

`user_skills` 행은 **캐시**다. 조회 성능을 위해 존재하며, 언제든 Evidence로부터 다시
만들 수 있다. 두 값이 어긋나면 Evidence가 이긴다.

## Evidence는 (sourceEventId, skillCode)로 유일해야 한다

Evidence가 정본이고 append-only라면 **같은 것이 두 번 들어오는 경우**를 정의해야 한다.
앞으로 붙일 Judge Worker와 큐(Addendum §67~69)에서 재시도는 정상적인 동작이다.

```text
Submission #1042 처리 → Evidence 생성
Worker 재시도        → 같은 Evidence 또 생성
```

그대로 접으면 EMA가 두 번 적용되고 confidence도 두 번 오른다. **재시도가 사용자의
점수를 바꾼다.**

그래서 Evidence에 두 식별자를 둔다.

| | 역할 |
| --- | --- |
| `sourceEventId` | **멱등성 키.** `submission:1042` 처럼 원천 이벤트를 가리킨다 |
| `evidenceId` | Evidence 자체의 식별자. `(sourceEventId, skillCode)` 에서 결정론적으로 파생 |

한 제출이 Skill 3개에 Evidence를 남기면 `sourceEventId`는 같고 `skillCode`가 다르다.
따라서 유일성은 두 값의 짝이다. DB에서는 `UNIQUE(source_event_id, skill_code)`가 된다.

**`evidenceId`를 무작위로 만들지 않는다.** UUID를 쓰면 같은 Evidence를 두 번 만들 때
서로 다른 id가 붙어, 재계산 결과가 입력에 따라 달라진다 — 이 ADR이 지키려는 결정론이
깨진다. 파생값이면 같은 원천에서 항상 같은 id가 나온다.

**재계산도 스스로 방어한다.** DB 제약이 일차 방어선이지만, `recompute()`도 중복 키를
걸러낸다. Evidence가 정본이라면 그것을 접는 쪽이 정합성을 책임져야 한다.

### 다만 중복을 조용히 버리지 않는다

같은 키인데 **내용이 다르면** 재시도가 아니라 데이터가 깨진 것이다. 하나를 골라 버리면
어느 것이 남는지가 입력 순서에 달린다.

```text
submission:100 → ACCEPTED
submission:100 → WRONG_ANSWER

recompute([a, b])  mastery 0.925
recompute([b, a])  mastery 0.275
```

같은 Evidence 집합인데 결과가 갈렸다. 이 ADR이 지키려는 것을 정확히 깨는 경우다.
그래서 내용이 다른 중복은 `ValueError`로 거부한다 — 조용히 넘어가면 어느 관측이 진짜인지
아무도 모르는 채로 점수가 만들어진다.

같은 이유로 **관측값이 하나도 없는 Evidence**도 거부한다. 그런 Evidence는 mastery를
바꾸지 않으면서 `confidence`와 `evidenceCount`만 올린다. 측정한 게 없는데 측정
신뢰도가 오르고, 그 상태로 `MASTERED` 문턱(`confidence >= 0.60`)을 넘을 수 있다.

실제로 오타 난 judge status(`WRONGANSWER`)가 그런 Evidence를 만들었다. 지금은
`from_submission()`이 `judge-result.schema.json`의 enum에 없는 status를 거부하고,
테스트가 두 목록이 같은지 대조한다.

## append-only는 말이 아니라 강제여야 한다

"Evidence가 정본이고 append-only다"는 이 ADR의 전제인데, 처음 구현에서는 그것을
**아무것도 강제하지 않았다.** 엔티티에 setter를 두지 않은 것이 근거였지만 그건 평범한
코드에서 필드를 바꾸기 어렵게 할 뿐이다.

```text
UPDATE skill_evidence SET observed_implementation = 0.1 → UPDATE 1
DELETE FROM skill_evidence WHERE id = 1                 → DELETE 1
```

Evidence 하나가 사라지면 과거를 다시 접었을 때 다른 mastery가 나온다. 그러면 다른
status, 다른 Decision으로 이어진다. `user_skills`는 캐시라 다시 만들면 되지만 이쪽은
복원할 방법이 없다 — **이 ADR이 얻으려던 것 전부가 무너진다.**

세 층에서 막는다.

| 층 | 막는 것 |
| --- | --- |
| 엔티티 | setter 없음 |
| Repository | `JpaRepository` 미상속. `delete*` API 자체가 없다 |
| DB 트리거 | `UPDATE` / `DELETE` 거부 |

**남은 것**: 사용자 데이터 삭제(탈퇴, GDPR)는 이 트리거를 우회해야 한다. 권한을 가진
경로에서 트리거를 잠시 끄고 감사 기록을 남기는 방식이 필요하며 아직 없다.

## EMA는 순서에 의존한다

재계산이 결정론적이려면 접는 순서가 고정돼야 하고, **그 순서가 실제 시간순과 같아야
한다.** `occurredAt`을 UTC로 바꾼 뒤 정렬한다.

문자열로 정렬하면 안 된다. ISO-8601의 사전순은 실제 시간순과 다르다.

```text
A = 2026-09-05T10:00:00+09:00   실제 UTC 01:00
B = 2026-09-05T01:30:00Z        실제 UTC 01:30

실제 순서   A → B
문자열 정렬 B → A
```

나중 일이 먼저 접혀 EMA가 거꾸로 쌓인다. 실제로 이 예에서 mastery가 0.808이어야 하는데
0.392가 나왔다. 결과는 여전히 결정론적이지만 **틀린 값에 대해 결정론적**이다 —
재현 가능한 것과 옳은 것은 다르다.

시간대가 없는 값은 거부한다. 지금은 한 곳에서만 시각을 만들지만, Spring / DB /
Judge Worker가 각자 만들기 시작하면 naive timestamp가 어느 지역 것인지 알 방법이 없다.
그때 고치려면 이미 쌓인 Evidence를 전부 해석해야 한다 — Evidence가 정본인 설계에서
가장 비싼 종류의 마이그레이션이다.

테스트가 이것을 확인한다 — 같은 Evidence 목록을 섞어 넣어도 결과가 같아야 한다.
정렬을 빠뜨리면 이 테스트가 깨진다.

같은 시각에 두 Evidence가 들어오면 순서가 불안정해지므로 `(occurredAt, evidenceId)`로
정렬한다. `evidenceId`가 결정론적 파생값이라 이 정렬도 결정론적이다.

## 결과

**얻는 것**

- **산식을 고치면 과거를 다시 접어 바로잡을 수 있다.** 가중치 조정, 버그 수정,
  taxonomy 확장이 소급 적용된다.
- **감사할 수 있다.** "이 Skill이 왜 0.72인가"에 Evidence 목록으로 답한다.
- **A/B 비교가 가능하다.** 같은 Evidence에 두 산식을 적용해 결과를 나란히 볼 수 있다.
  Addendum §157의 Knowledge Tracing 도입을 검토할 때 필요한 형태다.
- 순수 함수라 테스트가 쉽다. DB도 시간도 필요 없다.

**치르는 비용**

- Evidence가 무한히 쌓인다. 사용자당 Skill당 수백 건이면 재계산이 느려진다.
  **스냅샷**(N건마다 중간 상태를 저장하고 이후만 접는 방식)이 필요해지는 시점이 온다.
  지금은 필요 없다 — 슬라이스 1의 Evidence는 사용자당 수십 건이다.
- 저장 공간이 늘고, Evidence 스키마 자체가 계약이 된다. 필드를 잘못 만들면 과거
  Evidence를 새 산식으로 접을 수 없다. 그래서 `context`에 판단 근거를 전부 남긴다.

## 왜 Python으로 구현했는가

PRD §98은 백엔드를 Spring Boot로 권한다. 그런데 이 산식을 지금 Java로 쓰려면 Gradle,
JPA, DB 스키마가 먼저 있어야 한다. 그건 이 PR의 범위가 아니다.

그래서 **실행 가능한 명세**로 Python에 먼저 뒀다.

- 저장소의 다른 하네스와 언어가 같다. CI에 그대로 붙는다
- 순수 함수라 DB 없이 산식만 검증할 수 있다
- 나중에 Java 구현이 생기면 **이쪽을 기준 oracle로 삼아 대조**할 수 있다. 같은
  Evidence 목록에 두 구현이 같은 값을 내는지 보는 differential test가 가능하다

즉 Java로 옮길 때 이 코드가 버려지는 것이 아니라 **테스트 오라클로 남는다.**
그때까지는 이 파일이 Addendum PART I의 실행 가능한 사본 역할을 한다.

## MASTERED에서 나가는 길은 열거돼 있다

Addendum §23은 `MASTERED → WEAKENED` 전환을 세 조건으로 **열거**한다. 그 밖의 이유로
점수가 조금 내려갔다고 강등하지 않는다.

처음 구현은 그렇지 않았다. `mastery >= 0.80` 을 매번 다시 확인했기 때문에, 힌트를
보며 푼 실패들이 EMA로 점수를 끌어내리면 조용히 `PRACTICING`으로 떨어졌다. 독립 풀이를
시도한 적이 없는데도 "완료" 표시가 사라지고 복습 우선순위도 잃는다.

`WEAKENED`는 "됐었는데 지금은 아니다"라는 뜻이라 복습 대상이 되지만, `PRACTICING`은
그냥 진행 중이다. 둘을 섞으면 Decision Engine이 다르게 반응한다.

세 번째 조건("동일 핵심 Mistake 2회 반복")은 Reviewer가 붙어야 판단할 수 있어 아직
구현하지 않았다.

## 상태 전환에서 정하지 않은 것

Addendum은 `MASTERED`(§22)와 `WEAKENED`(§23)만 정의한다. `LEARNING` / `PRACTICING`의
경계(0.50)는 여기서 정했고 근거가 약하다 — 실제 사용자 분포를 보고 조정할 값이다.

`LOCKED` / `READY`는 **선수 관계에서 나오므로 이 계산이 다루지 않는다.** Evidence만
봐서는 "앞 Skill을 충분히 했는가"를 알 수 없다. `prerequisites.yaml`과 함께 보는
별도 계산이며, Decision Engine 쪽에 속한다.

## 관련

- [ADR-0001](0001-llm-analyzes-system-decides.md) — 점수는 시스템이 계산한다
- [ADR-0005](0005-judge-stops-at-first-failure.md) — 이 Evidence의 입력이 되는 판정
- [ADR-0007](0007-problems-are-verified-by-a-wrong-solution.md) — 오염된 Evidence를 만들지 않기 위한 문제 검증
