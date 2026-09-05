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

## EMA는 순서에 의존한다

재계산이 결정론적이려면 접는 순서가 고정돼야 한다. `occurredAt`으로 정렬한다.

테스트가 이것을 확인한다 — 같은 Evidence 목록을 섞어 넣어도 결과가 같아야 한다.
정렬을 빠뜨리면 이 테스트가 깨진다.

같은 시각에 두 Evidence가 들어오면 순서가 불안정해진다. 지금은 문제가 없지만
(제출은 사람이 하는 행위라 초 단위 충돌이 드물다) 실제 저장 시에는 `(occurredAt, id)`로
정렬해야 한다 — DB 스키마를 만들 때의 확인 항목이다.

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
