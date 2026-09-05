# Learning

Evidence 로부터 `mastery` / `confidence` / `status` 를 계산한다.
Addendum PART I 을 코드로 옮긴 것이며, **LLM 은 여기 관여하지 않는다**(ADR-0001).

```text
evidence.py   관측 -> Evidence         Addendum 11~16 의 표를 그대로 옮겼다
mastery.py    Evidence -> SkillState   EMA / 재정규화 / confidence / status
tests/        Addendum 84 가 요구하는 9항목 + 재정규화 · 계약 · 결정론
```

## Evidence 가 정본이다

`mastery` 는 저장된 값이 아니라 **Evidence 로부터 재계산되는 파생값**이다(ADR-0009).

```text
skill_evidence  (append-only, 불변)     <- 정본
        ↓ occurredAt 정렬 후 EMA 로 접는다
user_skills     (재계산 가능한 캐시)     <- 파생
```

EMA 는 되돌릴 수 없다. `0.90*0.82 + 0.30*0.18 = 0.792` 에서 원래 값을 복원할 수 없으므로,
저장된 값이 정본이면 산식을 고쳐도 과거에 적용할 수 없고 버그로 오염된 값도 남는다.

그래서 이 모듈의 함수는 전부 순수 함수다.

```python
from learning.mastery import recompute
state = recompute(evidences, "BFS_GRID_TRAVERSAL")
```

## 세 가지를 구분한다

| | 뜻 | 예 |
| --- | --- | --- |
| `null` | 아직 평가하지 않았다 | 신규 Skill 의 모든 차원 |
| `0.0` | 평가했고 거의 못한다 | 진단에서 전부 틀렸다 |
| `mastery` vs `confidence` | 얼마나 잘하는가 vs 그 판단을 얼마나 믿는가 | 0.85 / 0.20 = 잘해 보이지만 증거가 적다 |

평가되지 않은 차원을 0 으로 두면 안 된다. "아직 안 봤다" 가 "못한다" 로 둔갑하고
초반 mastery 가 부당하게 낮아진다. 평가된 차원만으로 계산하고 **가중치를 재정규화**한다
(Addendum 6).

## 값은 전부 정본 문서에서 온다

`evidence.py` 의 표는 임의로 고른 값이 아니다. 힌트 단계별 관측값, EMA 의 alpha,
confidence 가중치, 복습 간격별 retention 이 전부 Addendum PART I 에 있다.
고칠 때는 문서를 함께 고친다 - 값 하나가 mastery 를 통해 학습 경로를 바꾼다.

## 특히 주의할 것

**COMPILE_ERROR 는 Evidence 를 만들지 않는다.** 문법 오류를 알고리즘 Skill 의
penalty 로 쓰면 안 된다(Addendum 12). `from_submission` 이 `None` 을 돌려준다.
`SYSTEM_ERROR` 도 마찬가지다 - 우리 잘못을 사용자 점수에 반영하지 않는다.

**정답을 본 뒤의 AC 는 Mastered 판정에 쓰지 않는다**(PRD 143-4, Addendum 11.7).
힌트를 4단계 이상 쓴 AC 도 "최근 독립 풀이" 로 세지 않는다(Addendum 22).

**speed 는 정답일 때만 갱신한다**(Addendum 13). 틀린 답을 빨리 낸 것은 빠른 것이 아니다.

**recognition 은 유형을 알려주지 않은 모드에서만 관측한다**(Addendum 15).
GUIDED 모드에서 "BFS 를 쓰세요" 라고 알려준 뒤 BFS 를 썼다고 알아본 것이 아니다.

## 왜 Python 인가

PRD 98 은 백엔드를 Spring Boot 로 권한다. 이 산식을 지금 Java 로 쓰려면 Gradle · JPA ·
DB 가 먼저 있어야 하고 그건 이 단계의 범위가 아니다.

그래서 **실행 가능한 명세**로 여기 뒀다. 나중에 Java 구현이 생기면 이쪽을 기준
oracle 로 삼아 같은 Evidence 에 같은 값이 나오는지 대조할 수 있다 - 버려지는 코드가
아니라 테스트 오라클로 남는다. 근거: ADR-0009.

## 아직 없는 것

- **Decision Engine.** `nextAction` 은 이 계산의 결과와 Mistake 이력을 함께 본다
  (ADR-0002). 다음 단계다
- `LOCKED` / `READY` 판정. 선수 관계에서 나오므로 Evidence 만으로는 결정되지 않는다
- 복습 일정 계산 (Addendum 79 / PRD 79)
- 스냅샷. Evidence 가 수백 건을 넘으면 재계산이 느려진다(ADR-0009 의 비용)

## 검증

```bash
python learning/tests/test_mastery.py
```
